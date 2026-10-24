package dev.lumen.app.keychain

import dev.lumen.core.crypto.SecretStore
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties
import java.util.concurrent.TimeUnit

/**
 * Named secrets in the Secret Service via `secret-tool` (libsecret's CLI),
 * with a private-file fallback.
 *
 * ## Why a fallback
 *
 * `secret-tool` is missing on many minimal and tiling-WM installs (no
 * libsecret, or no keyring daemon running), which is exactly the Hyprland
 * and Sway audience. The previous keychain ignored the store's exit code, so
 * there it "saved" the device key into nothing and minted a new identity on
 * every launch — every other device then saw a key change.
 *
 * The fallback is a `0600` file in the user's data directory. That is a
 * weaker at-rest guarantee than an unlocked-for-the-session keyring only in
 * that other tools can copy it; it is stated, not hidden ([usingFallback]).
 */
class LinuxSecretStore(
    private val dataDir: File = File(System.getProperty("user.home"), ".local/share/lumen"),
    private val useSecretService: Boolean = true,
) : SecretStore {

    private val fallbackFile = File(dataDir, "secrets.properties")

    /** True once a secret had to go to the file instead of the keyring. */
    var usingFallback: Boolean = false
        private set

    /** What a lookup found. [Unreadable] is NOT "absent" — callers must not treat it as one. */
    sealed interface Lookup {
        data class Found(val value: String) : Lookup
        data object Absent : Lookup
        data class Unreadable(val reason: String) : Lookup
    }

    override fun get(name: String): String? = (lookup(name) as? Lookup.Found)?.value

    /**
     * Distinguishes "no such secret" from "the keyring did not answer" (locked
     * at login, D-Bus hiccup, timeout). Conflating them is how a locked keyring
     * at boot turned into a brand-new device identity written over the old one.
     */
    @Synchronized
    fun lookup(name: String): Lookup {
        if (useSecretService) {
            val result = runTool(listOf("secret-tool", "lookup", "service", SERVICE, "account", name), null)
            when {
                result == null && toolMissing() -> Unit // no libsecret: the file is the store
                result == null -> return Lookup.Unreadable("the keyring did not answer in time")
                result.first == 0 && result.second.isNotEmpty() -> return Lookup.Found(result.second.trimEnd('\n'))
                // secret-tool exits 1 with no output when the item does not exist.
                result.first == 1 && result.second.isBlank() -> Unit
                else -> return Lookup.Unreadable("secret-tool failed: ${result.second.trim().take(200)}")
            }
        }
        val fromFile = runCatching { readFallback().getProperty(name) }
            .getOrElse { return Lookup.Unreadable("could not read ${fallbackFile.path}: ${it.message}") }
        return if (fromFile != null) {
            usingFallback = true
            Lookup.Found(fromFile)
        } else {
            Lookup.Absent
        }
    }

    private fun toolMissing(): Boolean =
        runCatching { ProcessBuilder("secret-tool", "--version").start().waitFor(); false }.getOrDefault(true)

    @Synchronized
    override fun put(name: String, value: String) {
        if (useSecretService && secretToolStore(name, value)) {
            removeFallback(name)
            return
        }
        usingFallback = true
        val props = readFallback()
        props.setProperty(name, value)
        writeFallback(props)
    }

    @Synchronized
    override fun remove(name: String) {
        if (useSecretService) runTool(listOf("secret-tool", "clear", "service", SERVICE, "account", name), null)
        removeFallback(name)
    }

    private fun secretToolStore(name: String, value: String): Boolean {
        // The secret goes over stdin: argv is visible to every same-user
        // process through `ps`.
        val result = runTool(
            listOf("secret-tool", "store", "--label=Lumen: $name", "service", SERVICE, "account", name),
            value,
        ) ?: return false
        return result.first == 0 && (lookup(name) as? Lookup.Found)?.value == value
    }

    private fun runTool(command: List<String>, stdin: String?): Pair<Int, String>? = runCatching {
        val proc = ProcessBuilder(command).redirectErrorStream(true).start()
        proc.outputStream.use { out -> stdin?.let { out.write(it.toByteArray()) } }
        // waitFor first: readText() blocks to EOF and would never time out.
        if (!proc.waitFor(5, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return null
        }
        proc.exitValue() to proc.inputStream.bufferedReader().readText()
    }.getOrNull()

    private fun readFallback(): Properties = Properties().apply {
        if (fallbackFile.exists()) fallbackFile.inputStream().use { load(it) }
    }

    private fun removeFallback(name: String) {
        if (!fallbackFile.exists()) return
        val props = readFallback()
        if (props.remove(name) != null) writeFallback(props)
    }

    /**
     * Written 0600 inside a 0700 directory, or not at all: there is no
     * fallback to default permissions, which would leave the secrets
     * world-readable under a typical 022 umask.
     */
    private fun writeFallback(props: Properties) {
        val ownerOnlyDir = PosixFilePermissions.fromString("rwx------")
        val ownerOnlyFile = PosixFilePermissions.fromString("rw-------")
        if (!dataDir.exists()) {
            Files.createDirectories(dataDir.toPath(), PosixFilePermissions.asFileAttribute(ownerOnlyDir))
        }
        Files.setPosixFilePermissions(dataDir.toPath(), ownerOnlyDir)
        val tmp = File(dataDir, "secrets.properties.tmp")
        Files.deleteIfExists(tmp.toPath())
        Files.createFile(tmp.toPath(), PosixFilePermissions.asFileAttribute(ownerOnlyFile))
        try {
            tmp.outputStream().use { props.store(it, "Lumen secrets (keyring unavailable). Keep private.") }
            Files.move(
                tmp.toPath(), fallbackFile.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        } finally {
            Files.deleteIfExists(tmp.toPath())
        }
        Files.setPosixFilePermissions(fallbackFile.toPath(), ownerOnlyFile)
        check(Files.getPosixFilePermissions(fallbackFile.toPath()) == ownerOnlyFile) {
            "could not restrict ${fallbackFile.path} to its owner"
        }
    }

    companion object {
        const val SERVICE = "dev.lumen.linux"
    }
}
