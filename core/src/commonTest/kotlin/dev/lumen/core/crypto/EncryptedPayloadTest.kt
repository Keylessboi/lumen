package dev.lumen.core.crypto

import dev.lumen.core.model.DeviceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Envelope contract (Agent B, M1 gate) — `docs/e2ee.md` §6.
 *
 * The envelope freezes at M4, but its *rules* are written down now and the
 * `EncryptedPayload` shape is on the M1 frozen surface. These tests pin the
 * two rules that are checkable without an implementation:
 *
 *  - `version` mismatch is a hard failure, not a downgrade.
 *  - the nonce is 24 bytes from a CSPRNG, never a counter.
 *
 * [E2EEContract] below is the executable form of the rest — Agent A (or B's
 * sync-test-server at M4) subclasses it against the real libsodium
 * implementation. It deliberately has no subclass yet: the envelope is
 * pre-freeze, and a contract kit written before the implementation is the
 * point of the M1 re-cut.
 */
class EncryptedPayloadTest {

    private val sender = DeviceId("11111111-2222-3333-4444-555555555555")
    private val recipient = DeviceId("99999999-8888-7777-6666-555555555555")

    private fun nonce() = ByteArray(NONCE_BYTES) { it.toByte() }

    private fun payload(version: Int = 1) = EncryptedPayload(
        version = version,
        senderDeviceId = sender,
        nonce = nonce(),
        ciphertext = ByteArray(64) { it.toByte() },
    )

    @Test
    fun `the current envelope version is 1`() {
        assertEquals(1, EncryptedPayload(senderDeviceId = sender, nonce = nonce(), ciphertext = ByteArray(0)).version)
    }

    @Test
    fun `multi-device fields default to the single-recipient v1 path`() {
        // wrappedKeys and padding exist so OMEMO 2 fan-out and metadata
        // padding are not wire breaks. v1 leaves them empty; the defaults are
        // part of the contract, not an accident.
        val p = payload()
        assertEquals(emptyMap(), p.wrappedKeys)
        assertEquals(0, p.padding)
    }

    @Test
    fun `wrappedKeys is keyed by recipient device`() {
        val wrapped = payload().copy(wrappedKeys = mapOf(recipient to ByteArray(32)))
        assertEquals(setOf(recipient), wrapped.wrappedKeys.keys)
        assertEquals(32, wrapped.wrappedKeys.getValue(recipient).size)
    }

    @Test
    fun `a version-1 reader accepts version 1`() {
        assertEquals(1, requireSupportedVersion(payload(version = 1)).version)
    }

    @Test
    fun `a version-1 reader rejects any other version outright`() {
        // docs/e2ee.md §6: "Recipients MUST reject unknown versions rather
        // than best-effort parse" and "version mismatch is a hard failure,
        // not a downgrade" — so a v2 envelope must fail even though a v1
        // reader could physically read its v1-shaped fields.
        for (version in listOf(0, 2, 99, -1)) {
            assertFailsWith<IllegalArgumentException>("version $version must be rejected") {
                requireSupportedVersion(payload(version = version))
            }
        }
    }

    @Test
    fun `the nonce is exactly 24 bytes`() {
        // XSalsa20-Poly1305 nonce width. A shorter nonce is a truncation bug;
        // a longer one means someone swapped primitives without saying so.
        assertEquals(24, NONCE_BYTES)
        assertEquals(NONCE_BYTES, payload().nonce.size)
    }

    @Test
    fun `an envelope with a wrong-width nonce is rejected`() {
        for (width in listOf(0, 12, 16, 23, 25, 32)) {
            val bad = EncryptedPayload(
                senderDeviceId = sender,
                nonce = ByteArray(width),
                ciphertext = ByteArray(64),
            )
            assertFailsWith<IllegalArgumentException>("nonce width $width must be rejected") {
                requireWellFormedNonce(bad)
            }
        }
    }

    @Test
    fun `the sender device is cleartext so the recipient can find the key`() {
        // §6 marks senderDeviceId cleartext by design: the recipient needs it
        // to look up the sender's public key before it can decrypt anything.
        // This is a documented metadata leak, not an oversight.
        assertEquals(sender, payload().senderDeviceId)
    }

