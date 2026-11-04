package app.bungram.server.routes

import app.bungram.server.model.AttachmentDto
import app.bungram.server.model.AuthChallenge
import app.bungram.server.model.AuthRequest
import app.bungram.server.model.AuthSession
import app.bungram.server.model.AuthVerify
import app.bungram.server.model.CallSignal
import app.bungram.server.model.ChatDto
import app.bungram.server.model.CreateChatRequest
import app.bungram.server.model.CreateStatusRequest
import app.bungram.server.model.AckDto
import app.bungram.server.model.Envelope
import app.bungram.server.model.HealthDto
import app.bungram.server.model.TokenPairDto
import app.bungram.server.model.ErrorBody
import app.bungram.server.model.MessageDto
import app.bungram.server.model.ReceiptEvent
import app.bungram.server.model.RefreshRequest
import app.bungram.server.model.SendMessageRequest
import app.bungram.server.model.StatusResponse
import app.bungram.server.model.StoryDto
import app.bungram.server.model.SyncResponse
import app.bungram.server.model.TypingEvent
import app.bungram.server.model.UserDto
import app.bungram.server.model.Wire
import app.bungram.server.realtime.Hub
import app.bungram.server.security.JwtService
import app.bungram.server.security.OtpService
import app.bungram.server.security.RateLimiter
import app.bungram.server.store.Store
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private fun ApplicationCall.userId(): String =
    principal<JWTPrincipal>()?.payload?.getClaim("uid")?.asString() ?: error("unauthenticated")

