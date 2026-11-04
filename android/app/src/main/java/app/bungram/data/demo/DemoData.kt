package app.bungram.data.demo

import app.bungram.data.model.Attachment
import app.bungram.data.model.CallDirection
import app.bungram.data.model.CallLog
import app.bungram.data.model.CallType
import app.bungram.data.model.Chat
import app.bungram.data.model.ChatKind
import app.bungram.data.model.DeliveryState
import app.bungram.data.model.Message
import app.bungram.data.model.MessageKind
import app.bungram.data.model.Presence
import app.bungram.data.model.Story
import app.bungram.data.model.StoryFeed
import app.bungram.data.model.User

/**
 * Offline seed that reproduces the reference screens pixel-for-pixel. It lets the UI be developed
 * and demoed with zero backend, and doubles as the fixture set for screenshot tests.
 */
object DemoData {

    const val ME_ID = "me"
    private const val MIN = 60_000L
    private const val HOUR = 60 * MIN
    private const val DAY = 24 * HOUR

    private fun now() = System.currentTimeMillis()

    val me = User(
        id = ME_ID,
        displayName = "Fitria Rahmani",
        phone = "+62 812 3456 7890",
        username = "fitri",
        about = "Product designer · Jakarta",
        presence = Presence.ONLINE,
    )

    val abdurrahman = User(
        id = "u_abdurrahman",
        displayName = "Abdurrahman Client",
        phone = "+62 811 2233 4455",
        username = "rahman",
        about = "Project manager @Snapie",
        isVerified = true,
        presence = Presence.ONLINE,
    )

    private val u9287 = User("u_9287", "+92 287 918273", presence = Presence.OFFLINE)
    private val uChrist = User("u_christ", "Christ Client", presence = Presence.AWAY)
    private val uJohan = User("u_johan", "Johan Fauzan", username = "johanf")
    private val u62 = User("u_62", "+62 85292410704")
    private val uFaza = User("u_faza", "Fazaaa", username = "fazaaa")
    private val uRiauli = User("u_riauli", "Riauli")
    private val uVito = User("u_vito", "Vito A")
    private val uMay = User("u_may", "May Adri")
    private val uFarman = User("u_farman", "Farman Baig")
    private val uJop = User("u_jop", "Jop")

    val people = listOf(
        uFaza, uRiauli, uVito, uMay, uFarman, uJop, abdurrahman, u9287, uChrist, uJohan, u62,
    )

    fun user(id: String): User? = people.firstOrNull { it.id == id } ?: if (id == ME_ID) me else null

    fun chats(): List<Chat> {
        val t = now()
        return listOf(
            Chat(
                id = "c_abdurrahman",
                kind = ChatKind.DIRECT,
                title = "Abdurrahman Client",
                peerId = abdurrahman.id,
                lastMessagePreview = "Okay! thanks Fitri!",
                lastMessageAtEpochMs = t - 40 * MIN,
                unreadCount = 0,
                lastMessageFromMe = true,
                lastMessageRead = true,
            ),
            Chat(
                id = "c_9287",
                kind = ChatKind.DIRECT,
                title = "+92 287 918273",
                peerId = u9287.id,
                lastMessagePreview = "Hello Momon! Nice to meet you!",
                lastMessageAtEpochMs = t - 3 * HOUR,
                unreadCount = 1,
                lastMessageFromMe = false,
            ),
            Chat(
                id = "c_christ",
                kind = ChatKind.DIRECT,
                title = "Christ Client",
                peerId = uChrist.id,
                lastMessagePreview = "I will waiting update from u!",
                lastMessageAtEpochMs = t - 9 * HOUR,
                unreadCount = 3,
                lastMessageFromMe = false,
            ),
            Chat(
                id = "c_johan",
                kind = ChatKind.DIRECT,
                title = "Johan Fauzan",
                peerId = uJohan.id,
                lastMessagePreview = "Hello Momon!",
                lastMessageAtEpochMs = t - DAY - 2 * HOUR,
                unreadCount = 3,
                lastMessageFromMe = false,
            ),
            Chat(
                id = "c_62",
                kind = ChatKind.DIRECT,
                title = "+62 85292410704",
                peerId = u62.id,
                lastMessagePreview = "https://dribbble.com/abdulmugni...",
                lastMessageAtEpochMs = t - 6 * DAY,
                unreadCount = 1,
                lastMessageFromMe = false,
            ),
            Chat(
                id = "c_faza",
                kind = ChatKind.DIRECT,
                title = "Fazaaa",
                peerId = uFaza.id,
                lastMessagePreview = "yo mene wae",
                lastMessageAtEpochMs = t - 7 * DAY,
                unreadCount = 0,
                lastMessageFromMe = false,
            ),
        )
    }

