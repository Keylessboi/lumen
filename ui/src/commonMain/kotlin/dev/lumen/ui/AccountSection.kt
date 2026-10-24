package dev.lumen.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One sync server the user can pick, as the picker shows it. */
data class AccountProviderOption(
    val jid: String,
    val tier: Int,
    /** Plain language: "Germany". */
    val location: String = "",
    val captcha: Boolean = false,
    val passwordReset: Boolean = false,
    val since: Int? = null,
)

/** What a server asked for before it will create the account. */
class SignUpChallenge(
    val domain: String,
    val instructions: String?,
    val captchaImage: ByteArray?,
    val captchaField: String?,
    val captchaLabel: String?,
    /** Where to see the CAPTCHA if the image will not show. */
    val captchaLink: String?,
    val fields: List<SignUpField>,
    /** The server's own sign-up page, when in-app sign-up is not offered. */
    val webPage: String?,
)

data class SignUpField(
    val name: String,
    val label: String,
    val secret: Boolean = false,
    val required: Boolean = false,
    /** Read-only text the server wants shown. */
    val info: Boolean = false,
    val boolean: Boolean = false,
    val defaultValue: String? = null,
)

data class DeviceRow(
    val id: String,
    val label: String,
    val fingerprint: String,
    val thisDevice: Boolean,
)

/** What the account surface is doing right now. */
sealed interface AccountUiState {
    data object Unconfigured : AccountUiState
    data class Working(val message: String) : AccountUiState
    data class Failed(val message: String) : AccountUiState
    data class Challenge(val challenge: SignUpChallenge, val message: String? = null) : AccountUiState
    data class Connected(
        val username: String,
        val host: String,
        val fingerprint: String? = null,
        val devices: List<DeviceRow> = emptyList(),
        val lastSync: String? = null,
        val syncing: Boolean = false,
        val problem: String? = null,
        val warnings: List<String> = emptyList(),
    ) : AccountUiState
}

/**
 * The account / sync surface — M4.
 *
 * The caller owns the network work; this screen owns the picking, the
 * typing and the honesty (`docs/providers.md` §6): plan-locked wording,
 * plain-language locations, no protocol jargon, the metadata statement
 * said once and plainly, and local-only presented as a peer choice rather
 * than a failure.
 */
@Composable
fun AccountSection(
    providers: List<AccountProviderOption>,
    state: AccountUiState,
    onRegister: (server: String, username: String, password: String) -> Unit,
    onSave: (server: String, username: String, password: String) -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
    onAnswerChallenge: (answers: Map<String, String>) -> Unit = {},
    onCancel: () -> Unit = {},
    onSyncNow: () -> Unit = {},
    onForgetDevice: (deviceId: String) -> Unit = {},
    onOpenUrl: (String) -> Unit = {},
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        when (state) {
            is AccountUiState.Connected -> ConnectedView(state, onSyncNow, onForgetDevice, onDisconnect)
            is AccountUiState.Challenge -> ChallengeView(state, onAnswerChallenge, onCancel, onOpenUrl)
            else -> AccountForm(providers, state, onRegister, onSave)
        }
    }
}

