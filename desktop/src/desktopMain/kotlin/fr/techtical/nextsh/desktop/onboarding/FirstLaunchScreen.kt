// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ArrowRight
import com.composables.icons.lucide.KeyRound
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Wifi
import fr.techtical.nextsh.desktop.components.BrandText
import fr.techtical.nextsh.desktop.theme.Border1
import fr.techtical.nextsh.desktop.theme.Border2
import fr.techtical.nextsh.desktop.theme.Burgundy
import fr.techtical.nextsh.desktop.theme.ErrorRed
import fr.techtical.nextsh.desktop.theme.Gold
import fr.techtical.nextsh.desktop.theme.JetBrainsMonoFamily
import fr.techtical.nextsh.desktop.theme.NearBlack
import fr.techtical.nextsh.desktop.theme.Radii
import fr.techtical.nextsh.desktop.theme.SpaceGroteskFamily
import fr.techtical.nextsh.desktop.theme.Spacing
import fr.techtical.nextsh.desktop.theme.SuccessGreen
import fr.techtical.nextsh.desktop.theme.Surface
import fr.techtical.nextsh.desktop.theme.TextDisabled
import fr.techtical.nextsh.desktop.theme.TextPrimary
import fr.techtical.nextsh.desktop.theme.TextSecondary
import fr.techtical.nextsh.desktop.theme.White
import fr.techtical.nextsh.desktop.vault.CenteredPassphraseField
import fr.techtical.nextsh.desktop.vault.RecoveryPhraseDisplay
import fr.techtical.nextsh.desktop.vault.VaultSetupViewModel
import fr.techtical.nextsh.desktop.vault.vaultHaloBackground
import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.firstlaunch_step_count
import fr.techtical.nextsh.desktop.generated.resources.firstlaunch_sync_configure
import fr.techtical.nextsh.desktop.generated.resources.firstlaunch_sync_explainer
import fr.techtical.nextsh.desktop.generated.resources.firstlaunch_sync_later_hint
import fr.techtical.nextsh.desktop.generated.resources.firstlaunch_sync_skip
import fr.techtical.nextsh.desktop.generated.resources.firstlaunch_sync_subtitle
import fr.techtical.nextsh.desktop.generated.resources.firstlaunch_sync_title
import fr.techtical.nextsh.desktop.generated.resources.firstlaunch_vault_explainer
import fr.techtical.nextsh.desktop.generated.resources.firstlaunch_vault_title
import fr.techtical.nextsh.desktop.generated.resources.firstlaunch_welcome_button
import fr.techtical.nextsh.desktop.generated.resources.firstlaunch_welcome_tagline
import fr.techtical.nextsh.desktop.generated.resources.firstlaunch_welcome_title
import fr.techtical.nextsh.desktop.generated.resources.vault_setup_button
import fr.techtical.nextsh.desktop.generated.resources.vault_setup_button_loading
import fr.techtical.nextsh.desktop.generated.resources.vault_setup_logo_alt
import fr.techtical.nextsh.desktop.generated.resources.vault_setup_pin_confirm_label
import fr.techtical.nextsh.desktop.generated.resources.vault_setup_pin_label
import org.jetbrains.compose.resources.stringResource

/**
 * Fresh-install onboarding stepper, replaces the legacy `VaultSetupScreen`.
 *
 * Displayed once when `vaultPinManager.isSetup() == false` (no vault.meta on
 * disk). Three steps:
 *   1. Welcome: brand + tagline + Commencer
 *   2. Create vault: passphrase + confirm, persisted via VaultSetupViewModel
 *   3. Optional sync: Configure (→ EnrollmentScreen) / Skip (→ HostList)
 *
 * Once the user reaches step 3 and chooses one of the two options, the flow
 * never reappears unless the vault is wiped (Settings → Wipe vault).
 */
@Composable
fun FirstLaunchScreen(
    onCompleted: () -> Unit,
    onConfigureSync: () -> Unit,
) {
    var step by remember { mutableStateOf(FirstLaunchStep.Welcome) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NearBlack)
            .vaultHaloBackground(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(420.dp)
                .shadow(20.dp, RoundedCornerShape(Radii.Xl))
                .background(Surface, RoundedCornerShape(Radii.Xl))
                .border(1.dp, Border1, RoundedCornerShape(Radii.Xl))
                .padding(horizontal = Spacing.Xxl, vertical = Spacing.Xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StepperHeader(currentStep = step.ordinal + 1, totalSteps = FirstLaunchStep.entries.size)
            Spacer(Modifier.height(Spacing.Xl))

            // VaultSetupViewModel is hoisted here (not inside CreateVaultPane)
            // so the recovery words captured on creation survive the step
            // transition into RecoveryPhrase. The same VM instance backs both
            // panes.
            val setupViewModel = remember { VaultSetupViewModel() }

            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    (fadeIn(animationSpec = tween(220)) togetherWith
                        fadeOut(animationSpec = tween(120)))
                },
                label = "firstlaunch_step",
            ) { current ->
                when (current) {
                    FirstLaunchStep.Welcome -> WelcomePane(
                        onNext = { step = FirstLaunchStep.CreateVault },
                    )
                    FirstLaunchStep.CreateVault -> CreateVaultPane(
                        viewModel = setupViewModel,
                        onCreated = { step = FirstLaunchStep.RecoveryPhrase },
                    )
                    FirstLaunchStep.RecoveryPhrase -> RecoveryPhrasePane(
                        viewModel = setupViewModel,
                        onAcknowledged = { step = FirstLaunchStep.SyncOptional },
                    )
                    FirstLaunchStep.SyncOptional -> SyncOptionalPane(
                        onConfigureSync = onConfigureSync,
                        onSkip = onCompleted,
                    )
                }
            }
        }
    }
}

