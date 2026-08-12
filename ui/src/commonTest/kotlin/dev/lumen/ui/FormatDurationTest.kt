package dev.lumen.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every number the user sees passes through here, and the spec is explicit
 * that numbers and charts must agree — so the formatter is part of the chart
 * being honest, not just cosmetics.
 */
class FormatDurationTest {

    @Test
    fun `zero reads as zero seconds, not blank`() {
        assertEquals("0s", formatDuration(0))
    }

    @Test
    fun `sub-minute usage keeps its seconds`() {
        assertEquals("1s", formatDuration(1_000))
        assertEquals("59s", formatDuration(59_999))
    }

    @Test
    fun `minutes and hours roll over correctly`() {
        assertEquals("1m", formatDuration(60_000))
        assertEquals("59m", formatDuration(59 * 60_000))
        assertEquals("1h 0m", formatDuration(3_600_000))
        assertEquals("4h 20m", formatDuration(4 * 3_600_000 + 20 * 60_000))
    }

    @Test
    fun `it truncates rather than rounds, so a total never exceeds reality`() {
        // 119.999s is 1m, not 2m. Rounding up would let a column of rows sum
        // to more than the day they came from.
        assertEquals("1m", formatDuration(119_999))
    }

    @Test
    fun `negative input cannot render a negative duration`() {
        // A backwards clock step can produce one; showing "-3m" would be
        // worse than showing nothing.
        assertEquals("0m", formatDuration(-1))
        assertEquals("0m", formatDuration(-3_600_000))
    }

    @Test
    fun `long durations stay in hours rather than overflowing to a new unit`() {
        assertEquals("24h 0m", formatDuration(24 * 3_600_000))
        assertEquals("100h 30m", formatDuration(100 * 3_600_000 + 30 * 60_000))
    }

    @Test
    fun `the format never contains a decimal point`() {
        // Tabular figures are pinned by the spec; a stray decimal would make
        // columns jitter as values change.
        listOf(0L, 999L, 61_000L, 3_661_000L, 359_999_999L).forEach {
            assertTrue(!formatDuration(it).contains('.'), "decimal in ${formatDuration(it)}")
        }
    }
}
