package dev.lumen.app

import dev.lumen.app.collector.UsageStatsCollector
import dev.lumen.core.clock.UtcDay
import dev.lumen.core.collector.FocusChange
import dev.lumen.core.model.AppKey
import dev.lumen.core.model.DeviceId
import dev.lumen.core.model.Setting
import dev.lumen.core.rollup.RollupEngine
import dev.lumen.core.session.FocusSessionTracker
import dev.lumen.core.store.LumenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Brings the store up to date with Android's own usage log — incrementally.
 *
 * The Activity used to re-import the last three days on EVERY launch with a
 * session tracker numbering from seq 0. The second launch's events collided
 * with the first's on the `(device_id, seq)` primary key, and the ones that
 * did not collide covered time already recorded — totals went wrong a
 * little more each time the app was opened.
 *
 * Now a cursor (the last transition processed) and the still-open session
 * are kept in settings, and each run continues exactly where the previous
 * one stopped, with seqs continuing from the store. The Activity polls it
 * while visible; [SyncWorker] runs it in the background, so time is recorded
 * and synced even when nobody opens Lumen.
 */
class UsageImporter(
    private val store: LumenStore,
    private val deviceId: DeviceId,
    private val collector: UsageStatsCollector,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The session in progress: not an event yet, but the UI shows it live. */
    data class Open(val appKey: AppKey, val sinceMs: Long, val idle: Boolean)

    data class Result(val imported: Int, val open: Open?)

    private val lock = Mutex()

    suspend fun importNew(): Result = lock.withLock {
        withContext(Dispatchers.IO) { importLocked() }
    }

    private suspend fun importLocked(): Result {
        val now = clock()
        val cursor = setting(KEY_CURSOR)?.toLongOrNull() ?: firstRunCursor(now)
        val pending = readOpen()
        val tracker = FocusSessionTracker(deviceId, store.lastEventSeq(deviceId) + 1)
        pending?.let { tracker.onChange(FocusChange(it.appKey, it.sinceMs, isIdle = it.idle)) }

        var open = pending
        var imported = 0
        val touchedDays = mutableSetOf<String>()
        val changes = collector.backfill(maxOf(cursor, now - HORIZON_MS)).filter { it.atMs > cursor }
        for (change in changes) {
            // Same app again (e.g. two foreground events) is not a transition.
            if (open != null && change.appKey == open.appKey && change.isIdle == open.idle) continue
            tracker.onChange(change)?.let { event ->
                store.insertEvent(event)
                RollupEngine.bucket(event).forEach(store::insertBucket)
                touchedDays += UtcDay.dayOf(event.startedAtMs)
                touchedDays += UtcDay.dayOf(event.startedAtMs + event.durationMs)
                imported++
            }
            open = Open(change.appKey, change.atMs, change.isIdle)
        }
        changes.lastOrNull()?.let { putSetting(KEY_CURSOR, it.atMs.toString()) }
        if (setting(KEY_CURSOR) == null) putSetting(KEY_CURSOR, cursor.toString())
        writeOpen(open)
        touchedDays.forEach { day ->
            val start = UtcDay.boundary(day)
            RollupEngine.rollup(deviceId, day, store.bucketsForRange(deviceId, start, start + DAY_MS)).forEach(store::upsertRollup)
        }
        return Result(imported, open)
    }

    /**
     * No cursor yet. A fresh install imports the OS's whole retained window;
     * an install upgraded from the old importer continues after the last
     * event it already has, so nothing is counted twice.
     */
    private fun firstRunCursor(now: Long): Long {
        val last = store.eventsAfter(deviceId, Long.MIN_VALUE).maxByOrNull { it.startedAtMs + it.durationMs }
        return last?.let { it.startedAtMs + it.durationMs } ?: (now - HORIZON_MS)
    }

    private fun readOpen(): Open? {
        val raw = setting(KEY_OPEN) ?: return null
        val parts = raw.split('|')
        if (parts.size != 3) return null
        return Open(AppKey(parts[0]), parts[1].toLongOrNull() ?: return null, parts[2] == "1")
    }

    private fun writeOpen(open: Open?) =
        putSetting(KEY_OPEN, open?.let { "${it.appKey.value}|${it.sinceMs}|${if (it.idle) 1 else 0}" } ?: "")

    private fun setting(key: String) = store.setting(key)?.value?.decodeToString()?.takeIf { it.isNotBlank() }

    private fun putSetting(key: String, value: String) {
        val now = clock()
        store.upsertSetting(Setting(key, value.encodeToByteArray(), now, UtcDay.dayOf(now), deviceId))
    }

    private companion object {
        const val KEY_CURSOR = "android.import.cursor_ms"
        const val KEY_OPEN = "android.import.open"
        /** UsageStats keeps raw events for a few days. */
        const val HORIZON_MS = 3L * 24 * 60 * 60 * 1000
        const val DAY_MS = 86_400_000L
    }
}
