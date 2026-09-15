// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.vault

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.LockOpen
import com.composables.icons.lucide.Lucide
import fr.techtical.nextsh.desktop.components.BrandText
import fr.techtical.nextsh.desktop.theme.Border1
import fr.techtical.nextsh.desktop.theme.Border2
import fr.techtical.nextsh.desktop.theme.Burgundy
import fr.techtical.nextsh.desktop.theme.BurgundyLight
import fr.techtical.nextsh.desktop.theme.ErrorRed
import fr.techtical.nextsh.desktop.theme.Gold
import fr.techtical.nextsh.desktop.theme.GoldLight
import fr.techtical.nextsh.desktop.theme.JetBrainsMonoFamily
import fr.techtical.nextsh.desktop.theme.NearBlack
import fr.techtical.nextsh.desktop.theme.Radii
import fr.techtical.nextsh.desktop.theme.SpaceGroteskFamily
import fr.techtical.nextsh.desktop.theme.Spacing
import fr.techtical.nextsh.desktop.theme.SuccessGreen
import fr.techtical.nextsh.desktop.theme.Surface
import fr.techtical.nextsh.desktop.theme.SurfaceVariant
import fr.techtical.nextsh.desktop.theme.TextDisabled
import fr.techtical.nextsh.desktop.theme.TextPrimary
import fr.techtical.nextsh.desktop.theme.TextSecondary
import fr.techtical.nextsh.desktop.theme.White
import androidx.compose.ui.graphics.graphicsLayer
import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.action_back
import fr.techtical.nextsh.desktop.generated.resources.recovery_unlock_input_label
import fr.techtical.nextsh.desktop.generated.resources.recovery_unlock_input_placeholder
import fr.techtical.nextsh.desktop.generated.resources.recovery_unlock_subtitle
import fr.techtical.nextsh.desktop.generated.resources.recovery_unlock_submit
import fr.techtical.nextsh.desktop.generated.resources.recovery_unlock_title
import fr.techtical.nextsh.desktop.generated.resources.recovery_unlock_word_count
import fr.techtical.nextsh.desktop.generated.resources.recovery_set_pin_button
import fr.techtical.nextsh.desktop.generated.resources.recovery_set_pin_confirm_label
import fr.techtical.nextsh.desktop.generated.resources.recovery_set_pin_label
import fr.techtical.nextsh.desktop.generated.resources.recovery_set_pin_subtitle
import fr.techtical.nextsh.desktop.generated.resources.recovery_set_pin_title
import fr.techtical.nextsh.desktop.generated.resources.vault_unlock_button
import fr.techtical.nextsh.desktop.generated.resources.vault_unlock_button_loading
import fr.techtical.nextsh.desktop.generated.resources.vault_unlock_logo_alt
import fr.techtical.nextsh.desktop.generated.resources.vault_unlock_recovery_link
import fr.techtical.nextsh.desktop.generated.resources.vault_unlock_subtitle
import org.jetbrains.compose.resources.stringResource

/**
 * Vault unlock screen, refonte Phase 2.5.
 *
 * Layout : Box NearBlack centered → Card Surface 380dp avec brand NextSH,
 * sous-titre, PIN field centré (font-mono, password mask), bouton primary
 * Burgundy "Déverrouiller le vault", footer crypto info, shake animation
 * sur erreur. Le PIN reste un champ unique (NextSH supporte PIN variable
 * 4+ caractères, contrairement à la maquette qui montre 6 cells digit-only).
 */
/**
 * Sub-views of the unlock screen. The default [Pin] view is the legacy PIN
 * entry; [RecoveryEntry] lets a user who forgot their PIN unlock via the
 * 12-word phrase; [SetNewPin] forces them to choose a new PIN immediately
 * after a successful recovery unlock (the DEK is now in memory, so
 * [VaultViewModel.setNewPin] re-wraps it).
 */
private enum class UnlockView { Pin, RecoveryEntry, SetNewPin }

