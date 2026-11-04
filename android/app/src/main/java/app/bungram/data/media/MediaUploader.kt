package app.bungram.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import app.bungram.data.model.MessageKind
import app.bungram.data.remote.Envelope
import app.bungram.data.remote.MediaDto
import app.bungram.data.remote.ServerSettings
import app.bungram.data.remote.TokenStore
import app.bungram.data.remote.Wire
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okio.Buffer
import okio.BufferedSink
import okio.source
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Everything the UI needs after a successful upload. */
data class UploadedMedia(
    val id: String,
    val url: String,
    val thumbUrl: String?,
    val mimeType: String,
    val fileName: String?,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val colorHint: String?,
    val sha256: String,
    /** The compressed file we actually sent — kept so the bubble can render instantly. */
    val localFile: File,
)

class UploadException(message: String) : Exception(message)

/**
 * Device → server media path.
 *
 * Three jobs, in this order:
 *
 *  1. **Shrink before sending.** A 12 MP camera JPEG is ~4 MB; at 1600 px / q85 it is ~300 KB and
 *     visually identical in a chat bubble. Element X does the same thing in
 *     `AndroidMediaPreProcessor` (REA: processImage → processImageWithCompression), and it is the
 *     single biggest lever on mobile data and upload latency.
 *  2. **Stream, never buffer.** The file goes to OkHttp as a [RequestBody] that reads from disk in
 *     64 KB chunks, so a 90 MB video costs 64 KB of heap. Signal's AttachmentUploadJob enforces a
 *     hard `getMaxPlaintextSize`; our ceiling is the server's 100 MB, checked before we start.
 *  3. **Report progress.** The bubble shows a real percentage, because an upload with no feedback
 *     is indistinguishable from a hang.
 */
