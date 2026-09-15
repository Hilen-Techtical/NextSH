// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sync

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.QrCode
import com.composables.icons.lucide.Smartphone
import com.composables.icons.lucide.TriangleAlert
import com.composables.icons.lucide.X
import fr.techtical.nextsh.desktop.components.PageHeader
import fr.techtical.nextsh.desktop.data.di.DesktopContainer
import fr.techtical.nextsh.desktop.theme.Border1
import fr.techtical.nextsh.desktop.theme.Border2
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
import fr.techtical.nextsh.desktop.theme.TextDisabled
import fr.techtical.nextsh.desktop.theme.TextPrimary
import fr.techtical.nextsh.desktop.theme.TextSecondary
import fr.techtical.nextsh.desktop.theme.White
import kotlinx.coroutines.delay
import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.action_cancel
import fr.techtical.nextsh.desktop.generated.resources.enrollment_action_back
import fr.techtical.nextsh.desktop.generated.resources.enrollment_done_label
import fr.techtical.nextsh.desktop.generated.resources.enrollment_error_label
import fr.techtical.nextsh.desktop.generated.resources.enrollment_meta_address
import fr.techtical.nextsh.desktop.generated.resources.enrollment_meta_fingerprint
import fr.techtical.nextsh.desktop.generated.resources.enrollment_preparing
import fr.techtical.nextsh.desktop.generated.resources.enrollment_qr_alt
import fr.techtical.nextsh.desktop.generated.resources.enrollment_scan_hint
import fr.techtical.nextsh.desktop.generated.resources.enrollment_state_enrolled
import fr.techtical.nextsh.desktop.generated.resources.enrollment_state_error
import fr.techtical.nextsh.desktop.generated.resources.enrollment_state_listening
import fr.techtical.nextsh.desktop.generated.resources.enrollment_state_starting
import fr.techtical.nextsh.desktop.generated.resources.enrollment_title
import org.jetbrains.compose.resources.stringResource

/**
 * Écran d'enrôlement d'un appareil mobile : refonte Phase 2.7.
 *
 * Layout : Column NearBlack → PageHeader "Enrôler un appareil" → Box
 * centered avec une card 480dp Surface/Border1 qui contient l'état
 * courant du serveur QR (Idle/Starting → spinner, Listening → QR +
 * fingerprint, Enrolled → success, Error → message + retour). Cohérent
 * avec le chrome refondu (HostList/Vault/Settings).
 */
