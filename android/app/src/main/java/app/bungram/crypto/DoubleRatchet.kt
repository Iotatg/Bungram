package app.bungram.crypto

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters

/**
 * Double Ratchet (Signal spec §3), the same construction used by Signal-Android's libsignal and,
 * in spirit, by Element's Olm.
 *
 *   RK  root key          CKs/CKr  sending / receiving chain keys
 *   Ns/Nr counters        PN       length of the previous sending chain
 *
 * Every header is authenticated as AES-GCM associated data, so a tampered counter or ratchet key
 * fails the tag check. Out-of-order messages are handled by caching skipped message keys
 * (bounded by [MAX_SKIP]) — essential on mobile networks where packets reorder constantly.
 */
class DoubleRatchet private constructor(
    private var rootKey: ByteArray,
    private var dhSelf: AsymmetricCipherKeyPair,
    private var dhRemote: ByteArray?,
    private var ckSend: ByteArray?,
    private var ckRecv: ByteArray?,
) {
    private var ns = 0
    private var nr = 0
    private var pn = 0
    private val skipped = LinkedHashMap<String, ByteArray>()

    val selfPublic: ByteArray
        get() = (dhSelf.public as X25519PublicKeyParameters).encoded

    fun seal(plaintext: ByteArray): RatchetMessage {
        val chain = ckSend ?: error("sending chain not ready")
        val (messageKey, nextChain) = kdfChain(chain)
        ckSend = nextChain

        val header = RatchetHeader(selfPublic, ns, pn)
        val aad = header.encode()
        val ciphertext = CryptoBox.seal(messageKey, plaintext, aad)
        ns++
        return RatchetMessage(header, ciphertext)
    }

    fun open(message: RatchetMessage): ByteArray {
        val header = message.header
        val headerKey = "${header.ratchetKey.contentHashCode()}:${header.counter}"
        skipped.remove(headerKey)?.let {
            return CryptoBox.open(it, message.ciphertext, header.encode())
        }

        if (dhRemote == null || !header.ratchetKey.contentEquals(dhRemote)) {
            skipUntil(header.previousChainLength)
            dhRatchet(header.ratchetKey)
        }
        skipUntil(header.counter)

        val chain = ckRecv ?: error("receiving chain not ready")
        val (messageKey, nextChain) = kdfChain(chain)
        ckRecv = nextChain
        nr++
        return CryptoBox.open(messageKey, message.ciphertext, header.encode())
    }

    private fun dhRatchet(newRemote: ByteArray) {
        pn = ns
        ns = 0
        nr = 0
        dhRemote = newRemote

        val (rk1, ck1) = kdfRoot(rootKey, Keys.dh(privateOf(dhSelf), newRemote))
        rootKey = rk1
        ckRecv = ck1

        dhSelf = Keys.generateX25519()
        val (rk2, ck2) = kdfRoot(rootKey, Keys.dh(privateOf(dhSelf), newRemote))
        rootKey = rk2
        ckSend = ck2
    }

    private fun skipUntil(until: Int) {
        val chain = ckRecv ?: return
        if (until - nr > MAX_SKIP) error("too many skipped messages ($until)")
        var current = chain
        while (nr < until) {
            val (mk, next) = kdfChain(current)
            skipped["${dhRemote?.contentHashCode()}:$nr"] = mk
            current = next
            nr++
        }
        ckRecv = current
    }

    private fun kdfChain(chainKey: ByteArray): Pair<ByteArray, ByteArray> =
        hmac(chainKey, byteArrayOf(0x01)) to hmac(chainKey, byteArrayOf(0x02))

    private fun kdfRoot(root: ByteArray, dhOut: ByteArray): Pair<ByteArray, ByteArray> {
        val okm = CryptoBox.hkdf(dhOut, root, "BungramRatchet".toByteArray(), 64)
        return okm.copyOfRange(0, 32) to okm.copyOfRange(32, 64)
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = HMac(SHA256Digest())
        mac.init(KeyParameter(key))
        mac.update(data, 0, data.size)
        return ByteArray(32).also { mac.doFinal(it, 0) }
    }

    companion object {
        const val MAX_SKIP = 1000

        private fun privateOf(pair: AsymmetricCipherKeyPair) =
            (pair.private as X25519PrivateKeyParameters).encoded

        /** Alice: knows Bob's signed pre-key, performs the first DH ratchet immediately. */
        fun initiator(sharedSecret: ByteArray, remoteRatchetKey: ByteArray): DoubleRatchet {
            val self = Keys.generateX25519()
            val ratchet = DoubleRatchet(sharedSecret, self, null, null, null)
            ratchet.dhRatchet(remoteRatchetKey)
            return ratchet
        }

        /** Bob: starts from his signed pre-key pair and waits for Alice's first header. */
        fun responder(sharedSecret: ByteArray, selfRatchetPair: AsymmetricCipherKeyPair): DoubleRatchet =
            DoubleRatchet(sharedSecret, selfRatchetPair, null, null, null)
    }
}

data class RatchetHeader(
    val ratchetKey: ByteArray,
    val counter: Int,
    val previousChainLength: Int,
) {
    fun encode(): ByteArray {
        val out = ByteArray(4 + ratchetKey.size + 4 + 4)
        var i = 0
        for (b in intToBytes(ratchetKey.size)) out[i++] = b
        for (b in ratchetKey) out[i++] = b
        for (b in intToBytes(counter)) out[i++] = b
        for (b in intToBytes(previousChainLength)) out[i++] = b
        return out
    }

    private fun intToBytes(v: Int) = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte(),
    )
}

data class RatchetMessage(val header: RatchetHeader, val ciphertext: ByteArray)

/**
 * Group messaging: one sender chain per epoch, fanned out once instead of once per member.
 * Equivalent to libsignal's SenderKeyDistributionMessage / MLS-lite for large groups.
 */
class SenderKey(chainKey: ByteArray, val epoch: Int = 0) {

    private var current = chainKey
    private var index = 0

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = HMac(SHA256Digest())
        mac.init(KeyParameter(key))
        mac.update(data, 0, data.size)
        return ByteArray(32).also { mac.doFinal(it, 0) }
    }

    fun seal(plaintext: ByteArray): ByteArray {
        val messageKey = hmac(current, byteArrayOf(0x01))
        current = hmac(current, byteArrayOf(0x02))
        val header = "$epoch:$index".toByteArray()
        index++
        return byteArrayOf(header.size.toByte()) + header + CryptoBox.seal(messageKey, plaintext, header)
    }

    fun distributionMessage(): ByteArray = byteArrayOf(epoch.toByte()) + current
}
