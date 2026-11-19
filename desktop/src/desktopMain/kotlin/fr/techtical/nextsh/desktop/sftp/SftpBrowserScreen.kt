// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sftp

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.techtical.nextsh.desktop.components.formatFileSize
import fr.techtical.nextsh.desktop.core.ssh.DesktopSftpManager
import fr.techtical.nextsh.desktop.core.ssh.replaceInvisibleCharacters
import fr.techtical.nextsh.desktop.theme.Burgundy
import fr.techtical.nextsh.desktop.theme.ErrorRed
import fr.techtical.nextsh.desktop.theme.Gold
import fr.techtical.nextsh.desktop.theme.GoldMuted
import fr.techtical.nextsh.desktop.theme.NearBlack
import fr.techtical.nextsh.desktop.theme.Surface
import fr.techtical.nextsh.desktop.theme.SurfaceVariant
import fr.techtical.nextsh.desktop.theme.TextPrimary
import fr.techtical.nextsh.desktop.theme.TextSecondary
import fr.techtical.nextsh.desktop.theme.WarningAmber
import fr.techtical.nextsh.shared.domain.model.SftpFile
import fr.techtical.nextsh.shared.domain.model.SortOrder
import kotlinx.coroutines.delay
import java.awt.Cursor
import java.io.File
import javax.swing.JFileChooser
import fr.techtical.nextsh.desktop.components.dateTimeText
import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.action_delete
import fr.techtical.nextsh.desktop.generated.resources.action_ok
import fr.techtical.nextsh.desktop.generated.resources.sftp_action_clear_selection
import fr.techtical.nextsh.desktop.generated.resources.sftp_action_create
import fr.techtical.nextsh.desktop.generated.resources.sftp_action_hide_hidden
import fr.techtical.nextsh.desktop.generated.resources.sftp_action_new_file
import fr.techtical.nextsh.desktop.generated.resources.sftp_action_new_folder
import fr.techtical.nextsh.desktop.generated.resources.sftp_action_refresh
import fr.techtical.nextsh.desktop.generated.resources.sftp_action_show_hidden
import fr.techtical.nextsh.desktop.generated.resources.sftp_action_sort
import fr.techtical.nextsh.desktop.generated.resources.sftp_action_upload_alt
import fr.techtical.nextsh.desktop.generated.resources.sftp_context_chmod
import fr.techtical.nextsh.desktop.generated.resources.sftp_context_copy_path
import fr.techtical.nextsh.desktop.generated.resources.sftp_context_delete
import fr.techtical.nextsh.desktop.generated.resources.sftp_context_download
import fr.techtical.nextsh.desktop.generated.resources.sftp_context_preview
import fr.techtical.nextsh.desktop.generated.resources.sftp_context_rename
import fr.techtical.nextsh.desktop.generated.resources.sftp_dialog_save_title
import fr.techtical.nextsh.desktop.generated.resources.sftp_dialog_upload_title
import fr.techtical.nextsh.desktop.generated.resources.sftp_empty_short
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_retry
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_title
import fr.techtical.nextsh.desktop.generated.resources.sftp_leftover_badge
import fr.techtical.nextsh.desktop.generated.resources.sftp_leftover_banner_delete
import fr.techtical.nextsh.desktop.generated.resources.sftp_leftover_banner_dismiss
import fr.techtical.nextsh.desktop.generated.resources.sftp_leftover_banner_many
import fr.techtical.nextsh.desktop.generated.resources.sftp_leftover_banner_one
import fr.techtical.nextsh.desktop.generated.resources.sftp_leftover_detail
import fr.techtical.nextsh.desktop.generated.resources.sftp_selection_count_many
import fr.techtical.nextsh.desktop.generated.resources.sftp_selection_count_one
import fr.techtical.nextsh.desktop.generated.resources.sftp_selection_select_all
import fr.techtical.nextsh.desktop.generated.resources.sftp_sort_date_asc_alt
import fr.techtical.nextsh.desktop.generated.resources.sftp_sort_date_desc_alt
import fr.techtical.nextsh.desktop.generated.resources.sftp_sort_name_asc_alt
import fr.techtical.nextsh.desktop.generated.resources.sftp_sort_name_desc_alt
import fr.techtical.nextsh.desktop.generated.resources.sftp_sort_size_asc_alt
import fr.techtical.nextsh.desktop.generated.resources.sftp_sort_size_desc_alt
import org.jetbrains.compose.resources.stringResource

/**
 * SFTP file browser (Wave 2.2 scope: nav + CRUD + selection + sort +
 * hidden toggle + right-click context menu). Upload/download land in 2.4.
 */
