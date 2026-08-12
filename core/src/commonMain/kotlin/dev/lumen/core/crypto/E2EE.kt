package dev.lumen.core.crypto

import dev.lumen.core.model.DeviceId

/**
 * E2EE seam — FROZEN at M1; envelope format normative in docs/e2ee.md.
 *
 * v1 implementation: X25519 + libsodium crypto_box, keys protected at
 * rest by the platform keychain. OMEMO 2 is the hard-pinned first
 * post-MVP milestone; this interface is what makes the swap mechanical
 * rather than a rewrite.
 *
 * Honest scope (per docs/e2ee.md): no forward secrecy, no
 * post-compromise security, metadata visible to the server operator.
 * The locked product claim is "content encrypted; the server sees when
 * and how much you sync" — nothing stronger.
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
 * Wire envelope — normative in docs/e2ee.md §6, freezes at M4 (G3).
 *
 * [wrappedKeys] supports the multi-device path (OMEMO-style): encrypt
 * the payload once under a random symmetric key, then wrap that key per
 * recipient device. v1 populates the single-recipient path only, but the
 * field exists so adding N-device fan-out at OMEMO 2 is not a wire break.
 *
 * [padding] blunts the metadata leak where ciphertext size approximates
 * app count. Optional in v1; present so the wire format never needs a
 * version bump to add it.
 */
data class EncryptedPayload(
    val version: Int = 1,
    val senderDeviceId: DeviceId,
    val nonce: ByteArray,
    val ciphertext: ByteArray,
    val wrappedKeys: Map<DeviceId, ByteArray> = emptyMap(),
    val padding: Int = 0,
)

/**
 * Platform keychain abstraction. Android: hardware-wrapped at rest
 * (Keystore AES-256-GCM wrapping an X25519 key — the Android Keystore
 * cannot hold X25519 keys natively, see docs/e2ee.md §5.2). Desktop:
 * Secret Service / libsecret (weaker at-rest guarantee than Android;
 * keyring is unlocked for the session, see §5.3).
 */
interface Keychain {
    /** Create or load the device's X25519 keypair. */
    fun deviceKeyPair(): KeyPairRef
}

data class KeyPairRef(
    val publicKey: ByteArray,
    /**
     * Opaque handle to the private key.
     *
     * AT-REST guarantee only: the key is protected by the platform
     * keychain while stored. It DOES materialize in app process memory
     * during every encrypt/decrypt — unavoidable with X25519 on
     * Android (no Keystore XDH support at API 35). A5 (unlocked device
     * thief) and live-process-memory attackers are not defended against
     * in v1, and none is claimed.
     */
    val privateKeyHandle: ByteArray,
)