@Composable
private fun AccountForm(
    providers: List<AccountProviderOption>,
    state: AccountUiState,
    onRegister: (String, String, String) -> Unit,
    onSave: (String, String, String) -> Unit,
) {
    var existing by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(providers.firstOrNull()?.jid ?: "") }
    var customServer by remember { mutableStateOf("") }
    var useCustom by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val working = state is AccountUiState.Working

    PageTitle(
        "Where should your data live?",
        "Sync keeps your devices in step. It is optional — Lumen works fully on this device alone.",
    )
    BodyText(
        "Your usage data is encrypted before it leaves this device. Whoever runs this server can see " +
            "when you sync and how much, but not what you did.",
        secondary = true,
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ChoicePill("New account", !existing) { existing = false }
        ChoicePill("I have an account", existing) { existing = true }
    }

    if (state is AccountUiState.Failed) ErrorText(state.message)
    if (state is AccountUiState.Working) BodyText(state.message, secondary = true)

    if (!existing) {
        SettingsCard {
            CardTitle("Server")
            val visible = if (showAll) providers else providers.take(5)
            visible.forEach { p ->
                ChoiceRow(
                    title = p.jid,
                    detail = listOfNotNull(
                        p.location.takeIf { it.isNotBlank() },
                        p.since?.let { "since $it" },
                        if (p.captcha) "asks for a CAPTCHA" else "no CAPTCHA",
                    ).joinToString(" · "),
                    selected = !useCustom && p.jid == selected,
                    onClick = { selected = p.jid; useCustom = false },
                )
            }
            if (providers.size > 5) {
                QuietButton(if (showAll) "Show fewer" else "Show all ${providers.size} servers", { showAll = !showAll })
            }
            ChoiceRow("Another server", "Your own, or one not listed here", useCustom) { useCustom = true }
            if (useCustom) {
                LumenField(customServer, { customServer = it.trim() }, "Server name", placeholder = "example.org")
            }
            val pick = providers.firstOrNull { it.jid == selected }
            if (!useCustom && pick != null && !pick.passwordReset) {
                BodyText("If you forget this password, this server cannot recover your account.", secondary = true)
            }
        }
    }

    SettingsCard {
        CardTitle(if (existing) "Sign in" else "Choose a username and password")
        if (existing) {
            LumenField(
                username, { username = it.trim() }, "Address",
                placeholder = "name@server.org",
                hint = "The full address you sign in with elsewhere.",
            )
        } else {
            LumenField(
                username, { username = it.trim() }, "Username",
                hint = usernameProblem(username),
                isError = usernameProblem(username) != null,
            )
        }
        LumenField(
            password, { password = it }, "Password", secret = true,
            hint = if (!existing) "At least 8 characters. Stored in your system keyring." else null,
            imeAction = if (existing) ImeAction.Done else ImeAction.Next,
        )
        if (!existing) {
            LumenField(
                confirm, { confirm = it }, "Repeat password", secret = true,
                isError = confirm.isNotEmpty() && confirm != password,
                hint = if (confirm.isNotEmpty() && confirm != password) "Passwords do not match." else null,
                imeAction = ImeAction.Done,
            )
        }

        val server = if (useCustom) customServer else selected
        val canCreate = !existing && server.isNotBlank() && username.isNotBlank() && usernameProblem(username) == null &&
            password.length >= 8 && password == confirm
        val canSignIn = existing && username.contains('@') && password.isNotEmpty()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (existing) {
                PrimaryButton("Sign in", {
                    onSave(username.substringAfter('@'), username.substringBefore('@'), password)
                }, enabled = canSignIn && !working)
            } else {
                PrimaryButton("Create account", { onRegister(server, username, password) }, enabled = canCreate && !working)
            }
        }
    }
}

