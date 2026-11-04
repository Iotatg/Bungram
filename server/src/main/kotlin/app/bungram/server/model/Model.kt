package app.bungram.server.model

import kotlinx.serialization.Serializable

@Serializable
data class Envelope<T>(
    val ok: Boolean = true,
    val data: T? = null,
    val error: String? = null,
    val serverTimeMs: Long = System.currentTimeMillis(),
)

@Serializable
data class UserDto(
    val id: String,
    val displayName: String,
    val phone: String? = null,
    val username: String? = null,
    val avatarUrl: String? = null,
    val about: String = "",
    val isVerified: Boolean = false,
    val presence: String = "OFFLINE",
    val lastSeenEpochMs: Long = 0,
)

@Serializable
data class ChatDto(
    val id: String,
    /** DIRECT | GROUP | CHANNEL */
    val kind: String,
    val title: String,
    val avatarUrl: String? = null,
    val peerId: String? = null,
    val membersCount: Int = 0,
    val lastMessagePreview: String = "",
    val lastMessageAtEpochMs: Long = 0,
    val unreadCount: Int = 0,
    val isMuted: Boolean = false,
    val isPinned: Boolean = false,
    val isVerified: Boolean = false,
    val lastMessageFromMe: Boolean = false,
    val lastMessageRead: Boolean = true,
    /** Public description shown under the title on the group / channel info screen. */
    val description: String = "",
    /** Invite slug (`bungram.app/+slug`). Null until an admin mints one. */
    val inviteSlug: String? = null,
    val ownerId: String? = null,
    /** Caller's role in this chat: OWNER | ADMIN | MEMBER. Projected per request, never stored. */
    val myRole: String = "MEMBER",
    /** Channels only: true when the caller may post. Projected per request. */
    val canPost: Boolean = true,
    /** Channels: subscriber count is public even to non-members. */
    val isPublic: Boolean = false,
)

@Serializable
data class MemberDto(
    val user: UserDto,
    val role: String = "MEMBER",
    val joinedAtEpochMs: Long = 0,
)

@Serializable
data class ChatDetailDto(
    val chat: ChatDto,
    val members: List<MemberDto> = emptyList(),
    /** True when the caller is allowed to add / remove members or edit the chat. */
    val canManage: Boolean = false,
)

@Serializable
data class CreateGroupRequest(
    val title: String,
    val description: String = "",
    val memberIds: List<String> = emptyList(),
    /** CHANNEL creates a broadcast channel instead of a group. */
    val kind: String = "GROUP",
    val isPublic: Boolean = false,
)

@Serializable
data class UpdateChatRequest(
    val title: String? = null,
    val description: String? = null,
)

@Serializable
data class MembersRequest(val userIds: List<String>)

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
data class StickerDto(
    val id: String,
    val emoji: String,
    val packId: String,
)

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
    /** Long-term identity public key for Diffie-Hellman (X25519, base64). */
    val identityKey: String,
    /** Long-term identity public key for signatures (Ed25519, base64); verifies [signedPreKeySignature]. */
    val identitySignKey: String = "",
    val signedPreKeyId: Int = 1,
    val signedPreKey: String,
    val signedPreKeySignature: String,
    val oneTimePreKeyId: Int? = null,
    val oneTimePreKey: String? = null,
)

@Serializable
data class OneTimePreKeyDto(val id: Int, val key: String)

/**
 * A device publishes its long-term bundle once and then tops up the one-time pool. The server
 * hands out exactly one one-time key per fetch and never gives the same key twice — that is what
 * makes the first X3DH message forward-secret even if the identity key later leaks.
 */
@Serializable
data class PublishKeysRequest(
    val bundle: PreKeyBundleDto,
    val oneTimePreKeys: List<OneTimePreKeyDto> = emptyList(),
)

@Serializable
data class SecretChatRequest(
    val peerId: String,
    /** Base64 of the X3DH initial message so the peer can derive the same root key. */
    val initialMessage: String,
)

@Serializable
data class AttachmentDto(
    val url: String? = null,
    val thumbUrl: String? = null,
    val mediaId: String? = null,
    val mimeType: String = "application/octet-stream",
    val fileName: String? = null,
    val sizeBytes: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long = 0,
    /** Average colour of the image, painted behind the bubble until the bytes land. */
    val colorHint: String? = null,
    /** AES-256-GCM key wrapped for every recipient device (E2EE media). */
    val mediaKey: String? = null,
    val sha256: String? = null,
)

/** What `POST /v1/media` returns, and what a client caches for later reuse. */
@Serializable
data class MediaDto(
    val id: String,
    /** Relative path — the client resolves it against its own base URL. */
    val url: String,
    val thumbUrl: String? = null,
    val mimeType: String,
    val fileName: String? = null,
    val sizeBytes: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long = 0,
    val sha256: String = "",
    val colorHint: String? = null,
    val createdAtEpochMs: Long = 0,
)

