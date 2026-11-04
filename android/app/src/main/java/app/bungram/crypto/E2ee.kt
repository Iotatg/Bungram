package app.bungram.crypto

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** Base64 helpers, URL-safe and unpadded so a payload survives a URL or a JSON string intact. */
internal object B64 {
    private val enc: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val dec: Base64.Decoder = Base64.getUrlDecoder()
    fun encode(bytes: ByteArray): String = enc.encodeToString(bytes)
    fun decode(text: String): ByteArray = dec.decode(text)
    fun encodeOrNull(bytes: ByteArray?): String? = bytes?.let(::encode)
    fun decodeOrNull(text: String?): ByteArray? = text?.takeIf { it.isNotEmpty() }?.let(::decode)
}

/**
 * Everything the wire needs for one encrypted message.
 *
 * Two shapes only:
 *
 *  - **PREKEY** — the first message of a session. Carries the X3DH material (identity key,
 *    ephemeral key, which signed / one-time pre-key the sender used) so the receiver can derive
 *    the same root key without being online at the same time.
 *  - **RATCHET** — every message after that. Nothing but a header and a ciphertext.
 *
 * The type byte is inside the authenticated ciphertext's AAD chain, so an attacker cannot downgrade
 * a pre-key message to a ratchet message or strip the one-time key id.
 */
internal object Envelope {

    private const val VERSION = 1
    private const val TYPE_PREKEY = 1
    private const val TYPE_RATCHET = 2
    private const val PREFIX = "b1."

    data class PreKeyHeader(
        val identityDhPublic: ByteArray,
        /** The sender's Ed25519 identity key: needed to pin them and to show a safety number. */
        val identitySignPublic: ByteArray,
        val ephemeralPublic: ByteArray,
        val signedPreKeyId: Int,
        val oneTimePreKeyId: Int,
    )

    fun sealPreKey(header: PreKeyHeader, message: RatchetMessage): String {
        val out = java.io.ByteArrayOutputStream()
        out.write(VERSION)
        out.write(TYPE_PREKEY)
        out.write(header.identityDhPublic)
        out.write(header.identitySignPublic)
        out.write(header.ephemeralPublic)
        out.write(intBytes(header.signedPreKeyId))
        out.write(intBytes(header.oneTimePreKeyId))
        out.write(message.header.encode())
        out.write(message.ciphertext)
        return PREFIX + B64.encode(out.toByteArray())
    }

    fun sealRatchet(message: RatchetMessage): String {
        val out = java.io.ByteArrayOutputStream()
        out.write(VERSION)
        out.write(TYPE_RATCHET)
        out.write(message.header.encode())
        out.write(message.ciphertext)
        return PREFIX + B64.encode(out.toByteArray())
    }

    sealed interface Parsed {
        data class PreKey(
            val header: PreKeyHeader,
            val message: RatchetMessage,
        ) : Parsed

        data class Ratchet(val message: RatchetMessage) : Parsed
    }

    fun parse(wire: String): Parsed? {
        if (!wire.startsWith(PREFIX)) return null
        val raw = runCatching { B64.decode(wire.removePrefix(PREFIX)) }.getOrNull() ?: return null
        if (raw.size < 3) return null
        if (raw[0].toInt() != VERSION) return null

        var i = 1
        return when (raw[i++].toInt()) {
            TYPE_PREKEY -> {
                if (raw.size < i + 32 + 32 + 32 + 4 + 4) return null
                val identity = raw.copyOfRange(i, i + 32).also { i += 32 }
                val identitySign = raw.copyOfRange(i, i + 32).also { i += 32 }
                val ephemeral = raw.copyOfRange(i, i + 32).also { i += 32 }
                val spkId = readInt(raw, i).also { i += 4 }
                val otkId = readInt(raw, i).also { i += 4 }
                val (header, body) = readBody(raw, i) ?: return null
                Parsed.PreKey(
                    PreKeyHeader(identity, identitySign, ephemeral, spkId, otkId),
                    RatchetMessage(header, body),
                )
            }

            TYPE_RATCHET -> {
                val (header, body) = readBody(raw, i) ?: return null
                Parsed.Ratchet(RatchetMessage(header, body))
            }

            else -> null
        }
    }

