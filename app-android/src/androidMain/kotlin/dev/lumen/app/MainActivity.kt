package dev.lumen.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.lumen.app.collector.UsageStatsCollector
import dev.lumen.app.names.PackageManagerNameResolver
import dev.lumen.core.category.DayView
import dev.lumen.core.category.sessionCategoryEngine
import dev.lumen.core.clock.LocalDay
import dev.lumen.core.clock.UtcDay
import kotlinx.datetime.TimeZone
import dev.lumen.core.collector.AppNameResolver
import dev.lumen.core.collector.PermissionState
import dev.lumen.core.model.AppKey
import dev.lumen.core.model.AppTotal
import dev.lumen.core.model.DeviceId
import dev.lumen.core.model.FocusEvent
import dev.lumen.core.model.Setting
import dev.lumen.core.rollup.RollupEngine
import dev.lumen.core.session.DayAccumulator
import dev.lumen.core.session.FocusSessionTracker
import dev.lumen.core.store.AndroidLumenStore
import dev.lumen.ui.HistoryState
import dev.lumen.ui.TodayScreen
import dev.lumen.ui.charts.CategorySlice
import dev.lumen.ui.charts.DayDetail
import dev.lumen.ui.charts.DayTotal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Lumen for Android.
 *
 * Renders the SHARED `:ui` TodayScreen — byte-for-byte the same composable
 * macOS and Linux draw, so the three platforms cannot drift apart visually.
 *
 * Storage is [AndroidLumenStore] (SQLite via AndroidSqliteDriver). Events,
 * buckets and rollups persist across launches, so history survives restarts
 * and the full UI surface (categories, recent-days trend, day drilldown)
 * renders exactly as on desktop and macOS.
 */
class MainActivity : ComponentActivity() {

