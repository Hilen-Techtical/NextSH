// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sync

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.CheckCheck
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.TriangleAlert
import fr.techtical.nextsh.R
import fr.techtical.nextsh.shared.core.sync.PendingConflict
import fr.techtical.nextsh.shared.core.sync.SyncableEntityType
import fr.techtical.nextsh.shared.ui.conflict.ConflictField
import fr.techtical.nextsh.shared.ui.conflict.ConflictTitle
import fr.techtical.nextsh.shared.ui.conflict.TypedEntity
import fr.techtical.nextsh.shared.ui.conflict.decodeTypedEntity
import fr.techtical.nextsh.shared.ui.conflict.diffTypedFields
import fr.techtical.nextsh.ui.components.NextShMessage
import fr.techtical.nextsh.ui.components.relativeTimeLabel
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
import fr.techtical.nextsh.ui.theme.Surface
import fr.techtical.nextsh.ui.theme.SurfaceVariant
import fr.techtical.nextsh.ui.theme.TextDisabled
import fr.techtical.nextsh.ui.theme.TextPrimary
import fr.techtical.nextsh.ui.theme.TextSecondary
import fr.techtical.nextsh.ui.theme.White
import kotlinx.coroutines.launch

/**
 * ConflictResolutionScreen : refonte Phase 3.1 (DA Techtical portée
 * du Desktop).
 *
 * - TopAppBar Space Grotesk + subtitle compteur conflits
 * - LazyColumn de cards Surface/Border1/Radii.Lg avec EntityTypeBadge
 *   en haut à droite + label + relative time
 * - EntitySummary cards SurfaceVariant/Border1 (border Burgundy
 *   1dp si choix sélectionné)
 * - Bottom bar sticky : bouton Appliquer (n) Burgundy primary,
 *   séparé du body par 1dp Border1
 * - Empty state restylé : Lucide.CheckCheck Gold + Space Grotesk
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictResolutionScreen(
    onBack: () -> Unit,
    viewModel: ConflictResolutionViewModel = hiltViewModel(),
) {
    val pendingConflicts by viewModel.conflicts.collectAsState()
    // One card per element: the list is keyed by it, and two syncs racing on the same
    // element could leave two markers, which a lazy list must never see as duplicate keys.
    val conflicts = remember(pendingConflicts) { pendingConflicts.distinctBy { it.listKey() } }
    val resolution by viewModel.resolution.collectAsState()
    val applying by viewModel.applying.collectAsState()
    val scope = rememberCoroutineScope()

    // Shown when some decided conflicts could not be applied; the screen then stays open.
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val applyFailedMessage = stringResource(R.string.conflict_apply_failed)

    // Counted over the conflicts on screen with a choice valid for the versions shown: a
    // choice kept for an element no longer in conflict, or made on other versions, must
    // not inflate the Apply counter.
    val resolvedCount = conflicts.count { resolution.choiceFor(it) != null }
    val hasAnyResolution = resolvedCount > 0

    // The zero case is its own sentence: French plural "one" covers 0 and 1, which would
    // read "0 conflit à résoudre".
    val subtitle = if (conflicts.isEmpty()) {
        stringResource(R.string.conflict_subtitle_resolved)
    } else {
        pluralStringResource(R.plurals.conflict_subtitle_pending, conflicts.size, conflicts.size)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.title_conflict_resolution),
                            color = TextPrimary,
                            fontFamily = SpaceGroteskFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 20.sp,
                        )
                        Text(
                            text = subtitle,
                            color = TextDisabled,
                            fontSize = 12.sp,
                        )
                    }
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
        bottomBar = {
            Column {
                NextShMessage(
                    message = errorMessage,
                    isError = true,
                    onDismiss = { errorMessage = null },
                    modifier = Modifier.padding(horizontal = Spacing.Md),
                )
                if (conflicts.isNotEmpty()) {
                    BottomActionBar(
                        resolvedCount = resolvedCount,
                        enabled = hasAnyResolution && !applying,
                        onApply = {
                            scope.launch {
                                val outcome = viewModel.applyAll()
                                // Whatever was resolved must reach the peer, even if some
                                // other conflicts failed.
                                if (outcome.resolved > 0) viewModel.forceSyncAfterResolve()
                                if (outcome.failed > 0) {
                                    errorMessage = applyFailedMessage
                                } else if (outcome.resolved > 0) {
                                    onBack()
                                }
                            }
                        },
                        onCancel = onBack,
                    )
                }
            }
        },
        containerColor = NearBlack,
    ) { padding ->
        if (conflicts.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = Spacing.Lg, vertical = Spacing.Md),
                verticalArrangement = Arrangement.spacedBy(Spacing.Md),
            ) {
                // Keyed by the element, not by the random marker id, so a re-detection
                // does not recreate the card.
                items(conflicts, key = { it.listKey() }) { conflict ->
                    ConflictCard(
                        conflict = conflict,
                        choice = resolution.choiceFor(conflict),
                        onKeepLocal = { viewModel.keepLocal(conflict) },
                        onKeepRemote = { viewModel.keepRemote(conflict) },
                        onIgnore = { viewModel.ignoreConflict(conflict) },
                    )
                }
            }
        }
    }
}

// ── Bottom action bar ────────────────────────────────────────────────────────

@Composable
private fun BottomActionBar(
    resolvedCount: Int,
    enabled: Boolean,
    onApply: () -> Unit,
    onCancel: () -> Unit,
) {
    Column {
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Border1))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(NearBlack)
                .padding(horizontal = Spacing.Lg, vertical = Spacing.Md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BtnGhostSm(onClick = onCancel, label = stringResource(R.string.action_cancel))
            Spacer(Modifier.weight(1f))
            Button(
                onClick = onApply,
                enabled = enabled,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Burgundy,
                    contentColor = White,
                    disabledContainerColor = Burgundy.copy(alpha = 0.4f),
                    disabledContentColor = White.copy(alpha = 0.6f),
                ),
                shape = RoundedCornerShape(Radii.Md),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Icon(Lucide.Check, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(Spacing.Sm))
                Text(
                    text = stringResource(R.string.action_apply_resolutions, resolvedCount),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
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
    val localDecoded: TypedEntity? = decodeTypedEntity(conflict.entityType, conflict.localJson)
    val remoteDecoded: TypedEntity? = decodeTypedEntity(conflict.entityType, conflict.remoteJson)
    // Either side unreadable: the user must always have a way out, so Ignore is offered.
    val isCorrupt = localDecoded == null || remoteDecoded == null
    // Both choices read the remote version (keeping local needs its clock): without it
    // neither can succeed, so only Ignore is left. An unreadable local snapshot alone does
    // not block them: keeping local re-stamps the current row, not the snapshot.
    val canChoose = remoteDecoded != null

    val differingFields: Set<ConflictField> = if (localDecoded != null && remoteDecoded != null) {
        diffTypedFields(localDecoded, remoteDecoded)
    } else emptySet()

    // An item deleted on one side only is titled by the other side (local first, then remote): the
    // "(deleted)" title is kept only when no readable side has a name. Both versions unreadable: the name
    // of the kind of item ("Hôte", "Clé SSH"), not a fragment of its identifier, which means nothing to
    // the user.
    val readable = listOfNotNull(localDecoded, remoteDecoded)
    val entityName = (readable.firstOrNull { it.title !is ConflictTitle.Deleted } ?: readable.firstOrNull())
        ?.let { conflictTitleText(it.title) }
        ?: stringResource(conflictEntityLabelRes(conflict.entityType))

    // From the app's own resources, not DateUtils: that one follows the system language. Past a week the
    // text is a date, which reads "Detected on <date>" and not "Detected <date>".
    val detected = relativeTimeLabel(conflict.detectedAt)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface, RoundedCornerShape(Radii.Lg))
            .border(1.dp, Border1, RoundedCornerShape(Radii.Lg))
            .padding(Spacing.Md),
        verticalArrangement = Arrangement.spacedBy(Spacing.Md),
    ) {
        // ── Header ──────────────────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            EntityTypeBadge(conflict.entityType)
            Spacer(Modifier.width(Spacing.Sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entityName,
                    color = TextPrimary,
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        if (detected.isDate) R.string.conflict_detected_on else R.string.conflict_detected_at,
                        detected.text,
                    ),
                    color = TextDisabled,
                    fontSize = 11.sp,
                )
            }
        }

        if (isCorrupt) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
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
                    text = stringResource(R.string.conflict_corrupted),
                    color = ErrorRed,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (canChoose) {
            // ── Side-by-side comparison ──────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
            ) {
                EntitySummary(
                    title = stringResource(R.string.conflict_local_label),
                    entity = localDecoded,
                    differingFields = differingFields,
                    selected = choice == true,
                    onClick = onKeepLocal,
                    modifier = Modifier.weight(1f),
                )
                EntitySummary(
                    title = stringResource(R.string.conflict_remote_label),
                    entity = remoteDecoded,
                    differingFields = differingFields,
                    selected = choice == false,
                    onClick = onKeepRemote,
                    modifier = Modifier.weight(1f),
                )
            }

            // ── Choice buttons ───────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
            ) {
                ChoiceButton(
                    label = stringResource(R.string.action_keep_local),
                    selected = choice == true,
                    onClick = onKeepLocal,
                    modifier = Modifier.weight(1f),
                )
                ChoiceButton(
                    label = stringResource(R.string.action_keep_remote),
                    selected = choice == false,
                    onClick = onKeepRemote,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (isCorrupt) {
            BtnGhostSm(
                onClick = onIgnore,
                label = stringResource(R.string.action_ignore_conflict),
                isDanger = true,
            )
        }
    }
}

// ── EntityTypeBadge ──────────────────────────────────────────────────────────

@Composable
private fun EntityTypeBadge(type: SyncableEntityType) {
    Box(
        modifier = Modifier
            .background(Gold.copy(alpha = 0.08f), RoundedCornerShape(Radii.Sm))
            .border(1.dp, Gold.copy(alpha = 0.20f), RoundedCornerShape(Radii.Sm))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = stringResource(conflictEntityLabelRes(type)).uppercase(),
            color = GoldLight,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.5.sp,
        )
    }
}

// ── EntitySummary ────────────────────────────────────────────────────────────

@Composable
private fun EntitySummary(
    title: String,
    entity: TypedEntity?,
    differingFields: Set<ConflictField>,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor = if (selected) Burgundy else Border1
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(Radii.Md))
            .background(SurfaceVariant.copy(alpha = 0.5f))
            .border(1.dp, borderColor, RoundedCornerShape(Radii.Md))
            .clickable(onClick = onClick)
            .padding(Spacing.Sm),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = title.uppercase(),
            color = if (selected) GoldLight else GoldMuted,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.6.sp,
        )
        if (entity == null) {
            Text(text = CONFLICT_NO_VALUE, color = TextDisabled, fontSize = 12.sp)
        } else {
            entity.fields.forEach { row ->
                FieldRow(
                    fieldName = stringResource(conflictFieldLabelRes(row.field)),
                    value = conflictValueText(row.value),
                    highlight = row.field in differingFields,
                )
            }
        }
    }
}

@Composable
private fun FieldRow(
    fieldName: String,
    value: String,
    highlight: Boolean,
) {
    // Surlignage Burgundy@0.18 pour les valeurs différentes, parité Desktop.
    // Fond uniforme pour rendre le diff lisible d'un coup d'œil ; les
    // couleurs de texte restent constantes (sinon double-encodage du signal
    // qui dilue l'effet visuel).
    val bg = if (highlight) Burgundy.copy(alpha = 0.18f) else Color.Transparent
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(3.dp))
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        Text(
            text = fieldName.uppercase(),
            color = TextDisabled,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 9.sp,
            letterSpacing = 0.5.sp,
        )
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

// ── ChoiceButton ─────────────────────────────────────────────────────────────

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
            colors = ButtonDefaults.buttonColors(
                containerColor = Burgundy,
                contentColor = White,
            ),
            shape = RoundedCornerShape(Radii.Md),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
            modifier = modifier.defaultMinSize(minWidth = 0.dp, minHeight = 0.dp),
        ) {
            Icon(Lucide.Check, contentDescription = null, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = Color.Transparent,
                contentColor = TextPrimary,
            ),
            border = BorderStroke(1.dp, Border2),
            shape = RoundedCornerShape(Radii.Md),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
            modifier = modifier.defaultMinSize(minWidth = 0.dp, minHeight = 0.dp),
        ) {
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ── Empty state ──────────────────────────────────────────────────────────────

@Composable
private fun EmptyState() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = Spacing.Xl),
    ) {
        Icon(
            Lucide.CheckCheck,
            contentDescription = null,
            tint = Gold,
            modifier = Modifier.size(64.dp),
        )
        Spacer(Modifier.height(Spacing.Md))
        Text(
            text = stringResource(R.string.conflict_empty_title),
            color = TextPrimary,
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp,
        )
        Spacer(Modifier.height(Spacing.Xs))
        Text(
            text = stringResource(R.string.conflict_empty),
            color = TextSecondary,
            fontSize = 12.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

// ── Boutons ─────────────────────────────────────────────────────────────────

@Composable
private fun BtnGhostSm(
    onClick: () -> Unit,
    label: String,
    isDanger: Boolean = false,
) {
    val fg = if (isDanger) ErrorRed else TextSecondary
    OutlinedButton(
        onClick = onClick,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Color.Transparent,
            contentColor = fg,
        ),
        border = BorderStroke(1.dp, Color.Transparent),
        shape = RoundedCornerShape(Radii.Md),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        modifier = Modifier.defaultMinSize(minWidth = 0.dp, minHeight = 0.dp),
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