private enum class FirstLaunchStep { Welcome, CreateVault, RecoveryPhrase, SyncOptional }

/** Stepper dots header: "Étape N sur 3" + 3 horizontal dots. */
@Composable
private fun StepperHeader(currentStep: Int, totalSteps: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            repeat(totalSteps) { i ->
                val isActive = (i + 1) == currentStep
                val isCompleted = (i + 1) < currentStep
                Box(
                    modifier = Modifier
                        .size(if (isActive) 8.dp else 6.dp)
                        .background(
                            color = when {
                                isActive -> Burgundy
                                isCompleted -> Gold
                                else -> Border2
                            },
                            shape = RoundedCornerShape(9999.dp),
                        ),
                )
                if (i < totalSteps - 1) Spacer(Modifier.width(8.dp))
            }
        }
        Spacer(Modifier.height(Spacing.Sm))
        Text(
            text = stringResource(Res.string.firstlaunch_step_count, currentStep, totalSteps),
            color = TextDisabled,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 11.sp,
        )
    }
}

/** Step 1, Welcome: logo, brand text, tagline, "Commencer" button. */
@Composable
private fun WelcomePane(onNext: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Image(
            painter = painterResource("images/nextsh_logo.png"),
            contentDescription = stringResource(Res.string.vault_setup_logo_alt),
            modifier = Modifier.size(96.dp),
        )
        Spacer(Modifier.height(Spacing.Md))
        BrandText(fontSize = 28.sp)
        Spacer(Modifier.height(Spacing.Md))
        Text(
            text = stringResource(Res.string.firstlaunch_welcome_title),
            color = TextPrimary,
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 17.sp,
        )
        Spacer(Modifier.height(Spacing.Xs))
        Text(
            text = stringResource(Res.string.firstlaunch_welcome_tagline),
            color = TextSecondary,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.Xl))
        Button(
            onClick = onNext,
            modifier = Modifier
                .fillMaxWidth()
                .pointerHoverIcon(PointerIcon.Hand),
            colors = ButtonDefaults.buttonColors(
                containerColor = Burgundy,
                contentColor = White,
            ),
            shape = RoundedCornerShape(Radii.Md),
            contentPadding = PaddingValues(vertical = 10.dp),
        ) {
            Text(
                text = stringResource(Res.string.firstlaunch_welcome_button),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.width(Spacing.Sm))
            Icon(Lucide.ArrowRight, contentDescription = null, modifier = Modifier.size(14.dp))
        }
    }
}

/**
 * Step 2, Create vault: passphrase + confirm fields, "Créer le vault"
 * button. Reuses `VaultSetupViewModel` (which calls
 * `VaultPinManager.setupNewVault()`) and `CenteredPassphraseField` from the
 * `vault` package, same UX as the legacy `VaultSetupScreen` but embedded
 * in the stepper.
 */
