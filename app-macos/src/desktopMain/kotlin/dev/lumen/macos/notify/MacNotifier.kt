package dev.lumen.macos.notify

import dev.lumen.core.nudge.BreakNudge

/**
 * Delivers the break reminder as a macOS notification.
 *
 * Uses `osascript` rather than a native bridge: `UNUserNotificationCenter`
 * needs a signed, bundled app with an entitlement, and the dev build is an
 * unpackaged JVM. AppleScript's `display notification` works in both, needs
 * no permission prompt of its own, and this is one notification a few times a
 * day — not a hot path worth a native dependency for.
 *
 * Every failure is swallowed to a boolean. A nudge that cannot be delivered
 * is a nudge that did not happen; it is not worth interrupting tracking over,
 * and it is certainly not worth a crash.
 */
class MacNotifier(
    private val runner: (List<String>) -> Boolean = ::runOsascript,
) {

    fun notify(nudge: BreakNudge): Boolean =
        notify(title = nudge.title(), body = nudge.body())

    fun notify(title: String, body: String): Boolean {
        // AppleScript string literals: a stray quote or backslash would break
        // the script, and the title is built from our own copy today but the
        // body could carry a user-set app name tomorrow.
        val script = """display notification "${escape(body)}" with title "${escape(title)}""""
        return runner(listOf("/usr/bin/osascript", "-e", script))
    }

    private fun escape(text: String): String =
        text.replace("\\", "\\\\").replace("\"", "\\\"")

    companion object {
        private fun runOsascript(command: List<String>): Boolean = runCatching {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            if (!process.waitFor(TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly()
                false
            } else {
                process.exitValue() == 0
            }
        }.getOrDefault(false)

        private const val TIMEOUT_SECONDS = 5L
    }
}
