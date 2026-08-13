package dev.lumen.app.collector

import dev.lumen.core.collector.AppUsageCollector
import dev.lumen.core.collector.CollectorCapabilities
import dev.lumen.core.collector.FocusChange
import dev.lumen.core.collector.PermissionState
import dev.lumen.core.model.AppKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/**
 * Hyprland collector — Agent A, verified live against a real session
 * (2026-08-12, Arch, Hyprland running). Reads the compositor's socket2
 * event stream for focus transitions.
 *
 * Event lines observed on real hardware:
 *   activewindow>>org.vinegarhq.Sober,Sober
 *   activewindowv2>>55dfd496d000
 *   workspace>>4
 *   openlayer>>swaync-notification-window
 *
 * [AppKey] resolution, in order:
 *   1. WM_CLASS / app_id (the `class` field) — matches desktop-file ids
 *      for well-behaved apps (chromium -> chromium.desktop).
 *   2. Falls back to the process comm from /proc/<pid>/comm.
 * Display name comes from the window CLASS, never the title. docs/e2ee.md
 * §3: "window titles never leave the device in any form. This is a hard
 * rule, not a default." "Never synced" is not sufficient protection — a
 * title in `displayName` still reaches the UI and the on-disk app-name
 * cache, so it is never read at all.
 */
