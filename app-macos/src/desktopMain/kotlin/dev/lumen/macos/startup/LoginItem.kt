package dev.lumen.macos.startup

import java.io.File

/**
 * "Launch at login", via a per-user LaunchAgent.
 *
 * macOS offers three ways to do this. `SMAppService` (Ventura+) is the modern
 * one but requires a bundled app plus Objective-C to call; the legacy
 * `SMLoginItemSetEnabled` needs a helper target; a **user LaunchAgent** is a
 * plist in `~/Library/LaunchAgents` that `launchd` reads on login. The last
 * one is scriptable from the JVM, needs no native bridge and no admin rights,
 * and the user can see and delete it — which suits a FOSS tracker better than
 * a mechanism they cannot inspect.
 *
 * `RunAtLoad` only. Deliberately **no** `KeepAlive`: an agent that relaunches
 * itself the instant it is killed is malware behaviour, and it would make Quit
 * meaningless. If the user quits Lumen, it stays quit until next login.
 */
object LoginItem {

    const val LABEL = "dev.lumen.macos"

    private val plist: File
        get() = File(
            System.getProperty("user.home"),
            "Library/LaunchAgents/$LABEL.plist",
        )

    fun isEnabled(): Boolean = plist.exists()

    /**
     * Points the agent at [target] — the executable to run at login.
     *
     * Returns false when there is no stable target to point at. Launched from
     * Gradle there is no app bundle, only a transient JVM invocation, and
     * writing a login item for that would produce an agent that fails silently
     * every login. Package the app first (`./gradlew :app-macos:packageDmg`).
     */
    fun enable(target: File? = resolveExecutable()): Boolean {
        if (target == null) return false
        return try {
            plist.parentFile.mkdirs()
            plist.writeText(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
                  "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0">
                <dict>
                  <key>Label</key>
                  <string>$LABEL</string>
                  <key>ProgramArguments</key>
                  <array>
                    <string>${target.absolutePath}</string>
                  </array>
                  <key>RunAtLoad</key>
                  <true/>
                  <key>ProcessType</key>
                  <string>Background</string>
                </dict>
                </plist>
                """.trimIndent(),
            )
            // Registering immediately means the setting takes effect without a
            // logout. bootstrap fails harmlessly if the label is already loaded.
            runCatching {
                ProcessBuilder("launchctl", "bootstrap", "gui/${uid()}", plist.absolutePath)
                    .start().waitFor()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun disable(): Boolean = try {
        runCatching {
            ProcessBuilder("launchctl", "bootout", "gui/${uid()}/$LABEL").start().waitFor()
        }
        plist.delete() || !plist.exists()
    } catch (_: Exception) {
        false
    }

    /**
     * Whether a login item can be created at all in this launch mode.
     * False under `gradle run`, true from an installed `.app`.
     */
    fun isSupported(): Boolean = resolveExecutable() != null

    /**
     * Locates the packaged launcher inside the running `.app` bundle.
     *
     * Compose Desktop's packaged layout is
     * `Lumen.app/Contents/MacOS/Lumen`, and `jpackage` sets `jpackage.app-path`
     * to it. Falling back to walking up from `java.home` covers the case where
     * that property is absent.
     */
    private fun resolveExecutable(): File? {
        System.getProperty("jpackage.app-path")?.let { p ->
            val f = File(p)
            if (f.exists()) return f
        }
        val javaHome = File(System.getProperty("java.home"))
        // .../Lumen.app/Contents/runtime/Contents/Home -> .../Lumen.app
        val bundle = generateSequence(javaHome) { it.parentFile }
            .firstOrNull { it.name.endsWith(".app") } ?: return null
        val macOsDir = File(bundle, "Contents/MacOS")
        return macOsDir.listFiles()?.firstOrNull { it.canExecute() }
    }

    private fun uid(): String = ProcessBuilder("id", "-u")
        .start().inputStream.bufferedReader().readText().trim()
}
