package dev.lumen.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The export / import surface — M5, `docs/e2ee.md` §7.
 *
 * Two lines of §7 shape this entirely:
 *
 *  - *"The export contains decrypted history plus device private keys. It is
 *    the most sensitive artifact Lumen ever produces and the UI must say so
 *    at the moment of creation."* So the explanation sits on the screen where
 *    the file is made, in plain words — not behind a disclosure triangle and
 *    not in a help page nobody opens.
 *  - *"Passphrase strength is the user's; the UI shows an honest strength
 *    estimate and does not fabricate a security guarantee."* So there is no
 *    green tick, no "Strong!", no score out of five.
 */

/** What the export screen is doing right now. */
sealed interface ExportUiState {
    data object Idle : ExportUiState

    /** Deriving a key — deliberately slow, so the UI has to explain itself. */
    data object Working : ExportUiState

    data class Done(val message: String) : ExportUiState
    data class Failed(val message: String) : ExportUiState
}

@Composable
fun ExportSection(
    state: ExportUiState,
    passphrase: String,
    onExport: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val assessment = assessPassphrase(passphrase)

    Column(modifier.fillMaxWidth()) {
        Text(
            "BACKUP",
            style = TextStyle(
                color = LumenTheme.TextSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.2.sp,
            ),
        )

        Spacer(Modifier.height(12.dp))

        // Said plainly, at the moment of creation. Phrased so the user
        // understands what the file IS, rather than to frighten them out of
        // making one — a backup nobody makes protects nobody.
        Text(
            "A backup holds your full history and the keys this device syncs with. " +
                "Anyone who has the file and the passphrase has both. Keep it " +
                "somewhere you would keep a password.",
            style = TextStyle(color = LumenTheme.TextPrimary, fontSize = 13.sp, lineHeight = 19.sp),
        )

        if (assessment.message.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                assessment.message,
                style = TextStyle(color = LumenTheme.TextSecondary, fontSize = 12.sp, lineHeight = 18.sp),
            )
        }

        Spacer(Modifier.height(14.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActionLabel(
                text = if (state == ExportUiState.Working) "Working…" else "Create backup",
                enabled = state != ExportUiState.Working && assessment.usable,
                onClick = onExport,
            )
            ActionLabel(
                text = "Restore from backup",
                enabled = state != ExportUiState.Working,
                onClick = onImport,
            )
        }

        // Key derivation takes seconds by design. An unexplained pause reads
        // as a hang, and a user who force-quits during an export gets a
        // truncated file.
        if (state == ExportUiState.Working) {
            Spacer(Modifier.height(10.dp))
            Text(
                "This takes a few seconds on purpose — it is what makes a short " +
                    "passphrase expensive to guess.",
                style = TextStyle(color = LumenTheme.TextSecondary, fontSize = 12.sp),
            )
        }

        val message = when (state) {
            is ExportUiState.Done -> state.message
            is ExportUiState.Failed -> state.message
            else -> null
        }
        if (message != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                message,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(LumenTheme.Surface)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                style = TextStyle(color = LumenTheme.TextPrimary, fontSize = 12.sp, lineHeight = 18.sp),
            )
        }
    }
}

@Composable
private fun ActionLabel(text: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (enabled) LumenTheme.Surface else LumenTheme.Background)
            .let { if (enabled) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        style = TextStyle(
            color = if (enabled) LumenTheme.Accent else LumenTheme.TextSecondary,
            fontSize = 13.sp,
        ),
    )
}

/**
 * An honest passphrase assessment.
 *
 * §7: "an honest strength estimate [that] does not fabricate a security
 * guarantee." So this reports **observable facts** — how long it is, how many
 * words — and never a score, a colour, or the word "strong".
 *
 * A strength meter would be a fabrication: "correct horse battery staple" and
 * a 28-character sentence copied from a popular book are indistinguishable to
 * anything a client can compute, and one of them is in every wordlist. The
 * app can count characters honestly; it cannot know whether a passphrase is
 * guessable.
 *
 * [usable] gates the button only where the file would be trivially openable.
 * It is not a quality bar — deciding what is strong enough is the user's call.
 */
data class PassphraseAssessment(val usable: Boolean, val message: String)

fun assessPassphrase(passphrase: String): PassphraseAssessment {
    val length = passphrase.length
    val words = passphrase.trim().split(Regex("\\s+")).count { it.isNotEmpty() }

    return when {
        length == 0 -> PassphraseAssessment(usable = false, message = "")
        length < MINIMUM -> PassphraseAssessment(
            usable = false,
            message = "Too short to encrypt with — at least $MINIMUM characters.",
        )
        words >= MANY_WORDS -> PassphraseAssessment(
            usable = true,
            message = "$length characters, $words words.",
        )
        length < COMFORTABLE -> PassphraseAssessment(
            usable = true,
            message = "$length characters. Several unrelated words are easier to " +
                "remember than one short complicated one, and harder to guess.",
        )
        else -> PassphraseAssessment(usable = true, message = "$length characters.")
    }
}

/** Below this the file is trivially openable, so the button stays off. */
private const val MINIMUM = 8

/** Enough words that the suggestion would be redundant. */
private const val MANY_WORDS = 4

/** Above this we stop commenting. Not a threshold of safety. */
private const val COMFORTABLE = 20