@Composable
fun VaultUnlockScreen(onUnlocked: () -> Unit) {
    val viewModel = remember { VaultViewModel() }
    val state by viewModel.state.collectAsState()
    var view by remember { mutableStateOf(UnlockView.Pin) }
    // Resolved once at first composition: controls whether the recovery link
    // is offered at all. Migrated (legacy) vaults have no recovery wrap until
    // the user generates one in Settings.
    val recoveryAvailable = remember { viewModel.hasRecoveryPhrase() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NearBlack)
            .vaultHaloBackground(),
        contentAlignment = Alignment.Center,
    ) {
        when (view) {
            UnlockView.Pin -> PinUnlockCard(
                viewModel = viewModel,
                state = state,
                recoveryAvailable = recoveryAvailable,
                onUnlocked = onUnlocked,
                onUseRecovery = {
                    viewModel.clearRecoveryError()
                    view = UnlockView.RecoveryEntry
                },
            )
            UnlockView.RecoveryEntry -> RecoveryEntryCard(
                viewModel = viewModel,
                state = state,
                onBack = {
                    viewModel.clearRecoveryError()
                    view = UnlockView.Pin
                },
                onRecovered = { view = UnlockView.SetNewPin },
            )
            UnlockView.SetNewPin -> SetNewPinCard(
                viewModel = viewModel,
                state = state,
                onDone = onUnlocked,
            )
        }
    }
}

/** Card chrome shared by every unlock sub-view: logo, brand, content. */
@Composable
private fun UnlockCard(
    shakeKey: Any?,
    content: @Composable () -> Unit,
) {
    val shakeOffset = remember { Animatable(0f) }
    LaunchedEffect(shakeKey) {
        if (shakeKey != null) {
            val sequence = listOf(-12f, 12f, -8f, 8f, -4f, 4f, 0f)
            for (target in sequence) {
                shakeOffset.animateTo(target, animationSpec = tween(durationMillis = 50))
            }
        }
    }
    Column(
        modifier = Modifier
            .width(380.dp)
            .graphicsLayer { translationX = shakeOffset.value }
            .shadow(20.dp, RoundedCornerShape(Radii.Xl))
            .background(Surface, RoundedCornerShape(Radii.Xl))
            .border(1.dp, Border1, RoundedCornerShape(Radii.Xl))
            .padding(horizontal = Spacing.Xxl, vertical = Spacing.Xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource("images/nextsh_logo.png"),
            contentDescription = stringResource(Res.string.vault_unlock_logo_alt),
            modifier = Modifier.size(96.dp),
        )
        Spacer(Modifier.height(Spacing.Md))
        BrandText(fontSize = 26.sp)
        Spacer(Modifier.height(Spacing.Sm))
        content()
    }
}

@Composable
private fun PinUnlockCard(
    viewModel: VaultViewModel,
    state: VaultUnlockState,
    recoveryAvailable: Boolean,
    onUnlocked: () -> Unit,
    onUseRecovery: () -> Unit,
) {
    var pinInput by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val submit = {
        if (pinInput.isNotEmpty()) {
            val pinChars = pinInput.toCharArray()
            pinInput = ""
            viewModel.attemptUnlock(pinChars, onUnlocked)
        }
    }

    UnlockCard(shakeKey = state.error) {
        Text(
            text = stringResource(Res.string.vault_unlock_subtitle),
            color = TextSecondary,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.Xl))

        CenteredPassphraseField(
            value = pinInput,
            onValueChange = {
                pinInput = it
                viewModel.clearError()
            },
            isError = state.error != null,
            onSubmit = { submit() },
            focusRequester = focusRequester,
        )

        val errorMessage = state.error
        if (errorMessage != null) {
            Spacer(Modifier.height(Spacing.Sm))
            Text(
                text = errorMessage,
                color = ErrorRed,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(Spacing.Lg))

        Button(
            onClick = submit,
            enabled = !state.isUnlocking && pinInput.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .pointerHoverIcon(PointerIcon.Hand),
            colors = ButtonDefaults.buttonColors(
                containerColor = Burgundy,
                contentColor = White,
                disabledContainerColor = Burgundy.copy(alpha = 0.4f),
                disabledContentColor = White.copy(alpha = 0.6f),
            ),
            shape = RoundedCornerShape(Radii.Md),
            contentPadding = PaddingValues(vertical = 10.dp),
        ) {
            Icon(Lucide.LockOpen, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(Spacing.Sm))
            Text(
                text = stringResource(if (state.isUnlocking) Res.string.vault_unlock_button_loading else Res.string.vault_unlock_button),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        Spacer(Modifier.height(Spacing.Lg))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(SuccessGreen, RoundedCornerShape(9999.dp)),
            )
            Spacer(Modifier.width(Spacing.Sm))
            Text(
                text = "PBKDF2 · AES-256-GCM",
                color = TextDisabled,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 11.sp,
            )
        }

        // The link only appears for vaults that actually have a recovery wrap.
        // Migrated vaults keep it hidden until the user opts in via Settings.
        if (recoveryAvailable) {
            Spacer(Modifier.height(Spacing.Sm))
            RecoveryPhraseLink(onClick = onUseRecovery)
        }
    }
}

