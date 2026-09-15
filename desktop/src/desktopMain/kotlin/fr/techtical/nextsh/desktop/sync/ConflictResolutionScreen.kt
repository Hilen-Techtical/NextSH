// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sync

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.Lucide
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
import fr.techtical.nextsh.desktop.theme.SurfaceVariant
import fr.techtical.nextsh.desktop.theme.TextDisabled
import fr.techtical.nextsh.desktop.theme.TextPrimary
import fr.techtical.nextsh.desktop.theme.TextSecondary
import fr.techtical.nextsh.desktop.theme.White
import fr.techtical.nextsh.shared.core.sync.PendingConflict
import fr.techtical.nextsh.shared.ui.conflict.DecodedEntity
import fr.techtical.nextsh.shared.ui.conflict.decodeEntity
import fr.techtical.nextsh.shared.ui.conflict.diffFields
import fr.techtical.nextsh.shared.ui.conflict.displayName
import kotlinx.coroutines.launch
import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.action_cancel
import fr.techtical.nextsh.desktop.generated.resources.conflicts_action_ignore
import fr.techtical.nextsh.desktop.generated.resources.conflicts_apply_with_count
import fr.techtical.nextsh.desktop.generated.resources.conflicts_corrupted
import fr.techtical.nextsh.desktop.generated.resources.conflicts_detected_at
import fr.techtical.nextsh.desktop.generated.resources.conflicts_empty_hint
import fr.techtical.nextsh.desktop.generated.resources.conflicts_empty_subtitle
import fr.techtical.nextsh.desktop.generated.resources.conflicts_empty_title
import fr.techtical.nextsh.desktop.generated.resources.conflicts_keep_local
import fr.techtical.nextsh.desktop.generated.resources.conflicts_keep_remote
import fr.techtical.nextsh.desktop.generated.resources.conflicts_section_local
import fr.techtical.nextsh.desktop.generated.resources.conflicts_section_remote
import fr.techtical.nextsh.desktop.generated.resources.conflicts_title
import org.jetbrains.compose.resources.stringResource

/**
 * Résolution des conflits sync : refonte Phase 2.7.
 *
 * Layout : Column NearBlack → PageHeader "Conflits de synchronisation"
 * → grid responsive 1/2 cols (>= 1100 dp) → barre d'actions ancrée
 * en bas (Appliquer / Annuler) pour valider l'ensemble. Empty state
 * avec icône CircleCheck SuccessGreen quand aucun conflit.
 */
@Composable
fun ConflictResolutionScreen(onBack: () -> Unit) {
    val viewModel = remember { DesktopContainer.conflictResolutionViewModel }
    val conflicts by viewModel.conflicts.collectAsState()
    val resolution by viewModel.resolution.collectAsState()
    val scope = rememberCoroutineScope()

    val resolvedCount = resolution.size
    val hasAnyResolution = resolvedCount > 0

    val subtitle = when {
        conflicts.isEmpty() -> stringResource(Res.string.conflicts_empty_subtitle)
        conflicts.size == 1 -> "1 conflit à résoudre"
        else -> "${conflicts.size} conflits à résoudre"
    }

    Column(modifier = Modifier.fillMaxSize().background(NearBlack)) {
        PageHeader(title = stringResource(Res.string.conflicts_title), subtitle = subtitle)

        if (conflicts.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Lucide.CircleCheck,
                        contentDescription = null,
                        tint = SuccessGreen,
                        modifier = Modifier.size(64.dp),
                    )
                    Spacer(Modifier.height(Spacing.Md))
                    Text(
                        text = stringResource(Res.string.conflicts_empty_title),
                        color = TextPrimary,
                        fontFamily = SpaceGroteskFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp,
                    )
                    Spacer(Modifier.height(Spacing.Xs))
                    Text(
                        text = stringResource(Res.string.conflicts_empty_hint),
                        color = TextSecondary,
                        fontSize = 12.sp,
                    )
                }
            }
        } else {
            BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxSize()) {
                val columns = if (maxWidth >= 1100.dp) 2 else 1
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = Spacing.Xxl, vertical = Spacing.Xl),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Md),
                ) {
                    items(conflicts, key = { it.id }) { conflict ->
                        ConflictCard(
                            conflict = conflict,
                            choice = resolution[conflict.id],
                            onKeepLocal = { viewModel.keepLocal(conflict) },
                            onKeepRemote = { viewModel.keepRemote(conflict) },
                            onIgnore = { viewModel.ignoreConflict(conflict) },
                        )
                    }
                }
            }

            // ── Bottom action bar: sticky ───────────────────────────────────
            BottomActionBar(
                resolvedCount = resolvedCount,
                onApply = {
                    scope.launch {
                        val applied = viewModel.applyAll()
                        if (applied > 0) {
                            viewModel.forceSyncAfterResolve()
                            onBack()
                        }
                    }
                },
                onCancel = onBack,
                applyEnabled = hasAnyResolution,
            )
        }
    }
}

