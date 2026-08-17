package dev.lumen.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.launch
import dev.lumen.app.collector.HyprlandCollector
import dev.lumen.app.names.DesktopEntryNameResolver
import dev.lumen.core.clock.LocalDay
import dev.lumen.core.clock.UtcDay
import kotlinx.datetime.TimeZone
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
import dev.lumen.transport.XmppTransport
import dev.lumen.transport.providers.Providers
import dev.lumen.ui.AccountProviderOption
import dev.lumen.ui.AccountSection
import dev.lumen.ui.AccountUiState
import dev.lumen.ui.LumenTheme
import dev.lumen.ui.TodayScreen
import dev.lumen.ui.charts.CategorySlice
import dev.lumen.ui.charts.DayDetail
import dev.lumen.ui.charts.DayTotal
import kotlinx.coroutines.delay
import java.io.File
import dev.lumen.core.rollup.RecapEngine
import dev.lumen.core.model.RecapSummary
import dev.lumen.ui.charts.WeeklyRecapScreen
import dev.lumen.ui.charts.MonthlyRecapScreen
import dev.lumen.ui.charts.YearlyRecapScreen
import dev.lumen.ui.charts.MonthTotal
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.plus

/**
 * Lumen for Linux.
 *
 * Uses the SHARED `:ui` TodayScreen and persists through [JvmLumenStore]
 * (SQLite). The collector seam reports focus transitions; [FocusSessionTracker]
 * derives durations; [RollupEngine] buckets and rolls them up; the store
 * survives restarts.
 */
