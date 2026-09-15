// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.snippets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.Code
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2
import fr.techtical.nextsh.R
import fr.techtical.nextsh.domain.model.Host
import fr.techtical.nextsh.domain.model.Snippet
import fr.techtical.nextsh.ui.components.ConfirmDeleteDialog
import fr.techtical.nextsh.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnippetListScreen(
    onBack: () -> Unit = {},
    viewModel: SnippetViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val hosts by viewModel.hosts.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.error, uiState.successMessage) {
        val msg = uiState.error ?: uiState.successMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(message = msg, duration = SnackbarDuration.Short)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text       = stringResource(R.string.title_snippets),
                            fontFamily = SpaceGroteskFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize   = 20.sp,
                            color      = TextPrimary,
                        )
                        val total = uiState.snippets.size
                        if (total > 0) {
                            Text(
                                text       = if (total == 1) "1 snippet" else "$total snippets",
                                fontFamily = JetBrainsMonoFamily,
                                fontSize   = 11.sp,
                                color      = TextDisabled,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Lucide.ArrowLeft,
                            contentDescription = stringResource(R.string.action_back),
                            tint     = TextSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                },
                actions = {
                    Box(
                        modifier = Modifier
                            .padding(end = Spacing.Sm)
                            .size(40.dp)
                            .clip(RoundedCornerShape(Radii.Md))
                            .background(Burgundy, RoundedCornerShape(Radii.Md))
                            .clickable { viewModel.startEditing(null) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Lucide.Plus,
                            contentDescription = stringResource(R.string.action_add_snippet),
                            tint = White,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = NearBlack),
            )
        },
        containerColor = NearBlack,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (uiState.categories.isNotEmpty()) {
                LazyRow(
                    modifier              = Modifier.padding(horizontal = Spacing.Lg, vertical = Spacing.Sm),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Xs),
                ) {
                    item {
                        SnippetFilterChip(
                            label    = stringResource(R.string.filter_all),
                            selected = uiState.selectedCategory == null,
                            onClick  = { viewModel.filterByCategory(null) },
                        )
                    }
                    items(uiState.categories) { category ->
                        SnippetFilterChip(
                            label    = category,
                            selected = uiState.selectedCategory == category,
                            onClick  = { viewModel.filterByCategory(category) },
                        )
                    }
                }
            }

            if (uiState.snippets.isEmpty() && !uiState.isLoading) {
                EmptyStateCard(modifier = Modifier.fillMaxSize())
            } else {
                LazyColumn(
                    modifier            = Modifier.fillMaxSize(),
                    contentPadding      = PaddingValues(horizontal = Spacing.Lg, vertical = Spacing.Sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
                ) {
                    items(uiState.snippets, key = { it.id }) { snippet ->
                        SnippetCard(
                            snippet   = snippet,
                            hostLabel = hosts.find { it.id == snippet.hostId }?.label,
                            onEdit    = { viewModel.startEditing(snippet) },
                            onDelete  = { viewModel.delete(snippet.id) },
                        )
                    }
                }
            }
        }
    }

    if (uiState.isEditing) {
        SnippetEditSheet(
            snippet = uiState.editingSnippet,
            hosts   = hosts,
            onDismiss = { viewModel.cancelEditing() },
            onSave    = { label, command, category, hostId ->
                viewModel.save(
                    label      = label,
                    command    = command,
                    category   = category,
                    hostId     = hostId,
                    existingId = uiState.editingSnippet?.id,
                )
            },
        )
    }
}

// ── Filter chip ──────────────────────────────────────────────────────────────

@Composable
private fun SnippetFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(Radii.Sm))
            .background(
                if (selected) Burgundy else Color.Transparent,
                RoundedCornerShape(Radii.Sm),
            )
            .border(1.dp, if (selected) Burgundy else Border1, RoundedCornerShape(Radii.Sm))
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.Md, vertical = 6.dp),
    ) {
        Text(
            text       = label,
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.Medium,
            fontSize   = 12.sp,
            color      = if (selected) White else TextSecondary,
        )
    }
}

// ── Empty state ──────────────────────────────────────────────────────────────

