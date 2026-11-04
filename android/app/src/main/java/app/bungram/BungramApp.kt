package app.bungram

import android.app.Application
import app.bungram.data.local.BungramDb
import app.bungram.data.remote.BungramApi
import app.bungram.data.remote.ServerSettings
import app.bungram.data.remote.TokenStore
import app.bungram.data.repo.RemoteChatRepository
import app.bungram.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _session = MutableStateFlow<SessionState>(SessionState.Loading)
    val session: StateFlow<SessionState> = _session.asStateFlow()

    @Volatile
    private var initialized = false

    fun init(app: Application) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            settings = ServerSettings(app)
            tokens = TokenStore(app)
            api = BungramApi(app, tokens, settings)
            db = BungramDb(app)
            repository = RemoteChatRepository(db, api)
            sync = SyncEngine(repository, api, settings)

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
        sync.start()
        _session.value = SessionState.LoggedIn(tokens.userId.orEmpty())
    }

    fun logout() {
        sync.stop()
        tokens.clear()
        db.wipe()
        repository.clear()
        _session.value = SessionState.LoggedOut
    }

    /** True when the app has a token but the socket has not come up yet — drives the "connecting" chip. */
    fun isAuthenticated(): Boolean = tokens.access != null
}

class BungramApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
    }
}
