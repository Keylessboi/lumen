package dev.lumen.ui.charts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import dev.lumen.core.model.AppKey
import dev.lumen.core.model.RecapAppBreakdown
import dev.lumen.ui.LumenTheme
import dev.lumen.ui.SectionLabel
import dev.lumen.ui.formatDuration

/**
 * One month's total for the yearly bar chart.
 *
 * [monthIndex] is 0-11 (January-December). [label] is the short month name
 * shown on the axis (e.g. "Jan").
 */
data class MonthTotal(
    val monthIndex: Int,
    val label: String,
    val totalMs: Long,
)

/**
 * A category's time in a single month, for the evolution chart.
 */
data class CategoryMonthBreakdown(
    val monthIndex: Int,
    val category: String,
    val totalMs: Long,
)

/**
 * Year-over-year comparison.
 *
 * [previousMs] is last year's total; null means no prior year data.
 * Arrow direction is factual — up or down — not evaluative. The spec says
 * Lumen is "a mirror, not a judge": the arrow shows what happened, not
 * whether it is good.
 */
data class YearComparison(
    val currentMs: Long,
    val previousMs: Long?,
)

/**
 * The yearly recap screen.
 *
 * Shows a 12-month bar chart with monthly average line, category evolution
 * over time, and year-over-year comparison. All data arrives as parameters —
 * no ViewModel, no state management, just rendering.
 *
 * Follows TodayScreen's composable pattern: every visual decision is
 * parameterised so the caller controls what is shown.
 *
 * @param months The 12 months to display, oldest first (index 0 = January).
 *   Fewer than 12 renders as a partial year with no empty-frame artefact.
 * @param categoryEvolution Per-category breakdowns per month, for stacked
 *   bars. Empty hides the evolution section entirely.
 * @param comparison Current vs previous year totals. Null previous hides
 *   the arrow.
 * @param topApps Top apps by time for the year, ordered by totalMs
 *   descending. Empty hides the section.
 * @param monthlyAverageMs Average daily screen time across the year, for the
 *   solid reference line across the bar chart. Null hides the line.
 * @param selectedMonth Index (0-11) of the selected month, or null.
 * @param reducedMotion Disable animations when true.
 * @param onSelectMonth Callback when a month bar is tapped.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun YearlyRecapScreen(
    months: List<MonthTotal>,
    categoryEvolution: List<CategoryMonthBreakdown> = emptyList(),
    comparison: YearComparison? = null,
    topApps: List<RecapAppBreakdown> = emptyList(),
    monthlyAverageMs: Long? = null,
    selectedMonth: Int? = null,
    reducedMotion: Boolean = false,
    onSelectMonth: (Int) -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 24.dp)
    ) {
        SectionLabel("YEARLY RECAP")
        Spacer(Modifier.height(6.dp))

        // Year-over-week comparison arrow, or total if no previous year.
        comparison?.let { cmp ->
            ComparisonRow(cmp)
            Spacer(Modifier.height(16.dp))
        }

        // 12-month bar chart with optional average line overlay.
        if (months.isNotEmpty()) {
            MonthBarChart(
                months = months,
                monthlyAverageMs = monthlyAverageMs,
                selectedMonth = selectedMonth,
                onSelectMonth = onSelectMonth,
                reducedMotion = reducedMotion,
            )
            Spacer(Modifier.height(24.dp))
        }

        // Category evolution stacked bars.
        if (categoryEvolution.isNotEmpty()) {
            SectionLabel("CATEGORY EVOLUTION")
            Spacer(Modifier.height(10.dp))
            CategoryEvolutionChart(
                evolution = categoryEvolution,
                monthCount = months.size,
            )
            Spacer(Modifier.height(24.dp))
        }

        // Top apps breakdown.
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
 * Year-over-year comparison: total + directional arrow.
 *
 * The arrow is a plain Unicode character, not an icon — no external icon
 * set required, and it is readable at every scale. The percentage is
 * shown only when there is a prior year to compare against.
 */
