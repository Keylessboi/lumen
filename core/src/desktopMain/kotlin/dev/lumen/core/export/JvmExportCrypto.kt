package dev.lumen.core.export

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Argon2id + AES-256-GCM for the M5 export, on the JVM and Android.
 *
 * One implementation for both, deliberately: an export written on a laptop
 * must open on a phone, and a platform-specific KDF or cipher is a
 * compatibility bug waiting for its first user. Bouncy Castle supplies
 * Argon2id on both; AES-GCM comes from the platform JCE, which on Android is
 * hardware-accelerated.
 *
 * ## Why AES-GCM rather than the libsodium secretbox used for sync
 *
 * The sync envelope is XSalsa20-Poly1305 via libsodium (`docs/e2ee.md` §6),
 * but that arrives through a JNI binding with a native library per
 * architecture. The export must open on any device the user owns, including
 * one where Lumen was just installed to perform a restore — so it should
 * depend on as little native surface as possible. AES-256-GCM is in the
 * platform on every target, is an AEAD with the same guarantees that matter
 * here, and has no packaging story.
 */
class JvmExportCrypto(
    private val random: SecureRandom = SecureRandom(),
) : ExportCrypto {

    /** GCM's standard nonce width. 96 bits is the size GCM is specified for. */
    override val nonceBytes: Int = 12

    override fun deriveKey(passphrase: CharArray, params: KdfParams): ByteArray {
        require(params.algorithm == KdfParams.ARGON2ID) {
            "unsupported KDF: ${params.algorithm}"
        }
        val generator = Argon2BytesGenerator()
        generator.init(
            Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                // Pin the version. Argon2 v1.0 and v1.3 derive different keys
                // from the same inputs, so a reader that let the library pick
                // its default could fail to open a file it wrote under an
                // older release.
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(params.memoryKib)
                .withIterations(params.iterations)
                .withParallelism(params.parallelism)
                .withSalt(params.salt)
                .build(),
        )
        val key = ByteArray(params.keyLengthBytes)
        // Takes the passphrase as chars and encodes internally, so the
        // caller never has to build an immutable String it cannot clear.
        generator.generateBytes(passphrase, key)
        return key
    }

    override fun seal(plaintext: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        return cipher.doFinal(plaintext)
    }

    override fun open(ciphertext: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray? = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.doFinal(ciphertext)
    } catch (_: AEADBadTagException) {
        // The expected failure: wrong passphrase or a modified file. The two
        // are indistinguishable here by design — see ExportError.
        null
    } catch (_: Exception) {
        // A malformed nonce or truncated ciphertext lands here. Still "cannot
        // open this file", and still not worth telling an attacker which.
        null
    }

    override fun randomBytes(count: Int): ByteArray = ByteArray(count).also { random.nextBytes(it) }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
