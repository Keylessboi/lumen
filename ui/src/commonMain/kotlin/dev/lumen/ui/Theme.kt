package dev.lumen.ui

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

    /**
     * A stable colour for an app, derived from its key rather than its
     * position in a list.
     *
     * Position-based colouring means an app CHANGES COLOUR as its ranking
     * moves during the day — Terminal is orange until it overtakes Claude and
     * then it is blue. Colour reads as identity, so that is the chart telling
     * the user something untrue about which row is which, which
     * `docs/design-spec.md` puts on the uninstall side of the line.
     *
     * Keyed on the AppKey string, so the same app is the same colour in the
     * Today list, the day-detail panel, and across restarts. `String.hashCode`
     * is a specified algorithm, so this is stable across runs and platforms
     * rather than merely stable within one process.
     */
    fun colorForKey(key: String): Color = CategoryPalette[paletteIndex(key)]

    /**
     * Fixed colour per category.
     *
     * Categories are a closed set of eight plus Uncategorized, so unlike apps
     * they get a stable hand-assigned hue rather than a hash — the donut is
     * the same colours in the same order every day, which is what makes it
     * readable at a glance.
     *
     * Uncategorized is the neutral, deliberately: `docs/design-spec.md` says
     * categories never use red as a "bad" state, and not-knowing is not a
     * failure state either. It is a plain grey that recedes rather than
     * accuses.
     */
    fun colorForCategory(name: String): Color = when (name) {
        "Communication" -> CategoryPalette[1] // sky blue
        "Development" -> CategoryPalette[2]   // bluish green
        "Reading" -> CategoryPalette[4]       // blue
        "Writing" -> CategoryPalette[6]       // reddish purple
        "Browsing" -> CategoryPalette[0]      // orange
        "Media" -> CategoryPalette[5]         // vermillion
        "Games" -> CategoryPalette[3]         // yellow
        "Utilities" -> CategoryPalette[7]     // light neutral
        else -> Uncategorized                 // grey; not a judgement
    }

    /** The neutral for time Lumen cannot categorise. */
    val Uncategorized = Color(0xFF5A6272)

    private fun paletteIndex(key: String): Int =
        ((key.hashCode().toLong() and 0x7fffffffL) % CategoryPalette.size).toInt()

    /**
     * Colours for a set of apps shown together, avoiding adjacent duplicates.
     *
     * [colorForKey] alone is stable but collides: eight hues cannot separate
     * more than eight apps, and two rows next to each other in the same
     * colour reads as a rendering fault rather than as a coincidence.
     *
     * So each key takes its preferred colour, and a key whose colour is
     * already taken probes forward for a free one. The probe order is by KEY,
     * not by position in the list — so a set of apps always resolves the same
     * way regardless of which is currently on top, which is the property that
     * mattered in the first place. Colours only shift when the SET changes,
     * not when the ranking does.
     *
     * Beyond eight apps duplicates are unavoidable and reappear, in a stable
     * order.
     */
    fun colorsFor(keys: List<String>): Map<String, Color> {
        val taken = mutableSetOf<Int>()
        val assigned = mutableMapOf<String, Int>()
        for (key in keys.sorted()) {
            var idx = paletteIndex(key)
            var probes = 0
            while (idx in taken && probes < CategoryPalette.size) {
                idx = (idx + 1) % CategoryPalette.size
                probes++
            }
            taken += idx
            assigned[key] = idx
        }
        return assigned.mapValues { (_, idx) -> CategoryPalette[idx] }
    }

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
// probe
