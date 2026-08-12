package dev.lumen.core.crypto

import dev.lumen.core.model.DeviceId
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Round-trip tests for [CryptoBoxE2EE] — the M4 `crypto_box` implementation.
 *
 * Two parties, A and B, each with an X25519 keypair. A encrypts to B; B
 * decrypts using A's public key. The box key is symmetric (both derive the
 * same X25519 shared secret), so this proves the full construction: X25519
 * agreement, HSalsa20 beforenm, XSalsa20-Poly1305 secretbox, and the
 * envelope version gate.
 */
class CryptoBoxE2EETest {

    private val random = SecureRandom()

    private class Party(val id: DeviceId) {
        val sk = X25519PrivateKeyParameters(SecureRandom())
        val pk: X25519PublicKeyParameters = sk.generatePublicKey()
    }

    private val alice = Party(DeviceId("alice"))
    private val bob = Party(DeviceId("bob"))

    private fun resolver(vararg parties: Party): (DeviceId) -> X25519PublicKeyParameters =
        { id -> parties.first { it.id == id }.pk }

    @Test
    fun `alice encrypts to bob and bob decrypts`() {
        val aliceE2ee = CryptoBoxE2EE(
            senderDeviceId = alice.id,
            senderSk = alice.sk,
            senderPk = alice.pk,
            publicKeyResolver = resolver(alice, bob),
        )
        val bobE2ee = CryptoBoxE2EE(
            senderDeviceId = bob.id,
            senderSk = bob.sk,
            senderPk = bob.pk,
            publicKeyResolver = resolver(alice, bob),
        )

        val plaintext = "sync record payload".toByteArray()
        val envelope = aliceE2ee.encrypt(plaintext, bob.id)

        assertEquals(1, envelope.version)
        assertEquals(alice.id, envelope.senderDeviceId)
        assertEquals(24, envelope.nonce.size)

        assertContentEquals(plaintext, bobE2ee.decrypt(envelope))
    }

    @Test
    fun `bob cannot decrypt with a third party's key`() {
        val mallory = Party(DeviceId("mallory"))
        val aliceE2ee = CryptoBoxE2EE(
            senderDeviceId = alice.id,
            senderSk = alice.sk,
            senderPk = alice.pk,
            publicKeyResolver = resolver(alice, bob),
        )
        val malloryE2ee = CryptoBoxE2EE(
            senderDeviceId = mallory.id,
            senderSk = mallory.sk,
            senderPk = mallory.pk,
            publicKeyResolver = resolver(alice, mallory),
        )

        val envelope = aliceE2ee.encrypt("secret".toByteArray(), bob.id)

        assertFailsWith<IllegalArgumentException> {
            malloryE2ee.decrypt(envelope)
        }
    }

    @Test
    fun `tampered ciphertext is rejected`() {
        val aliceE2ee = CryptoBoxE2EE(
            senderDeviceId = alice.id,
            senderSk = alice.sk,
            senderPk = alice.pk,
            publicKeyResolver = resolver(alice, bob),
        )
        val bobE2ee = CryptoBoxE2EE(
            senderDeviceId = bob.id,
            senderSk = bob.sk,
            senderPk = bob.pk,
            publicKeyResolver = resolver(alice, bob),
        )

        val envelope = aliceE2ee.encrypt("do not modify me".toByteArray(), bob.id)
        envelope.ciphertext[envelope.ciphertext.size - 1] = (envelope.ciphertext.last().toInt() xor 0x01).toByte()

        assertFailsWith<IllegalArgumentException> {
            bobE2ee.decrypt(envelope)
        }
    }

    @Test
    fun `unknown envelope version is a hard failure`() {
        val aliceE2ee = CryptoBoxE2EE(
            senderDeviceId = alice.id,
            senderSk = alice.sk,
            senderPk = alice.pk,
            publicKeyResolver = resolver(alice, bob),
        )

        val envelope = aliceE2ee.encrypt("v2 test".toByteArray(), bob.id)
        val v2 = envelope.copy(version = 99)

        assertFailsWith<IllegalArgumentException> { aliceE2ee.decrypt(v2) }
    }

    @Test
    fun `nonce is 24 random bytes and unique per encryption`() {
        val aliceE2ee = CryptoBoxE2EE(
            senderDeviceId = alice.id,
            senderSk = alice.sk,
            senderPk = alice.pk,
            publicKeyResolver = resolver(alice, bob),
        )

        val e1 = aliceE2ee.encrypt("a".toByteArray(), bob.id)
        val e2 = aliceE2ee.encrypt("b".toByteArray(), bob.id)

        // 24-byte nonce from a CSPRNG: full size, never all-zero, and fresh
        // per encryption (collision is negligible at this size).
        assertEquals(24, e1.nonce.size)
        assertEquals(24, e2.nonce.size)
        assertTrue(e1.nonce.any { it != 0.toByte() })
        assertTrue(e2.nonce.any { it != 0.toByte() })
        assertFalse(e1.nonce.contentEquals(e2.nonce))
    }

    @Test
    fun `identity fingerprint is a stable hex of the public key`() {
        val aliceE2ee = CryptoBoxE2EE(
            senderDeviceId = alice.id,
            senderSk = alice.sk,
            senderPk = alice.pk,
            publicKeyResolver = resolver(alice, bob),
        )
        val expected = alice.pk.encoded.joinToString("") { "%02x".format(it) }.chunked(8).joinToString(":")
        assertEquals(expected, aliceE2ee.identityFingerprint())
    }
}