// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.vault

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Eye
import com.composables.icons.lucide.EyeOff
import com.composables.icons.lucide.KeyRound
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Usb
import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.action_cancel
import fr.techtical.nextsh.desktop.generated.resources.fido2_enroll_advanced_section
import fr.techtical.nextsh.desktop.generated.resources.fido2_enroll_algo_field
import fr.techtical.nextsh.desktop.generated.resources.fido2_enroll_algo_value
import fr.techtical.nextsh.desktop.generated.resources.fido2_enroll_btn_continue
import fr.techtical.nextsh.desktop.generated.resources.fido2_enroll_btn_detect
import fr.techtical.nextsh.desktop.generated.resources.fido2_enroll_btn_make
import fr.techtical.nextsh.desktop.generated.resources.fido2_enroll_detecting
import fr.techtical.nextsh.desktop.generated.resources.fido2_enroll_dialog_subtitle
import fr.techtical.nextsh.desktop.generated.resources.fido2_enroll_dialog_title
import fr.techtical.nextsh.desktop.generated.resources.fido2_enroll_label_field
import fr.techtical.nextsh.desktop.generated.resources.fido2_enroll_label_placeholder
import fr.techtical.nextsh.desktop.generated.resources.fido2_enroll_pin_field
import fr.techtical.nextsh.desktop.generated.resources.fido2_enroll_pin_hint
import fr.techtical.nextsh.desktop.generated.resources.fido2_enroll_retry
import fr.techtical.nextsh.desktop.generated.resources.fido2_enroll_rpid_field
import fr.techtical.nextsh.desktop.generated.resources.fido2_enroll_success
import fr.techtical.nextsh.desktop.generated.resources.fido2_enroll_success_ecdsa256
import fr.techtical.nextsh.desktop.generated.resources.fido2_enroll_success_ed25519
import fr.techtical.nextsh.desktop.generated.resources.fido2_enroll_waiting_touch
import fr.techtical.nextsh.desktop.generated.resources.fido2_enroll_waiting_touch_sub
import fr.techtical.nextsh.desktop.theme.Border1
import fr.techtical.nextsh.desktop.theme.Burgundy
import fr.techtical.nextsh.desktop.theme.ErrorRed
import fr.techtical.nextsh.desktop.theme.Gold
import fr.techtical.nextsh.desktop.theme.GoldLight
import fr.techtical.nextsh.desktop.theme.GoldMuted
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
import fr.techtical.nextsh.desktop.window.WindowCaptureProtection
import fr.techtical.nextsh.shared.domain.model.SshKey
import fr.techtical.nextsh.shared.domain.model.SshKeyType
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/**
 * Dialog d'enrôlement FIDO2 Desktop (MakeCredential, sk-ssh-ed25519).
 *
 * Affiché dans une [DialogWindow] OS séparée pour rester au-dessus des
 * [SwingPanel] JediTerm, même pattern que [UnknownHostKeyDialog].
 *
 * États :
 *  - [Fido2EnrollState.Idle] : formulaire + bouton "Brancher la clé"
 *  - [Fido2EnrollState.DetectingDevice] : spinner "Détection…"
 *  - [Fido2EnrollState.WaitingForPin] : champ PIN visible + bouton "Continuer"
 *  - [Fido2EnrollState.WaitingForTouch] : animation Gold pulse + texte touch
 *  - [Fido2EnrollState.Success] : confirmation 1,5 sec puis [onSuccess]
 *  - [Fido2EnrollState.Error] : message rouge + bouton "Réessayer"
 *
 * @param onDismiss  Appelé quand l'utilisateur annule ou ferme le dialog.
 * @param onSuccess  Appelé après confirmation réussie avec la [SshKey] créée.
 */
