package dev.lumen.core.crypto

import dev.lumen.core.model.DeviceId
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Envelope v2 — OMEMO 2 payload encryption with per-device key fan-out.
 *
 * ## What is OMEMO 2 here, and what is not
 *
 * The payload is encrypted exactly as XEP-0384 (v0.8, `urn:xmpp:omemo:2`)
 * §4.4 specifies:
 *
 *  1. a fresh 32-byte message key;
 *  2. HKDF-SHA-256(key, salt = 32 zero bytes, info = "OMEMO Payload") → 80
 *     bytes: a 32-byte AES key, a 32-byte HMAC key and a 16-byte IV;
 *  3. AES-256-CBC (PKCS#7) over the payload;
 *  4. HMAC-SHA-256 over the ciphertext, truncated to 16 bytes;
 *  5. the 48-byte key material `key || hmac` is what each recipient gets.
 *
 * Where OMEMO 2 hands that key material to a Double Ratchet session per
 * device, Lumen wraps it with `crypto_box` to each device's long-lived
 * X25519 identity key. A ratchet needs a two-way, in-order conversation;
 * sync records sit in a pubsub node and are read later, in any order, by
 * devices that may not exist yet when the record is written. So this keeps
 * OMEMO 2's multi-device structure and payload cryptography, and — stated
 * plainly, as `docs/e2ee.md` does — does NOT add forward secrecy.
 *
 * ## Padding
 *
 * The plaintext is framed as `length(4) || plaintext || zeros` and padded to
 * a multiple of [PAD_BLOCK], so ciphertext size no longer tracks how many
 * apps a record names. The frame is inside the authenticated ciphertext; the
 * envelope's [EncryptedPayload.padding] field only reports the count and is
 * never trusted on decrypt (it is outside the MAC).
 *
 * ## Compatibility
 *
 * v1 envelopes (single-recipient `crypto_box`) still decrypt, so records a
 * device published before upgrading are not stranded on the server.
 */
class Omemo2E2EE(
    private val selfDeviceId: DeviceId,
    selfSk: X25519PrivateKeyParameters,
    private val selfPk: X25519PublicKeyParameters,
    /** Identity key for a device, or null when the device is not trusted (yet). */
    private val publicKeyResolver: (DeviceId) -> X25519PublicKeyParameters?,
    private val random: SecureRandom = SecureRandom(),
) : MultiDeviceE2EE {

    private val box = CryptoBoxE2EE(
        senderDeviceId = selfDeviceId,
        senderSk = selfSk,
        senderPk = selfPk,
        random = random,
        publicKeyResolver = { id -> resolve(id) },
    )

    override fun encrypt(plaintext: ByteArray, recipientDeviceId: DeviceId): EncryptedPayload =
        encryptForDevices(plaintext, setOf(recipientDeviceId))

    override fun encryptForDevices(plaintext: ByteArray, recipients: Set<DeviceId>): EncryptedPayload {
        val framed = frame(plaintext)
        val messageKey = ByteArray(KEY_SIZE).also { random.nextBytes(it) }
        val keys = deriveKeys(messageKey)
        val ciphertext = aesCbc(Cipher.ENCRYPT_MODE, keys, framed)
        val tag = hmac(keys.authKey, ciphertext)
        val keyMaterial = messageKey + tag

        val wrapped = (recipients + selfDeviceId).associateWith { device ->
            box.seal(keyMaterial, resolve(device))
        }
        return EncryptedPayload(
            version = VERSION,
            senderDeviceId = selfDeviceId,
            nonce = ByteArray(0), // per-recipient nonces live inside each wrapped key
            ciphertext = ciphertext,
            wrappedKeys = wrapped,
            padding = framed.size - FRAME_HEADER - plaintext.size,
        )
    }

    override fun decrypt(payload: EncryptedPayload): ByteArray = when (payload.version) {
        CryptoBoxE2EE.VERSION -> box.decrypt(payload)
        VERSION -> decryptV2(payload)
        else -> throw IllegalArgumentException(
            "envelope version ${payload.version} is not supported (this build understands 1 and $VERSION)",
        )
    }

    override fun identityFingerprint(): String = fingerprintOf(selfPk.encoded)

    private fun decryptV2(payload: EncryptedPayload): ByteArray {
        val sealed = payload.wrappedKeys[selfDeviceId]
            ?: throw NotAddressedToThisDevice(
                "record from ${payload.senderDeviceId.value} was not encrypted for this device",
            )
        val keyMaterial = box.open(sealed, resolve(payload.senderDeviceId))
        require(keyMaterial.size == KEY_SIZE + TAG_SIZE) { "key material has the wrong length" }
        val messageKey = keyMaterial.copyOfRange(0, KEY_SIZE)
        val expectedTag = keyMaterial.copyOfRange(KEY_SIZE, keyMaterial.size)
        val keys = deriveKeys(messageKey)
        require(MessageDigest.isEqual(hmac(keys.authKey, payload.ciphertext), expectedTag)) {
            "payload authentication failed"
        }
        return unframe(aesCbc(Cipher.DECRYPT_MODE, keys, payload.ciphertext))
    }

    private fun resolve(device: DeviceId): X25519PublicKeyParameters =
        if (device == selfDeviceId) {
            selfPk
        } else {
            publicKeyResolver(device) ?: throw IllegalStateException("no trusted key for device ${device.value}")
        }

    private class Keys(val encKey: ByteArray, val authKey: ByteArray, val iv: ByteArray)

    private fun deriveKeys(messageKey: ByteArray): Keys {
        val out = ByteArray(80)
        HKDFBytesGenerator(SHA256Digest()).apply {
            init(HKDFParameters(messageKey, ByteArray(32), INFO))
            generateBytes(out, 0, out.size)
        }
        return Keys(out.copyOfRange(0, 32), out.copyOfRange(32, 64), out.copyOfRange(64, 80))
    }

    private fun aesCbc(mode: Int, keys: Keys, input: ByteArray): ByteArray =
        Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(mode, SecretKeySpec(keys.encKey, "AES"), IvParameterSpec(keys.iv))
            doFinal(input)
        }

    private fun hmac(authKey: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(authKey, "HmacSHA256"))
            doFinal(data).copyOf(TAG_SIZE)
        }

    private fun frame(plaintext: ByteArray): ByteArray {
        val unpadded = FRAME_HEADER + plaintext.size
        val total = ((unpadded + PAD_BLOCK - 1) / PAD_BLOCK) * PAD_BLOCK
        return ByteBuffer.allocate(total).putInt(plaintext.size).put(plaintext).array()
    }

    private fun unframe(framed: ByteArray): ByteArray {
        require(framed.size >= FRAME_HEADER) { "payload frame truncated" }
        val length = ByteBuffer.wrap(framed, 0, FRAME_HEADER).int
        require(length in 0..(framed.size - FRAME_HEADER)) { "payload frame length out of range" }
        return framed.copyOfRange(FRAME_HEADER, FRAME_HEADER + length)
    }

    companion object {
        const val VERSION = 2
        private const val KEY_SIZE = 32
        private const val TAG_SIZE = 16
        private const val FRAME_HEADER = 4
        private const val PAD_BLOCK = 64
        private val INFO = "OMEMO Payload".toByteArray(Charsets.US_ASCII)

        /** Same rendering as [CryptoBoxE2EE.identityFingerprint]: 8-hex groups. */
        fun fingerprintOf(publicKey: ByteArray): String =
            publicKey.joinToString("") { "%02x".format(it) }.chunked(8).joinToString(":")
    }
}
