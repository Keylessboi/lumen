package dev.lumen.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lumen.app.collector.UsageStatsCollector
import dev.lumen.app.names.PackageManagerNameResolver
import dev.lumen.core.category.DayView
import dev.lumen.core.category.sessionCategoryEngine
import dev.lumen.core.clock.LocalDay
import dev.lumen.core.clock.UtcDay
import dev.lumen.core.collector.AppNameResolver
import dev.lumen.core.collector.PermissionState
import dev.lumen.core.model.AppKey
import dev.lumen.core.model.AppTotal
import dev.lumen.core.model.DeviceId
import dev.lumen.core.model.FocusEvent
import dev.lumen.core.model.RecapAppBreakdown
import dev.lumen.core.model.Setting
import dev.lumen.core.model.setTargetScreentime
import dev.lumen.core.rollup.RecapEngine
import dev.lumen.core.rollup.RollupEngine
import dev.lumen.core.session.DayAccumulator
import dev.lumen.core.session.FocusSessionTracker
import dev.lumen.core.store.AndroidLumenStore
import dev.lumen.ui.HistoryState
import dev.lumen.ui.LumenTheme
import dev.lumen.ui.OnboardingScreen
import dev.lumen.ui.TodayScreen
import dev.lumen.ui.charts.CategorySlice
import dev.lumen.ui.charts.DayDetail
import dev.lumen.ui.charts.DayTotal
import dev.lumen.ui.charts.MonthTotal
import dev.lumen.ui.charts.MonthlyRecapScreen
import dev.lumen.ui.charts.WeekComparison
import dev.lumen.ui.charts.WeeklyRecapScreen
import dev.lumen.ui.charts.YearComparison
import dev.lumen.ui.charts.YearlyRecapScreen
import dev.lumen.ui.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus

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
        val hasDeviceId = store.setting("device_id") != null
        val deviceId = resolveDeviceId(store)

        setContent {
            val tracker = remember { FocusSessionTracker(deviceId) }
            val day = remember { DayAccumulator() }

            // First-run onboarding gate: show OnboardingScreen until the user
            // completes onboarding (sets target or skips). device_id existence
            // is the durable "onboarding done" flag — resolveDeviceId creates
            // it, but we check BEFORE that so the first launch shows onboarding.
            var showOnboarding by remember { mutableStateOf(!hasDeviceId) }
            var showSyncMessage by remember { mutableStateOf(false) }
            var onboardingTargetMs by remember { mutableStateOf(14_400_000L) }

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

            // Navigation
            var selectedTab by remember { mutableStateOf("today") }
            var selectedPeriod by remember { mutableStateOf("week") }

            // Recap state
            var weeklyDays by remember { mutableStateOf(emptyList<DayTotal>()) }
            var weeklyComparison by remember { mutableStateOf<WeekComparison?>(null) }
            var weeklyTopApps by remember { mutableStateOf(emptyList<RecapAppBreakdown>()) }
            var weeklyTargetMs by remember { mutableStateOf<Long?>(null) }

            var monthlyMonthLabel by remember { mutableStateOf("") }
            var monthlyDays by remember { mutableStateOf(emptyList<DayTotal>()) }
            var monthlyTotalMs by remember { mutableStateOf(0L) }
            var monthlyTargetMs by remember { mutableStateOf<Long?>(null) }
            var monthlyCategories by remember { mutableStateOf(emptyList<CategorySlice>()) }
            var monthlyPreviousMs by remember { mutableStateOf<Long?>(null) }

            var yearlyMonths by remember { mutableStateOf(emptyList<MonthTotal>()) }
            var yearlyTopApps by remember { mutableStateOf(emptyList<RecapAppBreakdown>()) }
            var yearlyComparison by remember { mutableStateOf<YearComparison?>(null) }
            var yearlyMonthlyAverageMs by remember { mutableStateOf<Long?>(null) }

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

            LaunchedEffect(permission, selectedTab, selectedPeriod) {
                if (selectedTab != "recaps" || permission !is PermissionState.Granted) return@LaunchedEffect
                withContext(Dispatchers.IO) {
                    val nowMs = System.currentTimeMillis()
                    val zone = displayZone()
                    val todayStr = LocalDay.today(zone)
                    val todayLocal = LocalDate.parse(todayStr)

                    when (selectedPeriod) {
                        "week" -> {
                            val dayOfWeek = todayLocal.dayOfWeek.ordinal
                            val todayMs = LocalDay.startOfDayMs(todayStr, zone)
                            val weekStartMs = todayMs - dayOfWeek * MILLIS_PER_DAY
                            val recap = RecapEngine.weeklyRecap(store, deviceId, weekStartMs)
                            weeklyTargetMs = recap.targetMs
                            weeklyTopApps = recap.appBreakdown

                            val dates = LocalDay.daysBetween(weekStartMs, weekStartMs + 7 * MILLIS_PER_DAY, zone)
                            weeklyDays = dates.map { d ->
                                val ms = store.bucketsForRange(deviceId, LocalDay.startOfDayMs(d, zone), LocalDay.endOfDayMs(d, zone))
                                    .filter { it.appKey.value.isNotBlank() }
                                    .sumOf { it.activeMs }
                                DayTotal(d, ms, d == todayStr)
                            }

                            val prevStartMs = weekStartMs - 7 * MILLIS_PER_DAY
                            val prevRecap = RecapEngine.weeklyRecap(store, deviceId, prevStartMs)
                            weeklyComparison = WeekComparison(recap.totalMs, prevRecap.totalMs.ifZero())
                        }
                        "month" -> {
                            val monthStart = LocalDate(todayLocal.year, todayLocal.monthNumber, 1)
                            val monthStartMs = monthStart.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
                            val recap = RecapEngine.monthlyRecap(store, deviceId, monthStartMs)
                            monthlyTotalMs = recap.totalMs
                            monthlyTargetMs = recap.targetMs
                            monthlyMonthLabel = "${monthStart.month.name.lowercase().replaceFirstChar { it.uppercase() }} ${todayLocal.year}"

                            val nextMonth = monthStart.plus(1, DateTimeUnit.MONTH)
                            val monthEndMs = nextMonth.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
                            val dates = LocalDay.daysBetween(monthStartMs, monthEndMs, zone)
                            monthlyDays = dates.map { d ->
                                val ms = store.bucketsForRange(deviceId, LocalDay.startOfDayMs(d, zone), LocalDay.endOfDayMs(d, zone))
                                    .filter { it.appKey.value.isNotBlank() }
                                    .sumOf { it.activeMs }
                                DayTotal(d, ms, d == todayStr)
                            }

                            val dayView = DayView(sessionCategoryEngine())
                            val appTotals = recap.appBreakdown.map { bd ->
                                AppTotal(bd.appKey, nameResolver.resolve(bd.appKey) ?: bd.appKey.value, bd.totalMs)
                            }
                            monthlyCategories = dayView.categoryNames(appTotals).map { (name, ms) -> CategorySlice(name, ms) }

                            val prevMonthStart = monthStart.minus(1, DateTimeUnit.MONTH)
                            val prevRecap = RecapEngine.monthlyRecap(store, deviceId, prevMonthStart.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds())
                            monthlyPreviousMs = prevRecap.totalMs.ifZero()
                        }
                        "year" -> {
                            val yearStart = LocalDate(todayLocal.year, 1, 1)
                            val yearStartMs = yearStart.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
                            val recap = RecapEngine.yearlyRecap(store, deviceId, yearStartMs)
                            yearlyTopApps = recap.appBreakdown

                            val labels = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
                            yearlyMonths = (0 until 12).map { idx ->
                                val mStart = LocalDate(todayLocal.year, idx + 1, 1)
                                val mStartMs = mStart.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
                                val mEndMs = mStart.plus(1, DateTimeUnit.MONTH).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
                                val mMs = store.bucketsForRange(deviceId, mStartMs, mEndMs)
                                    .filter { it.appKey.value.isNotBlank() }
                                    .sumOf { it.activeMs }
                                MonthTotal(idx, labels[idx], mMs)
                            }

                            val prevRecap = RecapEngine.yearlyRecap(store, deviceId, yearStartMs - 365 * MILLIS_PER_DAY)
                            yearlyComparison = YearComparison(recap.totalMs, prevRecap.totalMs.ifZero())

                            val completed = yearlyMonths.filter { it.totalMs > 0 }
                            yearlyMonthlyAverageMs = if (completed.isNotEmpty()) completed.sumOf { it.totalMs } / completed.size else null
                        }
                    }
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

            if (showOnboarding) {
                if (showSyncMessage) {
                    SyncComingSoonContent(
                        targetMs = onboardingTargetMs,
                        onTargetChange = { onboardingTargetMs = it },
                        onDone = {
                            store.setTargetScreentime(deviceId, onboardingTargetMs)
                            showOnboarding = false
                        },
                    )
                } else {
                    OnboardingScreen(
                        onRegister = { _, _, _ -> showSyncMessage = true },
                        onSave = { _, _, _ -> showSyncMessage = true },
                        onSetTarget = { targetMs ->
                            store.setTargetScreentime(deviceId, targetMs)
                            showOnboarding = false
                        },
                        onSkip = { showOnboarding = false },
                    )
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        when (selectedTab) {
                            "today" -> TodayScreen(
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
                            "recaps" -> {
                                Column(
                                    Modifier
                                        .fillMaxSize()
                                        .verticalScroll(rememberScrollState())
                                ) {
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 32.dp, vertical = 16.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        listOf("week" to "Week", "month" to "Month", "year" to "Year").forEach { (key, label) ->
                                            val selected = selectedPeriod == key
                                            Box(
                                                Modifier
                                                    .weight(1f)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(if (selected) LumenTheme.Accent else LumenTheme.Divider)
                                                    .clickable { selectedPeriod = key }
                                                    .padding(vertical = 10.dp),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Text(
                                                    label,
                                                    style = TextStyle(
                                                        color = if (selected) Color.White else LumenTheme.TextPrimary,
                                                        fontSize = 14.sp,
                                                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                                                    ),
                                                )
                                            }
                                        }
                                    }

                                    when (selectedPeriod) {
                                        "week" -> WeeklyRecapScreen(
                                            days = weeklyDays,
                                            comparison = weeklyComparison,
                                            topApps = weeklyTopApps,
                                            targetMs = weeklyTargetMs,
                                            reducedMotion = remember { reducedMotionEnabled() },
                                        )
                                        "month" -> MonthlyRecapScreen(
                                            monthLabel = monthlyMonthLabel,
                                            days = monthlyDays,
                                            totalMs = monthlyTotalMs,
                                            targetMs = monthlyTargetMs,
                                            categories = monthlyCategories,
                                            previousMonthMs = monthlyPreviousMs,
                                        )
                                        "year" -> YearlyRecapScreen(
                                            months = yearlyMonths,
                                            comparison = yearlyComparison,
                                            topApps = yearlyTopApps,
                                            monthlyAverageMs = yearlyMonthlyAverageMs,
                                            reducedMotion = remember { reducedMotionEnabled() },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(LumenTheme.Background)
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        BottomNavItem("Today", selectedTab == "today") { selectedTab = "today" }
                        BottomNavItem("Recaps", selectedTab == "recaps") { selectedTab = "recaps" }
                    }
                }
            }
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

@Composable
private fun SyncComingSoonContent(
    targetMs: Long,
    onTargetChange: (Long) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(LumenTheme.Background)
            .padding(start = 32.dp, end = 32.dp, top = 44.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Sync is coming soon",
            style = TextStyle(
                color = LumenTheme.TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Light,
            ),
        )

        Text(
            "Enjoy local tracking for now. Your data stays on this device " +
                "and syncs automatically when the Android transport lands.",
            style = TextStyle(color = LumenTheme.TextSecondary, fontSize = 13.sp),
        )

        Spacer(Modifier.height(16.dp))

        Text(
            "Set a daily target",
            style = TextStyle(
                color = LumenTheme.TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Light,
            ),
        )

        Text(
            "A quiet reference point. You can change this anytime in settings.",
            style = TextStyle(color = LumenTheme.TextSecondary, fontSize = 13.sp),
        )

        Spacer(Modifier.height(16.dp))

        Text(
            formatDuration(targetMs),
            style = TextStyle(
                color = LumenTheme.TextPrimary,
                fontSize = 40.sp,
                fontWeight = FontWeight.Light,
                fontFamily = LumenTheme.TabularFigures,
                fontFeatureSettings = "tnum",
            ),
        )

        Slider(
            value = targetMs.toFloat(),
            onValueChange = { onTargetChange(it.toLong()) },
            valueRange = 1_800_000f..43_200_000f,
            colors = SliderDefaults.colors(
                thumbColor = LumenTheme.Accent,
                activeTrackColor = LumenTheme.Accent,
                inactiveTrackColor = LumenTheme.Divider,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                formatDuration(1_800_000L),
                style = TextStyle(color = LumenTheme.TextSecondary, fontSize = 11.sp),
            )
            Text(
                formatDuration(43_200_000L),
                style = TextStyle(color = LumenTheme.TextSecondary, fontSize = 11.sp),
            )
        }

        Spacer(Modifier.height(8.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Text(
                "Done",
                style = TextStyle(color = LumenTheme.Accent, fontSize = 14.sp),
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onDone() }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun BottomNavItem(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 10.dp),
        style = TextStyle(
            color = if (selected) LumenTheme.Accent else LumenTheme.TextSecondary,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        ),
    )
}

private fun Long.ifZero(): Long? = if (this > 0) this else null