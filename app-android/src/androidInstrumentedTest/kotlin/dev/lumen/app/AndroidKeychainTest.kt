package dev.lumen.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lumen.core.crypto.AndroidKeychain
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device keychain verification (Android Keystore only exists on a real
 * device — this is why it's an instrumented test, not a JVM one).
 *
 * Proves the §5.2 contract end to end:
 *  - a fresh keychain generates a usable X25519 pair;
 *  - a new keychain instance (new process / new launch) reads back the SAME
 *    public key — the private key survived its trip through the Keystore
 *    AES-GCM wrap;
 *  - the stored private handle reconstructs the matching public key, so the
 *    pair SyncManager builds from it is the pair that was persisted.
 */
@RunWith(AndroidJUnit4::class)
class AndroidKeychainTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun freshKeychainGeneratesUsablePair() {
        // Isolate this test from any app-installed key by using a unique alias.
        val keychain = AndroidKeychain(context, keystoreAlias = "test_fresh_${System.nanoTime()}")

        val pair = keychain.deviceKeyPair()

        assertEquals(32, pair.publicKey.size)
        assertEquals(32, pair.privateKeyHandle.size)

        // The handle must reconstruct a key whose public half matches.
        val sk = X25519PrivateKeyParameters(pair.privateKeyHandle)
        val derivedPk = X25519PublicKeyParameters(sk.generatePublicKey().encoded)
        assertArrayEquals("stored private must match stored public", pair.publicKey, derivedPk.encoded)
    }

    @Test
    fun keypairPersistsAcrossInstances() {
        val alias = "test_persist_${System.nanoTime()}"
        val first = AndroidKeychain(context, keystoreAlias = alias).deviceKeyPair()

        // A second keychain with the SAME alias is a second "launch": the
        // Keystore key and the wrapped file both survive, so the identity
        // must be identical — a device that forgot its key would orphan
        // every record written under the old identity.
        val second = AndroidKeychain(context, keystoreAlias = alias).deviceKeyPair()

        assertArrayEquals("public key must be stable across launches", first.publicKey, second.publicKey)
        assertArrayEquals("private handle must decrypt to the same key", first.privateKeyHandle, second.privateKeyHandle)

        val sk = X25519PrivateKeyParameters(second.privateKeyHandle)
        val derivedPk = X25519PublicKeyParameters(sk.generatePublicKey().encoded)
        assertArrayEquals(second.publicKey, derivedPk.encoded)
    }

    @Test
    fun distinctAliasesGetDistinctIdentities() {
        val a = AndroidKeychain(context, keystoreAlias = "test_a_${System.nanoTime()}").deviceKeyPair()
        val b = AndroidKeychain(context, keystoreAlias = "test_b_${System.nanoTime()}").deviceKeyPair()

        assertTrue("two devices must not share a key", !a.publicKey.contentEquals(b.publicKey))
        assertNotNull(a.publicKey)
    }
}
