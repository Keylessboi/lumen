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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lumen.core.model.AppKey
import dev.lumen.core.model.RecapAppBreakdown
import dev.lumen.core.model.RecapPeriod
import dev.lumen.ui.LumenTheme
import dev.lumen.ui.SectionLabel
import dev.lumen.ui.formatDuration

/**
 * Week-over-week trend indicator.
 *
 * [previousMs] is the total from the prior week; null means no prior data.
 * Arrow direction is factual — up or down — not evaluative. The spec says
 * Lumen is "a mirror, not a judge": the arrow shows what happened, not
 * whether it is good.
 */
data class WeekComparison(
    val currentMs: Long,
    val previousMs: Long?,
)

/**
 * The weekly recap screen.
 *
 * Shows the 7-day bar chart with target line, top 5 apps breakdown, and
 * week-over-week comparison. All data arrives as parameters — no ViewModel,
 * no state management, just rendering.
 *
 * Follows TodayScreen's composable pattern: every visual decision is
 * parameterised so the caller controls what is shown.
 *
 * @param days The 7 days to display, oldest first. Exactly 7 for a full
 *   week; fewer renders as a partial week with no empty-frame artefact.
 * @param comparison Current vs previous week totals. Null previous hides
 *   the arrow.
 * @param topApps Top 5 apps by time, ordered by totalMs descending. Empty
 *   hides the section.
 * @param averageMs All-time daily average, for the dashed reference line
 *   across the bar chart. Null hides the line.
 * @param reducedMotion Disable animations when true.
 * @param onSelectDay Callback when a bar is tapped.
 */
@Composable
fun WeeklyRecapScreen(
    days: List<DayTotal>,
    comparison: WeekComparison? = null,
    topApps: List<RecapAppBreakdown> = emptyList(),
    averageMs: Long? = null,
    targetMs: Long? = null,
    reducedMotion: Boolean = false,
    selectedDay: String? = null,
    onSelectDay: (String) -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 24.dp)
    ) {
        SectionLabel("WEEKLY RECAP")
        Spacer(Modifier.height(6.dp))

        // Week-over-week comparison arrow, or total if no previous week.
        comparison?.let { cmp ->
            ComparisonRow(cmp)
            Spacer(Modifier.height(16.dp))
        }

        // 7-day bar chart with optional target line overlay.
        if (days.isNotEmpty()) {
            WeekBarChart(
                days = days,
                averageMs = averageMs,
                targetMs = targetMs,
                selectedDay = selectedDay,
                onSelectDay = onSelectDay,
                reducedMotion = reducedMotion,
            )
            Spacer(Modifier.height(24.dp))
        }

        // Top 5 apps breakdown.
        if (topApps.isNotEmpty()) {
            SectionLabel("TOP APPS")
            Spacer(Modifier.height(10.dp))
            TopAppsBreakdown(topApps)
        }
    }
}

// ---------------------------------------------------------------------------
// Comparison row
// ---------------------------------------------------------------------------

/**
 * Week-over-week comparison: total + directional arrow.
 *
 * The arrow is a plain Unicode character, not an icon — no external icon
 * set required, and it is readable at every scale. The percentage is
 * shown only when there is a prior week to compare against.
 */