// ── Bottom action bar ────────────────────────────────────────────────────────

@Composable
private fun BottomActionBar(
    resolvedCount: Int,
    onApply: () -> Unit,
    onCancel: () -> Unit,
    applyEnabled: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Border1))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(NearBlack)
                .padding(horizontal = Spacing.Xxl, vertical = Spacing.Md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BtnGhost(onClick = onCancel, label = stringResource(Res.string.action_cancel))
            Spacer(Modifier.weight(1f))
            Button(
                onClick = onApply,
                enabled = applyEnabled,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Burgundy,
                    contentColor = White,
                    disabledContainerColor = Burgundy.copy(alpha = 0.4f),
                    disabledContentColor = White.copy(alpha = 0.6f),
                ),
                shape = RoundedCornerShape(Radii.Md),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
            ) {
                Icon(Lucide.Check, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(Spacing.Sm))
                Text(
                    text = stringResource(Res.string.conflicts_apply_with_count, resolvedCount),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun BtnGhost(onClick: () -> Unit, label: String) {
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
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        modifier = Modifier
            .defaultMinSize(minWidth = 0.dp, minHeight = 0.dp)
            .pointerHoverIcon(PointerIcon.Hand),
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

// ── ConflictCard ─────────────────────────────────────────────────────────────

@Composable
private fun ConflictCard(
    conflict: PendingConflict,
    choice: Boolean?,
    onKeepLocal: () -> Unit,
    onKeepRemote: () -> Unit,
    onIgnore: () -> Unit,
) {
    val localDecoded: DecodedEntity? = decodeEntity(conflict.entityType, conflict.localJson)
    val remoteDecoded: DecodedEntity? = decodeEntity(conflict.entityType, conflict.remoteJson)
    val isCorrupt = localDecoded == null && remoteDecoded == null

    val differingFields: Set<String> = if (localDecoded != null && remoteDecoded != null) {
        diffFields(localDecoded, remoteDecoded)
    } else emptySet()

    val entityName = localDecoded?.primaryLabel
        ?: remoteDecoded?.primaryLabel
        ?: conflict.entityId.take(12)
    val relTime = relativeTime(conflict.detectedAt)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface, RoundedCornerShape(Radii.Lg))
            .border(1.dp, Border1, RoundedCornerShape(Radii.Lg))
            .padding(Spacing.Lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.Md),
    ) {
        // ── Header ──────────────────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            EntityTypeBadge(label = conflict.entityType.displayName)
            Spacer(Modifier.width(Spacing.Sm))
            Text(
                text = entityName,
                color = TextPrimary,
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(Res.string.conflicts_detected_at, relTime),
                color = TextDisabled,
                fontSize = 11.sp,
            )
        }

        if (isCorrupt) {
            // ── Corrupt conflict ────────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Lucide.TriangleAlert,
                    contentDescription = null,
                    tint = ErrorRed,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(Spacing.Xs))
                Text(
                    text = stringResource(Res.string.conflicts_corrupted),
                    color = ErrorRed,
                    fontSize = 12.sp,
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                BtnDanger(onClick = onIgnore, label = stringResource(Res.string.conflicts_action_ignore))
            }
        } else {
            // ── Side-by-side comparison ─────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
            ) {
                EntitySummary(
                    title = stringResource(Res.string.conflicts_section_local),
                    entity = localDecoded,
                    differingFields = differingFields,
                    isSelected = choice == true,
                    onClick = onKeepLocal,
                    modifier = Modifier.weight(1f),
                )
                EntitySummary(
                    title = stringResource(Res.string.conflicts_section_remote),
                    entity = remoteDecoded,
                    differingFields = differingFields,
                    isSelected = choice == false,
                    onClick = onKeepRemote,
                    modifier = Modifier.weight(1f),
                )
            }

            // ── Choice buttons ──────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
            ) {
                ChoiceButton(
                    label = stringResource(Res.string.conflicts_keep_local),
                    selected = choice == true,
                    onClick = onKeepLocal,
                    modifier = Modifier.weight(1f),
                )
                ChoiceButton(
                    label = stringResource(Res.string.conflicts_keep_remote),
                    selected = choice == false,
                    onClick = onKeepRemote,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun EntityTypeBadge(label: String) {
    Box(
        modifier = Modifier
            .background(Gold.copy(alpha = 0.08f), RoundedCornerShape(3.dp))
            .border(1.dp, Gold.copy(alpha = 0.20f), RoundedCornerShape(3.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = label.uppercase(),
            color = GoldLight,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.5.sp,
        )
    }
}

@Composable
private fun ChoiceButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selected) {
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(containerColor = Burgundy, contentColor = White),
            shape = RoundedCornerShape(Radii.Md),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            modifier = modifier
                .defaultMinSize(minWidth = 0.dp, minHeight = 0.dp)
                .pointerHoverIcon(PointerIcon.Hand),
        ) {
            Icon(Lucide.Check, contentDescription = null, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
            border = BorderStroke(1.dp, Border2),
            shape = RoundedCornerShape(Radii.Md),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            modifier = modifier
                .defaultMinSize(minWidth = 0.dp, minHeight = 0.dp)
                .pointerHoverIcon(PointerIcon.Hand),
        ) {
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun BtnDanger(onClick: () -> Unit, label: String) {
    OutlinedButton(
        onClick = onClick,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed),
        border = BorderStroke(1.dp, ErrorRed.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(Radii.Md),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        modifier = Modifier
            .defaultMinSize(minWidth = 0.dp, minHeight = 0.dp)
            .pointerHoverIcon(PointerIcon.Hand),
    ) {
        Icon(Lucide.X, contentDescription = null, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

// ── EntitySummary ────────────────────────────────────────────────────────────

@Composable
private fun EntitySummary(
    title: String,
    entity: DecodedEntity?,
    differingFields: Set<String>,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor = if (isSelected) Burgundy else Border1
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(Radii.Md))
            .background(SurfaceVariant)
            .border(1.dp, borderColor, RoundedCornerShape(Radii.Md))
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(Spacing.Md),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = title.uppercase(),
            color = if (isSelected) GoldLight else TextDisabled,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.5.sp,
        )
        Spacer(Modifier.height(2.dp))

        if (entity == null) {
            Text("-", color = TextSecondary, fontSize = 12.sp)
        } else {
            entity.fields.forEach { (fieldName, value) ->
                val isDiffering = fieldName in differingFields
                FieldRow(
                    fieldName = fieldName,
                    value = value,
                    highlight = isDiffering,
                )
            }
        }
    }
}

@Composable
private fun FieldRow(fieldName: String, value: String, highlight: Boolean) {
    val bg = if (highlight) Burgundy.copy(alpha = 0.18f) else Color.Transparent
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(3.dp))
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        Text(fieldName, color = TextDisabled, fontSize = 10.sp)
        Text(
            text = value,
            color = TextPrimary,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
