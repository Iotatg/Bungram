package app.bungram.sync

import app.bungram.data.remote.BungramApi
import app.bungram.data.remote.ServerSettings
import app.bungram.data.remote.Wire
import app.bungram.data.repo.RemoteChatRepository
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class ConnectionState { CONNECTING, ONLINE, OFFLINE }

data class TypingSignal(val chatId: String, val userId: String, val typing: Boolean)

/**
 * Owns the realtime socket and the retry loop.
 *
 * Wire protocol (server: /server .../routes/Routes.kt, `webSocket("/ws")`):
 *
 *   connect   ws://host/ws?token=<jwt>
 *   client -> {"type":"ping"}                          every 25s
 *   server -> {"type":"pong"}
 *   server -> {"type":"message","chatId":..,"messageId":..,"clientId":..}
 *   server -> {"type":"receipt","chatId":..,"messageId":..,"state":"READ"}
 *   server -> {"type":"typing","chatId":..,"userId":..,"typing":true}
 *   server -> {"type":"presence","userId":..,"online":true}
 *
 * The socket is a *hint*, never the source of truth: any hint that matters triggers a delta pull
 * through `/v1/sync`, so a dropped frame or a 6-hour offline gap self-heals on reconnect.
 */
class SyncEngine(
    private val repository: RemoteChatRepository,
    private val api: BungramApi,
    private val settings: ServerSettings,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _connection = MutableStateFlow(ConnectionState.OFFLINE)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _typing = MutableSharedFlow<TypingSignal>(extraBufferCapacity = 32)
    val typing: SharedFlow<TypingSignal> = _typing.asSharedFlow()

    private var backoffMs = 1_000L

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { loop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        _connection.value = ConnectionState.OFFLINE
    }

    private suspend fun loop() {
        while (currentCoroutineContext().isActive) {
            val token = api.tokens.access
            if (token == null) {
                delay(1_500)
                continue
            }
            try {
                _connection.value = ConnectionState.CONNECTING
                api.httpClient.webSocket(urlString = "${settings.wsUrl}?token=$token") {
                    _connection.value = ConnectionState.ONLINE
                    backoffMs = 1_000L

                    val heartbeat = launch {
                        while (isActive) {
                            delay(25_000)
                            runCatching { send(Frame.Text("""{"type":"ping"}""")) }
                        }
                    }
                    // Catch up first, then push anything the outbox is still holding.
                    repository.syncNow()
                    repository.refreshStories()
                    repository.flushOutbox()

                    try {
                        for (frame in incoming) {
                            val text = (frame as? Frame.Text)?.readText() ?: continue
                            handle(text)
                        }
                    } finally {
                        heartbeat.cancel()
                    }
                }
            } catch (_: Throwable) {
                // Connection refused, TLS error, token expired — all handled by backoff.
            }
            _connection.value = ConnectionState.OFFLINE
            delay(backoffMs + (0..350).random())
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        }
    }

    private suspend fun handle(raw: String) {
        val obj = runCatching { Wire.json.parseToJsonElement(raw) as JsonObject }.getOrNull() ?: return
        when (obj["type"]?.jsonPrimitive?.content) {
            "message" -> {
                repository.syncNow()
                repository.flushOutbox()
            }
            "receipt" -> {
                val id = obj["messageId"]?.jsonPrimitive?.content ?: return
                repository.onReceipt(id, obj["state"]?.jsonPrimitive?.content ?: "READ")
            }
            "typing" -> {
                val signal = TypingSignal(
                    chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty(),
                    userId = obj["userId"]?.jsonPrimitive?.content.orEmpty(),
                    typing = obj["typing"]?.jsonPrimitive?.content == "true",
                )
                repository.onTyping(signal.chatId, signal.userId, signal.typing)
                _typing.tryEmit(signal)
            }
            "presence" -> repository.onPresence(
                obj["userId"]?.jsonPrimitive?.content.orEmpty(),
                obj["online"]?.jsonPrimitive?.content == "true",
            )
        }
    }

    suspend fun sendTyping(chatId: String, typing: Boolean) {
        // Sent over REST so it also works while the socket is reconnecting.
        runCatching { api.typing(chatId, typing) }
    }

    /** Called by the UI when the app returns to foreground. */
    suspend fun poke() {
        repository.flushOutbox()
        repository.syncNow()
    }
}
