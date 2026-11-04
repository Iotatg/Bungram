package app.bungram.data.repo

import app.bungram.data.local.BungramDb
import app.bungram.data.local.OutboxRow
import app.bungram.data.model.Attachment
import app.bungram.data.model.CallLog
import app.bungram.data.model.Chat
import app.bungram.data.model.DeliveryState
import app.bungram.data.model.Message
import app.bungram.data.model.MessageKind
import app.bungram.data.model.StoryFeed
import app.bungram.data.model.User
import app.bungram.data.remote.BungramApi
import app.bungram.data.remote.SendMessageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The real repository: local SQLite is the source of truth for the UI, the network is a sync
 * channel that pushes deltas into it.
 *
 * Flow of an outgoing message:
 *
 *   composer ─► insert row (QUEUED, id = "l_<clientId>") ─► outbox row (durable)
 *            ─► flush: POST /v1/messages {clientId}  ─► server echoes the stored message
 *            ─► delete the optimistic row, insert the server row (SENT)
 *
 * If the process dies between any two steps, the outbox row survives and the next flush retries
 * with the *same* clientId — the server's idempotency index turns that into a no-op instead of a
 * duplicate bubble. This is the single most important property of a chat client.
 */
class RemoteChatRepository(
    private val db: BungramDb,
    private val api: BungramApi,
    private val uploader: app.bungram.data.media.MediaUploader,
) : ChatRepository {

    private val _chats = MutableStateFlow<List<Chat>>(emptyList())
    override val chats: StateFlow<List<Chat>> = _chats.asStateFlow()

    private val _stories = MutableStateFlow<List<StoryFeed>>(emptyList())
    override val stories: StateFlow<List<StoryFeed>> = _stories.asStateFlow()

    private val _calls = MutableStateFlow<List<CallLog>>(emptyList())
    override val calls: StateFlow<List<CallLog>> = _calls.asStateFlow()

    private val users = ConcurrentHashMap<String, User>()
    private val messageFlows = ConcurrentHashMap<String, MutableStateFlow<List<Message>>>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val sendMutex = Mutex()
    private val syncMutex = Mutex()

    /** Chat currently on screen; incoming messages there must not raise the unread badge. */
    @Volatile
    private var activeChatId: String? = null

    val meId: String get() = api.tokens.userId.orEmpty()

    private val meName: String
        get() = users[meId]?.displayName ?: "You"

    /* --------------------------------------------------------------------------- lifecycle */

    /** Loads the cache into memory. Called once after login, before the first frame. */
    suspend fun hydrate() = withContext(Dispatchers.IO) {
        users.clear()
        db.users().forEach { users[it.id] = it }
        _chats.value = db.chats()
        db.allMessages().groupBy { it.chatId }.forEach { (chatId, list) ->
            flowFor(chatId).value = list
        }
        // First launch after login: we know the id from the token but not the profile yet.
        if (users.isEmpty()) {
            runCatching { api.me() }.getOrNull()?.let {
                users[it.id] = it
                db.upsertUsers(listOf(it))
            }
        }
        recoverPendingMedia()
    }

    fun clear() {
        users.clear()
        messageFlows.clear()
        _chats.value = emptyList()
        _stories.value = emptyList()
    }

    /* ----------------------------------------------------------------------------- queries */

    override fun chat(chatId: String): Chat? = _chats.value.firstOrNull { it.id == chatId }

    override fun user(userId: String): User? = users[userId]

    override fun messages(chatId: String): StateFlow<List<Message>> = flowFor(chatId)

    private fun flowFor(chatId: String): MutableStateFlow<List<Message>> =
        messageFlows.getOrPut(chatId) { MutableStateFlow(emptyList()) }

    /* ------------------------------------------------------------------------------- sync */

    /**
     * Pulls everything newer than the stored cursor. Cheap enough to call on every socket event:
     * the server filters by timestamp, and an empty delta is a few hundred bytes.
     */
    suspend fun syncNow(): Boolean = withContext(Dispatchers.IO) {
        if (api.tokens.access == null) return@withContext false
        syncMutex.withLock {
            val cursor = db.meta(KEY_CURSOR)
            val response = runCatching { api.sync(cursor) }.getOrNull() ?: return@withLock false
            applyDelta(response.chats, response.users, response.messages)
            response.nextCursor?.let { db.setMeta(KEY_CURSOR, it) }
            true
        }
    }

    /** Full resync: drops the cursor and re-reads the last 200 messages of every chat. */
    suspend fun hardResync(): Boolean {
        db.setMeta(KEY_CURSOR, null)
        return syncNow()
    }

    private fun applyDelta(incomingChats: List<Chat>, incomingUsers: List<User>, incomingMessages: List<Message>) {
        if (incomingUsers.isNotEmpty()) {
            incomingUsers.forEach { users[it.id] = it }
            db.upsertUsers(incomingUsers)
        }
        val normalized = incomingMessages.map { it.copy(isOutgoing = it.senderId == meId) }
        db.upsertChats(incomingChats)
        db.upsertMessages(normalized)

        val merged = (_chats.value.associateBy { it.id } + incomingChats.associateBy { it.id }).toMutableMap()
        normalized.forEach { msg ->
            val flow = flowFor(msg.chatId)
            flow.value = (flow.value.filterNot { it.id == msg.id } + msg).sortedWith(MESSAGE_ORDER)
            merged[msg.chatId]?.let { chat ->
                val unread = if (msg.isOutgoing || msg.chatId == activeChatId) 0 else chat.unreadCount + 1
                merged[chat.id] = chat.copy(
                    lastMessagePreview = msg.attachment?.fileName ?: msg.text,
                    lastMessageAtEpochMs = msg.createdAtEpochMs,
                    lastMessageFromMe = msg.isOutgoing,
                    lastMessageRead = msg.isOutgoing,
                    unreadCount = unread,
                )
                db.setUnread(chat.id, unread)
            }
        }
        publishChats(merged.values.toList())
    }

    private fun publishChats(list: List<Chat>) {
        val sorted = list.sortedWith(compareByDescending<Chat> { it.isPinned }.thenByDescending { it.lastMessageAtEpochMs })
        _chats.value = sorted
        db.upsertChats(sorted)
    }

    suspend fun refreshStories() = withContext(Dispatchers.IO) {
        if (api.tokens.access == null) return@withContext
        runCatching { api.status() }.getOrNull()?.let { _stories.value = it }
    }

    /* ------------------------------------------------------------------------------- send */

    override suspend fun send(
        chatId: String,
        text: String,
        kind: MessageKind,
        attachment: Attachment?,
    ) = withContext(Dispatchers.IO) {
        val clientId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val optimistic = Message(
            id = BungramDb.LOCAL_PREFIX + clientId,
            chatId = chatId,
            senderId = meId,
            senderName = meName,
            kind = kind,
            text = text,
            attachment = attachment,
            createdAtEpochMs = now,
            delivery = DeliveryState.QUEUED,
            isOutgoing = true,
        )

        db.upsertMessages(listOf(optimistic))
        db.enqueue(OutboxRow(clientId, chatId, kind, text, now))
        insertIntoFlow(optimistic)
        touchChat(optimistic)

        flushOutbox()
    }

    /**
     * Drains the outbox in FIFO order. Stops at the first failure so messages never arrive
     * out of order; the row keeps its attempt count and is retried on the next socket event,
     * app resume or reconnect.
     */
    suspend fun flushOutbox() = withContext(Dispatchers.IO) {
        if (api.tokens.access == null) return@withContext
        sendMutex.withLock {
            while (true) {
                val pending = db.outbox().firstOrNull() ?: return@withLock
                val localId = BungramDb.LOCAL_PREFIX + pending.clientId
                val sent = try {
                    api.sendMessage(
                        SendMessageRequest(
                            clientId = pending.clientId,
                            chatId = pending.chatId,
                            kind = pending.kind.name,
                            ciphertext = pending.text,
                            // Already uploaded before the row was queued, so a retry never
                            // re-sends megabytes over a flaky connection.
                            attachmentId = pending.attachmentId,
                            sentAtMs = pending.createdAtEpochMs,
                        )
                    )
                } catch (t: Throwable) {
                    val attempts = pending.attempts + 1
                    db.bumpAttempt(pending.clientId, t.message)
                    val state = if (attempts >= MAX_ATTEMPTS) DeliveryState.FAILED else DeliveryState.QUEUED
                    db.updateDelivery(localId, state)
                    patchFlow(pending.chatId, localId) { it.copy(delivery = state) }
                    return@withLock
                }

                db.deleteMessage(localId)
                db.removeOutbox(pending.clientId)
                val stored = sent.copy(isOutgoing = true, delivery = DeliveryState.SENT)
                db.upsertMessages(listOf(stored))
                replaceInFlow(pending.chatId, localId, stored)
                touchChat(stored)
            }
        }
    }

    /* ------------------------------------------------------------------------------- media */

    /**
     * Send a picture / video / file / voice note.
     *
     * The bubble appears immediately with the local URI, then the bytes go up with progress, then
     * the message is queued. Splitting it this way means a failed upload never leaves a phantom
     * message on the server, and a failed *send* after a successful upload costs nothing — the
     * outbox row already carries the media id.
     */
    override suspend fun sendMedia(
        chatId: String,
        uri: String,
        kind: MessageKind,
        durationMs: Long,
    ) = withContext(Dispatchers.IO) {
        val clientId = UUID.randomUUID().toString()
        val localId = BungramDb.LOCAL_PREFIX + clientId
        val now = System.currentTimeMillis()
        val optimistic = Message(
            id = localId,
            chatId = chatId,
            senderId = meId,
            senderName = meName,
            kind = kind,
            text = "",
            attachment = Attachment(
                localPath = uri,
                mimeType = mimeFor(kind),
                durationMs = durationMs,
                uploadProgress = 0f,
            ),
            createdAtEpochMs = now,
            delivery = DeliveryState.QUEUED,
            isOutgoing = true,
        )
        db.upsertMessages(listOf(optimistic))
        insertIntoFlow(optimistic)
        touchChat(optimistic)

        uploadAndSend(clientId, chatId, kind, uri, durationMs, localId, now)
    }

    private suspend fun uploadAndSend(
        clientId: String,
        chatId: String,
        kind: MessageKind,
        uri: String,
        durationMs: Long,
        localId: String,
        createdAt: Long = System.currentTimeMillis(),
    ) {
        val uploaded = try {
            uploader.upload(android.net.Uri.parse(uri), kind, durationMs) { progress ->
                patchFlow(chatId, localId) { msg ->
                    msg.copy(attachment = msg.attachment?.copy(uploadProgress = progress))
                }
            }
        } catch (t: Throwable) {
            db.updateDelivery(localId, DeliveryState.FAILED)
            patchFlow(chatId, localId) {
                it.copy(delivery = DeliveryState.FAILED, attachment = it.attachment?.copy(uploadProgress = -1f))
            }
            return
        }

        val attachment = Attachment(
            url = uploaded.url,
            thumbUrl = uploaded.thumbUrl,
            mediaId = uploaded.id,
            localPath = uri,
            mimeType = uploaded.mimeType,
            fileName = uploaded.fileName,
            sizeBytes = uploaded.sizeBytes,
            width = uploaded.width,
            height = uploaded.height,
            durationMs = uploaded.durationMs,
            colorHint = uploaded.colorHint,
            sha256 = uploaded.sha256,
            uploadProgress = -1f,
        )
        db.upsertMessages(listOf(Message(
            id = localId, chatId = chatId, senderId = meId, senderName = meName,
            kind = kind, text = "", attachment = attachment,
            createdAtEpochMs = createdAt, delivery = DeliveryState.QUEUED, isOutgoing = true,
        )))
        patchFlow(chatId, localId) { it.copy(attachment = attachment) }

        db.enqueue(
            OutboxRow(
                clientId = clientId,
                chatId = chatId,
                kind = kind,
                text = "",
                createdAtEpochMs = createdAt,
                attachmentId = uploaded.id,
                localPath = uri,
            )
        )
        flushOutbox()
    }

    /**
     * A process kill between "bubble inserted" and "upload finished" leaves a QUEUED message with
     * a local URI and no outbox row. Re-upload it on next launch instead of losing the user's photo.
     */
    private fun recoverPendingMedia() {
        val queued = db.outbox().map { it.clientId }.toSet()
        db.allMessages()
            .filter {
                it.isOutgoing &&
                    it.delivery == DeliveryState.QUEUED &&
                    it.attachment?.localPath != null &&
                    it.attachment.url == null &&
                    it.id.removePrefix(BungramDb.LOCAL_PREFIX) !in queued
            }
            .forEach { msg ->
                val att = msg.attachment ?: return@forEach
                val clientId = msg.id.removePrefix(BungramDb.LOCAL_PREFIX)
                scope.launch {
                    uploadAndSend(clientId, msg.chatId, msg.kind, att.localPath!!, att.durationMs, msg.id, msg.createdAtEpochMs)
                }
            }
    }

    private fun mimeFor(kind: MessageKind): String = when (kind) {
        MessageKind.IMAGE -> "image/*"
        MessageKind.VIDEO -> "video/*"
        MessageKind.VOICE -> "audio/mp4"
        else -> "application/octet-stream"
    }

    /* ----------------------------------------------------------------------------- receipts */

    fun onReceipt(messageId: String, state: String) {
        val delivery = runCatching { DeliveryState.valueOf(state) }.getOrDefault(DeliveryState.READ)
        db.updateDelivery(messageId, delivery)
        messageFlows.values.forEach { flow ->
            if (flow.value.any { it.id == messageId }) {
                flow.value = flow.value.map { if (it.id == messageId) it.copy(delivery = delivery) else it }
            }
        }
    }

    fun onPresence(userId: String, online: Boolean) {
        users[userId]?.let { u ->
            users[userId] = u.copy(presence = if (online) app.bungram.data.model.Presence.ONLINE else app.bungram.data.model.Presence.OFFLINE)
        }
    }

    /* ------------------------------------------------------------------------------ typing */

    private val typingFlows = ConcurrentHashMap<String, MutableStateFlow<Boolean>>()
    private val typingExpiry = ConcurrentHashMap<String, Job>()

    override fun typing(chatId: String): StateFlow<Boolean> =
        typingFlows.getOrPut(chatId) { MutableStateFlow(false) }.asStateFlow()

    /**
     * A "stopped typing" frame is not guaranteed — the sender may drop off the network mid-word.
     * Every true therefore arms a timer that clears the flag, so the indicator can never stick.
     */
    override fun onTyping(chatId: String, userId: String, typing: Boolean) {
        if (userId == api.tokens.userId) return
        val flow = typingFlows.getOrPut(chatId) { MutableStateFlow(false) }
        typingExpiry.remove(chatId)?.cancel()
        flow.value = typing
        if (typing) {
            typingExpiry[chatId] = scope.launch {
                delay(TYPING_TTL_MS)
                flow.value = false
            }
        }
    }

    override suspend fun sendTyping(chatId: String, typing: Boolean) {
        runCatching { api.typing(chatId, typing) }
    }

    /* -------------------------------------------------------------------------------- read */

    override fun markRead(chatId: String) {
        activeChatId = chatId
        val chat = chat(chatId) ?: return
        if (chat.unreadCount == 0 && chat.lastMessageRead) return
        _chats.value = _chats.value.map { if (it.id == chatId) it.copy(unreadCount = 0, lastMessageRead = true) else it }
        db.setUnread(chatId, 0)
        scope.launch { runCatching { api.markRead(chatId) } }
    }

    fun leaveChat() {
        activeChatId = null
    }

    override fun markStoryViewed(storyId: String) {
        _stories.value = _stories.value.map { feed ->
            feed.copy(stories = feed.stories.map { if (it.id == storyId) it.copy(viewed = true) else it })
        }
    }

    /* ------------------------------------------------------------------------------ helpers */

    private fun insertIntoFlow(message: Message) {
        val flow = flowFor(message.chatId)
        flow.value = (flow.value + message).sortedWith(MESSAGE_ORDER)
    }

    private fun replaceInFlow(chatId: String, oldId: String, message: Message) {
        val flow = flowFor(chatId)
        flow.value = (flow.value.filterNot { it.id == oldId || it.id == message.id } + message).sortedWith(MESSAGE_ORDER)
    }

    private fun patchFlow(chatId: String, messageId: String, block: (Message) -> Message) {
        val flow = flowFor(chatId)
        flow.value = flow.value.map { if (it.id == messageId) block(it) else it }
    }

    private fun touchChat(message: Message) {
        val merged = _chats.value.map { chat ->
            if (chat.id != message.chatId) chat
            else chat.copy(
                lastMessagePreview = message.attachment?.fileName ?: message.text,
                lastMessageAtEpochMs = message.createdAtEpochMs,
                lastMessageFromMe = message.isOutgoing,
                lastMessageRead = false,
                unreadCount = 0,
            )
        }
        publishChats(merged)
    }

    private companion object {
        const val KEY_CURSOR = "sync_cursor"
        const val MAX_ATTEMPTS = 5

        /** Slightly longer than the composer's idle window so the bubble does not flicker. */
        const val TYPING_TTL_MS = 5_000L
        val MESSAGE_ORDER: Comparator<Message> =
            compareBy<Message> { it.createdAtEpochMs }.thenBy { it.id }
    }
}