/** Active ghost link "Utiliser la phrase de récupération". */
@Composable
private fun RecoveryPhraseLink(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    OutlinedButton(
        onClick = onClick,
        interactionSource = interactionSource,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Color.Transparent,
            contentColor = if (hovered) Gold else TextSecondary,
        ),
        border = BorderStroke(1.dp, Color.Transparent),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        modifier = Modifier
            .defaultMinSize(minWidth = 0.dp, minHeight = 0.dp)
            .pointerHoverIcon(PointerIcon.Hand),
    ) {
        Text(
            text = stringResource(Res.string.vault_unlock_recovery_link),
            color = if (hovered) Gold else TextSecondary,
            fontSize = 11.sp,
        )
    }
}

/**
 * Recovery-phrase entry view: a single multi-line field accepting the 12
 * space-separated words (one field is far more readable than 12 inputs and
 * supports paste). A live word counter guides the user; submit is enabled
 * once exactly 12 tokens are present. The actual validity (checksum + every
 * word in the BIP39 list) is checked by [VaultPinManager.unlockWithRecovery]:
 * a wrong phrase shows a single generic error.
 */
@Composable
private fun RecoveryEntryCard(
    viewModel: VaultViewModel,
    state: VaultUnlockState,
    onBack: () -> Unit,
    onRecovered: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val words = remember(input) {
        input.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    }
    val canSubmit = words.size == 12 && !state.isUnlocking

    val submit = submit@{
        if (!canSubmit) return@submit
        viewModel.attemptRecoveryUnlock(words.map { it.lowercase() }, onRecovered)
    }

    UnlockCard(shakeKey = state.recoveryError) {
        Text(
            text = stringResource(Res.string.recovery_unlock_title),
            color = TextPrimary,
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 17.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.Sm))
        Text(
            text = stringResource(Res.string.recovery_unlock_subtitle),
            color = TextSecondary,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.Lg))

        OutlinedTextField(
            value = input,
            onValueChange = {
                input = it
                viewModel.clearRecoveryError()
            },
            label = { Text(stringResource(Res.string.recovery_unlock_input_label)) },
            placeholder = {
                Text(
                    stringResource(Res.string.recovery_unlock_input_placeholder),
                    color = TextDisabled,
                    fontSize = 12.sp,
                )
            },
            isError = state.recoveryError != null,
            minLines = 3,
            maxLines = 4,
            textStyle = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 13.sp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            colors = unlockTextFieldColors(isError = state.recoveryError != null),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
        )

        Spacer(Modifier.height(Spacing.Xs))
        Text(
            text = stringResource(Res.string.recovery_unlock_word_count, words.size, 12),
            color = if (words.size == 12) SuccessGreen else TextDisabled,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 11.sp,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.End,
        )

        val errorMessage = state.recoveryError
        if (errorMessage != null) {
            Spacer(Modifier.height(Spacing.Sm))
            Text(
                text = errorMessage,
                color = ErrorRed,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(Spacing.Lg))

        Button(
            onClick = submit,
            enabled = canSubmit,
            modifier = Modifier
                .fillMaxWidth()
                .pointerHoverIcon(PointerIcon.Hand),
            colors = ButtonDefaults.buttonColors(
                containerColor = Burgundy,
                contentColor = White,
                disabledContainerColor = Burgundy.copy(alpha = 0.4f),
                disabledContentColor = White.copy(alpha = 0.6f),
            ),
            shape = RoundedCornerShape(Radii.Md),
            contentPadding = PaddingValues(vertical = 10.dp),
        ) {
            Icon(Lucide.LockOpen, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(Spacing.Sm))
            Text(
                text = stringResource(if (state.isUnlocking) Res.string.vault_unlock_button_loading else Res.string.recovery_unlock_submit),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        Spacer(Modifier.height(Spacing.Sm))
        BackLink(onClick = onBack)
    }
}

/**
 * Force a new PIN right after a recovery unlock. The DEK is already in memory,
 * so [VaultViewModel.setNewPin] simply re-wraps it under the new PIN. Two
 * confirmation fields, same shake/error UX as the rest of the screen.
 */
@Composable
private fun SetNewPinCard(
    viewModel: VaultViewModel,
    state: VaultUnlockState,
    onDone: () -> Unit,
) {
    var pinInput by remember { mutableStateOf("") }
    var confirmInput by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    val pinFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { pinFocus.requestFocus() }

    val submit = submit@{
        if (pinInput.isEmpty() || confirmInput.isEmpty() || state.isUnlocking) return@submit
        if (pinInput != confirmInput) {
            localError = "Les deux Passphrases ne correspondent pas"
            return@submit
        }
        localError = null
        val pinChars = pinInput.toCharArray()
        pinInput = ""
        confirmInput = ""
        viewModel.setNewPin(pinChars, onDone)
    }
    val canSubmit = pinInput.isNotEmpty() && confirmInput.isNotEmpty() && !state.isUnlocking
    val errorMessage = localError ?: state.recoveryError

    UnlockCard(shakeKey = errorMessage) {
        Text(
            text = stringResource(Res.string.recovery_set_pin_title),
            color = TextPrimary,
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 17.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.Sm))
        Text(
            text = stringResource(Res.string.recovery_set_pin_subtitle),
            color = TextSecondary,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.Xl))

        FieldLabel(stringResource(Res.string.recovery_set_pin_label))
        CenteredPassphraseField(
            value = pinInput,
            onValueChange = {
                pinInput = it
                localError = null
                viewModel.clearRecoveryError()
            },
            isError = errorMessage != null,
            onSubmit = { /* Tab to confirm */ },
            focusRequester = pinFocus,
            imeAction = ImeAction.Next,
        )
        Spacer(Modifier.height(Spacing.Md))

        FieldLabel(stringResource(Res.string.recovery_set_pin_confirm_label))
        CenteredPassphraseField(
            value = confirmInput,
            onValueChange = {
                confirmInput = it
                localError = null
                viewModel.clearRecoveryError()
            },
            isError = errorMessage != null,
            onSubmit = { submit() },
            focusRequester = remember { FocusRequester() },
            imeAction = ImeAction.Done,
        )

        if (errorMessage != null) {
            Spacer(Modifier.height(Spacing.Sm))
            Text(
                text = errorMessage,
                color = ErrorRed,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(Spacing.Lg))

        Button(
            onClick = submit,
            enabled = canSubmit,
            modifier = Modifier
                .fillMaxWidth()
                .pointerHoverIcon(PointerIcon.Hand),
            colors = ButtonDefaults.buttonColors(
                containerColor = Burgundy,
                contentColor = White,
                disabledContainerColor = Burgundy.copy(alpha = 0.4f),
                disabledContentColor = White.copy(alpha = 0.6f),
            ),
            shape = RoundedCornerShape(Radii.Md),
            contentPadding = PaddingValues(vertical = 10.dp),
        ) {
            Icon(Lucide.LockOpen, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(Spacing.Sm))
            Text(
                text = stringResource(Res.string.recovery_set_pin_button),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        color = TextSecondary,
        fontSize = 11.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, bottom = 4.dp),
    )
}

@Composable
private fun BackLink(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    OutlinedButton(
        onClick = onClick,
        interactionSource = interactionSource,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Color.Transparent,
            contentColor = if (hovered) TextPrimary else TextSecondary,
        ),
        border = BorderStroke(1.dp, Color.Transparent),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        modifier = Modifier
            .defaultMinSize(minWidth = 0.dp, minHeight = 0.dp)
            .pointerHoverIcon(PointerIcon.Hand),
    ) {
        Text(
            text = stringResource(Res.string.action_back),
            color = if (hovered) TextPrimary else TextSecondary,
            fontSize = 11.sp,
        )
    }
}

