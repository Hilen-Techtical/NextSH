// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.transfers

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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ArrowDownUp
import com.composables.icons.lucide.Clock
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Server
import com.composables.icons.lucide.Upload
import com.composables.icons.lucide.X
import fr.techtical.nextsh.desktop.components.PageHeader
import fr.techtical.nextsh.desktop.core.ssh.DesktopTransferManager
import fr.techtical.nextsh.desktop.data.di.DesktopContainer
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
import fr.techtical.nextsh.desktop.theme.SuccessGreen
import fr.techtical.nextsh.desktop.theme.Surface
import fr.techtical.nextsh.desktop.theme.SurfaceVariant
import fr.techtical.nextsh.desktop.theme.TextDisabled
import fr.techtical.nextsh.desktop.theme.TextPrimary
import fr.techtical.nextsh.desktop.theme.TextSecondary
import fr.techtical.nextsh.shared.domain.model.TransferDirection
import fr.techtical.nextsh.shared.domain.model.TransferState
import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.transfers_action_cancel
import fr.techtical.nextsh.desktop.generated.resources.transfers_action_clear_done
import fr.techtical.nextsh.desktop.generated.resources.transfers_action_remove
import fr.techtical.nextsh.desktop.generated.resources.transfers_cancelled_message
import fr.techtical.nextsh.desktop.generated.resources.transfers_completed_with_size
import fr.techtical.nextsh.desktop.generated.resources.transfers_direction_download
import fr.techtical.nextsh.desktop.generated.resources.transfers_direction_upload
import fr.techtical.nextsh.desktop.generated.resources.transfers_empty_hint
import fr.techtical.nextsh.desktop.generated.resources.transfers_empty_title
import fr.techtical.nextsh.desktop.generated.resources.transfers_failed_default
import fr.techtical.nextsh.desktop.generated.resources.transfers_queued_with_size
import fr.techtical.nextsh.desktop.generated.resources.transfers_relative_just_now
import fr.techtical.nextsh.desktop.generated.resources.transfers_state_cancelled_label
import fr.techtical.nextsh.desktop.generated.resources.transfers_state_done_label
import fr.techtical.nextsh.desktop.generated.resources.transfers_state_failed_label
import fr.techtical.nextsh.desktop.generated.resources.transfers_state_inprogress_label
import fr.techtical.nextsh.desktop.generated.resources.transfers_state_queued_label
import fr.techtical.nextsh.desktop.generated.resources.transfers_title
import fr.techtical.nextsh.desktop.generated.resources.statusbar_relative_minutes_ago
import org.jetbrains.compose.resources.stringResource

/**
 * Écran liste des transferts SFTP, refonte Phase 2.4.
 *
 * Layout : Column NearBlack → PageHeader → grid responsive 1/2/3 colonnes
 * de TransferCard (cohérent avec HostListScreen pour l'affichage dense).
 *
 * Pas de OS notifications : cet écran reste la seule surface de progression.
 */
