// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sync

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Monitor
import com.composables.icons.lucide.Smartphone
import com.composables.icons.lucide.Trash2
import fr.techtical.nextsh.desktop.components.PageHeader
import fr.techtical.nextsh.desktop.data.di.DesktopContainer
import fr.techtical.nextsh.desktop.theme.Border1
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
import fr.techtical.nextsh.shared.core.sync.EnrolledDevice
import fr.techtical.nextsh.shared.core.sync.Platform
import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.action_cancel
import fr.techtical.nextsh.desktop.generated.resources.enrolled_devices_action_back
import fr.techtical.nextsh.desktop.generated.resources.enrolled_devices_action_revoke_short
import fr.techtical.nextsh.desktop.generated.resources.enrolled_devices_empty_hint
import fr.techtical.nextsh.desktop.generated.resources.enrolled_devices_empty_title
import fr.techtical.nextsh.desktop.generated.resources.enrolled_devices_event_error
import fr.techtical.nextsh.desktop.generated.resources.enrolled_devices_event_revoked
import fr.techtical.nextsh.desktop.generated.resources.enrolled_devices_meta_enrolled
import fr.techtical.nextsh.desktop.generated.resources.enrolled_devices_meta_host
import fr.techtical.nextsh.desktop.generated.resources.enrolled_devices_meta_last_sync
import fr.techtical.nextsh.desktop.generated.resources.enrolled_devices_meta_never
import fr.techtical.nextsh.desktop.generated.resources.enrolled_devices_platform_android
import fr.techtical.nextsh.desktop.generated.resources.enrolled_devices_platform_desktop
import fr.techtical.nextsh.desktop.generated.resources.enrolled_devices_revoke_dialog_body
import fr.techtical.nextsh.desktop.generated.resources.enrolled_devices_revoke_dialog_title
import fr.techtical.nextsh.desktop.generated.resources.enrolled_devices_section_title
import fr.techtical.nextsh.desktop.generated.resources.enrolled_devices_subtitle_empty
import fr.techtical.nextsh.desktop.generated.resources.enrolled_devices_subtitle_many
import fr.techtical.nextsh.desktop.generated.resources.enrolled_devices_subtitle_one
import fr.techtical.nextsh.desktop.generated.resources.enrolled_devices_title
import org.jetbrains.compose.resources.stringResource

/**
 * Liste des appareils enrôlés : refonte Phase 2.7.
 *
 * Layout : Column NearBlack → PageHeader → grid responsive 1/2 cols
 * (breakpoint 800 dp ; au-delà la card peut afficher 5 lignes meta
 * sans étirement). Pas de Snackbar : les erreurs/confirmations
 * sortent en AlertDialog cohérents avec le reste de la refonte.
 */
@Composable
fun EnrolledDevicesScreen(onBack: () -> Unit) {
    val viewModel = remember { DesktopContainer.enrolledDevicesViewModel }
    val devices by viewModel.devices.collectAsState()
    var deviceToRevoke by remember { mutableStateOf<EnrolledDevice?>(null) }
    var transientMessage by remember { mutableStateOf<String?>(null) }

    val revokedTemplate = stringResource(Res.string.enrolled_devices_event_revoked, "%1\$s")
    val errorTemplate = stringResource(Res.string.enrolled_devices_event_error, "%1\$s")
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            transientMessage = when (event) {
                is EnrolledDevicesViewModel.Event.Revoked -> revokedTemplate.replace("%1\$s", event.deviceName)
                is EnrolledDevicesViewModel.Event.Error -> errorTemplate.replace("%1\$s", event.message)
            }
        }
    }

    val subtitle = when {
        devices.isEmpty() -> stringResource(Res.string.enrolled_devices_subtitle_empty)
        devices.size == 1 -> stringResource(Res.string.enrolled_devices_subtitle_one)
        else -> stringResource(Res.string.enrolled_devices_subtitle_many, devices.size)
    }

    Column(modifier = Modifier.fillMaxSize().background(NearBlack)) {
        PageHeader(
            title = stringResource(Res.string.enrolled_devices_title),
            subtitle = subtitle,
            actions = {
                BtnGhostSm(
                    onClick = onBack,
                    icon = Lucide.ArrowLeft,
                    label = stringResource(Res.string.enrolled_devices_action_back),
                )
            },
        )

        if (devices.isEmpty()) {
            EmptyState()
        } else {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val columns = if (maxWidth >= 800.dp) 2 else 1
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = Spacing.Xxl, vertical = Spacing.Xl),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Md),
                ) {
                    items(devices, key = { it.deviceId }) { device ->
                        DeviceCard(
                            device = device,
                            onRevoke = { deviceToRevoke = device },
                        )
                    }
                }
            }
        }
    }

    val pendingRevoke = deviceToRevoke
    if (pendingRevoke != null) {
        AlertDialog(
            onDismissRequest = { deviceToRevoke = null },
            title = { Text(stringResource(Res.string.enrolled_devices_revoke_dialog_title, pendingRevoke.deviceName), color = TextPrimary) },
            text = {
                Text(
                    text = stringResource(Res.string.enrolled_devices_revoke_dialog_body),
                    color = TextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.revoke(pendingRevoke)
                    deviceToRevoke = null
                }) { Text(stringResource(Res.string.enrolled_devices_action_revoke_short), color = ErrorRed) }
            },
            dismissButton = {
                TextButton(onClick = { deviceToRevoke = null }) {
                    Text(stringResource(Res.string.action_cancel), color = TextSecondary)
                }
            },
            containerColor = Surface,
        )
    }

    val toastMsg = transientMessage
    if (toastMsg != null) {
        AlertDialog(
            onDismissRequest = { transientMessage = null },
            title = { Text(stringResource(Res.string.enrolled_devices_section_title), color = TextPrimary) },
            text = { Text(toastMsg, color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = { transientMessage = null }) {
                    Text("OK", color = Gold)
                }
            },
            containerColor = Surface,
        )
    }
}

