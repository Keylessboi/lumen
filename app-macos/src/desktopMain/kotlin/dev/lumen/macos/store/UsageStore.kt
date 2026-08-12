package dev.lumen.macos.store

import dev.lumen.core.clock.LocalDay
import dev.lumen.core.clock.UtcDay
import kotlinx.datetime.TimeZone
import dev.lumen.core.model.AppKey
import dev.lumen.ui.AppTotal
import dev.lumen.ui.charts.DayTotal
import dev.lumen.core.model.DeviceId
import dev.lumen.core.model.FocusEvent
import dev.lumen.core.rollup.RollupEngine
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Local-only usage store for the macOS app.
 *
 * Events are appended as NDJSON, one file per UTC day, under
 * `~/Library/Application Support/Lumen/`. Per-app day totals are derived on
 * read via [RollupEngine.bucket] rather than stored — `RollupEngine`'s own
 * contract says buckets and rollups are DERIVED, never authoritative.
 *
 * Deliberately not SQLite: the SQLite schema lives in `core` and freezes at
 * M1 (Agent A's zone). Duplicating it here would create a second, divergent
 * definition of the same tables. NDJSON is a local cache this module owns
 * outright and can throw away when `core`'s store lands.
 *
 * Bucket-to-day assignment goes through [UtcDay.dayOf] on each bucket's
 * timestamp, not the event's start, so a session spanning UTC midnight splits
 * across both days — the locked UTC-day rule, applied where it actually bites.
 */
class UsageStore(
    private val root: File = defaultRoot(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    init {
        root.mkdirs()
    }

    /** Stable per-install device identity, created once. */
    fun deviceId(): DeviceId {
        val f = File(root, "device-id")
        if (f.exists()) {
            f.readText().trim().takeIf { it.isNotEmpty() }?.let { return DeviceId(it) }
        }
        val id = UUID.randomUUID().toString()
        f.writeText(id)
        return DeviceId(id)
    }

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
    fun earliestEventMs(): Long? =
        (root.listFiles { f -> f.name.startsWith("events-") && f.name.endsWith(".ndjson") } ?: emptyArray())
            .asSequence()
            .flatMap { readEvents(it.name.removePrefix("events-").removeSuffix(".ndjson")).asSequence() }
            .minOfOrNull { it.startedAtMs }

    /**
     * One past the highest seq the store holds, so an import continues the
     * sequence rather than restarting it.
     *
     * Harmless in this NDJSON cache, which never reads seq — but
     * `(device_id, seq)` is the primary key in `LumenStore`, and colliding
     * seqs there would be silently dropped by `INSERT OR IGNORE` when
     * app-macos migrates.
     */
    fun nextSeq(): Long =
        ((root.listFiles { f -> f.name.startsWith("events-") && f.name.endsWith(".ndjson") } ?: emptyArray())
            .asSequence()
            .flatMap { readEvents(it.name.removePrefix("events-").removeSuffix(".ndjson")).asSequence() }
            .maxOfOrNull { it.seq } ?: -1L) + 1L

    /** Append a batch, advancing the import watermark to the newest event taken. */
    fun appendImported(events: List<FocusEvent>) {
        if (events.isEmpty()) return
        events.forEach(::append)
        setImportWatermark(events.maxOf { it.startedAtMs + it.durationMs })
    }

    /** Append a closed session. */
    fun append(event: FocusEvent) {
        // An event can span midnight; it is written to the file for the day it
        // STARTED in, and split correctly at read time by bucket timestamp.
        val day = UtcDay.dayOf(event.startedAtMs)
        File(root, "events-$day.ndjson")
            .appendText(json.encodeToString(FocusEvent.serializer(), event) + "\n")
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
                val name = devSelfName(bundleId) ?: appNameForBundleId(bundleId)
                name?.let { rememberName(AppKey(bundleId), it) }
            }
    }

    /**
     * Lumen's own identity while running unpackaged.
     *
     * A dev build is a bare JVM, so lsappinfo reports the JDK's bundle id and
     * the main class as the name — which is why Lumen appeared in its own
     * list as "MainKt". The packaged .app has a real bundle id and does not
     * hit this. Naming it here rather than special-casing the collector keeps
     * the collector reporting what it observes.
     */
    private fun devSelfName(bundleId: String): String? {
        if (!bundleId.startsWith("net.java.openjdk.java/")) return null
        val mainClass = System.getProperty("sun.java.command")
            ?.substringBefore(' ')
            ?.substringAfterLast('.')
        return if (bundleId.substringAfter('/') == mainClass) "Lumen" else null
    }

    /** Spotlight lookup: bundle id -> the app's display name, or null. */
    private fun appNameForBundleId(bundleId: String): String? = runCatching {
        // Quoted so a crafted id cannot alter the query's structure.
        val query = "kMDItemCFBundleIdentifier == \"${bundleId.replace("\"", "")}\""
        val proc = ProcessBuilder("/usr/bin/mdfind", query).redirectErrorStream(false).start()
        val out = proc.inputStream.bufferedReader().readText()
        if (!proc.waitFor(MDFIND_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return null
        }
        out.lineSequence()
            .firstOrNull { it.endsWith(".app") }
            ?.substringAfterLast('/')
            ?.removeSuffix(".app")
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** Remember a human-facing app name. Display only, never synced. */
    fun rememberName(appKey: AppKey, displayName: String?) {
        // Our own dev process reports its main class ("MainKt"). Override at
        // the single point every name enters the store, so the live collector
        // and the import path cannot disagree about what Lumen is called.
        val resolved = devSelfName(appKey.value) ?: displayName
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
     * Per-app totals for [dayUtc], largest first.
     *
     * Reads the day's file plus the previous day's, because a session that
     * started before midnight contributes buckets to today.
     */
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
     * Events are filed by the UTC day they started in, and a local day
     * straddles at most two of those, so both are read and then filtered by
     * the local-day window. Bucketing to the minute first means an event
     * spanning local midnight is split across the two days rather than
     * assigned wholesale to one, which is the same rule the UTC path used.
     */
    fun totalsFor(dayLocal: String, zone: TimeZone = displayZone()): List<AppTotal> {
        val startMs = LocalDay.startOfDayMs(dayLocal, zone)
        val endMs = LocalDay.endOfDayMs(dayLocal, zone)

        // Events are filed by the UTC day they STARTED in, and a session can
        // run past midnight — so a day's usage can live in a file named for
        // the day before it. Read one extra UTC day back, or a session that
        // began at 23:58 and ran into this day is invisible.
        val utcDays = linkedSetOf(
            UtcDay.dayOf(startMs - 86_400_000L),
            UtcDay.dayOf(startMs),
            UtcDay.dayOf(endMs - 1),
        )
        val events = utcDays.flatMap { readEvents(it) }

        val byApp = mutableMapOf<String, Long>()
        for (e in events) {
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
     * Derived from the event files rather than a stored index, so it cannot
     * drift out of step with what is actually on disk.
     */
    fun recordedDays(zone: TimeZone = displayZone()): List<String> =
        (root.listFiles { f -> f.name.startsWith("events-") && f.name.endsWith(".ndjson") } ?: emptyArray())
            .asSequence()
            .flatMap { readEvents(it.name.removePrefix("events-").removeSuffix(".ndjson")).asSequence() }
            .map { LocalDay.dayOf(it.startedAtMs, zone) }
            .distinct()
            .sorted()
            .toList()

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
        val windowStart = LocalDay.startOfDayMs(today, zone) - (days.toLong() + 1) * 86_400_000L
        val allDays = LocalDay.daysBetween(windowStart, todayStart, zone).takeLast(days)
        return allDays.map { day ->
            DayTotal(
                dayUtc = day,
                totalMs = totalsFor(day, zone).sumOf { it.totalMs },
                isToday = day == today,
            )
        }
    }

    private fun readEvents(dayUtc: String): List<FocusEvent> {
        val f = File(root, "events-$dayUtc.ndjson")
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            // A single corrupt line must not lose the whole day's history.
            runCatching { json.decodeFromString(FocusEvent.serializer(), line) }.getOrNull()
        }
    }

    companion object {
        private const val MILLIS_PER_DAY = 86_400_000L
        private const val MDFIND_TIMEOUT_SECONDS = 2L

        fun defaultRoot(): File = File(
            System.getProperty("user.home"),
            "Library/Application Support/Lumen",
        )
    }
}

/** A single app's total for a day. [displayName] falls back to the bundle id. */
