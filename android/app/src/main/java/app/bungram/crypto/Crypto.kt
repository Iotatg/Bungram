package app.bungram.crypto

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Bungram's E2EE primitives.
 *
 * Model (Signal-style, same family as Signal-Android's libsignal and Element's Olm/Megolm):
 *   - X25519 for Diffie-Hellman, Ed25519 for identity signatures.
 *   - X3DH for the initial shared secret (asynchronous session setup).
 *   - Double Ratchet (KDF chains here; DH-ratchet step in [DoubleRatchet.ratchetStep]).
 *   - AES-256-GCM for the payload, random 96-bit nonce per message, AAD = header bytes.
 *
 * Group chats use Sender Keys: one chain per (sender, epoch), distributed over pairwise sessions,
 * so an N-member group costs 1 ciphertext instead of N — the same trade-off Signal made.
 */
object CryptoBox {

    private const val GCM_TAG_BITS = 128
    private const val NONCE_BYTES = 12
    private val random = SecureRandom()

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    fun sha256(vararg chunks: ByteArray): ByteArray {
        val digest = SHA256Digest()
        chunks.forEach { digest.update(it, 0, it.size) }
        return ByteArray(digest.digestSize).also { digest.doFinal(it, 0) }
    }

    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int = 32): ByteArray {
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(ikm, salt, info))
        return ByteArray(length).also { hkdf.generateBytes(it, 0, length) }
    }

    fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = HMac(SHA256Digest())
        mac.init(KeyParameter(key))
        mac.update(data, 0, data.size)
        return ByteArray(32).also { mac.doFinal(it, 0) }
    }

    /** AES-256-GCM seal → nonce || ciphertext||tag */
    fun seal(key: ByteArray, plaintext: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        val nonce = randomBytes(NONCE_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return nonce + cipher.doFinal(plaintext)
    }

    fun open(key: ByteArray, sealed: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        require(sealed.size > NONCE_BYTES) { "ciphertext too short" }
        val nonce = sealed.copyOfRange(0, NONCE_BYTES)
        val body = sealed.copyOfRange(NONCE_BYTES, sealed.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(body)
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean =
        java.security.MessageDigest.isEqual(a, b)
}

data class IdentityKeyPair(
    val dhPrivate: ByteArray,
    val dhPublic: ByteArray,
    val signPrivate: ByteArray,
    val signPublic: ByteArray,
) {
    /**
     * Safety number — the 60-digit fingerprint both sides can read out loud.
     *
     * The two public keys are sorted before hashing, so Alice and Bob compute the *same* number
     * for each other. Without the sort the two sides would see different values and the whole
     * comparison ritual would be useless.
     */
    fun safetyNumber(peerSignPublic: ByteArray): String {
        val (a, b) = if (compare(signPublic, peerSignPublic) <= 0) signPublic to peerSignPublic
        else peerSignPublic to signPublic
        val digest = CryptoBox.sha256(a, b)
        return buildString {
            for (i in 0 until 12) {
                val v = ((digest[i * 2].toInt() and 0xff) shl 8) or (digest[i * 2 + 1].toInt() and 0xff)
                append(java.lang.String.format(java.util.Locale.ROOT, "%05d", v % 100_000))
            }
        }
    }

    private fun compare(a: ByteArray, b: ByteArray): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
            if (d != 0) return d
        }
        return a.size - b.size
    }
}

object Keys {

    fun generateIdentity(): IdentityKeyPair {
        val dh = generateX25519()
        val signGen = Ed25519KeyPairGenerator().apply {
            init(Ed25519KeyGenerationParameters(SecureRandom()))
        }
        val sign = signGen.generateKeyPair()
        return IdentityKeyPair(
            dhPrivate = (dh.private as X25519PrivateKeyParameters).encoded,
            dhPublic = (dh.public as X25519PublicKeyParameters).encoded,
            signPrivate = (sign.private as Ed25519PrivateKeyParameters).encoded,
            signPublic = (sign.public as Ed25519PublicKeyParameters).encoded,
        )
    }

    fun generateX25519(): org.bouncycastle.crypto.AsymmetricCipherKeyPair {
        val gen = X25519KeyPairGenerator().apply {
            init(X25519KeyGenerationParameters(SecureRandom()))
        }
        return gen.generateKeyPair()
    }

    fun privateOf(pair: org.bouncycastle.crypto.AsymmetricCipherKeyPair): ByteArray =
        (pair.private as X25519PrivateKeyParameters).encoded

    fun publicOf(pair: org.bouncycastle.crypto.AsymmetricCipherKeyPair): ByteArray =
        (pair.public as X25519PublicKeyParameters).encoded

