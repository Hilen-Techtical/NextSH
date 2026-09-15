// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.snippets

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Code
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2
import fr.techtical.nextsh.desktop.components.ConfirmDeleteDialog
import fr.techtical.nextsh.desktop.components.PageHeader
import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.action_back
import fr.techtical.nextsh.desktop.generated.resources.snippets_action_add
import fr.techtical.nextsh.desktop.generated.resources.snippets_delete_body
import fr.techtical.nextsh.desktop.generated.resources.snippets_delete_title
import fr.techtical.nextsh.desktop.generated.resources.snippets_empty_hint
import fr.techtical.nextsh.desktop.generated.resources.snippets_empty_title
import fr.techtical.nextsh.desktop.generated.resources.snippets_filter_all
import fr.techtical.nextsh.desktop.generated.resources.snippets_message_deleted
import fr.techtical.nextsh.desktop.generated.resources.snippets_message_saved
import fr.techtical.nextsh.desktop.generated.resources.snippets_error_unknown
import fr.techtical.nextsh.desktop.generated.resources.snippets_subtitle
import fr.techtical.nextsh.desktop.generated.resources.snippets_title
import fr.techtical.nextsh.desktop.generated.resources.snippets_tooltip_delete
import fr.techtical.nextsh.desktop.generated.resources.snippets_tooltip_edit
import fr.techtical.nextsh.desktop.theme.Border1
import fr.techtical.nextsh.desktop.theme.Border2
import fr.techtical.nextsh.desktop.theme.Burgundy
import fr.techtical.nextsh.desktop.theme.ErrorRed
import fr.techtical.nextsh.desktop.theme.Gold
import fr.techtical.nextsh.desktop.theme.GoldLight
import fr.techtical.nextsh.desktop.theme.GoldMuted
import fr.techtical.nextsh.desktop.theme.InfoBlue
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
import fr.techtical.nextsh.shared.domain.model.Snippet
import org.jetbrains.compose.resources.stringResource

