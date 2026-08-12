package dev.lumen.ui.charts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Layout maths for the trend chart. Pure, so it is testable without a
 * renderer — and worth testing, because `docs/design-spec.md` treats a chart
 * that misrepresents its data as an uninstall-grade bug ("Charts that lie").
 */
class DayBarsTest {

    @Test
    fun `the tallest day fills the chart`() {
        assertEquals(1f, barFraction(totalMs = 7_200_000, peakMs = 7_200_000))
    }

    @Test
    fun `a half-length day is half as tall`() {
        assertEquals(0.5f, barFraction(totalMs = 3_600_000, peakMs = 7_200_000))
    }

    @Test
    fun `a day with nothing recorded still draws a visible sliver`() {
        // A zero-height bar is indistinguishable from a bar that failed to
        // render, which reads as missing data rather than an idle day.
        assertTrue(barFraction(totalMs = 0, peakMs = 7_200_000) > 0f)
        assertEquals(0.02f, barFraction(totalMs = 0, peakMs = 7_200_000))
    }

    @Test
    fun `no bar can exceed the chart even if the peak is understated`() {
        assertEquals(1f, barFraction(totalMs = 9_999_999, peakMs = 1_000))
    }

    @Test
    fun `an all-zero window does not divide by zero`() {
        assertEquals(0.02f, barFraction(totalMs = 0, peakMs = 0))
    }

    @Test
    fun `bar heights stay proportional to the underlying totals`() {
        // The property that makes the chart honest: ordering and ratios of
        // the bars must match ordering and ratios of the numbers.
        val totals = listOf(1_800_000L, 3_600_000L, 7_200_000L)
        val peak = totals.max()
        val fractions = totals.map { barFraction(it, peak) }
        assertEquals(fractions.sorted(), fractions)
        assertEquals(2f, fractions[2] / fractions[1], 0.0001f)
    }

    @Test
    fun `today is flagged so a partial day is not read as a decline`() {
        val today = DayTotal("2026-08-12", "12", 600_000, isToday = true)
        val yesterday = DayTotal("2026-08-11", "11", 7_200_000)
        assertTrue(today.isToday)
        assertTrue(!yesterday.isToday)
    }
}
