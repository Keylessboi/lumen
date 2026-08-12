package dev.lumen.ui.charts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lumen.ui.LumenTheme
import dev.lumen.ui.formatDuration
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/**
 * One day's total, for the trend view.
 *
 * [dayUtc] is a `YYYY-MM-DD` day key. Axis labels are derived from it by the
 * chart rather than passed in: an earlier version let each caller format them
 * and macOS produced bare day-of-month numbers ("06 07 08"), which reads as
 * an hour, a week number, or nothing at all.
 */
data class DayTotal(
    val dayUtc: String,
    val totalMs: Long,
    /** True for the day currently in progress — it is a partial number. */
    val isToday: Boolean = false,
)

/** Axis label: `Wed`, or `Today` for the day in progress. */
fun DayTotal.axisLabel(): String =
    if (isToday) "Today" else weekdayShort(dayUtc)

internal fun weekdayShort(dayUtc: String): String =
    LocalDate.parse(dayUtc).dayOfWeek.name
        .lowercase()
        .replaceFirstChar { it.uppercase() }
        .take(3)

internal fun monthDay(dayUtc: String): String {
    val date = LocalDate.parse(dayUtc)
    val month = date.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)
    return "$month ${date.dayOfMonth}"
}

/** Full form for a selected day: `Tue, Aug 11`. */
fun longDayLabel(dayUtc: String): String = "${weekdayShort(dayUtc)}, ${monthDay(dayUtc)}"

/**
 * The window's span, e.g. `Aug 6 – 12`, or `Jul 28 – Aug 3` across months.
 */
fun dateRangeLabel(days: List<DayTotal>): String {
    if (days.isEmpty()) return ""
    val first = days.first().dayUtc
    val last = days.last().dayUtc
    if (first == last) return monthDay(first)
    val sameMonth = first.substring(0, 7) == last.substring(0, 7)
    return if (sameMonth) {
        "${monthDay(first)} – ${LocalDate.parse(last).dayOfMonth}"
    } else {
        "${monthDay(first)} – ${monthDay(last)}"
    }
}

/**
 * The running average across every week on record, not just the one shown.
 *
 * A seven-day window's own mean moves with whatever week you are looking at,
 * so comparing a day to it tells you about that week rather than about you.
 * Averaging over all history gives a line that stays put, which is the only
 * kind worth drawing next to a bar.
 *
 * The in-progress day is excluded: it is a partial number by definition, and
 * including it drags the average down every morning and lets it recover every
 * evening, which looks like a trend and is an artefact.
 *
 * Returns null when there is no complete day yet — a mean of nothing is not
 * zero, and drawing a zero line would invite comparison against it.
 */
fun runningDailyAverageMs(allDays: List<DayTotal>): Long? {
    val complete = allDays.filterNot { it.isToday }
    if (complete.isEmpty()) return null
    return complete.sumOf { it.totalMs } / complete.size
}

/**
 * Chart 3 of the three v1 charts (`docs/design-spec.md`): per-day totals.
 *
 * ## What it deliberately does not do
 *
 * The spec calls Lumen "a mirror, not a judge", and `docs/directions.md`
 * sharpens it here: zero valence — no "vs last week", no percentages, no
 * trend arrows, never green-good/red-bad. The average line is drawn and
 * labelled; it is not annotated with whether you are above or below it. A
 * reader can see that for themselves, and the difference between showing
 * someone their average and telling them how they are doing against it is the
 * whole distinction the spec is built on.
 *
 * The in-progress day is drawn at reduced opacity rather than flagged,
 * because a partial number at full weight beside complete ones reads as a
 * decline that has not happened.
 */
@Composable
fun DayBars(
    days: List<DayTotal>,
    modifier: Modifier = Modifier,
    averageMs: Long? = null,
    selectedDay: String? = null,
    onSelectDay: (String) -> Unit = {},
    barHeight: androidx.compose.ui.unit.Dp = 132.dp,
) {
    if (days.isEmpty()) return

    // The average can exceed every bar in a quiet week; if the scale ignored
    // it the line would sit outside the chart.
    val peak = maxOf(days.maxOf { it.totalMs }, averageMs ?: 0L).coerceAtLeast(1L)

    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(barHeight)) {
            Row(
                Modifier.fillMaxWidth().fillMaxHeight(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                days.forEach { day ->
                    val selected = day.dayUtc == selectedDay
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { onSelectDay(day.dayUtc) },
                        verticalArrangement = Arrangement.Bottom,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // Per-day time, above its bar. LO asked for the days
                        // to be labelled with their time; putting it on the
                        // bar means the chart and the numbers cannot disagree,
                        // which the spec requires of every chart.
                        Text(
                            formatDuration(day.totalMs),
                            style = TextStyle(
                                color = if (selected) LumenTheme.TextPrimary else LumenTheme.TextSecondary,
                                fontSize = 10.sp,
                                fontFamily = LumenTheme.TabularFigures,
                            ),
                        )
                        Spacer(Modifier.height(4.dp))

                        val fraction = barFraction(day.totalMs, peak)
                        // Empty space FIRST, bar second. Reversing these
                        // pushes each bar up by its own height so they hang
                        // from different lines instead of standing on one.
                        Spacer(Modifier.weight((1f - fraction).coerceAtLeast(0.0001f)))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .weight(fraction)
                                .background(
                                    color = when {
                                        selected -> LumenTheme.TextPrimary
                                        day.isToday -> LumenTheme.Accent.copy(alpha = 0.45f)
                                        else -> LumenTheme.Accent
                                    },
                                    shape = RoundedCornerShape(3.dp),
                                ),
                        )
                    }
                }
            }

            // The average, drawn across the plot. Dashes rather than a solid
            // rule so it reads as a reference and not as another bar.
            if (averageMs != null && averageMs > 0) {
                val fromTop = 1f - barFraction(averageMs, peak)
                Column(Modifier.fillMaxWidth().fillMaxHeight()) {
                    Spacer(Modifier.weight(fromTop.coerceIn(0.001f, 0.999f)))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        repeat(48) {
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(1.dp)
                                    .background(LumenTheme.TextSecondary.copy(alpha = 0.55f)),
                            )
                        }
                    }
                    Spacer(Modifier.weight((1f - fromTop).coerceIn(0.001f, 0.999f)))
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            days.forEach { day ->
                val selected = day.dayUtc == selectedDay
                Text(
                    day.axisLabel(),
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSelectDay(day.dayUtc) },
                    textAlign = TextAlign.Center,
                    style = TextStyle(
                        color = if (selected) LumenTheme.TextPrimary else LumenTheme.TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                        fontFamily = LumenTheme.TabularFigures,
                    ),
                )
            }
        }
    }
}

/**
 * Pure layout helper, extracted so it is testable without a renderer.
 * Clamped to a visible minimum: a zero-height bar is indistinguishable from
 * one that failed to render, which reads as missing data rather than an idle
 * day.
 */
fun barFraction(totalMs: Long, peakMs: Long, minimum: Float = 0.02f): Float =
    (totalMs.toFloat() / peakMs.coerceAtLeast(1L)).coerceIn(minimum, 1f)

/** Days in `[from, to]` inclusive, as `YYYY-MM-DD`, oldest first. */
internal fun dayKeysBetween(from: String, to: String): List<String> {
    var d = LocalDate.parse(from)
    val last = LocalDate.parse(to)
    val out = mutableListOf<String>()
    while (d <= last) {
        out += d.toString()
        d = d.plus(1, DateTimeUnit.DAY)
    }
    return out
}
