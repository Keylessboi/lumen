package dev.lumen.ui.charts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import dev.lumen.ui.LumenTheme
import dev.lumen.ui.formatDuration

/**
 * One day's total, for the trend view.
 *
 * [dayUtc] is a `YYYY-MM-DD` UTC day — the same key the rollup table uses,
 * so a day here is the same day everywhere (`docs/data-model.md`).
 * [label] is the short human form ("Mon", "12"); the caller formats it,
 * because the UI module has no business owning locale rules.
 */
data class DayTotal(
    val dayUtc: String,
    val label: String,
    val totalMs: Long,
    /** True for the day currently in progress — it is a partial number. */
    val isToday: Boolean = false,
)

/**
 * Chart type 3 of the three v1 charts (`docs/design-spec.md`): per-day
 * totals, trend view.
 *
 * ## What it deliberately does not do
 *
 * The spec calls Lumen "a mirror, not a judge", and `docs/directions.md`
 * sharpens it for exactly this surface: zero valence — **no "vs last week",
 * no percentages, no trend arrows**, and never green-good/red-bad. So this
 * draws the bars and the numbers and stops. A reader can see their own trend;
 * they do not need the app to have an opinion about it.
 *
 * The in-progress day is drawn at reduced opacity rather than flagged,
 * because a partial number sitting at full weight next to complete ones reads
 * as a decline that has not happened yet.
 *
 * Bars are scaled against the largest day in the window, and the scale is
 * stated in the caption. A chart whose baseline is invisible is the "charts
 * that lie" case the spec puts on the uninstall side of the line.
 */
@Composable
fun DayBars(
    days: List<DayTotal>,
    modifier: Modifier = Modifier,
    barHeight: androidx.compose.ui.unit.Dp = 96.dp,
) {
    if (days.isEmpty()) return

    val peak = days.maxOf { it.totalMs }.coerceAtLeast(1L)

    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(barHeight),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            days.forEach { day ->
                Column(
                    Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.Bottom,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // A day with no recorded time still gets a visible sliver,
                    // so "nothing recorded" is distinguishable from "no bar
                    // drawn here" — an absent bar reads as missing data.
                    val fraction = barFraction(day.totalMs, peak)
                    // Empty space FIRST, bar second. Reversing these pushes
                    // each bar up by its own height, so they hang from
                    // different lines instead of standing on one — which
                    // makes the chart unreadable and, worse, plausible.
                    Spacer(Modifier.weight((1f - fraction).coerceAtLeast(0.0001f)))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .weight(fraction)
                            .background(
                                color = if (day.isToday) {
                                    LumenTheme.Accent.copy(alpha = 0.45f)
                                } else {
                                    LumenTheme.Accent
                                },
                                shape = RoundedCornerShape(3.dp),
                            ),
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            days.forEach { day ->
                Text(
                    day.label,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = TextStyle(
                        color = LumenTheme.TextSecondary,
                        fontSize = 11.sp,
                        fontFamily = LumenTheme.TabularFigures,
                    ),
                )
            }
        }
    }
}

/**
 * The trend view with its heading and scale caption — the whole section as
 * the Today screen uses it.
 *
 * [title] and the caption are passed rather than built here so the copy stays
 * with the screen that shows it; `docs/design-spec.md` owns the words.
 */
@Composable
fun DayBarsSection(
    title: String,
    days: List<DayTotal>,
    modifier: Modifier = Modifier,
) {
    if (days.isEmpty()) return
    val peak = days.maxOf { it.totalMs }

    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = TextStyle(
                    color = LumenTheme.TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.2.sp,
                ),
            )
            Spacer(Modifier.weight(1f))
            // State the scale. A bar chart without one invites the reader to
            // compare heights across screens where the peak differs.
            Text(
                "tallest ${formatDuration(peak)}",
                style = TextStyle(
                    color = LumenTheme.TextSecondary,
                    fontSize = 11.sp,
                    fontFamily = LumenTheme.TabularFigures,
                ),
            )
        }
        Spacer(Modifier.height(14.dp))
        DayBars(days)
    }
}

/**
 * Pure layout helper, extracted so it is testable without a renderer: the
 * bar fraction for a day against the window's peak.
 *
 * Clamped to a visible minimum for the reason in [DayBars] — a zero-height
 * bar is indistinguishable from a missing one.
 */
fun barFraction(totalMs: Long, peakMs: Long, minimum: Float = 0.02f): Float =
    (totalMs.toFloat() / peakMs.coerceAtLeast(1L)).coerceIn(minimum, 1f)
