package dev.lumen.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** How a service signs in. */
enum class ScrobbleSignIn {
    /** Open a link, approve Lumen on the service's site, come back. */
    BROWSER,
    /** Open a link, copy a token, paste it here. */
    TOKEN,
}

data class ScrobbleServiceRow(
    val id: String,
    val name: String,
    val signIn: ScrobbleSignIn,
    val connectedAs: String? = null,
    /** Set while a browser sign-in is waiting for approval. */
    val waitingUrl: String? = null,
    /** Where to get a token, for [ScrobbleSignIn.TOKEN]. */
    val tokenPage: String? = null,
    /** Why this service cannot be used in this build, if it cannot. */
    val unavailable: String? = null,
)

data class ScrobbleUiState(
    val services: List<ScrobbleServiceRow>,
    /** One line: what Lumen can see playing, or why it cannot. */
    val sourceStatus: String,
    /** Label for the action that fixes [sourceStatus], e.g. granting access. */
    val sourceAction: String? = null,
    val nowPlaying: String? = null,
    val pending: Int = 0,
    val lastSent: String? = null,
    val problem: String? = null,
    val includeBrowsers: Boolean = false,
    val busy: String? = null,
)

/**
 * Scrobbling — send what you listen to to Last.fm, Libre.fm or ListenBrainz.
 *
 * Off until the user signs in to a service. Sign-in never asks for the
 * service password inside Lumen: Last.fm and Libre.fm approve Lumen on their
 * own site (the link opens in the browser), ListenBrainz hands out a token.
 */
@Composable
fun ScrobbleSection(
    state: ScrobbleUiState,
    onSignIn: (serviceId: String) -> Unit,
    onCancelSignIn: (serviceId: String) -> Unit,
    onSubmitToken: (serviceId: String, token: String) -> Unit,
    onSignOut: (serviceId: String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onSourceAction: () -> Unit,
    onIncludeBrowsers: (Boolean) -> Unit,
    onSendNow: () -> Unit,
) {
    PageTitle(
        "Scrobbling",
        "Share what you listen to with a scrobbling service. Lumen sends the artist, title, album and when " +
            "you played it — nothing about your apps or screen time.",
    )

    SettingsCard {
        CardTitle("Listening")
        BodyText(state.sourceStatus, secondary = state.sourceAction == null)
        state.sourceAction?.let { QuietButton(it, onSourceAction) }
        state.nowPlaying?.let { InfoLine("Now playing", it) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                BodyText("Include web browsers")
                BodyText("Off by default: browsers mostly play video, not music.", secondary = true)
            }
            Switch(
                checked = state.includeBrowsers,
                onCheckedChange = onIncludeBrowsers,
                colors = SwitchDefaults.colors(checkedTrackColor = LumenTheme.Accent),
            )
        }
    }

    state.services.forEach { service ->
        SettingsCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                CardTitle(service.name)
                Text(
                    service.connectedAs?.let { "Signed in as $it" } ?: "Not connected",
                    style = TextStyle(color = LumenTheme.TextSecondary, fontSize = 12.sp),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.End,
                )
            }
            when {
                service.connectedAs != null -> QuietButton("Sign out", { onSignOut(service.id) }, danger = true)
                service.unavailable != null -> BodyText(service.unavailable, secondary = true)
                service.waitingUrl != null -> {
                    BodyText(
                        "Approve Lumen on ${service.name} in your browser. This page updates by itself once you have.",
                    )
                    SelectionContainer { BodyText(service.waitingUrl, secondary = true) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        QuietButton("Open the link again", { onOpenUrl(service.waitingUrl) })
                        QuietButton("Cancel", { onCancelSignIn(service.id) })
                    }
                }
                service.signIn == ScrobbleSignIn.BROWSER ->
                    PrimaryButton("Sign in with ${service.name}", { onSignIn(service.id) }, enabled = state.busy == null)
                else -> TokenSignIn(service, onOpenUrl, onSubmitToken, enabled = state.busy == null)
            }
        }
    }

    if (state.busy != null || state.problem != null || state.pending > 0 || state.lastSent != null) {
        SettingsCard {
            state.busy?.let { BodyText(it, secondary = true) }
            state.problem?.let { ErrorText(it) }
            if (state.pending > 0) InfoLine("Waiting to send", "${state.pending}")
            state.lastSent?.let { InfoLine("Last sent", it) }
            if (state.pending > 0) QuietButton("Send now", onSendNow)
        }
    }
}

@Composable
private fun TokenSignIn(
    service: ScrobbleServiceRow,
    onOpenUrl: (String) -> Unit,
    onSubmitToken: (String, String) -> Unit,
    enabled: Boolean,
) {
    var token by remember(service.id) { mutableStateOf("") }
    BodyText("Open your ${service.name} settings, copy the user token shown there, and paste it here.", secondary = true)
    service.tokenPage?.let { page -> QuietButton("Get your ${service.name} token", { onOpenUrl(page) }) }
    LumenField(token, { token = it.trim() }, "User token", secret = true)
    PrimaryButton("Connect", { onSubmitToken(service.id, token) }, enabled = enabled && token.length >= 8)
}
