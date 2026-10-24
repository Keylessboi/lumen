package dev.lumen.app.keychain

import dev.lumen.core.crypto.KeyPairRef
import dev.lumen.core.crypto.Keychain
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import java.security.SecureRandom
import java.util.Base64

/**
 * Linux keychain — Secret Service via `secret-tool` (libsecret CLI).
 *
 * `docs/e2ee.md` §5.3: desktop at-rest guarantee is the login keyring,
 * which is unlocked for the whole session — weaker than Android's
 * hardware keystore, and honestly stated as such. This is the same
 * guarantee B's MacosKeychain provides, not the Android one.
 *
 * ## Why secret-tool, not a libsecret binding
 *
 * The repo rule (gradle/libs.versions.toml) is no JNA/native binaries
 * in the distribution. `secret-tool` is the standard CLI over the
 * Secret Service D-Bus API; calling it is a subprocess, exactly like
 * the collectors shelling out to hyprctl/xprop. No new dependency.
 *
 * ## Key format
 *
 * Raw 32-byte X25519 keys (RFC 7748), NOT DER — matching §5.4: the
 * libsodium/OMEMO world speaks raw keys, and `devices.public_key_x25519`
 * is a bare BLOB. Stored as `base64(public):base64(private)` under
 * service `dev.lumen.linux`, account `device-key`, generated on first
 * use and kept forever (a device identity must survive reinstalls of
 * the app but not of the OS — which is exactly what a keyring does).
 */
class LinuxKeychain(
    private val secrets: LinuxSecretStore = LinuxSecretStore(),
) : Keychain {

    /**
     * The identity is generated ONLY when the store positively says it has
     * none. Any other failure throws: a new keypair under the same device id
     * is a "key change" every other device refuses, and this device could no
     * longer read its own history. Better one failed sync pass than that.
     */
    @Synchronized
    override fun deviceKeyPair(): KeyPairRef =
        when (val found = secrets.lookup(ACCOUNT)) {
            is LinuxSecretStore.Lookup.Found -> parse(found.value)
                ?: throw IllegalStateException("the stored device key is malformed; refusing to replace it")
            LinuxSecretStore.Lookup.Absent -> generateAndStore()
            is LinuxSecretStore.Lookup.Unreadable -> throw IllegalStateException("device key unavailable: ${found.reason}")
        }

    private fun parse(stored: String): KeyPairRef? {
        val parts = stored.trim().split(":")
        if (parts.size != 2) return null
        return runCatching {
            KeyPairRef(
                publicKey = Base64.getDecoder().decode(parts[0]),
                privateKeyHandle = Base64.getDecoder().decode(parts[1]),
            ).takeIf { it.publicKey.size == 32 && it.privateKeyHandle.size == 32 }
        }.getOrNull()
    }

    private fun generateAndStore(): KeyPairRef {
        val sk = X25519PrivateKeyParameters(SecureRandom())
        val pub = sk.generatePublicKey().encoded
        val priv = sk.encoded
        // LinuxSecretStore verifies the keyring write and falls back to a
        // private file — never "stored" into nothing, which minted a new
        // device identity on every launch wherever secret-tool was missing.
        secrets.put(ACCOUNT, "${Base64.getEncoder().encodeToString(pub)}:${Base64.getEncoder().encodeToString(priv)}")
        return KeyPairRef(publicKey = pub, privateKeyHandle = priv)
    }

    companion object {
        private const val ACCOUNT = "device-key"
    }
}