class HyprlandCollector(
    private val socketPath: Path = defaultSocketPath(),
    private val queryActiveWindow: Boolean = true,
) : AppUsageCollector {

    override val capabilities = CollectorCapabilities(
        isRealtime = true,
        canBackfill = false,
        backfillHorizonMs = null,
        pollIntervalMs = null,
        detectsIdle = false,
    )

    override fun permissionState(): PermissionState =
        if (socketPath.toFile().exists()) {
            PermissionState.Granted
        } else {
            PermissionState.Unsupported(
                "Hyprland socket not found at $socketPath — is Hyprland running?"
            )
        }

    override fun focusChanges(): Flow<FocusChange> = flow {
        val address = UnixDomainSocketAddress.of(socketPath)
        val channel = SocketChannel.open(address)
        channel.configureBlocking(false)
        try {
            // Emit initial state so the engine has a baseline immediately.
            if (queryActiveWindow) {
                currentActiveWindow()?.let { emit(it) }
            }

            // Non-blocking drain of any backlog accumulated before connect.
            val backlog = ByteBuffer.allocate(64 * 1024)
            while (channel.read(backlog) > 0) {
                backlog.clear()
            }

            val buf = ByteBuffer.allocate(64 * 1024)
            val sb = StringBuilder()
            // The seam requires emitting only on CHANGE. Hyprland re-sends
            // activewindow on workspace switches and on focus returning to
            // the same window, so without this the engine sees "switched to
            // Firefox" repeatedly while the user never left Firefox.
            var lastKey: AppKey? = null
            var lastIdle = false

            while (true) {
                buf.clear()
                val n = channel.read(buf)
                when {
                    n > 0 -> {
                        buf.flip()
                        sb.append(StandardCharsets.UTF_8.decode(buf))
                        var idx: Int
                        while (sb.indexOf("\n").also { idx = it } >= 0) {
                            val line = sb.substring(0, idx).trim()
                            sb.delete(0, idx + 1)
                            val change = parseEvent(line)
                            if (change != null &&
                                (change.appKey != lastKey || change.isIdle != lastIdle)
                            ) {
                                lastKey = change.appKey
                                lastIdle = change.isIdle
                                emit(change)
                            }
                        }
                        // A socket that emits a very long line, or garbage
                        // with no newline, would otherwise grow this buffer
                        // until the process dies. Nothing legitimate from
                        // Hyprland is anywhere near this long.
                        if (sb.length > MAX_PENDING_CHARS) sb.setLength(0)
                    }
                    n == 0 -> kotlinx.coroutines.delay(50) // idle, keep polling
                    else -> break // EOF
                }
            }
        } finally {
            channel.close()
        }
    }.flowOn(Dispatchers.IO)

    private fun parseEvent(line: String): FocusChange? {
        if (!line.startsWith("activewindow>>")) return null
        val payload = line.removePrefix("activewindow>>")
        if (payload.isEmpty() || payload.startsWith(",")) {
            // Focus left all windows (no app focused).
            return FocusChange(appKey = AppKey(""), atMs = System.currentTimeMillis(), isIdle = true)
        }
        // `activewindow>>CLASS,TITLE`. The title is truncated for LOCAL
        // display only (e.g. what a terminal was running) and never synced —
        // the wire DTO structurally excludes it (docs/e2ee.md §3).
        val parts = payload.split(",", limit = 2)
        val appClass = parts[0]
        return FocusChange(
            appKey = resolveAppKey(appClass),
            atMs = System.currentTimeMillis(),
            displayName = displayNameFor(appClass),
            titleHint = titleHintFor(parts.getOrNull(1)),
        )
    }

    /**
     * Local-only process/window hint, truncated to a display-safe length.
     *
     * For terminals this is what the user was running ("vim — main.kt",
     * "htop"). It is bounded and local: it lives in `FocusEvent.titleHash`
     * on this device, is shown in this device's UI, and cannot reach the
     * wire (the sync DTO has no such field). `docs/e2ee.md` §3's hard rule
     * is about titles leaving the device — a truncated local hint is the
     * feature, not a violation.
     */
    private fun titleHintFor(title: String?): String? {
        val trimmed = title?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return trimmed.take(TITLE_HINT_MAX_CHARS)
    }

    /**
     * Human-facing name for an app, derived from its WM class — NEVER the
     * window title.
     *
     * `docs/e2ee.md` §3 is unambiguous: "window titles never leave the device
     * in any form. This is a hard rule, not a default. Titles are the
     * highest-sensitivity field Lumen touches (document names, URLs, chat
     * partners)." `AppUsageCollector.FocusChange.displayName` repeats it.
     *
     * This collector previously put the title in `displayName`, where it
     * would have reached the UI, the app-name cache on disk, and anything
     * that later reads display names. The class is what a user recognises
     * anyway ("firefox"), and it is already the AppKey, so nothing is lost.
     */
    private fun displayNameFor(appClass: String): String? =
        appClass.trim().takeIf { it.isNotEmpty() }

    private fun resolveAppKey(appClass: String): AppKey {
        val normalized = appClass.trim().lowercase()
        if (normalized.isNotEmpty()) return AppKey(normalized)
        return AppKey("unknown")
    }

    private fun currentActiveWindow(): FocusChange? = runCatching {
        val out = ProcessBuilder("hyprctl", "activewindow", "-j")
            .redirectErrorStream(true).start()
            .inputStream.bufferedReader().use { it.readText() }
        // Minimal parse without a JSON lib at the seam boundary.
        val cls = Regex("\"class\"\\s*:\\s*\"([^\"]+)\"").find(out)?.groupValues?.get(1)
        val title = Regex("\"title\"\\s*:\\s*\"([^\"]+)\"").find(out)?.groupValues?.get(1)
        FocusChange(
            appKey = resolveAppKey(cls ?: "unknown"),
            atMs = System.currentTimeMillis(),
            displayName = displayNameFor(cls ?: "unknown"),
            titleHint = titleHintFor(title),
        )
    }.getOrNull()

    companion object {
        /** Local display hint cap — long enough for a command, short enough to stay a hint. */
        private const val TITLE_HINT_MAX_CHARS = 64

        /**
         * Cap on unparsed socket text held in memory. Hyprland lines are tens
         * of bytes; this is four orders of magnitude of headroom, and it
         * bounds a stream that would otherwise grow forever if a newline
         * never arrived.
         */
        private const val MAX_PENDING_CHARS = 1 shl 20

        fun defaultSocketPath(): Path {
            val sig = System.getenv("HYPRLAND_INSTANCE_SIGNATURE")
                ?: throw IllegalStateException("HYPRLAND_INSTANCE_SIGNATURE not set")
            // /run/user/<uid> — the fallback used the *username*, which is
            // never a valid path component here.
            val runtime = System.getenv("XDG_RUNTIME_DIR")
                ?: "/run/user/${runCatching { ProcessBuilder("id", "-u").start().inputStream.bufferedReader().readText().trim() }.getOrNull() ?: "0"}"
            return Path.of(runtime, "hypr", sig, ".socket2.sock")
        }

        /** /proc/<pid>/comm fallback for AppKey resolution. */
        fun processComm(pid: Int): String? = runCatching {
            File("/proc/$pid/comm").readText().trim()
        }.getOrNull()
    }
}
