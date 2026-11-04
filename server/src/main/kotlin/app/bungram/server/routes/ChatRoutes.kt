package app.bungram.server.routes

import app.bungram.server.model.AckDto
import app.bungram.server.model.ChatDetailDto
import app.bungram.server.model.CreateGroupRequest
import app.bungram.server.model.DeleteAckDto
import app.bungram.server.model.DeviceDto
import app.bungram.server.model.DeviceRegistration
import app.bungram.server.model.Envelope
import app.bungram.server.model.ErrorBody
import app.bungram.server.model.InviteDto
import app.bungram.server.model.InvitePreviewDto
import app.bungram.server.model.MembersRequest
import app.bungram.server.model.MessageDto
import app.bungram.server.model.PublishKeysRequest
import app.bungram.server.model.StickerCatalogDto
import app.bungram.server.model.StickerDto
import app.bungram.server.model.StickerPackDto
import app.bungram.server.model.UpdateChatRequest
import app.bungram.server.realtime.Hub
import app.bungram.server.store.Store
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route

private fun ApplicationCall.uid(): String =
    principal<JWTPrincipal>()?.payload?.getClaim("uid")?.asString() ?: error("unauthenticated")

/**
 * Groups, channels, membership, invites, stickers, devices and pre-key distribution.
 *
 * Everything here is mounted inside `authenticate("auth-jwt")`, so [uid] is always present.
 */
