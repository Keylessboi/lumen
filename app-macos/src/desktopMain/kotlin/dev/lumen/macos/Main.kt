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
import dev.lumen.core.session.FocusSessionTracker
import dev.lumen.macos.startup.LoginItem
import dev.lumen.macos.store.UsageStore
import dev.lumen.macos.ui.LumenTrayIcon
import dev.lumen.core.model.AppTotal
import dev.lumen.core.category.Category
import dev.lumen.core.category.CategoryEngine
import dev.lumen.core.category.GeneratedRegistry
import dev.lumen.core.category.OverrideStore
import dev.lumen.core.model.AppKey
import dev.lumen.core.nudge.BreakReminder
import dev.lumen.core.nudge.NudgeSettings
import dev.lumen.macos.notify.MacNotifier
import dev.lumen.ui.charts.CategorySlice
import dev.lumen.ui.charts.DayDetail
import dev.lumen.ui.charts.DayTotal
import dev.lumen.ui.HistoryState
import dev.lumen.ui.TodayScreen
import dev.lumen.ui.formatDuration
import dev.lumen.ui.liveMsWithinDay
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
    // The tracker's own seq counter restarts at 0 on every launch. That is
    // harmless only because UsageStore.append stamps the store's seq over it
    // — against a raw LumenStore it would collide with the previous session's
    // rows and INSERT OR IGNORE would drop them all without a word.
    val tracker = remember { FocusSessionTracker(deviceId) }
    val collector = remember { LsAppInfoCollector() }

    var totals by remember { mutableStateOf(emptyList<AppTotal>()) }
    var recentDays by remember { mutableStateOf(emptyList<DayTotal>()) }
    var liveApp by remember { mutableStateOf<String?>(null) }
    var liveSinceMs by remember { mutableStateOf(0L) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var currentDay by remember { mutableStateOf(store.today()) }
    var averageMs by remember { mutableStateOf<Long?>(null) }
    var categories by remember { mutableStateOf(emptyList<CategorySlice>()) }

    // The one nudge in v1 (M7). Fed from the same focus stream that feeds
    // tracking, so "at the screen" means the same thing to both.
    val breakReminder = remember {
        BreakReminder(
            NudgeSettings(
                enabled = store.overrides()[NudgeSettings.ENABLED_KEY] != "false",
            ),
        )
    }
    val notifier = remember { MacNotifier() }

    // The user's sticky overrides, read from the store. Registry lookups are
    // the shipped dataset; an override always wins and survives a dataset
    // update — see CategoryEngine.
    val categoryEngine = remember {
        CategoryEngine(
            registry = GeneratedRegistry,
            overrides = object : OverrideStore {
                override fun override(appKey: AppKey): Category? =
                    store.overrides()[appKey.value]?.takeIf { it.isNotBlank() }?.let(Category::fromStored)
                override fun setOverride(appKey: AppKey, category: Category) =
                    store.setOverride(appKey, category.name)
                override fun clearOverride(appKey: AppKey) = store.clearOverride(appKey)
            },
        )
    }

    fun categorySlices(dayTotals: List<AppTotal>): List<CategorySlice> =
        categoryEngine
            .summarize(dayTotals.associate { it.appKey to it.totalMs })
            .map { CategorySlice(it.category.displayName, it.totalMs) }

    /**
     * Attach each app's category to its row, so the UI can colour the row to
     * match the summary strip. Resolved here rather than in the UI because
     * the engine — registry plus the user's sticky overrides — is app state.
     */
    fun withCategories(dayTotals: List<AppTotal>): List<AppTotal> =
        dayTotals.map { it.copy(category = categoryEngine.categoryOf(it.appKey).displayName) }
    var selectedDay by remember { mutableStateOf<String?>(null) }
    var dayDetail by remember { mutableStateOf<DayDetail?>(null) }
    var windowVisible by remember { mutableStateOf(!launchedAtLogin()) }
    var launchAtLogin by remember { mutableStateOf(LoginItem.isEnabled()) }

    // Collection is tied to the application, NOT to the window. The window is
    // a view onto it; hiding the window must not stop tracking.
    LaunchedEffect(Unit) {
        totals = withCategories(store.totalsFor(store.today()))
        recentDays = store.dailyTotals(HISTORY_WINDOW_DAYS)
        averageMs = store.runningDailyAverageMs()
        categories = categorySlices(totals)

        // Imported Screen Time history carries bundle ids only, so a month of
        // recovered usage renders as "com.spotify.client". Resolve real names
        // once for everything in view, then redraw. Done after the first
        // paint rather than before it: a name is worth waiting a moment for,
        // an empty window is not.
        val visible = (recentDays.map { it.dayUtc } + store.today())
            .distinct()
            .flatMap { day -> store.totalsFor(day).map { it.appKey } }
            .distinct()
        store.resolveMissingNames(visible)
        totals = withCategories(store.totalsFor(store.today()))
        categories = categorySlices(totals)
        collector.focusChanges().collect { change ->
            store.rememberName(change.appKey, change.displayName)
            tracker.onChange(change)?.let { closed ->
                store.append(closed)
                totals = withCategories(store.totalsFor(store.today()))
            }
            liveApp = change.displayName ?: change.appKey.value
            liveSinceMs = change.atMs

            // A focus change IS activity. Idle transitions reset the streak,
            // which is what makes this "continuous time" rather than a timer.
            breakReminder.onActivity(change.atMs, isIdle = change.isIdle)?.let(notifier::notify)
        }
    }

    LaunchedEffect(Unit) {
        var lastChartRefreshMs = System.currentTimeMillis()
        while (true) {
            now = System.currentTimeMillis()
            val today = store.today()

            if (today != currentDay) {
                // Midnight, local. Yesterday is complete: it becomes a bar in
                // the trend chart, and the day starts again at zero. The open
                // session is NOT closed — the user is still at the keyboard —
                // but the part of it that belongs to yesterday stops counting
                // toward today; see liveMs below.
                currentDay = today
                totals = withCategories(store.totalsFor(today))
                categories = categorySlices(totals)
                recentDays = store.dailyTotals(HISTORY_WINDOW_DAYS)
                // Yesterday just became a complete day, so it now counts.
                averageMs = store.runningDailyAverageMs()
                selectedDay = null
                dayDetail = null
                lastChartRefreshMs = now
            } else {
                if (totals.isNotEmpty() || liveSinceMs > 0) {
                    totals = withCategories(store.totalsFor(today))
                    categories = categorySlices(totals)
                }
                // Today's own bar should grow during the day, but re-deriving
                // a week of totals every second would read every event file
                // every second. A minute is finer than the chart can show.
                if (now - lastChartRefreshMs >= CHART_REFRESH_MS) {
                    recentDays = store.dailyTotals(HISTORY_WINDOW_DAYS)
                    lastChartRefreshMs = now
                }
            }
            delay(1000)
        }
    }

    // Live time is clamped to the current day. A session open across midnight
    // has already had its pre-midnight portion counted into yesterday, so
    // counting the whole session into today would double it AND make the new
    // day open at however long the user had been sitting there.
    val dayStartMs = remember(currentDay) { store.startOfDayMs(currentDay) }
    val liveMs = liveMsWithinDay(nowMs = now, sessionStartedAtMs = liveSinceMs, dayStartMs = dayStartMs)
    val totalMs = totals.sumOf { it.totalMs } + liveMs

    var history by remember { mutableStateOf(openingHistoryState(store)) }

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
            // A bright system title bar above a near-black app is the single
            // loudest thing wrong with the window: docs/design-spec.md makes
            // dark the default and calls the palette "ink-near-black", and a
            // white strip across the top undoes that before the user reads a
            // number. macOS honours these AWT client properties on the native
            // window — full-size content view with a transparent title bar, so
            // the app's own background runs edge to edge and the traffic
            // lights float on it.
            LaunchedEffect(window) {
                runCatching {
                    window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
                    window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
                    // The title would otherwise draw over our own content.
                    window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
                }
            }
            TodayScreen(
                totals = totals,
                categories = categories,
                recentDays = recentDays,
                averageMs = averageMs,
                selectedDay = selectedDay,
                dayDetail = dayDetail,
                onSelectDay = { day ->
                    if (day == selectedDay) {
                        selectedDay = null
                        dayDetail = null
                    } else {
                        selectedDay = day
                        // Imported history carries bundle ids only; fill in
                        // real names before showing them.
                        store.resolveMissingNames(store.totalsFor(day).map { it.appKey })
                        val dayTotals = store.totalsFor(day)
                        dayDetail = DayDetail(
                            dayUtc = day,
                            totalMs = dayTotals.sumOf { it.totalMs },
                            totals = dayTotals,
                        )
                    }
                },
                onClearDaySelection = {
                    selectedDay = null
                    dayDetail = null
                },
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
                    val now = System.currentTimeMillis()
                    val floor = store.importWatermark().takeIf { it > 0L }
                        ?: (now - DEFAULT_IMPORT_WINDOW_MS)

                    // Two importable ranges, not one. Apple recorded the same
                    // apps Lumen did, so anything Lumen already covered would
                    // double-count — but the periods OUTSIDE that coverage are
                    // exactly what an import is for:
                    //
                    //   [floor, firstEverEvent)  history from before Lumen ran
                    //   [lastEvent, now)         the gap since it last ran
                    //
                    // Only the first range existed before, so a user who quit
                    // Lumen for a week and came back could never recover that
                    // week: `until` was pinned to their first-ever event, so
                    // the range was empty and the UI said "no new history" on
                    // a Mac that had a week of it.
                    val ranges = buildList {
                        val earliest = store.earliestEventMs()
                        val latest = store.latestEventMs()
                        if (earliest == null || latest == null) {
                            add(floor to now)
                        } else {
                            if (floor < earliest) add(floor to earliest)
                            if (latest < now) add(maxOf(latest, floor) to now)
                        }
                    }.filter { (from, to) -> to > from }

                    var seq = store.nextSeq()
                    val importer = KnowledgeCImporter(deviceId)
                    val collected = mutableListOf<dev.lumen.core.model.FocusEvent>()
                    var failure: KnowledgeCImporter.Result? = null
                    for ((from, to) in ranges) {
                        when (val r = importer.import(sinceMs = from, untilMs = to, startSeq = seq)) {
                            is KnowledgeCImporter.Result.Imported -> {
                                collected += r.events
                                seq += r.events.size
                            }
                            // Any non-success is reported as-is rather than
                            // averaged with a partial success: a permission
                            // error must not be hidden by another range
                            // happening to return rows.
                            else -> failure = r
                        }
                    }
                    val result = failure ?: KnowledgeCImporter.Result.Imported(collected)
                    history = when (val r = result) {
                        is KnowledgeCImporter.Result.Imported -> {
                            store.appendImported(r.events)
                            store.resolveMissingNames(r.events.map { it.appKey }.distinct())
                            val today = store.today()
                            totals = withCategories(store.totalsFor(today))
                            // The whole point of an import is the days behind
                            // today, so refresh the trend view too.
                            recentDays = store.dailyTotals(HISTORY_WINDOW_DAYS)
                            if (r.events.isEmpty()) {
                                HistoryState.Message("No new history to import.")
                            } else {
                                // Say where it went. Most of an import lands on
                                // days the Today screen does not show, and
                                // "Imported 319 sessions" next to an unchanged
                                // number reads as a lie.
                                val days = r.events.map { store.dayOf(it.startedAtMs) }.distinct()
                                val onToday = r.events
                                    .filter { store.dayOf(it.startedAtMs) == today }
                                    .sumOf { it.durationMs }
                                val earlier = r.events.sumOf { it.durationMs } - onToday
                                HistoryState.Message(
                                    buildString {
                                        append("Imported ${formatDuration(onToday + earlier)} ")
                                        append("across ${days.size} ")
                                        append(if (days.size == 1) "day" else "days")
                                        append(".")
                                        if (onToday > 0) {
                                            append(" ${formatDuration(onToday)} of it is today")
                                            append(" and is in the numbers above.")
                                        }
                                        if (earlier > 0) {
                                            append(" The rest is earlier days — see Recent days")
                                            append(" below.")
                                        }
                                    },
                                )
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
 * What the banner says when the window first opens.
 *
 * A one-time migration outranks the import offer: the user's history has just
 * moved to a different file on their disk, and a data move nobody is told
 * about is indistinguishable from a data loss they have not noticed yet. It
 * names the archive so "where did my files go" has an answer on screen rather
 * than in a commit message.
 */
private fun openingHistoryState(store: UsageStore): HistoryState {
    val migration = store.migration
    if (migration.ranAtAll) {
        return HistoryState.Message(
            buildString {
                append("Moved ${migration.migratedEvents} recorded sessions into Lumen's database. ")
                if (migration.skippedLines > 0) {
                    // Said out loud, with the count. A migration that quietly
                    // dropped part of someone's history is the failure this
                    // whole path is built to avoid.
                    append("${migration.skippedLines} unreadable ")
                    append(if (migration.skippedLines == 1) "line was" else "lines were")
                    append(" skipped. ")
                }
                append("The original files are kept, unchanged, in ")
                append("~/Library/Application Support/Lumen/migrated.")
                if (migration.filesLeftBehind.isNotEmpty()) {
                    // Half-failed, and the user is the only one who can look
                    // at the directory and see why.
                    append(" ${migration.filesLeftBehind.size} could not be moved there and ")
                    append("are still in the folder above it: ")
                    append(migration.filesLeftBehind.joinToString(", "))
                    append(".")
                }
            },
        )
    }
    return when (FullDiskAccess.status()) {
        FullDiskAccess.Status.Granted ->
            if (store.importWatermark() > 0L) HistoryState.Hidden else HistoryState.Ready
        FullDiskAccess.Status.Denied -> HistoryState.Offer
        FullDiskAccess.Status.Unavailable -> HistoryState.Hidden
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
/** Chart 3 in docs/design-spec.md is "7/30-day bars"; 7 is the calm default. */
private const val HISTORY_WINDOW_DAYS = 7

/**
 * How often the trend chart re-derives. A second would re-read every event
 * file every second; a minute is already finer than a bar can show.
 */
private const val CHART_REFRESH_MS = 60_000L

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