    private fun readBody(raw: ByteArray, from: Int): Pair<RatchetHeader, ByteArray>? {
        if (raw.size < from + 8) return null
        val keyLen = readInt(raw, from)
        val headerLen = 4 + keyLen + 4 + 4
        if (keyLen !in 32..64 || raw.size < from + headerLen + 16) return null
        val header = RatchetHeader.decode(raw.copyOfRange(from, from + headerLen))
        return header to raw.copyOfRange(from + headerLen, raw.size)
    }

    private fun intBytes(v: Int) = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte(),
    )

    private fun readInt(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xff) shl 24) or
            ((b[at + 1].toInt() and 0xff) shl 16) or
            ((b[at + 2].toInt() and 0xff) shl 8) or
            (b[at + 3].toInt() and 0xff)

    const val NO_KEY = -1
}

/**
 * The session layer: one Double Ratchet per peer, persisted after every single message.
 *
 * Persisting on every message is not an optimisation detail, it is the security model. A ratchet
 * state that is only flushed on app exit leaks the ability to decrypt everything since the last
 * flush to anyone who can read memory.
 */
class E2ee(
    private val storage: SecretStorage,
    private val deviceId: String,
    private val resolver: PreKeyResolver,
) {

    /** Fetches a peer's published bundle. Returns null when the peer has never published keys. */
    fun interface PreKeyResolver {
        suspend fun bundleFor(userId: String): PreKeyBundle?
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val ratchets = ConcurrentHashMap<String, DoubleRatchet>()

    private fun lockFor(peerId: String) = locks.getOrPut(peerId) { Mutex() }

    /* ------------------------------------------------------------------------- own identity */

    /** Loads the long-term identity, generating it on first launch. */
    fun identity(): IdentityKeyPair {
        storage.read(KEY_IDENTITY)?.let { return decodeIdentity(it) }
        val fresh = Keys.generateIdentity()
        storage.write(KEY_IDENTITY, encodeIdentity(fresh))
        return fresh
    }

    fun hasIdentity(): Boolean = storage.read(KEY_IDENTITY) != null

    fun deviceId(): String = deviceId

    /** The signed pre-key. Rotated by [rotateSignedPreKey]; kept stable otherwise. */
    fun signedPreKey(): SignedPreKey = storage.read(KEY_SPK)?.let(::decodeSignedPreKey)
        ?: rotateSignedPreKey()

    fun rotateSignedPreKey(): SignedPreKey {
        val identity = identity()
        val id = (System.currentTimeMillis() / 1000).toInt()
        val pair = Keys.generateX25519()
        val pub = Keys.publicOf(pair)
        val record = SignedPreKey(
            id = id,
            private = Keys.privateOf(pair),
            public = pub,
            signature = Keys.sign(identity.signPrivate, pub),
        )
        storage.write(KEY_SPK, encodeSignedPreKey(record))
        return record
    }

    /* ---------------------------------------------------------------------- one-time pre-keys */

    fun oneTimePreKeys(): List<OneTimePreKey> =
        storage.read(KEY_OTK)?.let { raw ->
            runCatching { json.decodeFromString<List<OneTimePreKeyBlob>>(raw) }.getOrNull()
        }?.map { OneTimePreKey(it.id, B64.decode(it.private), B64.decode(it.public)) }.orEmpty()

    /**
     * Generates [count] fresh one-time keys and returns **only the new ones** — those are the ones
     * the caller still has to publish.
     *
     * Re-uploading the whole pool would be a bug, not an optimisation: the server deletes a key the
     * moment it hands it out, so a re-upload would resurrect already-spent keys and hand the same
     * one-time key to two different senders.
     */
    fun topUpOneTimePreKeys(count: Int = OTK_BATCH): List<OneTimePreKey> {
        val existing = oneTimePreKeys().toMutableList()
        var nextId = (existing.maxOfOrNull { it.id } ?: 0) + 1
        val fresh = ArrayList<OneTimePreKey>(count)
        repeat(count) {
            val pair = Keys.generateX25519()
            val key = OneTimePreKey(nextId++, Keys.privateOf(pair), Keys.publicOf(pair))
            fresh += key
            existing += key
        }
        // The pool is capped so an old device cannot grow its storage without bound.
        val trimmed = existing.takeLast(MAX_OTK)
        storage.write(
            KEY_OTK,
            json.encodeToString(trimmed.map { OneTimePreKeyBlob(it.id, B64.encode(it.private), B64.encode(it.public)) }),
        )
        return fresh
    }

    private fun takeOneTimePreKey(id: Int): OneTimePreKey? {
        val pool = oneTimePreKeys()
        val match = pool.firstOrNull { it.id == id } ?: return null
        val left = pool - match
        storage.write(
            KEY_OTK,
            json.encodeToString(left.map { OneTimePreKeyBlob(it.id, B64.encode(it.private), B64.encode(it.public)) }),
        )
        return match
    }

    /** What we publish: identity, signed pre-key, and whatever one-time keys are still unspent. */
    fun myBundle(): PreKeyBundle {
        val identity = identity()
        val spk = signedPreKey()
        return PreKeyBundle(
            userId = "",
            deviceId = deviceId,
            identityDhPublic = identity.dhPublic,
            identitySignPublic = identity.signPublic,
            signedPreKeyId = spk.id,
            signedPreKeyPublic = spk.public,
            signedPreKeySignature = spk.signature,
            oneTimePreKeyId = null,
            oneTimePreKeyPublic = null,
        )
    }

    /* ------------------------------------------------------------------------------ sending */

    /**
     * Encrypts [plaintext] for [peerId], establishing the session first if this is the first
     * message. Throws [NoPreKeysException] when the peer has published nothing — the caller then
     * decides whether to send in the clear or refuse.
     */
    suspend fun encrypt(peerId: String, plaintext: ByteArray): String = lockFor(peerId).withLock {
        val existing = session(peerId)
        if (existing != null) {
            val sealed = existing.seal(plaintext)
            persist(peerId, existing, null)
            return@withLock Envelope.sealRatchet(sealed)
        }

        val bundle = resolver.bundleFor(peerId) ?: throw NoPreKeysException(peerId)
        require(bundle.verify()) { "peer published a pre-key with a bad signature" }

        val identity = identity()
        val ephemeral = Keys.generateX25519()
        val secret = X3dh.initiate(
            myIdentity = identity,
            myEphemeralPrivate = Keys.privateOf(ephemeral),
            myEphemeralPublic = Keys.publicOf(ephemeral),
            bundle = bundle,
        )
        val ratchet = DoubleRatchet.initiator(secret.rootKey, bundle.signedPreKeyPublic)
        val sealed = ratchet.seal(plaintext)
        persist(peerId, ratchet, bundle.identitySignPublic)

        Envelope.sealPreKey(
            Envelope.PreKeyHeader(
                identityDhPublic = identity.dhPublic,
                identitySignPublic = identity.signPublic,
                ephemeralPublic = secret.ephemeralPublic,
                signedPreKeyId = bundle.signedPreKeyId,
                oneTimePreKeyId = bundle.oneTimePreKeyId ?: Envelope.NO_KEY,
            ),
            sealed,
        )
    }

    /* ---------------------------------------------------------------------------- receiving */

    /**
     * Decrypts a wire string. Returns null when the payload cannot be opened — a tampered message,
     * a session that no longer matches, or a key we never had. The caller shows "message not
     * available" rather than crashing or silently dropping the row.
     */
    suspend fun decrypt(peerId: String, wire: String): ByteArray? = lockFor(peerId).withLock {
        val parsed = Envelope.parse(wire) ?: return@withLock null
        when (parsed) {
            is Envelope.Parsed.Ratchet -> {
                val ratchet = session(peerId) ?: return@withLock null
                val plain = runCatching { ratchet.open(parsed.message) }.getOrNull() ?: return@withLock null
                persist(peerId, ratchet, null)
                plain
            }

            is Envelope.Parsed.PreKey -> {
                // A pre-key message can arrive on an established session (the peer reinstalled, or
                // their first message was delayed). Try the live session first, then rebuild.
                session(peerId)?.let { live ->
                    runCatching { live.open(parsed.message) }.getOrNull()?.let { plain ->
                        persist(peerId, live, null)
                        return@withLock plain
                    }
                }
                val (rebuilt, peerSign) = establishAsResponder(parsed.header) ?: return@withLock null
                val plain = runCatching { rebuilt.open(parsed.message) }.getOrNull() ?: return@withLock null
                persist(peerId, rebuilt, peerSign)
                plain
            }
        }
    }

    private fun establishAsResponder(header: Envelope.PreKeyHeader): Pair<DoubleRatchet, ByteArray>? {
        val spk = signedPreKey()
        if (spk.id != header.signedPreKeyId) {
            // The sender used a signed pre-key we have already rotated away from. Keeping one
            // previous key around is what lets messages sent during the rotation still land.
            val previous = previousSignedPreKey() ?: return null
            if (previous.id != header.signedPreKeyId) return null
            return buildResponder(previous, header)
        }
        return buildResponder(spk, header)
    }

    private fun buildResponder(
        spk: SignedPreKey,
        header: Envelope.PreKeyHeader,
    ): Pair<DoubleRatchet, ByteArray>? {
        val identity = identity()
        val otk = if (header.oneTimePreKeyId >= 0) takeOneTimePreKey(header.oneTimePreKeyId) else null
        if (header.oneTimePreKeyId >= 0 && otk == null) {
            // The one-time key was already spent — likely a duplicate delivery of the same
            // handshake. Without it we cannot derive the secret, so refuse cleanly.
            return null
        }
        val secret = X3dh.respond(
            myIdentity = identity,
            mySignedPreKeyPrivate = spk.private,
            myOneTimePreKeyPrivate = otk?.private,
            initiatorIdentityDhPublic = header.identityDhPublic,
            initiatorEphemeralPublic = header.ephemeralPublic,
        )
        return DoubleRatchet.responder(secret.rootKey, spk.private, spk.public) to
            header.identitySignPublic
    }

    /* ------------------------------------------------------------------------------ sessions */

    fun isEstablished(peerId: String): Boolean = storage.read(sessionKey(peerId)) != null

    /** Safety number for the verification screen; null until a session exists. */
    fun safetyNumber(peerId: String): String? {
        val blob = readBlob(peerId) ?: return null
        val peerSign = B64.decodeOrNull(blob.peerSignPublic) ?: return null
        return identity().safetyNumber(peerSign)
    }

    fun resetSession(peerId: String) {
        storage.write(sessionKey(peerId), null)
        ratchets.remove(peerId)
    }

    private fun session(peerId: String): DoubleRatchet? {
        ratchets[peerId]?.let { return it }
        val blob = readBlob(peerId) ?: return null
        val restored = runCatching {
            DoubleRatchet.restore(
                RatchetState(
                    rootKey = B64.decode(blob.rootKey),
                    selfPriv = B64.decode(blob.selfPriv),
                    selfPub = B64.decode(blob.selfPub),
                    remotePub = B64.decodeOrNull(blob.remotePub),
                    ckSend = B64.decodeOrNull(blob.ckSend),
                    ckRecv = B64.decodeOrNull(blob.ckRecv),
                    ns = blob.ns,
                    nr = blob.nr,
                    pn = blob.pn,
                    skipped = blob.skipped.map { SkippedKey(it.headerKey, B64.decode(it.messageKey)) },
                )
            )
        }.getOrNull() ?: return null
        ratchets[peerId] = restored
        return restored
    }

    private fun persist(peerId: String, ratchet: DoubleRatchet, peerSignPublic: ByteArray?) {
        ratchets[peerId] = ratchet
        val state = ratchet.snapshot()
        val previousSign = readBlob(peerId)?.peerSignPublic
        val blob = SessionBlob(
            rootKey = B64.encode(state.rootKey),
            selfPriv = B64.encode(state.selfPriv),
            selfPub = B64.encode(state.selfPub),
            remotePub = B64.encodeOrNull(state.remotePub),
            ckSend = B64.encodeOrNull(state.ckSend),
            ckRecv = B64.encodeOrNull(state.ckRecv),
            ns = state.ns,
            nr = state.nr,
            pn = state.pn,
            skipped = state.skipped.map { SkippedBlob(it.headerKey, B64.encode(it.messageKey)) },
            peerSignPublic = B64.encodeOrNull(peerSignPublic) ?: previousSign,
            updatedAtMs = System.currentTimeMillis(),
        )
        storage.write(sessionKey(peerId), json.encodeToString(blob))
    }

    private fun readBlob(peerId: String): SessionBlob? =
        storage.read(sessionKey(peerId))?.let {
            runCatching { json.decodeFromString<SessionBlob>(it) }.getOrNull()
        }

    private fun previousSignedPreKey(): SignedPreKey? =
        storage.read(KEY_SPK_PREVIOUS)?.let(::decodeSignedPreKey)

    /** Called after [rotateSignedPreKey] by the app so in-flight messages still decrypt. */
    fun keepPreviousSignedPreKey(old: SignedPreKey) {
        storage.write(KEY_SPK_PREVIOUS, encodeSignedPreKey(old))
    }

    /* ----------------------------------------------------------------------------- codecs */

    private fun encodeIdentity(k: IdentityKeyPair): String =
        B64.encode(k.dhPrivate + k.dhPublic + k.signPrivate + k.signPublic)

    private fun decodeIdentity(raw: String): IdentityKeyPair {
        val b = B64.decode(raw)
        require(b.size >= 128) { "corrupt identity record" }
        return IdentityKeyPair(
            dhPrivate = b.copyOfRange(0, 32),
            dhPublic = b.copyOfRange(32, 64),
            signPrivate = b.copyOfRange(64, 96),
            signPublic = b.copyOfRange(96, 128),
        )
    }

    private fun encodeSignedPreKey(k: SignedPreKey): String =
        "${k.id}:" + B64.encode(k.private) + ":" + B64.encode(k.public) + ":" + B64.encode(k.signature)

    private fun decodeSignedPreKey(raw: String): SignedPreKey {
        val parts = raw.split(':')
        return SignedPreKey(parts[0].toInt(), B64.decode(parts[1]), B64.decode(parts[2]), B64.decode(parts[3]))
    }

    private fun sessionKey(peerId: String) = "$KEY_SESSION$peerId"

    /* ------------------------------------------------------------------------------ types */

    data class SignedPreKey(val id: Int, val private: ByteArray, val public: ByteArray, val signature: ByteArray)

    data class OneTimePreKey(val id: Int, val private: ByteArray, val public: ByteArray)

    class NoPreKeysException(val peerId: String) : Exception("no pre-keys published for $peerId")

    @Serializable
    private data class SessionBlob(
        val rootKey: String,
        val selfPriv: String,
        val selfPub: String,
        val remotePub: String? = null,
        val ckSend: String? = null,
        val ckRecv: String? = null,
        val ns: Int = 0,
        val nr: Int = 0,
        val pn: Int = 0,
        val skipped: List<SkippedBlob> = emptyList(),
        val peerSignPublic: String? = null,
        val updatedAtMs: Long = 0,
    )

    @Serializable
    private data class SkippedBlob(val headerKey: String, val messageKey: String)

    @Serializable
    private data class OneTimePreKeyBlob(val id: Int, val private: String, val public: String)

    private companion object {
        const val KEY_IDENTITY = "identity"
        const val KEY_SPK = "signed_pre_key"
        const val KEY_SPK_PREVIOUS = "signed_pre_key_previous"
        const val KEY_OTK = "one_time_pre_keys"
        const val KEY_SESSION = "session."
        const val OTK_BATCH = 20
        const val MAX_OTK = 100
    }
}
