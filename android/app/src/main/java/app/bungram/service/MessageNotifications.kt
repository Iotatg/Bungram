package app.bungram.service

import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import app.bungram.AppGraph
import app.bungram.MainActivity
import app.bungram.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Message notifications, built the way a chat app has to build them:
 *
 *   - one notification per chat, `MessagingStyle` so replies stack as a conversation;
 *   - an inline reply box that sends without opening the app;
 *   - "Mark as read" as a secondary action, and *dismissing* the notification marks it read too —
 *     a swipe is a read receipt, not an ignore;
 *   - everything grouped under one summary so ten chats do not become ten separate alerts.
 *
 * Notification text is whatever the chat list preview holds. For an encrypted chat that is the
 * literal string "Encrypted message": the notification is rendered by the system, so it must never
 * contain plaintext that the server does not already have.
 */
object MessageNotifications {

    const val GROUP_KEY = "bungram.messages"
    const val KEY_REPLY = "bungram.reply"
    const val ACTION_REPLY = "app.bungram.REPLY"
    const val ACTION_MARK_READ = "app.bungram.MARK_READ"
    private const val SUMMARY_ID = 1

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Posts a notification unless the user is already looking at that conversation.
     *
     * Suppressing in-app is not a nicety: a banner for the chat on screen is the single most
     * complained-about behaviour in every messenger.
     */
    fun onIncoming(context: Context, chatId: String) {
        val chat = AppGraph.repository.chat(chatId) ?: return
        if (AppGraph.isInForeground() && AppGraph.repository.activeChat() == chatId) return
        if (chat.isMuted) return

        val body = chat.lastMessagePreview.ifBlank { "New message" }
        val sender = if (chat.lastMessageFromMe) "You" else chat.title
        post(context, chatId, chat.title, sender, body, chat.isGroup)
    }

    fun post(
        context: Context,
        chatId: String,
        title: String,
        sender: String,
        body: String,
        isGroup: Boolean,
    ) {
        Notifications.ensureChannels(context)
        if (!canPost(context)) return

        val person = Person.Builder().setName(sender).setImportant(isGroup).build()
        val open = PendingIntent.getActivity(
            context,
            chatId.hashCode(),
            Intent(context, MainActivity::class.java)
                .putExtra("chatId", chatId)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val style = NotificationCompat.MessagingStyle(Person.Builder().setName("You").build())
            .setGroupConversation(isGroup)
            .addMessage(body, System.currentTimeMillis(), person)

        val reply = replyAction(context, chatId)
        val markRead = markReadAction(context, chatId)
        val dismiss = PendingIntent.getBroadcast(
            context,
            chatId.hashCode() + 7,
            Intent(context, MarkReadReceiver::class.java).putExtra(EXTRA_CHAT_ID, chatId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setDeleteIntent(dismiss)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setGroup(GROUP_KEY)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .addAction(reply)
            .addAction(markRead)
            .build()

        NotificationManagerCompat.from(context).notify(chatId.hashCode(), notification)
        postSummary(context)
        app.bungram.core.Sounds.receive()
    }

    /** Clears a chat's notification — called when the user opens or reads that conversation. */
    fun clear(context: Context, chatId: String) {
        NotificationManagerCompat.from(context).cancel(chatId.hashCode())
    }

    private fun postSummary(context: Context) {
        val summary = NotificationCompat.Builder(context, Notifications.CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Bungram")
            .setContentText("New messages")
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        NotificationManagerCompat.from(context).notify(SUMMARY_ID, summary)
    }

    private fun replyAction(context: Context, chatId: String): NotificationCompat.Action {
        val remoteInput = RemoteInput.Builder(KEY_REPLY)
            .setLabel("Reply")
            .build()
        val intent = PendingIntent.getBroadcast(
            context,
            chatId.hashCode() + 1,
            Intent(context, ReplyReceiver::class.java).putExtra(EXTRA_CHAT_ID, chatId),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Action.Builder(R.drawable.ic_send, "Reply", intent)
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(true)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .build()
    }

    private fun markReadAction(context: Context, chatId: String): NotificationCompat.Action {
        val intent = PendingIntent.getBroadcast(
            context,
            chatId.hashCode() + 2,
            Intent(context, MarkReadReceiver::class.java).putExtra(EXTRA_CHAT_ID, chatId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Action.Builder(R.drawable.ic_done, "Mark as read", intent)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .build()
    }

    private fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
        return true
    }

    /** Attach the small reply-send coroutine to a receiver without blocking the main thread. */
    internal fun launch(block: suspend () -> Unit) {
        scope.launch { runCatching { block() } }
    }

    const val EXTRA_CHAT_ID = "chat_id"
}

/**
 * Inline reply. `goAsync()` keeps the process alive long enough to hand the message to the outbox;
 * the outbox is durable, so even if the process dies here the message still goes out.
 */
class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val chatId = intent.getStringExtra(MessageNotifications.EXTRA_CHAT_ID) ?: return
        val text = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(MessageNotifications.KEY_REPLY)
            ?.toString()
            ?.trim()
            .orEmpty()
        if (text.isEmpty()) return

        val pending = goAsync()
        MessageNotifications.launch {
            try {
                AppGraph.repository.send(chatId, text)
            } finally {
                pending.finish()
            }
        }
        MessageNotifications.clear(context, chatId)
    }
}

/** "Mark as read", and the same thing when the user swipes the notification away. */
class MarkReadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val chatId = intent.getStringExtra(MessageNotifications.EXTRA_CHAT_ID) ?: return
        MessageNotifications.clear(context, chatId)
        val pending = goAsync()
        MessageNotifications.launch {
            try {
                AppGraph.repository.markRead(chatId)
            } finally {
                pending.finish()
            }
        }
    }
}

/** Kept for callers that still want the raw builder (the foreground service uses it). */
internal fun Notification.isMessage(): Boolean =
    this.extras?.getString(NotificationCompat.EXTRA_TITLE) != null