fun Application.configureRoutes(
    store: Store,
    hub: Hub,
    jwt: JwtService,
    otp: OtpService,
    otpLimiter: RateLimiter,
    sendLimiter: RateLimiter,
    media: app.bungram.server.media.MediaStore,
    signer: app.bungram.server.media.MediaSigner,
) {
    routing {

        mediaRoutes(media, signer, jwt, store)

        get("/v1/healthz") {
            call.respond(
                Envelope(
                    data = HealthDto(
                        status = "ok",
                        uptimeMs = System.currentTimeMillis() - store.startedAt,
                        connections = hub.connectionCount(),
                    )
                )
            )
        }

        /* ------------------------------------------------------------------ auth */

        route("/v1/auth") {
            post("/otp") {
                val body = call.receive<AuthRequest>()
                if (!otpLimiter.allow(body.phone)) {
                    call.respond(HttpStatusCode.TooManyRequests, ErrorBody(error = "too many attempts"))
                    return@post
                }
                val (challengeId, _) = otp.issue(body.phone)
                call.respond(
                    Envelope(
                        data = AuthChallenge(
                            challengeId = challengeId,
                            expiresInSec = 300,
                            devCode = otp.devCode(challengeId),
                        )
                    )
                )
            }

            post("/verify") {
                val body = call.receive<AuthVerify>()
                val phone = otp.verify(body.challengeId, body.code)
                    ?: return@post call.respond(
                        HttpStatusCode.Unauthorized,
                        ErrorBody(error = "invalid or expired code"),
                    )

                val existing = store.users.values.firstOrNull { it.phone == phone }
                val user = existing ?: store.createUser(phone)
                store.registerDevice(user.id, "android")
                store.users[user.id] = user.copy(presence = "ONLINE")

                call.respond(
                    Envelope(
                        data = AuthSession(
                            userId = user.id,
                            accessToken = jwt.accessToken(user.id),
                            refreshToken = jwt.refreshToken(user.id),
                            expiresInSec = 3600,
                            user = store.users[user.id]!!,
                        )
                    )
                )
            }

            post("/refresh") {
                val body = call.receive<RefreshRequest>()
                if (!jwt.isRefresh(body.refreshToken)) {
                    return@post call.respond(HttpStatusCode.Unauthorized, ErrorBody(error = "invalid refresh token"))
                }
                val uid = jwt.userId(body.refreshToken)
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorBody(error = "invalid refresh token"))
                call.respond(
                    Envelope(data = TokenPairDto(jwt.accessToken(uid), 3600))
                )
            }
        }

        /* ------------------------------------------------------------------ realtime */

        webSocket("/ws") {
            val token = call.request.queryParameters["token"].orEmpty()
            val userId = jwt.userId(token)
            if (userId == null) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
                return@webSocket
            }

            val (connId, outbox) = hub.register(userId, "android")
            val writer = launch {
                for (payload in outbox) send(Frame.Text(payload))
            }

            hub.sendToUser(userId, """{"type":"presence","userId":"$userId","online":true}""")

            try {
                for (frame in incoming) {
                    val text = (frame as? Frame.Text)?.readText() ?: continue
                    val obj = runCatching { Wire.json.parseToJsonElement(text).jsonObject }.getOrNull() ?: continue
                    when (obj["type"]?.jsonPrimitive?.content) {
                        "ping" -> send(Frame.Text("""{"type":"pong"}"""))
                        "typing" -> {
                            val chatId = obj["chatId"]?.jsonPrimitive?.content ?: continue
                            val typing = obj["typing"]?.jsonPrimitive?.content?.toBoolean() ?: false
                            val peers = store.members[chatId]?.filter { it != userId }.orEmpty()
                            hub.sendToUsers(
                                peers,
                                """{"type":"typing","chatId":"$chatId","userId":"$userId","typing":$typing}""",
                            )
                        }
                        "receipt" -> {
                            val chatId = obj["chatId"]?.jsonPrimitive?.content ?: continue
                            val read = store.markReadReturning(chatId, userId)
                            val peers = store.members[chatId]?.filter { it != userId }.orEmpty()
                            read.forEach { m ->
                                hub.sendToUsers(
                                    peers,
                                    """{"type":"receipt","chatId":"$chatId","messageId":"${m.id}","state":"READ"}""",
                                )
                            }
                        }
                    }
                }
            } finally {
                writer.cancel()
                hub.unregister(connId)
                hub.sendToUser(userId, """{"type":"presence","userId":"$userId","online":false}""")
            }
        }

        /* ------------------------------------------------------------------ authenticated API */

        authenticate("auth-jwt") {

            get("/v1/me") {
                val uid = call.userId()
                val user = store.userOrNull(uid)
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorBody(error = "unknown user"))
                call.respond(Envelope(data = user))
            }

            /**
             * Resolve a phone number to an account. Used by "new chat" — Bungram never uploads the
             * address book, the user types the number and the server answers with one match.
             */
            get("/v1/users/lookup") {
                val phone = call.request.queryParameters["phone"].orEmpty()
                val user = store.userByPhone(phone)
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorBody(error = "no account with that number"))
                call.respond(Envelope(data = user))
            }

            get("/v1/users/{id}") {
                val id = call.parameters["id"].orEmpty()
                val user = store.userOrNull(id)
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorBody(error = "unknown user"))
                call.respond(Envelope(data = user))
            }

            get("/v1/chats") {
                call.respond(Envelope(data = store.chatsFor(call.userId())))
            }

            post("/v1/chats") {
                val uid = call.userId()
                val body = call.receive<CreateChatRequest>()
                val chat = store.newChatFor(uid, body.peerId)
                call.respond(Envelope(data = chat))
            }

            get("/v1/chats/{id}/messages") {
                val uid = call.userId()
                val chatId = call.parameters["id"].orEmpty()
                if (store.members[chatId]?.contains(uid) != true) {
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorBody(error = "not a member"))
                }
                val before = call.request.queryParameters["before"]?.toLongOrNull()
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 50
                call.respond(Envelope(data = store.messagesFor(chatId, before, limit)))
            }

            post("/v1/messages") {
                val uid = call.userId()
                val req = call.receive<SendMessageRequest>()
                if (!sendLimiter.allow(uid)) {
                    return@post call.respond(HttpStatusCode.TooManyRequests, ErrorBody(error = "slow down"))
                }
                if (store.members[req.chatId]?.contains(uid) != true) {
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorBody(error = "not a member"))
                }
                val sender = store.userOrNull(uid)
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorBody(error = "unknown user"))

                // Idempotency: a retry with the same clientId returns the original message.
                store.findByClientId(uid, req.clientId)?.let {
                    return@post call.respond(Envelope(data = it))
                }

                val message = MessageDto(
                    id = store.id("m"),
                    chatId = req.chatId,
                    senderId = uid,
                    senderName = sender.displayName,
                    kind = req.kind,
                    text = req.ciphertext,
                    // The attachment is rebuilt from the blob record, never from client-supplied
                    // metadata: size, dimensions and MIME are facts the server measured itself.
                    attachment = req.attachmentId?.let { media.attachmentFor(it) },
                    // Server clock wins: two devices with skewed clocks must still agree on order.
                    createdAtEpochMs = System.currentTimeMillis(),
                    delivery = "SENT",
                    isOutgoing = true,
                    replyToId = req.replyToId,
                    clientId = req.clientId.ifBlank { null },
                )
                val stored = store.appendMessage(message)

                val peers = store.members[req.chatId]?.filter { it != uid }.orEmpty()
                hub.sendToUsers(
                    peers,
                    """{"type":"message","chatId":"${req.chatId}","messageId":"${message.id}","clientId":"${req.clientId}"}""",
                )
                call.respond(Envelope(data = stored))
            }

            post("/v1/chats/{id}/read") {
                val uid = call.userId()
                val chatId = call.parameters["id"].orEmpty()
                val read = store.markReadReturning(chatId, uid)
                // Tell the senders their message was read — the second tick in the bubble.
                val peers = store.members[chatId]?.filter { it != uid }.orEmpty()
                read.forEach { m ->
                    hub.sendToUsers(
                        peers,
                        """{"type":"receipt","chatId":"$chatId","messageId":"${m.id}","state":"READ"}""",
                    )
                }
                call.respond(Envelope(data = AckDto(chatId = chatId, unread = 0)))
            }

            post("/v1/chats/{id}/typing") {
                val uid = call.userId()
                val chatId = call.parameters["id"].orEmpty()
                val body = runCatching { call.receive<TypingEvent>() }.getOrNull()
                val typing = body?.typing ?: true
                val peers = store.members[chatId]?.filter { it != uid }.orEmpty()
                hub.sendToUsers(
                    peers,
                    """{"type":"typing","chatId":"$chatId","userId":"$uid","typing":$typing}""",
                )
                call.respond(Envelope(data = AckDto(typing = typing)))
            }

            /**
             * Delta sync. `cursor` is the server timestamp of the last seen message; the client
             * replays everything after it and stores the returned cursor locally.
             */
            get("/v1/sync") {
                val uid = call.userId()
                val cursor = call.request.queryParameters["cursor"]?.toLongOrNull() ?: 0L
                val watermark = store.seqWatermark()
                val chats = store.chatsFor(uid)
                val messages = chats.flatMap { chat ->
                    store.messagesFor(chat.id).filter { it.seq > cursor && it.seq <= watermark }
                }.sortedBy { it.seq }
                val next = watermark
                val memberIds = chats.flatMap { store.members[it.id].orEmpty() }.toSet() + uid
                val profiles = memberIds.mapNotNull { store.userOrNull(it) }
                call.respond(
                    Envelope(
                        data = SyncResponse(
                            chats = chats,
                            users = profiles,
                            messages = messages,
                            nextCursor = next.toString(),
                            hasMore = false,
                        )
                    )
                )
            }

            get("/v1/status") {
                val uid = call.userId()
                call.respond(Envelope(data = StatusResponse(feeds = store.feedsFor(uid))))
            }

            post("/v1/status") {
                val uid = call.userId()
                val body = call.receive<CreateStatusRequest>()
                val owner = store.userOrNull(uid)
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorBody(error = "unknown user"))
                val now = System.currentTimeMillis()
                val story = StoryDto(
                    id = store.id("s"),
                    owner = owner,
                    mediaUrl = body.mediaUrl,
                    thumbnailUrl = body.mediaUrl,
                    caption = body.caption,
                    createdAtEpochMs = now,
                    expiresAtEpochMs = now + body.ttlHours * 3_600_000L,
                )
                store.addStory(uid, story)
                call.respond(Envelope(data = story))
            }

            post("/v1/status/{id}/view") {
                val uid = call.userId()
                val storyId = call.parameters["id"].orEmpty()
                store.markStoryViewed(storyId, uid)
                call.respond(Envelope(data = AckDto(storyId = storyId, viewed = true)))
            }

            /**
             * WebRTC signalling relay: OFFER / ANSWER / ICE / HANGUP.
             * The server never touches media; it only forwards SDP/ICE to the peer's devices.
             */
            post("/v1/calls/{chatId}/signal") {
                val uid = call.userId()
                val chatId = call.parameters["chatId"].orEmpty()
                val signal = call.receive<CallSignal>()
                val peers = store.members[chatId]?.filter { it != uid }.orEmpty()
                val delivered = hub.sendToUsers(
                    peers,
                    """{"type":"call","callId":"${signal.callId}","chatId":"$chatId","action":"${signal.action}","payload":${signal.payload?.let { "\"$it\"" } ?: "null"}}""",
                )
                call.respond(Envelope(data = AckDto(delivered = delivered)))
            }
        }
    }
}
