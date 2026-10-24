package dev.lumen.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import kotlinx.coroutines.launch
import dev.lumen.ui.LumenTheme
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.lumen.app.names.PackageManagerNameResolver
import dev.lumen.core.category.DayView
import dev.lumen.core.category.sessionCategoryEngine
import dev.lumen.core.clock.LocalDay
import dev.lumen.core.collector.AppNameResolver
import dev.lumen.core.collector.PermissionState
import dev.lumen.core.model.AppTotal
import dev.lumen.core.model.DeviceId
import dev.lumen.ui.AccountSection
import dev.lumen.ui.BodyText
import dev.lumen.ui.DeviceScope
import dev.lumen.ui.LumenAppShell
import dev.lumen.ui.PrimaryButton
import dev.lumen.ui.ScrobbleSection
import dev.lumen.ui.SettingsCard
import dev.lumen.ui.SettingsPage
import dev.lumen.ui.ShellTab
import dev.lumen.ui.TodayScreen
import dev.lumen.ui.charts.CategorySlice
import dev.lumen.ui.charts.DayDetail
import dev.lumen.ui.charts.DayTotal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone

/**
 * Lumen for Android.
 *
 * The SHARED `:ui` screens inside [LumenAppShell] — Today, Sync, Scrobbling —
 * the same composables Linux draws. The work itself lives in [LumenRuntime]
 * so it also runs in the background ([SyncWorker], the media listener); this
 * Activity imports new usage while it is visible and renders the store.
 */
class MainActivity : ComponentActivity() {

