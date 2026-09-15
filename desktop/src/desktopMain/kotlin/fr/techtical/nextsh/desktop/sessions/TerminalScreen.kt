// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.RotateCw
import com.composables.icons.lucide.ServerOff
import fr.techtical.nextsh.desktop.data.di.DesktopContainer
import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.reconnect_button
import fr.techtical.nextsh.desktop.generated.resources.reconnect_disconnected_title
import fr.techtical.nextsh.desktop.generated.resources.reconnect_error_title
import fr.techtical.nextsh.desktop.generated.resources.sessions_connecting
import fr.techtical.nextsh.desktop.generated.resources.sessions_password_button
import fr.techtical.nextsh.desktop.generated.resources.sessions_password_placeholder
import fr.techtical.nextsh.desktop.generated.resources.sessions_password_title
import org.jetbrains.compose.resources.stringResource
import fr.techtical.nextsh.desktop.theme.Border1
import fr.techtical.nextsh.desktop.theme.Burgundy
import fr.techtical.nextsh.desktop.theme.BurgundyLight
import fr.techtical.nextsh.desktop.theme.ErrorRed
import fr.techtical.nextsh.desktop.theme.Gold
import fr.techtical.nextsh.desktop.theme.GoldMuted
import fr.techtical.nextsh.desktop.theme.JetBrainsMonoFamily
import fr.techtical.nextsh.desktop.theme.NearBlack
import fr.techtical.nextsh.desktop.theme.Radii
import fr.techtical.nextsh.desktop.theme.SpaceGroteskFamily
import fr.techtical.nextsh.desktop.theme.Spacing
import fr.techtical.nextsh.desktop.theme.Surface
import fr.techtical.nextsh.desktop.theme.SurfaceVariant
import fr.techtical.nextsh.desktop.theme.ResolvedTheme
import fr.techtical.nextsh.desktop.theme.TextPrimary
import fr.techtical.nextsh.desktop.theme.TextSecondary
import fr.techtical.nextsh.desktop.theme.White

/**
 * Renders the content of a Terminal tab: the Compose-native terminal pane
 * (or one of the loader / password / error states). The extra keys bar from
 * Android is **not** shipped on Desktop: physical keyboard covers ESC / TAB
 * / modifiers / arrows / PgUp / PgDn natively. The theme picker lives in the
 * parent tab bar.
 *
 * Session ownership: [sessionManager] caches the [ComposeTerminalSession] by
 * SSH session id, so tab switches do NOT recreate it. Palette changes are
 * hot-swapped via a [LaunchedEffect] on the tab's [TerminalThemeId].
 */
@Composable
fun TerminalScreen(
    theme: ResolvedTheme,
    status: TerminalTabStatus,
    sessionManager: DesktopSessionManager,
    onSubmitPassword: (CharArray) -> Unit,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().background(NearBlack)) {
        when (status) {
            TerminalTabStatus.Connecting -> CenteredLoader(stringResource(Res.string.sessions_connecting))
            is TerminalTabStatus.AwaitingPassword -> PasswordPrompt(onSubmit = onSubmitPassword)
            is TerminalTabStatus.Connected -> ConnectedTerminalContent(
                theme = theme,
                status = status,
                sessionManager = sessionManager,
            )
            is TerminalTabStatus.Disconnected -> ErrorView(
                title = stringResource(Res.string.reconnect_disconnected_title),
                message = status.reason,
                onRetry = onReconnect,
            )
            is TerminalTabStatus.Error -> ErrorView(
                title = stringResource(Res.string.reconnect_error_title),
                message = status.message,
                onRetry = onReconnect,
            )
        }
    }
}

