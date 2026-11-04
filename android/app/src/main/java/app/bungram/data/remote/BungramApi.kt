package app.bungram.data.remote

import android.content.Context
import app.bungram.BuildConfig
import app.bungram.data.model.Message
import app.bungram.data.model.StoryFeed
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Any non-2xx / `ok:false` reply. Message is safe to show in the UI. */
class ApiException(message: String) : Exception(message)

/**
 * Typed wrapper over the REST surface. One [HttpClient] for the whole process: REST and the
 * realtime socket share the connection pool, DNS cache and TLS session cache.
 *
 * Every call reads [ServerSettings.baseUrl] at request time, so switching servers from the login
 * screen takes effect immediately without rebuilding the client.
 */
class BungramApi(
    private val context: Context,
    val tokens: TokenStore,
    private val settings: ServerSettings,
) {
    val httpClient: HttpClient = HttpClient(OkHttp) {
        expectSuccess = false
        install(ContentNegotiation) { json(Wire.json) }
        install(Logging) { level = if (BuildConfig.DEBUG) LogLevel.INFO else LogLevel.NONE }
        install(WebSockets) { pingIntervalMillis = 20_000 }
        defaultRequest {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Accept, ContentType.Application.Json)
            tokens.access?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        }
    }

    private val base: String get() = settings.baseUrl

    private suspend inline fun <reified T> HttpResponse.dataOrThrow(): T {
        val env = runCatching { body<Envelope<T>>() }.getOrElse {
            throw ApiException("server sent an unreadable reply (HTTP $status)")
        }
        if (!env.ok) throw ApiException(env.error ?: "request failed (HTTP $status)")
        return env.data ?: throw ApiException(env.error ?: "empty reply (HTTP $status)")
    }

    suspend fun requestOtp(phone: String, deviceName: String, deviceId: String): AuthChallenge =
        withContext(Dispatchers.IO) {
            httpClient.post("$base/v1/auth/otp") { setBody(AuthRequest(phone, deviceName, deviceId)) }
                .dataOrThrow<AuthChallenge>()
        }

    suspend fun verifyOtp(payload: AuthVerify): AuthSession = withContext(Dispatchers.IO) {
        val session = httpClient.post("$base/v1/auth/verify") { setBody(payload) }
            .dataOrThrow<AuthSession>()
        tokens.save(session.accessToken, session.refreshToken, session.userId)
        session
    }

    suspend fun me(): app.bungram.data.model.User = withContext(Dispatchers.IO) {
        httpClient.get("$base/v1/me").dataOrThrow()
    }

    suspend fun sync(cursor: String?): SyncResponse = withContext(Dispatchers.IO) {
        httpClient.get("$base/v1/sync") { parameter("cursor", cursor) }.dataOrThrow()
    }

    suspend fun sendMessage(req: SendMessageRequest): Message = withContext(Dispatchers.IO) {
        httpClient.post("$base/v1/messages") { setBody(req) }.dataOrThrow()
    }

    suspend fun status(): List<StoryFeed> = withContext(Dispatchers.IO) {
        httpClient.get("$base/v1/status").dataOrThrow<StatusResponse>().feeds
    }

    /** Phone -> account. No address book upload: the user types the number themselves. */
    suspend fun lookup(phone: String): app.bungram.data.model.User = withContext(Dispatchers.IO) {
        httpClient.get("$base/v1/users/lookup") { parameter("phone", phone) }.dataOrThrow()
    }

    suspend fun createChat(peerId: String): app.bungram.data.model.Chat = withContext(Dispatchers.IO) {
        httpClient.post("$base/v1/chats") { setBody(CreateChatRequest(peerId)) }.dataOrThrow()
    }

    suspend fun markRead(chatId: String) {
        withContext(Dispatchers.IO) { httpClient.post("$base/v1/chats/$chatId/read") }
    }

    suspend fun typing(chatId: String, typing: Boolean) {
        withContext(Dispatchers.IO) {
            httpClient.post("$base/v1/chats/$chatId/typing") { setBody(TypingEvent(chatId, tokens.userId ?: "", typing)) }
        }
    }

    fun close() = httpClient.close()
}