@Composable
fun TransfersScreen(
    transferManager: DesktopTransferManager = DesktopContainer.transferManager,
    modifier: Modifier = Modifier,
) {
    val transfers by transferManager.transfers.collectAsState()
    val sorted = transfers.values.sortedByDescending { it.request.createdAt }

    val activeCount = transfers.values.count {
        it is TransferState.InProgress || it is TransferState.Queued
    }
    val terminalCount = transfers.values.count {
        it is TransferState.Completed || it is TransferState.Failed || it is TransferState.Cancelled
    }
    val hasFinished = terminalCount > 0

    val subtitle = when {
        transfers.isEmpty() -> stringResource(Res.string.transfers_empty_title)
        activeCount > 0 && terminalCount > 0 ->
            "$activeCount en cours · $terminalCount terminé${if (terminalCount > 1) "s" else ""}"
        activeCount > 0 -> "$activeCount en cours"
        else -> "$terminalCount terminé${if (terminalCount > 1) "s" else ""}"
    }

    Column(modifier = modifier.fillMaxSize().background(NearBlack)) {
        PageHeader(
            title = stringResource(Res.string.transfers_title),
            subtitle = subtitle,
            actions = if (hasFinished) {
                {
                    BtnGhostSm(
                        onClick = transferManager::dismissAllCompleted,
                        enabled = true,
                        icon = Lucide.X,
                        label = stringResource(Res.string.transfers_action_clear_done),
                    )
                }
            } else null,
        )

        if (sorted.isEmpty()) {
            EmptyTransfersState()
        } else {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val columns = when {
                    maxWidth < 700.dp -> 1
                    maxWidth < 1100.dp -> 2
                    else -> 3
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = Spacing.Xxl, vertical = Spacing.Xl),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Md),
                ) {
                    items(sorted, key = { it.request.id }) { state ->
                        TransferCard(
                            state = state,
                            onCancel = { transferManager.cancel(state.request.id) },
                            onDismiss = { transferManager.dismiss(state.request.id) },
                        )
                    }
                }
            }
        }
    }
}

// ── Card ─────────────────────────────────────────────────────────────────────

private const val CARD_HEIGHT_DP = 132

@Composable
private fun TransferCard(
    state: TransferState,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isTerminal = state is TransferState.Completed ||
        state is TransferState.Failed ||
        state is TransferState.Cancelled

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(CARD_HEIGHT_DP.dp)
            .background(Surface, RoundedCornerShape(Radii.Lg))
            .border(1.dp, Border1, RoundedCornerShape(Radii.Lg))
            .padding(Spacing.Md),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // ── Ligne 1 : icône + nom + status pill + close ───────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DirectionIconWrap(direction = state.request.direction)
                Spacer(Modifier.width(Spacing.Sm))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.request.displayName,
                        color = TextPrimary,
                        fontFamily = SpaceGroteskFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = state.request.remotePath,
                        color = TextSecondary,
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(Spacing.Xs))
                StatusPill(state = state)
                Spacer(Modifier.width(Spacing.Xs))
                CloseButton(
                    onClick = if (isTerminal) onDismiss else onCancel,
                    tooltip = stringResource(if (isTerminal) Res.string.transfers_action_remove else Res.string.transfers_action_cancel),
                )
            }

            // ── Ligne 2 : meta hôte + timestamp ───────────────────────────────
            MetaLine(
                hostLabel = state.request.hostLabel,
                direction = state.request.direction,
                createdAt = state.request.createdAt,
            )

            // ── Ligne 3 : progress bar + label OU status line ─────────────────
            ProgressOrStatus(state = state)
        }
    }
}

@Composable
private fun ProgressOrStatus(state: TransferState) {
    when (state) {
        is TransferState.Queued -> ProgressRow(
            progress = 0f,
            label = stringResource(Res.string.transfers_queued_with_size, formatBytes(state.request.fileSize)),
            color = TextDisabled,
        )
        is TransferState.InProgress -> {
            val ratio = if (state.totalBytes > 0)
                (state.bytesTransferred.toFloat() / state.totalBytes.toFloat()).coerceIn(0f, 1f)
            else 0f
            ProgressRow(
                progress = ratio,
                label = "${formatBytes(state.bytesTransferred)} / ${formatBytes(state.totalBytes)}  •  ${(ratio * 100).toInt()} %",
                color = colorForDirection(state.request.direction),
            )
        }
        is TransferState.Completed -> StatusLine(
            text = stringResource(Res.string.transfers_completed_with_size, formatBytes(state.request.fileSize)),
            color = SuccessGreen,
        )
        is TransferState.Failed -> StatusLine(
            text = state.error.ifBlank { stringResource(Res.string.transfers_failed_default) },
            color = ErrorRed,
        )
        is TransferState.Cancelled -> StatusLine(
            text = stringResource(Res.string.transfers_cancelled_message),
            color = GoldMuted,
        )
    }
}

