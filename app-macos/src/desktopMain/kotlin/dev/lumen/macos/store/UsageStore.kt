package dev.lumen.macos.store

import dev.lumen.core.clock.LocalDay
import dev.lumen.macos.collector.MacSystemUi
import dev.lumen.core.export.ExportPayload
import dev.lumen.core.export.ExportedDeviceKey
import dev.lumen.core.model.AppDayRollup
import dev.lumen.core.model.Setting
import dev.lumen.core.collector.AppNameResolver
import dev.lumen.macos.keychain.MacosKeychain
import dev.lumen.macos.names.SpotlightNameResolver
import kotlinx.datetime.TimeZone
import dev.lumen.core.model.AppKey
import dev.lumen.core.model.AppTotal
import dev.lumen.core.store.JvmLumenStore
import dev.lumen.core.store.LumenStore
import dev.lumen.ui.charts.DayTotal
import dev.lumen.core.model.DeviceId
import dev.lumen.core.model.FocusEvent
import dev.lumen.core.rollup.RollupEngine
import java.io.File
import java.util.UUID

/**
 * Local-only usage store for the macOS app.
 *
 * Events live in [LumenStore] — the frozen storage seam, SQLite underneath,
 * the same one `app-linux` writes to. `app-macos` shipped ahead of that seam
 * and kept its own append-only NDJSON files; [NdjsonMigration] moves them
 * across on first open and archives the originals. See [migration].
 *
 * Per-app day totals are still DERIVED on read rather than stored:
 * `RollupEngine`'s contract says buckets and rollups are derived, never
 * authoritative, and deriving from the events means the read filters
 * ([MacSystemUi]) and the display timezone can change without a rewrite of
 * anything already recorded.
 *
 * ## Events are the record here, so they are not pruned
 *
 * `docs/data-model.md` prunes events at ~30 days on the assumption that
 * rollups carry the long history. On macOS the display derives from events,
 * so pruning them would delete the visible past — including the month of
 * imported Screen Time history this migration exists to protect. Nothing in
 * `app-macos` calls [LumenStore.pruneEvents], and nothing should until the
 * rollup tables are populated and read.
 *
 * Bucket-to-day assignment goes through the local-day window on each bucket's
 * timestamp, not the event's start, so a session spanning midnight splits
 * across both days.
 */
