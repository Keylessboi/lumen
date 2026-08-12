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
        assertTrue(DayTotal("2026-08-12", 600_000, isToday = true).isToday)
        assertTrue(!DayTotal("2026-08-11", 7_200_000).isToday)
    }

    // ---- axis labels ----
    //
    // LO could not tell what "06 07 08" meant, and he was right not to: a
    // bare day-of-month reads as an hour, a week number, or a count. These
    // pin the labels as unambiguously calendar.

    @Test
    fun `axis labels are weekdays, not bare numbers`() {
        // 2026-08-12 is a Wednesday.
        assertEquals("Wed", DayTotal("2026-08-12", 0).axisLabel())
        assertEquals("Thu", DayTotal("2026-08-13", 0).axisLabel())
        assertEquals("Sun", DayTotal("2026-08-16", 0).axisLabel())
    }

    @Test
    fun `the day in progress is named rather than colour-coded`() {
        // docs/design-spec.md: state must never be carried by colour alone.
        assertEquals("Today", DayTotal("2026-08-12", 0, isToday = true).axisLabel())
    }

    @Test
    fun `the range anchors the weekday axis to real dates`() {
        val week = listOf(
            DayTotal("2026-08-06", 0),
            DayTotal("2026-08-12", 0, isToday = true),
        )
        // Weekdays alone say which days, never which week.
        assertEquals("Aug 6 – 12", dateRangeLabel(week))
    }

    @Test
    fun `a range spanning two months names both`() {
        assertEquals(
            "Jul 28 – Aug 3",
            dateRangeLabel(listOf(DayTotal("2026-07-28", 0), DayTotal("2026-08-03", 0))),
        )
    }

    @Test
    fun `a single-day range is just that day`() {
        assertEquals("Aug 6", dateRangeLabel(listOf(DayTotal("2026-08-06", 0))))
    }

    @Test
    fun `an empty range is empty rather than a stray dash`() {
        assertEquals("", dateRangeLabel(emptyList()))
    }
}
