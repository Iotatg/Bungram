package app.bungram.server.realtime

import kotlinx.coroutines.channels.Channel
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Fan-out hub. Each socket owns a bounded outbox channel; broadcasts are `trySend` so one slow
 * client can never block a sender. Dropped events are recovered by the client's cursor resync.
 */
class Hub {

    private data class Conn(
        val userId: String,
        val deviceId: String,
        val outbox: Channel<String>,
    )

    private val connections = ConcurrentHashMap<String, Conn>()

    fun register(userId: String, deviceId: String): Pair<String, Channel<String>> {
        val connId = UUID.randomUUID().toString()
        val outbox = Channel<String>(capacity = 128)
        connections[connId] = Conn(userId, deviceId, outbox)
        return connId to outbox
    }

    fun unregister(connId: String) {
        connections.remove(connId)?.outbox?.close()
    }

    fun onlineUserIds(): Set<String> = connections.values.map { it.userId }.toSet()

    fun isOnline(userId: String): Boolean = connections.values.any { it.userId == userId }

    fun sendToUsers(userIds: Collection<String>, payload: String): Int {
        var delivered = 0
        connections.values.forEach { conn ->
            if (conn.userId in userIds && conn.outbox.trySend(payload).isSuccess) delivered++
        }
        return delivered
    }

    fun sendToUser(userId: String, payload: String): Int = sendToUsers(listOf(userId), payload)

    fun presencePayload(userId: String, online: Boolean): String =
        """{"type":"presence","userId":"$userId","online":$online}"""

    fun connectionCount(): Int = connections.size
}
