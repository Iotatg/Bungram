package app.bungram.server.push

import app.bungram.server.model.DeviceRegistration
import app.bungram.server.store.Store
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Wake-up delivery for members who have no live socket.
 *
 * Two providers:
 *  - **fcm**   — real Android push. Needs a service-account JSON in `BUNGRAM_FCM_SA`; without it
 *                the sender degrades to logging instead of failing the send path.
 *  - **ntfy**  — self-hosted UnifiedPush distributor. One HTTP POST, no vendor account.
 *
 * A push never carries message text: with E2EE the server cannot read it, and a notification
 * preview is exactly the kind of leak a messenger should not have. The client fetches the
 * message over its socket and decrypts locally.
 */
interface PushSender {
    fun notify(userId: String, chatId: String, senderName: String, encrypted: Boolean)
}

class PushDispatcher(private val store: Store, private val hub: OnlineCheck) : PushSender {

    fun interface OnlineCheck {
        fun isOnline(userId: String): Boolean
    }

    private val http: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    }

    private val fcmToken: String? = System.getenv("BUNGRAM_FCM_TOKEN")
    private val ntfyBase: String? = System.getenv("BUNGRAM_NTFY_BASE")

    override fun notify(userId: String, chatId: String, senderName: String, encrypted: Boolean) {
        if (hub.isOnline(userId)) return
        val devices = store.devices[userId].orEmpty().mapNotNull { store.deviceMeta["$userId:$it"] }
        devices.forEach { device ->
            when (device.provider) {
                "ntfy" -> sendNtfy(device, chatId, senderName, encrypted)
                "fcm" -> sendFcm(device, chatId, senderName, encrypted)
                else -> Unit // provider "none": the client polls on next foreground
            }
        }
    }

    private fun sendNtfy(device: DeviceRegistration, chatId: String, senderName: String, encrypted: Boolean) {
        val base = ntfyBase ?: return
        val body = if (encrypted) "New encrypted message" else "New message from $senderName"
        val req = HttpRequest.newBuilder(URI.create("$base/${device.pushToken}"))
            .timeout(Duration.ofSeconds(5))
            .header("Title", senderName)
            .header("X-Click", "bungram://chat/$chatId")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        runCatching { http.sendAsync(req, HttpResponse.BodyHandlers.discarding()) }
    }

    private fun sendFcm(device: DeviceRegistration, chatId: String, senderName: String, encrypted: Boolean) {
        val token = fcmToken ?: return
        val payload = """
            {"message":{"token":"${device.pushToken}",
             "data":{"chatId":"$chatId","sender":"$senderName","encrypted":"$encrypted"},
             "android":{"priority":"high","ttl":"3600s"}}}
        """.trimIndent()
        val req = HttpRequest.newBuilder(URI.create("https://fcm.googleapis.com/v1/projects/bungram/messages:send"))
            .timeout(Duration.ofSeconds(5))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()
        runCatching { http.sendAsync(req, HttpResponse.BodyHandlers.discarding()) }
    }
}
