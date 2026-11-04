package app.bungram.data.local

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.bungram.data.model.Attachment
import app.bungram.data.model.Chat
import app.bungram.data.model.ChatKind
import app.bungram.data.model.DeliveryState
import app.bungram.data.model.Message
import app.bungram.data.model.MessageKind
import app.bungram.data.model.Presence
import app.bungram.data.model.User
import app.bungram.data.remote.Wire

/** One pending send. Survives process death, so a message composed offline is never lost. */
data class OutboxRow(
    val clientId: String,
    val chatId: String,
    val kind: MessageKind,
    val text: String,
    val createdAtEpochMs: Long,
    val attempts: Int = 0,
    val lastError: String? = null,
    /** Set once the bytes are on the server; a retry then only re-sends the message. */
    val attachmentId: String? = null,
    /** Local URI kept so a failed upload can be retried after a restart. */
    val localPath: String? = null,
)

/**
 * Bungram's own storage engine: a thin, hand-written SQLite layer (no ORM, no code generation).
 *
 * Why hand-written instead of Room: the whole point of this phase is that Bungram owns its stack.
 * There are six tables and a dozen statements — an ORM would add a compiler plugin and an
 * abstraction layer for no benefit. Every query below is visible, indexable and explainable.
 *
 * Tables:
 *   users    – profile cache (id -> display name, presence, ...)
 *   chats    – conversation list, exactly what the list screen renders
 *   messages – the local truth, including optimistic rows (id = "l_<clientId>")
 *   outbox   – durable queue of messages not yet acknowledged by the server
 *   meta     – key/value: sync cursor, schema markers
 */
class BungramDb(context: Context) : SQLiteOpenHelper(context.applicationContext, NAME, null, VERSION) {

