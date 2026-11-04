package app.bungram.server.media

import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * One stored blob. `id` is what clients reference; `sha256` is the content address used for
 * de-duplication *within a single owner* (never across owners — two users holding the same file
 * must not be able to learn that from the server).
 */
@Serializable
data class MediaRecord(
    val id: String,
    val sha256: String,
    val mimeType: String,
    val fileName: String? = null,
    val sizeBytes: Long,
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long = 0,
    val ownerId: String,
    val createdAtEpochMs: Long,
    val hasThumb: Boolean = false,
    /** Average colour of the image, used as a chat placeholder before the bytes arrive. */
    val colorHint: String? = null,
    val refCount: Int = 1,
)

@Serializable
data class MediaQuota(val usedBytes: Long, val limitBytes: Long, val fileCount: Int) {
    val remainingBytes: Long get() = (limitBytes - usedBytes).coerceAtLeast(0)
    val usedPercent: Int get() = if (limitBytes <= 0) 0 else ((usedBytes * 100) / limitBytes).toInt()
}

class QuotaExceededException(val quota: MediaQuota, val requested: Long) :
    Exception("storage quota exceeded: ${quota.usedBytes + requested} > ${quota.limitBytes}")

class MediaNotFoundException(id: String) : Exception("no media $id")

/** Zero-byte uploads are a client bug, not a storage problem — mapped to 400, not 413. */
class EmptyUploadException : Exception("empty upload")

/**
 * Filesystem-backed blob store with the same contract a S3/GCS implementation would have:
 *
 *   store(owner, tempFile, …) -> MediaRecord
 *   open(id)                 -> InputStream
 *   delete(id, owner)        -> Boolean
 *
 * Layout on disk is content-addressed and sharded, so a directory never holds more than a few
 * thousand entries even with millions of files:
 *
 *   <root>/blobs/ab/abcdef…            original bytes
 *   <root>/thumbs/ab/abcdef….jpg      320px JPEG preview
 *   <root>/tmp/<uuid>                  in-flight uploads (swept on boot)
 *   <root>/index.jsonl                 append-only metadata log, replayed on boot
 */
