package app.bungram.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import app.bungram.MainActivity
import app.bungram.R

/**
 * Phase-3 call shell. In production this becomes a `ConnectionService` so Bungram calls appear in
 * the system dialer and integrate with Android Auto / Bluetooth / watches. The signalling itself
 * (OFFER/ANSWER/ICE/HANGUP) travels over the existing WebSocket as `type:"call"` events.
 */
class CallService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_INCOMING -> {
                Notifications.ensureChannels(this)
                startForeground(NOTIF_ID, incomingNotification(intent.getStringExtra(EXTRA_TITLE) ?: "Bungram call"))
            }
            ACTION_END -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun incomingNotification(title: String): Notification {
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val decline = PendingIntent.getService(
            this, 2, Intent(this, CallService::class.java).setAction(ACTION_END),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, Notifications.CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText("Incoming call…")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentIntent(open)
            .addAction(0, "Decline", decline)
            .build()
    }

    companion object {
        private const val NOTIF_ID = 1002
        const val ACTION_INCOMING = "app.bungram.call.INCOMING"
        const val ACTION_END = "app.bungram.call.END"
        const val EXTRA_TITLE = "title"

        fun incoming(context: Context, title: String) {
            val intent = Intent(context, CallService::class.java)
                .setAction(ACTION_INCOMING)
                .putExtra(EXTRA_TITLE, title)
            context.startForegroundService(intent)
        }
    }
}