fun Route.chatRoutes(store: Store, hub: Hub) {

    /* ------------------------------------------------------------------ groups & channels */

    post("/v1/chats/create") {
        val me = call.uid()
        val body = call.receive<CreateGroupRequest>()
        val kind = if (body.kind.uppercase() == "CHANNEL") "CHANNEL" else "GROUP"
        if (body.title.isBlank()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorBody(error = "title is required"))
        }
        if (kind == "GROUP" && body.memberIds.size > 199) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorBody(error = "a group holds 200 members"))
        }
        val chat = store.createGroup(me, body.title, body.description, body.memberIds, kind, body.isPublic)
        store.appendMessage(
            MessageDto(
                id = store.id("m"),
                chatId = chat.id,
                senderId = me,
                senderName = "Bungram",
                kind = "SYSTEM",
                text = if (kind == "CHANNEL") "Channel created" else "Group created",
                createdAtEpochMs = System.currentTimeMillis(),
                delivery = "READ",
            ),
        )
        store.members[chat.id]?.forEach { member ->
            if (member != me) hub.sendToUser(member, """{"type":"chat","chatId":"${chat.id}"}""")
        }
        call.respond(Envelope(data = store.project(chat, me)))
    }

    get("/v1/chats/{id}") {
        val me = call.uid()
        val chatId = call.parameters["id"].orEmpty()
        val chat = store.chats[chatId]
            ?: return@get call.respond(HttpStatusCode.NotFound, ErrorBody(error = "no such chat"))
        if (store.members[chatId]?.contains(me) != true) {
            return@get call.respond(HttpStatusCode.Forbidden, ErrorBody(error = "not a member"))
        }
        call.respond(
            Envelope(
                data = ChatDetailDto(
                    chat = store.project(chat, me),
                    members = store.memberList(chatId),
                    canManage = store.canManage(chatId, me),
                )
            )
        )
    }

    patch("/v1/chats/{id}") {
        val me = call.uid()
        val chatId = call.parameters["id"].orEmpty()
        if (!store.canManage(chatId, me)) {
            return@patch call.respond(HttpStatusCode.Forbidden, ErrorBody(error = "admins only"))
        }
        val body = call.receive<UpdateChatRequest>()
        val updated = store.updateChat(chatId, body.title, body.description)
            ?: return@patch call.respond(HttpStatusCode.NotFound, ErrorBody(error = "no such chat"))
        val text = buildString {
            append("Bungram ")
            if (body.title != null) append("renamed the chat to \"${updated.title}\"")
            if (body.description != null) {
                if (isNotEmpty()) append(" and ")
                append("updated the description")
            }
        }
        val sys = store.appendMessage(
            MessageDto(
                id = store.id("m"), chatId = chatId, senderId = me, senderName = "Bungram",
                kind = "SYSTEM", text = text, createdAtEpochMs = System.currentTimeMillis(),
                delivery = "READ",
            ),
        )
        store.members[chatId]?.forEach {
            hub.sendToUser(it, """{"type":"message","chatId":"$chatId","messageId":"${sys.id}"}""")
        }
        call.respond(Envelope(data = store.project(updated, me)))
    }

    delete("/v1/chats/{id}") {
        val me = call.uid()
        val chatId = call.parameters["id"].orEmpty()
        val chat = store.chats[chatId]
            ?: return@delete call.respond(HttpStatusCode.NotFound, ErrorBody(error = "no such chat"))
        if (chat.kind == "DIRECT") {
            // Deleting a 1:1 thread is local-only in every messenger; refuse instead of
            // destroying the other side's history.
            return@delete call.respond(HttpStatusCode.BadRequest, ErrorBody(error = "direct chats cannot be deleted for both sides"))
        }
        if (store.roleOf(chatId, me) != "OWNER") {
            return@delete call.respond(HttpStatusCode.Forbidden, ErrorBody(error = "owner only"))
        }
        val members = store.members[chatId].orEmpty().toList()
        store.deleteChat(chatId)
        members.forEach { hub.sendToUser(it, """{"type":"chat_deleted","chatId":"$chatId"}""") }
        call.respond(Envelope(data = DeleteAckDto(deleted = true)))
    }

    /* ------------------------------------------------------------------------- members */

    post("/v1/chats/{id}/members") {
        val me = call.uid()
        val chatId = call.parameters["id"].orEmpty()
        if (!store.canManage(chatId, me)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorBody(error = "admins only"))
        }
        val body = call.receive<MembersRequest>()
        val added = store.addMembers(chatId, body.userIds)
        if (added.isNotEmpty()) {
            val names = added.joinToString(", ") { it.displayName }
            val sys = store.appendMessage(
                MessageDto(
                    id = store.id("m"), chatId = chatId, senderId = me, senderName = "Bungram",
                    kind = "SYSTEM", text = "$names joined", createdAtEpochMs = System.currentTimeMillis(),
                    delivery = "READ",
                ),
            )
            store.members[chatId]?.forEach {
                hub.sendToUser(it, """{"type":"message","chatId":"$chatId","messageId":"${sys.id}"}""")
            }
        }
        call.respond(Envelope(data = store.memberList(chatId)))
    }

    delete("/v1/chats/{id}/members/{userId}") {
        val me = call.uid()
        val chatId = call.parameters["id"].orEmpty()
        val target = call.parameters["userId"].orEmpty()
        val self = target == me
        if (!self && !store.canManage(chatId, me)) {
            return@delete call.respond(HttpStatusCode.Forbidden, ErrorBody(error = "admins only"))
        }
        if (!self && store.roleOf(chatId, target) == "OWNER") {
            return@delete call.respond(HttpStatusCode.BadRequest, ErrorBody(error = "the owner cannot be removed"))
        }
        val name = store.userOrNull(target)?.displayName ?: "Member"
        if (!store.removeMember(chatId, target)) {
            return@delete call.respond(HttpStatusCode.NotFound, ErrorBody(error = "not a member"))
        }
        val sys = store.appendMessage(
            MessageDto(
                id = store.id("m"), chatId = chatId, senderId = me, senderName = "Bungram",
                kind = "SYSTEM",
                text = if (self) "$name left" else "$name was removed",
                createdAtEpochMs = System.currentTimeMillis(), delivery = "READ",
            ),
        )
        hub.sendToUser(target, """{"type":"chat_deleted","chatId":"$chatId"}""")
        store.members[chatId]?.forEach {
            hub.sendToUser(it, """{"type":"message","chatId":"$chatId","messageId":"${sys.id}"}""")
        }
        call.respond(Envelope(data = store.memberList(chatId)))
    }

    post("/v1/chats/{id}/members/{userId}/role") {
        val me = call.uid()
        val chatId = call.parameters["id"].orEmpty()
        if (store.roleOf(chatId, me) != "OWNER") {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorBody(error = "owner only"))
        }
        val target = call.parameters["userId"].orEmpty()
        val role = call.request.queryParameters["role"]?.uppercase() ?: "ADMIN"
        if (role !in setOf("ADMIN", "MEMBER")) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorBody(error = "role must be ADMIN or MEMBER"))
        }
        store.setRole(chatId, target, role)
        call.respond(Envelope(data = store.memberList(chatId)))
    }

    /* ------------------------------------------------------------------------- invites */

    post("/v1/chats/{id}/invite") {
        val me = call.uid()
        val chatId = call.parameters["id"].orEmpty()
        if (!store.canManage(chatId, me)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorBody(error = "admins only"))
        }
        val ttl = call.request.queryParameters["ttlHours"]?.toLongOrNull() ?: 0L
        call.respond(Envelope(data = store.mintInvite(chatId, me, ttl)))
    }

    delete("/v1/chats/{id}/invite") {
        val me = call.uid()
        val chatId = call.parameters["id"].orEmpty()
        if (!store.canManage(chatId, me)) {
            return@delete call.respond(HttpStatusCode.Forbidden, ErrorBody(error = "admins only"))
        }
        val slug = store.chats[chatId]?.inviteSlug
        val ok = slug != null && store.revokeInvite(slug)
        call.respond(Envelope(data = DeleteAckDto(deleted = ok)))
    }

    /** Public preview — the join screen must show the chat before the user commits. */
    get("/v1/invites/{slug}") {
        val me = call.uid()
        val slug = call.parameters["slug"].orEmpty()
        val rec = store.inviteBySlug(slug)
            ?: return@get call.respond(HttpStatusCode.NotFound, ErrorBody(error = "invite expired or revoked"))
        val chat = store.chats[rec.chatId]
            ?: return@get call.respond(HttpStatusCode.NotFound, ErrorBody(error = "chat no longer exists"))
        call.respond(
            Envelope(
                data = InvitePreviewDto(
                    slug = slug,
                    chatId = chat.id,
                    kind = chat.kind,
                    title = chat.title,
                    description = chat.description,
                    membersCount = store.members[chat.id]?.size ?: 0,
                    isPublic = chat.isPublic,
                    alreadyMember = store.members[chat.id]?.contains(me) == true,
                )
            )
        )
    }

    post("/v1/invites/{slug}/join") {
        val me = call.uid()
        val slug = call.parameters["slug"].orEmpty()
        val chat = store.joinBySlug(slug, me)
            ?: return@post call.respond(HttpStatusCode.NotFound, ErrorBody(error = "invite expired or revoked"))
        val sys = store.appendMessage(
            MessageDto(
                id = store.id("m"), chatId = chat.id, senderId = me,
                senderName = store.userOrNull(me)?.displayName ?: "Someone",
                kind = "SYSTEM",
                text = "${store.userOrNull(me)?.displayName ?: "Someone"} joined via invite link",
                createdAtEpochMs = System.currentTimeMillis(), delivery = "READ",
            ),
        )
        store.members[chat.id]?.forEach {
            hub.sendToUser(it, """{"type":"message","chatId":"${chat.id}","messageId":"${sys.id}"}""")
        }
        call.respond(Envelope(data = chat))
    }

    /* ------------------------------------------------------------------------ stickers */

    /**
     * Sticker catalog. Stickers ship as emoji glyphs so the feature works offline and with zero
     * asset weight; a pack item can later carry a `mediaId` and the client renders it instead.
     */
    get("/v1/stickers") {
        call.respond(Envelope(data = StickerCatalog(store).catalog()))
    }

    /* -------------------------------------------------------------------- devices / push */

    post("/v1/devices") {
        val me = call.uid()
        val body = call.receive<DeviceRegistration>()
        store.registerDevice(me, body.deviceId)
        store.deviceMeta["$me:${body.deviceId}"] = body
        call.respond(
            Envelope(
                data = DeviceDto(
                    id = body.deviceId,
                    provider = body.provider,
                    deviceName = body.deviceName,
                    lastSeenEpochMs = System.currentTimeMillis(),
                    current = true,
                )
            )
        )
    }

    get("/v1/devices") {
        val me = call.uid()
        val current = call.request.queryParameters["current"].orEmpty()
        call.respond(
            Envelope(
                data = store.devices[me].orEmpty().map { id ->
                    val meta = store.deviceMeta["$me:$id"]
                    DeviceDto(
                        id = id,
                        provider = meta?.provider ?: "none",
                        deviceName = meta?.deviceName ?: "device",
                        lastSeenEpochMs = System.currentTimeMillis(),
                        current = id == current,
                    )
                }
            )
        )
    }

    delete("/v1/devices/{id}") {
        val me = call.uid()
        val id = call.parameters["id"].orEmpty()
        val removed = store.devices[me]?.remove(id) == true
        store.deviceMeta.remove("$me:$id")
        call.respond(Envelope(data = DeleteAckDto(deleted = removed)))
    }

    /* ------------------------------------------------------------------------- pre-keys */

    post("/v1/keys") {
        val me = call.uid()
        val body = call.receive<PublishKeysRequest>()
        if (body.bundle.userId != me) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorBody(error = "cannot publish keys for another user"))
        }
        store.publishPreKeys(body.bundle, body.oneTimePreKeys)
        call.respond(Envelope(data = AckDto(count = store.oneTimeKeyCount(me, body.bundle.deviceId))))
    }

    /** Lets a client decide when to top up its one-time pool without re-uploading the bundle. */
    get("/v1/keys/status") {
        val me = call.uid()
        val deviceId = call.request.queryParameters["deviceId"].orEmpty()
        call.respond(Envelope(data = AckDto(count = store.oneTimeKeyCount(me, deviceId))))
    }

    get("/v1/keys/{userId}") {
        val userId = call.parameters["userId"].orEmpty()
        val deviceId = call.request.queryParameters["deviceId"]
        val bundle = store.takePreKeyBundle(userId, deviceId)
            ?: return@get call.respond(HttpStatusCode.NotFound, ErrorBody(error = "no keys published for that user"))
        call.respond(Envelope(data = bundle))
    }
}