@Composable
private fun EmptyStateCard(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.Md),
            modifier = Modifier.padding(Spacing.Xxl),
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(Radii.Md))
                    .background(GoldMuted.copy(alpha = 0.10f), RoundedCornerShape(Radii.Md)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Lucide.Code,
                    contentDescription = null,
                    tint     = GoldMuted,
                    modifier = Modifier.size(32.dp),
                )
            }
            Text(
                text       = stringResource(R.string.empty_snippets_title),
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize   = 15.sp,
                color      = TextPrimary,
            )
            Text(
                text       = stringResource(R.string.empty_snippets_subtitle),
                fontFamily = SpaceGroteskFamily,
                fontSize   = 12.sp,
                color      = TextSecondary,
                textAlign  = androidx.compose.ui.text.style.TextAlign.Center,
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
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.Lg))
            .background(Surface, RoundedCornerShape(Radii.Lg))
            .border(1.dp, Border1, RoundedCornerShape(Radii.Lg))
            .padding(Spacing.Md),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(Radii.Sm))
                    .background(Gold.copy(alpha = 0.10f), RoundedCornerShape(Radii.Sm)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Lucide.Code,
                    contentDescription = null,
                    tint     = Gold,
                    modifier = Modifier.size(18.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text       = snippet.label,
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize   = 14.sp,
                    color      = TextPrimary,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text       = snippet.command,
                    fontFamily = JetBrainsMonoFamily,
                    fontSize   = 11.sp,
                    color      = TextSecondary,
                    maxLines   = 2,
                    overflow   = TextOverflow.Ellipsis,
                )
                if (snippet.category != null || hostLabel != null) {
                    Spacer(Modifier.height(Spacing.Sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Xs)) {
                        snippet.category?.let { cat ->
                            SnippetBadge(text = cat, accent = GoldMuted)
                        }
                        if (hostLabel != null) {
                            SnippetBadge(text = hostLabel, accent = InfoBlue)
                        }
                    }
                }
            }
            Row {
                IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Lucide.Pencil,
                        contentDescription = stringResource(R.string.action_edit),
                        tint     = GoldMuted,
                        modifier = Modifier.size(16.dp),
                    )
                }
                IconButton(onClick = { showDeleteDialog = true }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Lucide.Trash2,
                        contentDescription = stringResource(R.string.action_delete),
                        tint     = ErrorRed,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }

    if (showDeleteDialog) {
        ConfirmDeleteDialog(
            title    = stringResource(R.string.dialog_delete_snippet_title),
            message  = stringResource(R.string.dialog_delete_snippet_message),
            onDismiss = { showDeleteDialog = false },
            onConfirm = {
                onDelete()
                showDeleteDialog = false
            },
        )
    }
}

@Composable
private fun SnippetBadge(text: String, accent: Color) {
    Box(
        modifier = Modifier
            .background(accent.copy(alpha = 0.15f), RoundedCornerShape(Radii.Xs))
            .padding(horizontal = Spacing.Sm, vertical = 2.dp),
    ) {
        Text(
            text       = text,
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize   = 10.sp,
            color      = accent,
            maxLines   = 1,
            overflow   = TextOverflow.Ellipsis,
        )
    }
}

