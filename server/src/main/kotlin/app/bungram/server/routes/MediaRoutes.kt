package app.bungram.server.routes

import app.bungram.server.media.MediaNotFoundException
import app.bungram.server.media.MediaRecord
import app.bungram.server.media.MediaSigner
import app.bungram.server.media.MediaStore
import app.bungram.server.media.QuotaExceededException
import app.bungram.server.model.AttachmentDto
import app.bungram.server.model.Envelope
import app.bungram.server.model.ErrorBody
import app.bungram.server.model.MediaDto
import app.bungram.server.model.MediaQuotaDto
import app.bungram.server.model.ShareLinkDto
import app.bungram.server.security.JwtService
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.request.httpMethod
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.utils.io.jvm.javaio.toInputStream

/* ---------------------------------------------------------------------------------------------
 * Media pipeline, phase 2.
 *
 * Upload  : multipart, streamed to disk, hashed on the way through, de-duplicated per owner.
 * Download: auth header OR signed URL, ETag + immutable cache headers, Range requests via the
 *           PartialContent plugin (video seeking needs this).
 * Thumbs  : generated server-side at 320px so a chat list never downloads a 12 MB photo.
 *
 * Evidence for the design (REA, static decompilation of the reference APKs):
 *   · Telegram  FileLoader — 172 members: addOperationToQueue, changePriority, checkDownloadQueue,
 *               checkUploadNewDataAvailable, checkUploadFileSize → a *priority* upload/download
 *               queue with resumable chunks, not a single blocking request.
 *   · Signal    AttachmentUploadJob — getMaxPlaintextSize, getNextRunAttemptBackoff,
 *               handleUploadError, onShouldRetry, resetProgressListeners → uploads are durable
 *               jobs with exponential backoff and a hard plaintext size ceiling.
 *   · Element X AndroidMediaPreProcessor + DefaultMaxUploadSizeProvider → resize/compress on the
 *               device *before* the bytes leave it, bounded by a server-advertised maximum.
 * Bungram takes the union: client-side compression, durable retrying upload, server-enforced
 * quota, priority-free simplicity (one FIFO outbox) — implemented from scratch.
 * ------------------------------------------------------------------------------------------- */

private fun ApplicationCall.bearer(): String? =
    request.headers[HttpHeaders.Authorization]
        ?.removePrefix("Bearer ")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

private fun ApplicationCall.uid(jwt: JwtService): String? = bearer()?.let { jwt.userId(it) }

private fun MediaRecord.toDto(): MediaDto = MediaDto(
    id = id,
    url = "/v1/media/$id",
    thumbUrl = if (hasThumb) "/v1/media/$id/thumb" else null,
    mimeType = mimeType,
    fileName = fileName,
    sizeBytes = sizeBytes,
    width = width,
    height = height,
    durationMs = durationMs,
    sha256 = sha256,
    colorHint = colorHint,
    createdAtEpochMs = createdAtEpochMs,
)

/** Turns a stored blob into the attachment block embedded in a message. */
fun MediaStore.attachmentFor(mediaId: String): AttachmentDto? {
    val rec = record(mediaId) ?: return null
    return AttachmentDto(
        url = "/v1/media/${rec.id}",
        thumbUrl = if (rec.hasThumb) "/v1/media/${rec.id}/thumb" else null,
        mediaId = rec.id,
        mimeType = rec.mimeType,
        fileName = rec.fileName,
        sizeBytes = rec.sizeBytes,
        width = rec.width,
        height = rec.height,
        durationMs = rec.durationMs,
        colorHint = rec.colorHint,
        sha256 = rec.sha256,
    )
}

