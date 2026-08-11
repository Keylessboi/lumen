package dev.lumen.core.rollup

import dev.lumen.core.model.AppDayRollup
import dev.lumen.core.model.AppKey
import dev.lumen.core.model.DeviceId
import dev.lumen.core.model.FocusEvent
import dev.lumen.core.model.MinuteBucket

/**
 * Rollup engine — collapses raw events into 1-min buckets and
 * per-app-day rollups. Pure functions, no I/O, fully testable.
 *
 * Reconciliation contract (FROZEN at M1):
 *  - buckets/rollups are DERIVED, never synced as authoritative state;
 *    sync carries raw events, each device derives its own views.
 *  - day boundary is UTC (`YYYY-MM-DD`), never device-local midnight.
 */
object RollupEngine {

    /** Bucket a raw event into 1-minute slices. */
    fun bucket(event: FocusEvent): List<MinuteBucket> {
        if (event.durationMs <= 0) return emptyList()

        val buckets = mutableListOf<MinuteBucket>()
        var cursor = event.startedAtMs
        val end = event.startedAtMs + event.durationMs

        while (cursor < end) {
            val bucketStart = cursor - (cursor % MINUTE_MS)
            val bucketEnd = bucketStart + MINUTE_MS
            val sliceEnd = minOf(end, bucketEnd)
            buckets += MinuteBucket(
                deviceId = event.deviceId,
                bucketTs = bucketStart,
                appKey = event.appKey,
                activeMs = sliceEnd - cursor,
            )
            cursor = sliceEnd
        }
        return buckets
    }

    /** Sum buckets into a per-app-per-day rollup. */
    fun rollup(deviceId: DeviceId, dayUtc: String, buckets: List<MinuteBucket>): AppDayRollup {
        val byApp = buckets.groupBy { it.appKey }
        return byApp.map { (app, appBuckets) ->
            AppDayRollup(
                deviceId = deviceId,
                dayUtc = dayUtc,
                appKey = app,
                totalMs = appBuckets.sumOf { it.activeMs },
                category = null, // category is a separate lookup, never baked into rollup math
            )
        }.maxByOrNull { it.totalMs } ?: AppDayRollup(deviceId, dayUtc, AppKey(""), 0)
    }

    /** Aggregate a list of rollups into a single day total (all apps). */
    fun dayTotal(rollups: List<AppDayRollup>): Long = rollups.sumOf { it.totalMs }

    private const val MINUTE_MS = 60_000L
}
