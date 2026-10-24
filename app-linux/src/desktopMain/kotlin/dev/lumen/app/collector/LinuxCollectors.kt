package dev.lumen.app.collector

import dev.lumen.core.collector.AppUsageCollector
import dev.lumen.core.collector.CollectorCapabilities
import dev.lumen.core.collector.FocusChange
import dev.lumen.core.collector.PermissionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Pick the collector for the session Lumen is running in.
 *
 * Both entry points constructed [HyprlandCollector] unconditionally, and its
 * default socket path throws when `HYPRLAND_INSTANCE_SIGNATURE` is unset — so
 * on Sway, on X11 and on every other desktop Lumen crashed on launch, and the
 * Sway and X11 collectors that already existed were never used.
 *
 * Order: Hyprland, then Sway (both have exact IPC), then X11 (polling; also
 * covers XWayland-only sessions that export DISPLAY). Anything else gets a
 * collector that says plainly why nothing is tracked.
 */
object LinuxCollectors {

    fun detect(env: Map<String, String> = System.getenv()): AppUsageCollector {
        if (env["HYPRLAND_INSTANCE_SIGNATURE"] != null) {
            runCatching { HyprlandCollector() }.getOrNull()?.takeIf { it.permissionState() is PermissionState.Granted }
                ?.let { return it }
        }
        if (env["SWAYSOCK"] != null) {
            runCatching { SwayCollector() }.getOrNull()?.takeIf { it.permissionState() is PermissionState.Granted }
                ?.let { return it }
        }
        if (env["DISPLAY"] != null) return X11Collector()
        val desktop = env["XDG_CURRENT_DESKTOP"] ?: env["XDG_SESSION_DESKTOP"]
        return Unavailable(
            if (desktop != null) {
                "Lumen can track Hyprland, Sway and X11 sessions. This session ($desktop on Wayland) " +
                    "does not offer a way to see the focused app yet."
            } else {
                "No supported desktop session found (Hyprland, Sway or X11)."
            },
        )
    }

    /** Honest nothing: the reason reaches the UI instead of an empty day. */
    class Unavailable(private val reason: String) : AppUsageCollector {
        override val capabilities = CollectorCapabilities(
            isRealtime = false,
            canBackfill = false,
            backfillHorizonMs = null,
            pollIntervalMs = null,
            detectsIdle = false,
        )

        override fun focusChanges(): Flow<FocusChange> = emptyFlow()
        override fun permissionState(): PermissionState = PermissionState.Unsupported(reason)
    }
}
