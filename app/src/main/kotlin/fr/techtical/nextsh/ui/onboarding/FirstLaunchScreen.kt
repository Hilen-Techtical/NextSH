// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.onboarding

import fr.techtical.nextsh.core.ScreenCapturePolicy
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import com.composables.icons.lucide.ArrowRight
import com.composables.icons.lucide.Fingerprint
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ShieldCheck
import com.composables.icons.lucide.TriangleAlert
import com.composables.icons.lucide.Wifi
import fr.techtical.nextsh.R
import fr.techtical.nextsh.ui.components.BrandText
import fr.techtical.nextsh.ui.theme.Border1
import fr.techtical.nextsh.ui.theme.Border2
import fr.techtical.nextsh.ui.theme.Burgundy
import fr.techtical.nextsh.ui.theme.ErrorRed
import fr.techtical.nextsh.ui.theme.Gold
import fr.techtical.nextsh.ui.theme.GoldLight
import fr.techtical.nextsh.ui.theme.JetBrainsMonoFamily
import fr.techtical.nextsh.ui.theme.NearBlack
import fr.techtical.nextsh.ui.theme.Radii
import fr.techtical.nextsh.ui.theme.SpaceGroteskFamily
import fr.techtical.nextsh.ui.theme.Spacing
import fr.techtical.nextsh.ui.theme.SuccessGreen
import fr.techtical.nextsh.ui.theme.Surface
import fr.techtical.nextsh.ui.theme.SurfaceVariant
import fr.techtical.nextsh.ui.theme.TextDisabled
import fr.techtical.nextsh.ui.theme.TextPrimary
import fr.techtical.nextsh.ui.theme.TextSecondary
import fr.techtical.nextsh.ui.theme.White

/**
 * FirstLaunchScreen: Android first-launch onboarding stepper.
 *
 * Mirrors the Desktop onboarding flow but adapts to Android's hardware-backed
 * vault model: there is no PIN and no recovery phrase. The three steps are:
 *   1. Welcome: brand + tagline + "Commencer"
 *   2. Secure vault: hardware (TEE/StrongBox) explainer + biometric unlock,
 *      driving the existing [OnboardingViewModel.createVault] (which reuses the
 *      same BiometricHelper/Keystore path as VaultUnlockScreen: no crypto is
 *      duplicated). This is the credential step; FLAG_SECURE is enforced on it.
 *   3. Optional LAN sync: explainer + "Configure now" / "Skip later".
 *
 * Gated by [fr.techtical.nextsh.data.preferences.SettingsDataStore]'s
 * `onboarding_completed` flag: once finished, the flow never reappears.
 */