@Composable
fun SftpBrowserScreen(
    sessionId: String,
    tabId: String,
    paneId: String,
    initialCwd: String,
    modifier: Modifier = Modifier,
) {
    val viewModel = remember(sessionId, tabId, paneId) {
        SftpBrowserViewModel(
            sessionId = sessionId,
            tabId = tabId,
            paneId = paneId,
            initialCwd = initialCwd,
        )
    }
    // The view model is re-created when the screen comes back (tab change, another view): the old one
    // may still see an upload end and must then neither reload nor rewrite the folder of the pane.
    DisposableEffect(viewModel) {
        onDispose { viewModel.dispose() }
    }
    val state by viewModel.state.collectAsState()
    val notice by viewModel.notice.collectAsState()
    val listState = rememberLazyListState()

    // Preview split geometry is local UI state, not part of the VM: same
    // reasoning as SplitLayout's ratio: it's purely presentational and has no
    // reason to survive a process restart or sync across devices.
    var previewRatio by remember { mutableStateOf(PREVIEW_RATIO_DEFAULT) }
    var previewExpanded by remember { mutableStateOf(false) }

    // Toggling hidden files or changing sort rewrites the list from the top;
    // without resetting scroll the user would miss new entries above the fold.
    val loadedSignals = (state as? SftpBrowserUiState.Loaded)?.let { it.hiddenVisible to it.sortOrder }
    LaunchedEffect(loadedSignals?.first, loadedSignals?.second) {
        if (loadedSignals != null) listState.scrollToItem(0)
    }

    // The preview panel can also disappear without going through its close
    // button (folder navigation, refresh, the previewed file being deleted).
    // `previewExpanded` is local UI state that nothing else clears, so keyed on
    // the previewed file's path we reset it as soon as the panel is gone:
    // otherwise the next preview would silently reopen full-width.
    val previewedPath = (state as? SftpBrowserUiState.Loaded)?.preview?.file?.path
    LaunchedEffect(previewedPath) {
        if (previewedPath == null) previewExpanded = false
    }

    Column(modifier = modifier.fillMaxSize().background(NearBlack)) {
        val loaded = state as? SftpBrowserUiState.Loaded
        // The Swing JFileChooser pickers are plain functions (not composable),
        // so their dialog titles are resolved here in composable scope. The
        // save title keeps its %1$s placeholder: the remote file name is only
        // known inside the per-row click lambda (String.format handles the
        // positional specifier).
        val uploadDialogTitle = stringResource(Res.string.sftp_dialog_upload_title, displayEntryName(loaded?.cwd ?: "/"))
        val saveDialogTitleTemplate = stringResource(Res.string.sftp_dialog_save_title)
        if (loaded != null && loaded.isSelectionMode) {
            SelectionBar(
                count = loaded.selection.size,
                onSelectAll = viewModel::selectAll,
                onDelete = {
                    val targets = loaded.rawFiles.filter { it.path in loaded.selection }
                    viewModel.openDialog(SftpDialog.DeleteConfirm(targets))
                },
                onClear = viewModel::clearSelection,
            )
        } else {
            ActionBar(
                hiddenVisible = loaded?.hiddenVisible ?: false,
                sortOrder = loaded?.sortOrder ?: SortOrder.NAME_ASC,
                onNewFolder = { viewModel.openDialog(SftpDialog.NewFolder) },
                onNewFile = { viewModel.openDialog(SftpDialog.NewFile) },
                onUpload = {
                    pickFileForUpload(uploadDialogTitle)?.let { viewModel.uploadFile(it) }
                },
                onToggleHidden = viewModel::toggleHidden,
                onSortChange = viewModel::setSortOrder,
                onRefresh = viewModel::refresh,
                uploadEnabled = loaded != null,
                otherActionsEnabled = loaded != null,
            )
        }
        Breadcrumb(
            cwd = loaded?.cwd ?: "/",
            onJumpTo = viewModel::navigateTo,
        )
        // Leftovers of interrupted uploads: a banner above the list, since the rows can sit below the
        // folders. Hidden once dismissed for this folder, and while the selection bar is shown.
        if (loaded != null && !loaded.isSelectionMode && !loaded.leftoverBannerDismissed) {
            val leftovers = loaded.leftovers
            if (leftovers.isNotEmpty()) {
                UploadLeftoversBanner(
                    count = leftovers.size,
                    onDelete = { viewModel.openDialog(SftpDialog.DeleteLeftovers(leftovers)) },
                    onDismiss = viewModel::dismissLeftoverBanner,
                )
            }
        }
        val fileListContent: @Composable () -> Unit = {
            when (val s = state) {
                SftpBrowserUiState.Loading -> LoadingCenter()
                is SftpBrowserUiState.Error -> ErrorCenter(
                    message = s.message,
                    onRetry = viewModel::refresh,
                )
                is SftpBrowserUiState.Loaded -> FileList(
                    loaded = s,
                    listState = listState,
                    onNavigateInto = viewModel::navigateTo,
                    onPreview = viewModel::openPreview,
                    onClick = { file ->
                        // In selection mode: toggle. Out of selection mode:
                        // plain click is a no-op (double-click handles navigation
                        // + preview; long-press enters selection mode).
                        if (s.isSelectionMode) viewModel.toggleSelection(file.path)
                    },
                    onStartSelection = { viewModel.selectOnly(it.path) },
                    onCopyPathRefused = viewModel::onCopyPathRefused,
                    onRename = { viewModel.openDialog(SftpDialog.Rename(it)) },
                    onChmod = { viewModel.openDialog(SftpDialog.Chmod(it)) },
                    onDelete = { file ->
                        // A leftover of an interrupted upload has its own dialog (plain delete, with the
                        // explanation and the warning); everything else keeps the generic one.
                        viewModel.openDialog(
                            if (file.path in s.leftoverPaths) SftpDialog.DeleteLeftovers(listOf(file))
                            else SftpDialog.DeleteConfirm(listOf(file)),
                        )
                    },
                    onDownload = { file ->
                        // The name offered for the local file never holds a control or an invisible
                        // format character (a bidirectional control could make a `.exe` pass for a
                        // `.pdf` in the file explorer); the title shows it with U+FFFD in their place.
                        val title = String.format(saveDialogTitleTemplate, displayEntryName(file.name))
                        pickDestinationForDownload(replaceInvisibleCharacters(file.name, '_'), title)?.let { dest ->
                            viewModel.downloadFile(file, dest)
                        }
                    },
                )
            }
        }
        val preview = loaded?.preview
        if (preview != null) {
            SftpSplitRow(
                ratio = previewRatio,
                expanded = previewExpanded,
                onRatioChange = { previewRatio = it },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                fileList = fileListContent,
                preview = {
                    SftpPreviewPanel(
                        state = preview,
                        expanded = previewExpanded,
                        onToggleExpand = { previewExpanded = !previewExpanded },
                        onClose = {
                            // Closing always leaves the panel un-expanded so the
                            // next preview opens at the normal split ratio
                            // instead of silently reopening full-width.
                            previewExpanded = false
                            viewModel.closePreview()
                        },
                    )
                },
            )
        } else {
            Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                fileListContent()
            }
        }
        // Why a transfer was refused (a folder of that name...) or an operation failed
        // (create, rename, permissions, delete). Not tied to the loaded state: it must
        // still be there after a reload, or once the screen came back.
        notice?.let { message ->
            ErrorToast(message = message, onDismiss = { viewModel.dismissNotice(message) })
        }
        if (loaded?.lastInfo != null) {
            InfoToast(message = loaded.lastInfo, onDismiss = viewModel::dismissInfo)
        }
    }

    // Dialogs
    val dlg = (state as? SftpBrowserUiState.Loaded)?.dialog
    when (dlg) {
        SftpDialog.NewFolder -> NewFolderDialog(
            onConfirm = viewModel::createFolder,
            onDismiss = viewModel::dismissDialog,
        )
        SftpDialog.NewFile -> NewFileDialog(
            onConfirm = viewModel::createFile,
            onDismiss = viewModel::dismissDialog,
        )
        is SftpDialog.Rename -> RenameDialog(
            target = dlg.target,
            onConfirm = viewModel::rename,
            onDismiss = viewModel::dismissDialog,
        )
        is SftpDialog.Chmod -> ChmodDialog(
            target = dlg.target,
            onConfirm = viewModel::chmod,
            onDismiss = viewModel::dismissDialog,
        )
        is SftpDialog.DeleteConfirm -> DeleteConfirmDialog(
            targets = dlg.targets,
            onConfirm = viewModel::deleteTargets,
            onDismiss = viewModel::dismissDialog,
        )
        is SftpDialog.DeleteLeftovers -> LeftoverDeleteDialog(
            targets = dlg.targets,
            onConfirm = viewModel::deleteLeftovers,
            onDismiss = viewModel::dismissDialog,
        )
        null -> Unit
    }

    // Replacement confirmation of an upload or a download. The questions live in their own
    // queue and are shown whatever the state is: a folder reload (the one that follows an
    // earlier upload, for instance) must not close the one on screen, which a
    // `Loaded.dialog` would not survive. Only the first question is shown, the others wait
    // behind it. `key` gives every question its own window, so the focus goes back on
    // Cancel and the delay before "Replace" can be used starts again for each of them.
    val replaceQueue by viewModel.replaceQueue.collectAsState()
    replaceQueue.firstOrNull()?.let { prompt ->
        key(prompt.id) {
            ReplaceConfirmDialog(
                prompt = prompt,
                onConfirm = { viewModel.confirmReplace(prompt) },
                onDismiss = { viewModel.dismissReplace(prompt) },
            )
        }
    }
}

