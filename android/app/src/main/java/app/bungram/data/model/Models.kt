package app.bungram.data.model

import kotlinx.serialization.Serializable

/* ---------------------------------------------------------------------------------------------
 * Domain model. Deliberately transport-agnostic: the same types are produced by the demo data
 * source, the Room cache and the Ktor client, so the UI never knows where data came from.
 * ------------------------------------------------------------------------------------------- */

@Serializable
enum class Presence { ONLINE, AWAY, OFFLINE }

@Serializable
data class User(
    val id: String,
    val displayName: String,
    val phone: String? = null,
    val username: String? = null,
    val avatarUrl: String? = null,
    val about: String = "",
    val isVerified: Boolean = false,
    val presence: Presence = Presence.OFFLINE,
    val lastSeenEpochMs: Long = 0L,
)

@Serializable
enum class ChatKind { DIRECT, GROUP, CHANNEL, SAVED }

@Serializable
data class Chat(
    val id: String,
    val kind: ChatKind,
    val title: String,
    val avatarUrl: String? = null,
    val peerId: String? = null,
    val membersCount: Int = 0,
    val lastMessagePreview: String = "",
    val lastMessageAtEpochMs: Long = 0L,
    val unreadCount: Int = 0,
    val isMuted: Boolean = false,
    val isPinned: Boolean = false,
    val isVerified: Boolean = false,
    val lastMessageFromMe: Boolean = false,
    val lastMessageRead: Boolean = true,
) {
    val isGroup: Boolean get() = kind == ChatKind.GROUP || kind == ChatKind.CHANNEL
}

@Serializable
enum class MessageKind { TEXT, IMAGE, VIDEO, FILE, VOICE, LOCATION, CONTACT, POLL, SYSTEM }

@Serializable
enum class DeliveryState { QUEUED, SENT, DELIVERED, READ, FAILED }

@Serializable
data class Attachment(
    val url: String? = null,
    val localPath: String? = null,
    val mimeType: String = "application/octet-stream",
    val fileName: String? = null,
    val sizeBytes: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long = 0L,
    val blurHash: String? = null,
    /** AES-256-GCM key wrapped for every recipient device (E2EE media). */
    val mediaKey: String? = null,
    val sha256: String? = null,
)

@Serializable
data class Message(
    val id: String,
    val chatId: String,
    val senderId: String,
    val senderName: String,
    val kind: MessageKind = MessageKind.TEXT,
    val text: String = "",
    val attachment: Attachment? = null,
    val createdAtEpochMs: Long = 0L,
    val delivery: DeliveryState = DeliveryState.SENT,
    val isOutgoing: Boolean = false,
    val replyToId: String? = null,
    val replyPreview: String? = null,
    val editedAtEpochMs: Long? = null,
    val isDeleted: Boolean = false,
    val reactions: List<Reaction> = emptyList(),
    val isEphemeral: Boolean = false,
    val expiresAtEpochMs: Long? = null,
)

@Serializable
data class Reaction(val emoji: String, val count: Int, val mine: Boolean = false)

@Serializable
data class Story(
    val id: String,
    val owner: User,
    val mediaUrl: String? = null,
    val thumbnailUrl: String? = null,
    val caption: String = "",
    val createdAtEpochMs: Long = 0L,
    val expiresAtEpochMs: Long = 0L,
    val viewed: Boolean = false,
    val viewCount: Int = 0,
)

@Serializable
data class StoryFeed(val owner: User, val stories: List<Story>) {
    val allViewed: Boolean get() = stories.all { it.viewed }
    val latest: Story? get() = stories.maxByOrNull { it.createdAtEpochMs }
}

@Serializable
enum class CallType { AUDIO, VIDEO }

@Serializable
enum class CallDirection { INCOMING, OUTGOING, MISSED }

@Serializable
data class CallLog(
    val id: String,
    val peer: User,
    val type: CallType,
    val direction: CallDirection,
    val startedAtEpochMs: Long,
    val durationSec: Int = 0,
)

/** A chat row + the peer, which is what most list UIs actually need. */
data class ChatSummary(val chat: Chat, val peer: User?)