@Composable
fun FirstLaunchScreen(
    onCompleted: () -> Unit,
    onConfigureSync: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    // Survive configuration changes (rotation): persist the enum by ordinal.
    var step by rememberSaveable(
        stateSaver = androidx.compose.runtime.saveable.Saver(
            save = { it.ordinal },
            restore = { FirstLaunchStep.entries[it] },
        ),
    ) { mutableStateOf(FirstLaunchStep.Welcome) }
    val state by viewModel.state.collectAsState()

    // Advance to the optional-sync step once the hardware vault is ready.
    LaunchedEffect(state.vaultReady) {
        if (state.vaultReady && step == FirstLaunchStep.CreateVault) {
            step = FirstLaunchStep.SyncOptional
        }
    }

    // System Back: step backwards instead of exiting the app from a non-first
    // step. On Welcome, fall through (enabled = false) so Back exits normally.
    //   - From SyncOptional with the vault already ready, going back to
    //     CreateVault would immediately auto-advance again (stuck), so skip it
    //     and return straight to Welcome.
    BackHandler(enabled = step != FirstLaunchStep.Welcome) {
        step = when (step) {
            FirstLaunchStep.SyncOptional ->
                if (state.vaultReady) FirstLaunchStep.Welcome else FirstLaunchStep.CreateVault
            FirstLaunchStep.CreateVault -> FirstLaunchStep.Welcome
            FirstLaunchStep.Welcome -> FirstLaunchStep.Welcome
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NearBlack),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 420.dp)
                .padding(horizontal = Spacing.Xl)
                .background(Surface, RoundedCornerShape(Radii.Xxl))
                .border(1.dp, Border1, RoundedCornerShape(Radii.Xxl))
                .padding(horizontal = Spacing.Xl, vertical = Spacing.Xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StepperHeader(currentStep = step.ordinal + 1, totalSteps = 3)
            Spacer(Modifier.height(Spacing.Xl))

            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    fadeIn(animationSpec = tween(220)) togetherWith
                        fadeOut(animationSpec = tween(120))
                },
                label = "firstlaunch_step",
            ) { current ->
                when (current) {
                    FirstLaunchStep.Welcome -> WelcomePane(
                        // If the vault is already ready (e.g. the user enabled it
                        // then stepped Back to Welcome), skip the CreateVault step
                        // so we don't re-trigger the biometric prompt: its
                        // auto-advance LaunchedEffect won't re-fire without a
                        // vaultReady change, which would otherwise get stuck.
                        onNext = {
                            step = if (state.vaultReady) FirstLaunchStep.SyncOptional
                            else FirstLaunchStep.CreateVault
                        },
                    )
                    FirstLaunchStep.CreateVault -> CreateVaultPane(
                        state = state,
                        onCreateVault = viewModel::createVault,
                        onDismissError = viewModel::dismissError,
                    )
                    FirstLaunchStep.SyncOptional -> SyncOptionalPane(
                        onConfigureSync = {
                            // Persist completion before leaving the flow so that
                            // returning from enrollment lands on the host list,
                            // not back into onboarding.
                            viewModel.completeOnboarding(onConfigureSync)
                        },
                        onSkip = { viewModel.completeOnboarding(onCompleted) },
                    )
                }
            }
        }
    }
}

private enum class FirstLaunchStep { Welcome, CreateVault, SyncOptional }

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
                        .size(if (isActive) 9.dp else 7.dp)
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
            text = stringResource(R.string.firstlaunch_step_count, currentStep, totalSteps),
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
            painter = painterResource(id = R.drawable.nextsh_logo),
            contentDescription = stringResource(R.string.label_nextsh_logo),
            modifier = Modifier.size(112.dp),
        )
        Spacer(Modifier.height(Spacing.Md))
        BrandText(fontSize = 28.sp)
        Spacer(Modifier.height(Spacing.Md))
        Text(
            text = stringResource(R.string.firstlaunch_welcome_title),
            color = TextPrimary,
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.Xs))
        Text(
            text = stringResource(R.string.firstlaunch_welcome_tagline),
            color = TextSecondary,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.Xl))
        PrimaryButton(
            label = stringResource(R.string.firstlaunch_welcome_button),
            icon = Lucide.ArrowRight,
            onClick = onNext,
        )
    }
}

/**
 * Step 2, Secure vault: hardware-backed vault explainer + biometric unlock.
 *
 * On Android the vault key lives in the Keystore (TEE/StrongBox); there is no
 * PIN to type. Tapping "Activer le vault" launches the system BiometricPrompt
 * via [OnboardingViewModel.createVault]. This is the credential-entry step, so
 * FLAG_SECURE is enforced for its whole lifetime (matching SftpPreviewOverlay).
 */