// ── Preview split ─────────────────────────────────────────────────────────────

/** Default/min/max for the local, non-persisted file-list/preview split ratio. */
private const val PREVIEW_RATIO_DEFAULT = 0.35f
private const val PREVIEW_RATIO_MIN = 0.2f
private const val PREVIEW_RATIO_MAX = 0.8f

/**
 * Clamps a candidate preview ratio to the allowed range. Pure so it can be
 * unit-tested outside of a Compose UI harness (see [SftpSplitRow]'s divider
 * drag handler, which is the only caller).
 */
internal fun clampPreviewRatio(ratio: Float): Float = ratio.coerceIn(PREVIEW_RATIO_MIN, PREVIEW_RATIO_MAX)

/**
 * Computes the next (clamped) preview ratio from a horizontal drag delta in
 * pixels, given the total row width in pixels. The panel sits on the right
 * of the divider, so dragging right (positive [dragDeltaPx]) grows the file
 * list and shrinks the panel: i.e. the ratio decreases. Guards against a
 * zero/negative [totalWidthPx] (not-yet-measured layout) by returning
 * [current] unchanged.
 */
internal fun nextPreviewRatio(current: Float, dragDeltaPx: Float, totalWidthPx: Float): Float {
    if (totalWidthPx <= 0f) return current
    return clampPreviewRatio(current - dragDeltaPx / totalWidthPx)
}

