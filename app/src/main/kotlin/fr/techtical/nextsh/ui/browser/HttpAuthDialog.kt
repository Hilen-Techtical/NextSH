// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.composables.icons.lucide.Eye
import com.composables.icons.lucide.EyeOff
import com.composables.icons.lucide.Lock
import com.composables.icons.lucide.Lucide
import fr.techtical.nextsh.R
import fr.techtical.nextsh.ui.components.NextShTextField
import fr.techtical.nextsh.ui.theme.Burgundy
import fr.techtical.nextsh.ui.theme.ErrorRed
import fr.techtical.nextsh.ui.theme.Gold
import fr.techtical.nextsh.ui.theme.GoldLight
import fr.techtical.nextsh.ui.theme.JetBrainsMonoFamily
import fr.techtical.nextsh.ui.theme.Radii
import fr.techtical.nextsh.ui.theme.SpaceGroteskFamily
import fr.techtical.nextsh.ui.theme.Spacing
import fr.techtical.nextsh.ui.theme.Surface
import fr.techtical.nextsh.ui.theme.TextDisabled
import fr.techtical.nextsh.ui.theme.TextPrimary
import fr.techtical.nextsh.ui.theme.TextSecondary

/**
 * Formulaire d'identifiants pour un service tunnelé qui répond 401 (HTTP Basic
 * ou Digest).
 *
 * Les valeurs saisies n'existent que dans l'état de cette composition, indexé
 * sur [HttpAuthPrompt.id] : chaque nouveau défi repart vide, nom d'utilisateur
 * compris. Jamais de `rememberSaveable` ici, un secret ne doit pas finir dans un
 * Bundle. Valider les remet au WebView ; fermer le dialogue, de n'importe quelle
 * façon (Annuler, retour), passe par [onCancel]. Les deux rappels portent l'id du
 * prompt pour que le ViewModel ignore une réponse qui ne correspond plus au défi
 * en cours.
 *
 * Limites de la plateforme, acceptées : le WebView garde les identifiants acceptés
 * dans son cache d'authentification HTTP, commun à tout le processus, jusqu'à la
 * fin du processus de l'app (aucune API publique ne le vide) ; NextSH ne les écrit
 * jamais sur disque. Et si la page navigue ou s'interrompt pendant que le dialogue
 * est ouvert, la plateforme remplace le défi : le dialogue ne répond plus à rien
 * tant qu'il n'est pas fermé.
 */
@Composable
internal fun HttpAuthDialog(
    prompt: HttpAuthPrompt,
    onSubmit: (promptId: Long, username: String, password: String) -> Unit,
    onCancel: (promptId: Long) -> Unit,
) {
    var username by remember(prompt.id) { mutableStateOf("") }
    var password by remember(prompt.id) { mutableStateOf("") }
    var passwordVisible by remember(prompt.id) { mutableStateOf(false) }
    val usernameFocus = remember(prompt.id) { FocusRequester() }

    val canSubmit = username.isNotEmpty() || password.isNotEmpty()
    val submit: () -> Unit = {
        if (canSubmit) {
            val typedUsername = username
            // HttpAuthHandler.proceed prend des String : la règle CharArray à effacer
            // ne peut pas s'appliquer ici.
            val typedPassword = password
            username = ""
            password = ""
            onSubmit(prompt.id, typedUsername, typedPassword)
        }
    }
    val cancel: () -> Unit = {
        username = ""
        password = ""
        onCancel(prompt.id)
    }

    AlertDialog(
        onDismissRequest = cancel,
        // Un tap à côté ne doit pas annuler une saisie en cours.
        properties = DialogProperties(dismissOnClickOutside = false),
        containerColor = Surface,
        shape = RoundedCornerShape(Radii.Lg),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(Burgundy.copy(alpha = 0.16f), RoundedCornerShape(Radii.Md)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Lucide.Lock,
                        contentDescription = null,
                        tint     = GoldLight,
                        modifier = Modifier.size(13.dp),
                    )
                }
                Text(
                    text       = stringResource(R.string.browser_auth_title),
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize   = 16.sp,
                    color      = TextPrimary,
                )
            }
        },
        text = {
            // Une frame d'abord pour que le dialogue fraîchement composé puisse
            // prendre le focus. Toujours sur le nom d'utilisateur : les champs
            // repartent vides à chaque défi, y compris après un refus.
            LaunchedEffect(prompt.id) {
                withFrameNanos { }
                runCatching { usernameFocus.requestFocus() }
            }

            // Défilable : en paysage avec le clavier ouvert, la hauteur restante
            // ne suffit pas à afficher le message et deux champs.
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.Md),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.Xs)) {
                    Text(
                        text       = stringResource(R.string.browser_auth_message, prompt.origin),
                        fontFamily = SpaceGroteskFamily,
                        fontSize   = 13.sp,
                        color      = TextPrimary,
                    )
                    if (prompt.realm != null) {
                        Text(
                            text       = stringResource(R.string.browser_auth_realm, prompt.realm),
                            fontFamily = JetBrainsMonoFamily,
                            fontSize   = 11.sp,
                            color      = TextSecondary,
                            maxLines   = 2,
                            overflow   = TextOverflow.Ellipsis,
                        )
                    }
                    if (prompt.rejected) {
                        Text(
                            text       = stringResource(R.string.browser_auth_rejected),
                            fontFamily = SpaceGroteskFamily,
                            fontSize   = 12.sp,
                            color      = ErrorRed,
                        )
                    }
                }

                NextShTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = stringResource(R.string.label_username),
                    isError = prompt.rejected,
                    // Suivant passe au mot de passe.
                    keyboardOptions = KeyboardOptions(autoCorrect = false, imeAction = ImeAction.Next),
                    modifier = Modifier.focusRequester(usernameFocus),
                )
                NextShTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = stringResource(R.string.label_password),
                    isError = prompt.rejected,
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        autoCorrect = false,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Lucide.EyeOff else Lucide.Eye,
                                contentDescription = stringResource(
                                    if (passwordVisible) R.string.action_hide_password else R.string.action_show_password,
                                ),
                                tint = TextSecondary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = submit,
                enabled = canSubmit,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = Gold,
                    disabledContentColor = TextDisabled,
                ),
            ) {
                Text(
                    text       = stringResource(R.string.browser_auth_submit),
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize   = 13.sp,
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = cancel,
                colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary),
            ) {
                Text(
                    text       = stringResource(R.string.action_cancel),
                    fontFamily = SpaceGroteskFamily,
                    fontSize   = 13.sp,
                )
            }
        },
    )
}
