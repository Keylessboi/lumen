package dev.lumen.ui.charts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
/**
 * Chart 1 of the three in `docs/design-spec.md`: per-category time at a
 * single glance.
 *
 * ## Why this is a strip and a caption, not a chart with a legend
 *
 * The first version stacked a bar on top of a legend with one row per
 * category — name, swatch, time. That is the same shape as the app list
 * directly below it, so the screen showed two near-identical lists and the
 * summary crowded out the detail it was summarising. On a busy day the apps
 * were pushed off the bottom by their own totals.
 *
 * So the categories collapse to one strip plus a wrapped caption, and the
 * link to the detail is carried by COLOUR: every app row below is drawn in
 * its category's hue. The strip is the same data as the list, one level up,
 * rather than a competing block.
 *
 * Uncategorized is shown, never hidden. Dropping it would make the strip add
 * up to less than the day and quietly overstate everything else.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoryBar(
    slices: List<CategorySlice>,
    modifier: Modifier = Modifier,
) {
    val total = slices.sumOf { it.totalMs }
    if (slices.isEmpty() || total <= 0L) return

    // Uncategorized last regardless of size: it is the residual, and a strip
    // that reorders itself as categories overtake each other is hard to read
    // day to day.
    val ordered = slices.sortedWith(
        compareBy({ it.name == "Uncategorized" }, { -it.totalMs }),
    )

    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ordered.forEach { slice ->
                Box(
                    Modifier
                        .weight((slice.totalMs.toFloat() / total).coerceAtLeast(0.004f))
                        .fillMaxHeight()
                        .background(LumenTheme.colorForCategory(slice.name)),
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // One wrapped line rather than a row per category. FlowRow so a
        // narrow window reflows instead of clipping the last few.
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ordered.forEach { slice ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(LumenTheme.colorForCategory(slice.name)),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        slice.name,
                        style = TextStyle(color = LumenTheme.TextSecondary, fontSize = 11.sp),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        formatDuration(slice.totalMs),
                        style = TextStyle(
                            color = LumenTheme.TextPrimary,
                            fontSize = 11.sp,
                            fontFamily = LumenTheme.TabularFigures,
                            fontFeatureSettings = "tnum",
                        ),
                    )
                }
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
