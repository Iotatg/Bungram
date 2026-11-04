package app.bungram.server.store

import app.bungram.server.model.AttachmentDto
import app.bungram.server.model.ChatDto
import app.bungram.server.model.MessageDto
import app.bungram.server.model.StoryDto
import app.bungram.server.model.StoryFeedDto
import app.bungram.server.model.UserDto
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory store with the exact shape of the Postgres schema in docs/03-ARCHITECTURE.md.
 * Swapping this class for Exposed/Jooq repositories is the only change needed to go persistent:
 * every method here maps 1:1 to a table.
 */
class Store {

    val users = ConcurrentHashMap<String, UserDto>()
    val chats = ConcurrentHashMap<String, ChatDto>()
    val members = ConcurrentHashMap<String, MutableSet<String>>()
    val messages = ConcurrentHashMap<String, MutableList<MessageDto>>()
    val stories = ConcurrentHashMap<String, MutableList<StoryDto>>()
    val viewedStories = ConcurrentHashMap<String, MutableSet<String>>()
    val devices = ConcurrentHashMap<String, MutableSet<String>>()

    /**
     * Idempotency index: "senderId:clientId" -> messageId.
     * A retried send (flaky network, app killed mid-request) must never create a duplicate.
     */
    private val clientIndex = ConcurrentHashMap<String, String>()
    private val byId = ConcurrentHashMap<String, MessageDto>()

    private val sequence = AtomicLong(1_000)
    private val msgSeq = AtomicLong(1_000)
    val startedAt = System.currentTimeMillis()

    init {
        seed()
    }

    fun nextId(prefix: String): String = "${prefix}_${sequence.incrementAndGet()}"

    /* --------------------------------------------------------------------------------------- */

    fun seed() {
        val me = UserDto(
            id = "u_demo",
            displayName = "Fitria Rahmani",
            phone = "+62 812 3456 7890",
            username = "fitri",
            about = "Product designer · Jakarta",
            presence = "ONLINE",
        )
        val rahman = UserDto(
            id = "u_abdurrahman",
            displayName = "Abdurrahman Client",
            phone = "+62 811 2233 4455",
            username = "rahman",
            about = "Project manager @Snapie",
            isVerified = true,
            presence = "ONLINE",
        )
        val christ = UserDto("u_christ", "Christ Client", "+62 813 9988 7766", "christ")
        val johan = UserDto("u_johan", "Johan Fauzan", "+62 878 1122 3344", "johanf")
        val faza = UserDto("u_faza", "Fazaaa", "+62 895 6677 8899", "fazaaa")

        val bungram = UserDto(
            id = "u_bungram",
            displayName = "Bungram",
            username = "bungram",
            about = "Your Bungram assistant",
            isVerified = true,
            presence = "ONLINE",
        )

        listOf(me, rahman, christ, johan, faza, bungram).forEach { users[it.id] = it }

        val now = System.currentTimeMillis()
        val chat1 = ChatDto(
            id = "c_abdurrahman", kind = "DIRECT", title = "Abdurrahman Client",
            peerId = rahman.id, membersCount = 2,
            lastMessagePreview = "Okay! thanks Fitri!", lastMessageAtEpochMs = now - 40 * 60_000,
            lastMessageFromMe = true, lastMessageRead = true,
        )
        val chat2 = ChatDto(
            id = "c_christ", kind = "DIRECT", title = "Christ Client",
            peerId = christ.id, membersCount = 2,
            lastMessagePreview = "I will waiting update from u!", lastMessageAtEpochMs = now - 9 * 3_600_000,
            unreadCount = 3,
        )
        val chat3 = ChatDto(
            id = "c_johan", kind = "DIRECT", title = "Johan Fauzan",
            peerId = johan.id, membersCount = 2,
            lastMessagePreview = "Hello Momon!", lastMessageAtEpochMs = now - 26 * 3_600_000, unreadCount = 3,
        )
        val chat4 = ChatDto(
            id = "c_faza", kind = "DIRECT", title = "Fazaaa",
            peerId = faza.id, membersCount = 2,
            lastMessagePreview = "yo mene wae", lastMessageAtEpochMs = now - 7 * 86_400_000,
        )
        listOf(chat1, chat2, chat3, chat4).forEach {
            chats[it.id] = it
            members[it.id] = mutableSetOf(me.id, it.peerId!!)
            messages[it.id] = mutableListOf()
        }

        messages["c_abdurrahman"] = mutableListOf(
            MessageDto(
                id = "m_1", chatId = "c_abdurrahman", senderId = rahman.id, senderName = rahman.displayName,
                text = "Hi Momon!\nHow about this project?", createdAtEpochMs = now - 27 * 3_600_000,
                delivery = "READ",
            ),
            MessageDto(
                id = "m_2", chatId = "c_abdurrahman", senderId = me.id, senderName = me.displayName,
                text = "Hi Fitrial 👋\nI will report you soon as possible.",
                createdAtEpochMs = now - 26 * 3_600_000, delivery = "READ", isOutgoing = true,
            ),
            MessageDto(
                id = "m_3", chatId = "c_abdurrahman", senderId = rahman.id, senderName = rahman.displayName,
                text = "Sound good!🔥", createdAtEpochMs = now - 25 * 3_600_000, delivery = "READ",
            ),
            MessageDto(
                id = "m_4", chatId = "c_abdurrahman", senderId = rahman.id, senderName = rahman.displayName,
                kind = "FILE", text = "This is brief from Snapie. You can check on this file!",
                attachment = AttachmentDto(
                    mimeType = "application/pdf", fileName = "Mostate_Brief.pdf", sizeBytes = 250_880,
                ),
                createdAtEpochMs = now - 25 * 3_600_000 + 300_000, delivery = "READ",
            ),
            MessageDto(
                id = "m_5", chatId = "c_abdurrahman", senderId = me.id, senderName = me.displayName,
                text = "Okey! thanks Rahman!", createdAtEpochMs = now - 24 * 3_600_000,
                delivery = "READ", isOutgoing = true,
            ),
        )

        var seedSeq = 100L
        messages.replaceAll { _, list ->
            list.mapTo(mutableListOf()) { it.copy(seq = seedSeq++) }
        }
        messages.values.flatten().forEach { byId[it.id] = it }

        stories["u_faza"] = mutableListOf(
            StoryDto(
                id = "s_1", owner = faza,
                mediaUrl = "https://picsum.photos/seed/bungram1/600/900",
                thumbnailUrl = "https://picsum.photos/seed/bungram1/300/450",
                caption = "Morning coffee", createdAtEpochMs = now - 3_600_000,
                expiresAtEpochMs = now + 20 * 3_600_000, viewCount = 41,
            ),
        )
        stories["u_johan"] = mutableListOf(
            StoryDto(
                id = "s_2", owner = johan,
                mediaUrl = "https://picsum.photos/seed/bungram2/600/900",
                thumbnailUrl = "https://picsum.photos/seed/bungram2/300/450",
                caption = "Studio day", createdAtEpochMs = now - 7_200_000,
                expiresAtEpochMs = now + 18 * 3_600_000, viewCount = 22,
            ),
        )
    }