@Composable
fun Fido2EnrollDialog(
    onDismiss: () -> Unit,
    onSuccess: (SshKey) -> Unit,
) {
    val viewModel = remember { Fido2EnrollViewModel() }
    val state by viewModel.state.collectAsState()
    val isWindowsWebAuthn = viewModel.isWindowsWebAuthnMode

    // Auto-fermeture 1,5 sec après succès
    LaunchedEffect(state) {
        if (state is Fido2EnrollState.Success) {
            delay(1500)
            onSuccess((state as Fido2EnrollState.Success).key)
        }
    }

    DialogWindow(
        onCloseRequest = onDismiss,
        title = stringResource(Res.string.fido2_enroll_dialog_title),
        state = rememberDialogState(size = DpSize(520.dp, 640.dp)),
        resizable = false,
    ) {
        // Affiche label et empreinte de la clé FIDO2 enrôlée.
        WindowCaptureProtection(window)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(NearBlack),
        ) {
            // ── Header ────────────────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Surface)
                    .padding(horizontal = 28.dp, vertical = 20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .background(Gold.copy(alpha = 0.10f), RoundedCornerShape(Radii.Md)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Lucide.Usb, contentDescription = null, tint = Gold, modifier = Modifier.size(15.dp))
                    }
                    Spacer(Modifier.width(Spacing.Sm))
                    Column {
                        Text(
                            text = stringResource(Res.string.fido2_enroll_dialog_title),
                            color = TextPrimary,
                            fontFamily = SpaceGroteskFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                        )
                        Text(
                            text = stringResource(Res.string.fido2_enroll_dialog_subtitle),
                            color = TextSecondary,
                            fontSize = 11.sp,
                        )
                    }
                }
            }

            // ── Content area ─────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                when (val s = state) {
                    is Fido2EnrollState.Idle -> IdleContent(
                        viewModel = viewModel,
                        onDismiss = onDismiss,
                        isWindowsWebAuthn = isWindowsWebAuthn,
                    )
                    is Fido2EnrollState.DetectingDevice -> DetectingContent()
                    is Fido2EnrollState.WaitingForPin -> WaitingForPinContent(
                        deviceLabel = s.deviceLabel,
                        viewModel = viewModel,
                        onDismiss = onDismiss,
                    )
                    is Fido2EnrollState.WaitingForTouch -> WaitingForTouchContent(
                        deviceLabel = s.deviceLabel,
                        onDismiss = onDismiss,
                        isWindowsWebAuthn = isWindowsWebAuthn,
                    )
                    is Fido2EnrollState.Success -> SuccessContent(keyType = s.key.keyType)
                    is Fido2EnrollState.Error -> ErrorContent(
                        message = s.message,
                        viewModel = viewModel,
                        onDismiss = onDismiss,
                    )
                }
            }
        }
    }
}

// ── Idle state ────────────────────────────────────────────────────────────────

@Composable
private fun IdleContent(
    viewModel: Fido2EnrollViewModel,
    onDismiss: () -> Unit,
    isWindowsWebAuthn: Boolean = false,
) {
    var label by remember { mutableStateOf("") }
    var rpId by remember { mutableStateOf("ssh:") }
    var advancedExpanded by remember { mutableStateOf(false) }
    val canSubmit = label.isNotBlank()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.Md),
    ) {
        // Label field
        EnrollFieldGroup(label = stringResource(Res.string.fido2_enroll_label_field)) {
            EnrollTextField(
                value = label,
                onValueChange = { label = it },
                placeholder = stringResource(Res.string.fido2_enroll_label_placeholder),
            )
        }

        // Advanced section (rpId + algo)
        AdvancedSection(expanded = advancedExpanded, onToggle = { advancedExpanded = !advancedExpanded }) {
            EnrollFieldGroup(label = stringResource(Res.string.fido2_enroll_rpid_field)) {
                EnrollTextField(
                    value = rpId,
                    onValueChange = { rpId = it },
                    placeholder = "ssh:",
                )
            }
            Spacer(Modifier.height(Spacing.Sm))
            EnrollFieldGroup(label = stringResource(Res.string.fido2_enroll_algo_field)) {
                Text(
                    text = stringResource(Res.string.fido2_enroll_algo_value),
                    color = TextSecondary,
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 12.sp,
                )
            }
        }

        Spacer(Modifier.weight(1f))

        // Footer buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            EnrollBtnGhost(onClick = onDismiss, label = stringResource(Res.string.action_cancel))
            Spacer(Modifier.width(Spacing.Sm))
            // Sur Windows WebAuthn : "Démarrer l'enrôlement", Hello gère nativement
            // Sur YubiKit (PC/SC) : "Brancher la clé", detection hardware d'abord
            val btnLabel = if (isWindowsWebAuthn) {
                "Démarrer l'enrôlement"
            } else {
                stringResource(Res.string.fido2_enroll_btn_detect)
            }
            EnrollBtnPrimary(
                onClick = { viewModel.startDetection(label, rpId) },
                icon = Lucide.Usb,
                label = btnLabel,
                enabled = canSubmit,
            )
        }
    }
}

