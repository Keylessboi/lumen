package dev.lumen.app.keychain

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [LinuxKeychain] must hand back the SAME keypair on every load — the
 * at-rest guarantee in docs/e2ee.md §5.3. Runs against the private-file
 * store in a temp dir so it passes on any machine (it used to need a live,
 * unlocked keyring and failed everywhere else, including CI).
 */
class LinuxKeychainTest {

    private val dir = kotlin.io.path.createTempDirectory("lumen-keychain").toFile()
    private fun keychain() = LinuxKeychain(LinuxSecretStore(dir, useSecretService = false))

    @Test
    fun `generated keypair persists across lookups`() {
        val keychain = keychain()
        val first = keychain.deviceKeyPair()

        // 32-byte raw X25519 keys (RFC 7748), not DER.
        assertEquals(32, first.publicKey.size)
        assertEquals(32, first.privateKeyHandle.size)

        // A second call must load the SAME keypair from the keyring.
        val second = keychain().deviceKeyPair()
        assertContentEquals(first.publicKey, second.publicKey)
        assertContentEquals(first.privateKeyHandle, second.privateKeyHandle)
    }

    @Test
    fun `public key is a valid X25519 point`() {
        val keychain = keychain()
        val pair = keychain.deviceKeyPair()
        // The point is on the curve; BC would throw on a malformed key.
        val pub = org.bouncycastle.crypto.params.X25519PublicKeyParameters(pair.publicKey)
        assertTrue(pub.encoded.contentEquals(pair.publicKey))
    }

    @Test
    fun `secrets round-trip through the private file and can be removed`() {
        val store = LinuxSecretStore(dir, useSecretService = false)
        store.put("sync.password", "p@ss:word=1")
        assertEquals("p@ss:word=1", LinuxSecretStore(dir, useSecretService = false).get("sync.password"))
        store.remove("sync.password")
        assertEquals(null, store.get("sync.password"))
        val perms = java.nio.file.Files.getPosixFilePermissions(java.io.File(dir, "secrets.properties").toPath())
        assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"), perms)
    }

    @Test
    fun `a stored key that cannot be read is never replaced`() {
        val store = LinuxSecretStore(dir, useSecretService = false)
        store.put("device-key", "garbage")
        kotlin.test.assertFailsWith<IllegalStateException> { keychain().deviceKeyPair() }
        assertEquals("garbage", store.get("device-key"))
    }
}