fun main(args: Array<String>) = runApp()

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
        // Seed the seq counter from the store: FocusSessionTracker numbers
        // from 0 on every launch, and the (device_id, seq) PK drops colliding
        // inserts silently (INSERT OR IGNORE). Without this a restart makes
        // the day stop growing with no error.
        val tracker = remember { FocusSessionTracker(deviceId, store.lastEventSeq(deviceId) + 1) }
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
        var selectedTab by remember { mutableStateOf("Today") }
        var selectedPeriod by remember { mutableStateOf("Week") }
        var recapSummary by remember { mutableStateOf<RecapSummary?>(null) }

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

        fun localDayTotals(zone: TimeZone, dayLocal: String): List<Pair<AppKey, Long>> {
            val dayStart = LocalDay.startOfDayMs(dayLocal, zone)
            val dayEnd = LocalDay.endOfDayMs(dayLocal, zone)
            return store.bucketsForRange(deviceId, dayStart, dayEnd)
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

        fun dailyTotalsForRange(startMs: Long, endMs: Long): List<DayTotal> {
            val zone = displayZone()
            val tz = TimeZone.UTC
            val startDate = Instant.fromEpochMilliseconds(startMs).toLocalDateTime(tz).date
            val endDate = Instant.fromEpochMilliseconds(endMs).toLocalDateTime(tz).date
            val today = LocalDay.today(zone)

            val days = mutableListOf<DayTotal>()
            var current = startDate
            while (current <= endDate) {
                val dayStr = current.toString()
                val dayStart = LocalDay.startOfDayMs(dayStr, zone)
                val dayEnd = LocalDay.endOfDayMs(dayStr, zone)
                val totalMs = store.bucketsForRange(deviceId, dayStart, dayEnd)
                    .sumOf { it.activeMs }
                days.add(DayTotal(dayUtc = dayStr, totalMs = totalMs, isToday = dayStr == today))
                current = current.plus(1, DateTimeUnit.DAY)
            }
            return days
        }

        fun monthlyTotalsForYear(year: Int): List<MonthTotal> {
            val monthNames = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun",
                "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
            val tz = TimeZone.UTC

            return (1..12).map { month ->
                val monthStart = LocalDate(year, month, 1)
                val monthEnd = monthStart.plus(1, DateTimeUnit.MONTH)
                val monthStartMs = monthStart.atStartOfDayIn(tz).toEpochMilliseconds()
                val monthEndMs = monthEnd.atStartOfDayIn(tz).toEpochMilliseconds()

                val totalMs = store.bucketsForRange(deviceId, monthStartMs, monthEndMs)
                    .sumOf { it.activeMs }

                MonthTotal(monthIndex = month - 1, label = monthNames[month - 1], totalMs = totalMs)
            }
        }

        fun loadRecap() {
            recapSummary = RecapEngine.fullRecap(store, deviceId, System.currentTimeMillis())
        }

        LaunchedEffect(Unit) {
            render()
            loadHistory()
            loadRecap()
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

        // Periodic sync (M4): same loop as the headless tracker, so the
        // windowed app also pushes/pulls when an account is configured.
        // 'Sync additive, never a dependency' — unconfigured = no loop.
        LaunchedEffect(Unit) {
            val syncManager = SyncManager(store, deviceId)
            while (true) {
                delay(SYNC_INTERVAL_MS)
                if (!syncManager.isConfigured()) continue
                runCatching {
                    val report = syncManager.syncOnce()
                    if (report.integrityWarnings.isNotEmpty()) {
                        println("lumen: sync integrity warnings: ${report.integrityWarnings}")
                    }
                }.onFailure { e ->
                    println("lumen: sync failed: ${e.message}")
                }
            }
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
                    loadRecap()
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

        // ---- account / sync surface (M4) ----
        val syncManager = remember { SyncManager(store, deviceId) }
        var accountState by remember {
            mutableStateOf<AccountUiState>(
                if (syncManager.isConfigured()) {
                    val cfg = syncManager.account()
                    AccountUiState.Connected(cfg?.jid ?: "", cfg?.host ?: "")
                } else {
                    AccountUiState.Unconfigured
                },
            )
        }

        val providerOptions = remember {
            Providers.all.map { AccountProviderOption(jid = it.jid, tier = it.tier) }
        }
        val accountScope = rememberCoroutineScope()

        fun configure(providerJid: String, username: String, password: String) {
            accountState = AccountUiState.Working("Connecting to $providerJid\u2026")
            val parts = providerJid.split("@")
            val host = parts.lastOrNull() ?: providerJid
            syncManager.saveAccount(
                AccountConfig(
                    host = host,
                    port = 5222,
                    jid = "$username@$host",
                    password = password,
                ),
            )
            accountState = AccountUiState.Connected("$username@$host", host)
        }

        fun register(providerJid: String, username: String, password: String) {
            accountState = AccountUiState.Working("Creating account on $providerJid\u2026")
            val parts = providerJid.split("@")
            val host = parts.lastOrNull() ?: providerJid
            val jid = "$username@$host"
            accountScope.launch {
                runCatching {
                    val xmpp = XmppTransport(host = host, port = 5222, jid = jid, password = password)
                    xmpp.register(username, password)
                }.onSuccess {
                    configure(providerJid, username, password)
                }.onFailure { e ->
                    accountState = AccountUiState.Failed("Registration failed: ${e.message ?: e::class.simpleName}")
                }
            }
        }

        Row {
            listOf("Today", "Recaps", "Sync").forEach { label ->
                val selected = label == selectedTab
                Text(
                    label,
                    style = TextStyle(
                        color = if (selected) LumenTheme.TextPrimary else LumenTheme.TextSecondary,
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    ),
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .clickable { selectedTab = label },
                )
            }
        }

        when (selectedTab) {
            "Sync" -> AccountSection(
                providers = providerOptions,
                state = accountState,
                onRegister = ::register,
                onSave = ::configure,
                onDisconnect = {
                    syncManager.clearAccount()
                    accountState = AccountUiState.Unconfigured
                },
            )
            "Recaps" -> {
                Row(Modifier.padding(start = 32.dp, top = 16.dp)) {
                    listOf("Week", "Month", "Year").forEach { period ->
                        val selected = period == selectedPeriod
                        Text(
                            period,
                            style = TextStyle(
                                color = if (selected) LumenTheme.TextPrimary else LumenTheme.TextSecondary,
                                fontSize = 13.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            ),
                            modifier = Modifier
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .clickable { selectedPeriod = period },
                        )
                    }
                }

                val summary = recapSummary
                if (summary != null) {
                    when (selectedPeriod) {
                        "Week" -> summary.week?.let { period ->
                            val days = dailyTotalsForRange(period.startMs, period.endMs)
                            WeeklyRecapScreen(
                                days = days,
                                topApps = period.appBreakdown,
                                averageMs = averageMs,
                                targetMs = period.targetMs,
                                reducedMotion = reducedMotion,
                            )
                        }
                        "Month" -> summary.month?.let { period ->
                            val days = dailyTotalsForRange(period.startMs, period.endMs)
                            val monthDate = Instant.fromEpochMilliseconds(period.startMs)
                                .toLocalDateTime(TimeZone.UTC).date
                            val monthLabel = monthDate.month.name.lowercase()
                                .replaceFirstChar { it.uppercase() } + " ${monthDate.year}"
                            MonthlyRecapScreen(
                                monthLabel = monthLabel,
                                days = days,
                                totalMs = period.totalMs,
                                targetMs = period.targetMs,
                                categories = emptyList(),
                                previousMonthMs = null,
                            )
                        }
                        "Year" -> summary.year?.let { period ->
                            val yearDate = Instant.fromEpochMilliseconds(period.startMs)
                                .toLocalDateTime(TimeZone.UTC).date
                            val months = monthlyTotalsForYear(yearDate.year)
                            val monthlyAvg = if (months.isNotEmpty()) {
                                months.sumOf { it.totalMs } / months.size
                            } else null
                            YearlyRecapScreen(
                                months = months,
                                topApps = period.appBreakdown,
                                monthlyAverageMs = monthlyAvg,
                                reducedMotion = reducedMotion,
                            )
                        }
                    }
                } else {
                    Text(
                        "Loading recaps...",
                        modifier = Modifier.padding(32.dp),
                        style = TextStyle(
                            color = LumenTheme.TextSecondary,
                            fontSize = 13.sp,
                        ),
                    )
                }
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
            )
        }
    }
}

private const val SYNC_INTERVAL_MS = 5L * 60 * 1000 // every 5 minutes, matching the headless tracker

private fun openStore(): JvmLumenStore {
    val dataDir = File(System.getProperty("user.home"), ".local/share/lumen")
    return JvmLumenStore.open(File(dataDir, "lumen.db"))}

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
    // A fresh device has nothing acked — the watermark must be -1, not the
    // store's 0 default, or the first event (seq 0) is excluded from the
    // outbox by `eventsAfter(seq > watermark)` and never syncs.
    store.setAckedSeq(id, -1)
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