    /** Rebuilds a key pair from a stored private key — used when a session is restored from disk. */
    fun pairOf(privateKey: ByteArray): org.bouncycastle.crypto.AsymmetricCipherKeyPair {
        val priv = X25519PrivateKeyParameters(privateKey, 0)
        return org.bouncycastle.crypto.AsymmetricCipherKeyPair(priv.generatePublicKey(), priv)
    }

    fun dh(privateKey: ByteArray, peerPublicKey: ByteArray): ByteArray {
        val priv = X25519PrivateKeyParameters(privateKey, 0)
        val pub = X25519PublicKeyParameters(peerPublicKey, 0)
        return ByteArray(32).also { priv.generateSecret(pub, it, 0) }
    }

    fun sign(privateKey: ByteArray, message: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(privateKey, 0))
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        val signer = Ed25519Signer()
        signer.init(false, Ed25519PublicKeyParameters(publicKey, 0))
        signer.update(message, 0, message.size)
        return signer.verifySignature(signature)
    }
}

/** Everything a peer needs to start a session with you. Published once, rotated on a schedule. */
data class PreKeyBundle(
    val userId: String,
    val deviceId: String,
    val identityDhPublic: ByteArray,
    val identitySignPublic: ByteArray,
    val signedPreKeyId: Int,
    val signedPreKeyPublic: ByteArray,
    val signedPreKeySignature: ByteArray,
    val oneTimePreKeyId: Int?,
    val oneTimePreKeyPublic: ByteArray?,
) {
    fun verify(): Boolean = Keys.verify(
        identitySignPublic,
        signedPreKeyPublic,
        signedPreKeySignature,
    )
}

object X3dh {

    private val INFO = "BungramX3DH-v1".toByteArray()

    /** Initiator (Alice) side: derives the root key from Bob's bundle. */
    fun initiate(
        myIdentity: IdentityKeyPair,
        myEphemeralPrivate: ByteArray,
        myEphemeralPublic: ByteArray,
        bundle: PreKeyBundle,
    ): SessionSecret {
        require(bundle.verify()) { "signed pre-key signature invalid" }
        val dh1 = Keys.dh(myIdentity.dhPrivate, bundle.signedPreKeyPublic)
        val dh2 = Keys.dh(myEphemeralPrivate, bundle.identityDhPublic)
        val dh3 = Keys.dh(myEphemeralPrivate, bundle.signedPreKeyPublic)
        val dh4 = bundle.oneTimePreKeyPublic?.let { Keys.dh(myEphemeralPrivate, it) }

        val material = listOfNotNull(dh1, dh2, dh3, dh4).fold(ByteArray(0)) { acc, it -> acc + it }
        val rootKey = CryptoBox.hkdf(material, ByteArray(32), INFO, 32)

        return SessionSecret(
            rootKey = rootKey,
            associatedData = myIdentity.dhPublic + bundle.identityDhPublic,
            initialRatchetKey = bundle.signedPreKeyPublic,
            ephemeralPublic = myEphemeralPublic,
        )
    }

    /** Responder (Bob) side: recomputes the same secret from his private pre-keys. */
    fun respond(
        myIdentity: IdentityKeyPair,
        mySignedPreKeyPrivate: ByteArray,
        myOneTimePreKeyPrivate: ByteArray?,
        initiatorIdentityDhPublic: ByteArray,
        initiatorEphemeralPublic: ByteArray,
    ): SessionSecret {
        val dh1 = Keys.dh(mySignedPreKeyPrivate, initiatorIdentityDhPublic)
        val dh2 = Keys.dh(myIdentity.dhPrivate, initiatorEphemeralPublic)
        val dh3 = Keys.dh(mySignedPreKeyPrivate, initiatorEphemeralPublic)
        val dh4 = myOneTimePreKeyPrivate?.let { Keys.dh(it, initiatorEphemeralPublic) }

        val material = listOfNotNull(dh1, dh2, dh3, dh4).fold(ByteArray(0)) { acc, it -> acc + it }
        val rootKey = CryptoBox.hkdf(material, ByteArray(32), INFO, 32)

        return SessionSecret(
            rootKey = rootKey,
            associatedData = initiatorIdentityDhPublic + myIdentity.dhPublic,
            initialRatchetKey = null,
            ephemeralPublic = initiatorEphemeralPublic,
        )
    }
}

data class SessionSecret(
    val rootKey: ByteArray,
    val associatedData: ByteArray,
    val initialRatchetKey: ByteArray?,
    val ephemeralPublic: ByteArray,
)
