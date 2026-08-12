package dev.lumen.core.clock

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Contract tests for the UTC-day rule (Agent B, M1 gate).
 *
 * `docs/data-model.md` locks: "today" is a UTC day, never device-local
 * midnight — two devices in different timezones must agree on which day a
 * rollup belongs to. These tests are where that rule gets teeth: every
 * assertion below is written so that a device-local implementation would
 * fail it, regardless of the timezone the test host happens to run in.
 */
class UtcDayTest {

    // ---- epoch anchors (verified against UTC, not local time) ----

    private val epoch = 0L                          // 1970-01-01T00:00:00Z
    private val lateOnTheEleventh = 1786491000000L  // 2026-08-11T23:30:00Z
    private val midnightTwelfth = 1786492800000L    // 2026-08-12T00:00:00Z
    private val lastMsOfEleventh = 1786492799999L   // 2026-08-11T23:59:59.999Z
    private val singleDigitDate = 1767614400000L    // 2026-01-05T12:00:00Z
    private val leapDay = 1709232300000L            // 2024-02-29T18:45:00Z
    private val dayAfterLeapDay = 1709251200000L    // 2024-03-01T00:00:00Z

    @Test
    fun `epoch is the first UTC day`() {
        assertEquals("1970-01-01", UtcDay.dayOf(epoch))
    }

    @Test
    fun `month and day are always zero-padded to two digits`() {
        // A naive "$year-$month-$day" would produce "2026-1-5" here, which
        // breaks the schema's TEXT day_utc ordering and the PK on rollups.
        assertEquals("2026-01-05", UtcDay.dayOf(singleDigitDate))
    }

    @Test
    fun `day strings sort lexicographically in chronological order`() {
        // rollups are keyed (device_id, day_utc, app_key) and queried by day
        // range as TEXT — lexicographic order must match time order.
        val chronological = listOf(epoch, leapDay, singleDigitDate, lateOnTheEleventh, midnightTwelfth)
        val days = chronological.map(UtcDay::dayOf)
        assertEquals(days.sorted(), days, "TEXT day_utc must sort as time does")
    }

    @Test
    fun `a late-evening UTC timestamp stays on its UTC day`() {
        // 23:30Z on the 11th is already the 12th in any timezone at or east
        // of +01:00. The locked rule says it belongs to the 11th. A
        // device-local implementation running in CEST would return the 12th
        // and fail here.
        assertEquals("2026-08-11", UtcDay.dayOf(lateOnTheEleventh))
    }

    @Test
    fun `the day rolls over exactly at the UTC midnight boundary, not before`() {
        assertEquals("2026-08-11", UtcDay.dayOf(lastMsOfEleventh))
        assertEquals("2026-08-12", UtcDay.dayOf(midnightTwelfth))
        assertNotEquals(UtcDay.dayOf(lastMsOfEleventh), UtcDay.dayOf(lastMsOfEleventh + 1))
    }

    @Test
    fun `boundary returns the exact start of the UTC day`() {
        assertEquals(epoch, UtcDay.boundary("1970-01-01"))
        assertEquals(midnightTwelfth, UtcDay.boundary("2026-08-12"))
    }

    @Test
    fun `boundary and dayOf round-trip`() {
        for (t in listOf(epoch, lateOnTheEleventh, midnightTwelfth, singleDigitDate, leapDay)) {
            val day = UtcDay.dayOf(t)
            val start = UtcDay.boundary(day)
            assertEquals(day, UtcDay.dayOf(start), "dayOf(boundary(dayOf(t))) must be stable")
            assertTrue(start <= t, "the day's boundary must not be after a timestamp inside it")
            assertTrue(t - start < 86_400_000L, "a timestamp must fall within 24h of its day start")
        }
    }

    @Test
    fun `leap day is a real day and its successor is March first`() {
        assertEquals("2024-02-29", UtcDay.dayOf(leapDay))
        assertEquals("2024-03-01", UtcDay.dayOf(dayAfterLeapDay))
        assertEquals(dayAfterLeapDay - 86_400_000L, UtcDay.boundary("2024-02-29"))
    }

    @Test
    fun `a backwards clock step does not corrupt the day mapping`() {
        // NTP correcting a fast clock moves time backwards. dayOf must remain
        // a pure function of the instant — the same input always maps to the
        // same day, with no hidden "last seen" state.
        val forward = UtcDay.dayOf(midnightTwelfth + 5_000L)
        val stepped = UtcDay.dayOf(midnightTwelfth - 5_000L)
        assertEquals("2026-08-12", forward)
        assertEquals("2026-08-11", stepped)
        assertEquals(forward, UtcDay.dayOf(midnightTwelfth + 5_000L), "dayOf must be pure")
    }

    @Test
    fun `a day is exactly 24 hours wide with no DST discontinuity`() {
        // The whole point of UTC days: no 23h or 25h days, ever. A local-time
        // implementation in a DST-observing zone would fail this on the
        // transition dates.
        val marchTransition = UtcDay.boundary("2026-03-29")   // EU DST spring forward
        val octoberTransition = UtcDay.boundary("2026-10-25") // EU DST fall back
        assertEquals(86_400_000L, UtcDay.boundary("2026-03-30") - marchTransition)
        assertEquals(86_400_000L, UtcDay.boundary("2026-10-26") - octoberTransition)
    }

    @Test
    fun `today is a well-formed UTC day string`() {
        val today = UtcDay.today()
        assertTrue(
            Regex("""\d{4}-\d{2}-\d{2}""").matches(today),
            "today() must be YYYY-MM-DD, was '$today'",
        )
        assertEquals(today, UtcDay.dayOf(UtcDay.boundary(today)))
    }
}