    init {
        // Readers (UI) never block behind the outbox writer.
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE users(
                id TEXT PRIMARY KEY,
                display_name TEXT NOT NULL,
                phone TEXT,
                username TEXT,
                avatar_url TEXT,
                about TEXT NOT NULL DEFAULT '',
                is_verified INTEGER NOT NULL DEFAULT 0,
                presence TEXT NOT NULL DEFAULT 'OFFLINE',
                last_seen INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE chats(
                id TEXT PRIMARY KEY,
                kind TEXT NOT NULL,
                title TEXT NOT NULL,
                avatar_url TEXT,
                peer_id TEXT,
                members_count INTEGER NOT NULL DEFAULT 0,
                preview TEXT NOT NULL DEFAULT '',
                last_at INTEGER NOT NULL DEFAULT 0,
                unread INTEGER NOT NULL DEFAULT 0,
                is_muted INTEGER NOT NULL DEFAULT 0,
                is_pinned INTEGER NOT NULL DEFAULT 0,
                is_verified INTEGER NOT NULL DEFAULT 0,
                from_me INTEGER NOT NULL DEFAULT 0,
                is_read INTEGER NOT NULL DEFAULT 1,
                description TEXT NOT NULL DEFAULT '',
                invite_slug TEXT,
                owner_id TEXT,
                my_role TEXT NOT NULL DEFAULT 'MEMBER',
                can_post INTEGER NOT NULL DEFAULT 1,
                is_secret INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE messages(
                id TEXT PRIMARY KEY,
                chat_id TEXT NOT NULL,
                sender_id TEXT NOT NULL,
                sender_name TEXT NOT NULL,
                kind TEXT NOT NULL,
                body TEXT NOT NULL DEFAULT '',
                attachment TEXT,
                created_at INTEGER NOT NULL,
                delivery TEXT NOT NULL,
                outgoing INTEGER NOT NULL DEFAULT 0,
                client_id TEXT,
                reply_to TEXT,
                encrypted INTEGER NOT NULL DEFAULT 0,
                decrypt_failed INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_messages_chat ON messages(chat_id, created_at)")
        db.execSQL("CREATE INDEX idx_messages_client ON messages(client_id)")
        db.execSQL(
            """
            CREATE TABLE outbox(
                client_id TEXT PRIMARY KEY,
                chat_id TEXT NOT NULL,
                kind TEXT NOT NULL,
                body TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                attempts INTEGER NOT NULL DEFAULT 0,
                last_error TEXT,
                attachment_id TEXT,
                local_path TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Pre-1.0: local cache is disposable, a wipe is cheaper and safer than a migration ladder.
        listOf("messages", "outbox", "chats", "users", "meta").forEach { db.execSQL("DROP TABLE IF EXISTS $it") }
        onCreate(db)
    }

    /* ------------------------------------------------------------------------------ users */

    fun upsertUsers(users: Collection<User>) {
        if (users.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            users.forEach { u ->
                db.insertWithOnConflict("users", null, userValues(u), SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun users(): List<User> = readableDatabase.query("users", null, null, null, null, null, null).use { c ->
        buildList { while (c.moveToNext()) add(c.toUser()) }
    }

    /* ------------------------------------------------------------------------------ chats */

    fun upsertChats(chats: Collection<Chat>) {
        if (chats.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            chats.forEach { ch ->
                db.insertWithOnConflict("chats", null, chatValues(ch), SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun chats(): List<Chat> = readableDatabase
        .query("chats", null, null, null, null, null, "last_at DESC")
        .use { c -> buildList { while (c.moveToNext()) add(c.toChat()) } }

    fun setUnread(chatId: String, unread: Int) {
        val v = ContentValues().apply {
            put("unread", unread)
            if (unread == 0) put("is_read", 1)
        }
        writableDatabase.update("chats", v, "id = ?", arrayOf(chatId))
    }

    /* --------------------------------------------------------------------------- messages */

    fun upsertMessages(messages: Collection<Message>) {
        if (messages.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            messages.forEach { m ->
                db.insertWithOnConflict("messages", null, messageValues(m), SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun messagesFor(chatId: String, limit: Int = 300): List<Message> = readableDatabase.query(
        "messages", null, "chat_id = ?", arrayOf(chatId), null, null, "created_at ASC, id ASC", limit.toString(),
    ).use { c -> buildList { while (c.moveToNext()) add(c.toMessage()) } }

    fun allMessages(): List<Message> = readableDatabase
        .query("messages", null, null, null, null, null, "chat_id ASC, created_at ASC")
        .use { c -> buildList { while (c.moveToNext()) add(c.toMessage()) } }

    fun updateDelivery(id: String, state: DeliveryState) {
        writableDatabase.update(
            "messages",
            ContentValues().apply { put("delivery", state.name) },
            "id = ?", arrayOf(id),
        )
    }

    fun deleteMessage(id: String) {
        writableDatabase.delete("messages", "id = ?", arrayOf(id))
    }

    fun findMessageByClientId(clientId: String): Message? = readableDatabase.query(
        "messages", null, "client_id = ?", arrayOf(clientId), null, null, null, "1",
    ).use { c -> if (c.moveToFirst()) c.toMessage() else null }

    /* ----------------------------------------------------------------------------- outbox */

    fun enqueue(row: OutboxRow) {
        writableDatabase.insertWithOnConflict("outbox", null, ContentValues().apply {
            put("client_id", row.clientId)
            put("chat_id", row.chatId)
            put("kind", row.kind.name)
            put("body", row.text)
            put("created_at", row.createdAtEpochMs)
            put("attempts", row.attempts)
            put("last_error", row.lastError)
            put("attachment_id", row.attachmentId)
            put("local_path", row.localPath)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun outbox(): List<OutboxRow> = readableDatabase
        .query("outbox", null, null, null, null, null, "created_at ASC")
        .use { c ->
            buildList {
                while (c.moveToNext()) add(
                    OutboxRow(
                        clientId = c.str("client_id"),
                        chatId = c.str("chat_id"),
                        kind = c.enumOf("kind", MessageKind.TEXT),
                        text = c.str("body"),
                        createdAtEpochMs = c.long("created_at"),
                        attempts = c.getInt(c.getColumnIndexOrThrow("attempts")),
                        lastError = c.optStr("last_error"),
                        attachmentId = c.optStr("attachment_id"),
                        localPath = c.optStr("local_path"),
                    )
                )
            }
        }

    fun outboxSize(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM outbox", null).use {
        if (it.moveToFirst()) it.getInt(0) else 0
    }

    fun removeOutbox(clientId: String) {
        writableDatabase.delete("outbox", "client_id = ?", arrayOf(clientId))
    }

    fun bumpAttempt(clientId: String, error: String?) {
        writableDatabase.execSQL(
            "UPDATE outbox SET attempts = attempts + 1, last_error = ? WHERE client_id = ?",
            arrayOf(error, clientId),
        )
    }

    /* ------------------------------------------------------------------------------- meta */

    fun meta(key: String): String? = readableDatabase
        .query("meta", arrayOf("v"), "k = ?", arrayOf(key), null, null, null, "1")
        .use { if (it.moveToFirst()) it.getString(0) else null }

    fun setMeta(key: String, value: String?) {
        if (value == null) writableDatabase.delete("meta", "k = ?", arrayOf(key))
        else writableDatabase.insertWithOnConflict(
            "meta", null, ContentValues().apply { put("k", key); put("v", value) },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    /** Used on logout: nothing on disk may outlive the session. */
    fun wipe() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            listOf("messages", "outbox", "chats", "users", "meta").forEach { db.delete(it, null, null) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /* ---------------------------------------------------------------------------- mapping */

    private fun userValues(u: User) = ContentValues().apply {
        put("id", u.id)
        put("display_name", u.displayName)
        put("phone", u.phone)
        put("username", u.username)
        put("avatar_url", u.avatarUrl)
        put("about", u.about)
        put("is_verified", if (u.isVerified) 1 else 0)
        put("presence", u.presence.name)
        put("last_seen", u.lastSeenEpochMs)
    }

    private fun chatValues(c: Chat) = ContentValues().apply {
        put("id", c.id)
        put("kind", c.kind.name)
        put("title", c.title)
        put("avatar_url", c.avatarUrl)
        put("peer_id", c.peerId)
        put("members_count", c.membersCount)
        put("preview", c.lastMessagePreview)
        put("last_at", c.lastMessageAtEpochMs)
        put("unread", c.unreadCount)
        put("is_muted", if (c.isMuted) 1 else 0)
        put("is_pinned", if (c.isPinned) 1 else 0)
        put("is_verified", if (c.isVerified) 1 else 0)
        put("from_me", if (c.lastMessageFromMe) 1 else 0)
        put("is_read", if (c.lastMessageRead) 1 else 0)
        put("description", c.description)
        put("invite_slug", c.inviteSlug)
        put("owner_id", c.ownerId)
        put("my_role", c.myRole.name)
        put("can_post", if (c.canPost) 1 else 0)
        put("is_secret", if (c.isSecret) 1 else 0)
    }

    private fun messageValues(m: Message) = ContentValues().apply {
        put("id", m.id)
        put("chat_id", m.chatId)
        put("sender_id", m.senderId)
        put("sender_name", m.senderName)
        put("kind", m.kind.name)
        put("body", m.text)
        put("attachment", m.attachment?.let { Wire.json.encodeToString(Attachment.serializer(), it) })
        put("created_at", m.createdAtEpochMs)
        put("delivery", m.delivery.name)
        put("outgoing", if (m.isOutgoing) 1 else 0)
        put("client_id", if (m.id.startsWith(LOCAL_PREFIX)) m.id.removePrefix(LOCAL_PREFIX) else null)
        put("reply_to", m.replyToId)
        put("encrypted", if (m.isEncrypted) 1 else 0)
        put("decrypt_failed", if (m.decryptFailed) 1 else 0)
    }

    private fun Cursor.str(col: String): String = getString(getColumnIndexOrThrow(col)) ?: ""

    private fun Cursor.optStr(col: String): String? {
        val i = getColumnIndexOrThrow(col)
        return if (isNull(i)) null else getString(i)
    }

    private fun Cursor.long(col: String): Long = getLong(getColumnIndexOrThrow(col))

    private fun Cursor.boolean(col: String): Boolean = getInt(getColumnIndexOrThrow(col)) != 0

    private inline fun <reified E : Enum<E>> Cursor.enumOf(col: String, fallback: E): E =
        runCatching { enumValueOf<E>(str(col)) }.getOrDefault(fallback)

    private fun Cursor.toUser() = User(
        id = str("id"),
        displayName = str("display_name"),
        phone = optStr("phone"),
        username = optStr("username"),
        avatarUrl = optStr("avatar_url"),
        about = str("about"),
        isVerified = boolean("is_verified"),
        presence = enumOf("presence", Presence.OFFLINE),
        lastSeenEpochMs = long("last_seen"),
    )

    private fun Cursor.toChat() = Chat(
        id = str("id"),
        kind = enumOf("kind", ChatKind.DIRECT),
        title = str("title"),
        avatarUrl = optStr("avatar_url"),
        peerId = optStr("peer_id"),
        membersCount = getInt(getColumnIndexOrThrow("members_count")),
        lastMessagePreview = str("preview"),
        lastMessageAtEpochMs = long("last_at"),
        unreadCount = getInt(getColumnIndexOrThrow("unread")),
        isMuted = boolean("is_muted"),
        isPinned = boolean("is_pinned"),
        isVerified = boolean("is_verified"),
        lastMessageFromMe = boolean("from_me"),
        lastMessageRead = boolean("is_read"),
        description = str("description"),
        inviteSlug = optStr("invite_slug"),
        ownerId = optStr("owner_id"),
        myRole = enumOf("my_role", app.bungram.data.model.ChatRole.MEMBER),
        canPost = boolean("can_post"),
        isSecret = boolean("is_secret"),
    )

    private fun Cursor.toMessage(): Message {
        val attJson = optStr("attachment")
        return Message(
            id = str("id"),
            chatId = str("chat_id"),
            senderId = str("sender_id"),
            senderName = str("sender_name"),
            kind = enumOf("kind", MessageKind.TEXT),
            text = str("body"),
            attachment = attJson?.let { runCatching { Wire.json.decodeFromString(Attachment.serializer(), it) }.getOrNull() },
            createdAtEpochMs = long("created_at"),
            delivery = enumOf("delivery", DeliveryState.SENT),
            isOutgoing = boolean("outgoing"),
            replyToId = optStr("reply_to"),
            isEncrypted = boolean("encrypted"),
            decryptFailed = boolean("decrypt_failed"),
        )
    }

    companion object {
        const val LOCAL_PREFIX = "l_"
        private const val NAME = "bungram.db"
        private const val VERSION = 4
    }
}
