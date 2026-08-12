package dev.lumen.core.crypto

import dev.lumen.core.model.DeviceId
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.engines.XSalsa20Engine
import org.bouncycastle.crypto.generators.Poly1305KeyGenerator
import org.bouncycastle.crypto.macs.Poly1305
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.SecureRandom

/**
 * v1 E2EE — X25519 + XSalsa20-Poly1305 (libsodium `crypto_box`), per
 * `docs/e2ee.md` §2 (adjudicated decision B) and the frozen [E2EE] seam.
 *
 * ## Why hand-rolled primitives, not libsodium bindings
 *
 * The repo rule (gradle/libs.versions.toml, Bouncy Castle choice) is no
 * JNA/native binaries in the distribution. libsodium's `crypto_box`
 * construction is exactly specified and the primitives exist in BC:
 *
 *  1. `shared = X25519(sk, pk)` — BC [X25519Agreement].
 *  2. `k = HSalsa20(shared, zeros16)` — the one primitive BC lacks, so the
 *     Salsa20 core is implemented here (HSalsa20 = the core's middle output
 *     words with the feed-forward addition omitted; see below).
 *  3. `crypto_secretbox_easy`: subkey = first 32 bytes of the XSalsa20
 *     keystream (BC [XSalsa20Engine] does the HSalsa20 key-derivation for
 *     the 24-byte nonce internally), Poly1305 over the plaintext with that
 *     subkey, then XSalsa20 XOR starting at stream block 1 (block 0 was
 *     consumed by the subkey).
 *
 * Wire envelope is [EncryptedPayload]; the exact field layout and the
 * "reject unknown version" rule are normative in `docs/e2ee.md` §6.
 *
 * ## HSalsa20
 *
 * libsodium `crypto_core_hsalsa20` runs the Salsa20 core (16-word state:
 * sigma constants, 32-byte key, 16-byte nonce, sigma again) through 20
 * rounds and outputs words {0,5,10,15,6,7,8,9} WITHOUT adding the input
 * state back (the feed-forward that turns the core into a stream cipher is
 * what Salsa20's `processBlock` does; HSalsa20 omits it). The Salsa20
 * quarter-round is XOR-add-rotate, identical to the core used elsewhere.
 */
