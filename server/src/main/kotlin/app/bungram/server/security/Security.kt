package app.bungram.server.security

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import java.security.SecureRandom
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

class JwtService(secret: String, private val issuer: String = "bungram", private val audience: String = "bungram") {

    private val algorithm: Algorithm = Algorithm.HMAC256(secret)

    val verifier: JWTVerifier = JWT.require(algorithm)
        .withIssuer(issuer)
        .withAudience(audience)
        .build()

    fun accessToken(userId: String, ttlSeconds: Long = 3_600): String = JWT.create()
        .withIssuer(issuer)
        .withAudience(audience)
        .withClaim("uid", userId)
        .withClaim("typ", "access")
        .withIssuedAt(Date())
        .withExpiresAt(Date(System.currentTimeMillis() + ttlSeconds * 1_000))
        .sign(algorithm)

    fun refreshToken(userId: String, ttlSeconds: Long = 60L * 60 * 24 * 30): String = JWT.create()
        .withIssuer(issuer)
        .withAudience(audience)
        .withClaim("uid", userId)
        .withClaim("typ", "refresh")
        .withIssuedAt(Date())
        .withExpiresAt(Date(System.currentTimeMillis() + ttlSeconds * 1_000))
        .sign(algorithm)

    fun userId(token: String): String? = runCatching {
        verifier.verify(token).getClaim("uid").asString()
    }.getOrNull()

    fun isRefresh(token: String): Boolean = runCatching {
        verifier.verify(token).getClaim("typ").asString() == "refresh"
    }.getOrDefault(false)
}

/**
 * Phone verification. In production [issue] hands the code to an SMS provider (Twilio / local
 * aggregator) and never returns it; `devCode` is only populated when BUNGRAM_DEV=1.
 */
class OtpService(
    private val ttlSeconds: Long = 300,
    private val devMode: Boolean = System.getenv("BUNGRAM_DEV") == "1",
) {
    private data class Otp(val phone: String, val code: String, val expiresAt: Long, var attempts: Int = 0)

    private val challenges = ConcurrentHashMap<String, Otp>()
    private val random = SecureRandom()

    fun issue(phone: String): Pair<String, String> {
        val code = (100_000 + random.nextInt(900_000)).toString()
        val id = java.util.UUID.randomUUID().toString()
        challenges[id] = Otp(phone, code, System.currentTimeMillis() + ttlSeconds * 1_000)
        return id to code
    }

    fun verify(challengeId: String, code: String): String? {
        val otp = challenges[challengeId] ?: return null
        if (otp.expiresAt < System.currentTimeMillis()) {
            challenges.remove(challengeId)
            return null
        }
        if (otp.attempts >= 5) {
            challenges.remove(challengeId)
            return null
        }
        otp.attempts++
        if (otp.code != code.trim()) return null
        challenges.remove(challengeId)
        return otp.phone
    }

    fun devCode(challengeId: String): String? = if (devMode) challenges[challengeId]?.code else null
}

/** Sliding-window limiter, per phone number / per IP / per user. */
class RateLimiter(private val maxEvents: Int, private val windowMs: Long) {

    private val hits = ConcurrentHashMap<String, MutableList<Long>>()

    fun allow(key: String): Boolean {
        val now = System.currentTimeMillis()
        val list = hits.computeIfAbsent(key) { mutableListOf() }
        synchronized(list) {
            list.removeAll { it < now - windowMs }
            if (list.size >= maxEvents) return false
            list.add(now)
            return true
        }
    }
}
