package dev.lumen.app

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.lumen.app.collector.LinuxCollectors
import dev.lumen.app.names.DesktopEntryNameResolver
import dev.lumen.core.category.DayView
import dev.lumen.core.category.sessionCategoryEngine
import dev.lumen.core.clock.LocalDay
import dev.lumen.core.collector.AppNameResolver
import dev.lumen.core.collector.PermissionState
import dev.lumen.core.model.AppKey
import dev.lumen.core.model.AppTotal
import dev.lumen.core.model.DeviceId
import dev.lumen.core.session.DayAccumulator
import dev.lumen.core.session.FocusSessionTracker
import dev.lumen.ui.AccountSection
import dev.lumen.ui.DeviceScope
import dev.lumen.ui.HistoryState
import dev.lumen.ui.LumenAppShell
import dev.lumen.ui.ScrobbleSection
import dev.lumen.ui.SettingsPage
import dev.lumen.ui.ShellTab
import dev.lumen.ui.TodayScreen
import dev.lumen.ui.charts.CategorySlice
import dev.lumen.ui.charts.DayDetail
import dev.lumen.ui.charts.DayTotal
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.datetime.TimeZone

/**
 * Lumen for Linux.
 *
 * The SHARED `:ui` screens inside [LumenAppShell] — Today, Sync, Scrobbling —
 * over the SQLite store. The collector is picked for the running session
 * (Hyprland, Sway or X11 — [LinuxCollectors]); [FocusSessionTracker] derives
 * durations; RollupEngine buckets and rolls them up.
 *
 * If the background service (`lumen-tracker.service`) is already running,
 * IT tracks, syncs and scrobbles, and this window shows what it records —
 * see [LinuxRuntime.tryBecomeTracker].
 */
fun main(args: Array<String>) = runApp()

