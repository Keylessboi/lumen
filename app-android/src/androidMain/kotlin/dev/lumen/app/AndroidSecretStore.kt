package dev.lumen.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.lumen.core.crypto.SecretStore
import java.io.File
import java.security.KeyStore
import java.util.Base64
import java.util.Properties
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Named secrets (sync password, scrobbler tokens) encrypted under a
 * non-exportable AES-256-GCM key in the Android Keystore, stored in
 * app-private files. Same at-rest model as the device key
 * ([dev.lumen.core.crypto.AndroidKeychain], docs/e2ee.md §5.2): defeated by
 * a running, unlocked, compromised process; not by a copy of the files.
 */
class AndroidSecretStore(context: Context) : SecretStore {

    private val file = File(context.filesDir, "secrets.properties")
    private val key: SecretKey = loadOrCreateKey()

    @Synchronized
    override fun get(name: String): String? = runCatching {
        val blob = read().getProperty(name) ?: return null
        val bytes = Base64.getDecoder().decode(blob)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, 0, IV_SIZE))
        String(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE), Charsets.UTF_8)
    }.getOrNull()

    @Synchronized
    override fun put(name: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORM)
        // Keystore keys generate their own IV; supplying one throws.
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val blob = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val props = read()
        props.setProperty(name, Base64.getEncoder().encodeToString(blob))
        write(props)
    }

    @Synchronized
    override fun remove(name: String) {
        val props = read()
        if (props.remove(name) != null) write(props)
    }

    private fun read() = Properties().apply { if (file.exists()) file.inputStream().use { load(it) } }

    private fun write(props: Properties) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.outputStream().use { props.store(it, null) }
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private fun loadOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ALIAS = "lumen_secrets"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}