/** Static catalog: 6 packs, 30 stickers each. Emoji covers every common reaction. */
private class StickerCatalog(private val store: Store) {

    fun catalog(): StickerCatalogDto = StickerCatalogDto(
        packs = listOf(
            pack("smileys", "Smileys", "🙂", SMILEYS),
            pack("gestures", "Gestures", "👍", GESTURES),
            pack("animals", "Animals", "🐼", ANIMALS),
            pack("food", "Food & Drink", "🍜", FOOD),
            pack("travel", "Travel", "✈️", TRAVEL),
            pack("symbols", "Symbols", "❤️", SYMBOLS),
        )
    )

    private fun pack(id: String, title: String, cover: String, emojis: List<String>) =
        StickerPackDto(
            id = id,
            title = title,
            cover = cover,
            stickers = emojis.mapIndexed { i, e -> StickerDto(id = "${id}_$i", emoji = e, packId = id) },
        )

    private companion object {
        val SMILEYS = listOf(
            "😀", "😃", "😄", "😁", "😆", "😅", "🤣", "😂", "🙂", "🙃",
            "😉", "😊", "😇", "🥰", "😍", "🤩", "😘", "😗", "😚", "😙",
            "🥲", "😋", "😛", "😜", "🤪", "😝", "🤑", "🤗", "🤭", "🤫",
        )
        val GESTURES = listOf(
            "👍", "👎", "👌", "🤌", "✌️", "🤞", "🤟", "🤘", "🤙", "👈",
            "👉", "👆", "👇", "☝️", "👋", "🤚", "🖐️", "✋", "🖖", "🫱",
            "🙏", "🤝", "💪", "🦾", "👏", "🙌", "👐", "🤲", "🫶", "✍️",
        )
        val ANIMALS = listOf(
            "🐶", "🐱", "🐭", "🐹", "🐰", "🦊", "🐻", "🐼", "🐨", "🐯",
            "🦁", "🐮", "🐷", "🐸", "🐵", "🐔", "🐧", "🐦", "🦆", "🦉",
            "🦄", "🐝", "🦋", "🐢", "🐙", "🦀", "🐬", "🐳", "🦈", "🐊",
        )
        val FOOD = listOf(
            "🍏", "🍎", "🍐", "🍊", "🍋", "🍌", "🍉", "🍇", "🍓", "🫐",
            "🍒", "🍑", "🥭", "🍍", "🥥", "🥝", "🍅", "🥑", "🍆", "🥕",
            "🍞", "🥐", "🥨", "🧀", "🍳", "🥞", "🍔", "🍟", "🍕", "🍜",
        )
        val TRAVEL = listOf(
            "✈️", "🚀", "🚁", "🚂", "🚗", "🚕", "🚙", "🚌", "🏍️", "🚲",
            "🛴", "⛵", "🚤", "🛳️", "🗺️", "🏔️", "🌋", "🏕️", "🏖️", "🏝️",
            "🌆", "🌃", "🌉", "🎡", "🎢", "🗼", "🏰", "⛲", "🌅", "🌌",
        )
        val SYMBOLS = listOf(
            "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "🤎", "💔",
            "❣️", "💕", "💞", "💓", "💗", "💖", "💘", "💝", "✨", "⭐",
            "🌟", "💫", "🔥", "💥", "💯", "🎉", "🎊", "🎁", "🏆", "🥇",
        )
    }
}