// ── Edit BottomSheet ─────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SnippetEditSheet(
    snippet: Snippet?,
    hosts: List<Host>,
    onDismiss: () -> Unit,
    onSave: (label: String, command: String, category: String?, hostId: String?) -> Unit,
) {
    var label    by remember(snippet) { mutableStateOf(snippet?.label ?: "") }
    var command  by remember(snippet) { mutableStateOf(snippet?.command ?: "") }
    var category by remember(snippet) { mutableStateOf(snippet?.category ?: "") }
    var selectedHostId by remember(snippet) { mutableStateOf(snippet?.hostId) }
    var hostDropdownExpanded by remember { mutableStateOf(false) }

    val selectedHostLabel = hosts.find { it.id == selectedHostId }?.label
        ?: stringResource(R.string.label_snippet_global)

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = Surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // M3 1.2.1 n applique imePadding aux feuilles qu a partir d Android
                // 13 : sans cette ligne, le clavier recouvre le champ sur les
                // versions plus anciennes, que minSdk 29 autorise encore.
                .imePadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.Lg)
                .padding(bottom = Spacing.Xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.Md),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
                modifier = Modifier.padding(top = Spacing.Sm, bottom = Spacing.Xs),
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(Radii.Sm))
                        .background(Gold.copy(alpha = 0.12f), RoundedCornerShape(Radii.Sm)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (snippet == null) Lucide.Plus else Lucide.Pencil,
                        contentDescription = null,
                        tint     = Gold,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Text(
                    text       = if (snippet == null) stringResource(R.string.action_add_snippet)
                                 else stringResource(R.string.action_edit_snippet),
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize   = 16.sp,
                    color      = TextPrimary,
                )
            }

            EditField(
                value         = label,
                onValueChange = { label = it },
                label         = stringResource(R.string.label_snippet_label),
                mono          = false,
            )

            EditField(
                value         = command,
                onValueChange = { command = it },
                label         = stringResource(R.string.label_snippet_command),
                mono          = true,
                singleLine    = false,
                minLines      = 3,
            )

            EditField(
                value         = category,
                onValueChange = { category = it },
                label         = stringResource(R.string.label_snippet_category),
                mono          = false,
            )

            Column {
                Text(
                    text       = stringResource(R.string.label_snippet_host),
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize   = 11.sp,
                    color      = TextDisabled,
                    modifier   = Modifier.padding(bottom = 4.dp),
                )
                Box {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Radii.Md))
                            .background(SurfaceVariant, RoundedCornerShape(Radii.Md))
                            .border(1.dp, Border2, RoundedCornerShape(Radii.Md))
                            .clickable { hostDropdownExpanded = true }
                            .padding(horizontal = Spacing.Md, vertical = Spacing.Md),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text       = selectedHostLabel,
                            fontFamily = SpaceGroteskFamily,
                            fontSize   = 13.sp,
                            color      = TextPrimary,
                        )
                        Icon(
                            Lucide.ChevronDown,
                            contentDescription = null,
                            tint     = TextSecondary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    DropdownMenu(
                        expanded         = hostDropdownExpanded,
                        onDismissRequest = { hostDropdownExpanded = false },
                        modifier         = Modifier.background(Surface),
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text       = stringResource(R.string.label_snippet_global),
                                    fontFamily = SpaceGroteskFamily,
                                    fontSize   = 13.sp,
                                    color      = TextPrimary,
                                )
                            },
                            onClick = {
                                selectedHostId = null
                                hostDropdownExpanded = false
                            },
                        )
                        hosts.forEach { host ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text       = host.label,
                                        fontFamily = SpaceGroteskFamily,
                                        fontSize   = 13.sp,
                                        color      = TextPrimary,
                                    )
                                },
                                onClick = {
                                    selectedHostId = host.id
                                    hostDropdownExpanded = false
                                },
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.Sm),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(Radii.Md))
                        .border(1.dp, Border2, RoundedCornerShape(Radii.Md))
                        .clickable(onClick = onDismiss)
                        .padding(vertical = Spacing.Sm),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text       = stringResource(R.string.action_cancel),
                        fontFamily = SpaceGroteskFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize   = 13.sp,
                        color      = TextSecondary,
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(Radii.Md))
                        .background(Burgundy, RoundedCornerShape(Radii.Md))
                        .clickable {
                            onSave(label, command, category.takeIf { it.isNotBlank() }, selectedHostId)
                        }
                        .padding(vertical = Spacing.Sm),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text       = stringResource(R.string.action_save),
                        fontFamily = SpaceGroteskFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize   = 13.sp,
                        color      = White,
                    )
                }
            }
        }
    }
}

@Composable
private fun EditField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    mono: Boolean,
    singleLine: Boolean = true,
    minLines: Int = 1,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Xs)) {
        Text(
            text       = label,
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize   = 11.sp,
            color      = TextDisabled,
        )
        OutlinedTextField(
            value         = value,
            onValueChange = onValueChange,
            singleLine    = singleLine,
            minLines      = minLines,
            shape         = RoundedCornerShape(Radii.Md),
            textStyle     = androidx.compose.ui.text.TextStyle(
                fontFamily = if (mono) JetBrainsMonoFamily else SpaceGroteskFamily,
                fontSize   = 13.sp,
                color      = TextPrimary,
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor   = Gold,
                unfocusedBorderColor = Border2,
                cursorColor          = Gold,
                focusedContainerColor   = SurfaceVariant,
                unfocusedContainerColor = SurfaceVariant,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
