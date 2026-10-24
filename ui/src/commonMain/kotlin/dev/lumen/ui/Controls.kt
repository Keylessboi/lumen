package dev.lumen.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Shared building blocks for the settings-style screens (Sync, Scrobbling).
 *
 * Material 3 controls themed from LumenTheme. The screens drew their own
 * "buttons" out of clickable Text and their "inputs" out of plain Text that
 * could not be typed into, on whatever background the window happened to
 * have — the account form literally had nowhere to enter a username.
 */

/** The destructive/error colour. Red is reserved for this, never for a category. */
val LumenError = Color(0xFFE06C75)

private val LumenColors = darkColorScheme(
    primary = LumenTheme.Accent,
    onPrimary = LumenTheme.Background,
    secondary = LumenTheme.Accent,
    background = LumenTheme.Background,
    onBackground = LumenTheme.TextPrimary,
    surface = LumenTheme.Surface,
    onSurface = LumenTheme.TextPrimary,
    surfaceVariant = LumenTheme.Surface,
    onSurfaceVariant = LumenTheme.TextSecondary,
    outline = LumenTheme.Divider,
    error = LumenError,
)

@Composable
fun LumenMaterialTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LumenColors, content = content)
}

data class ShellTab(val id: String, val label: String)

/**
 * The app frame on Linux and Android: background, tab strip, content.
 *
 * Linux placed its tab Row and the Today screen as siblings directly in the
 * Window, which stacks children on top of each other — the tabs were drawn
 * over the screen's header and the Sync screen had no background at all.
 * Insets keep Android content clear of the status and navigation bars
 * (targetSdk 35 draws edge to edge).
 */
@Composable
fun LumenAppShell(
    tabs: List<ShellTab>,
    selectedId: String,
    onSelect: (String) -> Unit,
    content: @Composable () -> Unit,
) {
    LumenMaterialTheme {
        Column(
            Modifier
                .fillMaxSize()
                .background(LumenTheme.Background)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                tabs.forEach { tab ->
                    val selected = tab.id == selectedId
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .semantics { this.selected = selected }
                            .clickable(role = Role.Tab) { onSelect(tab.id) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            tab.label,
                            style = TextStyle(
                                color = if (selected) LumenTheme.TextPrimary else LumenTheme.TextSecondary,
                                fontSize = 14.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            ),
                        )
                        Spacer(Modifier.height(6.dp))
                        Box(
                            Modifier
                                .height(2.dp)
                                .widthIn(min = 24.dp)
                                .background(if (selected) LumenTheme.Accent else Color.Transparent, RoundedCornerShape(1.dp)),
                        )
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(LumenTheme.Divider))
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        }
    }
}

/** A scrolling, width-capped column for settings content. */
@Composable
fun SettingsPage(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

@Composable
fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(LumenTheme.Surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
fun PageTitle(text: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text, style = TextStyle(color = LumenTheme.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.SemiBold))
        if (subtitle != null) BodyText(subtitle, secondary = true)
    }
}

@Composable
fun CardTitle(text: String) {
    Text(text, style = TextStyle(color = LumenTheme.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
}

@Composable
fun BodyText(text: String, secondary: Boolean = false, color: Color? = null) {
    Text(
        text,
        style = TextStyle(
            color = color ?: if (secondary) LumenTheme.TextSecondary else LumenTheme.TextPrimary,
            fontSize = 13.sp,
            lineHeight = 19.sp,
        ),
    )
}

@Composable
fun ErrorText(text: String) = BodyText(text, color = LumenError)

@Composable
fun LumenField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    placeholder: String? = null,
    hint: String? = null,
    isError: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, color = LumenTheme.TextSecondary) } },
        supportingText = hint?.let { { Text(it) } },
        isError = isError,
        singleLine = true,
        enabled = enabled,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (secret) KeyboardType.Password else keyboardType,
            imeAction = imeAction,
            autoCorrectEnabled = false,
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = LumenTheme.TextPrimary,
            unfocusedTextColor = LumenTheme.TextPrimary,
            focusedBorderColor = LumenTheme.Accent,
            unfocusedBorderColor = LumenTheme.Divider,
            focusedLabelColor = LumenTheme.Accent,
            unfocusedLabelColor = LumenTheme.TextSecondary,
            cursorColor = LumenTheme.Accent,
            focusedSupportingTextColor = LumenTheme.TextSecondary,
            unfocusedSupportingTextColor = LumenTheme.TextSecondary,
            errorBorderColor = LumenError,
            errorSupportingTextColor = LumenError,
            errorLabelColor = LumenError,
        ),
    )
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = LumenTheme.Accent,
            contentColor = LumenTheme.Background,
            disabledContainerColor = LumenTheme.Divider,
            disabledContentColor = LumenTheme.TextSecondary,
        ),
    ) { Text(text, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun QuietButton(text: String, onClick: () -> Unit, enabled: Boolean = true, danger: Boolean = false) {
    TextButton(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(8.dp)) {
        Text(text, color = if (danger) LumenError else if (enabled) LumenTheme.Accent else LumenTheme.TextSecondary)
    }
}

/** A choice row with a radio dot, used for pickers. */
@Composable
fun ChoiceRow(title: String, detail: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) LumenTheme.Accent.copy(alpha = 0.10f) else Color.Transparent)
            .semantics { this.selected = selected }
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (selected) "◉" else "○",
            style = TextStyle(color = if (selected) LumenTheme.Accent else LumenTheme.TextSecondary, fontSize = 16.sp),
        )
        Column(Modifier.weight(1f)) {
            Text(title, style = TextStyle(color = LumenTheme.TextPrimary, fontSize = 14.sp))
            if (detail != null) Text(detail, style = TextStyle(color = LumenTheme.TextSecondary, fontSize = 12.sp))
        }
    }
}

/** A key/value line, value right-aligned. */
@Composable
fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = TextStyle(color = LumenTheme.TextSecondary, fontSize = 13.sp))
        Text(value, style = TextStyle(color = LumenTheme.TextPrimary, fontSize = 13.sp), textAlign = TextAlign.End)
    }
}
