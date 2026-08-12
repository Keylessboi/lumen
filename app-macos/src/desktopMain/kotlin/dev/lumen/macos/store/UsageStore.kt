package dev.lumen.macos.store

import dev.lumen.core.clock.UtcDay
import dev.lumen.core.model.AppKey
import dev.lumen.ui.AppTotal
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

    /** Remember a human-facing app name. Display only, never synced. */
    fun rememberName(appKey: AppKey, displayName: String?) {
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
    fun totalsFor(dayUtc: String): List<AppTotal> {
        val events = readEvents(dayUtc) + readEvents(previousDay(dayUtc))
        val byApp = mutableMapOf<String, Long>()
        for (e in events) {
            for (b in RollupEngine.bucket(e)) {
                if (UtcDay.dayOf(b.bucketTs) != dayUtc) continue
                byApp.merge(b.appKey.value, b.activeMs, Long::plus)
            }
        }
        val nameMap = names()
        return byApp.entries
            .map { (key, ms) -> AppTotal(AppKey(key), nameMap[key] ?: key, ms) }
            .sortedByDescending { it.totalMs }
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

    private fun previousDay(dayUtc: String): String =
        UtcDay.dayOf(UtcDay.boundary(dayUtc) - 1L)

    companion object {
        fun defaultRoot(): File = File(
            System.getProperty("user.home"),
            "Library/Application Support/Lumen",
        )
    }
}

/** A single app's total for a day. [displayName] falls back to the bundle id. */
