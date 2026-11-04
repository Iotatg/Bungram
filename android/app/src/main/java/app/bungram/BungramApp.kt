package app.bungram

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import app.bungram.core.DeviceIdentity
import app.bungram.core.Sounds
import app.bungram.crypto.E2ee
import app.bungram.crypto.MemorySecretStorage
import app.bungram.crypto.SecretStorage
import app.bungram.crypto.PrefsSecretStorage
import app.bungram.data.local.BungramDb
import app.bungram.data.media.AudioPlayer
import app.bungram.data.media.MediaUploader
import app.bungram.data.media.VoiceRecorder
import app.bungram.data.remote.BungramApi
import app.bungram.data.remote.ServerSettings
import app.bungram.data.remote.TokenStore
import app.bungram.data.repo.KeyExchange
import app.bungram.data.repo.RemoteChatRepository
import app.bungram.service.MessageNotifications
import app.bungram.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

sealed interface SessionState {
    data object Loading : SessionState
    data object LoggedOut : SessionState
    data class LoggedIn(val userId: String) : SessionState
}

/**
 * Hand-rolled service locator. Bungram is one module with six long-lived objects; a DI framework
 * would add a compiler plugin and indirection without removing a single line of wiring.
 *
 * Ownership is explicit:
 *   AppGraph -> BungramApi -> HttpClient (one per process)
 *            -> BungramDb  -> SQLite (one per process)
 *            -> RemoteChatRepository (cache + outbox)
 *            -> SyncEngine (socket + retry)
 */
object AppGraph {

    lateinit var appContext: android.content.Context
        private set

    lateinit var settings: ServerSettings
        private set

    lateinit var tokens: TokenStore
        private set

    lateinit var api: BungramApi
        private set

    lateinit var db: BungramDb
        private set

    lateinit var repository: RemoteChatRepository
        private set

    lateinit var sync: SyncEngine
        private set

    lateinit var uploader: MediaUploader
        private set

    lateinit var recorder: VoiceRecorder
        private set

    lateinit var audio: AudioPlayer
        private set

    /** End-to-end encryption engine: identity, pre-keys, and one ratchet per peer. */
    lateinit var e2ee: E2ee
        private set

    /** Stable per-install id used for push routing and one pre-key bundle per device. */
    lateinit var deviceId: String
        private set

    /**
     * Coil's HTTP stack. Every media request needs the bearer token, and images must not carry it
     * in the URL — a URL ends up in logs, screenshots and referrers.
     */
    private val mediaHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val token = runCatching { tokens.access }.getOrNull()
                val request = if (token.isNullOrBlank()) chain.request()
                else chain.request().newBuilder().header("Authorization", "Bearer $token").build()
                chain.proceed(request)
            }
            .build()
    }

    fun imageLoader(context: android.content.Context): ImageLoader =
        ImageLoader.Builder(context)
            .okHttpClient(mediaHttp)
            .crossfade(true)
            .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _session = MutableStateFlow<SessionState>(SessionState.Loading)
    val session: StateFlow<SessionState> = _session.asStateFlow()

    @Volatile
    private var initialized = false

    fun init(app: Application) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            appContext = app.applicationContext
            settings = ServerSettings(app)
            tokens = TokenStore(app)
            api = BungramApi(app, tokens, settings)
            db = BungramDb(app)
            uploader = MediaUploader(app, tokens, settings)
            recorder = VoiceRecorder(app)
            audio = AudioPlayer(app)
            deviceId = DeviceIdentity.of(app)
            // The bundle resolver is a lambda, not an injected dependency: the engine stays a pure
            // crypto object that a JVM test can drive with a fake directory.
            e2ee = E2ee(secretStorage(app), deviceId) { userId ->
                runCatching { KeyExchange.toBundle(api.preKeyBundle(userId)) }.getOrNull()
            }
            repository = RemoteChatRepository(db, api, uploader, e2ee)
            Sounds.attach(app)
            sync = SyncEngine(repository, api, settings) { chatId ->
                MessageNotifications.onIncoming(app, chatId)
            }

            if (tokens.access != null) {
                scope.launch { openSession() }
            } else {
                _session.value = SessionState.LoggedOut
            }
            initialized = true
        }
    }

    /** Called after a successful OTP verify (tokens are already saved by [BungramApi.verifyOtp]). */
    fun onLoggedIn() {
        scope.launch { openSession() }
    }

    private suspend fun openSession() {
        repository.hydrate()
        // Publish before the socket opens: the very first message a peer sends us may be a
        // handshake, and it can only complete if our bundle is already on the server.
        runCatching { repository.publishPreKeys() }
        runCatching { registerDevice() }
        sync.start()
        _session.value = SessionState.LoggedIn(tokens.userId.orEmpty())
    }

    /**
     * Keystore-backed storage, with a degraded fallback.
     *
     * `EncryptedSharedPreferences` needs a working Android Keystore. On a few emulator images and
     * on devices with a broken keystore it throws during creation — and a crash inside
     * `Application.onCreate` means the app never opens at all. Falling back to memory loses keys on
     * restart (old messages become undecryptable) but keeps the app usable, which is the better
     * failure of the two.
     */
    private fun secretStorage(context: Application): SecretStorage =
        runCatching { PrefsSecretStorage(context) }.getOrElse {
            android.util.Log.w("Bungram", "keystore unavailable — E2EE sessions will not survive restart", it)
            MemorySecretStorage()
        }

    /** Registers this install so the server can push to it, and so "Devices" can list it. */
    private suspend fun registerDevice() {
        if (tokens.access == null) return
        runCatching {
            api.registerDevice(
                app.bungram.data.remote.DeviceRegistration(
                    deviceId = deviceId,
                    provider = "ntfy",
                    pushToken = "",
                    deviceName = DeviceIdentity.name(),
                    appVersion = DeviceIdentity.appVersion(),
                )
            )
        }
    }

    fun logout() {
        sync.stop()
        tokens.clear()
        db.wipe()
        repository.clear()
        _session.value = SessionState.LoggedOut
    }

    /**
     * True while at least one activity is started. Notification suppression and the "do not beep
     * while the user is looking" rule both hang off this, so it is tracked once here rather than
     * guessed from lifecycle callbacks scattered across screens.
     */
    @Volatile
    private var foregroundActivities = 0

    fun isInForeground(): Boolean = foregroundActivities > 0

    fun onActivityStarted() { foregroundActivities++ }

    fun onActivityStopped() { foregroundActivities = (foregroundActivities - 1).coerceAtLeast(0) }

    /** True when the app has a token but the socket has not come up yet — drives the "connecting" chip. */
    fun isAuthenticated(): Boolean = tokens.access != null

    /** The signed-in account as the server described it, or null before the first sync lands. */
    fun me(): app.bungram.data.model.User? = tokens.userId?.let(repository::user)
}

class BungramApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
    }

    override fun newImageLoader(): ImageLoader = AppGraph.imageLoader(this)
}
