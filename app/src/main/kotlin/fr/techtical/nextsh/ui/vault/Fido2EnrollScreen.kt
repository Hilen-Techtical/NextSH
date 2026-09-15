// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.vault

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.composables.icons.lucide.KeyRound
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Nfc
import com.composables.icons.lucide.Usb
import fr.techtical.nextsh.R
import fr.techtical.nextsh.ui.theme.Border1
import fr.techtical.nextsh.ui.theme.Border2
import fr.techtical.nextsh.ui.theme.Burgundy
import fr.techtical.nextsh.ui.theme.ErrorRed
import fr.techtical.nextsh.ui.theme.Gold
import fr.techtical.nextsh.ui.theme.GoldLight
import fr.techtical.nextsh.ui.theme.GoldMuted
import fr.techtical.nextsh.ui.theme.JetBrainsMonoFamily
import fr.techtical.nextsh.ui.theme.NearBlack
import fr.techtical.nextsh.ui.theme.Radii
import fr.techtical.nextsh.ui.theme.SpaceGroteskFamily
import fr.techtical.nextsh.ui.theme.Spacing
import fr.techtical.nextsh.ui.theme.SuccessGreen
import fr.techtical.nextsh.ui.theme.SurfaceVariant
import fr.techtical.nextsh.ui.theme.TextPrimary
import fr.techtical.nextsh.ui.theme.TextSecondary
import fr.techtical.nextsh.ui.theme.WarningAmber
import fr.techtical.nextsh.ui.theme.White