    fun messages(chatId: String): List<Message> {
        val t = now()
        return when (chatId) {
            "c_abdurrahman" -> listOf(
                Message(
                    id = "m1", chatId = chatId, senderId = abdurrahman.id,
                    senderName = abdurrahman.displayName,
                    text = "Hi Momon!\nHow about this project?",
                    createdAtEpochMs = t - 26 * HOUR - 50 * MIN,
                    delivery = DeliveryState.READ,
                ),
                Message(
                    id = "m2", chatId = chatId, senderId = ME_ID, senderName = me.displayName,
                    text = "Hi Fitrial 👋\nI will report you soon as possible.",
                    createdAtEpochMs = t - 26 * HOUR - 45 * MIN,
                    delivery = DeliveryState.READ, isOutgoing = true,
                ),
                Message(
                    id = "m3", chatId = chatId, senderId = abdurrahman.id,
                    senderName = abdurrahman.displayName,
                    text = "Sound good!🔥",
                    createdAtEpochMs = t - 26 * HOUR - 40 * MIN,
                    delivery = DeliveryState.READ,
                ),
                Message(
                    id = "m4", chatId = chatId, senderId = abdurrahman.id,
                    senderName = abdurrahman.displayName,
                    kind = MessageKind.FILE,
                    text = "This is brief from Snapie. You can check on this file!",
                    attachment = Attachment(
                        mimeType = "application/pdf",
                        fileName = "Mostate_Brief.pdf",
                        sizeBytes = 250_880,
                    ),
                    createdAtEpochMs = t - 26 * HOUR - 30 * MIN,
                    delivery = DeliveryState.READ,
                ),
                Message(
                    id = "m5", chatId = chatId, senderId = ME_ID, senderName = me.displayName,
                    text = "Okey! thanks Rahman!",
                    createdAtEpochMs = t - 25 * HOUR,
                    delivery = DeliveryState.READ, isOutgoing = true,
                ),
            )
            "c_christ" -> listOf(
                Message(
                    id = "m10", chatId = chatId, senderId = uChrist.id,
                    senderName = uChrist.displayName,
                    text = "Hi, is the dashboard ready for review?",
                    createdAtEpochMs = t - 10 * HOUR,
                    delivery = DeliveryState.READ,
                ),
                Message(
                    id = "m11", chatId = chatId, senderId = ME_ID, senderName = me.displayName,
                    text = "Almost! I will waiting update from u!",
                    createdAtEpochMs = t - 9 * HOUR,
                    delivery = DeliveryState.DELIVERED, isOutgoing = true,
                ),
            )
            "c_9287" -> listOf(
                Message(
                    id = "m20", chatId = chatId, senderId = u9287.id,
                    senderName = u9287.displayName,
                    text = "Hello Momon! Nice to meet you!",
                    createdAtEpochMs = t - 3 * HOUR,
                    delivery = DeliveryState.READ,
                ),
            )
            else -> listOf(
                Message(
                    id = "m30_$chatId", chatId = chatId, senderId = chatId,
                    senderName = "Bungram",
                    kind = MessageKind.SYSTEM,
                    text = "Messages are end-to-end encrypted. No one outside of this chat can read them.",
                    createdAtEpochMs = t - 2 * DAY,
                    delivery = DeliveryState.READ,
                ),
            )
        }
    }

    fun stories(): List<StoryFeed> {
        val t = now()
        fun feed(owner: User, viewed: Boolean, caption: String, seed: Int) = StoryFeed(
            owner = owner,
            stories = listOf(
                Story(
                    id = "s_${owner.id}_$seed",
                    owner = owner,
                    mediaUrl = "https://picsum.photos/seed/bungram$seed/600/900",
                    thumbnailUrl = "https://picsum.photos/seed/bungram$seed/300/450",
                    caption = caption,
                    createdAtEpochMs = t - (seed + 1) * HOUR,
                    expiresAtEpochMs = t + (24 - seed) * HOUR,
                    viewed = viewed,
                    viewCount = 12 * seed + 8,
                ),
            ),
        )
        return listOf(
            feed(me, true, "My status", 0),
            feed(uFaza, false, "Morning coffee", 1),
            feed(uRiauli, false, "Studio day", 2),
            feed(uVito, true, "5:47", 3),
            feed(uMay, false, "The Violet Room", 4),
            feed(uFarman, false, "Plant corner", 5),
        )
    }

    fun calls(): List<CallLog> {
        val t = now()
        return listOf(
            CallLog("cl1", abdurrahman, CallType.VIDEO, CallDirection.INCOMING, t - 2 * HOUR, 742),
            CallLog("cl2", uChrist, CallType.AUDIO, CallDirection.MISSED, t - 6 * HOUR),
            CallLog("cl3", uJohan, CallType.AUDIO, CallDirection.OUTGOING, t - DAY, 128),
            CallLog("cl4", u9287, CallType.VIDEO, CallDirection.INCOMING, t - 2 * DAY, 65),
        )
    }

    val quickStatusPeople get() = people.take(5)
}