class MediaStore(
    rootDir: File,
    private val perUserQuotaBytes: Long = DEFAULT_QUOTA_BYTES,
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
) {

    private val root = rootDir.apply { mkdirs() }
    private val blobs = File(root, "blobs").apply { mkdirs() }
    private val thumbs = File(root, "thumbs").apply { mkdirs() }
    private val tmp = File(root, "tmp").apply { mkdirs() }
    private val indexFile = File(root, "index.jsonl")

    private val records = ConcurrentHashMap<String, MediaRecord>()
    /** "ownerId:sha256" -> mediaId. Per-owner de-dupe only. */
    private val dedupe = ConcurrentHashMap<String, String>()
    private val usage = ConcurrentHashMap<String, AtomicLong>()
    private val counter = AtomicLong(0)

    init {
        sweepTemp()
        replayIndex()
    }

    /* ------------------------------------------------------------------------------ boot */

    private fun sweepTemp() {
        val cutoff = System.currentTimeMillis() - 6 * 3_600_000L
        tmp.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }

    private fun replayIndex() {
        if (!indexFile.exists()) return
        indexFile.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val rec = runCatching {
                kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                    .decodeFromString(MediaRecord.serializer(), line)
            }.getOrNull() ?: return@forEachLine
            // A crash between writing the log line and moving the blob leaves a dangling record.
            if (!blobFile(rec.sha256).exists()) return@forEachLine
            records[rec.id] = rec
            dedupe["${rec.ownerId}:${rec.sha256}"] = rec.id
            usage.computeIfAbsent(rec.ownerId) { AtomicLong(0) }.addAndGet(rec.sizeBytes)
        }
    }

    private fun appendIndex(rec: MediaRecord) {
        synchronized(indexFile) {
            runCatching {
                indexFile.appendText(
                    kotlinx.serialization.json.Json.encodeToString(MediaRecord.serializer(), rec) + "\n"
                )
            }
        }
    }

    /* ---------------------------------------------------------------------------- paths */

    private fun shard(hash: String): String = hash.take(2).ifEmpty { "00" }

    fun blobFile(sha256: String): File = File(File(blobs, shard(sha256)), sha256)

    fun thumbFile(sha256: String): File = File(File(thumbs, shard(sha256)), "$sha256.jpg")

    fun newTempFile(): File = File(tmp, java.util.UUID.randomUUID().toString())

    /* ----------------------------------------------------------------------------- read */

    fun record(id: String): MediaRecord? = records[id]

    fun requireRecord(id: String): MediaRecord = records[id] ?: throw MediaNotFoundException(id)

    fun open(id: String): InputStream {
        val rec = requireRecord(id)
        val f = blobFile(rec.sha256)
        if (!f.exists()) throw MediaNotFoundException(id)
        return f.inputStream()
    }

    fun file(id: String): File {
        val rec = requireRecord(id)
        return blobFile(rec.sha256).also { if (!it.exists()) throw MediaNotFoundException(id) }
    }

    fun thumbFileFor(id: String): File? {
        val rec = requireRecord(id)
        if (!rec.hasThumb) return null
        return thumbFile(rec.sha256).takeIf { it.exists() }
    }

    fun quota(userId: String): MediaQuota = MediaQuota(
        usedBytes = usage[userId]?.get() ?: 0,
        limitBytes = perUserQuotaBytes,
        fileCount = records.values.count { it.ownerId == userId },
    )

    fun maxFileBytes(): Long = maxFileBytes

    /* ---------------------------------------------------------------------------- write */

    /**
     * Streams [input] to a temp file while hashing it, then commits. Returns the existing record
     * when the same owner uploads identical bytes again (re-sending a photo costs nothing).
     */
    fun store(
        ownerId: String,
        input: InputStream,
        mimeType: String,
        fileName: String?,
        width: Int = 0,
        height: Int = 0,
        durationMs: Long = 0,
    ): MediaRecord {
        val temp = newTempFile()
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        try {
            FileOutputStream(temp).use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buf)
                    if (read < 0) break
                    size += read
                    if (size > maxFileBytes) throw IllegalArgumentException("file larger than ${maxFileBytes / 1_048_576} MB")
                    digest.update(buf, 0, read)
                    out.write(buf, 0, read)
                }
            }
            if (size == 0L) throw EmptyUploadException()
            val sha = digest.digest().joinToString("") { "%02x".format(it) }

            dedupe["$ownerId:$sha"]?.let { existingId ->
                records[existingId]?.let { existing ->
                    temp.delete()
                    return existing
                }
            }

            val q = quota(ownerId)
            if (q.usedBytes + size > q.limitBytes) throw QuotaExceededException(q, size)

            val target = blobFile(sha)
            target.parentFile?.mkdirs()
            if (!target.exists()) {
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
            } else {
                temp.delete()
            }

            val probe = ImageTools.probe(target, mimeType)
            val finalW = if (width > 0) width else probe.width
            val finalH = if (height > 0) height else probe.height
            val thumbOk = probe.isImage && ImageTools.writeThumb(target, thumbFile(sha))

            val rec = MediaRecord(
                id = "med_${counter.incrementAndGet()}_${sha.take(8)}",
                sha256 = sha,
                mimeType = probe.mime ?: mimeType,
                fileName = fileName?.take(180),
                sizeBytes = size,
                width = finalW,
                height = finalH,
                durationMs = durationMs,
                ownerId = ownerId,
                createdAtEpochMs = System.currentTimeMillis(),
                hasThumb = thumbOk,
                colorHint = probe.colorHint,
            )
            records[rec.id] = rec
            dedupe["$ownerId:$sha"] = rec.id
            usage.computeIfAbsent(ownerId) { AtomicLong(0) }.addAndGet(size)
            appendIndex(rec)
            return rec
        } catch (t: Throwable) {
            temp.delete()
            throw t
        }
    }

    /** Removes the blob when the last reference goes away. Only the owner may delete. */
    fun delete(id: String, ownerId: String): Boolean {
        val rec = records[id] ?: return false
        if (rec.ownerId != ownerId) return false
        records.remove(id)
        dedupe.remove("${rec.ownerId}:${rec.sha256}")
        usage[rec.ownerId]?.addAndGet(-rec.sizeBytes)
        blobFile(rec.sha256).delete()
        thumbFile(rec.sha256).delete()
        return true
    }

    fun stats(): Map<String, Any> = mapOf(
        "blobs" to records.size,
        "bytes" to records.values.sumOf { it.sizeBytes },
    )

    companion object {
        const val DEFAULT_QUOTA_BYTES = 2L * 1024 * 1024 * 1024
        const val DEFAULT_MAX_FILE_BYTES = 100L * 1024 * 1024
    }
}