/**
 * Two-pane row: file list (weight `1 - ratio`) + draggable divider + preview
 * panel (weight `ratio`). When [expanded] is true the file list is omitted
 * entirely and [preview] takes the full row.
 *
 * Mirrors [fr.techtical.nextsh.desktop.sessions.SplitLayout]'s local-ratio
 * drag pattern: [localRatio] tracks the divider in real time so dragging
 * feels immediate, and [onRatioChange] (→ the caller's `remember`ed state)
 * is only invoked on drag end / double-click-reset, not on every frame.
 * There's no ViewModel/StateFlow in the loop here (the ratio is pure local
 * UI state to begin with), but recomposing this row on every drag frame
 * instead of the whole [SftpBrowserScreen] (action bar, breadcrumb, …) is
 * still worth keeping, same reasoning, smaller scope.
 */
@Composable
private fun SftpSplitRow(
    ratio: Float,
    expanded: Boolean,
    onRatioChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    fileList: @Composable () -> Unit,
    preview: @Composable () -> Unit,
) {
    var localRatio by remember { mutableStateOf(ratio) }
    LaunchedEffect(ratio) {
        if (ratio != localRatio) localRatio = ratio
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val totalPx = constraints.maxWidth.toFloat()
        Row(modifier = Modifier.fillMaxSize()) {
            if (!expanded) {
                Box(modifier = Modifier.weight(1f - localRatio).fillMaxHeight()) { fileList() }
                PreviewDivider(
                    onDragDelta = { delta ->
                        localRatio = nextPreviewRatio(localRatio, delta, totalPx)
                    },
                    onDragEnd = { onRatioChange(localRatio) },
                    onDoubleTap = {
                        localRatio = PREVIEW_RATIO_DEFAULT
                        onRatioChange(PREVIEW_RATIO_DEFAULT)
                    },
                )
                Box(modifier = Modifier.weight(localRatio).fillMaxHeight()) { preview() }
            } else {
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) { preview() }
            }
        }
    }
}

/**
 * Draggable divider between the file list and the preview panel, same
 * visual/gesture pattern as [fr.techtical.nextsh.desktop.sessions.SplitLayout]'s
 * horizontal divider (4 dp Burgundy bar, E_RESIZE cursor, double-click resets
 * the ratio), copied rather than reused because [SplitLayout] is coupled to
 * `PaneSlot` focus routing that doesn't apply here.
 */
