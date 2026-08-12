package dev.lumen.macos.permissions

import java.io.File

/**
 * Full Disk Access detection and request flow.
 *
 * ## Why this looks the way it does
 *
 * macOS has **no API to request Full Disk Access.** Screen Recording and
 * Accessibility have request calls that raise a system prompt; FDA does not.
 * The only supported pattern is:
 *
 *   1. attempt a read of a known FDA-gated path,
 *   2. if it fails, explain why the app wants it,
 *   3. deep-link the user into System Settings, where they add the app by hand,
 *   4. re-check, because the grant only takes effect for a **relaunched** process.
 *
 * Step 4 is not optional and is the part most implementations get wrong: TCC
 * decisions are cached per process, so an app granted FDA while running keeps
 * seeing denials until it restarts.
 *
 * ## The honest cost
 *
 * FDA is the broadest grant on macOS. An app holding it can read Mail,
 * Messages, Safari history, and every other user file — far more than Lumen
 * wants. That is why history import is **opt-in and off by default**, and why
 * the UI states the cost plainly instead of nudging.
 *
 * Live tracking needs none of this. FDA buys exactly one thing: history from
 * before Lumen was installed.
 */
object FullDiskAccess {

    /** The macOS Knowledge store — the system database behind Screen Time. */
    val knowledgeDb: File = File(
        System.getProperty("user.home"),
        "Library/Application Support/Knowledge/knowledgeC.db",
    )

    /**
     * True when this process can actually read the Knowledge store.
     *
     * Probes the real target rather than a proxy path. Other FDA-gated
     * locations can be individually granted (or individually denied), so
     * "can read Safari history" does not imply "can read knowledgeC" — a
     * distinction worth respecting rather than assuming.
     */
    fun isGranted(): Boolean = canRead(knowledgeDb)

    /**
     * Distinguishes "denied by TCC" from "genuinely absent".
     *
     * The directory listing succeeding while the file read fails is the
     * signature of a TCC denial; a missing Knowledge directory means the
     * system simply has no usage store to import.
     */
    fun status(): Status = when {
        canRead(knowledgeDb) -> Status.Granted
        knowledgeDb.parentFile.parentFile?.list()?.contains("Knowledge") == true -> Status.Denied
        else -> Status.Unavailable
    }

    private fun canRead(f: File): Boolean = try {
        f.inputStream().use { it.read() }
        true
    } catch (_: Exception) {
        false
    }

    /**
     * Opens System Settings at Privacy & Security → Full Disk Access.
     *
     * Cannot add the app for the user — only they can, and only in that pane.
     * Returns false if the pane could not be opened, so the UI can fall back
     * to showing the path rather than silently appearing to do nothing.
     */
    fun openSettingsPane(): Boolean = try {
        ProcessBuilder(
            "open",
            "x-apple.systempreferences:com.apple.preference.security?Privacy_AllFiles",
        ).start().waitFor() == 0
    } catch (_: Exception) {
        false
    }

    enum class Status {
        /** Readable now. History import is available. */
        Granted,

        /** The store exists but TCC is blocking it. The user can fix this. */
        Denied,

        /** No Knowledge store on this Mac. Nothing to import; not a failure. */
        Unavailable,
    }
}
