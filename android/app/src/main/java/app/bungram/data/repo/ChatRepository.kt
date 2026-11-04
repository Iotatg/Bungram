package app.bungram.data.repo

import app.bungram.data.demo.DemoData
import app.bungram.data.model.Attachment
import app.bungram.data.model.CallLog
import app.bungram.data.model.Chat
import app.bungram.data.model.DeliveryState
import app.bungram.data.model.Message
import app.bungram.data.model.MessageKind
import app.bungram.data.model.StoryFeed
import app.bungram.data.model.User
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/**
 * Single source of truth for the UI.
 *
 * Implementations:
 *  - [DemoChatRepository]  – in-memory, no backend (default for design work / screenshots)
 *  - (production)          – Room cache + Ktor client + WebSocket deltas, see docs/03-ARCHITECTURE.md
 *
 * Everything is exposed as [StateFlow] so Compose only ever collects.
 */
interface ChatRepository {
    val chats: StateFlow<List<Chat>>
    val stories: StateFlow<List<StoryFeed>>
    val calls: StateFlow<List<CallLog>>

    fun chat(chatId: String): Chat?
    fun user(userId: String): User?
    fun messages(chatId: String): StateFlow<List<Message>>

    suspend fun send(
        chatId: String,
        text: String,
        kind: MessageKind = MessageKind.TEXT,
        attachment: Attachment? = null,
    )

    /**
     * Pick → compress → upload → send. [uri] is a `content://` or `file://` string so the domain
     * layer stays free of Android framework types.
     */
    suspend fun sendMedia(
        chatId: String,
        uri: String,
        kind: MessageKind,
        durationMs: Long = 0L,
    )

    fun markRead(chatId: String)
    fun markStoryViewed(storyId: String)

    /** True while any other member of [chatId] is typing. Drives the "typing…" subtitle. */
    fun typing(chatId: String): StateFlow<Boolean>

    /** Inbound typing signal from the socket. */
    fun onTyping(chatId: String, userId: String, typing: Boolean)

    /** Outbound typing signal. Fire-and-forget; a dropped frame is not an error. */
    suspend fun sendTyping(chatId: String, typing: Boolean)

    /** Sticker packs for the composer. Empty when the backend does not serve them. */
    suspend fun stickers(): List<app.bungram.data.remote.StickerPackDto> = emptyList()

    /** True once a Double Ratchet session exists with this chat's peer. */
    fun isEncrypted(chatId: String): Boolean = false

    /** 60-digit fingerprint, identical on both devices, or null before the first handshake. */
    fun safetyNumber(chatId: String): String? = null

    /** Deletes for everyone (the server tombstones the row) and drops the local copy. */
    suspend fun deleteMessage(chatId: String, messageId: String) {}
}

class DemoChatRepository : ChatRepository {

    private val _chats = MutableStateFlow(DemoData.chats())
    override val chats: StateFlow<List<Chat>> = _chats.asStateFlow()

    private val _stories = MutableStateFlow(DemoData.stories())
    override val stories: StateFlow<List<StoryFeed>> = _stories.asStateFlow()

    private val _calls = MutableStateFlow(DemoData.calls())
    override val calls: StateFlow<List<CallLog>> = _calls.asStateFlow()

    private val messageStore = mutableMapOf<String, MutableStateFlow<List<Message>>>().apply {
        _chats.value.forEach { put(it.id, MutableStateFlow(DemoData.messages(it.id))) }
    }

    override fun chat(chatId: String): Chat? = _chats.value.firstOrNull { it.id == chatId }

    override fun user(userId: String): User? = DemoData.user(userId)

    override fun messages(chatId: String): StateFlow<List<Message>> =
        messageStore.getOrPut(chatId) { MutableStateFlow(DemoData.messages(chatId)) }.asStateFlow()

    override suspend fun send(
        chatId: String,
        text: String,
        kind: MessageKind,
        attachment: Attachment?,
    ) {
        val now = System.currentTimeMillis()
        val optimistic = Message(
            id = "local_" + UUID.randomUUID(),
            chatId = chatId,
            senderId = DemoData.ME_ID,
            senderName = DemoData.me.displayName,
            kind = kind,
            text = text,
            attachment = attachment,
            createdAtEpochMs = now,
            delivery = DeliveryState.QUEUED,
            isOutgoing = true,
        )
        messageStore.getOrPut(chatId) { MutableStateFlow(emptyList()) }
            .update { it + optimistic }

        // Simulated network round-trip: QUEUED -> SENT -> DELIVERED -> READ.
        // The real implementation enqueues into the outbox and lets SyncEngine drive these states.
        Thread {
            Thread.sleep(280)
            patch(chatId, optimistic.id) { it.copy(delivery = DeliveryState.SENT) }
            Thread.sleep(700)
            patch(chatId, optimistic.id) { it.copy(delivery = DeliveryState.DELIVERED) }
            Thread.sleep(900)
            patch(chatId, optimistic.id) { it.copy(delivery = DeliveryState.READ) }
        }.start()

        _chats.update { list ->
            list.map {
                if (it.id == chatId) {
                    it.copy(
                        lastMessagePreview = if (kind == MessageKind.FILE) {
                            attachment?.fileName ?: "Attachment"
                        } else text,
                        lastMessageAtEpochMs = now,
                        lastMessageFromMe = true,
                        lastMessageRead = false,
                    )
                } else it
            }.sortedByDescending { it.lastMessageAtEpochMs }
        }
    }

    private fun patch(chatId: String, messageId: String, block: (Message) -> Message) {
        messageStore[chatId]?.update { list -> list.map { if (it.id == messageId) block(it) else it } }
    }

    /** Demo mode has no server, so the picked file is shown straight from its local URI. */
    override suspend fun sendMedia(chatId: String, uri: String, kind: MessageKind, durationMs: Long) {
        send(
            chatId = chatId,
            text = "",
            kind = kind,
            attachment = Attachment(
                localPath = uri,
                mimeType = when (kind) {
                    MessageKind.IMAGE -> "image/*"
                    MessageKind.VIDEO -> "video/*"
                    MessageKind.VOICE -> "audio/mp4"
                    else -> "application/octet-stream"
                },
                durationMs = durationMs,
            ),
        )
    }

    override fun markRead(chatId: String) {
        _chats.update { list -> list.map { if (it.id == chatId) it.copy(unreadCount = 0) else it } }
    }

    override fun markStoryViewed(storyId: String) {
        _stories.update { feeds ->
            feeds.map { feed ->
                feed.copy(stories = feed.stories.map { if (it.id == storyId) it.copy(viewed = true) else it })
            }
        }
    }

    private val typingFlows = mutableMapOf<String, MutableStateFlow<Boolean>>()

    override fun typing(chatId: String): StateFlow<Boolean> =
        typingFlows.getOrPut(chatId) { MutableStateFlow(false) }.asStateFlow()

    override fun onTyping(chatId: String, userId: String, typing: Boolean) {
        if (userId == DemoData.ME_ID) return
        typingFlows.getOrPut(chatId) { MutableStateFlow(false) }.value = typing
    }

    override suspend fun sendTyping(chatId: String, typing: Boolean) = Unit
}