@Composable
private fun PreviewDivider(
    onDragDelta: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDoubleTap: () -> Unit,
) {
    val cursor = PointerIcon(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR))
    // `rememberUpdatedState` so the long-lived `pointerInput` gesture
    // detectors below always call the latest closures without needing to
    // restart the gesture loop on every recomposition.
    val currentDelta by rememberUpdatedState(onDragDelta)
    val currentEnd by rememberUpdatedState(onDragEnd)
    val currentDoubleTap by rememberUpdatedState(onDoubleTap)
    val dragMod = Modifier.pointerInput(Unit) {
        detectDragGestures(
            onDragEnd = { currentEnd() },
            onDragCancel = { currentEnd() },
            onDrag = { change, dragAmount ->
                change.consume()
                currentDelta(dragAmount.x)
            },
        )
    }
    val tapMod = Modifier.pointerInput(Unit) {
        detectTapGestures(onDoubleTap = { currentDoubleTap() })
    }
    Box(
        modifier = Modifier
            .width(4.dp)
            .fillMaxHeight()
            .background(Burgundy)
            .pointerHoverIcon(cursor)
            .then(dragMod)
            .then(tapMod),
    )
}

// ── Bars ──────────────────────────────────────────────────────────────────────

@Composable
private fun ActionBar(
    hiddenVisible: Boolean,
    sortOrder: SortOrder,
    onNewFolder: () -> Unit,
    onNewFile: () -> Unit,
    onUpload: () -> Unit,
    onToggleHidden: () -> Unit,
    onSortChange: (SortOrder) -> Unit,
    onRefresh: () -> Unit,
    uploadEnabled: Boolean,
    otherActionsEnabled: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(Surface)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CreateMenuButton(
            onNewFolder = onNewFolder,
            onNewFile = onNewFile,
            enabled = otherActionsEnabled,
        )
        ActionIconButton(
            icon = Icons.Default.Upload,
            label = stringResource(Res.string.sftp_action_upload_alt),
            enabled = uploadEnabled,
            onClick = onUpload,
        )
        Box(modifier = Modifier.weight(1f))
        ActionIconButton(
            icon = if (hiddenVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
            label = stringResource(if (hiddenVisible) Res.string.sftp_action_hide_hidden else Res.string.sftp_action_show_hidden),
            enabled = otherActionsEnabled,
            onClick = onToggleHidden,
        )
        SortMenuButton(current = sortOrder, onSelect = onSortChange, enabled = otherActionsEnabled)
        ActionIconButton(
            icon = Icons.Default.Refresh,
            label = stringResource(Res.string.sftp_action_refresh),
            enabled = otherActionsEnabled,
            onClick = onRefresh,
        )
    }
}

@Composable
private fun SelectionBar(
    count: Int,
    onSelectAll: () -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(Burgundy)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClear) {
            Icon(Icons.Default.Close, contentDescription = stringResource(Res.string.sftp_action_clear_selection), tint = Gold)
        }
        Text(
            text = stringResource(
                if (count > 1) Res.string.sftp_selection_count_many else Res.string.sftp_selection_count_one,
                count,
            ),
            color = Gold,
            style = MaterialTheme.typography.labelMedium,
        )
        Box(modifier = Modifier.weight(1f))
        TextButton(onClick = onSelectAll) {
            Icon(Icons.Default.DoneAll, contentDescription = null, tint = Gold)
            Spacer(Modifier.width(4.dp))
            Text(stringResource(Res.string.sftp_selection_select_all), color = Gold)
        }
        TextButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = null, tint = ErrorRed)
            Spacer(Modifier.width(4.dp))
            Text(stringResource(Res.string.action_delete), color = ErrorRed)
        }
    }
}

@Composable
private fun ActionIconButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (enabled) Gold else GoldMuted,
        )
    }
}

