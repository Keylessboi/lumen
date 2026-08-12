package dev.lumen.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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

            val max = totals.maxOf { it.totalMs }.coerceAtLeast(1L)            // Colour comes from the CATEGORY, not from the app key. That is
            // what ties each row to the strip above it: the strip is these
            // same numbers one level up, so a user can see at a glance which
            // apps make up the green. Falling back to a per-app hue keeps
            // rows distinguishable on a platform with no category engine
            // wired up yet, where every category would otherwise be null.
            val fallback = LumenTheme.colorsFor(totals.map { it.appKey.value })
            // The list takes a share of the leftover height and scrolls
            // inside it — but only ever a WHOLE number of rows. Sized to the
            // raw leftover it ends mid-row, and a row sliced through its own
            // text reads as a rendering fault rather than as "there is more
            // below".
            Box(Modifier.weight(1f)) {
                BoxWithConstraints {
                    val visibleRows = visibleRowCount(maxHeight)
                    // Deliberately UNKEYED. `items(totals, key = { it.appKey.value })`
                    // is the reflex, and it is wrong for this list: a keyed lazy
                    // list anchors the viewport to whichever KEY was first
                    // visible, and this list is sorted by time, so its order
                    // changes all day. The app that led at breakfast keeps the
                    // top slot as it is overtaken, dragging the viewport down
                    // with it — on this Mac the screen showed "Lumen 13m,
                    // Messages 2m" while the strip above it read "Browsing
                    // 2h 19m, Development 2h 2m", with Chrome and Terminal
                    // scrolled out of sight and nothing saying so. Unkeyed,
                    // identity is the RANK, which is what the viewport should
                    // follow: the top of the list is the largest app, always.
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(ROW_GAP),
                        modifier = Modifier.height(ROW_PITCH * visibleRows - ROW_GAP),
                    ) {
                        items(totals) { row ->
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
            }
        }

        Spacer(Modifier.height(22.dp))

        // Trend view last: today is what the screen is for, history is
        // context underneath it. Absent when there is none to show, so a
        // fresh install has no empty frame.
        //
        // The chart takes a SHARE of the leftover height rather than a fixed
        // 168.dp of bars. Fixed, it was first in the queue for space it did
        // not have to justify: with the history banner up at the default
        // window size the app list was starved to zero height — the APPS
        // heading with nothing under it — and the chart still overflowed, so
        // its own axis labels fell off the bottom edge. Sharing means both
        // sections get smaller together and neither disappears, at any window
        // size. 2:1 keeps roughly the proportions the fixed height had.
        if (recentDays.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            DayBarsSection(
                title = "RECENT DAYS",
                days = recentDays,
                modifier = Modifier.weight(2f),
                averageMs = averageMs,
                selectedDay = selectedDay,
                detail = dayDetail,
                onSelectDay = onSelectDay,
                onClearSelection = onClearDaySelection,
                fillHeight = true,
            )
        }
    }
}

/** One app row's height, and the gap under it. The list is sized in whole
 *  multiples of the two so it never ends part-way through a row. */
private val ROW_HEIGHT = 38.dp
private val ROW_GAP = 2.dp
private val ROW_PITCH = ROW_HEIGHT + ROW_GAP

/**
 * How many whole app rows fit in [available].
 *
 * Never zero: a section headed APPS with nothing under it reads as "no apps",
 * which is a different and false statement. One clipped row is worse than one
 * whole row, and both are better than none.
 */
internal fun visibleRowCount(
    available: androidx.compose.ui.unit.Dp,
    pitch: androidx.compose.ui.unit.Dp = ROW_PITCH,
): Int = (available / pitch).toInt().coerceAtLeast(1)

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
    // A 2-second app next to a 15-hour one is 0.004% of the bar — invisible,
    // which reads as "not tracked" while the number beside it says 2s. Floor
    // the fill at a visible sliver so every app row shows a bar; the number
    // stays exact, so proportion is still honest above the floor.
    val visible = animated.coerceAtLeast(MIN_BAR_FRACTION)

    Row(
        Modifier.fillMaxWidth().height(ROW_HEIGHT),
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
                    .fillMaxWidth(visible)
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

/** Smallest visible bar fill (3%), so a tiny app is still seen as tracked. */
private const val MIN_BAR_FRACTION = 0.03f
