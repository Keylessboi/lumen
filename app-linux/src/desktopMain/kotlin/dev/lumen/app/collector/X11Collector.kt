package dev.lumen.app.collector

import dev.lumen.core.collector.AppUsageCollector
import dev.lumen.core.collector.CollectorCapabilities
import dev.lumen.core.collector.FocusChange
import dev.lumen.core.collector.PermissionState
import dev.lumen.core.model.AppKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * X11 collector — Agent A, M2. Polls the EWMH `_NET_ACTIVE_WINDOW` property
 * on the root window via `xprop`, then resolves `WM_CLASS` on the active
 * window. This is the fallback for X11-only sessions (no Wayland compositor
 * IPC) and for XWayland windows under compositors that don't expose them
 * through their native IPC.
 *
 * `xprop` is used rather than a JNI/Xlib binding to keep the build
 * dependency-free and match the `hyprctl` shelling pattern in
 * [HyprlandCollector]. The poll cadence is 2 seconds: EWMH has no push
 * mechanism, so polling is the only option, and 2s is fast enough for
 * minute-bucket rollups without burning CPU.
 *
 * [AppKey] resolution: `WM_CLASS(STRING) = "instance", "class"` — the
 * second value (class) is used, lowercased. It matches desktop-file ids
 * for well-behaved apps. The window title (`_NET_WM_NAME`) is never read —
 * `docs/e2ee.md` §3: window titles never leave the device in any form.
 */
class X11Collector(
    private val pollIntervalMs: Long = 2_000,
    private val queryActiveWindow: Boolean = true,
) : AppUsageCollector {

    override val capabilities = CollectorCapabilities(
        isRealtime = false,
        canBackfill = false,
        backfillHorizonMs = null,
        pollIntervalMs = pollIntervalMs,
        detectsIdle = false,
    )

    override fun permissionState(): PermissionState =
        if (isXpropAvailable()) {
            PermissionState.Granted
        } else {
            PermissionState.Unsupported(
                "xprop not found on PATH — install xorg-xprop for X11 tracking"
            )
        }

    override fun focusChanges(): Flow<FocusChange> = flow {
        var lastKey: AppKey? = null
        var lastIdle = false

        if (queryActiveWindow) {
            currentActiveWindow()?.let { change ->
                lastKey = change.appKey
                lastIdle = change.isIdle
                emit(change)
            }
        }

        while (true) {
            delay(pollIntervalMs)
            val change = currentActiveWindow() ?: continue
            if (change.appKey != lastKey || change.isIdle != lastIdle) {
                lastKey = change.appKey
                lastIdle = change.isIdle
                emit(change)
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun currentActiveWindow(): FocusChange? = runCatching {
        val windowId = queryRootProperty("_NET_ACTIVE_WINDOW") ?: return null
        if (windowId == "0x0") {
            return FocusChange(
                appKey = AppKey(""),
                atMs = System.currentTimeMillis(),
                isIdle = true,
            )
        }
        val wmClass = queryWindowProperty(windowId, "WM_CLASS") ?: return null
        val appClass = parseWmClass(wmClass) ?: return null
        FocusChange(
            appKey = resolveAppKey(appClass),
            atMs = System.currentTimeMillis(),
            displayName = appClass,
            titleHint = titleHintFor(queryWindowProperty(windowId, "_NET_WM_NAME")),
        )
    }.getOrNull()

    private fun titleHintFor(raw: String?): String? =
        raw?.trim()?.takeIf { it.isNotEmpty() }?.take(TITLE_HINT_MAX_CHARS)

    private companion object {
        private const val TITLE_HINT_MAX_CHARS = 64
    }

    internal fun parseWmClass(raw: String): String? {
        // WM_CLASS(STRING) = "instance", "class"
        val match = Regex("\"([^\"]*)\"\\s*,\\s*\"([^\"]*)\"").find(raw)
            ?: return null
        return match.groupValues[2].takeIf { it.isNotBlank() }
    }

    private fun queryRootProperty(property: String): String? {
        val out = runXprop("-root", property) ?: return null
        return extractValue(out, property)
    }

    private fun queryWindowProperty(windowId: String, property: String): String? {
        val out = runXprop("-id", windowId, property) ?: return null
        return out.lines().firstOrNull { it.contains(property) }
    }

    private fun runXprop(vararg args: String): String? = runCatching {
        val proc = ProcessBuilder("xprop", *args)
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().readText()
        if (!proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return null
        }
        if (proc.exitValue() != 0) return null
        out
    }.getOrNull()

    private fun extractValue(output: String, property: String): String? {
        val line = output.lines().firstOrNull { it.contains(property) } ?: return null
        // _NET_ACTIVE_WINDOW(WINDOW): window id # 0xa00007
        val match = Regex("#\\s*(0x[0-9a-fA-F]+)").find(line)
            ?: Regex("=\\s*(0x[0-9a-fA-F]+)").find(line)
            ?: return null
        return match.groupValues[1]
    }

    private fun resolveAppKey(appClass: String): AppKey {
        val normalized = appClass.trim().lowercase()
        if (normalized.isNotEmpty()) return AppKey(normalized)
        return AppKey("unknown")
    }

    private fun isXpropAvailable(): Boolean = runCatching {
        val proc = ProcessBuilder("which", "xprop").redirectErrorStream(true).start()
        proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
        proc.inputStream.bufferedReader().readText().isNotBlank()
    }.getOrDefault(false)
}