/** "+" button opening a small menu: new folder or new file in the current directory. */
@Composable
private fun CreateMenuButton(
    onNewFolder: () -> Unit,
    onNewFile: () -> Unit,
    enabled: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = enabled) {
            Icon(
                Icons.Default.Add,
                contentDescription = stringResource(Res.string.sftp_action_create),
                tint = if (enabled) Gold else GoldMuted,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(Surface),
        ) {
            DropdownMenuItem(
                leadingIcon = {
                    Icon(Icons.Default.CreateNewFolder, contentDescription = null, tint = Gold, modifier = Modifier.size(18.dp))
                },
                text = {
                    Text(
                        text = stringResource(Res.string.sftp_action_new_folder),
                        color = TextPrimary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                onClick = {
                    expanded = false
                    onNewFolder()
                },
            )
            DropdownMenuItem(
                leadingIcon = {
                    Icon(Icons.AutoMirrored.Filled.NoteAdd, contentDescription = null, tint = Gold, modifier = Modifier.size(18.dp))
                },
                text = {
                    Text(
                        text = stringResource(Res.string.sftp_action_new_file),
                        color = TextPrimary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                onClick = {
                    expanded = false
                    onNewFile()
                },
            )
        }
    }
}

@Composable
private fun SortMenuButton(
    current: SortOrder,
    onSelect: (SortOrder) -> Unit,
    enabled: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = enabled) {
            Icon(
                Icons.AutoMirrored.Filled.Sort,
                contentDescription = stringResource(Res.string.sftp_action_sort),
                tint = if (enabled) Gold else GoldMuted,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(Surface),
        ) {
            SortOrder.entries.forEach { option ->
                val selected = option == current
                DropdownMenuItem(
                    leadingIcon = {
                        if (selected) Icon(Icons.Default.Check, contentDescription = null, tint = Gold)
                        else Spacer(Modifier.size(18.dp))
                    },
                    text = {
                        Text(
                            text = option.displayName(),
                            color = if (selected) Gold else TextPrimary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

// ── Breadcrumb ────────────────────────────────────────────────────────────────

@Composable
private fun Breadcrumb(cwd: String, onJumpTo: (String) -> Unit) {
    val scroll = rememberScrollState()
    val segments = pathSegments(cwd)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .background(SurfaceVariant),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f, fill = true)
                .horizontalScroll(scroll)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BreadcrumbChip(label = "/", onClick = { onJumpTo("/") })
            segments.forEachIndexed { index, segment ->
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(14.dp),
                )
                val fullPath = "/" + segments.subList(0, index + 1).joinToString("/")
                BreadcrumbChip(label = displayEntryName(segment), onClick = { onJumpTo(fullPath) })
            }
        }
    }
}

@Composable
private fun BreadcrumbChip(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            color = TextPrimary,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
    }
}

// ── File list ─────────────────────────────────────────────────────────────────

/**
 * Width thresholds (dp) below which [FileRow]'s metadata columns collapse to
 * give the file name more room. There's no separate column header in this
 * screen (rows self-label via icon + alignment), so these only gate the
 * cells below, but a future header would need to key off the same
 * thresholds and [FileListColumns] to stay in sync.
 *
 * Per Thibaud's review of the preview-split resize (2026-07-29): the name is
 * never as important to hide as the metadata, and must stay at least
 * partially visible even in the narrowest state. Columns collapse in the
 * order they're least missed (permissions first, then modified date, then
 * size), and thresholds are picked from today's fixed widths (size 80dp,
 * permissions 96dp + 16dp gap, date 140dp + 16dp gap, plus the 52dp of
 * icon/gap/padding "chrome" that's always shown) with enough headroom left
 * at each tier for the name to stay comfortably readable:
 * - >= 640dp: everything shown, name gets width - 400dp (>= ~240dp)
 * - >= 480dp (< 640): permissions hidden, name gets width - 288dp (>= ~192dp)
 * - >= 360dp (< 480): + date hidden, name gets width - 132dp (>= ~228dp)
 * - < 360dp: + size hidden, name (weight 1f) gets the entire remaining width
 *   minus the 52dp chrome: always shows at least an icon and a few
 *   characters of the name, per Thibaud's ask.
 */
private const val FILE_LIST_HIDE_PERMISSIONS_BELOW_DP = 640f
private const val FILE_LIST_HIDE_DATE_BELOW_DP = 480f
private const val FILE_LIST_HIDE_SIZE_BELOW_DP = 360f

/** Which of [FileRow]'s metadata columns fit at a given available width. */
internal data class FileListColumns(
    val showSize: Boolean,
    val showPermissions: Boolean,
    val showDate: Boolean,
)

/**
 * Pure so it's unit-testable outside a Compose UI harness, same reasoning
 * as [clampPreviewRatio]/[nextPreviewRatio]. [availableWidthDp] is the
 * measured width of the file-list container (dp, density-independent),
 * i.e. `BoxWithConstraints.maxWidth.value` at the [FileList] call site.
 */
internal fun fileListColumnsFor(availableWidthDp: Float): FileListColumns = FileListColumns(
    showPermissions = availableWidthDp >= FILE_LIST_HIDE_PERMISSIONS_BELOW_DP,
    showDate = availableWidthDp >= FILE_LIST_HIDE_DATE_BELOW_DP,
    showSize = availableWidthDp >= FILE_LIST_HIDE_SIZE_BELOW_DP,
)

@Composable
private fun FileList(
    loaded: SftpBrowserUiState.Loaded,
    listState: LazyListState,
    onNavigateInto: (String) -> Unit,
    onPreview: (SftpFile) -> Unit,
    onClick: (SftpFile) -> Unit,
    onStartSelection: (SftpFile) -> Unit,
    onCopyPathRefused: () -> Unit,
    onRename: (SftpFile) -> Unit,
    onChmod: (SftpFile) -> Unit,
    onDelete: (SftpFile) -> Unit,
    onDownload: (SftpFile) -> Unit,
) {
    val files = loaded.visibleFiles
    if (files.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(Res.string.sftp_empty_short), color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    // Measured at the list container's actual width: this is what shrinks
    // when the preview panel's divider is dragged wide, not the window width.
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val columns = fileListColumnsFor(maxWidth.value)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            items(files, key = { it.path }) { file ->
                FileRow(
                    file = file,
                    leftoverOf = if (file.path in loaded.leftoverPaths) leftoverOriginalName(file.name) else null,
                    isSelected = file.path in loaded.selection,
                    columns = columns,
                    onNavigateInto = { onNavigateInto(file.path) },
                    onPreview = { onPreview(file) },
                    onClick = { onClick(file) },
                    onStartSelection = { onStartSelection(file) },
                    onCopyPathRefused = onCopyPathRefused,
                    onRename = { onRename(file) },
                    onChmod = { onChmod(file) },
                    onDelete = { onDelete(file) },
                    onDownload = { onDownload(file) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    file: SftpFile,
    /** The name, ready to show, of the file this entry is the interrupted upload of; null for an ordinary entry. */
    leftoverOf: String?,
    isSelected: Boolean,
    columns: FileListColumns,
    onNavigateInto: () -> Unit,
    onPreview: () -> Unit,
    onClick: () -> Unit,
    onStartSelection: () -> Unit,
    onCopyPathRefused: () -> Unit,
    onRename: () -> Unit,
    onChmod: () -> Unit,
    onDelete: () -> Unit,
    onDownload: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    // Interaction model: long-press enters selection mode (Android parity).
    // Once selected, plain click toggles membership. Double-click navigates
    // into a directory OR opens the preview panel for a previewable file.
    // Right-click surfaces single-file actions via ContextMenuArea.
    val labelPreview = stringResource(Res.string.sftp_context_preview)
    val labelDownload = stringResource(Res.string.sftp_context_download)
    val labelRename = stringResource(Res.string.sftp_context_rename)
    val labelChmod = stringResource(Res.string.sftp_context_chmod)
    val labelCopyPath = stringResource(Res.string.sftp_context_copy_path)
    val labelDelete = stringResource(Res.string.sftp_context_delete)
    ContextMenuArea(items = {
        buildList {
            if (file.isPreviewable) add(ContextMenuItem(labelPreview, onPreview))
            if (!file.isDirectory) add(ContextMenuItem(labelDownload, onDownload))
            add(ContextMenuItem(labelRename, onRename))
            add(ContextMenuItem(labelChmod, onChmod))
            add(ContextMenuItem(labelCopyPath) {
                val copyable = copyablePath(file.path)
                if (copyable != null) clipboard.setText(AnnotatedString(copyable)) else onCopyPathRefused()
            })
            add(ContextMenuItem(labelDelete, onDelete))
        }
    }) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (isSelected) Burgundy.copy(alpha = 0.35f) else androidx.compose.ui.graphics.Color.Transparent)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onStartSelection,
                    onDoubleClick = {
                        when {
                            file.isDirectory || file.isSymlink -> onNavigateInto()
                            file.isPreviewable -> onPreview()
                        }
                    },
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = iconFor(file),
                contentDescription = null,
                tint = if (file.isDirectory) Gold else GoldMuted,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            if (leftoverOf == null) {
                Text(
                    text = displayEntryName(file.name),
                    color = TextPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                // The real name, then a badge, and under them the file this is the incomplete copy of.
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = displayEntryName(file.name),
                            color = TextPrimary,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        // Not in a narrow pane: the badge would take the room of the name, and the second
                        // line already says the same thing.
                        if (columns.showSize) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(Res.string.sftp_leftover_badge),
                                color = WarningAmber,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(WarningAmber.copy(alpha = 0.12f))
                                    .padding(horizontal = 6.dp, vertical = 1.dp),
                            )
                        }
                    }
                    Text(
                        text = stringResource(Res.string.sftp_leftover_detail, leftoverOf),
                        color = WarningAmber,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (columns.showSize) {
                Text(
                    text = if (file.isDirectory) "-" else formatFileSize(file.size),
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.width(80.dp),
                    textAlign = TextAlign.End,
                )
            }
            if (columns.showPermissions) {
                Spacer(Modifier.width(16.dp))
                Text(
                    text = file.permissionsString,
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.width(96.dp),
                )
            }
            if (columns.showDate) {
                Spacer(Modifier.width(16.dp))
                Text(
                    text = formatDate(file.modifiedAt),
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.width(140.dp),
                )
            }
        }
    }
}

/**
 * The name of the file that the leftover [leftoverName] is the incomplete copy of, ready to show: invisible
 * characters replaced for display, and an ellipsis when the start of the name may have been cut.
 */
internal fun leftoverOriginalName(leftoverName: String): String? =
    DesktopSftpManager.uploadLeftoverName(leftoverName)?.let { displayEntryName(it.original) + if (it.cut) "…" else "" }

/** Count of leftovers, a plain-text banner above the list with the actions "Delete…" and "Dismiss". */
@Composable
private fun UploadLeftoversBanner(count: Int, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val text = if (count > 1) stringResource(Res.string.sftp_leftover_banner_many, count)
    else stringResource(Res.string.sftp_leftover_banner_one, count)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .background(WarningAmber.copy(alpha = 0.12f))
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = WarningAmber,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            color = TextPrimary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDelete) { Text(stringResource(Res.string.sftp_leftover_banner_delete), color = Gold) }
        TextButton(onClick = onDismiss) {
            Text(stringResource(Res.string.sftp_leftover_banner_dismiss), color = TextSecondary)
        }
    }
}

// ── States ────────────────────────────────────────────────────────────────────

@Composable
private fun LoadingCenter() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Gold)
    }
}

