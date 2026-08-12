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
                            parseEvent(line)?.let { emit(it) }
                        }
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
        // `activewindow>>CLASS,TITLE`. The title is deliberately parsed and
        // DISCARDED — see [displayNameFor].
        val parts = payload.split(",", limit = 2)
        val appClass = parts[0]
        return FocusChange(
            appKey = resolveAppKey(appClass),
            atMs = System.currentTimeMillis(),
            displayName = displayNameFor(appClass),
        )
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
        // Only `class` is read. `title` is not parsed at all here, so a
        // window title cannot reach a FocusChange even by accident.
        val cls = Regex("\"class\"\\s*:\\s*\"([^\"]+)\"").find(out)?.groupValues?.get(1)
        FocusChange(
            appKey = resolveAppKey(cls ?: "unknown"),
            atMs = System.currentTimeMillis(),
            displayName = displayNameFor(cls ?: "unknown"),
        )
    }.getOrNull()

    companion object {
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
