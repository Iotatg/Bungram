package app.bungram.data.media

import app.bungram.AppGraph
import app.bungram.data.model.Attachment
import java.io.File

/**
 * The server stores media paths relative to itself (`/v1/media/med_12_ab12`), so a deployment can
 * move domains without rewriting a single row. The client is the only place that knows the origin.
 */
object MediaUrl {

    fun absolute(path: String?): String? {
        if (path.isNullOrBlank()) return null
        return when {
            path.startsWith("http://") || path.startsWith("https://") -> path
            path.startsWith("/") -> AppGraph.settings.baseUrl.trimEnd('/') + path
            else -> path
        }
    }

    /**
     * What to hand to Coil. A local file is passed as a [File] (not a bare path string) so Coil
     * never has to guess whether it is looking at a URI or a filesystem location.
     */
    fun coilModel(attachment: Attachment?, preferFullSize: Boolean = false): Any? {
        if (attachment == null) return null
        val local = attachment.localPath
        if (!local.isNullOrBlank()) {
            return when {
                local.startsWith("content://") || local.startsWith("file://") ->
                    android.net.Uri.parse(local)
                else -> File(local).takeIf { it.exists() } ?: absolute(attachment.url)
            }
        }
        val remote = if (preferFullSize) attachment.url else (attachment.thumbUrl ?: attachment.url)
        return absolute(remote)
    }

    /** Playable/downloadable source for ExoPlayer or MediaPlayer. */
    fun playbackSource(attachment: Attachment): String? {
        val local = attachment.localPath
        if (!local.isNullOrBlank()) {
            return when {
                local.startsWith("content://") || local.startsWith("file://") -> local
                File(local).exists() -> local
                else -> absolute(attachment.url)
            }
        }
        return absolute(attachment.url)
    }
}