private fun runApp() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Lumen",
        state = rememberWindowState(size = DpSize(880.dp, 720.dp)),
    ) {
        // Whoever holds the tracker lock records focus; if the service holds
        // it, this window is a viewer of the same database.
        val isTracker = remember { LinuxRuntime.tryBecomeTracker() != null }
        val collector = remember { LinuxCollectors.detect() }
        val permission = remember { collector.permissionState() }
        val nameResolver: AppNameResolver = remember { DesktopEntryNameResolver() }
        val store = remember { LinuxRuntime.openStore() }
        val deviceId = remember { LinuxRuntime.resolveDeviceId(store) }
        val services = remember {
            LinuxRuntime.services(store, deviceId).also {
                it.sync.passive = !isTracker
                it.scrobble.passive = !isTracker
            }
        }
        DisposableEffect(Unit) { onDispose { services.scope.coroutineContext[Job]?.cancel() } }
        // Seed the seq counter from the store: FocusSessionTracker numbers
        // from 0 on every launch, and the (device_id, seq) PK drops colliding
        // inserts silently (INSERT OR IGNORE). Without this a restart makes
        // the day stop growing with no error.
        val tracker = remember { FocusSessionTracker(deviceId, store.lastEventSeq(deviceId) + 1) }
        val day = remember { DayAccumulator() }

        var tab by remember { mutableStateOf("today") }
        var allDevices by remember { mutableStateOf(false) }
        var dataVersion by remember { mutableStateOf(0) }
        var totals by remember { mutableStateOf(emptyList<AppTotal>()) }
        var storedTotalMs by remember { mutableStateOf(0L) }
        var storedTotals by remember { mutableStateOf(emptyList<AppTotal>()) }
        var liveAppKey by remember { mutableStateOf<AppKey?>(null) }
        var liveApp by remember { mutableStateOf<String?>(null) }
        var liveSinceMs by remember { mutableStateOf(0L) }
        var now by remember { mutableStateOf(System.currentTimeMillis()) }
        var categories by remember { mutableStateOf(emptyList<CategorySlice>()) }
        var recentDays by remember { mutableStateOf(emptyList<DayTotal>()) }
        var averageMs by remember { mutableStateOf<Long?>(null) }
        var selectedDay by remember { mutableStateOf<String?>(null) }
        var dayDetail by remember { mutableStateOf<DayDetail?>(null) }

        // Shared derivation, so the strip and the app list are the same
        // numbers grouped and can never disagree. Session overrides for now:
        // the registry half is real, and nothing here pretends a user's
        // choice survives a restart until the store carries them.
        val dayView = remember { DayView(sessionCategoryEngine()) }

        fun decorate(rows: List<AppTotal>): List<AppTotal> = dayView.rows(rows)

        fun slices(rows: List<AppTotal>): List<CategorySlice> =
            dayView.categoryNames(rows).map { (name, ms) -> CategorySlice(name, ms) }

        /**
         * The display day is the USER's day, not the UTC day — discussion #29.
         * At UTC-4 the UTC day rolls at 20:00 local, so the "Today" number
         * reset at 8pm every evening. The zone comes from the reconciled
         * display.timezone setting (defaults to the device zone); the Today
         * screen sums buckets over that zone's local-day window, because
         * buckets carry absolute timestamps and a local day is a different
         * window over the same data.
         */
        fun displayZone(): TimeZone {
            val stored = store.setting(LocalDay.SETTING_KEY)?.value?.toString(Charsets.UTF_8)
            return LocalDay.zoneOf(stored)
        }

        /** This device, or this device plus every synced device that has delivered data. */
        fun devicesInScope(): List<DeviceId> =
            if (allDevices) listOf(deviceId) + services.sync.peerDevices() else listOf(deviceId)

        fun localDayTotals(zone: TimeZone, dayLocal: String): List<Pair<AppKey, Long>> {
            val dayStart = LocalDay.startOfDayMs(dayLocal, zone)
            val dayEnd = LocalDay.endOfDayMs(dayLocal, zone)
            return devicesInScope().flatMap { store.bucketsForRange(it, dayStart, dayEnd) }
                .filter { it.appKey.value.isNotBlank() }
                .groupBy { it.appKey }
                .map { (app, buckets) -> app to buckets.sumOf { it.activeMs } }
                .sortedByDescending { it.second }
        }

        fun loadHistory() {
            val zone = displayZone()
            val today = LocalDay.today(zone)
            val todayStart = LocalDay.startOfDayMs(today, zone)
            val windowStart = LocalDay.startOfDayMs(today, zone) - (HISTORY_WINDOW_DAYS - 1) * MILLIS_PER_DAY
            val days = LocalDay.daysBetween(windowStart, todayStart, zone)
            recentDays = days.map { d ->
                val totalMs = localDayTotals(zone, d).sumOf { it.second }
                DayTotal(
                    dayUtc = d,
                    totalMs = totalMs,
                    isToday = d == today,
                )
            }
            // Complete days only: a partial today drags the mean down every
            // morning and lets it recover every evening, which looks like a
            // trend and is an artefact of the clock.
            val complete = recentDays.filterNot { it.isToday }
            averageMs = if (complete.isEmpty()) null else complete.sumOf { it.totalMs } / complete.size
        }

        /**
         * Latest local title hint per app, for today's events. Local-only:
         * never synced (docs/e2ee.md §3), shown only in this device's UI.
         */
        fun titleHintsForToday(): Map<AppKey, String> {
            val zone = displayZone()
            val dayStart = LocalDay.startOfDayMs(LocalDay.today(zone), zone)
            val hints = mutableMapOf<AppKey, String>()
            store.eventsAfter(deviceId, Long.MIN_VALUE)
                .asSequence()
                .filter { it.startedAtMs >= dayStart && it.titleHash != null }
                .forEach { hints[it.appKey] = it.titleHash!! }
            return hints
        }

        fun render() {
            val zone = displayZone()
            val today = LocalDay.today(zone)
            val hints = titleHintsForToday()
            val appTotals = localDayTotals(zone, today)
            storedTotalMs = appTotals.sumOf { it.second }
            totals = appTotals.map { (appKey, ms) ->
                AppTotal(
                    appKey = appKey,
                    displayName = nameResolver.resolve(appKey) ?: day.nameFor(appKey),
                    totalMs = ms,
                    titleHint = hints[appKey],
                )
            }
            storedTotals = totals
            totals = decorate(totals)
            categories = slices(totals)
        }

        // The app list must tick with the live session, not freeze until the
        // next focus change — otherwise the top total climbs every second
        // while the rows beneath stay static. The base MUST come from the
        // stored rollups, never from `totals` (which already carries the
        // previous live merge — re-merging on top of it compounds the live
        // time quadratically and inflates the day).
        fun refreshTotals(liveMs: Long) {
            val base = storedTotals.associate { it.appKey to it.totalMs }.toMutableMap()
            val hints = titleHintsForToday()
            val liveKey = liveAppKey
            if (liveKey != null && liveMs > 0) {
                base.merge(liveKey, liveMs, Long::plus)
            }
            totals = base.entries
                // AppKey("") is the idle/locked transition, not an app; it is
                // already inside storedTotalMs + liveMs.
                .filter { it.key.value.isNotBlank() }
                .sortedByDescending { it.value }
                .map { (appKey, ms) ->
                    AppTotal(
                        appKey = appKey,
                        displayName = nameResolver.resolve(appKey) ?: day.nameFor(appKey),
                        totalMs = ms,
                        titleHint = hints[appKey],
                    )
                }
            totals = decorate(totals)
            categories = slices(totals)
        }

        LaunchedEffect(Unit) {
            render()
            loadHistory()
            services.sync.onRemoteData = { dataVersion++ }
            if (!isTracker) {
                // The service is recording; keep re-reading what it writes.
                while (true) {
                    delay(VIEWER_REFRESH_MS)
                    dataVersion++
                }
            }
            services.sync.startLoop()
            services.scrobble.attach(services.media.playback())
            if (permission !is PermissionState.Granted) return@LaunchedEffect
            collector.focusChanges().collect { change ->
                day.remember(change)
                tracker.onChange(change)?.let { closed ->
                    LinuxRuntime.persistEvent(store, closed)
                    day.add(closed)
                    render()
                }
                liveAppKey = change.appKey
                liveApp = nameResolver.resolve(change.appKey) ?: change.appKey.value
                liveSinceMs = change.atMs
            }
        }

        // Synced data arrived, the viewer refreshed, or the scope switched.
        LaunchedEffect(dataVersion, allDevices) {
            if (dataVersion == 0 && !allDevices) return@LaunchedEffect
            render()
            loadHistory()
        }

        LaunchedEffect(Unit) {
            var lastDay = LocalDay.today(displayZone())
            while (true) {
                now = System.currentTimeMillis()
                val today = LocalDay.today(displayZone())
                // Local midnight rollover, not UTC: the display day is the
                // user's day (discussion #29), and a UTC-bound ticker reset
                // the Today number at 8pm for a UTC-4 user. The cached render
                // (and its stored rollup totals) belong to the previous day;
                // without this the UI keeps showing yesterday's day until the
                // next focus change, which for an app left open overnight is
                // all morning.
                if (today != lastDay) {
                    lastDay = today
                    day.clear()
                    render()
                    loadHistory()
                    liveSinceMs = 0L
                    liveAppKey = null
                    liveApp = null
                }
                val liveMs = if (liveSinceMs > 0 && liveAppKey != null && liveAppKey!!.value.isNotBlank())
                    (now - liveSinceMs).coerceAtLeast(0) else 0
                refreshTotals(liveMs)
                delay(1000)
            }
        }

        val liveMs = if (liveSinceMs > 0 && liveAppKey != null && liveAppKey!!.value.isNotBlank())
            (now - liveSinceMs).coerceAtLeast(0) else 0

        val reducedMotion = remember { reducedMotionEnabled() }
        val accountState by services.sync.state.collectAsState()
        val scrobbleState by services.scrobble.state.collectAsState()
        val hasPeers = remember(dataVersion, accountState) { services.sync.peerDevices().isNotEmpty() }

        LumenAppShell(
            tabs = listOf(ShellTab("today", "Today"), ShellTab("sync", "Sync"), ShellTab("scrobble", "Scrobbling")),
            selectedId = tab,
            onSelect = { tab = it },
        ) {
            when (tab) {
                "sync" -> SettingsPage {
                    AccountSection(
                        providers = services.sync.providers,
                        state = accountState,
                        onRegister = services.sync::register,
                        onSave = services.sync::signIn,
                        onDisconnect = services.sync::disconnect,
                        onAnswerChallenge = services.sync::answerChallenge,
                        onCancel = services.sync::cancelSignUp,
                        onSyncNow = services.sync::syncNow,
                        onForgetDevice = services.sync::forgetDevice,
                        onOpenUrl = LinuxRuntime::openUrl,
                    )
                }
                "scrobble" -> SettingsPage {
                    ScrobbleSection(
                        state = if (isTracker) {
                            scrobbleState
                        } else {
                            scrobbleState.copy(
                                sourceStatus = "The Lumen background service is running and does the scrobbling. " +
                                    "Sign-ins made here apply to it.",
                            )
                        },
                        onSignIn = services.scrobble::signIn,
                        onCancelSignIn = services.scrobble::cancelSignIn,
                        onSubmitToken = services.scrobble::submitToken,
                        onSignOut = services.scrobble::signOut,
                        onOpenUrl = LinuxRuntime::openUrl,
                        onSourceAction = {},
                        onIncludeBrowsers = services.scrobble::setIncludeBrowsers,
                        onSendNow = services.scrobble::sendNow,
                    )
                }
                else -> TodayScreen(
                    totals = totals,
                    categories = categories,
                    recentDays = recentDays,
                    averageMs = averageMs,
                    selectedDay = selectedDay,
                    dayDetail = dayDetail,
                    onSelectDay = { d ->
                        if (d == selectedDay) {
                            selectedDay = null
                            dayDetail = null
                        } else {
                            selectedDay = d
                            val zone = displayZone()
                            val rows = decorate(
                                localDayTotals(zone, d).map { (appKey, ms) ->
                                    AppTotal(
                                        appKey = appKey,
                                        displayName = nameResolver.resolve(appKey) ?: appKey.value,
                                        totalMs = ms,
                                    )
                                },
                            )
                            dayDetail = DayDetail(dayUtc = d, totalMs = rows.sumOf { it.totalMs }, totals = rows)
                        }
                    },
                    onClearDaySelection = {
                        selectedDay = null
                        dayDetail = null
                    },
                    totalMs = storedTotalMs + liveMs,
                    liveApp = liveApp,
                    reducedMotion = reducedMotion,
                    topPadding = 20.dp,
                    historyState = when {
                        !isTracker -> HistoryState.Hidden
                        permission is PermissionState.Unsupported -> HistoryState.Message(permission.reason)
                        permission is PermissionState.Required ->
                            HistoryState.Message("${permission.rationale} ${permission.settingsHint}")
                        else -> HistoryState.Hidden
                    },
                    deviceScope = if (hasPeers) DeviceScope(allDevices) { allDevices = it } else null,
                )
            }
        }
    }
}

/** How often a viewer window (the service is tracking) re-reads the store. */
private const val VIEWER_REFRESH_MS = 30_000L

/**
 * Whether the desktop asks for reduced motion.
 *
 * `docs/design-spec.md` requires respecting it on every platform, and this
 * was hardcoded to false — so the setting was honoured on macOS and silently
 * ignored on Linux, which is worse than not claiming to support it.
 *
 * Wayland has no single answer, so this reads the GNOME/GTK key that the
 * toolkits actually consult; wlroots compositors inherit it through
 * `gsettings` on most distributions. Falls back to on-by-default motion when
 * the setting cannot be read, because a missing key is not a request.
 */
private fun reducedMotionEnabled(): Boolean = runCatching {
    val p = ProcessBuilder(
        "gsettings", "get", "org.gnome.desktop.interface", "enable-animations",
    ).redirectErrorStream(false).start()
    val out = p.inputStream.bufferedReader().readText().trim()
    if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
        p.destroyForcibly()
        false
    } else {
        out == "false"
    }
}.getOrDefault(false)

/** Chart 3 in docs/design-spec.md is "7/30-day bars"; 7 is the calm default. */
private const val HISTORY_WINDOW_DAYS = 7

private const val MILLIS_PER_DAY = 86_400_000L
