package dev.lumen.macos.ui

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
import dev.lumen.macos.store.AppTotal

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
        } else {
            val max = totals.maxOf { it.totalMs }.coerceAtLeast(1L)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(totals, key = { it.appKey.value }) { row ->
                    AppRow(
                        row = row,
                        fraction = row.totalMs.toFloat() / max.toFloat(),
                        color = LumenTheme.categoryColor(totals.indexOf(row)),
                        reducedMotion = reducedMotion,
                    )
                }
            }
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
