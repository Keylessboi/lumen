package dev.lumen.ui.charts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.heightIn
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
import dev.lumen.core.model.AppTotal
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
            dev.lumen.ui.SectionLabel(title)

            // The average legend sits ON the header row rather than under the
            // chart. Below the chart it was a stray line at the bottom of the
            // window with open space beneath it; here it reads as part of the
            // section's title, and the chart gets that height back.
            if (averageMs != null) {
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
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
                    Text(
                        "Daily average, all weeks — ${formatDuration(averageMs)}",
                        style = TextStyle(
                            color = LumenTheme.TextSecondary,
                            fontSize = 11.sp,
                            fontFamily = LumenTheme.TabularFigures,
                            fontFeatureSettings = "tnum",
                        ),
                    )
                }
            }

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
            val colors = LumenTheme.colorsFor(detail.totals.map { it.appKey.value })
            // Scrolls inside the panel rather than truncating at eight and
            // adding a "+ N more" the user cannot expand. The apps in a day
            // are exactly what the panel is for; a footnote saying they exist
            // is not the same as showing them.
            LazyColumn(Modifier.heightIn(max = DETAIL_MAX_HEIGHT)) {
              items(detail.totals, key = { it.appKey.value }) { row ->
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
                                // Same colour the app has in the Today list:
                                // one app, one colour, everywhere it appears.
                                .background(colors[row.appKey.value] ?: LumenTheme.colorForKey(row.appKey.value)),
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
            }
        }
    }
}

/**
 * Cap on the panel's height, not on its content.
 *
 * The panel scrolls past this, so every app in the day is reachable — but it
 * never grows tall enough to push its own bottom edge off the window, which
 * is what happened when it sized to content.
 */
private val DETAIL_MAX_HEIGHT = 208.dp