fun Route.mediaRoutes(media: MediaStore, signer: MediaSigner, jwt: JwtService, store: app.bungram.server.store.Store) {

    /* ------------------------------------------------------------------------------- upload */

    post("/v1/media") {
        val uid = call.uid(jwt)
            ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorBody(error = "missing token"))

        var temp: java.io.File? = null
        var mime = "application/octet-stream"
        var fileName: String? = null
        var width = 0
        var height = 0
        var durationMs = 0L

        try {
            call.receiveMultipart().forEachPart { part ->
                when (part) {
                    is PartData.FileItem -> {
                        mime = part.contentType?.toString()?.substringBefore(';') ?: mime
                        fileName = part.originalFileName
                        // Stream to disk: a 90 MB video must never sit in the heap.
                        val target = media.newTempFile()
                        part.provider().toInputStream().use { input ->
                            target.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
                        }
                        temp?.delete()
                        temp = target
                    }
                    is PartData.FormItem -> when (part.name) {
                        "width" -> width = part.value.toIntOrNull() ?: 0
                        "height" -> height = part.value.toIntOrNull() ?: 0
                        "durationMs" -> durationMs = part.value.toLongOrNull() ?: 0L
                        "mimeType" -> if (part.value.isNotBlank()) mime = part.value
                        "fileName" -> if (part.value.isNotBlank()) fileName = part.value
                    }
                    else -> Unit
                }
                part.dispose()
            }

            val file = temp
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorBody(error = "no file part"))

            val rec = file.inputStream().use {
                media.store(uid, it, mime, fileName, width, height, durationMs)
            }
            call.respond(Envelope(data = rec.toDto()))
        } catch (e: app.bungram.server.media.EmptyUploadException) {
            call.respond(HttpStatusCode.BadRequest, ErrorBody(error = "empty upload"))
        } catch (e: QuotaExceededException) {
            call.respond(
                HttpStatusCode.PayloadTooLarge,
                ErrorBody(error = "storage full — ${e.quota.usedPercent}% of ${e.quota.limitBytes / 1_048_576} MB used"),
            )
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.PayloadTooLarge, ErrorBody(error = e.message ?: "rejected"))
        } finally {
            temp?.delete()
        }
    }

    /* ----------------------------------------------------------------------------- download */

    get("/v1/media/quota") {
        val uid = call.uid(jwt) ?: return@get call.respond(HttpStatusCode.Unauthorized, ErrorBody(error = "missing token"))
        val q = media.quota(uid)
        call.respond(
            Envelope(
                data = MediaQuotaDto(
                    usedBytes = q.usedBytes,
                    limitBytes = q.limitBytes,
                    fileCount = q.fileCount,
                    maxFileBytes = media.maxFileBytes(),
                )
            )
        )
    }

    get("/v1/media/{id}") {
        val id = call.parameters["id"].orEmpty()
        val rec = media.record(id)
            ?: return@get call.respond(HttpStatusCode.NotFound, ErrorBody(error = "no such media"))
        if (!call.authorizedFor(rec, jwt, signer, store)) {
            return@get call.respond(HttpStatusCode.Forbidden, ErrorBody(error = "not allowed"))
        }
        val file = runCatching { media.file(id) }.getOrNull()
            ?: return@get call.respond(HttpStatusCode.NotFound, ErrorBody(error = "blob missing"))

        call.response.header(HttpHeaders.ETag, "\"${rec.sha256}\"")
        call.response.header(HttpHeaders.CacheControl, "private, max-age=31536000, immutable")
        call.response.header("Accept-Ranges", "bytes")
        call.respond(LocalFileContent(file, ContentType.parse(rec.mimeType)))
    }

    get("/v1/media/{id}/thumb") {
        val id = call.parameters["id"].orEmpty()
        val rec = media.record(id)
            ?: return@get call.respond(HttpStatusCode.NotFound, ErrorBody(error = "no such media"))
        if (!call.authorizedFor(rec, jwt, signer, store)) {
            return@get call.respond(HttpStatusCode.Forbidden, ErrorBody(error = "not allowed"))
        }
        val file = runCatching { media.thumbFileFor(id) }.getOrNull()
            ?: return@get call.respond(HttpStatusCode.NotFound, ErrorBody(error = "no thumbnail"))
        call.response.header(HttpHeaders.CacheControl, "private, max-age=31536000, immutable")
        call.respond(LocalFileContent(file, ContentType.Image.JPEG))
    }

    /** Mints a 24h signed URL so the file can be shared outside the app. */
    get("/v1/media/{id}/link") {
        val uid = call.uid(jwt) ?: return@get call.respond(HttpStatusCode.Unauthorized, ErrorBody(error = "missing token"))
        val id = call.parameters["id"].orEmpty()
        val rec = media.record(id)
            ?: return@get call.respond(HttpStatusCode.NotFound, ErrorBody(error = "no such media"))
        if (!call.authorizedFor(rec, jwt, signer, store)) {
            return@get call.respond(HttpStatusCode.Forbidden, ErrorBody(error = "not allowed"))
        }
        call.respond(
            Envelope(data = ShareLinkDto(url = "/v1/media/$id?t=${signer.sign(id)}", expiresInSec = 86_400))
        )
    }

    /* ------------------------------------------------------------------------------- delete */

    delete("/v1/media/{id}") {
        val uid = call.uid(jwt) ?: return@delete call.respond(HttpStatusCode.Unauthorized, ErrorBody(error = "missing token"))
        val id = call.parameters["id"].orEmpty()
        val ok = media.delete(id, uid)
        call.respond(Envelope(data = app.bungram.server.model.DeleteAckDto(deleted = ok)))
    }
}

/**
 * A media GET is allowed when:
 *   · the caller presents a valid, unexpired signed URL, or
 *   · the caller holds a valid access token *and* is either the owner or shares a chat with them.
 *
 * The second rule matters: a media id leaks in a forwarded message, and an id alone must never be
 * enough to read someone's file.
 */
private fun ApplicationCall.authorizedFor(
    rec: MediaRecord,
    jwt: JwtService,
    signer: MediaSigner,
    store: app.bungram.server.store.Store,
): Boolean {
    request.queryParameters["t"]?.let { if (signer.verify(rec.id, it)) return true }

    val uid = bearer()?.let { jwt.userId(it) } ?: return false
    if (rec.ownerId == uid) return true
    return store.chats.values.any { chat ->
        val members = store.members[chat.id] ?: return@any false
        members.contains(uid) && members.contains(rec.ownerId)
    }
}
