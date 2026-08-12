package dev.lumen.macos

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
import dev.lumen.core.clock.UtcDay
import dev.lumen.macos.collector.LsAppInfoCollector
import dev.lumen.macos.session.FocusSessionTracker
import dev.lumen.macos.store.AppTotal
import dev.lumen.macos.store.UsageStore
import dev.lumen.macos.ui.TodayScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect

/**
 * Lumen for macOS — local-only.
 *
 * collector -> session tracker -> local store -> Today screen. No account, no
 * sync, no network. `docs/plan.md` locks local-only as the default posture, so
 * this is a complete slice: first run needs nothing and blocks on nothing.
 */
fun main() = application {
    val store = remember { UsageStore() }
    val deviceId = remember { store.deviceId() }
    val tracker = remember { FocusSessionTracker(deviceId) }
    val collector = remember { LsAppInfoCollector() }

    var totals by remember { mutableStateOf(emptyList<AppTotal>()) }
    var liveApp by remember { mutableStateOf<String?>(null) }
    var liveSinceMs by remember { mutableStateOf(0L) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

    // Collect focus transitions; close each session into the store as it ends.
    LaunchedEffect(Unit) {
        totals = store.totalsFor(UtcDay.today())
        collector.focusChanges().collect { change ->
            store.rememberName(change.appKey, change.displayName)
            tracker.onChange(change)?.let { closed ->
                store.append(closed)
                totals = store.totalsFor(UtcDay.today())
            }
            liveApp = change.displayName ?: change.appKey.value
            liveSinceMs = change.atMs
        }
    }

    // Ticks the live readout. The open session is not yet an event, so its
    // elapsed time is added for display only and never written to the store.
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    val liveMs = if (liveSinceMs > 0) (now - liveSinceMs).coerceAtLeast(0) else 0
    val totalMs = totals.sumOf { it.totalMs } + liveMs

    Window(
        onCloseRequest = ::exitApplication,
        title = "Lumen",
        state = rememberWindowState(size = DpSize(720.dp, 560.dp)),
    ) {
        TodayScreen(
            totals = totals,
            totalMs = totalMs,
            liveApp = liveApp,
            reducedMotion = reducedMotionEnabled(),
        )
    }
}

/**
 * macOS "Reduce motion" (Accessibility > Display). The spec requires honouring
 * it on both platforms. Read once at startup; a mid-session toggle is rare
 * enough that re-reading it every frame is not worth a `defaults` subprocess.
 */
private fun reducedMotionEnabled(): Boolean = runCatching {
    val p = ProcessBuilder(
        "defaults", "read", "com.apple.universalaccess", "reduceMotion",
    ).start()
    val out = p.inputStream.bufferedReader().readText().trim()
    p.waitFor()
    out == "1"
}.getOrDefault(false)