    /**
     * Whether the system asks for reduced motion — the value Android's own
     * "Remove animations" toggle writes. `docs/design-spec.md` requires it.
     */
    private fun reducedMotionEnabled(): Boolean = runCatching {
        android.provider.Settings.Global.getFloat(
            contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }.getOrDefault(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        // targetSdk 35 is edge to edge regardless. The UI is always dark, so the
        // bar icons must always be light — the default follows the SYSTEM
        // theme and drew dark icons on the dark background in light mode.
        // The shell pads for the bars.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val nameResolver: AppNameResolver = PackageManagerNameResolver(applicationContext)
        val reducedMotion = reducedMotionEnabled()

        setContent {
            // The runtime opens the database and the Keystore: never on the
            // main thread (that was this screen's ANR). A plain background
            // frame shows for the few milliseconds it takes.
            var runtime by remember { mutableStateOf(LumenRuntime.peek()) }
            LaunchedEffect(Unit) {
                if (runtime == null) runtime = withContext(Dispatchers.IO) { LumenRuntime.get(applicationContext) }
            }
            val ready = runtime
            if (ready == null) {
                Box(Modifier.fillMaxSize().background(LumenTheme.Background))
            } else {
                LumenScreen(ready, nameResolver, reducedMotion)
            }
        }
    }

    @Composable
    private fun LumenScreen(runtime: LumenRuntime, nameResolver: AppNameResolver, reducedMotion: Boolean) {
        val store = runtime.store
        val deviceId = runtime.deviceId
        var tab by remember { mutableStateOf("today") }
        var allDevices by remember { mutableStateOf(false) }
        var dataVersion by remember { mutableIntStateOf(0) }
        var permission by remember { mutableStateOf(runtime.collector.permissionState()) }
        var totals by remember { mutableStateOf(emptyList<AppTotal>()) }
        var storedTotalMs by remember { mutableStateOf(0L) }
        var storedTotals by remember { mutableStateOf(emptyList<AppTotal>()) }
        var categories by remember { mutableStateOf(emptyList<CategorySlice>()) }
        var recentDays by remember { mutableStateOf(emptyList<DayTotal>()) }
        var averageMs by remember { mutableStateOf<Long?>(null) }
        var selectedDay by remember { mutableStateOf<String?>(null) }
        var dayDetail by remember { mutableStateOf<DayDetail?>(null) }
        var open by remember { mutableStateOf<UsageImporter.Open?>(null) }
        var now by remember { mutableStateOf(System.currentTimeMillis()) }
        val dayView = remember { DayView(sessionCategoryEngine()) }
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val uiScope = rememberCoroutineScope()

        fun displayZone(): TimeZone =
            LocalDay.zoneOf(store.setting(LocalDay.SETTING_KEY)?.value?.toString(Charsets.UTF_8))

        fun devicesInScope(): List<DeviceId> =
            if (allDevices) listOf(deviceId) + runtime.sync.peerDevices() else listOf(deviceId)

        /** Per-app totals for one local day, idle (blank key) excluded. */
        fun dayTotals(dayLocal: String, zone: TimeZone): List<AppTotal> {
            val start = LocalDay.startOfDayMs(dayLocal, zone)
            val end = LocalDay.endOfDayMs(dayLocal, zone)
            return devicesInScope().flatMap { store.bucketsForRange(it, start, end) }
                .filter { it.appKey.value.isNotBlank() }
                .groupBy { it.appKey }
                .map { (key, buckets) ->
                    AppTotal(key, nameResolver.resolve(key) ?: key.value, buckets.sumOf { it.activeMs })
                }
                .sortedByDescending { it.totalMs }
        }

        // All DB reads on Dispatchers.IO — a main-thread read blocked on the
        // SQLite lock is the ANR this screen used to hit.
        suspend fun render() {
            val snapshot = withContext(Dispatchers.IO) {
                val zone = displayZone()
                val today = LocalDay.today(zone)
                val list = dayTotals(today, zone)
                val windowStart = LocalDay.startOfDayMs(today, zone) - (HISTORY_WINDOW_DAYS - 1) * MILLIS_PER_DAY
                val days = LocalDay.daysBetween(windowStart, LocalDay.startOfDayMs(today, zone), zone).map { d ->
                    DayTotal(dayUtc = d, totalMs = dayTotals(d, zone).sumOf { it.totalMs }, isToday = d == today)
                }
                Triple(list, days, runtime.sync.peerDevices().isNotEmpty())
            }
            storedTotals = snapshot.first
            storedTotalMs = snapshot.first.sumOf { it.totalMs }
            totals = dayView.rows(snapshot.first)
            categories = dayView.categoryNames(snapshot.first).map { (name, ms) -> CategorySlice(name, ms) }
            recentDays = snapshot.second
            val complete = snapshot.second.filter { !it.isToday && it.totalMs > 0 }
            averageMs = if (complete.isNotEmpty()) complete.sumOf { it.totalMs } / complete.size else null
        }

        /** The open session is live time: on this device only, never idle. */
        fun liveMs(): Long {
            val session = open ?: return 0
            if (session.idle || session.appKey.value.isBlank()) return 0
            return (now - session.sinceMs).coerceAtLeast(0)
        }

        fun refreshTotals() {
            val base = storedTotals.associate { it.appKey to it.totalMs }.toMutableMap()
            val session = open
            if (session != null && liveMs() > 0) base.merge(session.appKey, liveMs(), Long::plus)
            val rows = base.entries
                .filter { it.key.value.isNotBlank() }
                .sortedByDescending { it.value }
                .map { (key, ms) -> AppTotal(key, nameResolver.resolve(key) ?: key.value, ms) }
            totals = dayView.rows(rows)
            // The strip is the same rows grouped; without this it lagged the
            // big number by the live session until the next import.
            categories = dayView.categoryNames(rows).map { (name, ms) -> CategorySlice(name, ms) }
        }

        // While visible: re-check Usage Access (the user may have just
        // granted it in Settings — it used to be read once and never
        // again, so the app kept asking until restarted), import, render.
        LaunchedEffect(Unit) {
            runtime.sync.onRemoteData = { dataVersion++ }
            runtime.sync.startLoop()
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                runtime.refreshMediaAccess()
                while (true) {
                    // Both accesses are granted in system Settings, which can
                    // happen while Lumen stays on screen (split screen, a
                    // quick-settings tile): re-read them on every pass.
                    runtime.refreshMediaAccess()
                    permission = runtime.collector.permissionState()
                    if (permission is PermissionState.Granted) {
                        val result = runtime.importer.importNew()
                        open = result.open
                        if (result.imported > 0 || storedTotals.isEmpty()) render()
                    }
                    delay(IMPORT_POLL_MS)
                }
            }
        }

        LaunchedEffect(dataVersion, allDevices) { render() }

        LaunchedEffect(Unit) {
            while (true) {
                now = System.currentTimeMillis()
                refreshTotals()
                delay(1000)
            }
        }

        val accountState by runtime.sync.state.collectAsState()
        val scrobbleState by runtime.scrobble.state.collectAsState()
        var hasPeers by remember { mutableStateOf(false) }
        LaunchedEffect(dataVersion, accountState) {
            hasPeers = withContext(Dispatchers.IO) { runtime.sync.peerDevices().isNotEmpty() }
        }

        BackHandler(enabled = tab != "today") { tab = "today" }

        LumenAppShell(
            tabs = listOf(ShellTab("today", "Today"), ShellTab("sync", "Sync"), ShellTab("scrobble", "Scrobbling")),
            selectedId = tab,
            onSelect = { tab = it },
        ) {
            when (tab) {
                "sync" -> SettingsPage {
                    AccountSection(
                        providers = runtime.sync.providers,
                        state = accountState,
                        onRegister = runtime.sync::register,
                        onSave = runtime.sync::signIn,
                        onDisconnect = runtime.sync::disconnect,
                        onAnswerChallenge = runtime.sync::answerChallenge,
                        onCancel = runtime.sync::cancelSignUp,
                        onSyncNow = runtime.sync::syncNow,
                        onForgetDevice = runtime.sync::forgetDevice,
                        onOpenUrl = runtime::openUrl,
                    )
                }
                "scrobble" -> SettingsPage {
                    ScrobbleSection(
                        state = scrobbleState,
                        onSignIn = runtime.scrobble::signIn,
                        onCancelSignIn = runtime.scrobble::cancelSignIn,
                        onSubmitToken = runtime.scrobble::submitToken,
                        onSignOut = runtime.scrobble::signOut,
                        onOpenUrl = runtime::openUrl,
                        onSourceAction = runtime::openNotificationAccessSettings,
                        onIncludeBrowsers = runtime.scrobble::setIncludeBrowsers,
                        onSendNow = runtime.scrobble::sendNow,
                    )
                }
                else -> Column {
                    val missing = permission as? PermissionState.Required
                    if (missing != null) {
                        Column(Modifier.padding(horizontal = 20.dp).padding(top = 16.dp)) {
                            SettingsCard {
                                BodyText(missing.rationale)
                                BodyText(missing.settingsHint, secondary = true)
                                PrimaryButton("Open Usage Access settings", runtime::openUsageAccessSettings)
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                    TodayScreen(
                        totals = totals,
                        totalMs = storedTotalMs + liveMs(),
                        liveApp = null,
                        showLiveApp = false,
                        reducedMotion = reducedMotion,
                        topPadding = 20.dp,
                        categories = categories,
                        recentDays = recentDays,
                        averageMs = averageMs,
                        selectedDay = selectedDay,
                        dayDetail = dayDetail,
                        deviceScope = if (hasPeers) DeviceScope(allDevices) { allDevices = it } else null,
                        onSelectDay = { dayLocal ->
                            if (dayLocal == selectedDay) {
                                selectedDay = null
                                dayDetail = null
                            } else {
                                selectedDay = dayLocal
                                val rows = dayTotals(dayLocal, displayZone())
                                dayDetail = DayDetail(dayUtc = dayLocal, totalMs = rows.sumOf { it.totalMs }, totals = rows)
                            }
                        },
                        onClearDaySelection = {
                            selectedDay = null
                            dayDetail = null
                        },
                    )
                }
            }
        }
    }

    companion object {
        /** Trend chart window, matching the Linux app's HISTORY_WINDOW_DAYS. */
        private const val HISTORY_WINDOW_DAYS = 7
        private const val MILLIS_PER_DAY = 86_400_000L

        /** How often to read new usage from the OS while the app is visible. */
        private const val IMPORT_POLL_MS = 5_000L
    }
}
