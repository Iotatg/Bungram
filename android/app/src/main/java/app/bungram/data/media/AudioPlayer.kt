package app.bungram.data.media

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import app.bungram.data.model.Attachment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One voice note plays at a time — exactly like every messenger. A single MediaPlayer is reused
 * so scrubbing through ten notes does not leak ten codecs.
 *
 * Media URLs are protected, so the Authorization header travels with the data source instead of
 * the URL.
 */
class AudioPlayer(private val context: Context) {

    private var player: MediaPlayer? = null

    private val _playingId = MutableStateFlow<String?>(null)
    val playingId: StateFlow<String?> = _playingId.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    fun toggle(messageId: String, attachment: Attachment, token: String?) {
        if (_playingId.value == messageId) {
            stop()
            return
        }
        stop()
        val source = MediaUrl.playbackSource(attachment) ?: return
        val mp = MediaPlayer()
        runCatching {
            mp.setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            val headers = if (token.isNullOrBlank()) emptyMap() else mapOf("Authorization" to "Bearer $token")
            mp.setDataSource(context, Uri.parse(source), headers)
            mp.setOnCompletionListener {
                _playingId.value = null
                _progress.value = 0f
                it.release()
                if (player === it) player = null
            }
            mp.setOnErrorListener { p, _, _ ->
                _playingId.value = null
                _progress.value = 0f
                p.release()
                if (player === p) player = null
                true
            }
            mp.prepare()
            mp.start()
            player = mp
            _playingId.value = messageId
            _progress.value = 0f
            startProgressTicker(mp, messageId)
        }.onFailure {
            runCatching { mp.release() }
            player = null
            _playingId.value = null
        }
    }

    private fun startProgressTicker(mp: MediaPlayer, messageId: String) {
        val total = runCatching { mp.duration }.getOrDefault(0).coerceAtLeast(1)
        val thread = Thread {
            while (_playingId.value == messageId && player === mp) {
                val pos = runCatching { mp.currentPosition }.getOrDefault(0)
                _progress.value = (pos.toFloat() / total).coerceIn(0f, 1f)
                Thread.sleep(120)
            }
        }
        thread.isDaemon = true
        thread.start()
    }

    fun stop() {
        player?.let { mp ->
            runCatching { if (mp.isPlaying) mp.stop() }
            runCatching { mp.release() }
        }
        player = null
        _playingId.value = null
        _progress.value = 0f
    }
}