class CryptoBoxE2EE(
    private val senderDeviceId: DeviceId,
    private val senderSk: X25519PrivateKeyParameters,
    private val senderPk: X25519PublicKeyParameters,
    private val random: SecureRandom = SecureRandom(),
    private val publicKeyResolver: (DeviceId) -> X25519PublicKeyParameters = { _ ->
        error("key lookup not wired — the Keychain seam lands with M4 key management")
    },
) : E2EE {

    override fun encrypt(plaintext: ByteArray, recipientDeviceId: DeviceId): EncryptedPayload {
        val nonce = ByteArray(NONCE_SIZE).also { random.nextBytes(it) }
        val recipientPk = publicKeyResolver(recipientDeviceId)
        val k = beforeNm(recipientPk, senderSk)
        val ciphertext = secretBox(plaintext, nonce, k)
        return EncryptedPayload(
            version = VERSION,
            senderDeviceId = senderDeviceId,
            nonce = nonce,
            ciphertext = ciphertext,
        )
    }

    override fun decrypt(payload: EncryptedPayload): ByteArray {
        require(payload.version == VERSION) {
            "envelope version ${payload.version} is not supported (this build understands $VERSION)"
        }
        // The box key is symmetric: both sides derive the same shared
        // secret from (my sk, their pk) / (their sk, my pk).
        val senderPk = publicKeyResolver(payload.senderDeviceId)
        val k = beforeNm(senderPk, senderSk)
        return secretBoxOpen(payload.ciphertext, payload.nonce, k)
    }

    override fun identityFingerprint(): String {
        val raw = senderPk.encoded
        return raw.joinToString("") { "%02x".format(it) }.chunked(8).joinToString(":")
    }

    // ---- crypto_box internals -------------------------------------------

    /** `crypto_box_beforenm`: k = HSalsa20(X25519(sk, pk), zeros16). */
    private fun beforeNm(recipientPk: X25519PublicKeyParameters, senderSk: X25519PrivateKeyParameters): ByteArray {
        val agreement = X25519Agreement().also { it.init(senderSk) }
        val shared = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(recipientPk, shared, 0)
        return hsalsa20(shared, ByteArray(16))
    }

    /** `crypto_secretbox_easy` without the outer box nonce offset. */
    private fun secretBox(plaintext: ByteArray, nonce: ByteArray, k: ByteArray): ByteArray {
        val subkey = xsalsa20Keystream(k, nonce, 32)
        val mac = poly1305(subkey, plaintext)
        val ciphertext = xsalsa20Xor(plaintext, k, nonce, startBlock = 1)
        return mac + ciphertext
    }

    /** `crypto_secretbox_open`: decrypt, verify Poly1305 over the PLAINTEXT, then return it. */
    private fun secretBoxOpen(boxed: ByteArray, nonce: ByteArray, k: ByteArray): ByteArray {
        require(boxed.size >= TAG_SIZE) { "ciphertext shorter than the Poly1305 tag" }
        val mac = boxed.copyOfRange(0, TAG_SIZE)
        val body = boxed.copyOfRange(TAG_SIZE, boxed.size)
        val subkey = xsalsa20Keystream(k, nonce, 32)
        // libsodium MACs the PLAINTEXT, not the ciphertext (secretbox_open
        // decrypts first, then authenticates the recovered message) — the
        // subkey block is consumed the same way on both paths.
        val plaintext = xsalsa20Xor(body, k, nonce, startBlock = 1)
        val expected = poly1305(subkey, plaintext)
        // Constant-time-ish compare: a mismatch is an auth failure and MUST
        // surface as an integrity warning, never a silent skip.
        require(mac.contentEquals(expected)) { "Poly1305 authentication failed" }
        return plaintext
    }

    /** First [len] bytes of the XSalsa20(key, nonce) keystream. */
    private fun xsalsa20Keystream(k: ByteArray, nonce: ByteArray, len: Int): ByteArray {
        val engine = XSalsa20Engine()
        engine.init(true, ParametersWithIV(KeyParameter(k), nonce))
        val out = ByteArray(len)
        engine.processBytes(ByteArray(len), 0, len, out, 0)
        return out
    }

    /**
     * XOR [input] with the XSalsa20 keystream starting at block
     * [startBlock] (block 0 is consumed by the Poly1305 subkey).
     * XSalsa20Engine exposes no block offset, so the skipped blocks are
     * generated and discarded.
     */
    private fun xsalsa20Xor(input: ByteArray, k: ByteArray, nonce: ByteArray, startBlock: Int): ByteArray {
        val engine = XSalsa20Engine()
        engine.init(true, ParametersWithIV(KeyParameter(k), nonce))
        if (startBlock > 0) {
            val skip = ByteArray(startBlock * 64)
            engine.processBytes(skip, 0, skip.size, skip, 0)
        }
        val out = ByteArray(input.size)
        engine.processBytes(input, 0, input.size, out, 0)
        return out
    }

    private fun poly1305(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Poly1305()
        mac.init(KeyParameter(key))
        mac.update(data, 0, data.size)
        return ByteArray(TAG_SIZE).also { mac.doFinal(it, 0) }
    }

    /** libsodium `crypto_core_hsalsa20` — see class KDoc. */
    private fun hsalsa20(key: ByteArray, nonce: ByteArray): ByteArray {
        val x = IntArray(16)
        // Constants: "expand 32-byte k" split little-endian.
        for (i in 0 until 4) x[i] = le32(SIGMA, i * 4)
        for (i in 0 until 8) x[4 + i] = le32(key, i * 4)
        // The 16-byte nonce fills words 12-15, not the constants again.
        for (i in 0 until 4) x[12 + i] = le32(nonce, i * 4)

        for (round in 0 until 10) {
            // Column rounds.
            quarterRound(x, 0, 4, 8, 12)
            quarterRound(x, 5, 9, 13, 1)
            quarterRound(x, 10, 14, 2, 6)
            quarterRound(x, 15, 3, 7, 11)
            // Row rounds.
            quarterRound(x, 0, 1, 2, 3)
            quarterRound(x, 5, 6, 7, 4)
            quarterRound(x, 10, 11, 8, 9)
            quarterRound(x, 15, 12, 13, 14)
        }

        // HSalsa20 output words (no feed-forward), little-endian.
        val out = ByteArray(32)
        val words = intArrayOf(x[0], x[5], x[10], x[15], x[6], x[7], x[8], x[9])
        for (i in words.indices) {
            out[i * 4] = (words[i] and 0xff).toByte()
            out[i * 4 + 1] = ((words[i] ushr 8) and 0xff).toByte()
            out[i * 4 + 2] = ((words[i] ushr 16) and 0xff).toByte()
            out[i * 4 + 3] = ((words[i] ushr 24) and 0xff).toByte()
        }
        return out
    }

    private fun quarterRound(x: IntArray, a: Int, b: Int, c: Int, d: Int) {
        x[b] = x[b] xor rotl(x[a] + x[d], 7)
        x[c] = x[c] xor rotl(x[b] + x[a], 9)
        x[d] = x[d] xor rotl(x[c] + x[b], 13)
        x[a] = x[a] xor rotl(x[d] + x[c], 18)
    }

    private fun rotl(v: Int, n: Int): Int = (v shl n) or (v ushr (32 - n))

    private fun le32(bytes: ByteArray, off: Int): Int =
        (bytes[off].toInt() and 0xff) or
            ((bytes[off + 1].toInt() and 0xff) shl 8) or
            ((bytes[off + 2].toInt() and 0xff) shl 16) or
            ((bytes[off + 3].toInt() and 0xff) shl 24)

    companion object {
        const val VERSION = 1
        private const val NONCE_SIZE = 24
        private const val TAG_SIZE = 16
        private val SIGMA = "expand 32-byte k".toByteArray(Charsets.US_ASCII)
    }
}