// ── Detecting state ───────────────────────────────────────────────────────────

@Composable
private fun DetectingContent() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                color = Gold,
                strokeWidth = 2.dp,
                modifier = Modifier.size(36.dp),
            )
            Spacer(Modifier.height(Spacing.Md))
            Text(
                text = stringResource(Res.string.fido2_enroll_detecting),
                color = TextSecondary,
                fontSize = 13.sp,
            )
        }
    }
}

// ── WaitingForPin state ───────────────────────────────────────────────────────

@Composable
private fun WaitingForPinContent(
    deviceLabel: String,
    viewModel: Fido2EnrollViewModel,
    onDismiss: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var pinVisible by remember { mutableStateOf(false) }
    val canSubmit = pin.isNotBlank()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.Md),
    ) {
        // Device info
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceVariant, RoundedCornerShape(Radii.Md))
                .padding(Spacing.Md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Lucide.KeyRound, contentDescription = null, tint = Gold, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(Spacing.Sm))
            Text(deviceLabel, color = GoldLight, fontFamily = JetBrainsMonoFamily, fontSize = 12.sp)
        }

        // PIN field
        EnrollFieldGroup(label = stringResource(Res.string.fido2_enroll_pin_field)) {
            OutlinedTextField(
                value = pin,
                onValueChange = { pin = it },
                placeholder = { Text("••••••••", color = TextDisabled, fontSize = 12.sp) },
                singleLine = true,
                visualTransformation = if (pinVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { pinVisible = !pinVisible }, modifier = Modifier.size(28.dp)) {
                        Icon(
                            imageVector = if (pinVisible) Lucide.EyeOff else Lucide.Eye,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                colors = enrollTextFieldColors(),
                shape = RoundedCornerShape(Radii.Md),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(Res.string.fido2_enroll_pin_hint),
                color = TextDisabled,
                fontSize = 10.sp,
            )
        }

        Spacer(Modifier.weight(1f))

        // Footer
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            EnrollBtnGhost(onClick = onDismiss, label = stringResource(Res.string.action_cancel))
            Spacer(Modifier.width(Spacing.Sm))
            EnrollBtnPrimary(
                onClick = {
                    val pinChars = pin.toCharArray()
                    pin = ""
                    viewModel.submitPin(pinChars)
                },
                icon = Lucide.KeyRound,
                label = stringResource(Res.string.fido2_enroll_btn_continue),
                enabled = canSubmit,
            )
        }
    }
}

// ── WaitingForTouch state ─────────────────────────────────────────────────────

@Composable
private fun WaitingForTouchContent(
    deviceLabel: String,
    onDismiss: () -> Unit,
    isWindowsWebAuthn: Boolean = false,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (isWindowsWebAuthn) {
            // ── Mode Windows WebAuthn : pas d'animation pulse, Hello affiche son propre UI ──

            // Spinner simple pour indiquer l'attente côté NextSH
            CircularProgressIndicator(
                color = Gold,
                strokeWidth = 2.dp,
                modifier = Modifier.size(48.dp),
            )

            Spacer(Modifier.height(Spacing.Lg))

            Text(
                text = "En attente de Windows Hello…",
                color = TextPrimary,
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(Spacing.Xs))

            Text(
                text = "Windows va te demander de toucher ta clé ou saisir ton PIN.",
                color = TextSecondary,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )

        } else {
            // ── Mode YubiKit (PC/SC + HID) : animation pulse Gold classique ──

            val infiniteTransition = rememberInfiniteTransition(label = "fido2-enroll-pulse")
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.4f,
                targetValue = 1.0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 800, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "iconAlpha",
            )

            Box(
                modifier = Modifier
                    .size(88.dp)
                    .clip(CircleShape)
                    .background(Gold.copy(alpha = alpha * 0.12f))
                    .border(2.dp, Gold.copy(alpha = alpha * 0.5f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Lucide.Usb,
                    contentDescription = null,
                    tint = Gold.copy(alpha = alpha),
                    modifier = Modifier.size(40.dp),
                )
            }

            Spacer(Modifier.height(Spacing.Lg))

            Text(
                text = stringResource(Res.string.fido2_enroll_waiting_touch),
                color = TextPrimary,
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(Spacing.Xs))

            Text(
                text = stringResource(Res.string.fido2_enroll_waiting_touch_sub, deviceLabel),
                color = TextSecondary,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(Spacing.Xl))

        OutlinedButton(
            onClick = onDismiss,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Burgundy),
            border = androidx.compose.foundation.BorderStroke(1.dp, Burgundy.copy(alpha = 0.6f)),
            shape = RoundedCornerShape(Radii.Md),
        ) {
            Text(stringResource(Res.string.action_cancel), fontSize = 12.sp)
        }
    }
}

