package dev.lumen.macos.keychain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The device identity has to be the SAME identity every time, or a restore
 * brings back a month of history under a device that no longer exists.
 *
 * These run against a fake secret store. The one test that touches the real
 * login keychain is opt-in — see [MacosKeychainLiveTest].
 */
class MacosKeychainTest {

    private class FakeStore(
        var entry: String? = null,
        var writes: Int = 0,
    ) : MacosKeychain.SecretStore {
        override fun read(account: String): String? = entry
        override fun write(account: String, value: String) {
            entry = value
            writes++
        }
    }

    @Test
    fun `a keypair is generated on first use and stored`() {
        val store = FakeStore()

        val pair = MacosKeychain(store).deviceKeyPair()

        assertEquals(32, pair.publicKey.size, "not a raw X25519 public key")
        assertEquals(32, pair.privateKeyHandle.size, "not a raw X25519 private key")
        assertEquals(1, store.writes)
        assertTrue(store.entry!!.contains(":"), "stored form is not public:private")
    }

    @Test
    fun `the identity is stable across calls and instances`() {
        val store = FakeStore()

        val first = MacosKeychain(store).deviceKeyPair()
        val second = MacosKeychain(store).deviceKeyPair()

        assertTrue(first.publicKey.contentEquals(second.publicKey), "the public key changed")
        assertTrue(
            first.privateKeyHandle.contentEquals(second.privateKeyHandle),
            "the private key changed",
        )
        assertEquals(1, store.writes, "a stored key was written over")
    }

    @Test
    fun `two devices do not get the same key`() {
        val a = MacosKeychain(FakeStore()).deviceKeyPair()
        val b = MacosKeychain(FakeStore()).deviceKeyPair()

        assertNotEquals(
            a.privateKeyHandle.toList(),
            b.privateKeyHandle.toList(),
            "every install would share one identity",
        )
    }

    /**
     * A damaged entry must not be silently replaced. Regenerating turns
     * "something is wrong with your key" into "you are now a different
     * device", and orphans every record written under the old one.
     */
    @Test
    fun `a damaged entry fails loudly rather than becoming a new identity`() {
        val cases = listOf(
            "not-a-key",
            "onlyonepart",
            ":",
            "!!!!:!!!!",
            // Right shape, wrong sizes — a truncated write.
            "AAAA:AAAA",
        )
        for (stored in cases) {
            val store = FakeStore(entry = stored)
            assertFailsWith<IllegalArgumentException>("accepted '$stored'") {
                MacosKeychain(store).deviceKeyPair()
            }
            assertEquals(0, store.writes, "'$stored' was overwritten with a new identity")
        }
    }

    @Test
    fun `an unreachable keychain yields no key rather than an exception`() {
        val broken = object : MacosKeychain.SecretStore {
            override fun read(account: String): String? = null
            override fun write(account: String, value: String) = error("no keychain here")
        }

        // The export path calls this: a backup of a month of history must not
        // fail over an identity the user has not started using yet.
        assertEquals(null, MacosKeychain(broken).deviceKeyPairOrNull())
    }
}
