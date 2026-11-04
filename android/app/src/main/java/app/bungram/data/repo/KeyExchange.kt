package app.bungram.data.repo

import app.bungram.crypto.B64
import app.bungram.crypto.E2ee
import app.bungram.crypto.PreKeyBundle
import app.bungram.data.remote.OneTimePreKeyDto
import app.bungram.data.remote.PreKeyBundleDto

/**
 * Translation layer between the crypto engine (raw bytes, no networking) and the API DTOs
 * (base64, `kotlinx.serialization`).
 *
 * Keeping the conversion here means `crypto/` stays a pure, JVM-testable module and `data/remote`
 * stays a pure transport layer; neither has to know about the other.
 */
internal object KeyExchange {

    /** Server bundle -> crypto bundle. Null when the payload is malformed or unsigned. */
    fun toBundle(dto: PreKeyBundleDto): PreKeyBundle? {
        val identityDh = B64.decodeOrNull(dto.identityKey) ?: return null
        val identitySign = B64.decodeOrNull(dto.identitySignKey) ?: return null
        val signedPreKey = B64.decodeOrNull(dto.signedPreKey) ?: return null
        val signature = B64.decodeOrNull(dto.signedPreKeySignature) ?: return null
        return PreKeyBundle(
            userId = dto.userId,
            deviceId = dto.deviceId,
            identityDhPublic = identityDh,
            identitySignPublic = identitySign,
            signedPreKeyId = dto.signedPreKeyId,
            signedPreKeyPublic = signedPreKey,
            signedPreKeySignature = signature,
            oneTimePreKeyId = dto.oneTimePreKeyId,
            oneTimePreKeyPublic = dto.oneTimePreKey?.let(B64::decode),
        )
    }

    /** What this device publishes. The one-time keys travel separately so the server can pool them. */
    fun toDto(engine: E2ee, userId: String): PreKeyBundleDto {
        val bundle = engine.myBundle()
        return PreKeyBundleDto(
            userId = userId,
            deviceId = engine.deviceId(),
            identityKey = B64.encode(bundle.identityDhPublic),
            identitySignKey = B64.encode(bundle.identitySignPublic),
            signedPreKeyId = bundle.signedPreKeyId,
            signedPreKey = B64.encode(bundle.signedPreKeyPublic),
            signedPreKeySignature = B64.encode(bundle.signedPreKeySignature),
        )
    }

    fun oneTimeKeys(keys: List<E2ee.OneTimePreKey>): List<OneTimePreKeyDto> =
        keys.map { OneTimePreKeyDto(it.id, B64.encode(it.public)) }
}
