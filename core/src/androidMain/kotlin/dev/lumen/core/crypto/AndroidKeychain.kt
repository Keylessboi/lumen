package dev.lumen.core.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android keychain — hardware-wrapped at rest (docs/e2ee.md §5.2).
 *
 * The Android Keystore cannot hold an X25519 key (verified: no XDH at
 * API 35, only 3DES/AES/EC/HMAC/RSA). So the v1 approach is:
 *
 *  1. A non-exportable AES-256-GCM key lives in the Android Keystore.
 *  2. The X25519 keypair is generated in software (BouncyCastle, same
 *     as LinuxKeychain) and the PRIVATE key is encrypted under that
 *     Keystore AES key, stored in app-private storage.
 *  3. The private key materializes in process memory only during a
 *     crypto_box operation — which is the honest limit: A4/A6 (powered
 *     off) are defeated, A5 (unlocked device) and live-process-memory
 *     attackers are not, and none is claimed.
 *
 * Key format matches §5.4/§5.3: raw 32-byte X25519 keys (RFC 7748), not
 * DER — `devices.public_key_x25519` is a bare BLOB and the
 * libsodium/OMEMO world speaks raw keys. Stored file layout:
 * `version(1) | iv(12) | ciphertext` where ciphertext is
 * AES-GCM(public32 || private32).
 */
class AndroidKeychain(
    private val context: Context,
    keystoreAlias: String = DEFAULT_KEYSTORE_ALIAS,
) : Keychain {

    private val keystoreFile = File(context.filesDir, "device-key.bin")
    private val aesKey: SecretKey = loadOrCreateAesKey(keystoreAlias)

    override fun deviceKeyPair(): KeyPairRef {
        keystoreFile.takeIf { it.exists() }?.let { read()?.let { return it } }
        return generateAndStore()
    }

    /** Load and decrypt the stored keypair, or null when absent/corrupt. */
    private fun read(): KeyPairRef? = runCatching {
        val bytes = keystoreFile.readBytes()
        if (bytes.size < IV_SIZE + 1) return null
        if (bytes[0] != FORMAT_VERSION) return null
        val iv = bytes.copyOfRange(1, 1 + IV_SIZE)
        val ciphertext = bytes.copyOfRange(1 + IV_SIZE, bytes.size)

        // Decryption alone takes a caller-supplied IV — the Keystore GCM
        // rule is one-way: encrypt generates, decrypt consumes.
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        val plain = cipher.doFinal(ciphertext)
        if (plain.size != 64) return null

        KeyPairRef(
            publicKey = plain.copyOfRange(0, 32),
            privateKeyHandle = plain.copyOfRange(32, 64),
        )
    }.getOrNull()

    /** Generate a fresh X25519 pair, wrap it under the Keystore AES key, persist. */
    private fun generateAndStore(): KeyPairRef {
        val sk = X25519PrivateKeyParameters(SecureRandom())
        val pk = sk.generatePublicKey()
        val pub = pk.encoded
        val priv = sk.encoded

        // ENCRYPT mode with a Keystore key must NOT pass an IV: the Keystore
        // generates one and returns it from cipher.iv after init(). Passing
        // our own throws InvalidAlgorithmParameterException.
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, aesKey)
        val ciphertext = cipher.doFinal(pub + priv)
        val iv = cipher.iv

        keystoreFile.parentFile?.mkdirs()
        keystoreFile.writeBytes(byteArrayOf(FORMAT_VERSION) + iv + ciphertext)

        return KeyPairRef(publicKey = pub, privateKeyHandle = priv)
    }

    private fun loadOrCreateAesKey(alias: String): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            // No user-authentication gate: the screen lock is the phone's
            // whole at-rest story, and locking crypto behind a fresh auth
            // prompt every background sync would break the product. A4/A6
            // (powered off) is the guarantee; A5 (unlocked device) is not.
            .setUserAuthenticationRequired(false)
            .build()
        // init() is mandatory before generateKey() — without it the Keystore
        // throws IllegalStateException("Not initialized") at generation time.
        generator.init(spec)
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_KEYSTORE_ALIAS = "lumen_device_key"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val GCM_TAG_BITS = 128
        const val FORMAT_VERSION: Byte = 1
    }
}
