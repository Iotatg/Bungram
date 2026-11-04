package app.bungram.data.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File
import java.util.UUID

data class Recording(val file: File, val durationMs: Long, val waveform: List<Float>)

/**
 * Voice notes: AAC-LC in an MP4 container, 44.1 kHz mono at 64 kbps.
 *
 * That is the same trade-off Signal and Telegram make — speech is narrowband anyway, and 64 kbps
 * keeps a one-minute note under 500 KB. The amplitude samples are collected while recording so the
 * bubble can show the real waveform immediately instead of a fake one.
 */
class VoiceRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var target: File? = null
    private var startedAt = 0L
    private val amplitudes = mutableListOf<Float>()
    private var sampler: Thread? = null

    val isRecording: Boolean get() = recorder != null

    fun start(): Boolean {
        if (recorder != null) return false
        val file = File(context.cacheDir, "voice_${UUID.randomUUID()}.m4a")
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        return try {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioChannels(1)
            rec.setAudioSamplingRate(44_100)
            rec.setAudioEncodingBitRate(64_000)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()

            recorder = rec
            target = file
            startedAt = System.currentTimeMillis()
            amplitudes.clear()
            startSampling()
            true
        } catch (t: Throwable) {
            runCatching { rec.release() }
            file.delete()
            false
        }
    }

    fun stop(): Recording? {
        val rec = recorder ?: return null
        val file = target
        val started = startedAt
        recorder = null
        target = null
        stopSampling()

        val ok = runCatching { rec.stop() }.isSuccess
        runCatching { rec.release() }
        if (!ok || file == null || !file.exists() || file.length() == 0L) {
            file?.delete()
            return null
        }
        val duration = (System.currentTimeMillis() - started).coerceAtLeast(200)
        return Recording(file, duration, normalize(amplitudes.toList()))
    }

    fun cancel() {
        val rec = recorder
        recorder = null
        target = null
        stopSampling()
        runCatching { rec?.stop() }
        runCatching { rec?.release() }
        target?.delete()
    }

    /* -------------------------------------------------------------------------- amplitude */

    private fun startSampling() {
        val rec = recorder ?: return
        sampler = Thread {
            while (recorder === rec) {
                val amp = runCatching { rec.maxAmplitude }.getOrDefault(0)
                amplitudes += amp / 32_767f
                Thread.sleep(60)
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun stopSampling() {
        sampler?.interrupt()
        sampler = null
    }

    /** Downsamples to ~40 bars and scales so the loudest sample reaches 1.0. */
    private fun normalize(samples: List<Float>): List<Float> {
        if (samples.isEmpty()) return List(40) { 0.25f }
        val bars = 40
        val step = (samples.size.toFloat() / bars).coerceAtLeast(1f)
        val out = ArrayList<Float>(bars)
        for (i in 0 until bars) {
            val from = (i * step).toInt().coerceIn(0, samples.lastIndex)
            val to = ((i + 1) * step).toInt().coerceIn(from, samples.lastIndex)
            var peak = 0f
            for (j in from..to) peak = maxOf(peak, samples[j])
            out += peak
        }
        val max = out.maxOrNull()?.takeIf { it > 0.01f } ?: return List(bars) { 0.25f }
        return out.map { (it / max).coerceIn(0.08f, 1f) }
    }
}
