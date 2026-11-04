package app.bungram.crypto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proof that the ratchet actually behaves like a ratchet.
 *
 * These are JVM tests on purpose: no emulator, no Robolectric, no Android APIs — the same code the
 * app runs, exercised directly. [MemorySecretStorage] stands in for the Keystore, which also proves
 * the engine never reaches for a Context behind our back.
 *
 * [World] plays the part of the server's pre-key directory, so a test can wire two devices together
 * the same way `POST /v1/keys` and `GET /v1/keys/{id}` do in production.
 */
class E2eeTest {

    private class World(withOneTime: Boolean = true) {
        private val directory = mutableMapOf<String, PreKeyBundle>()

        val alice: E2ee = E2ee(MemorySecretStorage(), "alice-device") { directory[it] }
        val bob: E2ee = E2ee(MemorySecretStorage(), "bob-device") { directory[it] }

        init {
            bob.topUpOneTimePreKeys()
            directory["alice"] = bundleOf(alice, "alice", withOneTime)
            directory["bob"] = bundleOf(bob, "bob", withOneTime)
        }
    }

    private fun decryptString(engine: E2ee, peerId: String, wire: String): String? =
        runBlocking { engine.decrypt(peerId, wire)?.toString(Charsets.UTF_8) }

    private fun encryptString(engine: E2ee, peerId: String, text: String): String =
        runBlocking { engine.encrypt(peerId, text.toByteArray()) }

    /* --------------------------------------------------------------------------- handshake */

    @Test
    fun `first message is a pre-key message and decrypts on the other side`() = runBlocking {
        val world = World()
        val wire = encryptString(world.alice, "bob", "namaste")
        assertTrue("first message must carry X3DH material", wire.startsWith("b1."))
        assertEquals("namaste", decryptString(world.bob, "alice", wire))
    }

    @Test
    fun `both sides establish a session`() = runBlocking {
        val world = World()
        assertFalse(world.alice.isEstablished("bob"))
        val wire = encryptString(world.alice, "bob", "hi")
        assertTrue(world.alice.isEstablished("bob"))
        world.bob.decrypt("alice", wire)
        assertTrue(world.bob.isEstablished("alice"))
    }

    @Test
    fun `reply uses the ratchet and is readable by the initiator`() = runBlocking {
        val world = World()
        world.bob.decrypt("alice", encryptString(world.alice, "bob", "one"))
        val reply = encryptString(world.bob, "alice", "two")
        assertEquals("two", decryptString(world.alice, "bob", reply))
    }

    @Test
    fun `conversation keeps working across several turns`() = runBlocking {
        val world = World()
        repeat(5) { turn ->
            val down = encryptString(world.alice, "bob", "a$turn")
            assertEquals("a$turn", decryptString(world.bob, "alice", down))
            val up = encryptString(world.bob, "alice", "b$turn")
            assertEquals("b$turn", decryptString(world.alice, "bob", up))
        }
    }

    @Test
    fun `safety numbers match on both sides`() = runBlocking {
        val world = World()
        world.bob.decrypt("alice", encryptString(world.alice, "bob", "hi"))

        val a = world.alice.safetyNumber("bob")
        val b = world.bob.safetyNumber("alice")
        assertNotNull(a)
        assertEquals(60, a!!.length)
        assertEquals("both sides must read out the same number", a, b)
    }

    /* ------------------------------------------------------------------------ forward secrecy */

    @Test
    fun `every message has a distinct ciphertext`() = runBlocking {
        val world = World()
        val first = encryptString(world.alice, "bob", "same")
        val second = encryptString(world.alice, "bob", "same")
        assertNotEquals(first, second)

        assertEquals("same", decryptString(world.bob, "alice", first))
        assertEquals("same", decryptString(world.bob, "alice", second))
    }

    @Test
    fun `replaying a consumed message is rejected`() = runBlocking {
        val world = World()
        val wire = encryptString(world.alice, "bob", "once")
        assertNotNull(world.bob.decrypt("alice", wire))
        assertNull("a message key must never be used twice", world.bob.decrypt("alice", wire))
    }

    @Test
    fun `tampering with the ciphertext fails authentication`() = runBlocking {
        val world = World()
        val wire = encryptString(world.alice, "bob", "secret")
        val flipped = wire.dropLast(4) + "AAAA"
        assertNull(decryptString(world.bob, "alice", flipped))
    }

    @Test
    fun `a message from a stranger is refused`() = runBlocking {
        val world = World()
        val wire = encryptString(world.alice, "bob", "hi")
        val stranger = E2ee(MemorySecretStorage(), "stranger") { null }
        assertNull(stranger.decrypt("bob", wire))
    }

    /* -------------------------------------------------------------------- out-of-order + loss */

