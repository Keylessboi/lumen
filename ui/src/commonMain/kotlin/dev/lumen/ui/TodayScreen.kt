package dev.lumen.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import dev.lumen.core.model.AppTotal
import dev.lumen.ui.charts.DayBarsSection
import dev.lumen.ui.charts.DayDetail
import dev.lumen.ui.charts.DayTotal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Today — the only screen that decides whether a user stays
 * (`docs/design-spec.md`).
 *
 * A mirror, not a judge: the total is stated, never evaluated. No streaks, no
 * badges, no colour-coded verdicts. Nothing on this screen tells the user
 * whether the number is good.
 */
@Composable
fun TodayScreen(
    totals: List<AppTotal>,
    totalMs: Long,
    liveApp: String?,
    reducedMotion: Boolean,
    historyState: HistoryState = HistoryState.Hidden,
    /**
     * Recent per-day totals for the trend view (chart 3 of the three in
     * `docs/design-spec.md`). Empty hides the section entirely — a platform
     * that cannot yet supply history shows no empty frame.
     */
    recentDays: List<DayTotal> = emptyList(),
    /** Running mean across every complete day on record; null before any. */
    averageMs: Long? = null,
    selectedDay: String? = null,
    dayDetail: DayDetail? = null,
    onSelectDay: (String) -> Unit = {},
    onClearDaySelection: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onImport: () -> Unit = {},
    onDismissHistory: () -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(LumenTheme.Background)
            .padding(horizontal = 32.dp, vertical = 28.dp)
    ) {
        Text(
            "Today",
            style = TextStyle(
                color = LumenTheme.TextSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.2.sp,
            ),
        )

        Spacer(Modifier.height(6.dp))

        // The big number. Tabular figures so it does not jitter as it ticks.
        Text(
            formatDuration(totalMs),
            style = TextStyle(
                color = LumenTheme.TextPrimary,
                fontSize = 52.sp,
                fontWeight = FontWeight.Light,
                fontFamily = LumenTheme.TabularFigures,
                fontFeatureSettings = "tnum",
                fontSynthesis = FontSynthesis.None,
            ),
        )

        Spacer(Modifier.height(2.dp))

        Text(
            if (liveApp != null) "Now: $liveApp" else "Waiting for the first app switch",
            style = TextStyle(color = LumenTheme.TextSecondary, fontSize = 13.sp),
        )

        Spacer(Modifier.height(20.dp))

        HistoryBanner(
            state = historyState,
            onOpenSettings = onOpenSettings,
            onImport = onImport,
            onDismiss = onDismissHistory,
        )

        if (historyState != HistoryState.Hidden) Spacer(Modifier.height(20.dp))

        if (totals.isEmpty()) {
            Text(
                "Nothing recorded yet. Switch between a couple of apps and they'll appear here.",
                style = TextStyle(color = LumenTheme.TextSecondary, fontSize = 13.sp),
            )
            // Same reason as the weight above: keep the trend section at the
            // bottom even with no rows at all.
            Spacer(Modifier.weight(1f))
        } else {
            val max = totals.maxOf { it.totalMs }.coerceAtLeast(1L)
            // Resolved for the whole visible set so two rows are not the same
            // colour, and resolved by key so the assignment does not change
            // when rows reorder during the day.
            val colors = LumenTheme.colorsFor(totals.map { it.appKey.value })
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                // fill = true so the list takes the leftover height and the
                // trend section below stays pinned to the bottom of the
                // window. With fill = false the chart floated up whenever few
                // apps had been used, so the same screen sat in a different
                // place depending on the day — and a chart that moves is one
                // you have to re-find every time you look.
                modifier = Modifier.weight(1f),
            ) {
                items(totals, key = { it.appKey.value }) { row ->
                    AppRow(
                        row = row,
                        fraction = row.totalMs.toFloat() / max.toFloat(),
                        color = colors[row.appKey.value] ?: LumenTheme.colorForKey(row.appKey.value),
                        reducedMotion = reducedMotion,
                    )
                }
            }
        }

        // Trend view last: today is what the screen is for, history is
        // context underneath it. Absent when there is none to show, so a
        // fresh install has no empty frame.
        if (recentDays.isNotEmpty()) {
            Spacer(Modifier.height(28.dp))
            DayBarsSection(
                title = "RECENT DAYS",
                days = recentDays,
                averageMs = averageMs,
                selectedDay = selectedDay,
                detail = dayDetail,
                onSelectDay = onSelectDay,
                onClearSelection = onClearDaySelection,
            )
        }
    }
}

@Composable
private fun AppRow(
    row: AppTotal,
    fraction: Float,
    color: androidx.compose.ui.graphics.Color,
    reducedMotion: Boolean,
) {
    // 150-250ms ease-out, no bounce; disabled entirely under reduced motion.
    val animated by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(durationMillis = if (reducedMotion) 0 else 200),
        label = "bar",
    )

    Row(
        Modifier.fillMaxWidth().height(38.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            row.displayName,
            style = TextStyle(color = LumenTheme.TextPrimary, fontSize = 14.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(190.dp),
        )

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
                    .fillMaxWidth(animated)
                    .clip(RoundedCornerShape(4.dp))
                    .background(color)
            )
        }

        Spacer(Modifier.width(16.dp))

        // Right-aligned, tabular: the column must not shuffle as values tick.
        Text(
            formatDuration(row.totalMs),
            style = TextStyle(
                color = LumenTheme.TextSecondary,
                fontSize = 13.sp,
                fontFamily = LumenTheme.TabularFigures,
                fontFeatureSettings = "tnum",
            ),
            modifier = Modifier.width(72.dp),
        )
    }
}