@Composable
private fun ComparisonRow(comparison: YearComparison) {
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
                "$pct vs last year",
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
// 12-month bar chart with solid average line
// ---------------------------------------------------------------------------

/**
 * The yearly bar chart: up to 12 bars scaled to the year's peak, with a
 * solid monthly average line.
 *
 * The average line is solid (not dashed) and drawn in accent colour so it
 * reads as a distinct reference rather than as a ghost bar. This matches
 * the spec's direction for yearly views where the line is a first-class
 * element, not a secondary overlay.
 */
@Composable
private fun MonthBarChart(
    months: List<MonthTotal>,
    monthlyAverageMs: Long?,
    selectedMonth: Int?,
    onSelectMonth: (Int) -> Unit,
    reducedMotion: Boolean,
) {
    val peak = buildMonthPeak(months, monthlyAverageMs)

    Column(Modifier.fillMaxWidth()) {
        // The bars.
        Box(Modifier.fillMaxWidth().height(168.dp)) {
            Row(
                Modifier.fillMaxWidth().fillMaxHeight(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                months.forEach { month ->
                    MonthBar(
                        month = month,
                        peak = peak,
                        selected = month.monthIndex == selectedMonth,
                        onSelect = { onSelectMonth(month.monthIndex) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // Solid average line overlay — accent, not dashed.
            if (monthlyAverageMs != null && monthlyAverageMs > 0 && monthlyAverageMs <= peak) {
                SolidLineOverlay(
                    fraction = barFraction(monthlyAverageMs, peak),
                    color = LumenTheme.Accent,
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Month axis labels.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            months.forEach { month ->
                val selected = month.monthIndex == selectedMonth
                Text(
                    month.label,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSelectMonth(month.monthIndex) },
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

        // Legend for the average line.
        if (monthlyAverageMs != null) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(
                    Modifier
                        .width(16.dp)
                        .height(2.dp)
                        .background(LumenTheme.Accent),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Monthly average — ${formatDuration(monthlyAverageMs)}",
                    style = TextStyle(
                        color = LumenTheme.TextSecondary,
                        fontSize = 11.sp,
                        fontFamily = LumenTheme.TabularFigures,
                        fontFeatureSettings = "tnum",
                    ),
                )
            }
        }
    }
}

/** Single month bar with its time label above. */
@Composable
private fun MonthBar(
    month: MonthTotal,
    peak: Long,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onSelect),
        verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            formatDuration(month.totalMs),
            style = TextStyle(
                color = if (selected) LumenTheme.TextPrimary else LumenTheme.TextSecondary,
                fontSize = 10.sp,
                fontFamily = LumenTheme.TabularFigures,
            ),
        )
        Spacer(Modifier.height(4.dp))

        val fraction = barFraction(month.totalMs, peak)
        Spacer(Modifier.weight((1f - fraction).coerceAtLeast(0.0001f)))
        Box(
            Modifier
                .fillMaxWidth()
                .weight(fraction)
                .background(
                    color = if (selected) LumenTheme.TextPrimary else LumenTheme.Accent,
                    shape = RoundedCornerShape(3.dp),
                ),
        )
    }
}

/**
 * A horizontal solid line drawn at [fraction] of the chart height.
 *
 * Uses weight-based positioning so the line is always at the correct
 * y-coordinate regardless of chart height.
 */
@Composable
private fun SolidLineOverlay(
    fraction: Float,
    color: Color,
) {
    val fromTop = 1f - fraction
    Column(Modifier.fillMaxWidth().fillMaxHeight()) {
        Spacer(Modifier.weight(fromTop.coerceIn(0.001f, 0.999f)))
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(color),
        )
        Spacer(Modifier.weight((1f - fromTop).coerceIn(0.001f, 0.999f)))
    }
}

// ---------------------------------------------------------------------------
// Category evolution — stacked bars per month
// ---------------------------------------------------------------------------

/**
 * Stacked bars showing how category distribution changes across months.
 *
 * Each month's bar is divided into category segments proportional to that
 * category's time in the month. Categories use the same fixed colour as
 * everywhere else in the app (via [LumenTheme.colorForCategory]).
 *
 * Uncategorized is shown, never hidden — dropping it would make bars
 * shorter than the month total, quietly overstating everything else.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryEvolutionChart(
    evolution: List<CategoryMonthBreakdown>,
    monthCount: Int,
) {
    if (evolution.isEmpty()) return

    // Group by monthIndex, then sort categories within each month by totalMs.
    val byMonth = evolution.groupBy { it.monthIndex }
        .toSortedMap()

    // Collect the full set of category names, ordered by typical prominence
    // (total across all months, descending). Uncategorized last.
    val categoryOrder = evolution
        .groupBy { it.category }
        .mapValues { (_, slices) -> slices.sumOf { it.totalMs } }
        .entries
        .sortedWith(
            compareBy({ it.key == "Uncategorized" }, { -it.value }),
        )
        .map { it.key }

    Column(Modifier.fillMaxWidth()) {
        // Stacked bars — one per month.
        Row(
            Modifier.fillMaxWidth().height(80.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            for (i in 0 until monthCount) {
                val monthSlices = byMonth[i] ?: emptyList()
                val monthTotal = monthSlices.sumOf { it.totalMs }

                Row(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(3.dp)),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    if (monthTotal > 0) {
                        categoryOrder.forEach { cat ->
                            val slice = monthSlices.find { it.category == cat }
                            val sliceMs = slice?.totalMs ?: 0L
                            if (sliceMs > 0) {
                                val fraction = (sliceMs.toFloat() / monthTotal).coerceAtLeast(0.004f)
                                Box(
                                    Modifier
                                        .weight(fraction)
                                        .fillMaxHeight()
                                        .background(LumenTheme.colorForCategory(cat)),
                                )
                            }
                        }
                    } else {
                        // Empty month: faint divider so the column is visible.
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .background(LumenTheme.Divider),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Category legend — wrapped so it reflows on narrow windows.
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            categoryOrder.forEach { cat ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(LumenTheme.colorForCategory(cat)),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        cat,
                        style = TextStyle(
                            color = LumenTheme.TextSecondary,
                            fontSize = 11.sp,
                        ),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Top apps breakdown
// ---------------------------------------------------------------------------

/**
 * Horizontal bars for the top apps, sorted by totalMs descending.
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
 * The peak value for the chart, including the average if it exceeds every
 * bar. The line must stay inside the chart — drawing it outside is a
 * layout fault.
 */
private fun buildMonthPeak(months: List<MonthTotal>, averageMs: Long?): Long {
    val monthMax = months.maxOf { it.totalMs }
    return maxOf(monthMax, averageMs ?: 0L).coerceAtLeast(1L)
}

// ---------------------------------------------------------------------------
// Preview
// ---------------------------------------------------------------------------

/**
 * Compose preview data for IDE rendering.
 *
 * Uses 12 months of realistic-looking totals and sample breakdowns
 * so the preview renders without needing a running RecapEngine.
 */
@Composable
fun YearlyRecapScreenPreview() {
    val months = listOf(
        MonthTotal(0, "Jan", 98_000_000),
        MonthTotal(1, "Feb", 85_000_000),
        MonthTotal(2, "Mar", 110_000_000),
        MonthTotal(3, "Apr", 92_000_000),
        MonthTotal(4, "May", 78_000_000),
        MonthTotal(5, "Jun", 105_000_000),
        MonthTotal(6, "Jul", 115_000_000),
        MonthTotal(7, "Aug", 120_000_000),
        MonthTotal(8, "Sep", 95_000_000),
        MonthTotal(9, "Oct", 88_000_000),
        MonthTotal(10, "Nov", 75_000_000),
        MonthTotal(11, "Dec", 100_000_000),
    )

    val categoryEvolution = listOf(
        CategoryMonthBreakdown(0, "Development", 35_000_000),
        CategoryMonthBreakdown(0, "Browsing", 25_000_000),
        CategoryMonthBreakdown(0, "Communication", 18_000_000),
        CategoryMonthBreakdown(0, "Media", 12_000_000),
        CategoryMonthBreakdown(0, "Uncategorized", 8_000_000),
        CategoryMonthBreakdown(6, "Development", 42_000_000),
        CategoryMonthBreakdown(6, "Browsing", 28_000_000),
        CategoryMonthBreakdown(6, "Communication", 22_000_000),
        CategoryMonthBreakdown(6, "Media", 15_000_000),
        CategoryMonthBreakdown(6, "Uncategorized", 8_000_000),
        CategoryMonthBreakdown(11, "Development", 38_000_000),
        CategoryMonthBreakdown(11, "Browsing", 30_000_000),
        CategoryMonthBreakdown(11, "Communication", 15_000_000),
        CategoryMonthBreakdown(11, "Media", 10_000_000),
        CategoryMonthBreakdown(11, "Uncategorized", 7_000_000),
    )

    val topApps = listOf(
        RecapAppBreakdown(AppKey("Terminal"), 120_000_000, 16.0),
        RecapAppBreakdown(AppKey("Chrome"), 95_000_000, 12.5),
        RecapAppBreakdown(AppKey("VS Code"), 85_000_000, 11.2),
        RecapAppBreakdown(AppKey("Figma"), 45_000_000, 6.0),
        RecapAppBreakdown(AppKey("Slack"), 35_000_000, 4.6),
    )

    YearlyRecapScreen(
        months = months,
        categoryEvolution = categoryEvolution,
        comparison = YearComparison(currentMs = 1_161_000_000, previousMs = 1_050_000_000),
        topApps = topApps,
        monthlyAverageMs = 96_750_000,
        reducedMotion = false,
    )
}