    /* --------------------------------------------------------------------------------------- */

    fun chatsFor(userId: String): List<ChatDto> = chats.values
        .filter { members[it.id]?.contains(userId) == true }
        .sortedByDescending { it.lastMessageAtEpochMs }

    fun messagesFor(chatId: String, beforeMs: Long? = null, limit: Int = 100): List<MessageDto> {
        val all = messages[chatId] ?: return emptyList()
        val filtered = if (beforeMs == null) all else all.filter { it.createdAtEpochMs < beforeMs }
        return filtered.sortedBy { it.createdAtEpochMs }.takeLast(limit)
    }

    fun messageById(id: String): MessageDto? = byId[id]

    /** Returns the already-stored message for this idempotency key, or null. */
    fun findByClientId(senderId: String, clientId: String): MessageDto? {
        if (clientId.isBlank()) return null
        return clientIndex["$senderId:$clientId"]?.let { byId[it] }
    }

    fun appendMessage(message: MessageDto): MessageDto {
        val stored = if (message.seq != 0L) message else message.copy(seq = msgSeq.incrementAndGet())
        messages.computeIfAbsent(stored.chatId) { mutableListOf() }.add(stored)
        byId[stored.id] = stored
        stored.clientId?.let { if (it.isNotBlank()) clientIndex["${stored.senderId}:$it"] = stored.id }
        chats[stored.chatId]?.let { chat ->
            chats[chat.id] = chat.copy(
                lastMessagePreview = stored.attachment?.fileName ?: stored.text,
                lastMessageAtEpochMs = stored.createdAtEpochMs,
                lastMessageFromMe = stored.isOutgoing,
                lastMessageRead = false,
            )
        }
        return stored
    }

    /** Highest assigned sequence. Read *before* a sync query so nothing falls between two reads. */
    fun seqWatermark(): Long = msgSeq.get()

