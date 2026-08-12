package dev.lumen.core.clock

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.offsetAt
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/**
 * The day a human lived, as distinct from [UtcDay] — the day a record is
 * filed under.
 *
 * ## Why both exist
 *
 * `docs/data-model.md` locks the UTC day as the reconciliation key, and that
 * rule is right: two devices merging rollups must agree what day a record
 * belongs to, or the same day exists twice. But that argument is entirely
 * about storage, and it was also being used for display — so the Today screen
 * showed the UTC day.
 *
 * For anyone not at UTC+0 that is visibly wrong. At UTC-4 the day rolls over
 * at 20:00 local: the number the whole product exists to show resets while
 * the user is still at the keyboard. At UTC+10 it is worse — the Lumen day
 * starts at 10:00, so every morning counts toward yesterday.
 *
 * So: **UTC day for reconciliation, local day for display.** They come apart
 * cleanly because buckets carry absolute epoch-millisecond timestamps; a
 * local day is a different window over the same data, not different data.
 *
 * ## Why the zone is a setting, not the OS timezone
 *
 * If "local day" meant each device's current OS timezone, a laptop in Berlin
 * and a phone in New York would disagree about which day a session belongs
 * to and their totals could not be summed — reintroducing, at the display
 * layer, exactly the problem the UTC rule was written to prevent.
 *
 * Instead the boundary comes from one [SETTING_KEY] value that reconciles
 * across devices like any other setting. Every device then computes the same
 * day, wherever it physically is. This is also what the platform trackers do:
 * a screen-time day belongs to a person, not to a handset.
 */
object LocalDay {

    /**
     * Settings key holding the IANA zone id that defines the display day.
     * Reconciles LWW + UTC-day like every other setting.
     */
    const val SETTING_KEY: String = "display.timezone"

    /**
     * Resolve the display zone from a stored setting value.
     *
     * Falls back to the device zone when unset (first run) or unparseable —
     * a zone id can go stale when the tz database drops or renames one, and
     * refusing to render a screen over that is worse than being briefly wrong
     * about a boundary.
     */
    fun zoneOf(settingValue: String?): TimeZone =
        settingValue
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { TimeZone.of(it) }.getOrNull() }
            ?: TimeZone.currentSystemDefault()

    /** `YYYY-MM-DD` for the [zone]-local day containing [epochMs]. */
    fun dayOf(epochMs: Long, zone: TimeZone): String =
        Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone).date.toString()

    /** Epoch millis of local midnight starting [dayLocal] in [zone]. */
    fun startOfDayMs(dayLocal: String, zone: TimeZone): Long =
        LocalDate.parse(dayLocal).atStartOfDayIn(zone).toEpochMilliseconds()

    /**
     * Epoch millis of the first instant of the NEXT local day.
     *
     * Computed from the next calendar date rather than `start + 24h`, because
     * a DST transition makes a local day 23 or 25 hours long. Adding a fixed
     * day would silently drop or double-count an hour twice a year.
     */
    fun endOfDayMs(dayLocal: String, zone: TimeZone): Long =
        LocalDate.parse(dayLocal).plus(1, DateTimeUnit.DAY)
            .atStartOfDayIn(zone)
            .toEpochMilliseconds()

    /** The local day containing "now". */
    fun today(zone: TimeZone): String = dayOf(nowMs(), zone)

    /**
     * The zone's UTC offset in minutes at [epochMs].
     *
     * Recorded alongside a stored local rollup so a day written under one
     * offset stays explainable after the user moves. A day you lived in EDT
     * is a historical fact; relocating should not silently redraw it.
     */
    fun offsetMinutes(epochMs: Long, zone: TimeZone): Int =
        zone.offsetAt(Instant.fromEpochMilliseconds(epochMs)).totalSeconds / 60

    /** Local days in `[fromMs, toMs]`, oldest first, with no gaps. */
    fun daysBetween(fromMs: Long, toMs: Long, zone: TimeZone): List<String> {
        if (toMs < fromMs) return emptyList()
        var date = Instant.fromEpochMilliseconds(fromMs).toLocalDateTime(zone).date
        val last = Instant.fromEpochMilliseconds(toMs).toLocalDateTime(zone).date
        val out = mutableListOf<String>()
        while (date <= last) {
            out += date.toString()
            date = date.plus(1, DateTimeUnit.DAY)
        }
        return out
    }

    internal fun nowMs(): Long = System.currentTimeMillis()
}
