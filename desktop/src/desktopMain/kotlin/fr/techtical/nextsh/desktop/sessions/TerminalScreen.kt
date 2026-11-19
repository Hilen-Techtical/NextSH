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
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.composables.icons.lucide.Terminal
import fr.techtical.nextsh.desktop.data.di.DesktopContainer
import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.reconnect_button
import fr.techtical.nextsh.desktop.generated.resources.reconnect_disconnected_title
import fr.techtical.nextsh.desktop.generated.resources.reconnect_ended_message
import fr.techtical.nextsh.desktop.generated.resources.reconnect_ended_message_code
import fr.techtical.nextsh.desktop.generated.resources.reconnect_ended_title
import fr.techtical.nextsh.desktop.generated.resources.reconnect_error_title
import fr.techtical.nextsh.desktop.generated.resources.sessions_action_close
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
    onClose: () -> Unit,
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
            is TerminalTabStatus.Ended -> EndedTerminal(
                theme = theme,
                status = status,
                sessionManager = sessionManager,
                onReconnect = onReconnect,
                onClose = onClose,
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
    val desktopSettings by DesktopContainer.settingsStore.settings.collectAsState()

    val composeSession = remember(status.sshSessionId) {
        sessionManager.getOrCreateComposeSession(
            status.sshSessionId,
            status.terminal.connector,
            palette,
            desktopSettings.terminalFontSize,
        )
    }
    RenderedTerminal(theme, sessionManager, status.sshSessionId, composeSession)
}

/**
 * The renderer of a terminal session with its live theme and font-size effects and the picker-close focus
 * restore. Shared by a connected pane and an ended one (read-only: the session drops what is typed).
 */
@Composable
private fun RenderedTerminal(
    theme: ResolvedTheme,
    sessionManager: DesktopSessionManager,
    sshSessionId: String,
    composeSession: fr.techtical.nextsh.desktop.sessions.compose.ComposeTerminalSession,
) {
    val palette = theme.palette
    val desktopSettings by DesktopContainer.settingsStore.settings.collectAsState()
    val fontSizeSp = desktopSettings.terminalFontSize.sp
    val focusRestoreEpoch by sessionManager.terminalFocusEpoch.collectAsState()
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
    val terminalFocusRequester = remember(sshSessionId) { FocusRequester() }
    LaunchedEffect(focusRestoreEpoch) {
        if (focusRestoreEpoch == 0L) return@LaunchedEffect
        if (sessionManager.activeFocusedTerminalSshSessionId() != sshSessionId) {
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

/**
 * A pane whose remote shell ended by itself: the terminal stays readable (the last words of the server, such as
 * a `nologin` message), read-only, with a neutral banner underneath offering Reconnect and Close.
 */
@Composable
private fun EndedTerminal(
    theme: ResolvedTheme,
    status: TerminalTabStatus.Ended,
    sessionManager: DesktopSessionManager,
    onReconnect: () -> Unit,
    onClose: () -> Unit,
) {
    val composeSession = remember(status.sshSessionId) { sessionManager.composeSessionFor(status.sshSessionId) }
    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            if (composeSession != null) RenderedTerminal(theme, sessionManager, status.sshSessionId, composeSession)
        }
        EndedBanner(
            title = stringResource(Res.string.reconnect_ended_title),
            message = status.exitCode?.let { stringResource(Res.string.reconnect_ended_message_code, it) }
                ?: stringResource(Res.string.reconnect_ended_message),
            onReconnect = onReconnect,
            onClose = onClose,
        )
    }
}


@Composable
private fun EndedBanner(title: String, message: String, onReconnect: () -> Unit, onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface)
            .border(1.dp, Border1)
            .padding(horizontal = Spacing.Lg, vertical = Spacing.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Lucide.Terminal,
            contentDescription = null,
            tint = TextSecondary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(Spacing.Md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = TextPrimary,
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
            )
            Text(
                text = message,
                color = TextSecondary,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 12.sp,
            )
        }
        Spacer(Modifier.width(Spacing.Md))
        OutlinedButton(onClick = onClose, shape = RoundedCornerShape(Radii.Md)) {
            Text(
                text = stringResource(Res.string.sessions_action_close),
                color = TextPrimary,
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
            )
        }
        Spacer(Modifier.width(Spacing.Sm))
        Button(
            onClick = onReconnect,
            colors = ButtonDefaults.buttonColors(containerColor = Burgundy, contentColor = White),
            shape = RoundedCornerShape(Radii.Md),
        ) {
            Icon(Lucide.RotateCw, contentDescription = null, tint = White, modifier = Modifier.size(14.dp))
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
private fun ErrorView(
    title: String,
    message: String,
    onRetry: () -> Unit,
    titleColor: Color = ErrorRed,
    iconTint: Color = ErrorRed,
    icon: ImageVector = Lucide.ServerOff,
    onClose: (() -> Unit)? = null,
) {
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
                    .background(iconTint.copy(alpha = 0.12f), RoundedCornerShape(Radii.Md)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(26.dp),
                )
            }
            Spacer(Modifier.height(Spacing.Md))
            Text(
                text = title,
                color = titleColor,
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
            if (onClose != null) {
                Spacer(Modifier.height(Spacing.Sm))
                OutlinedButton(
                    onClick = onClose,
                    shape = RoundedCornerShape(Radii.Md),
                ) {
                    Text(
                        text = stringResource(Res.string.sessions_action_close),
                        color = TextPrimary,
                        fontFamily = SpaceGroteskFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }
}
