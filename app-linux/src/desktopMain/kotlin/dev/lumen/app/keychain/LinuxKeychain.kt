package dev.lumen.app.keychain

import dev.lumen.core.crypto.KeyPairRef
import dev.lumen.core.crypto.Keychain
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import java.io.File
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
class LinuxKeychain : Keychain {

    override fun deviceKeyPair(): KeyPairRef {
        lookup()?.let { return it }
        return generateAndStore()
    }

    private fun lookup(): KeyPairRef? {
        val proc = runCatching {
            ProcessBuilder("secret-tool", "lookup", "service", SERVICE, "account", ACCOUNT)
                .redirectErrorStream(true)
                .start()
        }.getOrNull() ?: return null
        // waitFor FIRST: readText() blocks until EOF, so a hung subprocess
        // would never hit the timeout if read before the wait.
        if (!proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return null
        }
        val out = proc.inputStream.bufferedReader().readText().trim()
        if (proc.exitValue() != 0 || out.isBlank()) return null
        val (pubB64, privB64) = out.split(":").let {
            if (it.size != 2) return null else it[0] to it[1]
        }
        return runCatching {
            KeyPairRef(
                publicKey = Base64.getDecoder().decode(pubB64),
                privateKeyHandle = Base64.getDecoder().decode(privB64),
            )
        }.getOrNull()
    }

    private fun generateAndStore(): KeyPairRef {
        val sk = X25519PrivateKeyParameters(SecureRandom())
        val pk = sk.generatePublicKey()
        val pub = pk.encoded
        val priv = sk.encoded
        val value = "${Base64.getEncoder().encodeToString(pub)}:${Base64.getEncoder().encodeToString(priv)}"

        // `secret-tool store` reads the secret from stdin — passing it via
        // argv would put it in `ps` output, readable by every same-user
        // process (the same reasoning as MacosKeychain's -w stdin note).
        // Attributes are name-value pairs: "service" -> SERVICE, "account"
        // -> ACCOUNT, matching the lookup command.
        val proc = ProcessBuilder(
            "secret-tool", "store", "--label=dev.lumen.linux device key",
            "service", SERVICE, "account", ACCOUNT,
        ).start()
        proc.outputStream.use { it.write(value.toByteArray()) }
        if (!proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
            proc.destroyForcibly()
        }
        return KeyPairRef(publicKey = pub, privateKeyHandle = priv)
    }

    companion object {
        private const val SERVICE = "dev.lumen.linux"
        private const val ACCOUNT = "device-key"
    }
}