@Composable
private fun ComparisonRow(comparison: WeekComparison) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            formatDuration(comparison.currentMs),
            style = TextStyle(
                color = LumenTheme.TextPrimary,
                fontSize = 36.sp,
                fontWeight = FontWeight.Light,
                fontFamily = LumenTheme.TabularFigures,
                fontFeatureSettings = "tnum",
            ),
        )

        comparison.previousMs?.let { prev ->
            Spacer(Modifier.width(14.dp))
            val delta = comparison.currentMs - prev
            val pct = if (prev > 0) {
                (delta.toDouble() / prev * 100).let {
                    if (it >= 0) "+${"%.0f".format(it)}" else "${"%.0f".format(it)}%"
                }
            } else "—"

            val arrow = when {
                delta > 0 -> "↑"
                delta < 0 -> "↓"
                else -> "→"
            }
            val arrowColor = when {
                delta > 0 -> LumenTheme.Accent.copy(alpha = 0.8f)
                delta < 0 -> LumenTheme.TextSecondary
                else -> LumenTheme.TextSecondary
            }

            Text(
                arrow,
                style = TextStyle(
                    color = arrowColor,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "$pct vs last week",
                style = TextStyle(
                    color = LumenTheme.TextSecondary,
                    fontSize = 12.sp,
                    fontFamily = LumenTheme.TabularFigures,
                    fontFeatureSettings = "tnum",
                ),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 7-day bar chart with target line overlay
// ---------------------------------------------------------------------------

/**
 * The weekly bar chart: 7 bars scaled to the week's peak, with an optional
 * dashed target line.
 *
 * The target line is drawn the same way as the average line in DayBars —
 * a row of small spacers that produce a dashed effect — but in accent
 * colour rather than secondary, so it is visually distinct from the
 * average.
 */
@Composable
private fun WeekBarChart(
    days: List<DayTotal>,
    averageMs: Long?,
    targetMs: Long?,
    selectedDay: String?,
    onSelectDay: (String) -> Unit,
    reducedMotion: Boolean,
) {
    val peak = buildPeak(days, averageMs, targetMs)

    Column(Modifier.fillMaxWidth()) {
        // The bars.
        Box(Modifier.fillMaxWidth().height(168.dp)) {
            Row(
                Modifier.fillMaxWidth().fillMaxHeight(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                // Bars are rendered inline so .weight() has RowScope
                // access — same pattern as DayBars.kt.
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

            // Target line overlay — dashed accent, drawn across the chart.
            if (targetMs != null && targetMs > 0 && targetMs <= peak) {
                DashedLineOverlay(
                    fraction = barFraction(targetMs, peak),
                    color = LumenTheme.Accent,
                    label = "Target",
                )
            }

            // Average line overlay — dashed secondary.
            if (averageMs != null && averageMs > 0 && averageMs <= peak) {
                DashedLineOverlay(
                    fraction = barFraction(averageMs, peak),
                    color = LumenTheme.TextSecondary.copy(alpha = 0.55f),
                    label = "Average",
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Weekday axis labels.
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

        // Legend for the dashed lines.
        if (targetMs != null || averageMs != null) {
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (targetMs != null) {
                    DashedLegend("Target", LumenTheme.Accent)
                }
                if (averageMs != null) {
                    DashedLegend("Average", LumenTheme.TextSecondary.copy(alpha = 0.55f))
                }
            }
        }
    }
}

/**
 * A horizontal dashed line drawn at [fraction] of the chart height.
 *
 * Uses the same technique as DayBars: a row of small spacers with gaps
 * between them, placed via weight-based positioning so the line is always
 * at the correct y-coordinate regardless of chart height.
 */
@Composable
private fun DashedLineOverlay(
    fraction: Float,
    color: Color,
    label: String,
) {
    val fromTop = 1f - fraction
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
                        .background(color),
                )
            }
        }
        Spacer(Modifier.weight((1f - fromTop).coerceIn(0.001f, 0.999f)))
    }
}

/** Legend entry for a dashed reference line. */
@Composable
private fun DashedLegend(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(4) {
                Spacer(
                    Modifier
                        .width(4.dp)
                        .height(1.dp)
                        .background(color),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = TextStyle(
                color = LumenTheme.TextSecondary,
                fontSize = 11.sp,
                fontFamily = LumenTheme.TabularFigures,
                fontFeatureSettings = "tnum",
            ),
        )
    }
}

// ---------------------------------------------------------------------------
// Top 5 apps breakdown
// ---------------------------------------------------------------------------

/**
 * Horizontal bars for the top 5 apps, sorted by totalMs descending.
 *
 * Each row shows: app name, proportional bar, time. The bar width is
 * scaled to the largest app in the set, so the longest bar fills the
 * available width and all others are proportional to it.
 *
 * Colour comes from the app key, not from position — same app, same
 * colour, everywhere it appears.
 */
@Composable
private fun TopAppsBreakdown(topApps: List<RecapAppBreakdown>) {
    if (topApps.isEmpty()) return

    val sorted = topApps.sortedByDescending { it.totalMs }
    val maxMs = sorted.first().totalMs.coerceAtLeast(1L)
    val colors = LumenTheme.colorsFor(sorted.map { it.appKey.value })

    Column(Modifier.fillMaxWidth()) {
        sorted.take(5).forEach { app ->
            TopAppRow(
                app = app,
                fraction = app.totalMs.toFloat() / maxMs.toFloat(),
                color = colors[app.appKey.value]
                    ?: LumenTheme.colorForKey(app.appKey.value),
            )
        }
    }
}

/** One app row: name, bar, time. */
@Composable
private fun TopAppRow(
    app: RecapAppBreakdown,
    fraction: Float,
    color: Color,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            app.appKey.value,
            modifier = Modifier.width(140.dp),
            maxLines = 1,
            style = TextStyle(color = LumenTheme.TextPrimary, fontSize = 13.sp),
        )

        Spacer(Modifier.width(12.dp))

        Box(
            Modifier
                .weight(1f)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(LumenTheme.Divider)
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                    .clip(RoundedCornerShape(4.dp))
                    .background(color),
            )
        }

        Spacer(Modifier.width(12.dp))

        Text(
            formatDuration(app.totalMs),
            style = TextStyle(
                color = LumenTheme.TextSecondary,
                fontSize = 13.sp,
                fontFamily = LumenTheme.TabularFigures,
                fontFeatureSettings = "tnum",
            ),
            modifier = Modifier.width(LumenTheme.TimeColumnWidth),
            textAlign = TextAlign.End,
        )
    }
}

