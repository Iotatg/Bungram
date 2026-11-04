package app.bungram.crypto

import java.util.Base64

/**
 * Double Ratchet (Signal spec §3) — the construction libsignal uses and, in spirit, the one
 * Element's Olm implements.
 *
 *   RK        root key              CKs / CKr   sending / receiving chain keys
 *   Ns / Nr   message counters      PN          length of the previous sending chain
 *
 * Two properties matter and both are tested in `RatchetTest`:
 *
 *   1. **Forward secrecy** — every message consumes its chain key and derives the next one, so a
 *      key recovered today cannot decrypt yesterday's traffic.
 *   2. **Post-compromise recovery** — each reply carries a fresh DH ratchet key, so an attacker who
 *      stole the state is locked out again as soon as the conversation turns around.
 *
 * Every header is fed to AES-GCM as associated data, which means a tampered counter, ratchet key
 * or chain length fails the tag check instead of silently decrypting to the wrong plaintext.
 *
 * Out-of-order delivery is handled by caching skipped message keys. Mobile networks reorder
 * constantly, and without this a single delayed packet would poison the chain forever.
 */
class DoubleRatchet private constructor(
    private var rootKey: ByteArray,
    private var selfPriv: ByteArray,
    private var selfPub: ByteArray,
    private var remotePub: ByteArray?,
    private var ckSend: ByteArray?,
    private var ckRecv: ByteArray?,
) {
    private var ns = 0
    private var nr = 0
    private var pn = 0

    /** headerKey -> message key, insertion-ordered so the oldest is evicted first. */
    private val skipped = LinkedHashMap<String, ByteArray>()

    /** The ratchet public key to advertise in outgoing headers. */
    val publicKey: ByteArray get() = selfPub

    fun seal(plaintext: ByteArray): RatchetMessage {
        val chain = ckSend ?: error("sending chain not ready")
        val (messageKey, nextChain) = kdfChain(chain)
        ckSend = nextChain

        val header = RatchetHeader(selfPub, ns, pn)
        val sealed = CryptoBox.seal(messageKey, plaintext, header.encode())
        ns++
        return RatchetMessage(header, sealed)
    }

    fun open(message: RatchetMessage): ByteArray {
        val header = message.header
        val cacheKey = cacheKeyOf(header)

        // A message that arrived before one we already decrypted: use its cached key, and drop it
        // immediately — a message key is used exactly once.
        skipped.remove(cacheKey)?.let { key ->
            return CryptoBox.open(key, message.ciphertext, header.encode())
        }

        val remote = remotePub
        if (remote == null || !header.ratchetKey.contentEquals(remote)) {
            // The peer turned the ratchet: everything still outstanding on the old chain must be
            // skipped *before* the receiving chain is replaced, or those keys are lost forever.
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

    /**
     * One DH ratchet step: derive the new receiving chain from the peer's fresh key, then generate
     * our own fresh key and derive the new sending chain from it.
     */
    private fun dhRatchet(newRemote: ByteArray) {
        pn = ns
        ns = 0
        nr = 0
        remotePub = newRemote

        val (rk1, ck1) = kdfRoot(rootKey, Keys.dh(selfPriv, newRemote))
        rootKey = rk1
        ckRecv = ck1

        val pair = Keys.generateX25519()
        selfPriv = Keys.privateOf(pair)
        selfPub = Keys.publicOf(pair)
        val (rk2, ck2) = kdfRoot(rootKey, Keys.dh(selfPriv, newRemote))
        rootKey = rk2
        ckSend = ck2
    }

    /** Walks the receiving chain forward to [until], banking the keys of messages still in flight. */
    private fun skipUntil(until: Int) {
        val chain = ckRecv ?: return
        if (until < nr) return
        if (until - nr > MAX_SKIP) error("refusing to skip ${until - nr} messages")
        var current = chain
        val remote = remotePub ?: return
        while (nr < until) {
            val (messageKey, next) = kdfChain(current)
            skipped[cacheKeyOf(remote, nr)] = messageKey
            current = next
            nr++
        }
        ckRecv = current
        while (skipped.size > MAX_SKIP) {
            skipped.remove(skipped.keys.first())
        }
    }

    private fun kdfChain(chainKey: ByteArray): Pair<ByteArray, ByteArray> =
        CryptoBox.hmac(chainKey, byteArrayOf(0x01)) to CryptoBox.hmac(chainKey, byteArrayOf(0x02))

    private fun kdfRoot(root: ByteArray, dhOut: ByteArray): Pair<ByteArray, ByteArray> =
        kdfRootStatic(root, dhOut)

    /* ---------------------------------------------------------------------------- persistence */

    fun snapshot(): RatchetState = RatchetState(
        rootKey = rootKey,
        selfPriv = selfPriv,
        selfPub = selfPub,
        remotePub = remotePub,
        ckSend = ckSend,
        ckRecv = ckRecv,
        ns = ns,
        nr = nr,
        pn = pn,
        skipped = skipped.map { SkippedKey(it.key, it.value) },
    )

    companion object {
        /** Signal's recommendation: enough for a long outage, bounded so the cache cannot be abused. */
        const val MAX_SKIP = 1000

        private val ROOT_INFO = "BungramRatchet-v1".toByteArray()
        private val B64: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

        /** `KDF_RK` from the spec: HKDF over the DH output, salted with the current root key. */
        private fun kdfRootStatic(root: ByteArray, dhOut: ByteArray): Pair<ByteArray, ByteArray> {
            val okm = CryptoBox.hkdf(dhOut, root, ROOT_INFO, 64)
            return okm.copyOfRange(0, 32) to okm.copyOfRange(32, 64)
        }

        private fun cacheKeyOf(header: RatchetHeader) = cacheKeyOf(header.ratchetKey, header.counter)

        private fun cacheKeyOf(ratchetKey: ByteArray, counter: Int) =
            "${B64.encodeToString(ratchetKey)}:$counter"

        /**
         * Alice: knows Bob's signed pre-key, so she performs the first DH ratchet immediately —
         * exactly one step, per the spec's `RatchetInitAlice`. Doing a full two-step ratchet here
         * would advance the root key twice and Bob would derive a different chain.
         */
        fun initiator(sharedSecret: ByteArray, remoteRatchetKey: ByteArray): DoubleRatchet {
            val pair = Keys.generateX25519()
            val selfPriv = Keys.privateOf(pair)
            val selfPub = Keys.publicOf(pair)
            val (rootKey, chainKey) = kdfRootStatic(sharedSecret, Keys.dh(selfPriv, remoteRatchetKey))
            return DoubleRatchet(
                rootKey = rootKey,
                selfPriv = selfPriv,
                selfPub = selfPub,
                remotePub = remoteRatchetKey,
                ckSend = chainKey,
                ckRecv = null,
            )
        }

        /** Bob: starts from the signed pre-key pair he already published and waits for Alice. */
        fun responder(sharedSecret: ByteArray, selfPriv: ByteArray, selfPub: ByteArray): DoubleRatchet =
            DoubleRatchet(sharedSecret, selfPriv, selfPub, null, null, null)

        fun restore(state: RatchetState): DoubleRatchet = DoubleRatchet(
            rootKey = state.rootKey,
            selfPriv = state.selfPriv,
            selfPub = state.selfPub,
            remotePub = state.remotePub,
            ckSend = state.ckSend,
            ckRecv = state.ckRecv,
        ).apply {
            ns = state.ns
            nr = state.nr
            pn = state.pn
            state.skipped.forEach { skipped[it.headerKey] = it.messageKey }
        }
    }
}

data class RatchetHeader(
    val ratchetKey: ByteArray,
    val counter: Int,
    val previousChainLength: Int,
) {
    /** Fixed-width big-endian encoding: identical on every platform, and stable as GCM AAD. */
    fun encode(): ByteArray {
        val out = ByteArray(4 + ratchetKey.size + 4 + 4)
        var i = 0
        for (b in intToBytes(ratchetKey.size)) out[i++] = b
        for (b in ratchetKey) out[i++] = b
        for (b in intToBytes(counter)) out[i++] = b
        for (b in intToBytes(previousChainLength)) out[i++] = b
        return out
    }

    companion object {
        fun decode(bytes: ByteArray): RatchetHeader {
            var i = 0
            val keyLen = readInt(bytes, i).also { i += 4 }
            require(keyLen in 32..64) { "bad ratchet key length $keyLen" }
            val key = bytes.copyOfRange(i, i + keyLen).also { i += keyLen }
            val counter = readInt(bytes, i).also { i += 4 }
            val pn = readInt(bytes, i)
            return RatchetHeader(key, counter, pn)
        }

        private fun readInt(b: ByteArray, at: Int): Int =
            ((b[at].toInt() and 0xff) shl 24) or
                ((b[at + 1].toInt() and 0xff) shl 16) or
                ((b[at + 2].toInt() and 0xff) shl 8) or
                (b[at + 3].toInt() and 0xff)

        private fun intToBytes(v: Int) = byteArrayOf(
            (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte(),
        )
    }
}

data class RatchetMessage(val header: RatchetHeader, val ciphertext: ByteArray)

/** Plain-data form of a session, small enough to keep in encrypted preferences. */
data class RatchetState(
    val rootKey: ByteArray,
    val selfPriv: ByteArray,
    val selfPub: ByteArray,
    val remotePub: ByteArray?,
    val ckSend: ByteArray?,
    val ckRecv: ByteArray?,
    val ns: Int,
    val nr: Int,
    val pn: Int,
    val skipped: List<SkippedKey> = emptyList(),
)

data class SkippedKey(val headerKey: String, val messageKey: ByteArray)

/**
 * Group messaging: one sender chain per epoch, fanned out once instead of once per member — the
 * same trade-off Signal made with Sender Key. Not yet wired into the app (groups still use
 * server-side storage); kept here because it is the building block for group E2EE.
 */
class SenderKey(chainKey: ByteArray, val epoch: Int = 0) {

    private var current = chainKey
    private var index = 0

    fun seal(plaintext: ByteArray): ByteArray {
        val messageKey = CryptoBox.hmac(current, byteArrayOf(0x01))
        current = CryptoBox.hmac(current, byteArrayOf(0x02))
        val header = "$epoch:$index".toByteArray()
        index++
        return byteArrayOf(header.size.toByte()) + header + CryptoBox.seal(messageKey, plaintext, header)
    }

    fun distributionMessage(): ByteArray = byteArrayOf(epoch.toByte()) + current
}
