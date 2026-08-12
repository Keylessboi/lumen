package dev.lumen.app

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.lumen.app.collector.HyprlandCollector
import dev.lumen.app.names.DesktopEntryNameResolver
import dev.lumen.core.collector.AppNameResolver
import dev.lumen.core.model.DeviceId
import dev.lumen.core.session.DayAccumulator
import dev.lumen.core.session.FocusSessionTracker
import dev.lumen.ui.AppTotal
import dev.lumen.ui.TodayScreen
import kotlinx.coroutines.delay

/**
 * Lumen for Linux.
 *
 * Uses the SHARED `:ui` TodayScreen — the same screen macOS and Android
 * render. It previously drew its own dev-harness list with hardcoded hex
 * colours copied out of `docs/design-spec.md`, which is exactly the drift the
 * shared module exists to prevent: three hand-copies of the design language,
 * differing the moment one is edited.
 *
 * Storage is still in-memory ([DayAccumulator]) pending the `LumenStore`
 * wiring at M2 — but the surface the user sees is the real one, not a
 * placeholder that quietly becomes the product.
 */
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Lumen",
        state = rememberWindowState(size = DpSize(880.dp, 680.dp)),
    ) {
        val collector = remember { HyprlandCollector() }
        val nameResolver: AppNameResolver = remember { DesktopEntryNameResolver() }
        val tracker = remember { FocusSessionTracker(DeviceId()) }
        val day = remember { DayAccumulator() }

        var totals by remember { mutableStateOf(emptyList<AppTotal>()) }
        var liveApp by remember { mutableStateOf<String?>(null) }
        var liveSinceMs by remember { mutableStateOf(0L) }
        var now by remember { mutableStateOf(System.currentTimeMillis()) }

        fun render() {
            totals = day.snapshot().map { (appKey, ms) ->
                AppTotal(
                    appKey = appKey,
                    // Resolver first: it knows the desktop-entry name, which
                    // is friendlier than the WM class the collector reports.
                    displayName = nameResolver.resolve(appKey) ?: day.nameFor(appKey),
                    totalMs = ms,
                )
            }
        }

        LaunchedEffect(Unit) {
            collector.focusChanges().collect { change ->
                day.remember(change)
                tracker.onChange(change)?.let { closed ->
                    day.add(closed)
                    render()
                }
                liveApp = nameResolver.resolve(change.appKey) ?: change.appKey.value
                liveSinceMs = change.atMs
            }
        }

        LaunchedEffect(Unit) {
            while (true) {
                now = System.currentTimeMillis()
                delay(1000)
            }
        }

        val liveMs = if (liveSinceMs > 0) (now - liveSinceMs).coerceAtLeast(0) else 0

        TodayScreen(
            totals = totals,
            totalMs = day.totalMs() + liveMs,
            liveApp = liveApp,
            reducedMotion = false,
        )
    }
}