@Composable
private fun ChallengeView(
    state: AccountUiState.Challenge,
    onAnswer: (Map<String, String>) -> Unit,
    onCancel: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val challenge = state.challenge
    val answers = remember(challenge) {
        mutableStateMapOf<String, String>().apply {
            challenge.fields.filter { !it.info }.forEach { put(it.name, it.defaultValue ?: "") }
            challenge.captchaField?.let { put(it, "") }
        }
    }
    PageTitle("One more step", "${challenge.domain} needs a little more before it creates the account.")
    state.message?.let { ErrorText(it) }

    SettingsCard {
        // The server's instructions are written for its whole form ("choose a
        // username and password"); the user did that already, so they only
        // help when the server asks for something beyond the CAPTCHA.
        if (challenge.fields.any { !it.info }) {
            challenge.instructions?.takeIf { it.isNotBlank() }?.let { BodyText(it, secondary = true) }
        }
        if (challenge.captchaField != null) {
            CardTitle(challenge.captchaLabel ?: "Type the characters you see")
            val bitmap = remember(challenge) { challenge.captchaImage?.let(::decodeImage) }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "CAPTCHA image",
                    contentScale = ContentScale.Fit,
                    // CAPTCHAs are ~180x60 px: at native size they are unreadable
                    // on a phone. Scale to the card width, keeping the shape.
                    filterQuality = FilterQuality.Medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 420.dp)
                        .aspectRatio((bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)).coerceIn(1f, 6f))
                        .clip(RoundedCornerShape(6.dp))
                        // CAPTCHAs are drawn for light backgrounds.
                        .background(Color.White)
                        .padding(6.dp),
                )
            } else {
                BodyText("The image did not load.", secondary = true)
            }
            challenge.captchaLink?.let { link ->
                QuietButton("Can’t read it? Open it in the browser", { onOpenUrl(link) })
            }
            LumenField(answers[challenge.captchaField] ?: "", { answers[challenge.captchaField] = it }, "Answer")
        }
        challenge.fields.forEach { field ->
            when {
                field.info -> BodyText(field.defaultValue ?: field.label, secondary = true)
                field.boolean -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = answers[field.name] == "1" || answers[field.name] == "true",
                        onCheckedChange = { answers[field.name] = if (it) "1" else "0" },
                        colors = CheckboxDefaults.colors(checkedColor = LumenTheme.Accent),
                    )
                    BodyText(field.label)
                }
                else -> LumenField(
                    answers[field.name] ?: "", { answers[field.name] = it },
                    field.label + if (field.required) "" else " (optional)", secret = field.secret,
                )
            }
        }
        challenge.webPage?.let { page ->
            BodyText("This server also offers sign-up on its website.", secondary = true)
            QuietButton("Open sign-up page", { onOpenUrl(page) })
        }
        val missing = challenge.fields.any { it.required && !it.info && answers[it.name].isNullOrBlank() } ||
            (challenge.captchaField != null && answers[challenge.captchaField].isNullOrBlank())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton("Create account", { onAnswer(answers.toMap()) }, enabled = !missing)
            QuietButton("Back", onCancel)
        }
    }
}

@Composable
private fun ConnectedView(
    state: AccountUiState.Connected,
    onSyncNow: () -> Unit,
    onForgetDevice: (String) -> Unit,
    onDisconnect: () -> Unit,
) {
    PageTitle("Sync is on", "Usage syncs between your devices, encrypted end to end.")
    SettingsCard {
        InfoLine("Account", "${state.username} on ${state.host}")
        InfoLine("Last sync", if (state.syncing) "Syncing…" else state.lastSync ?: "Not yet")
        state.problem?.let { ErrorText(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("Sync now", onSyncNow, enabled = !state.syncing)
        }
    }

    SettingsCard {
        CardTitle("Devices")
        BodyText(
            "Each device has its own key. Compare a device's code with what that device shows for itself " +
                "before trusting it — a server that slipped in a device of its own would show up here.",
            secondary = true,
        )
        if (state.devices.isEmpty()) BodyText("Only this device so far.", secondary = true)
        state.devices.forEach { device ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        device.label + if (device.thisDevice) " (this device)" else "",
                        style = TextStyle(color = LumenTheme.TextPrimary, fontSize = 14.sp),
                        modifier = Modifier.weight(1f),
                    )
                    if (!device.thisDevice) QuietButton("Forget", { onForgetDevice(device.id) }, danger = true)
                }
                SelectionContainer {
                    Text(
                        device.fingerprint,
                        style = TextStyle(color = LumenTheme.TextSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                    )
                }
            }
        }
        state.warnings.forEach { ErrorText(it) }
    }

    SettingsCard {
        BodyText("Disconnecting stops sync on this device. Your data here stays.", secondary = true)
        QuietButton("Disconnect", onDisconnect, danger = true)
    }
}

@Composable
private fun ChoicePill(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = TextStyle(
            color = if (selected) LumenTheme.Background else LumenTheme.TextSecondary,
            fontSize = 13.sp,
        ),
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) LumenTheme.Accent else LumenTheme.Surface)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

/** Why a username will be refused, before the server has to say so. */
fun usernameProblem(username: String): String? = when {
    username.isEmpty() -> null
    '@' in username -> "Just the name — the server is picked above."
    username.any { it.isWhitespace() } -> "No spaces."
    username.any { it in "\"&'/:<>" } -> "These characters are not allowed: \" & ' / : < >"
    username.length > 64 -> "That is too long."
    else -> null
}
