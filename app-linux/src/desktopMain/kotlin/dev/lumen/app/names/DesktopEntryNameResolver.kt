package dev.lumen.app.names

import dev.lumen.core.collector.AppNameResolver
import dev.lumen.core.model.AppKey
import java.io.File

/**
 * Resolves Linux app names from freedesktop `.desktop` entries.
 *
 * The collector's [AppKey] is a lowercased WM class / app_id. The mapping to
 * a human name lives in the desktop entries the distribution already ships,
 * so nothing new is collected and no extra permission is needed.
 *
 * Two keys are indexed per entry, because WM class and desktop-file id agree
 * for well-behaved apps and disagree often enough to matter:
 *
 *  - the file's basename (`firefox.desktop` -> `firefox`), and
 *  - `StartupWMClass`, which exists precisely because some apps report a
 *    class that does not match their file name (`code` vs `code-oss`,
 *    `jetbrains-idea` vs `idea`).
 *
 * Read once and cached: on a normal system this is a few hundred small files,
 * and re-reading them per unknown app during a render would be silly.
 */
class DesktopEntryNameResolver(
    private val searchPaths: List<File> = defaultSearchPaths(),
) : AppNameResolver {

    private val index: Map<String, String> by lazy { buildIndex() }

    override fun resolve(appKey: AppKey): String? =
        index[appKey.value.trim().lowercase()]

    private fun buildIndex(): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for (dir in searchPaths) {
            val files = dir.takeIf { it.isDirectory }
                ?.listFiles { f -> f.isFile && f.name.endsWith(".desktop") }
                ?: continue
            for (file in files) {
                val entry = runCatching { parse(file) }.getOrNull() ?: continue
                if (entry.name.isBlank()) continue
                // First write wins: search paths are ordered most-specific
                // first, so a user override in ~/.local beats the system copy.
                out.putIfAbsent(file.name.removeSuffix(".desktop").lowercase(), entry.name)
                entry.wmClass?.lowercase()?.let { out.putIfAbsent(it, entry.name) }
            }
        }
        return out
    }

    private data class Entry(val name: String, val wmClass: String?)

    /**
     * Minimal desktop-entry parse: only `[Desktop Entry]`, only `Name` and
     * `StartupWMClass`.
     *
     * Localised keys (`Name[de]`) are deliberately ignored — picking a locale
     * is a product decision nobody has made, and the unlocalised `Name` is
     * always present. `NoDisplay=true` entries are skipped: they are helpers
     * and MIME handlers the user never launched by name.
     */
    private fun parse(file: File): Entry? {
        var inDesktopEntry = false
        var name: String? = null
        var wmClass: String? = null
        var noDisplay = false

        file.forEachLine { raw ->
            val line = raw.trim()
            when {
                line.startsWith("[") -> inDesktopEntry = line == "[Desktop Entry]"
                !inDesktopEntry || line.isEmpty() || line.startsWith("#") -> Unit
                line.startsWith("Name=") && name == null -> name = line.removePrefix("Name=").trim()
                line.startsWith("StartupWMClass=") -> wmClass = line.removePrefix("StartupWMClass=").trim()
                line.startsWith("NoDisplay=") -> noDisplay = line.removePrefix("NoDisplay=").trim() == "true"
            }
        }
        if (noDisplay) return null
        return name?.let { Entry(it, wmClass?.takeIf { c -> c.isNotBlank() }) }
    }

    companion object {
        /**
         * XDG search order, most specific first: the user's own entries, then
         * `XDG_DATA_DIRS`, then the standard system locations. Flatpak and
         * Snap install into their own share dirs, which is why the defaults
         * are not just `/usr/share`.
         */
        fun defaultSearchPaths(): List<File> {
            val home = System.getProperty("user.home") ?: "/root"
            val dataDirs = (System.getenv("XDG_DATA_DIRS") ?: "")
                .split(':')
                .filter { it.isNotBlank() }
            val candidates = buildList {
                add("$home/.local/share/applications")
                addAll(dataDirs.map { "$it/applications" })
                add("/usr/local/share/applications")
                add("/usr/share/applications")
                add("/var/lib/flatpak/exports/share/applications")
                add("$home/.local/share/flatpak/exports/share/applications")
                add("/var/lib/snapd/desktop/applications")
            }
            return candidates.distinct().map(::File)
        }
    }
}