// ── Success state ─────────────────────────────────────────────────────────────

@Composable
private fun SuccessContent(keyType: SshKeyType = SshKeyType.SK_ED25519) {
    val successText = when (keyType) {
        SshKeyType.SK_ECDSA_256 -> stringResource(Res.string.fido2_enroll_success_ecdsa256)
        SshKeyType.SK_ED25519 -> stringResource(Res.string.fido2_enroll_success_ed25519)
        else -> stringResource(Res.string.fido2_enroll_success)
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .background(SuccessGreen.copy(alpha = 0.12f), CircleShape)
                    .border(2.dp, SuccessGreen.copy(alpha = 0.4f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Lucide.KeyRound, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(32.dp))
            }
            Spacer(Modifier.height(Spacing.Md))
            Text(
                text = successText,
                color = SuccessGreen,
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = Spacing.Md),
            )
        }
    }
}

// ── Error state ───────────────────────────────────────────────────────────────

@Composable
private fun ErrorContent(
    message: String,
    viewModel: Fido2EnrollViewModel,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Lucide.Usb, contentDescription = null, tint = ErrorRed, modifier = Modifier.size(36.dp))
        Spacer(Modifier.height(Spacing.Md))
        Text(
            text = message,
            color = ErrorRed,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.Xl))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
            EnrollBtnGhost(onClick = onDismiss, label = stringResource(Res.string.action_cancel))
            EnrollBtnPrimary(
                onClick = { viewModel.reset() },
                icon = Lucide.Usb,
                label = stringResource(Res.string.fido2_enroll_retry),
            )
        }
    }
}

// ── Form primitives ───────────────────────────────────────────────────────────

@Composable
private fun AdvancedSection(
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceVariant, RoundedCornerShape(Radii.Md))
            .border(1.dp, Border1, RoundedCornerShape(Radii.Md)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = Spacing.Md, vertical = Spacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (expanded) Lucide.ChevronDown else Lucide.ChevronRight,
                contentDescription = null,
                tint = GoldMuted,
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(Spacing.Xs))
            Text(
                text = stringResource(Res.string.fido2_enroll_advanced_section).uppercase(),
                color = GoldMuted,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.6.sp,
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceVariant)
                    .padding(start = Spacing.Md, end = Spacing.Md, bottom = Spacing.Md),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun EnrollFieldGroup(
    label: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Xs)) {
        Text(
            text = label.uppercase(),
            color = GoldMuted,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.6.sp,
        )
        content()
    }
}

@Composable
private fun EnrollTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, color = TextDisabled, fontSize = 12.sp) },
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
        colors = enrollTextFieldColors(),
        shape = RoundedCornerShape(Radii.Md),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun enrollTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Gold.copy(alpha = 0.55f),
    unfocusedBorderColor = Border1,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    cursorColor = Gold,
    focusedContainerColor = Surface,
    unfocusedContainerColor = Surface,
)

@Composable
private fun EnrollBtnPrimary(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
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
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        modifier = Modifier
            .defaultMinSize(minWidth = 0.dp, minHeight = 0.dp)
            .pointerHoverIcon(PointerIcon.Hand),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun EnrollBtnGhost(onClick: () -> Unit, label: String) {
    OutlinedButton(
        onClick = onClick,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
        border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color.Transparent),
        shape = RoundedCornerShape(Radii.Md),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        modifier = Modifier
            .defaultMinSize(minWidth = 0.dp, minHeight = 0.dp)
            .pointerHoverIcon(PointerIcon.Hand),
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}
