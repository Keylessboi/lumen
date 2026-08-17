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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lumen.ui.LumenTheme
import dev.lumen.ui.SectionLabel
import dev.lumen.ui.formatDuration

/**
 * Monthly screentime recap — 30-day bar chart with weekly subtotals,
 * category breakdown, target line overlay, and month-over-month comparison.
 *
 * Follows TodayScreen's composable pattern: all data as parameters, no
 * ViewModel. The caller computes categories, daily totals, and comparisons
 * from [RecapPeriod][dev.lumen.core.model.RecapPeriod] + history.
 */
@Composable
fun MonthlyRecapScreen(
    monthLabel: String,
    days: List<DayTotal>,
    totalMs: Long,
    targetMs: Long?,
    categories: List<CategorySlice>,
    previousMonthMs: Long?,
    modifier: Modifier = Modifier,
) {
    if (days.isEmpty()) return

    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp)
    ) {
        SectionLabel("MONTH RECAP")
        Spacer(Modifier.height(6.dp))

        Text(
            monthLabel,
            style = TextStyle(
                color = LumenTheme.TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
            ),
        )

        Spacer(Modifier.height(4.dp))

        // Big number — tabular figures so digits do not jitter.
        Text(
            formatDuration(totalMs),
            style = TextStyle(
                color = LumenTheme.TextPrimary,
                fontSize = 44.sp,
                fontWeight = FontWeight.Light,
                fontFamily = LumenTheme.TabularFigures,
                fontFeatureSettings = "tnum",
                fontSynthesis = FontSynthesis.None,
            ),
        )

        // Month-over-month comparison. Neutral phrasing per the design spec:
        // no "good"/"bad", just the number and the percentage.
        if (previousMonthMs != null && previousMonthMs > 0) {
            Spacer(Modifier.height(4.dp))
            val deltaMs = totalMs - previousMonthMs
            val deltaPercent = ((deltaMs.toFloat() / previousMonthMs) * 100).toInt()
            val sign = if (deltaMs >= 0) "+" else ""
            Text(
                "vs last month: ${formatDuration(previousMonthMs)} ($sign$deltaPercent%)",
                style = TextStyle(
                    color = LumenTheme.TextSecondary,
                    fontSize = 13.sp,
                    fontFamily = LumenTheme.TabularFigures,
                    fontFeatureSettings = "tnum",
                ),
            )
        }

        // Category breakdown — reuses CategoryBar's stacked-bar pattern.
        // Uncategorized is shown, never hidden, per the spec.
        if (categories.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            CategoryBar(categories)
        }

        // 30-day bar chart with weekly subtotals and target line.
        Spacer(Modifier.height(24.dp))
        SectionLabel("DAILY BARS")
        Spacer(Modifier.height(10.dp))

        MonthlyBars(days = days, targetMs = targetMs)
    }
}

// ---------------------------------------------------------------------------
// Internal chart composables
// ---------------------------------------------------------------------------

/**
 * 30-day bar chart with weekly subtotals and target line overlay.
 *
 * Bars follow the same visual language as [DayBars] — accent-colored,
 * bottom-aligned. The target line is a dashed accent-colored overlay so it
 * reads as a reference, not as another bar. Weekly subtotals are shown
 * below the axis, each weighted to match its group's bar count.
 */
@Composable
private fun MonthlyBars(
    days: List<DayTotal>,
    targetMs: Long?,
) {
    if (days.isEmpty()) return

    val peak = days.maxOf { it.totalMs }.coerceAtLeast(1L)
    // Scale includes the target so the line sits inside the chart area.
    val scalePeak = maxOf(peak, targetMs ?: 0L).coerceAtLeast(1L)

    Column(Modifier.fillMaxWidth()) {
        // ---- bar chart area ----
        Box(Modifier.fillMaxWidth().height(168.dp)) {
            Row(
                Modifier.fillMaxWidth().fillMaxHeight(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                days.forEach { day ->
                    val fraction = barFraction(day.totalMs, scalePeak)
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.Bottom,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Spacer(Modifier.weight((1f - fraction).coerceAtLeast(0.0001f)))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .weight(fraction)
                                .background(
                                    color = when {
                                        day.isToday -> LumenTheme.Accent.copy(alpha = 0.45f)
                                        else -> LumenTheme.Accent
                                    },
                                    shape = RoundedCornerShape(2.dp),
                                ),
                        )
                    }
                }
            }

            // ---- target line overlay ----
            // Dashed accent-colored line at the target level. The dash
            // pattern matches DayBars' average line — small boxes with gaps
            // — but uses Accent instead of TextSecondary.
            if (targetMs != null && targetMs > 0) {
                val fromTop = 1f - barFraction(targetMs, scalePeak)
                Column(Modifier.fillMaxWidth().fillMaxHeight()) {
                    Spacer(Modifier.weight(fromTop.coerceIn(0.001f, 0.999f)))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        repeat(60) {
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(1.dp)
                                    .background(LumenTheme.Accent.copy(alpha = 0.7f)),
                            )
                        }
                    }
                    Spacer(Modifier.weight((1f - fromTop).coerceIn(0.001f, 0.999f)))
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // ---- weekly subtotals ----
        WeeklySubtotalsRow(days)
    }
}

/**
 * Weekly subtotals below the bar chart.
 *
 * Groups the days into 7-day chunks (Mon–Sun alignment is left to the
 * caller) and renders one duration label per group, weighted to match the
 * bar widths above. Tabular figures keep the column stable as values change.
 */
@Composable
private fun WeeklySubtotalsRow(days: List<DayTotal>) {
    if (days.isEmpty()) return

    val weeks = days.chunked(7)

    Row(Modifier.fillMaxWidth()) {
        weeks.forEach { weekDays ->
            val weekTotal = weekDays.sumOf { it.totalMs }
            Text(
                formatDuration(weekTotal),
                modifier = Modifier.weight(weekDays.size.toFloat()),
                textAlign = TextAlign.Center,
                style = TextStyle(
                    color = LumenTheme.TextSecondary,
                    fontSize = 9.sp,
                    fontFamily = LumenTheme.TabularFigures,
                    fontFeatureSettings = "tnum",
                ),
            )
        }
    }
}
