package dev.lumen.ui.charts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lumen.ui.AppTotal
import dev.lumen.ui.LumenTheme
import dev.lumen.ui.formatDuration

/**
 * A day's detail, shown when its bar is tapped.
 *
 * [totals] are that day's per-app times; empty means the day was recorded and
 * had nothing in it, which is different from the day being unavailable.
 */
data class DayDetail(
    val dayUtc: String,
    val totalMs: Long,
    val totals: List<AppTotal>,
)

/**
 * The trend view: heading, running average, bars, and the detail panel for a
 * selected day.
 */
@Composable
fun DayBarsSection(
    title: String,
    days: List<DayTotal>,
    modifier: Modifier = Modifier,
    averageMs: Long? = null,
    selectedDay: String? = null,
    detail: DayDetail? = null,
    onSelectDay: (String) -> Unit = {},
    onClearSelection: () -> Unit = {},
) {
    if (days.isEmpty()) return

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
            // Anchors the weekday axis to real dates: weekdays alone say
            // which days, never which week.
            Text(
                dateRangeLabel(days),
                style = TextStyle(
                    color = LumenTheme.TextSecondary,
                    fontSize = 11.sp,
                    fontFamily = LumenTheme.TabularFigures,
                ),
            )
        }

        Spacer(Modifier.height(14.dp))

        DayBars(
            days = days,
            averageMs = averageMs,
            selectedDay = selectedDay,
            onSelectDay = onSelectDay,
        )

        if (averageMs != null) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // A short dashed swatch, so the label is tied to the line in
                // the plot without needing colour to do it.
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    repeat(4) {
                        Spacer(
                            Modifier
                                .width(4.dp)
                                .height(1.dp)
                                .background(LumenTheme.TextSecondary.copy(alpha = 0.55f)),
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                // Named for what it is. "Daily average" alone would be read
                // as this week's, which is the thing it deliberately is not.
                Text(
                    "Daily average, all weeks — ${formatDuration(averageMs)}",
                    style = TextStyle(
                        color = LumenTheme.TextSecondary,
                        fontSize = 11.sp,
                        fontFamily = LumenTheme.TabularFigures,
                    ),
                )
            }
        }

        if (detail != null) {
            Spacer(Modifier.height(18.dp))
            DayDetailPanel(detail = detail, onClose = onClearSelection)
        }
    }
}

@Composable
private fun DayDetailPanel(detail: DayDetail, onClose: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(LumenTheme.Surface)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                longDayLabel(detail.dayUtc),
                style = TextStyle(
                    color = LumenTheme.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                formatDuration(detail.totalMs),
                style = TextStyle(
                    color = LumenTheme.TextSecondary,
                    fontSize = 14.sp,
                    fontFamily = LumenTheme.TabularFigures,
                ),
            )
            Spacer(Modifier.weight(1f))
            Text(
                "Close",
                modifier = Modifier.clickable { onClose() },
                style = TextStyle(color = LumenTheme.Accent, fontSize = 12.sp),
            )
        }

        Spacer(Modifier.height(10.dp))

        if (detail.totals.isEmpty()) {
            // "Nothing recorded" and "no data for this day" are different
            // facts and the user can tell them apart only if we say which.
            Text(
                "Nothing recorded on this day.",
                style = TextStyle(color = LumenTheme.TextSecondary, fontSize = 12.sp),
            )
        } else {
            val max = detail.totals.maxOf { it.totalMs }.coerceAtLeast(1L)
            detail.totals.take(DETAIL_ROWS).forEach { row ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        row.displayName,
                        modifier = Modifier.width(150.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(color = LumenTheme.TextPrimary, fontSize = 12.sp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Row(Modifier.weight(1f).height(6.dp)) {
                        Spacer(
                            Modifier
                                .weight((row.totalMs.toFloat() / max).coerceIn(0.01f, 1f))
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(LumenTheme.Accent),
                        )
                        Spacer(Modifier.weight((1f - row.totalMs.toFloat() / max).coerceAtLeast(0.0001f)))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        formatDuration(row.totalMs),
                        style = TextStyle(
                            color = LumenTheme.TextSecondary,
                            fontSize = 12.sp,
                            fontFamily = LumenTheme.TabularFigures,
                        ),
                    )
                }
            }
            if (detail.totals.size > DETAIL_ROWS) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "+ ${detail.totals.size - DETAIL_ROWS} more",
                    style = TextStyle(color = LumenTheme.TextSecondary, fontSize = 11.sp),
                )
            }
        }
    }
}

/** Enough to see the shape of a day without turning the panel into a report. */
private const val DETAIL_ROWS = 8
