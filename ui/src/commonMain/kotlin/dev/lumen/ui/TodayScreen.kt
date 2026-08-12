package dev.lumen.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import dev.lumen.core.model.AppTotal
import dev.lumen.ui.charts.CategorySlice
import dev.lumen.ui.charts.CategoryBar
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
import androidx.compose.ui.text.style.TextAlign
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
    /**
     * Hide the "Now: <app>" line entirely. Android passes false: the app
     * only shows this screen while Lumen itself is foreground, so the
     * indicator would always read "Now: Lumen" — noise, not information.
     * (LO directive; added by Agent A, awaiting B review since ui/ is B's.)
     */
    showLiveApp: Boolean = true,
    historyState: HistoryState = HistoryState.Hidden,
    /**
     * Recent per-day totals for the trend view (chart 3 of the three in
     * `docs/design-spec.md`). Empty hides the section entirely — a platform
     * that cannot yet supply history shows no empty frame.
     */
    /** Per-category time for today (chart 1 of three). Empty hides it. */
    categories: List<CategorySlice> = emptyList(),
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
    // The page does NOT scroll. Scrolling the whole screen made the window
    // content taller than the window, which is worse than the problem it
    // solved. Everything fits; the one list that can outgrow its space
    // scrolls inside itself.
    Column(
        Modifier
            .fillMaxSize()
            .background(LumenTheme.Background)
            .padding(start = 32.dp, end = 32.dp, top = 44.dp, bottom = 28.dp)
    ) {
        SectionLabel("TODAY")

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

        if (showLiveApp) {
            Text(
                if (liveApp != null) "Now: $liveApp" else "Waiting for the first app switch",
                style = TextStyle(color = LumenTheme.TextSecondary, fontSize = 13.sp),
            )
        }

        Spacer(Modifier.height(20.dp))

        HistoryBanner(
            state = historyState,
            onOpenSettings = onOpenSettings,
            onImport = onImport,
            onDismiss = onDismissHistory,
        )

        if (historyState != HistoryState.Hidden) Spacer(Modifier.height(20.dp))

        // When a day is open, its apps ARE the app list — showing today's
        // list above it wastes the space twice and leaves an empty band where
        // the reader is looking. So the section is replaced, not stacked.
        val dayIsOpen = dayDetail != null

        if (dayIsOpen) {
            Spacer(Modifier.height(4.dp))
        } else if (totals.isEmpty()) {
            Text(
                "Nothing recorded yet. Switch between a couple of apps and they'll appear here.",
                style = TextStyle(color = LumenTheme.TextSecondary, fontSize = 13.sp),
            )
        } else {
            if (categories.isNotEmpty()) {
                CategoryBar(categories)
                Spacer(Modifier.height(22.dp))
            }

            SectionLabel("APPS")
            Spacer(Modifier.height(10.dp))

            val max = totals.maxOf { it.totalMs }.coerceAtLeast(1L)
            // Colour comes from the CATEGORY, not from the app key. That is
            // what ties each row to the strip above it: the strip is these
            // same numbers one level up, so a user can see at a glance which
            // apps make up the green. Falling back to a per-app hue keeps
            // rows distinguishable on a platform with no category engine
            // wired up yet, where every category would otherwise be null.
            val fallback = LumenTheme.colorsFor(totals.map { it.appKey.value })
            // weight(1f) with an internal scroll: the list takes the space
            // between the strip and the chart and scrolls within it. A
            // fill = false version sized itself to the leftover space and cut
            // the last row in half, which reads as a rendering fault.
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.weight(1f),
            ) {
                items(totals, key = { it.appKey.value }) { row ->
                    AppRow(
                        row = row,
                        fraction = row.totalMs.toFloat() / max.toFloat(),
                        color = row.category
                            ?.let { LumenTheme.colorForCategory(it) }
                            ?: fallback[row.appKey.value]
                            ?: LumenTheme.colorForKey(row.appKey.value),
                        reducedMotion = reducedMotion,
                    )
                }
            }
        }

        Spacer(Modifier.height(22.dp))

        // Trend view last: today is what the screen is for, history is
        // context underneath it. Absent when there is none to show, so a
        // fresh install has no empty frame.
        if (recentDays.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
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

/** Section heading. One definition so every section matches exactly. */
@Composable
internal fun SectionLabel(text: String) {
    Text(
        text,
        style = TextStyle(
            color = LumenTheme.TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.4.sp,
        ),
    )
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
            modifier = Modifier.width(LumenTheme.TimeColumnWidth),
            textAlign = TextAlign.End,
        )
    }
}
