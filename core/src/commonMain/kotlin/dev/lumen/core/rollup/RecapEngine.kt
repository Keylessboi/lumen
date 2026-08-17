package dev.lumen.core.rollup

import dev.lumen.core.model.DeviceId
import dev.lumen.core.model.MinuteBucket
import dev.lumen.core.model.RecapAppBreakdown
import dev.lumen.core.model.RecapPeriod
import dev.lumen.core.model.RecapSummary
import dev.lumen.core.model.TARGET_SCREENTIME_KEY
import dev.lumen.core.store.LumenStore
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/**
 * Recap engine — aggregates 1-minute buckets into weekly, monthly, and
 * yearly screen-time summaries. Pure functions, no I/O beyond store reads.
 *
 * Every function takes a [LumenStore] and reads buckets/settings, but
 * never writes. The store dependency is not injected via interface because
 * the engine is a stateless object — testability comes from mock/fake stores,
 * not DI.
 */
object RecapEngine {

    private const val DAY_MS = 86_400_000L

    /**
     * Build a weekly recap starting at [weekStartMs] (UTC epoch millis).
     *
     * A week is exactly 7 days: [weekStartMs, weekStartMs + 7 days).
     */
    fun weeklyRecap(
        store: LumenStore,
        deviceId: DeviceId,
        weekStartMs: Long,
    ): RecapPeriod {
        val endMs = weekStartMs + 7 * DAY_MS
        return buildRecap(store, deviceId, weekStartMs, endMs)
    }

    /**
     * Build a monthly recap starting at [monthStartMs] (UTC epoch millis).
     *
     * The period end is the start of the next month in UTC. If the start
     * timestamp does not fall on a month boundary, it is rounded down to
     * the containing month's first day.
     */
    fun monthlyRecap(
        store: LumenStore,
        deviceId: DeviceId,
        monthStartMs: Long,
    ): RecapPeriod {
        val tz = TimeZone.UTC
        val date = Instant.fromEpochMilliseconds(monthStartMs).toLocalDateTime(tz).date
        val startDate = LocalDate(date.year, date.monthNumber, 1)
        val endDate = startDate.plus(1, DateTimeUnit.MONTH)
        val startMs = startDate.atStartOfDayIn(tz).toEpochMilliseconds()
        val endMs = endDate.atStartOfDayIn(tz).toEpochMilliseconds()
        return buildRecap(store, deviceId, startMs, endMs)
    }

    /**
     * Build a yearly recap starting at [yearStartMs] (UTC epoch millis).
     *
     * The period end is the start of the next year in UTC. If the start
     * timestamp does not fall on a year boundary, it is rounded down to
     * the containing year's first day.
     */
    fun yearlyRecap(
        store: LumenStore,
        deviceId: DeviceId,
        yearStartMs: Long,
    ): RecapPeriod {
        val tz = TimeZone.UTC
        val date = Instant.fromEpochMilliseconds(yearStartMs).toLocalDateTime(tz).date
        val startDate = LocalDate(date.year, 1, 1)
        val endDate = startDate.plus(1, DateTimeUnit.YEAR)
        val startMs = startDate.atStartOfDayIn(tz).toEpochMilliseconds()
        val endMs = endDate.atStartOfDayIn(tz).toEpochMilliseconds()
        return buildRecap(store, deviceId, startMs, endMs)
    }

    /**
     * Build a full recap with all three periods.
     *
     * [now] is the current UTC epoch millis. Week/month/year boundaries
     * are computed relative to [now].
     */
    fun fullRecap(
        store: LumenStore,
        deviceId: DeviceId,
        now: Long,
    ): RecapSummary {
        val tz = TimeZone.UTC
        val today = Instant.fromEpochMilliseconds(now).toLocalDateTime(tz).date

        // ISO-8601: Monday=ordinal 0, Sunday=ordinal 6
        val weekStart = today.minus(today.dayOfWeek.ordinal, DateTimeUnit.DAY)
            .atStartOfDayIn(tz).toEpochMilliseconds()

        val monthStart = LocalDate(today.year, today.monthNumber, 1)
            .atStartOfDayIn(tz).toEpochMilliseconds()

        val yearStart = LocalDate(today.year, 1, 1)
            .atStartOfDayIn(tz).toEpochMilliseconds()

        return RecapSummary(
            week = weeklyRecap(store, deviceId, weekStart),
            month = monthlyRecap(store, deviceId, monthStart),
            year = yearlyRecap(store, deviceId, yearStart),
        )
    }

    // ---- internal helpers ----

    private fun buildRecap(
        store: LumenStore,
        deviceId: DeviceId,
        startMs: Long,
        endMs: Long,
    ): RecapPeriod {
        val buckets = store.bucketsForRange(deviceId, startMs, endMs)
        val totalMs = buckets.sumOf { it.activeMs }
        val breakdown = aggregateBreakdown(buckets, totalMs)
        val targetMs = readDailyTarget(store)

        return RecapPeriod(
            startMs = startMs,
            endMs = endMs,
            totalMs = totalMs,
            targetMs = targetMs,
            appBreakdown = breakdown,
        )
    }

    private fun aggregateBreakdown(
        buckets: List<MinuteBucket>,
        totalMs: Long,
    ): List<RecapAppBreakdown> {
        if (buckets.isEmpty()) return emptyList()

        val byApp = buckets.groupBy { it.appKey }
        return byApp.map { (appKey, appBuckets) ->
            val appTotal = appBuckets.sumOf { it.activeMs }
            val pct = if (totalMs > 0) {
                (appTotal.toDouble() / totalMs.toDouble()) * 100.0
            } else {
                0.0
            }
            RecapAppBreakdown(
                appKey = appKey,
                totalMs = appTotal,
                percentage = pct,
            )
        }.sortedByDescending { it.totalMs }
    }

    private fun readDailyTarget(store: LumenStore): Long? {
        val setting = store.setting(TARGET_SCREENTIME_KEY) ?: return null
        return setting.value.decodeToString().toLongOrNull()
    }
}