class MediaUploader(
    private val context: Context,
    private val tokens: TokenStore,
    private val settings: ServerSettings,
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        // Uploads on a slow connection can legitimately take minutes; never abort mid-body.
        .writeTimeout(0, TimeUnit.SECONDS)
        .build()

    private val cacheDir: File
        get() = File(context.cacheDir, "uploads").apply { mkdirs() }

    suspend fun upload(
        uri: Uri,
        kind: MessageKind,
        durationMs: Long = 0L,
        onProgress: (Float) -> Unit = {},
    ): UploadedMedia = withContext(Dispatchers.IO) {
        val token = tokens.access ?: throw UploadException("not signed in")
        val meta = resolve(uri)

        val prepared = when (kind) {
            MessageKind.IMAGE -> runCatching { compressImage(uri) }.getOrElse { copyToCache(uri, meta) }
            else -> copyToCache(uri, meta)
        }

        if (prepared.length() > MAX_BYTES) {
            prepared.delete()
            throw UploadException("file is larger than ${MAX_BYTES / 1_048_576} MB")
        }

        try {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    meta.fileName ?: prepared.name,
                    ProgressBody(prepared, meta.mime, onProgress),
                )
                .addFormDataPart("durationMs", durationMs.toString())
                .build()

            val request = Request.Builder()
                .url("${settings.baseUrl.trimEnd('/')}/v1/media")
                .header("Authorization", "Bearer $token")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val message = runCatching {
                        Wire.json.decodeFromString(Envelope.serializer(MediaDto.serializer()), text).error
                    }.getOrNull()
                    throw UploadException(message ?: "upload failed (HTTP ${response.code})")
                }
                val envelope = Wire.json.decodeFromString(Envelope.serializer(MediaDto.serializer()), text)
                val dto = envelope.data ?: throw UploadException(envelope.error ?: "empty upload reply")

                onProgress(1f)
                UploadedMedia(
                    id = dto.id,
                    url = dto.url,
                    thumbUrl = dto.thumbUrl,
                    mimeType = dto.mimeType,
                    fileName = dto.fileName ?: meta.fileName,
                    sizeBytes = dto.sizeBytes,
                    width = dto.width,
                    height = dto.height,
                    durationMs = if (dto.durationMs > 0) dto.durationMs else durationMs,
                    colorHint = dto.colorHint,
                    sha256 = dto.sha256,
                    localFile = prepared,
                )
            }
        } catch (t: Throwable) {
            if (t !is UploadException) prepared.delete()
            throw if (t is UploadException) t else UploadException(t.message ?: "network error")
        }
    }

    /* ------------------------------------------------------------------------------ source */

    private data class Meta(val mime: String, val fileName: String?, val size: Long)

    private fun resolve(uri: Uri): Meta {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        var name: String? = null
        var size = 0L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val si = c.getColumnIndex(OpenableColumns.SIZE)
                        if (ni >= 0) name = c.getString(ni)
                        if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                    }
                }
        }
        return Meta(mime, name, size)
    }

    private fun copyToCache(uri: Uri, meta: Meta): File {
        val suffix = meta.fileName?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() }?.let { ".$it" }
            ?: extensionFor(meta.mime)
        val target = File(cacheDir, "up_${UUID.randomUUID()}$suffix")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
        } ?: throw UploadException("cannot read the selected file")
        return target
    }

    private fun extensionFor(mime: String): String = when {
        mime.contains("jpeg") -> ".jpg"
        mime.contains("png") -> ".png"
        mime.contains("webp") -> ".webp"
        mime.contains("mp4") -> ".mp4"
        mime.contains("quicktime") -> ".mov"
        mime.contains("audio") -> ".m4a"
        mime.contains("pdf") -> ".pdf"
        else -> ".bin"
    }

    /**
     * Downscale to [MAX_IMAGE_PX] on the long edge and re-encode as JPEG.
     * Two passes: `inSampleSize` (cheap power-of-two decode) then an exact matrix scale.
     */
    private fun compressImage(uri: Uri, maxPx: Int = MAX_IMAGE_PX): File {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw UploadException("not a decodable image")

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw UploadException("not a decodable image")

        val longEdge = maxOf(decoded.width, decoded.height)
        val scale = if (longEdge > maxPx) maxPx.toFloat() / longEdge else 1f
        val rotated = applyExif(uri, decoded)

        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                rotated,
                (rotated.width * scale).toInt().coerceAtLeast(1),
                (rotated.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else rotated

        val target = File(cacheDir, "up_${UUID.randomUUID()}.jpg")
        target.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        if (scaled !== rotated) scaled.recycle()
        if (rotated !== decoded) rotated.recycle()
        decoded.recycle()
        return target
    }

    private fun applyExif(uri: Uri, bitmap: Bitmap): Bitmap = runCatching {
        val orientation = context.contentResolver.openInputStream(uri)?.use { input ->
            ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: return@runCatching bitmap

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return@runCatching bitmap
        }
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }.getOrDefault(bitmap)

    /** Wraps a file body and reports how much of it has left the device. */
    private class ProgressBody(
        private val file: File,
        private val mime: String,
        private val onProgress: (Float) -> Unit,
    ) : RequestBody() {

        override fun contentType() = mime.toMediaTypeOrNull()

        override fun contentLength(): Long = file.length()

        override fun writeTo(sink: BufferedSink) {
            val total = file.length().coerceAtLeast(1L)
            var written = 0L
            var lastReported = -1
            file.source().use { source ->
                val buffer = Buffer()
                while (true) {
                    val read = source.read(buffer, CHUNK)
                    if (read == -1L) break
                    sink.write(buffer, read)
                    written += read
                    val pct = ((written * 100) / total).toInt()
                    if (pct != lastReported) {
                        lastReported = pct
                        onProgress((written.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            sink.flush()
        }
    }

    companion object {
        const val MAX_IMAGE_PX = 1600
        const val MAX_BYTES = 100L * 1024 * 1024
        private const val CHUNK = 64L * 1024
    }
}
