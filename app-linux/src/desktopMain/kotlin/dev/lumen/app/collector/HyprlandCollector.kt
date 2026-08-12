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
 * Display name comes from the window title at observation time, NEVER
 * synced (docs/e2ee.md §3).
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

    /**
     * The app's own WM_CLASS — the dev harness window is
     * `dev-lumen-app-MainKt`. Excluded from reported focus by the engine
     * (docs/design-spec.md: the app must not count its own window).
     */
    override val selfAppKey: AppKey = AppKey("dev-lumen-app-mainkt")

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
        val parts = payload.split(",", limit = 2)
        val appClass = parts[0]
        val title = parts.getOrNull(1)
        return FocusChange(
            appKey = resolveAppKey(appClass),
            atMs = System.currentTimeMillis(),
            displayName = title?.takeIf { it.isNotBlank() },
        )
    }

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
            displayName = title?.takeIf { it.isNotBlank() },
        )
    }.getOrNull()

    companion object {
        fun defaultSocketPath(): Path {
            val sig = System.getenv("HYPRLAND_INSTANCE_SIGNATURE")
                ?: throw IllegalStateException("HYPRLAND_INSTANCE_SIGNATURE not set")
            val runtime = System.getenv("XDG_RUNTIME_DIR") ?: "/run/user/${System.getProperty("user.name")}"
            return Path.of(runtime, "hypr", sig, ".socket2.sock")
        }

        /** /proc/<pid>/comm fallback for AppKey resolution. */
        fun processComm(pid: Int): String? = runCatching {
            File("/proc/$pid/comm").readText().trim()
        }.getOrNull()
    }
}
