package dev.lumen.core.clock

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The display-day contract (discussion #29).
 *
 * The bug these exist for: at 23:50 on Tue 11 Aug in New York it is already
 * Wed 12 Aug in UTC, so the Today screen showed Wednesday and the user's
 * Tuesday had been filed away as a past day. The number the product exists to
 * show reset at 20:00 local, every day.
 */
class LocalDayTest {

    private val newYork = TimeZone.of("America/New_York")
    private val sydney = TimeZone.of("Australia/Sydney")
    private val utc = TimeZone.UTC

    /** 2026-08-12T03:50:00Z — the exact moment the bug was reported. */
    private val theMoment = 1_786_506_600_000L

    @Test
    fun `the reported bug — late local evening is not tomorrow`() {
        // UTC has already rolled over; the user has not gone to bed.
        assertEquals("2026-08-12", UtcDay.dayOf(theMoment))
        assertEquals("2026-08-11", LocalDay.dayOf(theMoment, newYork))
    }

    @Test
    fun `east of UTC the local day starts before the UTC one`() {
        // Sydney is UTC+10: at 03:50Z it is already 13:50 on the 12th there.
        // A UTC-day screen would show a day that is fourteen hours old.
        assertEquals("2026-08-12", LocalDay.dayOf(theMoment, sydney))
        assertEquals("2026-08-12", UtcDay.dayOf(theMoment))

        // ...and eight hours earlier, the two genuinely disagree the other way.
        val earlier = theMoment - 8 * 3_600_000L
        assertEquals("2026-08-11", UtcDay.dayOf(earlier))
        assertEquals("2026-08-12", LocalDay.dayOf(earlier, sydney))
    }

    @Test
    fun `at UTC the local day and the UTC day agree`() {
        assertEquals(UtcDay.dayOf(theMoment), LocalDay.dayOf(theMoment, utc))
    }

    @Test
    fun `a local day starts at local midnight`() {
        val start = LocalDay.startOfDayMs("2026-08-11", newYork)
        assertEquals("2026-08-11", LocalDay.dayOf(start, newYork))
        assertEquals("2026-08-10", LocalDay.dayOf(start - 1, newYork))
    }

    @Test
    fun `the day window is half-open and covers the whole day`() {
        val start = LocalDay.startOfDayMs("2026-08-11", newYork)
        val end = LocalDay.endOfDayMs("2026-08-11", newYork)
        assertEquals("2026-08-11", LocalDay.dayOf(end - 1, newYork))
        assertEquals("2026-08-12", LocalDay.dayOf(end, newYork))
        assertEquals(start, LocalDay.startOfDayMs("2026-08-11", newYork))
    }

    @Test
    fun `a DST spring-forward day is 23 hours, not 24`() {
        // The reason endOfDayMs uses the next calendar date rather than
        // start + 24h. Adding a fixed day would drop an hour twice a year.
        val start = LocalDay.startOfDayMs("2026-03-08", newYork)
        val end = LocalDay.endOfDayMs("2026-03-08", newYork)
        assertEquals(23 * 3_600_000L, end - start)
    }

    @Test
    fun `a DST fall-back day is 25 hours`() {
        val start = LocalDay.startOfDayMs("2026-11-01", newYork)
        val end = LocalDay.endOfDayMs("2026-11-01", newYork)
        assertEquals(25 * 3_600_000L, end - start)
    }

    @Test
    fun `every instant in a DST day belongs to that day`() {
        // The 25-hour day is where an off-by-one hour hides.
        val start = LocalDay.startOfDayMs("2026-11-01", newYork)
        val end = LocalDay.endOfDayMs("2026-11-01", newYork)
        var t = start
        while (t < end) {
            assertEquals("2026-11-01", LocalDay.dayOf(t, newYork), "instant $t")
            t += 3_600_000L
        }
    }

    @Test
    fun `the offset is recorded so a relocated day stays explainable`() {
        assertEquals(-4 * 60, LocalDay.offsetMinutes(theMoment, newYork)) // EDT
        assertEquals(10 * 60, LocalDay.offsetMinutes(theMoment, sydney))
        assertEquals(0, LocalDay.offsetMinutes(theMoment, utc))
        // Same zone, different season: the offset itself moves.
        val january = 1_767_225_600_000L // 2026-01-01T00:00:00Z
        assertEquals(-5 * 60, LocalDay.offsetMinutes(january, newYork)) // EST
    }

    @Test
    fun `daysBetween returns every day with no gaps`() {
        val from = LocalDay.startOfDayMs("2026-08-06", newYork)
        val to = LocalDay.startOfDayMs("2026-08-12", newYork)
        assertEquals(
            listOf(
                "2026-08-06", "2026-08-07", "2026-08-08",
                "2026-08-09", "2026-08-10", "2026-08-11", "2026-08-12",
            ),
            LocalDay.daysBetween(from, to, newYork),
        )
    }

    @Test
    fun `daysBetween spans a DST transition without losing or repeating a day`() {
        val from = LocalDay.startOfDayMs("2026-11-01", newYork) - 3_600_000L
        val to = LocalDay.startOfDayMs("2026-11-02", newYork)
        val days = LocalDay.daysBetween(from, to, newYork)
        assertEquals(days.distinct(), days)
        assertTrue(days.contains("2026-11-01"))
    }

    @Test
    fun `an inverted range is empty rather than infinite`() {
        val t = LocalDay.startOfDayMs("2026-08-11", newYork)
        assertEquals(emptyList(), LocalDay.daysBetween(t, t - 1, newYork))
    }

    // ---- the zone setting ----

    @Test
    fun `a stored zone id is honoured`() {
        assertEquals("2026-08-11", LocalDay.dayOf(theMoment, LocalDay.zoneOf("America/New_York")))
        assertEquals("2026-08-12", LocalDay.dayOf(theMoment, LocalDay.zoneOf("Australia/Sydney")))
    }

    @Test
    fun `an unset or unusable zone falls back to the device rather than failing`() {
        // A zone id can go stale when the tz database renames one. Refusing
        // to render the screen over that is worse than a wrong boundary.
        val fallback = TimeZone.currentSystemDefault()
        assertEquals(fallback, LocalDay.zoneOf(null))
        assertEquals(fallback, LocalDay.zoneOf(""))
        assertEquals(fallback, LocalDay.zoneOf("   "))
        assertEquals(fallback, LocalDay.zoneOf("Mars/Olympus_Mons"))
    }

    @Test
    fun `the settings key is the one the sync layer reconciles`() {
        assertEquals("display.timezone", LocalDay.SETTING_KEY)
    }

    @Test
    fun `two devices in different places agree when they share the setting`() {
        // The whole reason the zone is a setting and not the OS timezone.
        val berlinLaptop = LocalDay.zoneOf("America/New_York")
        val newYorkPhone = LocalDay.zoneOf("America/New_York")
        assertEquals(
            LocalDay.dayOf(theMoment, berlinLaptop),
            LocalDay.dayOf(theMoment, newYorkPhone),
        )
        // Whereas using each device's own zone, they would not.
        assertNotEquals(
            LocalDay.dayOf(theMoment, TimeZone.of("Europe/Berlin")),
            LocalDay.dayOf(theMoment, TimeZone.of("America/New_York")),
        )
    }
}