/**
 * Écran d'enrôlement FIDO2 : génère une clé sk-ssh-ed25519 via YubiKey (USB-C ou NFC).
 *
 * Phases UI gérées par [Fido2EnrollViewModel.EnrollState] :
 *   Idle          → formulaire label + rpId + PIN optionnel + bouton
 *   WaitingForKey → animation pulse "Branchez ou approchez" (USB + NFC)
 *   Success       → confirmation + retour
 *   Error         → message rouge + réessayer
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Fido2EnrollScreen(
    onBack: () -> Unit,
    viewModel: Fido2EnrollViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    // Démarrer/arrêter le listening YubiKit avec le cycle de vie de l'écran
    DisposableEffect(Unit) {
        val activity = context.findActivity()
        if (activity != null) {
            viewModel.startListening(activity)
        }
        onDispose {
            viewModel.stopListening()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.title_fido2_enroll),
                        color = TextPrimary,
                        fontFamily = SpaceGroteskFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 20.sp,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                            tint = TextPrimary,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = NearBlack),
            )
        },
        containerColor = NearBlack,
    ) { padding ->
        AnimatedContent(
            targetState = state,
            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
            label = "Fido2EnrollContent",
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // Le clavier ne redimensionne plus la fenetre depuis le passage
                // au bord a bord : sans cela il recouvre les champs de nom et
                // de PIN de la cle.
                .imePadding(),
        ) { currentState ->
            when (currentState) {
                is Fido2EnrollViewModel.EnrollState.Idle ->
                    IdleContent(viewModel = viewModel)

                is Fido2EnrollViewModel.EnrollState.WaitingForKey ->
                    WaitingForKeyContent(onCancel = onBack)

                is Fido2EnrollViewModel.EnrollState.Success ->
                    SuccessContent(
                        keyLabel = currentState.savedKey.label,
                        onDone = onBack,
                    )

                is Fido2EnrollViewModel.EnrollState.Error ->
                    ErrorContent(
                        message = currentState.message,
                        canRetry = currentState.canRetry,
                        isPinRequired = currentState.isPinRequired,
                        onRetry = { viewModel.reset() },
                        onBack = onBack,
                    )
            }
        }
    }
}

// ── Idle : formulaire ─────────────────────────────────────────────────────────

@Composable
private fun IdleContent(viewModel: Fido2EnrollViewModel) {
    var labelText by remember { mutableStateOf(viewModel.label) }
    var rpIdText by remember { mutableStateOf(viewModel.rpId) }
    var pinText by remember { mutableStateOf("") }
    var showAdvanced by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.Lg, vertical = Spacing.Md),
        verticalArrangement = Arrangement.spacedBy(Spacing.Md),
    ) {
        // Header illustration
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.Lg),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .background(Gold.copy(alpha = 0.08f), CircleShape)
                    .border(1.dp, Gold.copy(alpha = 0.20f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Lucide.KeyRound, contentDescription = null, tint = Gold, modifier = Modifier.size(32.dp))
            }
        }

        Text(
            text = stringResource(R.string.fido2_enroll_intro),
            color = TextSecondary,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(Spacing.Sm))

        // Champ label
        OutlinedTextField(
            value = labelText,
            onValueChange = { labelText = it; viewModel.onLabelChange(it) },
            label = { Text(stringResource(R.string.label_key_name), color = TextSecondary, fontSize = 12.sp) },
            placeholder = { Text("Ma YubiKey Ed25519", color = TextSecondary.copy(alpha = 0.5f), fontSize = 13.sp) },
            singleLine = true,
            colors = nextshOutlinedColors(),
            shape = RoundedCornerShape(Radii.Md),
            modifier = Modifier.fillMaxWidth(),
        )

        // Champ PIN optionnel
        OutlinedTextField(
            value = pinText,
            onValueChange = { pinText = it },
            label = { Text(stringResource(R.string.fido2_pin_optional_label), color = TextSecondary, fontSize = 12.sp) },
            visualTransformation = PasswordVisualTransformation(),
            // KeyboardType.Password (clavier complet masqué) : le PIN FIDO2 YubiKey
            // peut contenir des lettres et symboles (PIN alphanum).
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            colors = nextshOutlinedColors(),
            shape = RoundedCornerShape(Radii.Md),
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            text = stringResource(R.string.fido2_pin_optional_hint),
            color = TextSecondary,
            fontSize = 11.sp,
        )

        // Section avancée repliable
        TextButton(
            onClick = { showAdvanced = !showAdvanced },
            modifier = Modifier.align(Alignment.Start),
        ) {
            Text(
                text = if (showAdvanced) stringResource(R.string.fido2_enroll_hide_advanced) else stringResource(R.string.fido2_enroll_show_advanced),
                color = GoldMuted,
                fontSize = 12.sp,
                fontFamily = JetBrainsMonoFamily,
            )
        }

        if (showAdvanced) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(Radii.Lg))
                    .border(1.dp, Border1, RoundedCornerShape(Radii.Lg))
                    .padding(Spacing.Md),
                verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
            ) {
                Text(
                    text = stringResource(R.string.fido2_enroll_advanced_title).uppercase(),
                    color = GoldMuted,
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.6.sp,
                )
                OutlinedTextField(
                    value = rpIdText,
                    onValueChange = { rpIdText = it; viewModel.onRpIdChange(it) },
                    label = { Text(stringResource(R.string.fido2_enroll_label_rpid), color = TextSecondary, fontSize = 12.sp) },
                    singleLine = true,
                    colors = nextshOutlinedColors(),
                    shape = RoundedCornerShape(Radii.Md),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.fido2_enroll_rpid_hint),
                    color = TextSecondary,
                    fontSize = 11.sp,
                )
            }
        }

        Spacer(Modifier.weight(1f))

        Button(
            onClick = {
                val pin = pinText.takeIf { it.isNotEmpty() }?.toCharArray()
                pinText = "" // wipe local state
                viewModel.startEnroll(pin = pin)
            },
            enabled = labelText.isNotBlank(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Burgundy,
                contentColor = White,
                disabledContainerColor = Burgundy.copy(alpha = 0.4f),
                disabledContentColor = White.copy(alpha = 0.6f),
            ),
            shape = RoundedCornerShape(Radii.Md),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Icon(Lucide.KeyRound, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(Spacing.Sm))
            Text(
                text = stringResource(R.string.fido2_enroll_action_detect),
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
            )
        }

        Spacer(Modifier.height(Spacing.Sm))
    }
}

// ── WaitingForKey ─────────────────────────────────────────────────────────────

@Composable
private fun WaitingForKeyContent(onCancel: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.Lg, vertical = Spacing.Md),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))

        // Pulse avec icône USB + NFC côte à côte
        PulseCircle(tint = Gold.copy(alpha = 0.10f)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Lucide.Usb, contentDescription = null, tint = Gold, modifier = Modifier.size(22.dp))
                Icon(Lucide.Nfc, contentDescription = null, tint = GoldLight, modifier = Modifier.size(22.dp))
            }
        }

        Spacer(Modifier.height(Spacing.Lg))
        Text(
            text = stringResource(R.string.fido2_waiting_key_title),
            color = TextPrimary,
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.Sm))
        Text(
            text = stringResource(R.string.fido2_waiting_key_hint),
            color = TextSecondary,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.weight(1f))

        OutlinedButton(
            onClick = onCancel,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
            border = androidx.compose.foundation.BorderStroke(1.dp, Border2),
            shape = RoundedCornerShape(Radii.Md),
            modifier = Modifier.fillMaxWidth().height(44.dp),
        ) {
            Text(stringResource(R.string.action_cancel), fontSize = 13.sp)
        }
        Spacer(Modifier.height(Spacing.Md))
    }
}

// ── Success ───────────────────────────────────────────────────────────────────

@Composable
private fun SuccessContent(keyLabel: String, onDone: () -> Unit) {
    CenteredStatus {
        Box(
            modifier = Modifier
                .size(80.dp)
                .background(SuccessGreen.copy(alpha = 0.12f), CircleShape)
                .border(1.dp, SuccessGreen.copy(alpha = 0.30f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Lucide.KeyRound, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.height(Spacing.Lg))
        Text(
            text = stringResource(R.string.fido2_enroll_success_title),
            color = TextPrimary,
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.Sm))
        Text(
            text = stringResource(R.string.fido2_enroll_success_message, keyLabel),
            color = TextSecondary,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.Xl))
        Button(
            onClick = onDone,
            colors = ButtonDefaults.buttonColors(containerColor = Burgundy, contentColor = White),
            shape = RoundedCornerShape(Radii.Md),
            modifier = Modifier.fillMaxWidth(0.7f).height(48.dp),
        ) {
            Text(stringResource(R.string.enrollment_action_done), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
    }
}

// ── Error ─────────────────────────────────────────────────────────────────────

@Composable
private fun ErrorContent(
    message: String,
    canRetry: Boolean,
    isPinRequired: Boolean,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.Lg, vertical = Spacing.Md),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))

        Box(
            modifier = Modifier
                .size(72.dp)
                .background(ErrorRed.copy(alpha = 0.10f), CircleShape)
                .border(1.dp, ErrorRed.copy(alpha = 0.25f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("✕", color = ErrorRed, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(Spacing.Lg))
        Text(
            text = message,
            color = ErrorRed,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )

        if (isPinRequired) {
            Spacer(Modifier.height(Spacing.Sm))
            Text(
                text = stringResource(R.string.fido2_error_pin_required_hint),
                color = WarningAmber,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.weight(1f))

        if (canRetry) {
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(containerColor = Burgundy, contentColor = White),
                shape = RoundedCornerShape(Radii.Md),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Text(stringResource(R.string.action_retry), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
            Spacer(Modifier.height(Spacing.Sm))
        }

        OutlinedButton(
            onClick = onBack,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
            border = androidx.compose.foundation.BorderStroke(1.dp, Border2),
            shape = RoundedCornerShape(Radii.Md),
            modifier = Modifier.fillMaxWidth().height(44.dp),
        ) {
            Text(stringResource(R.string.action_back), fontSize = 13.sp)
        }
        Spacer(Modifier.height(Spacing.Md))
    }
}

// ── Composables utilitaires ───────────────────────────────────────────────────

@Composable
private fun CenteredStatus(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.Lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        content = { content() },
    )
}

@Composable
private fun PulseCircle(tint: Color, content: @Composable () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulseScale",
    )
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulseAlpha",
    )
    Box(
        modifier = Modifier
            .size(88.dp)
            .scale(scale)
            .alpha(alpha)
            .background(tint, CircleShape)
            .border(1.dp, tint.copy(alpha = 0.4f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun nextshOutlinedColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    focusedBorderColor = Gold,
    unfocusedBorderColor = Border1,
    cursorColor = Gold,
    focusedContainerColor = SurfaceVariant.copy(alpha = 0.5f),
    unfocusedContainerColor = SurfaceVariant.copy(alpha = 0.5f),
    focusedLabelColor = Gold,
    unfocusedLabelColor = TextSecondary,
)

// ── Extension Context → Activity ──────────────────────────────────────────────

private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
