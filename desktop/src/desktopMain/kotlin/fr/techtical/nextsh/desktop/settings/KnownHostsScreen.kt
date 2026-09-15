// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.settings

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
import com.composables.icons.lucide.ShieldCheck
import com.composables.icons.lucide.Trash2
import fr.techtical.nextsh.desktop.components.PageHeader
import fr.techtical.nextsh.desktop.core.ssh.KnownHostEntry
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
import fr.techtical.nextsh.desktop.theme.Surface
import fr.techtical.nextsh.desktop.theme.TextDisabled
import fr.techtical.nextsh.desktop.theme.TextPrimary
import fr.techtical.nextsh.desktop.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.action_cancel
import fr.techtical.nextsh.desktop.generated.resources.known_hosts_action_back
import fr.techtical.nextsh.desktop.generated.resources.known_hosts_action_clear_all
import fr.techtical.nextsh.desktop.generated.resources.known_hosts_action_remove
import fr.techtical.nextsh.desktop.generated.resources.known_hosts_added_on
import fr.techtical.nextsh.desktop.generated.resources.known_hosts_clear_all_body
import fr.techtical.nextsh.desktop.generated.resources.known_hosts_clear_all_title
import fr.techtical.nextsh.desktop.generated.resources.known_hosts_delete_single_body
import fr.techtical.nextsh.desktop.generated.resources.known_hosts_delete_single_title
import fr.techtical.nextsh.desktop.generated.resources.known_hosts_empty_hint
import fr.techtical.nextsh.desktop.generated.resources.known_hosts_empty_title
import fr.techtical.nextsh.desktop.generated.resources.known_hosts_subtitle_empty
import fr.techtical.nextsh.desktop.generated.resources.known_hosts_title
import org.jetbrains.compose.resources.stringResource

/**
 * Écran de gestion des hôtes connus, refonte Phase 2.8.
 *
 * Layout : Column NearBlack → PageHeader "Hôtes connus" + actions
 * (`Retour` ghost + `Tout effacer` danger conditionnel) → grid
 * responsive 1/2 cols (≥ 800 dp). Pas de Scaffold/TopAppBar legacy.
 */
@Composable
fun KnownHostsScreen(onBack: () -> Unit) {
    val viewModel = remember { KnownHostsViewModel() }
    val entries by viewModel.entries.collectAsState()
    var pendingDelete by remember { mutableStateOf<KnownHostEntry?>(null) }
    var showClearAllDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.refresh() }

    val subtitle = when {
        entries.isEmpty() -> stringResource(Res.string.known_hosts_subtitle_empty)
        entries.size == 1 -> "1 empreinte enregistrée"
        else -> "${entries.size} empreintes enregistrées"
    }

    Column(modifier = Modifier.fillMaxSize().background(NearBlack)) {
        PageHeader(
            title = stringResource(Res.string.known_hosts_title),
            subtitle = subtitle,
            actions = {
                BtnGhostSm(
                    onClick = onBack,
                    icon = Lucide.ArrowLeft,
                    label = stringResource(Res.string.known_hosts_action_back),
                )
                if (entries.isNotEmpty()) {
                    Spacer(Modifier.width(Spacing.Sm))
                    BtnDangerSm(
                        onClick = { showClearAllDialog = true },
                        icon = Lucide.Trash2,
                        label = stringResource(Res.string.known_hosts_action_clear_all),
                    )
                }
            },
        )

        if (entries.isEmpty()) {
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
                    items(entries, key = { it.hostPort }) { entry ->
                        KnownHostCard(entry = entry, onDelete = { pendingDelete = entry })
                    }
                }
            }
        }
    }

    val toDelete = pendingDelete
    if (toDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(Res.string.known_hosts_delete_single_title), color = TextPrimary) },
            text = {
                Text(
                    stringResource(Res.string.known_hosts_delete_single_body, toDelete.hostPort),
                    color = TextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeEntry(toDelete.hostPort)
                    pendingDelete = null
                }) { Text(stringResource(Res.string.known_hosts_action_remove), color = ErrorRed) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(Res.string.action_cancel), color = TextSecondary) }
            },
            containerColor = Surface,
        )
    }

    if (showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllDialog = false },
            title = { Text(stringResource(Res.string.known_hosts_clear_all_title), color = TextPrimary) },
            text = {
                Text(
                    stringResource(Res.string.known_hosts_clear_all_body),
                    color = TextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearAll()
                    showClearAllDialog = false
                }) { Text(stringResource(Res.string.known_hosts_action_clear_all), color = ErrorRed) }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllDialog = false }) { Text(stringResource(Res.string.action_cancel), color = TextSecondary) }
            },
            containerColor = Surface,
        )
    }
}