/**
 * Sous-titre meta : `<icône Server> [vers/depuis] hostLabel  •  <icône Clock> 14:32`.
 * Couleur TextDisabled, fontSize 11sp.
 */
@Composable
private fun MetaLine(
    hostLabel: String?,
    direction: TransferDirection,
    createdAt: Long,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (!hostLabel.isNullOrBlank()) {
            Icon(
                imageVector = Lucide.Server,
                contentDescription = null,
                tint = TextDisabled,
                modifier = Modifier.size(11.dp),
            )
            Spacer(Modifier.width(Spacing.Xs))
            Text(
                text = directionPrefix(direction) + hostLabel,
                color = TextDisabled,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(Spacing.Sm))
            Text(text = "•", color = TextDisabled, fontSize = 11.sp)
            Spacer(Modifier.width(Spacing.Sm))
        }
        Icon(
            imageVector = Lucide.Clock,
            contentDescription = null,
            tint = TextDisabled,
            modifier = Modifier.size(11.dp),
        )
        Spacer(Modifier.width(Spacing.Xs))
        Text(
            text = formatTimestamp(createdAt),
            color = TextDisabled,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun directionPrefix(direction: TransferDirection): String = stringResource(
    when (direction) {
        TransferDirection.UPLOAD -> Res.string.transfers_direction_upload
        TransferDirection.DOWNLOAD -> Res.string.transfers_direction_download
    }
)

private fun colorForDirection(direction: TransferDirection): Color = when (direction) {
    TransferDirection.UPLOAD -> Gold
    TransferDirection.DOWNLOAD -> BurgundyLight
}

/**
 * Icon-wrap directionnel 32×32, radius 6dp.
 *  - UPLOAD   : `Lucide.Upload`   Gold sur bg `Gold α=0.10`.
 *  - DOWNLOAD : `Lucide.Download` BurgundyLight sur bg `Burgundy α=0.18`.
 */
@Composable
private fun DirectionIconWrap(direction: TransferDirection) {
    val (icon, tint, bg) = when (direction) {
        TransferDirection.UPLOAD -> Triple(Lucide.Upload, Gold, Gold.copy(alpha = 0.10f))
        TransferDirection.DOWNLOAD -> Triple(Lucide.Download, BurgundyLight, Burgundy.copy(alpha = 0.18f))
    }
    Box(
        modifier = Modifier
            .size(32.dp)
            .background(bg, RoundedCornerShape(Radii.Md)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
    }
}

/**
 * Pill UPPERCASE 10sp font-mono, mêmes specs que `TunnelCard.StatusPill`.
 * Couleur dérivée de l'état du transfert.
 */
@Composable
private fun StatusPill(state: TransferState) {
    val (labelRes, color) = when (state) {
        is TransferState.Queued -> Res.string.transfers_state_queued_label to TextDisabled
        is TransferState.InProgress -> Res.string.transfers_state_inprogress_label to colorForDirection(state.request.direction)
        is TransferState.Completed -> Res.string.transfers_state_done_label to SuccessGreen
        is TransferState.Failed -> Res.string.transfers_state_failed_label to ErrorRed
        is TransferState.Cancelled -> Res.string.transfers_state_cancelled_label to GoldMuted
    }
    val label = stringResource(labelRes)
    val bg = if (state is TransferState.Queued) Color.White.copy(alpha = 0.04f)
    else color.copy(alpha = 0.10f)

    Row(
        modifier = Modifier
            .background(bg, RoundedCornerShape(9999.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(color, RoundedCornerShape(9999.dp)),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = label,
            color = color,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.5.sp,
        )
    }
}

@Composable
private fun ProgressRow(progress: Float, label: String, color: Color) {
    Column {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp),
            color = color,
            trackColor = SurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            color = TextSecondary,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StatusLine(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        fontSize = 12.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * Bouton X 24×24, hover bg `White α=0.06`, tint TextPrimary.
 */
@Composable
private fun CloseButton(onClick: () -> Unit, tooltip: String) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val bg = if (hovered) Color.White.copy(alpha = 0.06f) else Color.Transparent
    val tint = if (hovered) TextPrimary else TextSecondary
    OutlinedButton(
        onClick = onClick,
        interactionSource = interactionSource,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = bg,
            contentColor = tint,
        ),
        border = BorderStroke(1.dp, Color.Transparent),
        shape = RoundedCornerShape(Radii.Md),
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier
            .size(24.dp)
            .defaultMinSize(minWidth = 0.dp, minHeight = 0.dp)
            .pointerHoverIcon(PointerIcon.Hand),
    ) {
        Icon(Lucide.X, contentDescription = tooltip, tint = tint, modifier = Modifier.size(12.dp))
    }
}

// ── Empty state ──────────────────────────────────────────────────────────────

@Composable
private fun EmptyTransfersState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.Md),
        ) {
            Icon(
                imageVector = Lucide.ArrowDownUp,
                contentDescription = null,
                tint = GoldMuted,
                modifier = Modifier.size(64.dp),
            )
            Spacer(Modifier.height(Spacing.Sm))
            Text(
                text = stringResource(Res.string.transfers_empty_title),
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(Res.string.transfers_empty_hint),
                color = TextSecondary,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ── Buttons (.btn-ghost .btn-sm) ─────────────────────────────────────────────

@Composable
private fun BtnGhostSm(
    onClick: () -> Unit,
    enabled: Boolean,
    icon: ImageVector,
    label: String,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val bg = if (enabled && hovered) Color.White.copy(alpha = 0.05f) else Color.Transparent
    val fg = when {
        !enabled -> TextDisabled
        hovered -> TextPrimary
        else -> TextSecondary
    }
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = bg,
            contentColor = fg,
            disabledContentColor = TextDisabled,
        ),
        border = BorderStroke(1.dp, Color.Transparent),
        shape = RoundedCornerShape(Radii.Md),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        modifier = Modifier
            .defaultMinSize(minWidth = 0.dp, minHeight = 0.dp)
            .pointerHoverIcon(PointerIcon.Hand),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(6.dp))
        Text(text = label, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

// ── Helpers ──────────────────────────────────────────────────────────────────

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f Ko".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f Mo".format(mb)
    return "%.2f Go".format(mb / 1024.0)
}

/**
 * Format relatif/court selon l'âge :
 *  - < 60 s     → "à l'instant"
 *  - < 60 min   → "il y a Xmin"
 *  - même jour  → "HH:mm"
 *  - même année → "dd/MM HH:mm"
 *  - autre      → "dd/MM/yyyy"
 */
@Composable
private fun formatTimestamp(epochMillis: Long): String {
    val now = java.time.Instant.now()
    val ts = java.time.Instant.ofEpochMilli(epochMillis)
    val zone = java.time.ZoneId.systemDefault()
    val seconds = java.time.Duration.between(ts, now).seconds
    return when {
        seconds < 60 -> stringResource(Res.string.transfers_relative_just_now)
        seconds < 3600 -> stringResource(Res.string.statusbar_relative_minutes_ago, (seconds / 60).toInt())
        else -> {
            val dtNow = java.time.LocalDateTime.ofInstant(now, zone)
            val dt = java.time.LocalDateTime.ofInstant(ts, zone)
            val sameDay = dt.toLocalDate() == dtNow.toLocalDate()
            val sameYear = dt.year == dtNow.year
            val time = "%02d:%02d".format(dt.hour, dt.minute)
            when {
                sameDay -> time
                sameYear -> "%02d/%02d %s".format(dt.dayOfMonth, dt.monthValue, time)
                else -> "%02d/%02d/%04d".format(dt.dayOfMonth, dt.monthValue, dt.year)
            }
        }
    }
}
