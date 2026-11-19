// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Eye
import com.composables.icons.lucide.EyeOff
import com.composables.icons.lucide.Lucide
import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.action_cancel
import fr.techtical.nextsh.desktop.generated.resources.browser_auth_hide_password
import fr.techtical.nextsh.desktop.generated.resources.browser_auth_message
import fr.techtical.nextsh.desktop.generated.resources.browser_auth_password
import fr.techtical.nextsh.desktop.generated.resources.browser_auth_realm
import fr.techtical.nextsh.desktop.generated.resources.browser_auth_rejected
import fr.techtical.nextsh.desktop.generated.resources.browser_auth_show_password
import fr.techtical.nextsh.desktop.generated.resources.browser_auth_submit
import fr.techtical.nextsh.desktop.generated.resources.browser_auth_title
import fr.techtical.nextsh.desktop.generated.resources.browser_auth_username
import fr.techtical.nextsh.desktop.theme.Border1
import fr.techtical.nextsh.desktop.theme.ErrorRed
import fr.techtical.nextsh.desktop.theme.Gold
import fr.techtical.nextsh.desktop.theme.GoldMuted
import fr.techtical.nextsh.desktop.theme.Radii
import fr.techtical.nextsh.desktop.theme.Surface
import fr.techtical.nextsh.desktop.theme.SurfaceVariant
import fr.techtical.nextsh.desktop.theme.TextDisabled
import fr.techtical.nextsh.desktop.theme.TextPrimary
import fr.techtical.nextsh.desktop.theme.TextSecondary
import fr.techtical.nextsh.desktop.theme.WarningAmber
import org.jetbrains.compose.resources.stringResource

/**
 * Credentials form for a tunnelled service answering 401 with an HTTP Basic challenge,
 * rendered inline in the browser overlay: a plain composable card, not a Dialog or a
 * Popup, because those open as separate OS windows on Desktop and would outlive the
 * overlay.
 *
 * The typed values exist only in this composition's state, keyed on [HttpAuthPrompt.id]
 * so every new challenge starts empty (the username is not prefilled either); they are
 * never persisted. Confirming hands them to [onSubmit] as fresh [CharArray]s (the receiver
 * owns and wipes them) and empties the fields; dismissing in any way (Cancel button or
 * Escape) goes through [onCancel]. Both callbacks carry the prompt id so the receiver can
 * ignore an answer that no longer matches the pending challenge.
 *
 * A text field edits a [String], which cannot be wiped: the typed text stays on the heap
 * until the garbage collector reclaims it. Only the copies handed on are wiped.
 */
@Composable
internal fun HttpAuthPanel(
    prompt: HttpAuthPrompt,
    onSubmit: (promptId: Long, username: CharArray, password: CharArray) -> Unit,
    onCancel: (promptId: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var username by remember(prompt.id) { mutableStateOf("") }
    var password by remember(prompt.id) { mutableStateOf("") }
    var passwordVisible by remember(prompt.id) { mutableStateOf(false) }
    val usernameFocus = remember(prompt.id) { FocusRequester() }
    val passwordFocus = remember(prompt.id) { FocusRequester() }
    // The panel itself can hold the focus, so that Escape keeps working when no field has it.
    val panelFocus = remember(prompt.id) { FocusRequester() }
    val title = stringResource(Res.string.browser_auth_title)

    val canSubmit = username.isNotEmpty() || password.isNotEmpty()
    val submit: () -> Unit = {
        if (canSubmit) {
            val user = username.toCharArray()
            val pass = password.toCharArray()
            username = ""
            password = ""
            onSubmit(prompt.id, user, pass)
        }
    }
    val cancel: () -> Unit = { onCancel(prompt.id) }

    // One frame first so the freshly composed card can take focus. After a refusal the
    // username is most likely right, so the cursor goes straight to the password. The
    // overlay has already taken the keyboard away from the web page (see its prompt effect).
    LaunchedEffect(prompt.id) {
        withFrameNanos { }
        val field = if (prompt.rejected) passwordFocus else usernameFocus
        if (runCatching { field.requestFocus() }.isFailure) runCatching { panelFocus.requestFocus() }
    }

    val shape = RoundedCornerShape(Radii.Xl)
    Column(
        modifier = modifier
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(Surface)
            .border(1.dp, Border1, shape)
            // A pane of its own for assistive technology, announced by its title.
            .semantics { paneTitle = title }
            // Escape cancels from anywhere inside the panel, whichever child has the focus,
            // the panel itself included. The handler comes before focusable() so that the panel,
            // as the focus target, is below it and still gets its events.
            .onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown && ev.key == Key.Escape) {
                    cancel()
                    true
                } else {
                    false
                }
            }
            .focusRequester(panelFocus)
            .focusable()
            .padding(24.dp),
    ) {
        Text(
            text = title,
            color = TextPrimary,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(Res.string.browser_auth_message, prompt.origin),
            color = TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (prompt.realm != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(Res.string.browser_auth_realm, prompt.realm),
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (prompt.rejected) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(Res.string.browser_auth_rejected),
                color = WarningAmber,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text(stringResource(Res.string.browser_auth_username)) },
            singleLine = true,
            isError = prompt.rejected,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(usernameFocus),
            colors = authFieldColors(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(Res.string.browser_auth_password)) },
            singleLine = true,
            isError = prompt.rejected,
            visualTransformation = if (passwordVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            trailingIcon = {
                IconButton(
                    onClick = { passwordVisible = !passwordVisible },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = if (passwordVisible) Lucide.EyeOff else Lucide.Eye,
                        contentDescription = stringResource(
                            if (passwordVisible) {
                                Res.string.browser_auth_hide_password
                            } else {
                                Res.string.browser_auth_show_password
                            },
                        ),
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(passwordFocus),
            colors = authFieldColors(),
        )
        Spacer(Modifier.height(24.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            TextButton(
                onClick = cancel,
                colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary),
            ) { Text(stringResource(Res.string.action_cancel)) }
            TextButton(
                onClick = submit,
                enabled = canSubmit,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = Gold,
                    disabledContentColor = TextDisabled,
                ),
            ) { Text(stringResource(Res.string.browser_auth_submit)) }
        }
    }
}

@Composable
private fun authFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Gold,
    unfocusedBorderColor = GoldMuted,
    focusedLabelColor = Gold,
    unfocusedLabelColor = TextSecondary,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    cursorColor = Gold,
    focusedContainerColor = SurfaceVariant,
    unfocusedContainerColor = SurfaceVariant,
    errorBorderColor = ErrorRed,
    errorLabelColor = ErrorRed,
    errorTextColor = TextPrimary,
    errorCursorColor = Gold,
    errorContainerColor = SurfaceVariant,
)
