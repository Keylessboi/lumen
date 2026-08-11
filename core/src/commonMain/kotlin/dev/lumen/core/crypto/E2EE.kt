package dev.lumen.core.crypto

import dev.lumen.core.model.DeviceId

/**
 * E2EE seam — FROZEN at M1; envelope format normative in docs/e2ee.md.
 *
 * v1 implementation: X25519 + libsodium secretbox, keys in hardware
 * keystore (Android) / OS keyring (desktop). OMEMO 2 is the hard-pinned
 * first post-MVP milestone; this interface is what makes the swap
 * mechanical rather than a rewrite.
 */
interface E2EE {

    /** Encrypt a sync record payload before upload. */
    fun encrypt(plaintext: ByteArray, recipientDeviceId: DeviceId): EncryptedPayload

    /** Decrypt a received payload. Throws on tamper/auth failure. */
    fun decrypt(payload: EncryptedPayload): ByteArray

    /** Fingerprint for out-of-band verification (QR / string compare). */
    fun identityFingerprint(): String
}

/**
 * Wire envelope. [nonce] + [ciphertext] + [senderDeviceId] are the
 * minimum; format version field enables the OMEMO migration path.
 */
data class EncryptedPayload(
    val version: Int = 1,
    val senderDeviceId: DeviceId,
    val nonce: ByteArray,
    val ciphertext: ByteArray,
)

/**
 * Platform keychain abstraction. Android: hardware Keystore (Agent B).
 * Desktop: Secret Service / libsecret (Agent A). Keys NEVER leave this
 * interface as plaintext bytes.
 */
interface Keychain {
    /** Create or load the device's X25519 keypair. */
    fun deviceKeyPair(): KeyPairRef
}

data class KeyPairRef(
    val publicKey: ByteArray,
    /** Opaque handle; the private key never materializes in app memory. */
    val privateKeyHandle: ByteArray,
)