    private companion object {
        const val NONCE_BYTES = 24

        /**
         * Executable form of the §6 version rule. Every reader — the sync
         * engine, the test server's verifier, the M5 import path — must apply
         * exactly this, so it lives in the contract rather than in three
         * places.
         */
        fun requireSupportedVersion(payload: EncryptedPayload): EncryptedPayload {
            require(payload.version == 1) {
                "unsupported envelope version ${payload.version}; this reader speaks version 1 only"
            }
            return payload
        }

        fun requireWellFormedNonce(payload: EncryptedPayload): EncryptedPayload {
            require(payload.nonce.size == NONCE_BYTES) {
                "nonce must be $NONCE_BYTES bytes, was ${payload.nonce.size}"
            }
            return payload
        }
    }
}

/**
 * Contract kit for any [E2EE] implementation — `docs/e2ee.md` §6.
 *
 * Subclass this at M4 with the real libsodium implementation:
 *
 * ```kotlin
 * class LibsodiumE2EEContractTest : E2EEContract() {
 *     override fun impl(): E2EE = LibsodiumE2EE(testKeychain())
 * }
 * ```
 *
 * Written by the consumer of the seam rather than its author, per the #12
 * re-cut. If a rule below cannot be expressed against the interface, that is
 * a finding about the interface and should be filed before the M4 freeze.
 */
abstract class E2EEContract {

    protected abstract fun impl(): E2EE

    protected open val recipient: DeviceId = DeviceId("99999999-8888-7777-6666-555555555555")

    @Test
    fun `plaintext survives an encrypt-decrypt round trip`() {
        val e2ee = impl()
        val plaintext = "the quick brown fox".encodeToByteArray()
        val decrypted = e2ee.decrypt(e2ee.encrypt(plaintext, recipient))
        assertTrue(plaintext.contentEquals(decrypted))
    }

    @Test
    fun `an empty payload round-trips`() {
        val e2ee = impl()
        assertEquals(0, e2ee.decrypt(e2ee.encrypt(ByteArray(0), recipient)).size)
    }

    @Test
    fun `every encryption uses a fresh nonce`() {
        // §6: "Nonce reuse under the same keypair is catastrophic for
        // XSalsa20." Encrypting the same plaintext twice must not produce the
        // same nonce or the same ciphertext.
        val e2ee = impl()
        val plaintext = "same input".encodeToByteArray()
        val first = e2ee.encrypt(plaintext, recipient)
        val second = e2ee.encrypt(plaintext, recipient)
        assertTrue(!first.nonce.contentEquals(second.nonce), "nonce must not repeat")
        assertTrue(!first.ciphertext.contentEquals(second.ciphertext), "ciphertext must not repeat")
    }

    @Test
    fun `a tampered ciphertext fails authentication instead of decrypting`() {
        // §6: a record that fails authentication MUST be discarded and
        // surfaced, never silently skipped or partially decrypted.
        val e2ee = impl()
        val sealed = e2ee.encrypt("authentic".encodeToByteArray(), recipient)
        val tampered = sealed.copy(
            ciphertext = sealed.ciphertext.copyOf().also { it[0] = (it[0] + 1).toByte() },
        )
        assertFailsWith<Exception> { e2ee.decrypt(tampered) }
    }

    @Test
    fun `a tampered nonce fails authentication`() {
        val e2ee = impl()
        val sealed = e2ee.encrypt("authentic".encodeToByteArray(), recipient)
        val tampered = sealed.copy(
            nonce = sealed.nonce.copyOf().also { it[0] = (it[0] + 1).toByte() },
        )
        assertFailsWith<Exception> { e2ee.decrypt(tampered) }
    }

    @Test
    fun `the identity fingerprint is stable and non-empty`() {
        // It is compared out-of-band by a human reading it aloud or scanning
        // a QR. An unstable fingerprint makes verification meaningless.
        val e2ee = impl()
        val fingerprint = e2ee.identityFingerprint()
        assertTrue(fingerprint.isNotBlank(), "fingerprint must be presentable to a user")
        assertEquals(fingerprint, e2ee.identityFingerprint(), "fingerprint must be stable")
    }
}
