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
import dev.lumen.macos.importer.KnowledgeCImporter
import dev.lumen.macos.permissions.FullDiskAccess
import dev.lumen.macos.session.FocusSessionTracker
import dev.lumen.macos.store.AppTotal
import dev.lumen.macos.store.UsageStore
import dev.lumen.macos.ui.HistoryState
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

    // History import is opt-in. Live tracking never depends on it.
    var history by remember {
        mutableStateOf(
            when (FullDiskAccess.status()) {
                FullDiskAccess.Status.Granted ->
                    if (store.importWatermark() > 0L) HistoryState.Hidden else HistoryState.Ready
                FullDiskAccess.Status.Denied -> HistoryState.Offer
                FullDiskAccess.Status.Unavailable -> HistoryState.Hidden
            }
        )
    }

    Window(
        onCloseRequest = ::exitApplication,
        title = "Lumen",
        state = rememberWindowState(size = DpSize(760.dp, 620.dp)),
    ) {
        TodayScreen(
            totals = totals,
            totalMs = totalMs,
            liveApp = liveApp,
            reducedMotion = reducedMotionEnabled(),
            historyState = history,
            onOpenSettings = {
                history = if (FullDiskAccess.openSettingsPane()) {
                    HistoryState.Message(
                        "Add Lumen under Full Disk Access, then quit and reopen it — " +
                            "macOS only applies the change to a freshly launched app."
                    )
                } else {
                    HistoryState.Message(
                        "Couldn't open System Settings. Go to Privacy & Security → " +
                            "Full Disk Access and add Lumen there."
                    )
                }
            },
            onImport = {
                val since = store.importWatermark().takeIf { it > 0L }
                    ?: (System.currentTimeMillis() - DEFAULT_IMPORT_WINDOW_MS)
                history = when (val r = KnowledgeCImporter(deviceId).import(since)) {
                    is KnowledgeCImporter.Result.Imported -> {
                        store.appendImported(r.events)
                        totals = store.totalsFor(UtcDay.today())
                        if (r.events.isEmpty()) {
                            HistoryState.Message("No new history to import.")
                        } else {
                            HistoryState.Message("Imported ${r.events.size} sessions.")
                        }
                    }
                    KnowledgeCImporter.Result.PermissionDenied -> HistoryState.Offer
                    KnowledgeCImporter.Result.Unavailable ->
                        HistoryState.Message("This Mac has no usage history to import.")
                    KnowledgeCImporter.Result.SchemaUnrecognised -> HistoryState.Message(
                        "This macOS version stores usage history in a shape Lumen " +
                            "doesn't recognise, so nothing was imported. Tracking from " +
                            "here on is unaffected."
                    )
                    is KnowledgeCImporter.Result.Failed ->
                        HistoryState.Message("Import failed: ${r.reason}")
                }
            },
            onDismissHistory = { history = HistoryState.Hidden },
        )
    }
}

/** How far back a first import reaches. The store rarely holds more than this. */
private const val DEFAULT_IMPORT_WINDOW_MS = 30L * 24 * 3_600_000

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
