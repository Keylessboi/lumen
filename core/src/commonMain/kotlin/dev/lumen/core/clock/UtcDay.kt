package dev.lumen.core.clock

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * UTC-day boundary helpers. LOCKED RULE: "today" is a UTC day, never
 * device-local midnight — two devices in different timezones must agree
 * on what day a rollup belongs to.
 */
object UtcDay {

    /** Returns `YYYY-MM-DD` for the UTC day containing [epochMs]. */
    fun dayOf(epochMs: Long): String {
        val instant = Instant.fromEpochMilliseconds(epochMs)
        val utc = instant.toLocalDateTime(TimeZone.UTC)
        return "${utc.year}-${utc.monthNumber.pad(2)}-${utc.dayOfMonth.pad(2)}"
    }

    /** Epoch millis of the UTC day boundary (00:00:00.000 UTC) for [dayUtc]. */
    fun boundary(dayUtc: String): Long {
        val (y, m, d) = dayUtc.split("-").map { it.toInt() }
        return Instant.parse("${y.toString().padStart(4, '0')}-${m.pad(2)}-${d.pad(2)}T00:00:00Z")
            .toEpochMilliseconds()
    }

    /** The day containing "now" per the system clock. */
    fun today(): String = dayOf(System.currentTimeMillis())

    private fun Int.pad(width: Int): String = toString().padStart(width, '0')
}