// ── Card ─────────────────────────────────────────────────────────────────────

@Composable
private fun KnownHostCard(entry: KnownHostEntry, onDelete: () -> Unit) {
    val dateStr = remember(entry.addedAt) {
        SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(entry.addedAt))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface, RoundedCornerShape(Radii.Lg))
            .border(1.dp, Border1, RoundedCornerShape(Radii.Lg))
            .padding(horizontal = Spacing.Lg, vertical = Spacing.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(Gold.copy(alpha = 0.08f), RoundedCornerShape(Radii.Md)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Lucide.ShieldCheck, contentDescription = null, tint = Gold, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(Spacing.Md))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.hostPort,
                    color = TextPrimary,
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(Spacing.Xs))
                AlgorithmBadge(entry.algorithm)
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = entry.fingerprint,
                color = TextSecondary,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(Res.string.known_hosts_added_on, dateStr),
                color = TextDisabled,
                fontSize = 11.sp,
            )
        }
        Spacer(Modifier.width(Spacing.Sm))
        BtnIconDanger(
            icon = Lucide.Trash2,
            tooltip = stringResource(Res.string.known_hosts_action_remove),
            onClick = onDelete,
        )
    }
}

@Composable
private fun AlgorithmBadge(algorithm: String) {
    Box(
        modifier = Modifier
            .background(Gold.copy(alpha = 0.08f), RoundedCornerShape(3.dp))
            .border(1.dp, Gold.copy(alpha = 0.20f), RoundedCornerShape(3.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(
            text = algorithm,
            color = GoldLight,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.5.sp,
        )
    }
}

// ── Empty state ──────────────────────────────────────────────────────────────

@Composable
private fun EmptyState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Lucide.ShieldCheck,
                contentDescription = null,
                tint = GoldMuted,
                modifier = Modifier.size(64.dp),
            )
            Spacer(Modifier.height(Spacing.Md))
            Text(
                text = stringResource(Res.string.known_hosts_empty_title),
                color = TextPrimary,
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
            )
            Spacer(Modifier.height(Spacing.Xs))
            Text(
                text = stringResource(Res.string.known_hosts_empty_hint),
                color = TextSecondary,
                fontSize = 12.sp,
            )
        }
    }
}

// ── Buttons ──────────────────────────────────────────────────────────────────

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
private fun BtnDangerSm(onClick: () -> Unit, icon: ImageVector, label: String) {
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
        Icon(icon, contentDescription = null, tint = ErrorRed, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun BtnIconDanger(icon: ImageVector, tooltip: String, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val bg = if (hovered) ErrorRed.copy(alpha = 0.10f) else Color.Transparent
    val tint = if (hovered) ErrorRed else ErrorRed.copy(alpha = 0.7f)
    OutlinedButton(
        onClick = onClick,
        interactionSource = interactionSource,
        colors = ButtonDefaults.outlinedButtonColors(containerColor = bg, contentColor = tint),
        border = BorderStroke(1.dp, Color.Transparent),
        shape = RoundedCornerShape(Radii.Md),
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier
            .size(28.dp)
            .defaultMinSize(minWidth = 0.dp, minHeight = 0.dp)
            .pointerHoverIcon(PointerIcon.Hand),
    ) {
        Icon(icon, contentDescription = tooltip, tint = tint, modifier = Modifier.size(13.dp))
    }
}
