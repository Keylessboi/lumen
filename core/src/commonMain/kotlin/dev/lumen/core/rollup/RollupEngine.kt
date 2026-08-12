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
            val bucketStart = floorToMinute(cursor)
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

    /** Sum buckets into per-app-per-day rollups. Returns one rollup per distinct app. */
    fun rollup(deviceId: DeviceId, dayUtc: String, buckets: List<MinuteBucket>): List<AppDayRollup> {
        val byApp = buckets.groupBy { it.appKey }
        return byApp.map { (app, appBuckets) ->
            AppDayRollup(
                deviceId = deviceId,
                dayUtc = dayUtc,
                appKey = app,
                totalMs = appBuckets.sumOf { it.activeMs },
                category = null, // category is a separate lookup, never baked into rollup math
            )
        }
    }

    /** Aggregate a list of rollups into a single day total (all apps). */
    fun dayTotal(rollups: List<AppDayRollup>): Long = rollups.sumOf { it.totalMs }

    /**
     * Floor to the containing minute, for negative epochs too.
     *
     * Kotlin's `%` keeps the sign of the dividend, so `-90_000 % 60_000` is
     * `-30_000` and `cursor - (cursor % MINUTE_MS)` yields `-60_000` — a
     * boundary AFTER the timestamp it is supposed to contain. That produced a
     * "1-minute bucket" holding 90 seconds, breaking the invariant every
     * consumer of the bucket table relies on.
     *
     * Pre-1970 timestamps are not hypothetical: a device with a wrong clock,
     * or a corrupt row in an imported store, produces them, and the result
     * was silently wrong data rather than a rejected event.
     */
    private fun floorToMinute(epochMs: Long): Long {
        val rem = epochMs % MINUTE_MS
        return if (rem < 0) epochMs - rem - MINUTE_MS else epochMs - rem
    }

    private const val MINUTE_MS = 60_000L
}
