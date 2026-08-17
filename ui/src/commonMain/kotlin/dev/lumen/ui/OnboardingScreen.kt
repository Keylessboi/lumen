package dev.lumen.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── Data ────────────────────────────────────────────────────────────

/** Onboarding flow state. */
data class OnboardingState(
    val step: Int = 1,
    val selectedProvider: String? = null,
    val username: String = "",
    val password: String = "",
    /** Daily target in milliseconds. Default: 4 hours. */
    val targetMs: Long = 14_400_000L,
)

/** A sync provider the user can choose during onboarding. */
data class OnboardingProvider(
    val jid: String,
    val label: String,
)

private val DefaultProviders = listOf(
    OnboardingProvider("yax.im", "yax.im"),
    OnboardingProvider("jabber.fr", "jabber.fr"),
    OnboardingProvider("chat.between-us.online", "chat.between-us.online"),
    OnboardingProvider("xmpp.party", "xmpp.party"),
    OnboardingProvider("jabber.vg", "jabber.vg"),
)

private const val TARGET_MIN_MS = 1_800_000L   // 30 minutes
private const val TARGET_MAX_MS = 43_200_000L  // 12 hours

// ── Main screen ─────────────────────────────────────────────────────

/**
 * Three-step onboarding for new users.
 *
 * Step 1 — Provider picker: where the user's data lives.
 * Step 2 — Registration: username + password.
 * Step 3 — Target screentime: a daily goal.
 *
 * All navigation and I/O is callback-driven; this screen owns no
 * persisted state — only local UI state via [remember].
 */
