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
import dev.lumen.core.clock.UtcDay
import dev.lumen.core.collector.AppNameResolver
import dev.lumen.core.model.AppKey
import dev.lumen.core.model.AppTotal
import dev.lumen.core.model.DeviceId
import dev.lumen.core.model.FocusEvent
import dev.lumen.core.model.Setting
import dev.lumen.core.rollup.RollupEngine
import dev.lumen.core.category.DayView
import dev.lumen.core.category.sessionCategoryEngine
import dev.lumen.core.session.DayAccumulator
import dev.lumen.core.session.FocusSessionTracker
import dev.lumen.core.store.JvmLumenStore
import dev.lumen.ui.TodayScreen
import dev.lumen.ui.charts.CategorySlice
import dev.lumen.ui.charts.DayDetail
import dev.lumen.ui.charts.DayTotal
import kotlinx.coroutines.delay
import java.io.File

/**
 * Lumen for Linux.
 *
 * Uses the SHARED `:ui` TodayScreen and persists through [JvmLumenStore]
 * (SQLite). The collector seam reports focus transitions; [FocusSessionTracker]
 * derives durations; [RollupEngine] buckets and rolls them up; the store
 * survives restarts.
 */
fun main(args: Array<String>) {
    // `lumen --headless` runs the tracking pipeline with no window — the
    // systemd service mode. The window app shares the same store, so both
    // can run at once and agree on the day.
    if (args.contains("--headless")) {
        mainHeadless()
        return
    }
    runApp()
}

private fun runApp() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Lumen",
        state = rememberWindowState(size = DpSize(880.dp, 680.dp)),
    ) {
        val collector = remember { HyprlandCollector() }
        val nameResolver: AppNameResolver = remember { DesktopEntryNameResolver() }
        val store = remember { openStore() }
        val deviceId = remember { resolveDeviceId(store) }
        val tracker = remember { FocusSessionTracker(deviceId) }
        val day = remember { DayAccumulator() }

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

        /** Per-day totals for the trend chart, straight from stored rollups. */
        fun loadHistory() {
            val today = UtcDay.today()
            val days = (HISTORY_WINDOW_DAYS - 1 downTo 0).map { back ->
                UtcDay.dayOf(UtcDay.boundary(today) - back * MILLIS_PER_DAY)
            }
            recentDays = days.map { d ->
                DayTotal(
                    dayUtc = d,
                    totalMs = store.rollupsForDay(deviceId, d)
                        .filter { it.appKey.value.isNotBlank() }
                        .sumOf { it.totalMs },
                    isToday = d == today,
                )
            }
            // Complete days only: a partial today drags the mean down every
            // morning and lets it recover every evening, which looks like a
            // trend and is an artefact of the clock.
            val complete = recentDays.filterNot { it.isToday }
            averageMs = if (complete.isEmpty()) null else complete.sumOf { it.totalMs } / complete.size
        }

        fun render() {
            val today = UtcDay.today()
            val rollups = store.rollupsForDay(deviceId, today)
            // storedTotalMs must exclude the idle key: locked screen time is
            // not screen time, and the UI total feeds off this.
            storedTotalMs = rollups.filter { it.appKey.value.isNotBlank() }.sumOf { it.totalMs }
            totals = rollups
                .filter { it.appKey.value.isNotBlank() }
                .sortedByDescending { it.totalMs }
                .map { rollup ->
                    AppTotal(
                        appKey = rollup.appKey,
                        displayName = nameResolver.resolve(rollup.appKey) ?: day.nameFor(rollup.appKey),
                        totalMs = rollup.totalMs,
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
                    )
                }
            totals = decorate(totals)
            categories = slices(totals)
        }

        LaunchedEffect(Unit) {
            render()
            loadHistory()
            collector.focusChanges().collect { change ->
                day.remember(change)
                tracker.onChange(change)?.let { closed ->
                    persistEvent(store, closed)
                    day.add(closed)
                    render()
                }
                liveAppKey = change.appKey
                liveApp = nameResolver.resolve(change.appKey) ?: change.appKey.value
                liveSinceMs = change.atMs
            }
        }

        LaunchedEffect(Unit) {
            while (true) {
                now = System.currentTimeMillis()
                val liveMs = if (liveSinceMs > 0 && liveAppKey != null && liveAppKey!!.value.isNotBlank())
                    (now - liveSinceMs).coerceAtLeast(0) else 0
                refreshTotals(liveMs)
                delay(1000)
            }
        }

        val liveMs = if (liveSinceMs > 0 && liveAppKey != null && liveAppKey!!.value.isNotBlank())
            (now - liveSinceMs).coerceAtLeast(0) else 0

        val reducedMotion = remember { reducedMotionEnabled() }

        TodayScreen(
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
                    val rows = decorate(
                        store.rollupsForDay(deviceId, d)
                            .filter { it.appKey.value.isNotBlank() }
                            .map {
                                AppTotal(
                                    appKey = it.appKey,
                                    displayName = nameResolver.resolve(it.appKey) ?: it.appKey.value,
                                    totalMs = it.totalMs,
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
        )
    }
}

private fun openStore(): JvmLumenStore {
    val dataDir = File(System.getProperty("user.home"), ".local/share/lumen")
    return JvmLumenStore.open(File(dataDir, "lumen.db"))
}

private fun resolveDeviceId(store: JvmLumenStore): DeviceId {
    val existing = store.setting("device_id")
    if (existing != null) return DeviceId(String(existing.value, Charsets.UTF_8))
    val id = DeviceId()
    store.upsertSetting(
        Setting(
            key = "device_id",
            value = id.value.toByteArray(Charsets.UTF_8),
            updatedAtMs = System.currentTimeMillis(),
            updatedDayUtc = UtcDay.today(),
            deviceId = id,
        ),
    )
    return id
}

private fun persistEvent(store: JvmLumenStore, event: FocusEvent) {
    store.insertEvent(event)
    RollupEngine.bucket(event).forEach(store::insertBucket)
    val today = UtcDay.dayOf(event.startedAtMs)
    val dayStart = UtcDay.boundary(today)
    val dayEnd = dayStart + 86_400_000
    val buckets = store.bucketsForRange(event.deviceId, dayStart, dayEnd)
    RollupEngine.rollup(event.deviceId, today, buckets).forEach(store::upsertRollup)
}

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
