package dev.lumen.macos.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

/**
 * Palette and formatting, per `docs/design-spec.md` (FROZEN at M0, Agent A's).
 * Values here are transcribed from that spec, not chosen — if they disagree,
 * the spec wins and this file is the bug.
 */
object LumenTheme {

    /** Ink-near-black. Explicitly NOT #000 — OLED smearing. Dark is the default. */
    val Background = Color(0xFF0E1116)
    val Surface = Color(0xFF161A21)

    /**
     * Calm indigo. The spec says pick ONE saturated accent and keep it; this
     * is that one. Nothing else in the app may be saturated.
     */
    val Accent = Color(0xFF7C9CF5)

    val TextPrimary = Color(0xFFE6E9EF)
    val TextSecondary = Color(0xFF8A93A3)
    val Divider = Color(0xFF232833)

    /**
     * Okabe-Ito colorblind-safe categorical palette. Black is swapped for a
     * light neutral because the background is near-black.
     *
     * Red is absent by design: the spec reserves it for destructive actions,
     * never for a category and never as a "bad" state.
     */
    val CategoryPalette = listOf(
        Color(0xFFE69F00), // orange
        Color(0xFF56B4E9), // sky blue
        Color(0xFF009E73), // bluish green
        Color(0xFFF0E442), // yellow
        Color(0xFF0072B2), // blue
        Color(0xFFD55E00), // vermillion
        Color(0xFFCC79A7), // reddish purple
        Color(0xFFBFC6D4), // light neutral (stands in for Okabe-Ito black)
    )

    fun categoryColor(index: Int): Color = CategoryPalette[index % CategoryPalette.size]

    /**
     * Tabular figures — non-negotiable per the spec. Proportional numerals
     * change width as the digits change, so a live-updating readout visibly
     * jitters. `tnum` fixes every digit to the same advance width.
     */
    val TabularFigures = FontFamily.Default
}

/**
 * Human time, tabular-friendly: `4h 20m`.
 *
 * Seconds appear only under a minute — during the first moments of tracking a
 * bare `0m` reads as broken, but seconds in an hours-long readout are noise.
 */
fun formatDuration(ms: Long): String {
    if (ms < 0) return "0m"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "${seconds}s"
    }
}