/**
 * Écran de gestion CRUD des snippets, Desktop.
 *
 * Layout : Column NearBlack → PageHeader → StatusBanner (si message)
 * → scroll : filter chips + FlowRow de SnippetCard 400dp.
 * Dialog d'édition : [SnippetEditDialog] OS-natif (DialogWindow).
 *
 * Reçoit [onBack] pour retourner à HostList.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SnippetListScreen(onBack: () -> Unit) {
    val viewModel = remember { SnippetListViewModel() }
    val uiState by viewModel.uiState.collectAsState()

    // Auto-dismiss success messages after 2.5 s, errors stay until user acts
    LaunchedEffect(uiState.successMessage) {
        if (uiState.successMessage != null) {
            delay(2500)
            viewModel.clearMessage()
        }
    }

    var snippetToDelete by remember { mutableStateOf<Snippet?>(null) }

    val savedMsg = stringResource(Res.string.snippets_message_saved)
    val deletedMsg = stringResource(Res.string.snippets_message_deleted)
    val errorUnknownMsg = stringResource(Res.string.snippets_error_unknown)

    Column(modifier = Modifier.fillMaxSize().background(NearBlack)) {
        PageHeader(
            title = stringResource(Res.string.snippets_title),
            subtitle = stringResource(Res.string.snippets_subtitle),
            actions = {
                BtnGhostSm(
                    onClick = onBack,
                    icon = Lucide.ArrowLeft,
                    label = stringResource(Res.string.action_back),
                )
                Button(
                    onClick = { viewModel.startEditing(null) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Burgundy,
                        contentColor = White,
                    ),
                    shape = RoundedCornerShape(Radii.Sm),
                    contentPadding = PaddingValues(horizontal = Spacing.Md, vertical = Spacing.Xs),
                    modifier = Modifier
                        .defaultMinSize(minWidth = 0.dp, minHeight = 0.dp)
                        .pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Icon(Lucide.Plus, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(Spacing.Xs))
                    Text(text = stringResource(Res.string.snippets_action_add), fontSize = 13.sp)
                }
            },
        )

        // ── Status banner ─────────────────────────────────────────────────────
        val msg = uiState.successMessage ?: uiState.error
        val isError = uiState.error != null
        if (msg != null) {
            val displayMsg = when (msg) {
                "snippets_message_saved" -> savedMsg
                "snippets_message_deleted" -> deletedMsg
                "snippets_error_unknown" -> errorUnknownMsg
                else -> msg
            }
            StatusBanner(text = displayMsg, isError = isError, onDismiss = { viewModel.clearMessage() })
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.Xxl, vertical = Spacing.Xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.Xl),
        ) {
            // ── Filter chips ─────────────────────────────────────────────────
            if (uiState.categories.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
                ) {
                    // "Tous" chip
                    FilterChip(
                        label = stringResource(Res.string.snippets_filter_all),
                        selected = uiState.selectedCategory == null,
                        onClick = { viewModel.filterByCategory(null) },
                    )
                    uiState.categories.forEach { cat ->
                        FilterChip(
                            label = cat,
                            selected = uiState.selectedCategory == cat,
                            onClick = { viewModel.filterByCategory(cat) },
                        )
                    }
                }
            }

            // ── Snippet cards ─────────────────────────────────────────────────
            if (uiState.snippets.isEmpty()) {
                EmptySnippetsState()
            } else {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
                ) {
                    uiState.snippets.forEach { snippet ->
                        val hostLabel = uiState.hosts.find { it.id == snippet.hostId }?.label
                        SnippetCard(
                            snippet = snippet,
                            hostLabel = hostLabel,
                            onEdit = { viewModel.startEditing(snippet) },
                            onDelete = { snippetToDelete = snippet },
                            modifier = Modifier.width(400.dp),
                        )
                    }
                }
            }
        }
    }

    // ── Edit / Create dialog ──────────────────────────────────────────────────
    if (uiState.isEditing) {
        SnippetEditDialog(
            snippet = uiState.editingSnippet,
            hosts = uiState.hosts,
            onDismiss = { viewModel.cancelEditing() },
            onSave = { label, command, category, hostId ->
                viewModel.save(
                    label = label,
                    command = command,
                    category = category,
                    hostId = hostId,
                    existingId = uiState.editingSnippet?.id,
                )
            },
        )
    }

    // ── Delete confirmation dialog ────────────────────────────────────────────
    val toDelete = snippetToDelete
    if (toDelete != null) {
        ConfirmDeleteDialog(
            title = stringResource(Res.string.snippets_delete_title),
            message = stringResource(Res.string.snippets_delete_body),
            onConfirm = {
                viewModel.delete(toDelete.id)
                snippetToDelete = null
            },
            onDismiss = { snippetToDelete = null },
        )
    }
}

// ── Status banner ────────────────────────────────────────────────────────────

@Composable
private fun StatusBanner(text: String, isError: Boolean, onDismiss: () -> Unit) {
    val color = if (isError) ErrorRed else SuccessGreen
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.08f))
            .padding(horizontal = Spacing.Xxl, vertical = Spacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(color, RoundedCornerShape(9999.dp)),
        )
        Spacer(Modifier.width(Spacing.Sm))
        Text(text = text, color = color, fontSize = 12.sp, modifier = Modifier.weight(1f))
    }
}

// ── Filter chip ───────────────────────────────────────────────────────────────

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()

    if (selected) {
        Button(
            onClick = onClick,
            interactionSource = interactionSource,
            colors = ButtonDefaults.buttonColors(
                containerColor = Burgundy,
                contentColor = White,
            ),
            shape = RoundedCornerShape(Radii.Md),
            contentPadding = PaddingValues(horizontal = Spacing.Md, vertical = 4.dp),
            modifier = Modifier
                .defaultMinSize(minWidth = 0.dp, minHeight = 0.dp)
                .pointerHoverIcon(PointerIcon.Hand),
        ) {
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    } else {
        val borderColor = if (hovered) Border2 else Border1
        OutlinedButton(
            onClick = onClick,
            interactionSource = interactionSource,
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = if (hovered) Color.White.copy(alpha = 0.04f) else Color.Transparent,
                contentColor = TextSecondary,
            ),
            border = BorderStroke(1.dp, borderColor),
            shape = RoundedCornerShape(Radii.Md),
            contentPadding = PaddingValues(horizontal = Spacing.Md, vertical = 4.dp),
            modifier = Modifier
                .defaultMinSize(minWidth = 0.dp, minHeight = 0.dp)
                .pointerHoverIcon(PointerIcon.Hand),
        ) {
            Text(label, fontSize = 12.sp)
        }
    }
}

// ── Empty state ───────────────────────────────────────────────────────────────

@Composable
private fun EmptySnippetsState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface, RoundedCornerShape(Radii.Lg))
            .border(1.dp, Border1, RoundedCornerShape(Radii.Lg))
            .padding(vertical = 40.dp, horizontal = Spacing.Xl),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Lucide.Code,
                contentDescription = null,
                tint = GoldMuted,
                modifier = Modifier.size(64.dp),
            )
            Spacer(Modifier.height(Spacing.Md))
            Text(
                stringResource(Res.string.snippets_empty_title),
                color = TextPrimary,
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(Res.string.snippets_empty_hint),
                color = TextSecondary,
                fontSize = 11.sp,
            )
        }
    }
}

// ── Snippet card ─────────────────────────────────────────────────────────────

@Composable
private fun SnippetCard(
    snippet: Snippet,
    hostLabel: String?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(Surface, RoundedCornerShape(Radii.Lg))
            .border(1.dp, Border1, RoundedCornerShape(Radii.Lg))
            .padding(Spacing.Md),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
            verticalAlignment = Alignment.Top,
        ) {
            // Icon wrap
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(Gold.copy(alpha = 0.10f), RoundedCornerShape(Radii.Md)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Lucide.Code, contentDescription = null, tint = Gold, modifier = Modifier.size(18.dp))
            }

            // Content
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = snippet.label,
                    color = TextPrimary,
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = snippet.command,
                    color = TextSecondary,
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // Badges
                if (snippet.category != null || hostLabel != null) {
                    Spacer(Modifier.height(Spacing.Xs))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Xs)) {
                        snippet.category?.let { cat ->
                            CategoryBadge(cat)
                        }
                        hostLabel?.let { host ->
                            HostBadge(host)
                        }
                    }
                }
            }

            // Action buttons
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                SnippetIconBtn(
                    icon = Lucide.Pencil,
                    tint = GoldMuted,
                    tooltip = stringResource(Res.string.snippets_tooltip_edit),
                    onClick = onEdit,
                )
                SnippetIconBtn(
                    icon = Lucide.Trash2,
                    tint = ErrorRed,
                    tooltip = stringResource(Res.string.snippets_tooltip_delete),
                    onClick = onDelete,
                )
            }
        }
    }
}

@Composable
private fun CategoryBadge(name: String) {
    Box(
        modifier = Modifier
            .background(Gold.copy(alpha = 0.10f), RoundedCornerShape(3.dp))
            .border(1.dp, Gold.copy(alpha = 0.20f), RoundedCornerShape(3.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = name,
            color = GoldLight,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.5.sp,
        )
    }
}

@Composable
private fun HostBadge(name: String) {
    Box(
        modifier = Modifier
            .background(InfoBlue.copy(alpha = 0.12f), RoundedCornerShape(3.dp))
            .border(1.dp, InfoBlue.copy(alpha = 0.25f), RoundedCornerShape(3.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = name,
            color = InfoBlue,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.5.sp,
        )
    }
}

@Composable
private fun SnippetIconBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    tooltip: String,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val bg = if (hovered) tint.copy(alpha = 0.10f) else Color.Transparent

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
            .size(28.dp)
            .defaultMinSize(minWidth = 0.dp, minHeight = 0.dp)
            .pointerHoverIcon(PointerIcon.Hand),
    ) {
        Icon(icon, contentDescription = tooltip, modifier = Modifier.size(13.dp))
    }
}

// ── Buttons ──────────────────────────────────────────────────────────────────

/** `.btn-ghost .btn-sm` pour l'action "Retour" du PageHeader. */
@Composable
private fun BtnGhostSm(onClick: () -> Unit, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
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