@Composable
private fun EmptyState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Lucide.Smartphone,
                contentDescription = null,
                tint = GoldMuted,
                modifier = Modifier.size(64.dp),
            )
            Spacer(Modifier.height(Spacing.Md))
            Text(
                text = stringResource(Res.string.enrolled_devices_empty_title),
                color = TextPrimary,
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
            )
            Spacer(Modifier.height(Spacing.Xs))
            Text(
                text = stringResource(Res.string.enrolled_devices_empty_hint),
                color = TextSecondary,
                fontSize = 12.sp,
            )
        }
    }
}

// ── Card ─────────────────────────────────────────────────────────────────────

@Composable
private fun DeviceCard(
    device: EnrolledDevice,
    onRevoke: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface, RoundedCornerShape(Radii.Lg))
            .border(1.dp, Border1, RoundedCornerShape(Radii.Lg))
            .padding(horizontal = Spacing.Lg, vertical = Spacing.Md),
        verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // icon-wrap selon plateforme
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(Gold.copy(alpha = 0.08f), RoundedCornerShape(Radii.Md)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = when (device.platform) {
                        Platform.DESKTOP -> Lucide.Monitor
                        Platform.ANDROID -> Lucide.Smartphone
                    },
                    contentDescription = null,
                    tint = Gold,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(Spacing.Md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.deviceName,
                    color = TextPrimary,
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                PlatformBadge(device.platform)
            }
            Spacer(Modifier.width(Spacing.Sm))
            BtnRevoke(onClick = onRevoke)
        }

        // ── Méta lignes mono ─────────────────────────────────────────────────
        MetaLine(label = stringResource(Res.string.enrolled_devices_meta_host), value = device.lastKnownHost ?: "-",
            valueColor = if (device.lastKnownHost == null) ErrorRed else TextPrimary)
        MetaLine(label = "ECDH", value = device.publicKeyFingerprint)
        MetaLine(
            label = "TLS",
            value = device.tlsCertFingerprint?.let { "$it…" }?.take(16 + 1) ?: "-",
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MetaLine(label = stringResource(Res.string.enrolled_devices_meta_enrolled), value = relativeTime(device.enrolledAt))
            MetaLine(
                label = stringResource(Res.string.enrolled_devices_meta_last_sync),
                value = device.lastSyncAt?.let { relativeTime(it) } ?: stringResource(Res.string.enrolled_devices_meta_never),
                valueColor = if (device.lastSyncAt != null) SuccessGreen else TextDisabled,
            )
        }
    }
}

@Composable
private fun MetaLine(label: String, value: String, valueColor: Color = TextSecondary) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextDisabled, fontSize = 11.sp, modifier = Modifier.width(60.dp))
        Text(
            text = value,
            color = valueColor,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PlatformBadge(platform: Platform) {
    val label = stringResource(when (platform) {
        Platform.DESKTOP -> Res.string.enrolled_devices_platform_desktop
        Platform.ANDROID -> Res.string.enrolled_devices_platform_android
    })
    Box(
        modifier = Modifier
            .background(Gold.copy(alpha = 0.08f), RoundedCornerShape(3.dp))
            .border(1.dp, Gold.copy(alpha = 0.20f), RoundedCornerShape(3.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(
            text = label,
            color = GoldLight,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.5.sp,
        )
    }
}

/** `.btn-ghost .btn-sm` pour l'action "Retour" du PageHeader. */
@Composable
private fun BtnGhostSm(onClick: () -> Unit, icon: ImageVector, label: String) {
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

@Composable
private fun BtnRevoke(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val borderColor = if (hovered) ErrorRed else ErrorRed.copy(alpha = 0.5f)
    val bg = if (hovered) ErrorRed.copy(alpha = 0.08f) else Color.Transparent
    OutlinedButton(
        onClick = onClick,
        interactionSource = interactionSource,
        colors = ButtonDefaults.outlinedButtonColors(containerColor = bg, contentColor = ErrorRed),
        border = BorderStroke(1.dp, borderColor),
        shape = RoundedCornerShape(Radii.Md),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        modifier = Modifier
            .defaultMinSize(minWidth = 0.dp, minHeight = 0.dp)
            .pointerHoverIcon(PointerIcon.Hand),
    ) {
        Icon(Lucide.Trash2, contentDescription = null, tint = ErrorRed, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(Res.string.enrolled_devices_action_revoke_short), fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}