    fun markRead(chatId: String, userId: String) {
        markReadReturning(chatId, userId)
    }

    /**
     * Marks every peer message in the chat as READ and returns the ones that actually changed,
     * so the caller can tell the senders exactly once (no receipt storms on re-open).
     */
    fun markReadReturning(chatId: String, userId: String): List<MessageDto> {
        chats[chatId]?.let { chats[chatId] = it.copy(unreadCount = 0, lastMessageRead = true) }
        val list = messages[chatId] ?: return emptyList()
        val changed = mutableListOf<MessageDto>()
        for (i in list.indices) {
            val m = list[i]
            if (m.senderId != userId && m.delivery != "READ") {
                val updated = m.copy(delivery = "READ")
                list[i] = updated
                byId[updated.id] = updated
                changed += updated
            }
        }
        return changed
    }

    fun feedsFor(userId: String): List<StoryFeedDto> {
        val now = System.currentTimeMillis()
        return stories.map { (ownerId, list) ->
            val owner = users[ownerId] ?: return@map StoryFeedDto(UserDto(ownerId, ownerId), emptyList())
            StoryFeedDto(
                owner = owner,
                stories = list.filter { it.expiresAtEpochMs > now }.map {
                    it.copy(viewed = viewedStories[it.id]?.contains(userId) == true)
                },
            )
        }.filter { it.stories.isNotEmpty() }
    }

    fun addStory(userId: String, story: StoryDto) {
        stories.computeIfAbsent(userId) { mutableListOf() }.add(story)
    }

    fun markStoryViewed(storyId: String, userId: String) {
        viewedStories.computeIfAbsent(storyId) { mutableSetOf() }.add(userId)
    }

    fun registerDevice(userId: String, deviceId: String) {
        devices.computeIfAbsent(userId) { mutableSetOf() }.add(deviceId)
    }

    fun userOrNull(id: String): UserDto? = users[id]

    fun createUser(phone: String): UserDto {
        val id = nextId("u")
        val user = UserDto(
            id = id,
            displayName = phone,
            phone = phone,
            username = null,
            presence = "ONLINE",
        )
        users[id] = user
        provisionWelcomeChat(user)
        return user
    }

    /**
     * Every new account gets one system conversation, so the first launch is never an empty
     * screen. Mirrors Telegram's "Telegram" service chat and Signal's "Signal" note-to-self.
     */
    private fun provisionWelcomeChat(user: UserDto) {
        val bot = users["u_bungram"] ?: return
        val chatId = "c_welcome_${user.id}"
        val now = System.currentTimeMillis()
        chats[chatId] = ChatDto(
            id = chatId, kind = "DIRECT", title = "Bungram", peerId = bot.id, membersCount = 2,
            lastMessagePreview = "Welcome to Bungram 👋", lastMessageAtEpochMs = now, isVerified = true,
        )
        members[chatId] = mutableSetOf(user.id, bot.id)
        messages[chatId] = mutableListOf()
        // appendMessage assigns the sequence number; without it the message would never sync.
        appendMessage(
            MessageDto(
                id = nextId("m"), chatId = chatId, senderId = bot.id, senderName = bot.displayName,
                text = "Welcome to Bungram 👋\n\nThis is your private space. Once you verify a device, " +
                    "messages here are end-to-end encrypted — the server only ever sees ciphertext.",
                createdAtEpochMs = now, delivery = "DELIVERED",
            ),
        )
    }

    fun userByPhone(phone: String): UserDto? {
        val digits = phone.filter { it.isDigit() }
        if (digits.length < 6) return null
        return users.values.firstOrNull { it.phone?.filter { c -> c.isDigit() } == digits }
    }

    fun newChatFor(userId: String, peerId: String): ChatDto {
        // Idempotent: opening the same conversation twice must not create a second thread.
        chats.values.firstOrNull {
            it.kind == "DIRECT" && members[it.id]?.containsAll(listOf(userId, peerId)) == true
        }?.let { return it }

        val id = nextId("c")
        val peer = users[peerId] ?: error("unknown peer")
        val chat = ChatDto(
            id = id, kind = "DIRECT", title = peer.displayName, peerId = peerId, membersCount = 2,
            lastMessageAtEpochMs = System.currentTimeMillis(),
        )
        chats[id] = chat
        members[id] = mutableSetOf(userId, peerId)
        messages[id] = mutableListOf()
        return chat
    }

    fun id(prefix: String): String = "${prefix}_${UUID.randomUUID().toString().take(8)}"
}
