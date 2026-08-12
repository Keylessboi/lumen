package dev.lumen.ui.charts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lumen.ui.LumenTheme
import dev.lumen.ui.formatDuration

/** One category's share of a day, ready to render. */
data class CategorySlice(
    val name: String,
    val totalMs: Long,
)

/**
 * Chart 1 of the three in `docs/design-spec.md`: per-category time at a
 * single glance.
 *
 * A stacked bar rather than a donut. The spec says "donut/bars" and leaves
 * the choice open; a bar wins here because the labelled legend sits directly
 * under it in reading order, it degrades gracefully to a phone width, and a
 * category worth 2% of the day is still a visible sliver rather than an
 * unhittable wedge.
 *
 * Uncategorized is shown, never hidden. Dropping it would make the bar add up
 * to less than the day and quietly overstate everything else — the "numbers
 * and charts must agree" line in the spec.
 */
@Composable
fun CategoryBar(
    slices: List<CategorySlice>,
    modifier: Modifier = Modifier,
) {
    val total = slices.sumOf { it.totalMs }
    if (slices.isEmpty() || total <= 0L) return

    // Uncategorized last regardless of size: it is the residual, and a bar
    // that reorders itself as categories overtake each other is hard to read
    // day to day.
    val ordered = slices.sortedWith(
        compareBy({ it.name == "Uncategorized" }, { -it.totalMs }),
    )

    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)),
        ) {
            ordered.forEach { slice ->
                Box(
                    Modifier
                        .weight((slice.totalMs.toFloat() / total).coerceAtLeast(0.004f))
                        .fillMaxWidth()
                        .height(10.dp)
                        .background(LumenTheme.colorForCategory(slice.name)),
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // Legend with times. The chart and the numbers are the same object,
        // so they cannot disagree.
        ordered.forEach { slice ->
            Row(
                Modifier.fillMaxWidth().height(22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(LumenTheme.colorForCategory(slice.name)),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    slice.name,
                    style = TextStyle(
                        color = LumenTheme.TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Normal,
                    ),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    formatDuration(slice.totalMs),
                    modifier = Modifier.width(LumenTheme.TimeColumnWidth),
                    textAlign = TextAlign.End,
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
}

/**
 * A slice's share of the whole, for tests and layout.
 *
 * Floored at a visible minimum for the same reason the day bars are: a
 * category with real time in it must not render as nothing.
 */
fun sliceFraction(totalMs: Long, dayTotalMs: Long, minimum: Float = 0.004f): Float =
    if (dayTotalMs <= 0L) 0f else (totalMs.toFloat() / dayTotalMs).coerceIn(minimum, 1f)
