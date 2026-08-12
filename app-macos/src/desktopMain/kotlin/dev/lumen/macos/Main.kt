package dev.lumen.macos

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import dev.lumen.core.clock.UtcDay
import dev.lumen.macos.collector.LsAppInfoCollector
import dev.lumen.macos.importer.KnowledgeCImporter
import dev.lumen.macos.permissions.FullDiskAccess
import dev.lumen.macos.session.FocusSessionTracker
import dev.lumen.macos.startup.LoginItem
import dev.lumen.macos.store.UsageStore
import dev.lumen.macos.ui.LumenTrayIcon
import dev.lumen.ui.AppTotal
import dev.lumen.ui.HistoryState
import dev.lumen.ui.TodayScreen
import dev.lumen.ui.formatDuration
import kotlinx.coroutines.delay

/**
 * Lumen for macOS — a menu-bar app that happens to have a window.
 *
 * Tracking runs for as long as the app runs, independent of whether the window
 * is open. Closing the window hides it; the tray item stays. Quit is explicit,
 * from the menu — the one place the user can stop it, and it must always work.
 *
 * Local-only: no account, no sync, no network.
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
    var windowVisible by remember { mutableStateOf(!launchedAtLogin()) }
    var launchAtLogin by remember { mutableStateOf(LoginItem.isEnabled()) }

    // Collection is tied to the application, NOT to the window. The window is
    // a view onto it; hiding the window must not stop tracking.
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

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            // Rolls the view over at UTC midnight without a restart.
            val today = UtcDay.today()
            if (totals.isNotEmpty() || liveSinceMs > 0) totals = store.totalsFor(today)
            delay(1000)
        }
    }

    val liveMs = if (liveSinceMs > 0) (now - liveSinceMs).coerceAtLeast(0) else 0
    val totalMs = totals.sumOf { it.totalMs } + liveMs

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

    // The menu bar item — the app's primary surface.
    Tray(
        icon = remember { LumenTrayIcon(Color.Black) },
        state = rememberTrayState(),
        tooltip = "Lumen — ${formatDuration(totalMs)} today",
        menu = {
            Item("Today — ${formatDuration(totalMs)}", enabled = false, onClick = {})
            liveApp?.let { Item("Now: $it", enabled = false, onClick = {}) }
            Separator()

            if (totals.isEmpty()) {
                Item("No activity recorded yet", enabled = false, onClick = {})
            } else {
                // A glance, not a report. The window is there for the rest.
                totals.take(5).forEach { row ->
                    Item("${row.displayName}  ·  ${formatDuration(row.totalMs)}", onClick = { windowVisible = true })
                }
            }

            Separator()
            Item("Open Lumen", onClick = { windowVisible = true })

            if (LoginItem.isSupported()) {
                Item(
                    if (launchAtLogin) "Launch at login  ✓" else "Launch at login",
                    onClick = {
                        launchAtLogin = if (launchAtLogin) {
                            !LoginItem.disable()
                        } else {
                            LoginItem.enable()
                        }
                    },
                )
            }

            Separator()
            Item("Quit Lumen", onClick = ::exitApplication)
        },
    )

    if (windowVisible) {
        Window(
            // Closing hides; it does not quit. Tracking continues in the menu bar.
            onCloseRequest = { windowVisible = false },
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
}

/**
 * True when launchd started us at login, in which case the app should come up
 * quietly in the menu bar rather than throwing a window at someone who is
 * still logging in.
 */
private fun launchedAtLogin(): Boolean =
    System.getenv("XPC_SERVICE_NAME")?.contains(LoginItem.LABEL) == true

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
