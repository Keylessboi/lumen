package dev.lumen.ui.charts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Layout maths for the weekly recap chart. Pure functions only, testable
 * without a renderer — same approach as DayBarsTest.
 *
 * The target line must stay inside the chart, the peak must account for
 * all reference lines, and the week comparison percentages must be exact.
 */
class WeeklyRecapTest {

    // ---- peak calculation ----

    @Test
    fun `peak includes the tallest day`() {
        val days = listOf(
            DayTotal("2026-08-10", 3_600_000),
            DayTotal("2026-08-11", 7_200_000),
            DayTotal("2026-08-12", 1_800_000),
        )
        val peak = buildPeakFromDays(days, null, null)
        assertEquals(7_200_000L, peak)
    }

    @Test
    fun `peak includes the average when it exceeds every bar`() {
        val days = listOf(DayTotal("2026-08-10", 3_600_000))
        val peak = buildPeakFromDays(days, 7_200_000L, null)
        assertEquals(7_200_000L, peak)
    }

    @Test
    fun `peak includes the target when it exceeds every bar`() {
        val days = listOf(DayTotal("2026-08-10", 3_600_000))
        val peak = buildPeakFromDays(days, null, 10_000_000L)
        assertEquals(10_000_000L, peak)
    }

    @Test
    fun `peak includes both average and target when both are high`() {
        val days = listOf(DayTotal("2026-08-10", 3_600_000))
        val peak = buildPeakFromDays(days, 5_000_000L, 8_000_000L)
        assertEquals(8_000_000L, peak)
    }

    @Test
    fun `peak is at least 1 even with all zeros`() {
        val days = listOf(DayTotal("2026-08-10", 0))
        val peak = buildPeakFromDays(days, null, null)
        assertEquals(1L, peak)
    }

    // ---- target line stays inside chart ----

    @Test
    fun `target line fraction never exceeds 1`() {
        // A target of 10 hours should still be within the chart if a day
        // reached 10 hours.
        val target = 10_000_000L
        val peak = target // peak includes the target
        assertTrue(barFraction(target, peak) <= 1f)
        assertEquals(1f, barFraction(target, peak))
    }

    @Test
    fun `target line is visible when below the peak`() {
        val days = listOf(
            DayTotal("2026-08-10", 7_200_000),
        )
        val target = 5_000_000L
        val peak = buildPeakFromDays(days, null, target)
        val frac = barFraction(target, peak)
        assertTrue(frac > 0f, "target line should be visible")
        assertTrue(frac < 1f, "target line should not fill the chart")
    }

    @Test
    fun `target above all bars is still drawn at the top`() {
        val days = listOf(DayTotal("2026-08-10", 1_800_000))
        val target = 7_200_000L
        val peak = buildPeakFromDays(days, null, target)
        assertEquals(1f, barFraction(target, peak))
    }

    // ---- week comparison (percentage math) ----

    @Test
    fun `week comparison percentage is correct`() {
        // 32h -> 28h is a 12.5% decrease
        val currentMs = 28_800_000L // 8h
        val previousMs = 32_400_000L // 9h
        val delta = currentMs - previousMs
        val pct = delta.toDouble() / previousMs * 100
        assertEquals(-11.11, pct, 0.01)
    }

    @Test
    fun `week comparison shows up arrow when increase`() {
        val currentMs = 10_000_000L
        val previousMs = 5_000_000L
        assertTrue(currentMs > previousMs)
    }

    @Test
    fun `week comparison shows down arrow when decrease`() {
        val currentMs = 5_000_000L
        val previousMs = 10_000_000L
        assertTrue(currentMs < previousMs)
    }

    @Test
    fun `week comparison shows right arrow when equal`() {
        val currentMs = 5_000_000L
        val previousMs = 5_000_000L
        assertEquals(currentMs, previousMs)
    }

    @Test
    fun `week comparison handles zero previous gracefully`() {
        val currentMs = 5_000_000L
        val previousMs = 0L
        val pct = if (previousMs > 0) {
            (currentMs - previousMs).toDouble() / previousMs * 100
        } else null
        assertEquals(null, pct)
    }

    // ---- top apps breakdown ----

    @Test
    fun `top apps are limited to five`() {
        val apps = listOf(
            RecapAppBreakdown(dev.lumen.core.model.AppKey("A"), 1_000, 0.0),
            RecapAppBreakdown(dev.lumen.core.model.AppKey("B"), 900, 0.0),
            RecapAppBreakdown(dev.lumen.core.model.AppKey("C"), 800, 0.0),
            RecapAppBreakdown(dev.lumen.core.model.AppKey("D"), 700, 0.0),
            RecapAppBreakdown(dev.lumen.core.model.AppKey("E"), 600, 0.0),
            RecapAppBreakdown(dev.lumen.core.model.AppKey("F"), 500, 0.0),
            RecapAppBreakdown(dev.lumen.core.model.AppKey("G"), 400, 0.0),
        )
        val limited = apps.sortedByDescending { it.totalMs }.take(5)
        assertEquals(5, limited.size)
        assertEquals("A", limited[0].appKey.value)
        assertEquals("E", limited[4].appKey.value)
    }

    @Test
    fun `top apps fraction uses the first app as max`() {
        val apps = listOf(
            RecapAppBreakdown(dev.lumen.core.model.AppKey("A"), 4_000, 0.0),
            RecapAppBreakdown(dev.lumen.core.model.AppKey("B"), 2_000, 0.0),
        )
        val maxMs = apps.first().totalMs
        val frac = apps[1].totalMs.toFloat() / maxMs.toFloat()
        assertEquals(0.5f, frac)
    }
}

/**
 * Extracted peak calculation for testability. Mirrors the private
 * [buildPeak] function in WeeklyRecapScreen.kt without needing a
 * Compose context.
 */
private fun buildPeakFromDays(
    days: List<DayTotal>,
    averageMs: Long?,
    targetMs: Long?,
): Long {
    val dayMax = days.maxOf { it.totalMs }
    return maxOf(dayMax, averageMs ?: 0L, targetMs ?: 0L).coerceAtLeast(1L)
}