@Composable
fun EnrollmentScreen(onBack: () -> Unit) {
    val viewModel = remember { EnrollmentViewModel(DesktopContainer.appScope) }
    val state by viewModel.state.collectAsState()

    DisposableEffect(Unit) { onDispose { viewModel.cancel() } }

    LaunchedEffect(state) {
        if (state is EnrollmentServer.State.Cancelled) onBack()
    }

    val subtitle = when (state) {
        is EnrollmentServer.State.Idle,
        is EnrollmentServer.State.Starting -> stringResource(Res.string.enrollment_state_starting)
        is EnrollmentServer.State.Listening -> stringResource(Res.string.enrollment_state_listening)
        is EnrollmentServer.State.Enrolled -> stringResource(Res.string.enrollment_state_enrolled)
        is EnrollmentServer.State.Error -> stringResource(Res.string.enrollment_state_error)
        is EnrollmentServer.State.Cancelled -> ""
    }

    Column(modifier = Modifier.fillMaxSize().background(NearBlack)) {
        PageHeader(
            title = stringResource(Res.string.enrollment_title),
            subtitle = subtitle,
            actions = {
                BtnGhostSm(
                    onClick = {
                        viewModel.cancel()
                        onBack()
                    },
                    icon = Lucide.X,
                    label = stringResource(Res.string.action_cancel),
                )
            },
        )

        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when (val s = state) {
                is EnrollmentServer.State.Idle,
                is EnrollmentServer.State.Starting -> StatusCard {
                    CircularProgressIndicator(color = Burgundy, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(Spacing.Md))
                    Text(stringResource(Res.string.enrollment_preparing), color = TextSecondary, fontSize = 13.sp)
                }

                is EnrollmentServer.State.Listening -> ListeningCard(s)

                is EnrollmentServer.State.Enrolled -> {
                    LaunchedEffect(Unit) {
                        delay(2_000)
                        onBack()
                    }
                    StatusCard {
                        Icon(
                            Lucide.CircleCheck,
                            contentDescription = null,
                            tint = SuccessGreen,
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(Modifier.height(Spacing.Md))
                        Text(
                            text = stringResource(Res.string.enrollment_done_label),
                            color = TextPrimary,
                            fontFamily = SpaceGroteskFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                        )
                        Spacer(Modifier.height(Spacing.Xs))
                        Text(s.device.deviceName, color = SuccessGreen, fontSize = 13.sp)
                    }
                }

                is EnrollmentServer.State.Error -> StatusCard {
                    Icon(
                        Lucide.TriangleAlert,
                        contentDescription = null,
                        tint = ErrorRed,
                        modifier = Modifier.size(40.dp),
                    )
                    Spacer(Modifier.height(Spacing.Md))
                    Text(
                        text = stringResource(Res.string.enrollment_error_label),
                        color = TextPrimary,
                        fontFamily = SpaceGroteskFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                    )
                    Spacer(Modifier.height(Spacing.Xs))
                    Text(s.reason, color = ErrorRed, fontSize = 12.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(Spacing.Md))
                    Button(
                        onClick = onBack,
                        colors = ButtonDefaults.buttonColors(containerColor = Burgundy, contentColor = White),
                        shape = RoundedCornerShape(Radii.Md),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 7.dp),
                    ) {
                        Text(stringResource(Res.string.enrollment_action_back), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }

                is EnrollmentServer.State.Cancelled -> Unit
            }
        }
    }
}

// ── Card listing the QR + connection details ────────────────────────────────

@Composable
private fun ListeningCard(state: EnrollmentServer.State.Listening) {
    val qrContent = "nextsh://enroll?addr=${state.addr}:${state.port}&fp=${state.fingerprint}"
    val qrBitmap = remember(qrContent) { QrCodeGenerator.generate(qrContent) }

    Column(
        modifier = Modifier
            .width(420.dp)
            .background(Surface, RoundedCornerShape(Radii.Lg))
            .border(1.dp, Border1, RoundedCornerShape(Radii.Lg))
            .padding(Spacing.Xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Icon-wrap titre
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Lucide.Smartphone,
                contentDescription = null,
                tint = Gold,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(Spacing.Sm))
            Text(
                text = stringResource(Res.string.enrollment_scan_hint),
                color = TextPrimary,
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
            )
        }
        Spacer(Modifier.height(Spacing.Lg))

        // QR encadré dans un fond White pour contraste de scan
        Box(
            modifier = Modifier
                .size(256.dp)
                .background(White, RoundedCornerShape(Radii.Md))
                .padding(Spacing.Md),
        ) {
            Image(
                bitmap = qrBitmap,
                contentDescription = stringResource(Res.string.enrollment_qr_alt),
                modifier = Modifier.fillMaxSize(),
            )
        }

        Spacer(Modifier.height(Spacing.Lg))

        // Adresse + port + fingerprint mono
        InfoRow(label = stringResource(Res.string.enrollment_meta_address), value = "${state.addr}:${state.port}")
        Spacer(Modifier.height(Spacing.Sm))
        InfoRow(label = stringResource(Res.string.enrollment_meta_fingerprint), value = state.fingerprint, valueColor = GoldLight)
    }
}

@Composable
private fun InfoRow(label: String, value: String, valueColor: Color = TextSecondary) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = TextDisabled, fontSize = 11.sp)
        Spacer(Modifier.width(Spacing.Sm))
        Text(
            text = value,
            color = valueColor,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun StatusCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .width(360.dp)
            .background(Surface, RoundedCornerShape(Radii.Lg))
            .border(1.dp, Border1, RoundedCornerShape(Radii.Lg))
            .padding(Spacing.Xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

// ── Bouton .btn-ghost .btn-sm pour la PageHeader ────────────────────────────

@Composable
private fun BtnGhostSm(
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val bg = if (hovered) Color.White.copy(alpha = 0.05f) else Color.Transparent
    val fg = if (hovered) TextPrimary else TextSecondary
    OutlinedButton(
        onClick = onClick,
        interactionSource = interactionSource,
        colors = ButtonDefaults.outlinedButtonColors(containerColor = bg, contentColor = fg),
        border = BorderStroke(1.dp, Color.Transparent),
        shape = RoundedCornerShape(Radii.Md),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        modifier = Modifier
            .defaultMinSize(minWidth = 0.dp, minHeight = 0.dp)
            .pointerHoverIcon(PointerIcon.Hand),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}