/**
 * Champ Passphrase à curseur centré.
 *
 * Implémentation : on rend manuellement une Row centrée de dots ("•")
 * (un par caractère saisi) + un curseur clignotant 2dp explicitement
 * positionné dans cette Row. Le `BasicTextField` réel est invisible
 * (alpha 0 + size 0) mais reçoit le focus et capture la saisie clavier
 * via `focusRequester`. Un `Modifier.clickable` sur la Box parente
 * propage le clic au focusRequester.
 *
 * Pourquoi ce détour : `OutlinedTextField` (et même `BasicTextField` +
 * `wrapContentWidth()`) ne mesurent pas leur contenu vide à 0 px sur
 * Compose Desktop : la min-width interne du CoreTextField pousse le
 * curseur à gauche. Rendre les dots + curseur dans une `Row(
 * horizontalArrangement = Arrangement.Center)` garantit le centrage
 * exact de l'affichage indépendamment du backing TextField.
 */
@Composable
internal fun CenteredPassphraseField(
    value: String,
    onValueChange: (String) -> Unit,
    isError: Boolean,
    onSubmit: () -> Unit,
    focusRequester: FocusRequester,
    imeAction: ImeAction = ImeAction.Done,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val borderColor = when {
        isError -> ErrorRed
        focused -> Gold
        else -> Border2
    }

    // Animation de blink du curseur custom, 1 cycle de 1 s (500 ms ON,
    // 500 ms OFF). Ne tourne que si le champ a le focus.
    val cursorAlpha = remember { Animatable(1f) }
    LaunchedEffect(focused) {
        if (focused) {
            while (true) {
                cursorAlpha.snapTo(1f)
                kotlinx.coroutines.delay(500)
                cursorAlpha.snapTo(0f)
                kotlinx.coroutines.delay(500)
            }
        } else {
            cursorAlpha.snapTo(0f)
        }
    }

    // `pointerInput` plutôt que `clickable` : `clickable` rend le node
    // focusable, ce qui injectait un focus stop supplémentaire dans la
    // chaîne de tabulation (le user devait Tab deux fois pour passer du
    // 1er au 2ᵉ champ Passphrase de l'écran Setup). `pointerInput` n'a
    // pas cette sémantique a11y et laisse le Tab aller directement
    // d'un BasicTextField au suivant.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(SurfaceVariant, RoundedCornerShape(Radii.Md))
            .border(BorderStroke(1.dp, borderColor), RoundedCornerShape(Radii.Md))
            .pointerInput(focusRequester) {
                detectTapGestures(onTap = { focusRequester.requestFocus() })
            },
        contentAlignment = Alignment.Center,
    ) {
        // Affichage centré : Row de dots (un par char) + curseur
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            repeat(value.length) {
                Text(
                    text = "•",
                    color = TextPrimary,
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 22.sp,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(28.dp)
                    .alpha(cursorAlpha.value)
                    .background(if (isError) ErrorRed else Gold),
            )
        }

        // BasicTextField caché (alpha 0 + taille 1dp) qui capture la saisie.
        // On garde une taille non-nulle pour que la mesure de Compose ne
        // saute pas le composable (`size(0.dp)` est parfois optimisé en
        // skip-render et casse le focus sur certaines versions).
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = imeAction,
            ),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }, onNext = { onSubmit() }),
            interactionSource = interactionSource,
            cursorBrush = SolidColor(Color.Transparent),
            textStyle = TextStyle(color = Color.Transparent, fontSize = 1.sp),
            modifier = Modifier
                .alpha(0f)
                .size(1.dp)
                .focusRequester(focusRequester),
        )
    }
}