@Composable
private fun ErrorCenter(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(Res.string.sftp_error_title), color = ErrorRed, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(message, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onRetry) { Text(stringResource(Res.string.sftp_error_retry), color = TextPrimary) }
    }
}

@Composable
private fun ErrorToast(message: String, onDismiss: () -> Unit) {
    // "OK" is usable only once the notice has been on screen for a moment: a double click (or a key held
    // down) on the "OK" of the previous notice must not take off this one, which nobody read.
    var armed by remember(message) { mutableStateOf(false) }
    LaunchedEffect(message) {
        delay(REPLACE_ARM_DELAY_MS)
        armed = true
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ErrorRed.copy(alpha = 0.12f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            color = ErrorRed,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss, enabled = armed) { Text(stringResource(Res.string.action_ok), color = if (armed) Gold else TextSecondary) }
    }
}

/** Success confirmation. Unlike [ErrorToast] there is nothing to re-read, so it clears itself. */
@Composable
private fun InfoToast(message: String, onDismiss: () -> Unit) {
    LaunchedEffect(message) {
        delay(INFO_TOAST_DURATION_MS)
        onDismiss()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Gold.copy(alpha = 0.12f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            color = Gold,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_ok), color = Gold) }
    }
}

private const val INFO_TOAST_DURATION_MS = 3_000L

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun pathSegments(cwd: String): List<String> =
    cwd.split('/').filter { it.isNotEmpty() }