@Composable
private fun CreateVaultPane(
    state: OnboardingViewModel.OnboardingState,
    onCreateVault: (FragmentActivity) -> Unit,
    onDismissError: () -> Unit,
) {
    val activity = LocalContext.current as? FragmentActivity

    // Enforce FLAG_SECURE for the credential step. The Activity window already
    // sets it globally, but we re-assert it here (and leave it set on dispose,
    // since the app keeps FLAG_SECURE app-wide) to make the guarantee explicit
    // for the most sensitive screen, same intent as SftpPreviewOverlay.
    DisposableEffect(activity) {
        activity?.window?.let(ScreenCapturePolicy::protect)
        onDispose { /* keep FLAG_SECURE, set globally by MainActivity */ }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(Burgundy.copy(alpha = 0.16f), RoundedCornerShape(Radii.Xl)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Lucide.ShieldCheck,
                contentDescription = null,
                tint = GoldLight,
                modifier = Modifier.size(26.dp),
            )
        }
        Spacer(Modifier.height(Spacing.Md))
        Text(
            text = stringResource(R.string.firstlaunch_vault_title),
            color = TextPrimary,
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.Sm))
        Text(
            text = stringResource(R.string.firstlaunch_vault_explainer),
            color = TextSecondary,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.Xl))

        if (state.biometricAvailable && activity != null) {
            PrimaryButton(
                label = stringResource(R.string.firstlaunch_vault_button),
                icon = Lucide.Fingerprint,
                enabled = !state.isAuthenticating,
                loading = state.isAuthenticating,
                onClick = { onCreateVault(activity) },
            )
        } else {
            FallbackBlock(text = stringResource(R.string.label_no_device_lock))
        }

        // Prefer the localizable res-id (known vault exceptions); fall back to
        // the OS-localized BiometricPrompt string. Tap the banner to dismiss a
        // transient auth error.
        val errorText = state.errorRes?.let { stringResource(it) } ?: state.errorMessage
        errorText?.let { error ->
            Spacer(Modifier.height(Spacing.Md))
            ErrorBanner(text = error, onDismiss = onDismissError)
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
                text = stringResource(R.string.firstlaunch_vault_security_badge),
                color = TextDisabled,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 11.sp,
            )
        }
    }
}

/**
 * Step 3, Optional LAN sync: explainer + "Configure now" / "Skip later".
 * Mirrors the Desktop step intent: sync is never forced.
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
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(Gold.copy(alpha = 0.10f), RoundedCornerShape(Radii.Xl)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Lucide.Wifi,
                contentDescription = null,
                tint = Gold,
                modifier = Modifier.size(26.dp),
            )
        }
        Spacer(Modifier.height(Spacing.Md))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.firstlaunch_sync_title),
                color = TextPrimary,
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
            )
            Spacer(Modifier.width(Spacing.Sm))
            Box(
                modifier = Modifier
                    .background(Border1, RoundedCornerShape(Radii.Sm))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = stringResource(R.string.firstlaunch_sync_subtitle),
                    color = TextSecondary,
                    fontSize = 10.sp,
                )
            }
        }
        Spacer(Modifier.height(Spacing.Sm))
        Text(
            text = stringResource(R.string.firstlaunch_sync_explainer),
            color = TextSecondary,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.Xl))

        PrimaryButton(
            label = stringResource(R.string.firstlaunch_sync_configure),
            icon = Lucide.Wifi,
            onClick = onConfigureSync,
        )
        Spacer(Modifier.height(Spacing.Sm))
        OutlinedButton(
            onClick = onSkip,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
            border = BorderStroke(1.dp, Border2),
            shape = RoundedCornerShape(Radii.Md),
            contentPadding = PaddingValues(vertical = Spacing.Md),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Text(
                text = stringResource(R.string.firstlaunch_sync_skip),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.height(Spacing.Md))
        Text(
            text = stringResource(R.string.firstlaunch_sync_later_hint),
            color = TextDisabled,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
        )
    }
}

// ── Shared building blocks ───────────────────────────────────────────────────

@Composable
private fun PrimaryButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean = true,
    loading: Boolean = false,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = Burgundy,
            contentColor = White,
            disabledContainerColor = Burgundy.copy(alpha = 0.4f),
            disabledContentColor = White.copy(alpha = 0.6f),
        ),
        shape = RoundedCornerShape(Radii.Md),
        contentPadding = PaddingValues(vertical = Spacing.Md),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = White,
                strokeWidth = 2.dp,
            )
        } else {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(Spacing.Sm))
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun FallbackBlock(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(Radii.Md))
            .border(BorderStroke(1.dp, Border1), RoundedCornerShape(Radii.Md))
            .padding(horizontal = Spacing.Lg, vertical = Spacing.Md),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = TextSecondary,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ErrorBanner(text: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onDismiss)
            .background(ErrorRed.copy(alpha = 0.08f), RoundedCornerShape(Radii.Md))
            .border(1.dp, ErrorRed.copy(alpha = 0.30f), RoundedCornerShape(Radii.Md))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Lucide.TriangleAlert,
            contentDescription = null,
            tint = ErrorRed,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(Spacing.Sm))
        Text(
            text = text,
            color = ErrorRed,
            fontSize = 12.sp,
        )
    }
}