/**
 * Décor de fond du Vault, fade Burgundy → NearBlack avec dithering SKSL.
 *
 * On revient au décor "halo doux en haut de page" demandé par le user.
 * Pour éviter le color banding sRGB 8-bit (qui apparaît dès que la
 * variation d'alpha par pixel tombe sous 1/256), on utilise un shader
 * SKSL Skia custom qui :
 *
 *  1. Calcule l'alpha à chaque fragment selon une décroissance non
 *     linéaire `pow(1 - t, power)` sur toute la hauteur. Concentre la
 *     chaleur en haut, étire un long tail invisible jusqu'en bas.
 *  2. Ajoute un **bruit pseudo-aléatoire ±½ unité de quantification**
 *     (Interleaved Gradient Noise, Jimenez 2014) par pixel sur l'alpha
 *     calculée. Le bruit casse les plateaux de pixels identiques
 *     produits par la quantification 8-bit → l'œil reçoit un fade
 *     perçu comme parfaitement lisse.
 *
 * Le shader runtime via `RuntimeEffect.makeForShader` se compile une
 * fois au chargement de la classe, le brush en `ShaderBrush` est
 * réutilisé sur chaque frame.
 *
 * Trim Gold 1 px conservé en bordure haute.
 */
internal fun Modifier.vaultHaloBackground(): Modifier = this.drawBehind {
    drawRect(brush = ditheredBurgundyFadeBrush)
    drawRect(
        color = Gold.copy(alpha = 0.25f),
        topLeft = Offset.Zero,
        size = androidx.compose.ui.geometry.Size(size.width, 1f),
    )
}

