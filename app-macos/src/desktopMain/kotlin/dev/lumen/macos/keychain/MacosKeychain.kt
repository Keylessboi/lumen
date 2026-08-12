package dev.lumen.macos.keychain

import dev.lumen.core.crypto.KeyPairRef
import dev.lumen.core.crypto.Keychain
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.interfaces.XECPrivateKey
import java.security.interfaces.XECPublicKey
import java.security.spec.NamedParameterSpec
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * The device's X25519 sync identity, kept in the macOS login keychain.
 *
 * Until now `deviceKeys` exported empty — honest, and incomplete: a restore
 * could bring back a month of history but not the identity that history was
 * recorded under, so the restored Mac would join a sync as a stranger.
 *
 * ## Why the `security` command and not the framework
 *
 * Reaching Keychain Services directly needs AppKit/Security through a JNI
 * bridge, and `app-macos` does not have one (see `BackupFiles` for the same
 * constraint on the save panel). `/usr/bin/security` is the same keychain,
 * reachable from a plain JVM.
 *
 * ## The private key never appears in a command line
 *
 * `security add-generic-password -w <secret>` is the obvious call and it is
 * wrong: arguments are visible to `ps` for every process of the same user,
 * so the one value in this program that must not leak would be published to
 * the process table each time it was written. Apple's own usage text says as
 * much — *"Use of the -p or -w options is insecure. Specify -w as the last
 * option to be prompted."*
 *
 * So `-w` is passed last, with no value, and the secret is written to the
 * process's stdin — twice, because the prompt asks for confirmation. Nothing
 * sensitive reaches `argv`.
 *
 * ## What this does and does not buy
 *
 * `docs/e2ee.md` §5.3 is blunt that the desktop at-rest guarantee is weaker
 * than Android's, and macOS is no exception: the login keychain is unlocked
 * for the whole session, so this defends a stolen powered-off Mac (A4) and
 * not an attacker at an unlocked one (A5). Recorded in §5.4 rather than
 * implied.
 */
class MacosKeychain(
    private val secrets: SecretStore = SecurityCommandSecretStore(),
) : Keychain {

    /**
     * Create the device keypair, or load the one already stored.
     *
     * Stable by construction: a second call returns the same key, because a
     * sync identity that quietly regenerated would orphan every record ever
     * written under the old one.
     */
    override fun deviceKeyPair(): KeyPairRef {
        secrets.read(ACCOUNT)?.let { return decode(it) }
        val generated = generate()
        secrets.write(ACCOUNT, encode(generated))
        return generated
    }

    /**
     * The stored keypair, or null when there is none and none can be made.
     *
     * The export path uses this: a missing keychain must leave `deviceKeys`
     * empty and say so, never fail the backup of a month of history over an
     * identity the user has not started using yet.
     */
    fun deviceKeyPairOrNull(): KeyPairRef? = runCatching { deviceKeyPair() }.getOrNull()

    private fun generate(): KeyPairRef {
        val generator = KeyPairGenerator.getInstance("XDH")
        generator.initialize(NamedParameterSpec.X25519)
        val pair = generator.generateKeyPair()
        val private = (pair.private as XECPrivateKey).scalar.orElseThrow {
            IllegalStateException("the JDK would not hand back the X25519 private scalar")
        }
        return KeyPairRef(
            publicKey = uToLittleEndian((pair.public as XECPublicKey).u),
            privateKeyHandle = private,
        )
    }

    /**
     * Raw 32-byte little-endian, per RFC 7748 — not the JDK's X.509/PKCS#8
     * wrappers.
     *
     * `devices.public_key_x25519` is a bare BLOB and the OMEMO/libsodium
     * world this has to interoperate with speaks raw keys. Storing a DER
     * encoding here would work perfectly until the first byte crossed a wire.
     */
    private fun uToLittleEndian(u: BigInteger): ByteArray {
        val bigEndian = u.toByteArray()
        val out = ByteArray(KEY_BYTES)
        // toByteArray() is big-endian, two's complement: it may carry a
        // leading zero byte, or be shorter than 32 for a small u.
        var read = bigEndian.size - 1
        var write = 0
        while (read >= 0 && write < KEY_BYTES) {
            out[write] = bigEndian[read]
            read--
            write++
        }
        return out
    }

    private fun encode(pair: KeyPairRef): String =
        base64.encodeToString(pair.publicKey) + SEPARATOR + base64.encodeToString(pair.privateKeyHandle)

    /**
     * Read the stored form back, refusing anything that is not exactly it.
     *
     * A damaged entry is NOT quietly replaced with a fresh identity: that
     * turns "something is wrong with your key" into "you are now a different
     * device", silently, and every record written under the old key is
     * orphaned. Better to fail loudly and leave the entry for a human.
     */
    private fun decode(stored: String): KeyPairRef {
        val parts = stored.trim().split(SEPARATOR)
        require(parts.size == 2) { "keychain entry '$ACCOUNT' is not a Lumen device key" }
        val publicKey = runCatching { base64Decoder.decode(parts[0]) }.getOrNull()
        val privateKey = runCatching { base64Decoder.decode(parts[1]) }.getOrNull()
        require(publicKey != null && privateKey != null) {
            "keychain entry '$ACCOUNT' is not readable as a Lumen device key"
        }
        require(publicKey.size == KEY_BYTES && privateKey.size == KEY_BYTES) {
            "keychain entry '$ACCOUNT' holds ${publicKey.size}/${privateKey.size} byte keys, expected $KEY_BYTES"
        }
        return KeyPairRef(publicKey = publicKey, privateKeyHandle = privateKey)
    }

    /** Where a secret is kept. Injected so tests never touch a real keychain. */
    interface SecretStore {
        fun read(account: String): String?
        fun write(account: String, value: String)
    }

    /** The login keychain, through `/usr/bin/security`. */
    class SecurityCommandSecretStore(
        private val service: String = SERVICE,
    ) : SecretStore {

        override fun read(account: String): String? {
            val process = ProcessBuilder(
                "/usr/bin/security", "find-generic-password",
                "-a", account, "-s", service, "-w",
            ).start()
            val out = process.inputStream.bufferedReader().readText().trim()
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }
            // Exit 44 is "item not found", which is the ordinary first-run
            // answer rather than a failure.
            return if (process.exitValue() == 0 && out.isNotEmpty()) out else null
        }

        override fun write(account: String, value: String) {
            // -w LAST and with no value, so the secret goes over stdin
            // instead of into the process table. The prompt asks twice.
            val process = ProcessBuilder(
                "/usr/bin/security", "add-generic-password",
                "-a", account, "-s", service,
                "-D", "Lumen device key",
                "-U", "-w",
            ).redirectErrorStream(true).start()
            process.outputStream.bufferedWriter().use { it.write("$value\n$value\n") }
            val out = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                error("the keychain did not answer within ${TIMEOUT_SECONDS}s")
            }
            check(process.exitValue() == 0) {
                "the keychain refused to store the device key: ${out.trim()}"
            }
        }

        private companion object {
            const val TIMEOUT_SECONDS = 10L
        }
    }

    companion object {
        /** Matches the packaged app's bundle id, so the item is identifiable. */
        const val SERVICE: String = "dev.lumen.macos"
        const val ACCOUNT: String = "device-key"

        private const val KEY_BYTES = 32
        private const val SEPARATOR = ":"

        private val base64: Base64.Encoder = Base64.getEncoder()
        private val base64Decoder: Base64.Decoder = Base64.getDecoder()
    }
}