    @Test
    fun `out of order delivery still decrypts`() = runBlocking {
        val world = World()
        val wires = (1..5).map { encryptString(world.alice, "bob", "m$it") }
        // Deliver 1, then 4, then 2 — the classic case on a flaky mobile link.
        assertEquals("m1", decryptString(world.bob, "alice", wires[0]))
        assertEquals("m4", decryptString(world.bob, "alice", wires[3]))
        assertEquals("m2", decryptString(world.bob, "alice", wires[1]))
        assertEquals("m3", decryptString(world.bob, "alice", wires[2]))
        assertEquals("m5", decryptString(world.bob, "alice", wires[4]))
    }

    @Test
    fun `messages sent before a reply still decrypt after the ratchet turns`() = runBlocking {
        val world = World()
        val early1 = encryptString(world.alice, "bob", "e1")
        val early2 = encryptString(world.alice, "bob", "e2")

        // Bob answers first, which turns his ratchet; the second old message lands afterwards.
        world.bob.decrypt("alice", early1)
        val reply = encryptString(world.bob, "alice", "r1")
        world.alice.decrypt("bob", reply)

        assertEquals("e2", decryptString(world.bob, "alice", early2))
    }

    @Test
    fun `a session survives being reloaded from storage`() = runBlocking {
        val storageB = MemorySecretStorage()
        val directory = mutableMapOf<String, PreKeyBundle>()
        val bob = E2ee(storageB, "bob-device") { directory[it] }
        bob.topUpOneTimePreKeys()
        val alice = E2ee(MemorySecretStorage(), "alice-device") { directory[it] }
        directory["alice"] = bundleOf(alice, "alice")
        directory["bob"] = bundleOf(bob, "bob")

        bob.decrypt("alice", encryptString(alice, "bob", "before"))

        // Process death: a brand new engine over the same storage must continue the same chain.
        val revived = E2ee(storageB, "bob-device") { directory[it] }
        val wire = encryptString(alice, "bob", "after")
        assertEquals("after", decryptString(revived, "alice", wire))
    }

    /* -------------------------------------------------------------------------- pre-key pool */

    @Test
    fun `a one-time pre-key is consumed exactly once`() = runBlocking {
        val world = World()
        val before = world.bob.oneTimePreKeys().size
        assertEquals(20, before)

        world.bob.decrypt("alice", encryptString(world.alice, "bob", "hi"))
        assertEquals("the handshake burns one key", before - 1, world.bob.oneTimePreKeys().size)
    }

    @Test
    fun `a handshake without a one-time key still works`() = runBlocking {
        val world = World(withOneTime = false)
        val wire = encryptString(world.alice, "bob", "no otk")
        assertEquals("no otk", decryptString(world.bob, "alice", wire))
    }

    @Test
    fun `a bad signature on the signed pre-key is rejected`() = runBlocking {
        val world = World()
        val forged = bundleOf(world.bob, "bob").copy(signedPreKeySignature = ByteArray(64))
        val mallory = E2ee(MemorySecretStorage(), "mallory") { forged }
        var threw = false
        try {
            encryptString(mallory, "bob", "hi")
        } catch (t: Throwable) {
            threw = true
        }
        assertTrue("must not start a session with an unverified bundle", threw)
    }

    @Test
    fun `no pre-keys published surfaces as a typed error`() = runBlocking {
        val alice = E2ee(MemorySecretStorage(), "alice-device") { null }
        var caught: E2ee.NoPreKeysException? = null
        try {
            encryptString(alice, "ghost", "hi")
        } catch (e: E2ee.NoPreKeysException) {
            caught = e
        }
        assertEquals("ghost", caught?.peerId)
    }

    /* ------------------------------------------------------------------------------- wire */

    @Test
    fun `garbage on the wire is ignored instead of crashing`() = runBlocking {
        val bob = World().bob
        assertNull(bob.decrypt("alice", ""))
        assertNull(bob.decrypt("alice", "not-a-payload"))
        assertNull(bob.decrypt("alice", "b1.AAAA"))
    }

    @Test
    fun `identity and signed pre-key are stable across restarts`() = runBlocking {
        val storage = MemorySecretStorage()
        val one = E2ee(storage, "device") { null }
        val two = E2ee(storage, "device") { null }
        assertTrue(one.identity().dhPublic.contentEquals(two.identity().dhPublic))
        assertEquals(one.signedPreKey().id, two.signedPreKey().id)
    }
}

/** Publishes [peer]'s bundle the way `GET /v1/keys/{id}` would, with one unspent one-time key. */
private fun bundleOf(peer: E2ee, userId: String, withOneTime: Boolean = true): PreKeyBundle {
    val base = peer.myBundle()
    val otk = if (withOneTime) peer.oneTimePreKeys().firstOrNull() else null
    return base.copy(userId = userId, oneTimePreKeyId = otk?.id, oneTimePreKeyPublic = otk?.public)
}