/**
 * SKSL : on **blend en interne** Burgundy sur NearBlack et on dither le
 * RGB final fully-opaque, plutôt que de sortir un alpha < 1 que le
 * compositor de Compose re-quantifierait par-dessus le `.background()`
 * NearBlack (effaçant le bruit du dither). Le shader sort directement
 * la couleur RGB attendue à l'écran, dither inclus.
 *
 * Ampleur du bruit : ±1 unité de quantification (`(n - 0.5) * 2 / 255`).
 * ±0.5 unité (essai précédent) pouvait être trop faible pour casser le
 * banding sur certains panels : ±1 garantit un arrondi différent entre
 * pixels adjacents.
 */
private val DITHERED_VERTICAL_FADE_SKSL = """
    uniform float2 uSize;
    uniform half3 uBg;
    uniform half3 uFg;
    uniform float uTopAlpha;
    uniform float uPower;

    half4 main(float2 coord) {
        float t = clamp(coord.y / uSize.y, 0.0, 1.0);
        float alpha = uTopAlpha * pow(1.0 - t, uPower);

        // Blend manuel en-shader → output fully-opaque, le dither
        // suivant ne sera donc pas re-quantifié par le compositor.
        half3 rgb = mix(uBg, uFg, half(alpha));

        // Interleaved Gradient Noise (Jimenez 2014). Recentré sur
        // [-0.5, +0.5] puis amplitude ±1 unité de quantification 8-bit.
        float n = fract(52.9829189 * fract(0.06711056 * coord.x + 0.00583715 * coord.y));
        rgb += half3((n - 0.5) * (2.0 / 255.0));

        return half4(rgb, 1.0);
    }
""".trimIndent()

private val ditherEffect: org.jetbrains.skia.RuntimeEffect =
    org.jetbrains.skia.RuntimeEffect.makeForShader(DITHERED_VERTICAL_FADE_SKSL)

private class DitheredVerticalFadeBrush(
    private val bgR: Float, private val bgG: Float, private val bgB: Float,
    private val fgR: Float, private val fgG: Float, private val fgB: Float,
    private val topAlpha: Float,
    private val power: Float,
) : androidx.compose.ui.graphics.ShaderBrush() {
    override fun createShader(size: androidx.compose.ui.geometry.Size): androidx.compose.ui.graphics.Shader {
        return org.jetbrains.skia.RuntimeShaderBuilder(ditherEffect).apply {
            uniform("uSize", size.width, size.height)
            uniform("uBg", bgR, bgG, bgB)
            uniform("uFg", fgR, fgG, fgB)
            uniform("uTopAlpha", topAlpha)
            uniform("uPower", power)
        }.makeShader()
    }
}

// NearBlack = #0F0F0F = (15,15,15) ; Burgundy = #8B1A2F = (139,26,47).
// Alpha 0.18 en haut, falloff pow(1-t)^2.5 → chaleur sur le tiers haut.
private val ditheredBurgundyFadeBrush = DitheredVerticalFadeBrush(
    bgR = 0.0588f, bgG = 0.0588f, bgB = 0.0588f,
    fgR = 0.5451f, fgG = 0.1020f, fgB = 0.1843f,
    topAlpha = 0.18f,
    power = 2.5f,
)

@Composable
internal fun unlockTextFieldColors(isError: Boolean) = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = if (isError) ErrorRed else Gold,
    unfocusedBorderColor = if (isError) ErrorRed else Border2,
    focusedLabelColor = Gold,
    unfocusedLabelColor = TextSecondary,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    cursorColor = Gold,
    focusedContainerColor = SurfaceVariant,
    unfocusedContainerColor = SurfaceVariant,
    errorContainerColor = SurfaceVariant,
    errorBorderColor = ErrorRed,
    errorTextColor = TextPrimary,
)