@Composable
private fun CreateVaultPane(
    viewModel: VaultSetupViewModel,
    onCreated: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    var pinInput by remember { mutableStateOf("") }
    var confirmInput by remember { mutableStateOf("") }
    val pinFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) { pinFocus.requestFocus() }

    val shakeOffset = remember { Animatable(0f) }
    LaunchedEffect(state.error) {
        if (state.error != null) {
            val sequence = listOf(-12f, 12f, -8f, 8f, -4f, 4f, 0f)
            for (target in sequence) {
                shakeOffset.animateTo(target, animationSpec = tween(durationMillis = 50))
            }
        }
    }

    val submit = submit@{
        if (pinInput.isEmpty() || confirmInput.isEmpty() || state.isSubmitting) return@submit
        val pinChars = pinInput.toCharArray()
        val confirmChars = confirmInput.toCharArray()
        pinInput = ""
        confirmInput = ""
        viewModel.submit(pinChars, confirmChars, onCreated)
    }

    val canSubmit = pinInput.isNotEmpty() && confirmInput.isNotEmpty() && !state.isSubmitting

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { translationX = shakeOffset.value },
    ) {
        Icon(
            Lucide.KeyRound,
            contentDescription = null,
            tint = Burgundy,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(Spacing.Md))
        Text(
            text = stringResource(Res.string.firstlaunch_vault_title),
            color = TextPrimary,
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 17.sp,
        )
        Spacer(Modifier.height(Spacing.Sm))
        Text(
            text = stringResource(Res.string.firstlaunch_vault_explainer),
            color = TextSecondary,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.Xl))

        FieldLabel(stringResource(Res.string.vault_setup_pin_label))
        CenteredPassphraseField(
            value = pinInput,
            onValueChange = {
                pinInput = it
                viewModel.clearError()
            },
            isError = state.error != null,
            onSubmit = { /* Tab to confirm field via IME Next */ },
            focusRequester = pinFocus,
            imeAction = ImeAction.Next,
        )
        Spacer(Modifier.height(Spacing.Md))

        FieldLabel(stringResource(Res.string.vault_setup_pin_confirm_label))
        CenteredPassphraseField(
            value = confirmInput,
            onValueChange = {
                confirmInput = it
                viewModel.clearError()
            },
            isError = state.error != null,
            onSubmit = { submit() },
            focusRequester = remember { FocusRequester() },
            imeAction = ImeAction.Done,
        )

        val err = state.error
        if (err != null) {
            Spacer(Modifier.height(Spacing.Sm))
            Text(
                text = err,
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
            Icon(Lucide.KeyRound, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(Spacing.Sm))
            Text(
                text = stringResource(
                    if (state.isSubmitting) Res.string.vault_setup_button_loading
                    else Res.string.vault_setup_button
                ),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        Spacer(Modifier.height(Spacing.Md))
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
    }
}

/**
 * Step 3: One-time display of the recovery phrase. Reads the words captured
 * on creation from [VaultSetupState.recoveryWords]. The shared
 * [RecoveryPhraseDisplay] renders the numbered grid, the "only way to recover"
 * warning, and the mandatory acknowledgement gate. We only advance once the
 * user acknowledges. If the words are somehow absent (creation produced none),
 * we skip straight ahead rather than block the flow.
 */
@Composable
private fun RecoveryPhrasePane(
    viewModel: VaultSetupViewModel,
    onAcknowledged: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val words = state.recoveryWords

    LaunchedEffect(words) {
        if (words == null) onAcknowledged()
    }

    if (words != null) {
        RecoveryPhraseDisplay(
            words = words,
            onAcknowledged = {
                // Drop the plaintext mnemonic from VM state once acknowledged,
                // then advance. (One-time-display contract.)
                viewModel.clearRecoveryWords()
                onAcknowledged()
            },
        )
    }
}

/**
 * Step 4: Optional LAN sync, explainer + 2 buttons (Configure / Skip).
 * Configure → EnrollmentScreen with `fromFirstLaunch = true` so the back
 * button later routes to HostList instead of Settings.
 */
@Composable
private fun SyncOptionalPane(
    onConfigureSync: () -> Unit,
    onSkip: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            Lucide.Wifi,
            contentDescription = null,
            tint = Gold,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(Spacing.Md))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(Res.string.firstlaunch_sync_title),
                color = TextPrimary,
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
            )
            Spacer(Modifier.width(Spacing.Sm))
            Box(
                modifier = Modifier
                    .background(Border1, RoundedCornerShape(Radii.Sm))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = stringResource(Res.string.firstlaunch_sync_subtitle),
                    color = TextSecondary,
                    fontSize = 10.sp,
                )
            }
        }
        Spacer(Modifier.height(Spacing.Sm))
        Text(
            text = stringResource(Res.string.firstlaunch_sync_explainer),
            color = TextSecondary,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.Xl))

        Button(
            onClick = onConfigureSync,
            modifier = Modifier
                .fillMaxWidth()
                .pointerHoverIcon(PointerIcon.Hand),
            colors = ButtonDefaults.buttonColors(
                containerColor = Burgundy,
                contentColor = White,
            ),
            shape = RoundedCornerShape(Radii.Md),
            contentPadding = PaddingValues(vertical = 10.dp),
        ) {
            Icon(Lucide.Wifi, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(Spacing.Sm))
            Text(
                text = stringResource(Res.string.firstlaunch_sync_configure),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.height(Spacing.Sm))
        OutlinedButton(
            onClick = onSkip,
            modifier = Modifier
                .fillMaxWidth()
                .pointerHoverIcon(PointerIcon.Hand),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = TextSecondary,
            ),
            border = BorderStroke(1.dp, Border2),
            shape = RoundedCornerShape(Radii.Md),
            contentPadding = PaddingValues(vertical = 10.dp),
        ) {
            Text(
                text = stringResource(Res.string.firstlaunch_sync_skip),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.height(Spacing.Md))
        Text(
            text = stringResource(Res.string.firstlaunch_sync_later_hint),
            color = TextDisabled,
            fontSize = 10.sp,
            textAlign = TextAlign.Center,
        )
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
