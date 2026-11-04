package app.bungram.server.media

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Signed, expiring media URLs.
 *
 * The normal path is an `Authorization: Bearer` header on every request (Coil does this for us),
 * but a signed URL lets a link keep working in a browser, an <img> tag or a share sheet without
 * ever putting the user's access token in a URL. Format: `?t=<exp>.<base64url(hmac)>`.
 */
class MediaSigner(secret: String) {

    private val key = SecretKeySpec(secret.toByteArray(), "HmacSHA256")

    private fun mac(payload: String): String {
        val h = Mac.getInstance("HmacSHA256").apply { init(key) }
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(h.doFinal(payload.toByteArray()))
    }

    fun sign(mediaId: String, ttlSeconds: Long = 24 * 3_600): String {
        val exp = System.currentTimeMillis() / 1_000 + ttlSeconds
        return "$exp.${mac("$mediaId.$exp")}"
    }

    fun verify(mediaId: String, token: String): Boolean {
        val parts = token.split('.', limit = 2)
        if (parts.size != 2) return false
        val exp = parts[0].toLongOrNull() ?: return false
        if (exp < System.currentTimeMillis() / 1_000) return false
        val expected = mac("$mediaId.$exp")
        // Constant-time compare: a timing oracle here would let an attacker forge URLs byte by byte.
        return MessageDigest.isEqual(expected.toByteArray(), parts[1].toByteArray())
    }
}
