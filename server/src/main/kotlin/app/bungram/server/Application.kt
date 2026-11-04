package app.bungram.server

import app.bungram.server.media.MediaSigner
import app.bungram.server.media.MediaStore
import app.bungram.server.model.Envelope
import app.bungram.server.model.ErrorBody
import app.bungram.server.model.Wire
import app.bungram.server.realtime.Hub
import app.bungram.server.push.PushDispatcher
import app.bungram.server.routes.configureRoutes
import app.bungram.server.security.JwtService
import app.bungram.server.security.OtpService
import app.bungram.server.security.RateLimiter
import app.bungram.server.store.Store
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.deflate
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.statuspages.StatusPages
import kotlinx.serialization.SerializationException
import io.ktor.server.response.respond
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import org.slf4j.event.Level
import kotlin.time.Duration.Companion.seconds

fun main() {
    // Must be set before any AWT class loads, otherwise ImageIO fails on headless hosts.
    System.setProperty("java.awt.headless", "true")
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    val jwt = JwtService(System.getenv("BUNGRAM_JWT_SECRET") ?: "dev-secret-change-me")
    val store = Store()
    val hub = Hub()
    val otp = OtpService(devMode = System.getenv("BUNGRAM_DEV") != "0")
    val otpLimiter = RateLimiter(maxEvents = 5, windowMs = 10 * 60_000)
    val sendLimiter = RateLimiter(maxEvents = 30, windowMs = 10_000)

    val mediaDir = System.getenv("BUNGRAM_MEDIA_DIR") ?: "data/media"
    val media = MediaStore(java.io.File(mediaDir))
    val signer = MediaSigner(System.getenv("BUNGRAM_JWT_SECRET") ?: "dev-secret-change-me")
    // Push only fires for members with no live socket; the hub is the source of truth for that.
    val push = PushDispatcher(store) { userId -> hub.isOnline(userId) }

    install(ContentNegotiation) { json(Wire.json) }
    install(DefaultHeaders)
    install(Compression) { gzip(); deflate() }
    // Range requests: without this a video cannot seek and a big photo cannot resume.
    install(PartialContent)
    install(CallLogging) { level = Level.INFO }
    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Patch)
    }
    install(WebSockets) {
        pingPeriod = 20.seconds
        timeout = 30.seconds
        maxFrameSize = 1_048_576
    }
    install(StatusPages) {
        // Malformed JSON / missing required fields are a client problem, not a 500.
        exception<BadRequestException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorBody(error = cause.message ?: "bad request"))
        }
        exception<SerializationException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorBody(error = cause.message ?: "malformed body"))
        }
        exception<ContentTransformationException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorBody(error = cause.message ?: "malformed body"))
        }
        exception<Throwable> { call, cause ->
            this@module.log.error("unhandled", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorBody(error = cause.message ?: "internal error"),
            )
        }
    }
    install(Authentication) {
        jwt("auth-jwt") {
            realm = "bungram"
            verifier(jwt.verifier)
            validate { credential ->
                if (credential.payload.getClaim("uid").asString() != null) JWTPrincipal(credential.payload) else null
            }
            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized, ErrorBody(error = "invalid or expired token"))
            }
        }
    }
    configureRoutes(store, hub, jwt, otp, otpLimiter, sendLimiter, media, signer, push)

    log.info("Bungram server ready — ${store.users.size} users, ${store.chats.size} chats, media=${media.stats()["blobs"]}")
}