@Composable
private fun ConnectedTerminalContent(
    theme: ResolvedTheme,
    status: TerminalTabStatus.Connected,
    sessionManager: DesktopSessionManager,
) {
    val palette = theme.palette

    // Live terminal font size from DesktopSettingsStore. Collected here (the
    // same level the theme palette is read from tab content) so a Settings
    // slider change applies to already-OPEN sessions with no reconnect:
    // mirrors the palette live-swap below, except font size must also reach
    // ComposeTerminalRenderer as a composable parameter (see its kdoc): text
    // layout is `remember`-based and only recomputes on recomposition, it
    // can't be re-read for free inside the Canvas draw phase the way palette
    // colours are.
    val desktopSettings by DesktopContainer.settingsStore.settings.collectAsState()
    val fontSizeSp = desktopSettings.terminalFontSize.sp

    // Picker-close focus restore. When a modal picker DialogWindow (snippet /
    // host) closes, Compose hands focus back to the last focus owner of the
    // main window, which may be the TabBar button that opened the picker,
    // not the terminal. Main.kt bumps this epoch on the picker's open→closed
    // transition; the effect below re-grabs focus, gated to the active tab's
    // focused pane so split siblings don't steal it.
    val focusRestoreEpoch by sessionManager.terminalFocusEpoch.collectAsState()

    val composeSession = remember(status.sshSessionId) {
        sessionManager.getOrCreateComposeSession(
            status.sshSessionId,
            status.terminal.connector,
            palette,
            desktopSettings.terminalFontSize,
        )
    }
    LaunchedEffect(palette, composeSession) {
        // Theme swap goes through the same TechticalTerminalSettings
        // mutable palette: the renderer Canvas re-reads the styles via
        // the supplier mechanism, so no session recreation is needed.
        composeSession.settings.setPalette(palette)
        composeSession.display.forceInvalidate()
    }
    LaunchedEffect(desktopSettings.terminalFontSize, composeSession) {
        // Keeps TechticalTerminalSettings as the source of truth for the
        // session's current font size (used as the starting point for any
        // future reader/session); the actual live re-measure below is driven
        // by passing fontSizeSp straight into ComposeTerminalRenderer.
        composeSession.settings.setFontSize(desktopSettings.terminalFontSize)
    }
    val terminalFocusRequester = remember(status.sshSessionId) { FocusRequester() }
    LaunchedEffect(focusRestoreEpoch) {
        if (focusRestoreEpoch == 0L) return@LaunchedEffect
        if (sessionManager.activeFocusedTerminalSshSessionId() != status.sshSessionId) {
            return@LaunchedEffect
        }
        // requestFocus() sets the scene's focus owner even while the
        // frame hasn't regained OS focus yet: keys route to the terminal
        // once the picker's DialogWindow is fully gone.
        runCatching { terminalFocusRequester.requestFocus() }
    }
    fr.techtical.nextsh.desktop.sessions.compose.ComposeTerminalRenderer(
        session = composeSession,
        modifier = Modifier.fillMaxSize(),
        externalFocusRequester = terminalFocusRequester,
        fontSize = fontSizeSp,
    )
}

@Composable
private fun CenteredLoader(message: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(color = Gold)
        Spacer(Modifier.height(16.dp))
        Text(message, color = TextSecondary)
    }
}

@Composable
private fun PasswordPrompt(onSubmit: (CharArray) -> Unit) {
    var pwd by remember { mutableStateOf("") }
    val submit = {
        val chars = pwd.toCharArray()
        pwd = ""
        onSubmit(chars)
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(Res.string.sessions_password_title),
            color = TextPrimary,
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = pwd,
            onValueChange = { pwd = it },
            label = { Text(stringResource(Res.string.sessions_password_placeholder)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.width(360.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Gold,
                unfocusedBorderColor = GoldMuted,
                focusedLabelColor = Gold,
                unfocusedLabelColor = TextSecondary,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                cursorColor = Gold,
                focusedContainerColor = SurfaceVariant,
                unfocusedContainerColor = SurfaceVariant,
            ),
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = submit,
            colors = ButtonDefaults.buttonColors(containerColor = Burgundy, contentColor = White),
            modifier = Modifier.width(360.dp),
        ) {
            Text(stringResource(Res.string.sessions_password_button))
        }
    }
}

@Composable
private fun ErrorView(title: String, message: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(NearBlack).padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .background(Surface, RoundedCornerShape(Radii.Lg))
                .border(1.dp, Border1, RoundedCornerShape(Radii.Lg))
                .padding(horizontal = 32.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(ErrorRed.copy(alpha = 0.12f), RoundedCornerShape(Radii.Md)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Lucide.ServerOff,
                    contentDescription = null,
                    tint = ErrorRed,
                    modifier = Modifier.size(26.dp),
                )
            }
            Spacer(Modifier.height(Spacing.Md))
            Text(
                text = title,
                color = ErrorRed,
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
            )
            Spacer(Modifier.height(Spacing.Xs))
            Text(
                text = message,
                color = TextSecondary,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(Spacing.Lg))
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Burgundy,
                    contentColor = White,
                ),
                shape = RoundedCornerShape(Radii.Md),
            ) {
                Icon(
                    Lucide.RotateCw,
                    contentDescription = null,
                    tint = White,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(Spacing.Sm))
                Text(
                    text = stringResource(Res.string.reconnect_button),
                    color = White,
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                )
            }
        }
    }
}
