package dev.lumen.app.keychain

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Live test for [LinuxKeychain] against the REAL Secret Service
 * (secret-tool / gnome-keyring). Not a pure unit test — it needs the
 * login keyring unlocked, exactly like the collector harnesses need a
 * real session. It proves the at-rest guarantee in docs/e2ee.md §5.3:
 * a keypair generated once is the same keypair on the next load.
 */
class LinuxKeychainTest {

    @Test
    fun `generated keypair persists across lookups`() {
        val keychain = LinuxKeychain()
        val first = keychain.deviceKeyPair()

        // 32-byte raw X25519 keys (RFC 7748), not DER.
        assertEquals(32, first.publicKey.size)
        assertEquals(32, first.privateKeyHandle.size)

        // A second call must load the SAME keypair from the keyring.
        val second = keychain.deviceKeyPair()
        assertContentEquals(first.publicKey, second.publicKey)
        assertContentEquals(first.privateKeyHandle, second.privateKeyHandle)
    }

    @Test
    fun `public key is a valid X25519 point`() {
        val keychain = LinuxKeychain()
        val pair = keychain.deviceKeyPair()
        // The point is on the curve; BC would throw on a malformed key.
        val pub = org.bouncycastle.crypto.params.X25519PublicKeyParameters(pair.publicKey)
        assertTrue(pub.encoded.contentEquals(pair.publicKey))
    }
}