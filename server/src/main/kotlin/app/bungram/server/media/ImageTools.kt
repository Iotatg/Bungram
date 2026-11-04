package app.bungram.server.media

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * Everything the server needs to know about a picture without trusting the client:
 * real MIME (magic bytes, not the multipart header), pixel dimensions, and a colour hint.
 *
 * All of it is best-effort. A `.webp` or a PDF simply yields `isImage = false` and the upload
 * still succeeds — the message just renders as a file.
 */
object ImageTools {

    init {
        // ImageIO is pure CPU work here; without this the JVM tries to reach an X server and
        // every thumbnail generation fails on a headless box (containers, CI, production).
        System.setProperty("java.awt.headless", "true")
    }

    data class Probe(
        val isImage: Boolean,
        val mime: String?,
        val width: Int,
        val height: Int,
        val colorHint: String?,
    )

    private val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val GIF = "GIF8".toByteArray()
    private val RIFF = "RIFF".toByteArray()
    private val WEBP = "WEBP".toByteArray()
    private val PDF = "%PDF".toByteArray()

    /** Content sniffing. The multipart `Content-Type` is a hint from the client, never a fact. */
    fun sniff(file: File): String? {
        val head = ByteArray(16)
        val read = file.inputStream().use { it.read(head) }
        if (read < 4) return null
        fun startsWith(prefix: ByteArray): Boolean =
            read >= prefix.size && prefix.indices.all { head[it] == prefix[it] }

        return when {
            startsWith(JPEG) -> "image/jpeg"
            startsWith(PNG) -> "image/png"
            startsWith(GIF) -> "image/gif"
            startsWith(PDF) -> "application/pdf"
            startsWith(RIFF) && read >= 12 &&
                (8..11).all { head[it] == WEBP[it - 8] } -> "image/webp"
            // ISO-BMFF: ....ftyp{heic,heix,mif1,mp42,isom}
            read >= 12 && String(head, 4, 4) == "ftyp" -> when (String(head, 8, 4)) {
                "heic", "heix", "hevc", "mif1", "msf1" -> "image/heic"
                "qt  " -> "video/quicktime"
                else -> "video/mp4"
            }
            else -> null
        }
    }

    fun probe(file: File, declaredMime: String): Probe {
        val sniffed = sniff(file)
        val mime = sniffed ?: declaredMime
        if (mime == null || !mime.startsWith("image/")) {
            return Probe(isImage = false, mime = mime, width = 0, height = 0, colorHint = null)
        }
        val img = runCatching { ImageIO.read(file) }.getOrNull()
            ?: return Probe(isImage = false, mime = mime, width = 0, height = 0, colorHint = null)
        return Probe(
            isImage = true,
            mime = mime,
            width = img.width,
            height = img.height,
            colorHint = averageColor(img),
        )
    }

    /**
     * Writes a max-[maxPx] JPEG preview. Animated GIFs keep only their first frame — that is what
     * a chat bubble needs; the full file is still one tap away.
     */
    fun writeThumb(src: File, dst: File, maxPx: Int = 320): Boolean = runCatching {
        val img = ImageIO.read(src) ?: return false
        if (img.width <= 0 || img.height <= 0) return false
        val scale = minOf(maxPx.toFloat() / img.width, maxPx.toFloat() / img.height, 1f)
        val w = (img.width * scale).toInt().coerceAtLeast(1)
        val h = (img.height * scale).toInt().coerceAtLeast(1)

        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.drawImage(img, 0, 0, w, h, null)
        } finally {
            g.dispose()
        }

        dst.parentFile?.mkdirs()
        val writer = ImageIO.getImageWritersByFormatName("jpg").next()
        ImageIO.createImageOutputStream(dst).use { stream ->
            writer.output = stream
            val params = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = 0.82f
            }
            writer.write(null, IIOImage(out, null, null), params)
        }
        writer.dispose()
        true
    }.getOrDefault(false)

    private fun averageColor(img: BufferedImage): String {
        val tiny = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)
        val g = tiny.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(img, 0, 0, 1, 1, null)
        } finally {
            g.dispose()
        }
        val rgb = tiny.getRGB(0, 0)
        return "#%02X%02X%02X".format((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
    }
}
