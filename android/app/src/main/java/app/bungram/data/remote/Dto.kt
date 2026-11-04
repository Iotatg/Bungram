package app.bungram.data.remote

import app.bungram.data.model.Chat
import app.bungram.data.model.Message
import app.bungram.data.model.StoryFeed
import app.bungram.data.model.User
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/* ---------------------------------------------------------------------------------------------
 * Wire contract with the Kotlin/Ktor backend in /server.
 * Every payload is versioned so old clients keep working during rollouts.
 * ------------------------------------------------------------------------------------------- */

@Serializable
data class Envelope<T>(
    val ok: Boolean = true,
    val data: T? = null,
    val error: String? = null,
    val serverTimeMs: Long = 0L,
)

@Serializable
data class AuthRequest(val phone: String, val deviceName: String, val deviceId: String)

@Serializable
data class AuthChallenge(
    val challengeId: String,
    val expiresInSec: Int,
    val length: Int = 6,
    /** Only populated when the server runs with BUNGRAM_DEV=1; the UI shows it as a tappable chip. */
    val devCode: String? = null,
)

@Serializable
data class AuthVerify(
    val challengeId: String,
    val code: String,
    val identityKey: String,
    val signedPreKey: String,
    val oneTimePreKeys: List<String>,
)

@Serializable
data class AuthSession(
    val userId: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresInSec: Int,
    val user: User,
)

@Serializable
data class SendMessageRequest(
    val clientId: String,
    val chatId: String,
    val kind: String = "TEXT",
    val ciphertext: String,
    val attachmentId: String? = null,
    val replyToId: String? = null,
    val sentAtMs: Long,
    val encrypted: Boolean = false,
    val senderDeviceId: String? = null,
)

@Serializable
data class SyncRequest(val cursor: String? = null, val limit: Int = 200)

@Serializable
data class SyncResponse(
    val chats: List<Chat> = emptyList(),
    val users: List<User> = emptyList(),
    val messages: List<Message> = emptyList(),
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
)

@Serializable
data class CreateChatRequest(val peerId: String)

@Serializable
data class CreateGroupRequest(
    val title: String,
    val description: String = "",
    val memberIds: List<String> = emptyList(),
    val kind: String = "GROUP",
    val isPublic: Boolean = false,
)

@Serializable
data class UpdateChatRequest(val title: String? = null, val description: String? = null)

@Serializable
data class MembersRequest(val userIds: List<String>)

@Serializable
data class MemberDto(val user: User, val role: String = "MEMBER", val joinedAtEpochMs: Long = 0)

@Serializable
data class ChatDetailDto(
    val chat: Chat,
    val members: List<MemberDto> = emptyList(),
    val canManage: Boolean = false,
)

@Serializable
data class InviteDto(
    val slug: String,
    val url: String,
    val chatId: String,
    val expiresAtEpochMs: Long = 0,
    val uses: Int = 0,
)

@Serializable
data class InvitePreviewDto(
    val slug: String,
    val chatId: String,
    val kind: String,
    val title: String,
    val description: String = "",
    val membersCount: Int = 0,
    val isPublic: Boolean = false,
    val alreadyMember: Boolean = false,
)

@Serializable
data class StickerDto(val id: String, val emoji: String, val packId: String)

@Serializable
data class StickerPackDto(
    val id: String,
    val title: String,
    val cover: String,
    val stickers: List<StickerDto> = emptyList(),
)

@Serializable
data class StickerCatalogDto(val packs: List<StickerPackDto> = emptyList())

@Serializable
data class DeviceRegistration(
    val deviceId: String,
    val provider: String = "none",
    val pushToken: String = "",
    val deviceName: String = "android",
    val appVersion: String = "",
)

@Serializable
data class DeviceDto(
    val id: String,
    val provider: String,
    val deviceName: String,
    val lastSeenEpochMs: Long,
    val current: Boolean = false,
)

@Serializable
data class PreKeyBundleDto(
    val userId: String,
    val deviceId: String,
    val registrationId: Int = 0,
    val identityKey: String,
    val identitySignKey: String = "",
    val signedPreKeyId: Int = 1,
    val signedPreKey: String,
    val signedPreKeySignature: String,
    val oneTimePreKeyId: Int? = null,
    val oneTimePreKey: String? = null,
)

@Serializable
data class OneTimePreKeyDto(val id: Int, val key: String)

@Serializable
data class PublishKeysRequest(
    val bundle: PreKeyBundleDto,
    val oneTimePreKeys: List<OneTimePreKeyDto> = emptyList(),
)

@Serializable
data class AckDto(
    val chatId: String? = null,
    val unread: Int? = null,
    val typing: Boolean? = null,
    val storyId: String? = null,
    val viewed: Boolean? = null,
    val delivered: Int? = null,
    val callId: String? = null,
    val count: Int? = null,
)

@Serializable
data class UpdateProfileRequest(
    val displayName: String? = null,
    val username: String? = null,
    val about: String? = null,
    val avatarUrl: String? = null,
)

/** Reply of `POST /v1/media`. */
@Serializable
data class MediaDto(
    val id: String,
    val url: String,
    val thumbUrl: String? = null,
    val mimeType: String = "application/octet-stream",
    val fileName: String? = null,
    val sizeBytes: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long = 0L,
    val sha256: String = "",
    val colorHint: String? = null,
    val createdAtEpochMs: Long = 0L,
)

@Serializable
data class MediaQuotaDto(
    val usedBytes: Long = 0L,
    val limitBytes: Long = 0L,
    val fileCount: Int = 0,
    val maxFileBytes: Long = 0L,
)

@Serializable
data class StatusResponse(val feeds: List<StoryFeed> = emptyList())

@Serializable
data class TypingEvent(val chatId: String, val userId: String, val typing: Boolean)

@Serializable
data class ReceiptEvent(val chatId: String, val messageId: String, val userId: String, val state: String)

@Serializable
data class PresenceEvent(val userId: String, val online: Boolean, val lastSeenMs: Long)

@Serializable
data class CallSignal(
    val callId: String,
    val chatId: String,
    val fromUserId: String,
    val type: String,          // AUDIO | VIDEO
    val action: String,        // OFFER | ANSWER | ICE | HANGUP | BUSY
    val payload: String? = null,
)

object Wire {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
}
