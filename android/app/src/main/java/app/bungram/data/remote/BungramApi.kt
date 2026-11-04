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
import app.bungram.data.model.Chat
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
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

    /* ------------------------------------------------------------------ groups & channels */

    suspend fun createGroup(req: CreateGroupRequest): Chat = withContext(Dispatchers.IO) {
        httpClient.post("$base/v1/chats/create") { setBody(req) }.dataOrThrow()
    }

    suspend fun chatDetail(chatId: String): ChatDetailDto = withContext(Dispatchers.IO) {
        httpClient.get("$base/v1/chats/$chatId").dataOrThrow()
    }

    suspend fun updateChat(chatId: String, title: String?, description: String?): Chat =
        withContext(Dispatchers.IO) {
            httpClient.patch("$base/v1/chats/$chatId") {
                setBody(UpdateChatRequest(title, description))
            }.dataOrThrow()
        }

    suspend fun deleteChat(chatId: String) {
        withContext(Dispatchers.IO) { httpClient.delete("$base/v1/chats/$chatId") }
    }

    suspend fun addMembers(chatId: String, userIds: List<String>): List<MemberDto> =
        withContext(Dispatchers.IO) {
            httpClient.post("$base/v1/chats/$chatId/members") { setBody(MembersRequest(userIds)) }
                .dataOrThrow()
        }

    suspend fun removeMember(chatId: String, userId: String): List<MemberDto> =
        withContext(Dispatchers.IO) {
            httpClient.delete("$base/v1/chats/$chatId/members/$userId").dataOrThrow()
        }

    suspend fun setRole(chatId: String, userId: String, role: String): List<MemberDto> =
        withContext(Dispatchers.IO) {
            httpClient.post("$base/v1/chats/$chatId/members/$userId/role") {
                parameter("role", role)
            }.dataOrThrow()
        }

    suspend fun mintInvite(chatId: String): InviteDto = withContext(Dispatchers.IO) {
        httpClient.post("$base/v1/chats/$chatId/invite").dataOrThrow()
    }

    suspend fun revokeInvite(chatId: String) {
        withContext(Dispatchers.IO) { httpClient.delete("$base/v1/chats/$chatId/invite") }
    }

    suspend fun invitePreview(slug: String): InvitePreviewDto = withContext(Dispatchers.IO) {
        httpClient.get("$base/v1/invites/$slug").dataOrThrow()
    }

    suspend fun joinInvite(slug: String): Chat = withContext(Dispatchers.IO) {
        httpClient.post("$base/v1/invites/$slug/join").dataOrThrow()
    }

    /* ------------------------------------------------------------------------ stickers */

    suspend fun stickers(): List<StickerPackDto> = withContext(Dispatchers.IO) {
        httpClient.get("$base/v1/stickers").dataOrThrow<StickerCatalogDto>().packs
    }

    /* -------------------------------------------------------------------- devices/push */

    suspend fun registerDevice(reg: DeviceRegistration): DeviceDto = withContext(Dispatchers.IO) {
        httpClient.post("$base/v1/devices") { setBody(reg) }.dataOrThrow()
    }

    suspend fun devices(currentDeviceId: String): List<DeviceDto> = withContext(Dispatchers.IO) {
        httpClient.get("$base/v1/devices") { parameter("current", currentDeviceId) }.dataOrThrow()
    }

    suspend fun revokeDevice(deviceId: String) {
        withContext(Dispatchers.IO) { httpClient.delete("$base/v1/devices/$deviceId") }
    }

    /* ------------------------------------------------------------------------- pre-keys */

    suspend fun publishKeys(bundle: PreKeyBundleDto, oneTimePreKeys: List<OneTimePreKeyDto>): Int =
        withContext(Dispatchers.IO) {
            httpClient.post("$base/v1/keys") { setBody(PublishKeysRequest(bundle, oneTimePreKeys)) }
                .dataOrThrow<app.bungram.data.remote.AckDto>().count ?: 0
        }

    /** How many one-time pre-keys the server still holds for this device. */
    suspend fun keyStatus(deviceId: String): Int = withContext(Dispatchers.IO) {
        httpClient.get("$base/v1/keys/status") { parameter("deviceId", deviceId) }
            .dataOrThrow<app.bungram.data.remote.AckDto>().count ?: 0
    }

    suspend fun preKeyBundle(userId: String): PreKeyBundleDto = withContext(Dispatchers.IO) {
        httpClient.get("$base/v1/keys/$userId").dataOrThrow()
    }

    suspend fun updateProfile(req: UpdateProfileRequest): app.bungram.data.model.User =
        withContext(Dispatchers.IO) {
            httpClient.patch("$base/v1/me") { setBody(req) }.dataOrThrow()
        }

    suspend fun deleteMessage(chatId: String, messageId: String) {
        withContext(Dispatchers.IO) { httpClient.delete("$base/v1/messages/$chatId/$messageId") }
    }

    fun close() = httpClient.close()
}