// ---------------------------------------------------------------------------
// Layout helpers
// ---------------------------------------------------------------------------

/**
 * The peak value for the chart, including the average and target if they
 * exceed every bar. The line must stay inside the chart — drawing it
 * outside is a layout fault.
 */
private fun buildPeak(days: List<DayTotal>, averageMs: Long?, targetMs: Long?): Long {
    val dayMax = days.maxOf { it.totalMs }
    return maxOf(dayMax, averageMs ?: 0L, targetMs ?: 0L).coerceAtLeast(1L)
}

/**
 * Compose preview data for IDE rendering.
 *
 * Uses 7 days of realistic-looking totals and a sample top-apps list
 * so the preview renders without needing a running RecapEngine.
 */
@Composable
fun WeeklyRecapScreenPreview() {
    val days = listOf(
        DayTotal("2026-08-10", 3_600_000),
        DayTotal("2026-08-11", 5_400_000),
        DayTotal("2026-08-12", 2_100_000),
        DayTotal("2026-08-13", 4_800_000),
        DayTotal("2026-08-14", 6_200_000),
        DayTotal("2026-08-15", 1_800_000),
        DayTotal("2026-08-16", 4_200_000, isToday = true),
    )
    val topApps = listOf(
        RecapAppBreakdown(AppKey("Terminal"), 3_600_000, 28.0),
        RecapAppBreakdown(AppKey("Chrome"), 2_800_000, 22.0),
        RecapAppBreakdown(AppKey("VS Code"), 2_100_000, 16.0),
        RecapAppBreakdown(AppKey("Figma"), 1_500_000, 12.0),
        RecapAppBreakdown(AppKey("Slack"), 900_000, 7.0),
    )

    WeeklyRecapScreen(
        days = days,
        comparison = WeekComparison(currentMs = 28_100_000, previousMs = 32_400_000),
        topApps = topApps,
        averageMs = 4_300_000,
        targetMs = 5_000_000,
        reducedMotion = false,
    )
}