@Serializable
data class MediaQuotaDto(
    val usedBytes: Long = 0,
    val limitBytes: Long = 0,
    val fileCount: Int = 0,
    val maxFileBytes: Long = 0,
)

@Serializable
data class ShareLinkDto(val url: String, val expiresInSec: Long = 86_400)

@Serializable
data class DeleteAckDto(val deleted: Boolean)

@Serializable
data class MessageDto(
    val id: String,
    val chatId: String,
    val senderId: String,
    val senderName: String,
    val kind: String = "TEXT",
    val text: String = "",
    val attachment: AttachmentDto? = null,
    val createdAtEpochMs: Long = 0,
    val delivery: String = "SENT",
    val isOutgoing: Boolean = false,
    val replyToId: String? = null,
    /** Echo of the client-generated idempotency key, so the sender can reconcile its optimistic row. */
    val clientId: String? = null,
    /** Monotonic server sequence. The only safe sync cursor: client clocks lie. */
    val seq: Long = 0,
    val editedAtEpochMs: Long? = null,
    val isDeleted: Boolean = false,
    /** True when [text] is a sealed payload. The server stores it but cannot read it. */
    val encrypted: Boolean = false,
    /** Sender device that sealed the message — needed to pick the right ratchet session. */
    val senderDeviceId: String? = null,
)

@Serializable
data class StoryDto(
    val id: String,
    val owner: UserDto,
    val mediaUrl: String? = null,
    val thumbnailUrl: String? = null,
    val caption: String = "",
    val createdAtEpochMs: Long = 0,
    val expiresAtEpochMs: Long = 0,
    val viewed: Boolean = false,
    val viewCount: Int = 0,
)

@Serializable
data class StoryFeedDto(val owner: UserDto, val stories: List<StoryDto>)

@Serializable
data class AuthRequest(val phone: String, val deviceName: String = "android", val deviceId: String)

@Serializable
data class AuthChallenge(val challengeId: String, val expiresInSec: Int, val length: Int = 6, val devCode: String? = null)

@Serializable
data class AuthVerify(
    val challengeId: String,
    val code: String,
    val identityKey: String = "",
    val signedPreKey: String = "",
    val oneTimePreKeys: List<String> = emptyList(),
)

@Serializable
data class AuthSession(
    val userId: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresInSec: Int,
    val user: UserDto,
)

@Serializable
data class SendMessageRequest(
    /** Idempotency key generated by the client; a retry with the same id must not duplicate. */
    val clientId: String = "",
    val chatId: String,
    val kind: String = "TEXT",
    /** Opaque E2EE payload. The server never parses it. */
    val ciphertext: String = "",
    val attachmentId: String? = null,
    val replyToId: String? = null,
    val sentAtMs: Long = System.currentTimeMillis(),
    /** True when [ciphertext] is sealed — the server stores and forwards it verbatim. */
    val encrypted: Boolean = false,
    val senderDeviceId: String? = null,
)

@Serializable
data class SyncResponse(
    val chats: List<ChatDto> = emptyList(),
    /** Profiles for every member of those chats — one round trip, no N+1 lookups on the client. */
    val users: List<UserDto> = emptyList(),
    val messages: List<MessageDto> = emptyList(),
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
)

@Serializable
data class StatusResponse(val feeds: List<StoryFeedDto> = emptyList())

@Serializable
data class TypingEvent(val chatId: String, val userId: String = "", val typing: Boolean)

@Serializable
data class ReceiptEvent(val chatId: String, val messageId: String, val userId: String, val state: String = "READ")

@Serializable
data class PresenceEvent(val userId: String, val online: Boolean, val lastSeenMs: Long = System.currentTimeMillis())

@Serializable
data class CallSignal(
    val callId: String,
    val chatId: String,
    val fromUserId: String = "",
    val type: String = "AUDIO",
    val action: String,
    val payload: String? = null,
)

@Serializable
data class CreateStatusRequest(
    val mediaUrl: String? = null,
    val caption: String = "",
    val ttlHours: Int = 24,
)

@Serializable
data class CreateChatRequest(val peerId: String)

@Serializable
data class UpdateProfileRequest(
    val displayName: String? = null,
    val username: String? = null,
    val about: String? = null,
    val avatarUrl: String? = null,
)

@Serializable
data class HealthDto(val status: String, val uptimeMs: Long, val connections: Int)

@Serializable
data class TokenPairDto(val accessToken: String, val expiresInSec: Int)

@Serializable
data class AckDto(
    val chatId: String? = null,
    val unread: Int? = null,
    val typing: Boolean? = null,
    val storyId: String? = null,
    val viewed: Boolean? = null,
    val delivered: Int? = null,
    val callId: String? = null,
    /** Remaining one-time pre-keys, so a client knows when to top up. */
    val count: Int? = null,
)

@Serializable
data class RefreshRequest(val refreshToken: String)

object Wire {
    val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
}

@Serializable
data class ErrorBody(val ok: Boolean = false, val error: String, val serverTimeMs: Long = System.currentTimeMillis())
