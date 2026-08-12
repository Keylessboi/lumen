package dev.lumen.macos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Offers the optional history import, and states its cost.
 *
 * Deliberately not a nudge. The design spec's tone rule — a mirror, not a
 * judge — applies to permissions too: the banner explains what Full Disk
 * Access buys, says plainly what else it exposes, and gives dismiss equal
 * standing with grant. Lumen works completely without it.
 */
@Composable
fun HistoryBanner(
    state: HistoryState,
    onOpenSettings: () -> Unit,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (state is HistoryState.Hidden) return

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(LumenTheme.Surface)
            .border(1.dp, LumenTheme.Divider, RoundedCornerShape(10.dp))
            .padding(16.dp)
    ) {
        when (state) {
            is HistoryState.Offer -> {
                Text(
                    "Import your existing history?",
                    style = TextStyle(
                        color = LumenTheme.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "macOS already records which app is in front — it's what Screen Time " +
                        "is built on. Lumen can read it once to fill in the weeks before " +
                        "you installed it.",
                    style = TextStyle(color = LumenTheme.TextSecondary, fontSize = 13.sp),
                )
                Spacer(Modifier.height(10.dp))
                // The cost, stated as plainly as the benefit.
                Text(
                    "This needs Full Disk Access, which also lets any app holding it read " +
                        "your Mail, Messages and Safari history. Lumen reads only the app-focus " +
                        "records. Tracking from here on works without it.",
                    style = TextStyle(color = LumenTheme.TextSecondary, fontSize = 12.sp),
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onOpenSettings) {
                        Text("Open Settings", style = TextStyle(color = LumenTheme.Accent, fontSize = 13.sp))
                    }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = onDismiss) {
                        Text("No thanks", style = TextStyle(color = LumenTheme.TextSecondary, fontSize = 13.sp))
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "Add Lumen under Privacy & Security → Full Disk Access, then restart it — " +
                        "macOS only applies the change to a freshly launched app.",
                    style = TextStyle(color = LumenTheme.TextSecondary, fontSize = 11.sp),
                )
            }

            is HistoryState.Ready -> {
                Text(
                    "History is available",
                    style = TextStyle(
                        color = LumenTheme.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Lumen can read your existing app history now.",
                    style = TextStyle(color = LumenTheme.TextSecondary, fontSize = 13.sp),
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onImport) {
                        Text("Import history", style = TextStyle(color = LumenTheme.Accent, fontSize = 13.sp))
                    }
                    TextButton(onClick = onDismiss) {
                        Text("Not now", style = TextStyle(color = LumenTheme.TextSecondary, fontSize = 13.sp))
                    }
                }
            }

            is HistoryState.Message -> {
                Text(
                    state.text,
                    style = TextStyle(color = LumenTheme.TextSecondary, fontSize = 13.sp),
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onDismiss) {
                    Text("Dismiss", style = TextStyle(color = LumenTheme.Accent, fontSize = 13.sp))
                }
            }

            HistoryState.Hidden -> Unit
        }
    }
}

sealed interface HistoryState {
    /** Nothing to offer, or the user dismissed it. */
    data object Hidden : HistoryState

    /** Store present but blocked — offer the grant flow. */
    data object Offer : HistoryState

    /** Access granted, history not yet imported. */
    data object Ready : HistoryState

    /** Outcome of an import, or an explained failure. */
    data class Message(val text: String) : HistoryState
}