@Composable
fun OnboardingScreen(
    onRegister: (providerJid: String, username: String, password: String) -> Unit,
    onSave: (providerJid: String, username: String, password: String) -> Unit,
    onSetTarget: (targetMs: Long) -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
    providers: List<OnboardingProvider> = DefaultProviders,
) {
    var step by remember { mutableStateOf(1) }
    var selectedProvider by remember { mutableStateOf<String?>(null) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var targetMs by remember { mutableStateOf(14_400_000L) }

    Column(
        modifier
            .fillMaxSize()
            .background(LumenTheme.Background)
            .padding(start = 32.dp, end = 32.dp, top = 44.dp, bottom = 28.dp)
    ) {
        StepIndicator(step)
        Spacer(Modifier.height(32.dp))

        when (step) {
            1 -> ProviderStep(
                providers = providers,
                selectedProvider = selectedProvider,
                onSelectProvider = { selectedProvider = it },
                onNext = { if (selectedProvider != null) step = 2 },
                onSkipLocal = onSkip,
            )
            2 -> RegistrationStep(
                username = username,
                password = password,
                onUsernameChange = { username = it },
                onPasswordChange = { password = it },
                onRegister = { onRegister(selectedProvider ?: "", username, password) },
                onUseExisting = { onSave(selectedProvider ?: "", username, password) },
                onBack = { step = 1 },
                canProceed = username.isNotBlank() && password.isNotBlank(),
            )
            3 -> TargetStep(
                targetMs = targetMs,
                onTargetChange = { targetMs = it },
                onConfirm = { onSetTarget(targetMs) },
                onBack = { step = 2 },
            )
        }
    }
}

// ── Step indicator ──────────────────────────────────────────────────

@Composable
private fun StepIndicator(current: Int) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 1..3) {
            Text(
                text = "$i/3",
                style = TextStyle(
                    color = if (i == current) LumenTheme.TextPrimary else LumenTheme.TextSecondary,
                    fontSize = 12.sp,
                    fontFamily = LumenTheme.TabularFigures,
                    fontFeatureSettings = "tnum",
                ),
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

// ── Step 1: Provider picker ─────────────────────────────────────────

@Composable
private fun ProviderStep(
    providers: List<OnboardingProvider>,
    selectedProvider: String?,
    onSelectProvider: (String) -> Unit,
    onNext: () -> Unit,
    onSkipLocal: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            "Where should your data live?",
            style = TextStyle(
                color = LumenTheme.TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Light,
            ),
        )

        Text(
            "Your usage data syncs between devices through one of these " +
                "providers, encrypted end to end.",
            style = TextStyle(color = LumenTheme.TextSecondary, fontSize = 13.sp),
        )

        Spacer(Modifier.height(8.dp))

        providers.forEach { provider ->
            val selected = provider.jid == selectedProvider
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onSelectProvider(provider.jid) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    if (selected) "\u25CF" else "\u25CB",
                    style = TextStyle(color = LumenTheme.Accent, fontSize = 14.sp),
                )
                Text(
                    provider.label,
                    style = TextStyle(
                        color = if (selected) LumenTheme.TextPrimary else LumenTheme.TextSecondary,
                        fontSize = 14.sp,
                    ),
                )
            }
        }

        // Skip (local only)
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .clickable { onSkipLocal() }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "\u25CB",
                style = TextStyle(color = LumenTheme.Accent, fontSize = 14.sp),
            )
            Text(
                "Skip (local only)",
                style = TextStyle(color = LumenTheme.TextSecondary, fontSize = 14.sp),
            )
        }

        Spacer(Modifier.height(8.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Text(
                "Next",
                style = TextStyle(
                    color = if (selectedProvider != null) LumenTheme.Accent else LumenTheme.Divider,
                    fontSize = 14.sp,
                ),
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = selectedProvider != null) { onNext() }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

// ── Step 2: Registration form ───────────────────────────────────────

@Composable
private fun RegistrationStep(
    username: String,
    password: String,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onRegister: () -> Unit,
    onUseExisting: () -> Unit,
    onBack: () -> Unit,
    canProceed: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            "Create your account",
            style = TextStyle(
                color = LumenTheme.TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Light,
            ),
        )

        Text(
            "Your credentials are stored in your system keyring, not in any file.",
            style = TextStyle(color = LumenTheme.TextSecondary, fontSize = 13.sp),
        )

        Spacer(Modifier.height(8.dp))

        // Username
        Text(
            "Username",
            style = TextStyle(color = LumenTheme.TextSecondary, fontSize = 12.sp),
        )
        BasicTextField(
            value = username,
            onValueChange = onUsernameChange,
            singleLine = true,
            textStyle = TextStyle(
                color = LumenTheme.TextPrimary,
                fontSize = 14.sp,
            ),
            cursorBrush = SolidColor(LumenTheme.Accent),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(LumenTheme.Surface)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            decorationBox = { inner ->
                if (username.isEmpty()) {
                    Text(
                        "your-name",
                        style = TextStyle(color = LumenTheme.TextSecondary, fontSize = 14.sp),
                    )
                }
                inner()
            },
        )

        // Password
        Text(
            "Password",
            style = TextStyle(color = LumenTheme.TextSecondary, fontSize = 12.sp),
        )
        BasicTextField(
            value = password,
            onValueChange = onPasswordChange,
            singleLine = true,
            textStyle = TextStyle(
                color = LumenTheme.TextPrimary,
                fontSize = 14.sp,
            ),
            cursorBrush = SolidColor(LumenTheme.Accent),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(LumenTheme.Surface)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            decorationBox = { inner ->
                if (password.isEmpty()) {
                    Text(
                        "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022",
                        style = TextStyle(color = LumenTheme.TextSecondary, fontSize = 14.sp),
                    )
                }
                inner()
            },
        )

        Spacer(Modifier.height(8.dp))

        // Actions
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Create account",
                style = TextStyle(
                    color = if (canProceed) LumenTheme.Accent else LumenTheme.Divider,
                    fontSize = 14.sp,
                ),
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = canProceed) { onRegister() }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
            Text(
                "Use existing",
                style = TextStyle(
                    color = if (canProceed) LumenTheme.TextSecondary else LumenTheme.Divider,
                    fontSize = 14.sp,
                ),
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = canProceed) { onUseExisting() }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        Spacer(Modifier.weight(1f))

        Text(
            "Back",
            style = TextStyle(color = LumenTheme.TextSecondary, fontSize = 14.sp),
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { onBack() }
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

// ── Step 3: Target screentime ───────────────────────────────────────

@Composable
private fun TargetStep(
    targetMs: Long,
    onTargetChange: (Long) -> Unit,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            "Set a daily target",
            style = TextStyle(
                color = LumenTheme.TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Light,
            ),
        )

        Text(
            "A quiet reference point. You can change this anytime in settings.",
            style = TextStyle(color = LumenTheme.TextSecondary, fontSize = 13.sp),
        )

        Spacer(Modifier.height(16.dp))

        // Duration display
        Text(
            formatDuration(targetMs),
            style = TextStyle(
                color = LumenTheme.TextPrimary,
                fontSize = 40.sp,
                fontWeight = FontWeight.Light,
                fontFamily = LumenTheme.TabularFigures,
                fontFeatureSettings = "tnum",
            ),
        )

        // Slider
        Slider(
            value = targetMs.toFloat(),
            onValueChange = { onTargetChange(it.toLong()) },
            valueRange = TARGET_MIN_MS.toFloat()..TARGET_MAX_MS.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = LumenTheme.Accent,
                activeTrackColor = LumenTheme.Accent,
                inactiveTrackColor = LumenTheme.Divider,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        // Range labels
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                formatDuration(TARGET_MIN_MS),
                style = TextStyle(color = LumenTheme.TextSecondary, fontSize = 11.sp),
            )
            Text(
                formatDuration(TARGET_MAX_MS),
                style = TextStyle(color = LumenTheme.TextSecondary, fontSize = 11.sp),
            )
        }

        Spacer(Modifier.height(8.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "Back",
                style = TextStyle(color = LumenTheme.TextSecondary, fontSize = 14.sp),
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onBack() }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
            Text(
                "Done",
                style = TextStyle(color = LumenTheme.Accent, fontSize = 14.sp),
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onConfirm() }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}