class UsageStore(
    private val root: File = defaultRoot(),
    /**
     * Turns app ids into human names. Platform-specific by necessity —
     * Spotlight here, desktop entries on Linux, PackageManager on Android —
     * behind one seam in core so the *gap* is only solved once.
     */
    private val nameResolver: AppNameResolver = SpotlightNameResolver(),
    /**
     * The durable record. Defaults to the SQLite database beside the app's
     * other data; injectable so a test can hand in an in-memory one.
     */
    private val store: LumenStore = JvmLumenStore.open(File(root, DB_NAME)),
    /**
     * The device's sync identity, for an export to carry. Lazy by
     * construction — nothing here touches the login keychain until an export
     * actually asks for a key.
     */
    private val keychain: MacosKeychain = MacosKeychain(),
) {

    // Everything the init block touches is declared ABOVE it. A `by lazy`
    // declared below is still a null delegate field while init runs, and
    // reading it there throws — which it did.

    /**
     * Stable per-install device identity, created once.
     *
     * Held rather than re-read: it is now stamped onto every event, and the
     * migration depends on it being the SAME id the NDJSON was written under
     * — a different one and the migrated rows belong to a device that never
     * existed and join to nothing.
     */
    private val device: DeviceId by lazy {
        val f = File(root, "device-id")
        if (f.exists()) {
            f.readText().trim().takeIf { it.isNotEmpty() }?.let { return@lazy DeviceId(it) }
        }
        val id = UUID.randomUUID().toString()
        f.writeText(id)
        DeviceId(id)
    }

    /**
     * Every event this device holds.
     *
     * Read once and kept, because every display number is derived from the
     * events and the screen re-derives about once a second. Lazy, so it loads
     * after the migration has run and a migrated event is in here from the
     * first render.
     */
    private val events: MutableList<FocusEvent> by lazy {
        store.eventsAfter(device, -1L).toMutableList()
    }

    /** One past the highest seq in the store. See [append]. */
    private var seqCursor: Long = -1L

    /**
     * What the one-time NDJSON migration did, for the app to report.
     *
     * Runs at open rather than on demand: a migration nobody remembers to
     * call leaves a month of someone's history sitting in files nothing reads,
     * and the app comes up empty with no error anywhere.
     */
    val migration: NdjsonMigration.Report

    init {
        root.mkdirs()
        migration = NdjsonMigration.run(root, store, device)
    }

    fun deviceId(): DeviceId = device

    /**
     * Newest event timestamp already imported from the Knowledge store, or 0
     * if history has never been imported.
     *
     * The import is idempotent through this watermark: re-running it only
     * pulls events newer than the last one taken. Without it, a second import
     * would double every app's history.
     */
    fun importWatermark(): Long =
        File(root, "import-watermark").takeIf { it.exists() }
            ?.readText()?.trim()?.toLongOrNull() ?: 0L

    fun setImportWatermark(atMs: Long) {
        File(root, "import-watermark").writeText(atMs.toString())
    }

    /**
     * The start of Lumen's own coverage — the earliest event it recorded
     * itself — or null when it has recorded nothing yet.
     *
     * This is the boundary the Screen Time import must not cross. Apple has
     * been recording the same apps all along, so re-importing a period Lumen
     * already tracked counts it twice and inflates every number for that day.
     * Before this point is history Lumen genuinely missed; after it is a
     * duplicate.
     */
    fun earliestEventMs(): Long? = events.minOfOrNull { it.startedAtMs }

    /**
     * The end of Lumen's own coverage — the last moment it recorded — or null
     * when it has recorded nothing.
     *
     * Pairs with [earliestEventMs] to bound the two importable ranges: before
     * Lumen ever ran, and since it last ran.
     */
    fun latestEventMs(): Long? = events.maxOfOrNull { it.startedAtMs + it.durationMs }

    /**
     * One past the highest seq the store holds.
     *
     * `(device_id, seq)` is the primary key and `insertEvent` is
     * `INSERT OR IGNORE`, so a colliding seq is not an error — the row is
     * silently discarded. That is exactly what a fresh
     * `FocusSessionTracker(deviceId)` produced on every launch: its counter
     * restarts at 0, and the real NDJSON on this Mac shows it, seq running
     * 12, 13, 14, then 0, 1 where the app was restarted. Under NDJSON nothing
     * read seq so it cost nothing; against the store it would have dropped
     * every event of every session after the first, invisibly.
     *
     * So [append] stamps the seq itself rather than trusting its caller. This
     * is still exposed because the Screen Time importer wants a starting
     * point for a batch it builds before handing it over.
     */
    fun nextSeq(): Long {
        if (seqCursor < 0L) seqCursor = (events.maxOfOrNull { it.seq } ?: -1L) + 1L
        return seqCursor
    }

    /** Append a batch, advancing the import watermark to the newest event taken. */
    fun appendImported(imported: List<FocusEvent>) {
        if (imported.isEmpty()) return
        imported.forEach(::append)
        setImportWatermark(imported.maxOf { it.startedAtMs + it.durationMs })
    }

    /**
     * Append a closed session.
     *
     * The seq and the device are stamped here, at the single point where an
     * event enters the store, because they are properties of *this store's*
     * sequence rather than of whoever built the event. See [nextSeq].
     */
    fun append(event: FocusEvent) {
        val stamped = event.copy(seq = nextSeq(), deviceId = device)
        seqCursor++
        store.insertEvent(stamped)
        events += stamped
    }

    /**
     * Fill in names for apps we have time for but no name.
     *
     * The live collector learns a name when it sees an app in the
     * foreground, but imported Screen Time history carries bundle ids only —
     * so a month of recovered history rendered as `com.spotify.client`
     * instead of `Spotify`. Spotlight already knows the mapping.
     *
     * Resolved once per unknown id and cached in the same append-only file
     * the collector writes, so this is not a per-render cost. Ids that
     * resolve to nothing (uninstalled apps, Apple daemons) are left alone
     * rather than cached as a guess.
     */
    fun resolveMissingNames(appKeys: Collection<AppKey>) {
        val known = names()
        appKeys
            .map { it.value }
            .filter { it.isNotBlank() && it !in known }
            .distinct()
            .forEach { bundleId ->
                nameResolver.resolve(AppKey(bundleId))?.let { rememberName(AppKey(bundleId), it) }
            }
    }

    /**
     * The user's sticky category overrides, one per line as
     * `app_key<TAB>Category`.
     *
     * Append-only like the name cache: the last line for a key wins, so a
     * change is a write rather than a rewrite, and a crash mid-write costs at
     * most the line being appended.
     */
    fun overrides(): Map<String, String> {
        val f = File(root, "category-overrides.tsv")
        if (!f.exists()) return emptyMap()
        return f.readLines()
            .mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size == 2 && parts[0].isNotBlank()) parts[0] to parts[1] else null
            }
            .toMap()
    }

    fun setOverride(appKey: AppKey, categoryName: String) {
        File(root, "category-overrides.tsv").appendText("${appKey.value}\t$categoryName\n")
    }

    fun clearOverride(appKey: AppKey) {
        // Written as an explicit tombstone rather than by rewriting the file:
        // "" is not a category name, so it reads back as absent.
        File(root, "category-overrides.tsv").appendText("${appKey.value}\t\n")
    }

    /**
     * Everything this device holds, as an export payload (M5).
     *
     * Rollups are derived from the stored events rather than read from the
     * rollup table, because nothing on macOS writes that table yet — the
     * events ARE the record here. When the rollup tables are populated the
     * source changes and the shape does not.
     *
     * deviceKeys carries this device's X25519 identity from the login
     * keychain, so a restore resumes as the same device rather than joining
     * as a stranger. When the keychain cannot be reached it stays EMPTY
     * rather than carrying an invented key: an empty list makes a restore
     * visibly incomplete, a placeholder makes it look complete and is not.
     */
    fun exportPayload(zone: TimeZone = displayZone()): ExportPayload {
        val rollups = recordedDays(zone).flatMap { day ->
            totalsFor(day, zone).map { total ->
                AppDayRollup(
                    deviceId = deviceId(),
                    dayUtc = day,
                    appKey = total.appKey,
                    totalMs = total.totalMs,
                )
            }
        }
        val settings = overrides()
            .filterValues { it.isNotBlank() }
            .map { (key, value) ->
                Setting(
                    key = "category.override.$key",
                    value = value.encodeToByteArray(),
                    updatedAtMs = 0L,
                    updatedDayUtc = LocalDay.today(zone),
                    deviceId = deviceId(),
                )
            }
        return ExportPayload(
            rollups = rollups,
            settings = settings,
            deviceKeys = keychain.deviceKeyPairOrNull()?.let { keys ->
                listOf(
                    ExportedDeviceKey(
                        deviceId = device,
                        displayName = deviceDisplayName(),
                        publicKey = keys.publicKey,
                        privateKey = keys.privateKeyHandle,
                    ),
                )
            } ?: emptyList(),
        )
    }

    /**
     * What this Mac is called, for a restore screen to name the device a
     * backup came from. Falls back rather than failing: a backup must not
     * depend on a subprocess answering.
     */
    private fun deviceDisplayName(): String = runCatching {
        val process = ProcessBuilder("/usr/sbin/scutil", "--get", "ComputerName").start()
        val name = process.inputStream.bufferedReader().readText().trim()
        process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
        name.takeIf { it.isNotBlank() }
    }.getOrNull() ?: "This Mac"

    /** Remember a human-facing app name. Display only, never synced. */
    fun rememberName(appKey: AppKey, displayName: String?) {
        // Our own dev process reports its main class ("MainKt"). Override at
        // the single point every name enters the store, so the live collector
        // and the import path cannot disagree about what Lumen is called.
        val resolved = nameResolver.resolve(appKey) ?: displayName
        return rememberResolvedName(appKey, resolved)
    }

    private fun rememberResolvedName(appKey: AppKey, displayName: String?) {
        if (displayName.isNullOrBlank()) return
        val f = File(root, "app-names.tsv")
        val existing = names()
        if (existing[appKey.value] == displayName) return
        f.appendText("${appKey.value}\t$displayName\n")
    }

    fun names(): Map<String, String> {
        val f = File(root, "app-names.tsv")
        if (!f.exists()) return emptyMap()
        // Later lines win — the file is append-only, so the last write for a
        // key is the current name.
        return f.readLines()
            .mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size == 2 && parts[0].isNotBlank()) parts[0] to parts[1] else null
            }
            .toMap()
    }

    /**
     * The display timezone: the zone whose midnight defines a Lumen day.
     *
     * Stored rather than read from the OS so that every device agrees on the
     * boundary — see `LocalDay` and discussion #29. Defaults to this Mac's
     * zone on first run.
     */
    fun displayZone(): TimeZone =
        LocalDay.zoneOf(File(root, "display-timezone").takeIf { it.exists() }?.readText()?.trim())

    fun setDisplayZone(zoneId: String) {
        File(root, "display-timezone").writeText(zoneId)
    }

    /**
     * Per-app totals for a LOCAL day — the day the user actually lived.
     *
     * Bucketing to the minute first means an event spanning local midnight is
     * split across the two days rather than assigned wholesale to one, which
     * is the same rule the UTC path used.
     */
    fun totalsFor(dayLocal: String, zone: TimeZone = displayZone()): List<AppTotal> {
        val startMs = LocalDay.startOfDayMs(dayLocal, zone)
        val endMs = LocalDay.endOfDayMs(dayLocal, zone)

        val byApp = mutableMapOf<String, Long>()
        for (e in events) {
            // A session that began before this day can still run into it, and
            // one that began inside it can run past the end — so the test is
            // overlap, not containment.
            if (e.startedAtMs >= endMs || e.startedAtMs + e.durationMs <= startMs) continue
            // Filtered on READ, so history recorded before this filter existed
            // is corrected without rewriting or deleting a single event. The
            // raw record stays intact; only what we count changes.
            if (MacSystemUi.isSystemUi(e.appKey)) continue
            for (b in RollupEngine.bucket(e)) {
                if (b.bucketTs < startMs || b.bucketTs >= endMs) continue
                byApp.merge(b.appKey.value, b.activeMs, Long::plus)
            }
        }
        val nameMap = names()
        return byApp.entries
            .map { (key, ms) -> AppTotal(AppKey(key), nameMap[key] ?: key, ms) }
            .sortedByDescending { it.totalMs }
    }

    /** Epoch millis of local midnight starting [dayLocal]. */
    fun startOfDayMs(dayLocal: String, zone: TimeZone = displayZone()): Long =
        LocalDay.startOfDayMs(dayLocal, zone)

    /**
     * Running daily mean over every complete day on record — not just the
     * window on screen.
     *
     * A seven-day window's own mean moves with whichever week you are looking
     * at, so a line drawn from it tells you about that week rather than about
     * you. Averaging across all history gives a line that stays put.
     *
     * Today is excluded: a partial day drags the mean down every morning and
     * lets it recover every evening, which looks like a trend and is an
     * artefact. Returns null when no complete day exists yet — a mean of
     * nothing is not zero, and a zero line invites comparison against it.
     */
    fun runningDailyAverageMs(zone: TimeZone = displayZone()): Long? {
        val today = LocalDay.today(zone)
        val days = recordedDays(zone).filterNot { it == today }
        if (days.isEmpty()) return null
        return days.sumOf { day -> totalsFor(day, zone).sumOf { it.totalMs } } / days.size
    }

    /**
     * Every local day that has any recorded usage, oldest first.
     *
     * Derived from the events rather than a stored index, so it cannot drift
     * out of step with what is actually recorded.
     */
    fun recordedDays(zone: TimeZone = displayZone()): List<String> =
        events
            .map { LocalDay.dayOf(it.startedAtMs, zone) }
            .distinct()
            .sorted()

    /** The local day containing [epochMs]. */
    fun dayOf(epochMs: Long, zone: TimeZone = displayZone()): String = LocalDay.dayOf(epochMs, zone)

    /** The local day containing "now". */
    fun today(zone: TimeZone = displayZone()): String = LocalDay.today(zone)

    /**
     * Per-day totals for the [days] local days ending today, oldest first.
     *
     * Every day in the window is returned, including ones with nothing
     * recorded — a gap must render as an empty day rather than silently
     * shortening the chart and misrepresenting the period.
     */
    fun dailyTotals(days: Int, zone: TimeZone = displayZone()): List<DayTotal> {
        val today = LocalDay.today(zone)
        val todayStart = LocalDay.startOfDayMs(today, zone)
        // Walk back by calendar date, not by subtracting 24h: DST days are 23
        // or 25 hours long and fixed arithmetic drifts across one.
        val windowStart = LocalDay.startOfDayMs(today, zone) - (days.toLong() + 1) * MILLIS_PER_DAY
        val allDays = LocalDay.daysBetween(windowStart, todayStart, zone).takeLast(days)
        return allDays.map { day ->
            DayTotal(
                dayUtc = day,
                totalMs = totalsFor(day, zone).sumOf { it.totalMs },
                isToday = day == today,
            )
        }
    }

    companion object {
        private const val MILLIS_PER_DAY = 86_400_000L

        /** Beside the rest of the app's data, so a backup of one is a backup of all. */
        internal const val DB_NAME = "lumen.db"

        fun defaultRoot(): File = File(
            System.getProperty("user.home"),
            "Library/Application Support/Lumen",
        )
    }
}