    /**
     * Whether the system asks for reduced motion.
     *
     * `docs/design-spec.md` requires respecting it on both platforms. This
     * was hardcoded to false, so the setting worked on macOS and was silently
     * ignored here — worse than not claiming support, because the spec says
     * we do.
     *
     * `ANIMATOR_DURATION_SCALE` is the value Android's own accessibility
     * "Remove animations" toggle writes, and what the platform widgets read.
     */
    private fun reducedMotionEnabled(): Boolean = runCatching {
        android.provider.Settings.Global.getFloat(
            contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }.getOrDefault(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val collector = UsageStatsCollector(applicationContext)
        val nameResolver: AppNameResolver = PackageManagerNameResolver(applicationContext)
        val store = AndroidLumenStore.open(applicationContext)
        val deviceId = resolveDeviceId(store)

        setContent {
            val tracker = remember { FocusSessionTracker(deviceId) }
            val day = remember { DayAccumulator() }

            var totals by remember { mutableStateOf(emptyList<AppTotal>()) }
            var storedTotalMs by remember { mutableStateOf(0L) }
            var storedTotals by remember { mutableStateOf(emptyList<AppTotal>()) }
            var categories by remember { mutableStateOf(emptyList<CategorySlice>()) }
            var recentDays by remember { mutableStateOf(emptyList<DayTotal>()) }
            var averageMs by remember { mutableStateOf<Long?>(null) }
            var selectedDay by remember { mutableStateOf<String?>(null) }
            var dayDetail by remember { mutableStateOf<DayDetail?>(null) }
            var liveAppKey by remember { mutableStateOf<AppKey?>(null) }
            var liveAppName by remember { mutableStateOf<String?>(null) }
            var liveSinceMs by remember { mutableStateOf(0L) }
            var now by remember { mutableStateOf(System.currentTimeMillis()) }
            val permission = remember { collector.permissionState() }

            // Shared derivation (same as macOS and Linux): the category strip
            // is exactly the app list grouped, so they cannot disagree.
            val dayView = remember { DayView(sessionCategoryEngine()) }

            // All DB reads happen on Dispatchers.IO, with state writes applied
            // back on the main thread. The old version ran the 7-day trend
            // query on the main thread, which blocked on the SQLite lock the
            // IO backfill holds — that is the ANR ("Input dispatching timed
            // out") that reappeared even after the backfill was batched.
            fun displayZone(): TimeZone {
                val stored = store.setting(LocalDay.SETTING_KEY)?.value?.toString(Charsets.UTF_8)
                return LocalDay.zoneOf(stored)
            }

            suspend fun render() = withContext(Dispatchers.IO) {
                val zone = displayZone()
                val today = LocalDay.today(zone)
                val dayStart = LocalDay.startOfDayMs(today, zone)
                val dayEnd = LocalDay.endOfDayMs(today, zone)
                // storedTotalMs must exclude the idle key: locked screen time
                // is not screen time, and the UI total feeds off this. The
                // Today number is the USER's day (LocalDay, discussion #29),
                // summed from buckets over the local-day window — a UTC-day
                // read reset it at 8pm local.
                val todayBuckets = store.bucketsForRange(deviceId, dayStart, dayEnd)
                    .filter { it.appKey.value.isNotBlank() }
                val storedTotal = todayBuckets.sumOf { it.activeMs }
                val byApp = todayBuckets.groupBy { it.appKey }
                val list = byApp
                    .map { (appKey, buckets) ->
                        AppTotal(
                            appKey = appKey,
                            displayName = nameResolver.resolve(appKey) ?: day.nameFor(appKey),
                            totalMs = buckets.sumOf { it.activeMs },
                        )
                    }
                    .sortedByDescending { it.totalMs }

                // Recent days for the trend chart, on the same local-day
                // boundaries as "Today" — the chart and the big number must
                // agree (design-spec: "numbers and charts must agree").
                val todayStartMs = LocalDay.startOfDayMs(today, zone)
                val windowStart = LocalDay.startOfDayMs(today, zone) - (HISTORY_WINDOW_DAYS - 1) * MILLIS_PER_DAY
                val days = LocalDay.daysBetween(windowStart, todayStartMs, zone)
                val dayTotals = days.map { dayLocal ->
                    val dayStartMs = LocalDay.startOfDayMs(dayLocal, zone)
                    val dayEndMs = LocalDay.endOfDayMs(dayLocal, zone)
                    val dayMs = store.bucketsForRange(deviceId, dayStartMs, dayEndMs)
                        .filter { it.appKey.value.isNotBlank() }
                        .sumOf { it.activeMs }
                    DayTotal(dayUtc = dayLocal, totalMs = dayMs, isToday = dayLocal == today)
                }
                RenderSnapshot(
                    storedTotal = storedTotal,
                    list = list,
                    dayTotals = dayTotals,
                )
            }.also { snap ->
                storedTotalMs = snap.storedTotal
                storedTotals = snap.list
                totals = dayView.rows(snap.list)
                categories = dayView.categoryNames(snap.list)
                    .map { (name, ms) -> CategorySlice(name, ms) }
                recentDays = snap.dayTotals
                val completed = snap.dayTotals.filter { !it.isToday && it.totalMs > 0 }
                averageMs = if (completed.isNotEmpty()) completed.sumOf { it.totalMs } / completed.size else null
            }

            // The app list must tick with the live session, not freeze until
            // the next focus change. The base MUST come from the stored
            // rollups, never from `totals` (which already carries the previous
            // live merge — re-merging on top of it compounds the live time
            // quadratically and inflates the day).
            fun refreshTotals(liveMs: Long) {
                val base = storedTotals.associate { it.appKey to it.totalMs }.toMutableMap()
                val liveKey = liveAppKey
                if (liveKey != null && liveMs > 0) {
                    base.merge(liveKey, liveMs, Long::plus)
                }
                totals = base.entries
                    .filter { it.key.value.isNotBlank() }
                    .sortedByDescending { it.value }
                    .map { (appKey, ms) ->
                        AppTotal(
                            appKey = appKey,
                            displayName = nameResolver.resolve(appKey) ?: day.nameFor(appKey),
                            totalMs = ms,
                        )
                    }
            }

            /** Recompute one day's rollups from its buckets (idempotent). */
            fun recomputeDayRollups(deviceId: DeviceId, atMs: Long) {
                val today = UtcDay.dayOf(atMs)
                val dayStart = UtcDay.boundary(today)
                val buckets = store.bucketsForRange(deviceId, dayStart, dayStart + 86_400_000)
                RollupEngine.rollup(deviceId, today, buckets).forEach(store::upsertRollup)
            }

            /** Recompute the trend window's rollups once after a batched import. */
            fun recomputeHistoryRollups() {
                val today = UtcDay.today()
                (0 until HISTORY_WINDOW_DAYS).forEach { back ->
                    val dayUtc = UtcDay.dayOf(UtcDay.boundary(today) - back * MILLIS_PER_DAY)
                    val dayStart = UtcDay.boundary(dayUtc)
                    val buckets = store.bucketsForRange(deviceId, dayStart, dayStart + 86_400_000)
                    RollupEngine.rollup(deviceId, dayUtc, buckets).forEach(store::upsertRollup)
                }
            }

            /**
             * Rebuild derived rows from events, which are the source of truth
             * (M1 contract: buckets/rollups are DERIVED, never synced).
             *
             * A pre-fix binary could leave derived rows no event explains —
             * e.g. the old idle-accrual bug wrote a session that never
             * closed, and its ~24h of full-minute buckets survived even
             * after the events themselves were corrected. Wipe and re-derive
             * so the numbers shown always trace back to real events.
             */
            fun rebuildDerived() {
                val allEvents = store.eventsAfter(deviceId, Long.MIN_VALUE)
                store.clearDerived(deviceId)
                allEvents.forEach { event ->
                    RollupEngine.bucket(event).forEach(store::insertBucket)
                }
                recomputeHistoryRollups()
            }

            fun persistEvent(event: FocusEvent) {
                store.insertEvent(event)
                RollupEngine.bucket(event).forEach(store::insertBucket)
                recomputeDayRollups(event.deviceId, event.startedAtMs)
            }

            /** Insert event + buckets only — cheap, used by the batched backfill. */
            fun insertEventOnly(event: FocusEvent) {
                store.insertEvent(event)
                RollupEngine.bucket(event).forEach(store::insertBucket)
            }

            LaunchedEffect(permission) {
                // Without Usage Access there is nothing to collect. Say so in
                // the screen's own language rather than starting a stream that
                // silently yields nothing, which is indistinguishable from an
                // idle user.
                if (permission !is PermissionState.Granted) return@LaunchedEffect

                render()

                // Android retains usage events for ~3 days (the collector's
                // backfillHorizonMs). The live stream only sees events from
                // this point on, so without a backfill the Today screen would
                // start at zero every launch even though the user has been
                // using the phone for hours. UsageStatsManager is the same
                // backend Digital Wellbeing reads — this is how the app shows
                // the real day. Backfill the FULL horizon, not just today:
                // the trend chart reads past days, and a today-only query
                // leaves them at zero on a fresh install.
                //
                // The backfill can be hundreds of events. It runs on
                // Dispatchers.IO (never the main thread — that ANRs first
                // launch), and inserts are batched: events + buckets only
                // during the loop, rollups recomputed ONCE afterward. The
                // old per-event full-day rollup recompute made the import
                // O(n²) — each event re-read every bucket of its day while
                // holding the SQLite write lock, so the main thread blocked
                // on the post-import render for minutes.
                val sinceMs = System.currentTimeMillis() - BACKFILL_DAYS * 86_400_000
                withContext(Dispatchers.IO) {
                    collector.backfill(sinceMs).forEach { change ->
                        day.remember(change)
                        tracker.onChange(change)?.let { closed ->
                            insertEventOnly(closed)
                            day.add(closed)
                        }
                        liveAppKey = change.appKey
                        liveAppName = nameResolver.resolve(change.appKey) ?: change.appKey.value
                        liveSinceMs = change.atMs
                    }
                    // Repair any derived rows left by earlier buggy writers,
                    // then keep them honest on every launch.
                    rebuildDerived()
                }
                render()
                refreshTotals(0)

                collector.focusChanges().collect { change ->
                    day.remember(change)
                    tracker.onChange(change)?.let { closed ->
                        withContext(Dispatchers.IO) {
                            persistEvent(closed)
                            day.add(closed)
                        }
                        render()
                        refreshTotals(0)
                    }
                    liveAppKey = change.appKey
                    liveAppName = nameResolver.resolve(change.appKey) ?: change.appKey.value
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

            // The visible total must exclude the idle key (AppKey("")) — the
            // screen-locked sessions. They are needed internally to close the
            // previous app's session, but they are not screen time: a phone
            // locked from 2am to 11am would otherwise show an 9-hour "day".
            fun visibleTotalMs(): Long =
                storedTotalMs + if (liveAppKey != null && liveAppKey!!.value.isNotBlank()) {
                    if (liveSinceMs > 0) (now - liveSinceMs).coerceAtLeast(0) else 0
                } else {
                    0
                }

            TodayScreen(
                totals = totals,
                totalMs = visibleTotalMs(),
                liveApp = null,
                showLiveApp = false,
                reducedMotion = remember { reducedMotionEnabled() },
                historyState = when (permission) {
                    is PermissionState.Granted -> HistoryState.Hidden
                    is PermissionState.Required -> HistoryState.Message(
                        "${permission.rationale} ${permission.settingsHint}",
                    )
                    is PermissionState.Unsupported -> HistoryState.Message(permission.reason)
                },
                categories = categories,
                recentDays = recentDays,
                averageMs = averageMs,
                selectedDay = selectedDay,
                dayDetail = dayDetail,
                onSelectDay = { dayUtc ->
                    if (dayUtc == selectedDay) {
                        selectedDay = null
                        dayDetail = null
                    } else {
                        selectedDay = dayUtc
                        val zone = displayZone()
                        val dayStart = LocalDay.startOfDayMs(dayUtc, zone)
                        val dayEnd = LocalDay.endOfDayMs(dayUtc, zone)
                        val dayTotals = store.bucketsForRange(deviceId, dayStart, dayEnd)
                            .filter { it.appKey.value.isNotBlank() }
                            .groupBy { it.appKey }
                            .map { (appKey, bs) ->
                                AppTotal(
                                    appKey = appKey,
                                    displayName = nameResolver.resolve(appKey) ?: day.nameFor(appKey),
                                    totalMs = bs.sumOf { it.activeMs },
                                )
                            }
                            .sortedByDescending { it.totalMs }
                        dayDetail = DayDetail(
                            dayUtc = dayUtc,
                            totalMs = dayTotals.sumOf { it.totalMs },
                            totals = dayTotals,
                        )
                    }
                },
                onClearDaySelection = {
                    selectedDay = null
                    dayDetail = null
                },
            )
        }
    }

    private fun resolveDeviceId(store: AndroidLumenStore): DeviceId {
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

    companion object {
        /** Trend chart window, matching the Linux app's HISTORY_WINDOW_DAYS. */
        private const val HISTORY_WINDOW_DAYS = 7
        private const val MILLIS_PER_DAY = 86_400_000L

        /** How many days of usage history to import at startup. */
        private const val BACKFILL_DAYS = 3
    }
}

/** Values computed off the main thread by [MainActivity]'s render, applied on it. */
private data class RenderSnapshot(
    val storedTotal: Long,
    val list: List<AppTotal>,
    val dayTotals: List<DayTotal>,
)