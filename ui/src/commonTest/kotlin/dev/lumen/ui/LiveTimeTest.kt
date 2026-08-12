package dev.lumen.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Day-rollover arithmetic for the live session.
 *
 * LO: "when it hits midnight all of the data from that day should go into a
 * graph bar to the last day, then a new one should start, at zero, and the
 * top number should be zero." The stored side already splits at midnight
 * because events are bucketed to the minute; this is the un-stored half.
 */
class LiveTimeTest {

    private val midnight = 1_786_507_200_000L // a local midnight
    private val minute = 60_000L

    @Test
    fun `a session started today counts from when it started`() {
        assertEquals(
            5 * minute,
            liveMsWithinDay(
                nowMs = midnight + 10 * minute,
                sessionStartedAtMs = midnight + 5 * minute,
                dayStartMs = midnight,
            ),
        )
    }

    @Test
    fun `a session open across midnight counts only the part after it`() {
        // Started 21:30 yesterday, it is now 00:01. The answer is one minute,
        // not two and a half hours.
        assertEquals(
            minute,
            liveMsWithinDay(
                nowMs = midnight + minute,
                sessionStartedAtMs = midnight - 150 * minute,
                dayStartMs = midnight,
            ),
        )
    }

    @Test
    fun `at the stroke of midnight the new day is zero`() {
        assertEquals(
            0L,
            liveMsWithinDay(
                nowMs = midnight,
                sessionStartedAtMs = midnight - 300 * minute,
                dayStartMs = midnight,
            ),
        )
    }

    @Test
    fun `no open session contributes nothing`() {
        assertEquals(0L, liveMsWithinDay(midnight + minute, sessionStartedAtMs = 0L, dayStartMs = midnight))
    }

    @Test
    fun `a backwards clock step never produces negative time`() {
        // NTP correcting a fast clock must not subtract from the day.
        assertEquals(
            0L,
            liveMsWithinDay(
                nowMs = midnight + minute,
                sessionStartedAtMs = midnight + 5 * minute,
                dayStartMs = midnight,
            ),
        )
    }

    @Test
    fun `the live portion never exceeds the elapsed day`() {
        // Whatever the session start, live time cannot claim more of the day
        // than has actually happened.
        val elapsed = 42 * minute
        for (start in listOf(midnight - 999 * minute, midnight, midnight + minute)) {
            val live = liveMsWithinDay(midnight + elapsed, start, midnight)
            assertEquals(true, live <= elapsed, "live=$live exceeded elapsed=$elapsed for start=$start")
        }
    }
}
