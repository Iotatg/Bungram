package app.bungram.core

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import app.bungram.R

/**
 * Short UI sounds, pre-decoded and played through [SoundPool].
 *
 * `SoundPool` rather than `MediaPlayer` because these are 100–400 ms effects fired on taps: a
 * `MediaPlayer` would allocate a codec per sound and audibly lag behind the animation.
 *
 * Two rules the class enforces so the app never feels rude:
 *   - nothing plays while the phone is on silent/vibrate;
 *   - nothing plays if the user switched sounds off in Settings.
 */
object Sounds {

    private var pool: SoundPool? = null
    private var sendId = 0
    private var receiveId = 0
    private var loginId = 0
    private var loaded = false

    @Volatile
    var enabled: Boolean = true

    fun init(context: Context) {
        if (pool != null) return
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val created = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(attributes)
            .build()
        created.setOnLoadCompleteListener { _, _, status -> if (status == 0) loaded = true }
        sendId = created.load(context, R.raw.send, 1)
        receiveId = created.load(context, R.raw.receive, 1)
        loginId = created.load(context, R.raw.login, 1)
        pool = created
    }

    fun send() = play(sendId, 0.55f)

    fun receive() = play(receiveId, 0.6f)

    fun login() = play(loginId, 0.7f)

    private fun play(id: Int, volume: Float) {
        if (!enabled || !loaded) return
        val context = appContext ?: return
        if (!audible(context)) return
        pool?.play(id, volume, volume, 1, 0, 1f)
    }

    private var appContext: Context? = null

    fun attach(context: Context) {
        appContext = context.applicationContext
        init(context)
    }

    /** Ringer mode is the user's global "be quiet" switch; honour it before our own setting. */
    private fun audible(context: Context): Boolean {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return true
        return manager.ringerMode == AudioManager.RINGER_MODE_NORMAL
    }
}
