package dev.lumen.ui

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One sync provider the user can pick, as the UI needs it. */
data class AccountProviderOption(
    val jid: String,
    val tier: Int,
)

/** What the account surface is doing right now. */
sealed interface AccountUiState {
    data object Unconfigured : AccountUiState
    data class Connected(val jid: String, val host: String) : AccountUiState
    data class Working(val message: String) : AccountUiState
    data class Failed(val message: String) : AccountUiState
}

/**
 * The account / sync surface — M4, the piece that turns a built XMPP
 * stack into something a user can actually log in with.
 *
 * The caller owns the transport work (register/save/disconnect are real
 * I/O and platform-specific); this screen owns the picking and the
 * honesty. A failed registration is shown plainly — a made-up success
 * would leave the user believing sync was on when nothing would run.
 */
@Composable
fun AccountSection(
    providers: List<AccountProviderOption>,
    state: AccountUiState,
    onRegister: (providerJid: String, username: String, password: String) -> Unit,
    onSave: (providerJid: String, username: String, password: String) -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var selectedJid by remember { mutableStateOf(providers.firstOrNull()?.jid ?: "") }

    Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Sync",
            style = TextStyle(color = LumenTheme.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold),
        )

        when (state) {
            is AccountUiState.Connected -> {
                Text(
                    "Connected as ${state.jid} (${state.host})",
                    style = TextStyle(color = LumenTheme.TextSecondary, fontSize = 12.sp),
                )
                Text(
                    "Usage syncs between your devices, encrypted end to end. Disconnect to stop.",
                    style = TextStyle(color = LumenTheme.TextSecondary, fontSize = 12.sp),
                )
                Text(
                    "Disconnect",
                    style = TextStyle(color = LumenTheme.Accent, fontSize = 14.sp),
                    modifier = Modifier.clickable(onClick = onDisconnect),
                )
            }

            is AccountUiState.Working -> {
                Text(
                    state.message,
                    style = TextStyle(color = LumenTheme.TextSecondary, fontSize = 12.sp),
                )
            }

            is AccountUiState.Failed -> {
                Text(
                    state.message,
                    style = TextStyle(color = Color(0xFFE06C75), fontSize = 12.sp),
                )
                Spacer(Modifier.height(4.dp))
                accountForm(providers, selectedJid, username, password, onPick = { selectedJid = it }, onUsername = { username = it }, onPassword = { password = it }, onRegister = { onRegister(selectedJid, username, password) }, onSave = { onSave(selectedJid, username, password) })
            }

            AccountUiState.Unconfigured -> {
                Text(
                    "Pick a provider to sync your devices. Registration is in-app; your data stays end-to-end encrypted.",
                    style = TextStyle(color = LumenTheme.TextSecondary, fontSize = 12.sp),
                )
                accountForm(providers, selectedJid, username, password, onPick = { selectedJid = it }, onUsername = { username = it }, onPassword = { password = it }, onRegister = { onRegister(selectedJid, username, password) }, onSave = { onSave(selectedJid, username, password) })
            }
        }
    }
}

@Composable
private fun accountForm(
    providers: List<AccountProviderOption>,
    selectedJid: String,
    username: String,
    password: String,
    onPick: (String) -> Unit,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    onRegister: () -> Unit,
    onSave: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Provider",
            style = TextStyle(color = LumenTheme.TextSecondary, fontSize = 12.sp),
        )
        providers.forEach { provider ->
            val selected = provider.jid == selectedJid
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onPick(provider.jid) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    if (selected) "\u25CF" else "\u25CB",
                    style = TextStyle(color = LumenTheme.Accent, fontSize = 14.sp),
                )
                Text(
                    provider.jid,
                    style = TextStyle(
                        color = if (selected) LumenTheme.TextPrimary else LumenTheme.TextSecondary,
                        fontSize = 14.sp,
                    ),
                )
            }
        }
        Text(
            "Username",
            style = TextStyle(color = LumenTheme.TextSecondary, fontSize = 12.sp),
        )
        Text(
            username.ifBlank { "(your username — becomes user@provider)" },
            style = TextStyle(color = if (username.isBlank()) LumenTheme.TextSecondary else LumenTheme.TextPrimary, fontSize = 14.sp),
        )
        Text(
            "Password",
            style = TextStyle(color = LumenTheme.TextSecondary, fontSize = 12.sp),
        )
        Text(
            if (password.isBlank()) "(new password — stored in your keyring)" else "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022",
            style = TextStyle(color = LumenTheme.TextPrimary, fontSize = 14.sp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Create account",
                style = TextStyle(color = LumenTheme.Accent, fontSize = 14.sp),
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = username.isNotBlank() && password.isNotBlank()) { onRegister() }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
            Text(
                "Use existing",
                style = TextStyle(color = LumenTheme.TextSecondary, fontSize = 14.sp),
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = username.isNotBlank() && password.isNotBlank()) { onSave() }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}