private fun iconFor(file: SftpFile): ImageVector =
    if (file.isDirectory || file.isSymlink) Icons.Default.Folder
    else Icons.AutoMirrored.Filled.InsertDriveFile

@Composable
private fun formatDate(epochMillis: Long): String =
    if (epochMillis <= 0) "-" else dateTimeText(epochMillis)

@Composable
private fun SortOrder.displayName(): String = stringResource(when (this) {
    SortOrder.NAME_ASC -> Res.string.sftp_sort_name_asc_alt
    SortOrder.NAME_DESC -> Res.string.sftp_sort_name_desc_alt
    SortOrder.SIZE_ASC -> Res.string.sftp_sort_size_asc_alt
    SortOrder.SIZE_DESC -> Res.string.sftp_sort_size_desc_alt
    SortOrder.DATE_ASC -> Res.string.sftp_sort_date_asc_alt
    SortOrder.DATE_DESC -> Res.string.sftp_sort_date_desc_alt
})

/**
 * Swing [JFileChooser] (files only) for picking a local file to upload.
 * Returns null if the user cancels. Called from a Compose click handler:
 * those fire on the EDT, so running the modal chooser inline is fine.
 * [dialogTitle] is resolved by the composable caller (stringResource is
 * not reachable from a plain function).
 */
private fun pickFileForUpload(dialogTitle: String): File? {
    val chooser = JFileChooser().apply {
        this.dialogTitle = dialogTitle
        fileSelectionMode = JFileChooser.FILES_ONLY
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile
    else null
}

/**
 * Swing [JFileChooser] for choosing a local destination for a download.
 * Pre-fills the file name with the remote name. The chooser asks nothing
 * when the chosen file already exists: the replacement is confirmed
 * afterwards by [SftpBrowserViewModel.downloadFile], through [ReplaceConfirmDialog].
 * [dialogTitle] is resolved by the composable caller.
 */
private fun pickDestinationForDownload(remoteName: String, dialogTitle: String): File? {
    val chooser = JFileChooser().apply {
        this.dialogTitle = dialogTitle
        selectedFile = File(remoteName)
        fileSelectionMode = JFileChooser.FILES_ONLY
    }
    return if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile
    else null
}
