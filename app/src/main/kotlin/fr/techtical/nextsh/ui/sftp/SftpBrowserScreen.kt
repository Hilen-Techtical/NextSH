// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sftp

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.composables.icons.lucide.ArrowUpDown
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.CornerLeftUp
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Eye
import com.composables.icons.lucide.EyeOff
import com.composables.icons.lucide.File
import com.composables.icons.lucide.Folder
import com.composables.icons.lucide.FolderOpen
import com.composables.icons.lucide.FolderPlus
import com.composables.icons.lucide.Lock
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.RefreshCcw
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.TriangleAlert
import com.composables.icons.lucide.Upload
import com.composables.icons.lucide.WifiOff
import com.composables.icons.lucide.X
import fr.techtical.nextsh.R
import fr.techtical.nextsh.core.ssh.TransferStatus
import fr.techtical.nextsh.domain.model.SftpFile
import fr.techtical.nextsh.domain.model.SortOrder
import fr.techtical.nextsh.ui.components.NextShMessage
import fr.techtical.nextsh.ui.theme.*

/**
 * Fichier distant en attente d'une destination locale.
 *
 * Réduit au strict nécessaire pour lancer le transfert, en types
 * sauvegardables dans l'état d'instance : [SftpFile] n'est pas Parcelable, et
 * il ne faut de toute façon pas dépendre du listing courant, qui repart du
 * répertoire home si l'activité a été recréée entre-temps.
 */
private data class PendingDownload(
    val path: String,
    val name: String,
    val size: Long,
)

private val PendingDownloadSaver: Saver<PendingDownload?, Any> = mapSaver(
    save = { pending ->
        if (pending == null) emptyMap()
        else mapOf("path" to pending.path, "name" to pending.name, "size" to pending.size)
    },
    restore = { stored ->
        val path = stored["path"] as? String
        val name = stored["name"] as? String
        val size = stored["size"] as? Long
        if (path != null && name != null && size != null) PendingDownload(path, name, size) else null
    },
)

/**
 * Explorateur SFTP : Phase 3.3 DA Techtical.
 *
 * Chrome :
 *  - TopAppBar : nav back + titre "SFTP" Space Grotesk SemiBold 18sp +
 *    subtitle hostLabel mono 12sp TextDisabled + 4 actions (refresh,
 *    sort, hidden, upload).
 *  - Mode sélection : TopAppBar bascule en cancel ✕ + compteur Gold,
 *    actions masquées (Tout / Supprimer apparaissent dans la bottom bar).
 *  - Breadcrumb dédié sous le TopAppBar (LazyRow Lucide.ChevronRight) +
 *    bouton primary "+" mkdir à droite (parité HostList FilterAndAddBar).
 *  - Liste : LazyColumn de [SftpFileItem] cards Surface+Border1+Radii.Md.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SftpBrowserScreen(
    sessionId: String,
    hostLabel: String = "",
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    enableBackHandler: Boolean = true,
    viewModel: SftpBrowserViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // Le Snackbar Material a été retiré : il se plaçait au même endroit que le
    // bandeau de transfert et le recouvrait pendant toute sa durée. Le message
    // NextSH s'empile au-dessus du bandeau, et l'application garde la main sur
    // son apparence comme sur sa durée.

    LaunchedEffect(sessionId) { viewModel.init(sessionId, hostLabel) }

    // `enabled` et non `if` autour du BackHandler : sortir puis rentrer dans la
    // composition reenregistre le rappel, ce qui le replace APRES celui de
    // l apercu et inverse leur priorite. En split, `enableBackHandler` suit le
    // panneau focalise et bascule donc a chaque changement de focus.
    BackHandler(enabled = enableBackHandler) {
        when {
            uiState.isSelectionMode -> viewModel.clearSelection()
            !viewModel.goBack()     -> onBack()
        }
    }

    var selectedFile by remember { mutableStateOf<SftpFile?>(null) }
    var showSortMenu by remember { mutableStateOf(false) }

    // rememberSaveable et non remember : le sélecteur de destination est une
    // activité séparée, pendant laquelle le système peut détruire la nôtre. Un
    // simple remember serait alors perdu, et au retour on aurait une
    // destination valide sans savoir quoi y écrire : le sélecteur a déjà créé
    // le fichier, il resterait vide, sans transfert ni progression.
    // Le ViewModel ne conviendrait pas non plus, il est vidé en même temps que
    // l'activité hors changement de configuration.
    var pendingDownload by rememberSaveable(stateSaver = PendingDownloadSaver) {
        mutableStateOf<PendingDownload?>(null)
    }

    val downloadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { destinationUri ->
        val pending = pendingDownload
        if (destinationUri != null && pending != null) {
            // sessionId vient du paramètre de l'écran, restauré par la route de
            // navigation. Celui du ViewModel serait encore vide : après
            // recréation de l'activité, ce rappel s'exécute avant le
            // LaunchedEffect qui appelle init.
            viewModel.downloadFile(
                sessionId      = sessionId,
                remotePath     = pending.path,
                displayName    = pending.name,
                fileSize       = pending.size,
                destinationUri = destinationUri,
            )
        }
        pendingDownload = null
    }

    // Répertoire de destination capturé au moment où l'utilisateur lance
    // l'envoi : celui du ViewModel repart de la racine après recréation, le
    // fichier atterrirait au mauvais endroit.
    var pendingUploadDir by rememberSaveable { mutableStateOf<String?>(null) }

    val uploadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { sourceUri ->
        val remoteDir = pendingUploadDir
        if (sourceUri != null && remoteDir != null) {
            val displayName = resolveDisplayName(context, sourceUri)
            val fileSize    = resolveFileSize(context, sourceUri)
            viewModel.uploadFile(
                sessionId   = sessionId,
                remoteDir   = remoteDir,
                sourceUri   = sourceUri,
                displayName = displayName,
                fileSize    = fileSize,
            )
        }
        pendingUploadDir = null
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            SftpTopBar(
                isSelectionMode = uiState.isSelectionMode,
                selectionCount  = uiState.selectedFiles.size,
                hostLabel       = hostLabel,
                showHidden      = uiState.showHiddenFiles,
                sortOrder       = uiState.sortOrder,
                onBack          = onBack,
                onCancelSelection = viewModel::clearSelection,
                onRefresh       = viewModel::refresh,
                onToggleHidden  = viewModel::toggleHiddenFiles,
                onSortClick     = { showSortMenu = !showSortMenu },
                showSortMenu    = showSortMenu,
                onDismissSortMenu = { showSortMenu = false },
                onSortSelect    = {
                    viewModel.changeSortOrder(it)
                    showSortMenu = false
                },
                onUpload        = {
                    pendingUploadDir = uiState.currentPath
                    uploadLauncher.launch(arrayOf("*/*"))
                },
            )
        },
        bottomBar = {
            if (uiState.isSelectionMode) {
                SftpActionBar(
                    selectedCount     = uiState.selectedFiles.size,
                    onSelectAll       = viewModel::selectAll,
                    onDeleteSelected  = viewModel::showMultiDeleteConfirmation,
                    onCancelSelection = viewModel::clearSelection,
                )
            }
        },
        containerColor = NearBlack,
    ) { padding ->
        Column(modifier = Modifier
            .fillMaxSize()
            .padding(padding)
        ) {
            BreadcrumbAndAddBar(
                path           = uiState.currentPath,
                onNavigateTo   = viewModel::navigateTo,
                onCreateFolder = viewModel::showMkdirDialog,
                showAddButton  = !uiState.isSelectionMode,
            )

            Box(modifier = Modifier
                .fillMaxSize()
                .weight(1f)
            ) {
                when {
                    uiState.isLoading -> {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center),
                            color    = Gold,
                            strokeWidth = 2.dp,
                        )
                    }

                    uiState.error != null -> {
                        ErrorState(
                            message = uiState.error!!,
                            onRetry = viewModel::refresh,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }

                    uiState.files.isEmpty() && uiState.currentPath == "/" -> {
                        EmptyDirState(modifier = Modifier.align(Alignment.Center))
                    }

                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                horizontal = Spacing.Md,
                                vertical   = Spacing.Sm,
                            ),
                            verticalArrangement = Arrangement.spacedBy(Spacing.Xs),
                        ) {
                            if (uiState.currentPath != "/") {
                                item(key = "..") {
                                    ParentDirectoryItem(onClick = viewModel::navigateUp)
                                }
                            }

                            if (uiState.files.isEmpty()) {
                                item(key = "empty") {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = Spacing.Xxxl),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = stringResource(R.string.sftp_empty_directory),
                                            fontFamily = SpaceGroteskFamily,
                                            fontSize   = 13.sp,
                                            color      = TextSecondary,
                                        )
                                    }
                                }
                            }

                            items(items = uiState.files, key = { it.path }) { file ->
                                val isSelected = file.path in uiState.selectedFiles
                                SftpFileItem(
                                    file            = file,
                                    isSelectionMode = uiState.isSelectionMode,
                                    isSelected      = isSelected,
                                    onClick         = {
                                        if (uiState.isSelectionMode) {
                                            viewModel.toggleSelection(file)
                                        } else if (file.isDirectory) {
                                            viewModel.navigateTo(file.path)
                                        } else {
                                            selectedFile = file
                                        }
                                    },
                                    onLongClick = {
                                        if (uiState.isSelectionMode) viewModel.toggleSelection(file)
                                        else viewModel.enterSelectionMode(file)
                                    },
                                )
                            }
                        }
                    }
                }

                val inProgressTransfers = uiState.activeTransfers.filter {
                    it.status == TransferStatus.IN_PROGRESS || it.status == TransferStatus.QUEUED
                }
                // Message et bandeau empilés dans la même colonne, ancrée en
                // bas : le message ne peut plus recouvrir la progression, ce
                // que faisait le Snackbar.
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
                ) {
                    NextShMessage(
                        message   = uiState.message,
                        isError   = uiState.messageIsError,
                        onDismiss = viewModel::clearMessage,
                        modifier  = Modifier.padding(horizontal = Spacing.Md),
                    )
                    if (inProgressTransfers.isNotEmpty()) {
                        SftpTransfersBanner(
                            transfers = inProgressTransfers,
                            onCancel  = viewModel::cancelTransfer,
                        )
                    }
                }

                if (uiState.isDisconnected) {
                    SftpDisconnectedOverlay(
                        onReconnect = viewModel::reconnect,
                        modifier    = Modifier.align(Alignment.Center),
                    )
                }
            }
        }
    }

    // ── BottomSheet d'actions ─────────────────────────────────────────────────
    val current = selectedFile
    if (current != null) {
        SftpFileActionsSheet(
            file      = current,
            onDismiss = { selectedFile = null },
            onDownload = {
                selectedFile = null
                pendingDownload = PendingDownload(current.path, current.name, current.size)
                downloadLauncher.launch(current.name)
            },
            onPreview = {
                selectedFile = null
                viewModel.openPreview(current)
            },
            onRename = {
                selectedFile = null
                viewModel.showRenameDialog(current)
            },
            onChmod = {
                selectedFile = null
                viewModel.showChmodDialog(current)
            },
            onDelete = {
                selectedFile = null
                viewModel.showDeleteConfirmation(current)
            },
        )
    }

    // ── Dialogs CRUD ──────────────────────────────────────────────────────────
    if (uiState.mkdirDialog) {
        MkdirDialog(
            onDismiss = viewModel::dismissMkdirDialog,
            onConfirm = viewModel::createDirectory,
        )
    }

    val renameState = uiState.renameDialog
    if (renameState != null) {
        RenameDialog(
            file      = renameState.file,
            onDismiss = viewModel::dismissRenameDialog,
            onConfirm = { newName -> viewModel.renameFile(renameState.file.path, newName) },
        )
    }

    val chmodState = uiState.chmodDialog
    if (chmodState != null) {
        ChmodDialog(
            file      = chmodState.file,
            onDismiss = viewModel::dismissChmodDialog,
            onConfirm = { permissions -> viewModel.changePermissions(chmodState.file.path, permissions) },
        )
    }

    val deleteState = uiState.deleteConfirmation
    if (deleteState != null) {
        DeleteConfirmDialog(
            file      = deleteState.file,
            onDismiss = viewModel::dismissDeleteConfirmation,
            onConfirm = { viewModel.deleteFile(deleteState.file) },
        )
    }

    if (uiState.multiDeleteConfirmation) {
        MultiDeleteConfirmDialog(
            count     = uiState.selectedFiles.size,
            onDismiss = viewModel::dismissMultiDeleteConfirmation,
            onConfirm = viewModel::confirmMultiDelete,
        )
    }

    val previewState = uiState.previewState
    if (previewState != null) {
        SftpPreviewOverlay(
            previewState = previewState,
            onDismiss    = viewModel::dismissPreview,
            enableBackHandler = enableBackHandler,
        )
    }
}

// ── Top bar ──────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SftpTopBar(
    isSelectionMode: Boolean,
    selectionCount: Int,
    hostLabel: String,
    showHidden: Boolean,
    sortOrder: SortOrder,
    onBack: () -> Unit,
    onCancelSelection: () -> Unit,
    onRefresh: () -> Unit,
    onToggleHidden: () -> Unit,
    onSortClick: () -> Unit,
    showSortMenu: Boolean,
    onDismissSortMenu: () -> Unit,
    onSortSelect: (SortOrder) -> Unit,
    onUpload: () -> Unit,
) {
    TopAppBar(
        title = {
            if (isSelectionMode) {
                Text(
                    text       = stringResource(R.string.sftp_selection_count, selectionCount),
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize   = 18.sp,
                    color      = Gold,
                )
            } else {
                Column {
                    Text(
                        text       = "SFTP",
                        fontFamily = SpaceGroteskFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize   = 18.sp,
                        color      = TextPrimary,
                    )
                    if (hostLabel.isNotBlank()) {
                        Text(
                            text       = hostLabel,
                            fontFamily = JetBrainsMonoFamily,
                            fontSize   = 11.sp,
                            color      = TextDisabled,
                            maxLines   = 1,
                            overflow   = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = if (isSelectionMode) onCancelSelection else onBack) {
                Icon(
                    Lucide.X,
                    contentDescription = stringResource(R.string.action_cancel),
                    tint     = TextSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        },
        actions = {
            if (!isSelectionMode) {
                IconButton(onClick = onRefresh) {
                    Icon(
                        Lucide.RefreshCcw,
                        contentDescription = stringResource(R.string.action_refresh),
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Box {
                    IconButton(onClick = onSortClick) {
                        Icon(
                            Lucide.ArrowUpDown,
                            contentDescription = stringResource(R.string.action_sort),
                            tint = TextSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    SortDropdownMenu(
                        expanded     = showSortMenu,
                        currentOrder = sortOrder,
                        onDismiss    = onDismissSortMenu,
                        onSelect     = onSortSelect,
                    )
                }
                IconButton(onClick = onToggleHidden) {
                    Icon(
                        imageVector = if (showHidden) Lucide.EyeOff else Lucide.Eye,
                        contentDescription = if (showHidden)
                            stringResource(R.string.sftp_hide_hidden)
                        else
                            stringResource(R.string.sftp_show_hidden),
                        tint = if (showHidden) Gold else TextSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(onClick = onUpload) {
                    Icon(
                        Lucide.Upload,
                        contentDescription = stringResource(R.string.sftp_action_upload),
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = NearBlack),
    )
}

// ── Breadcrumb + bouton "+" mkdir ─────────────────────────────────────────────

@Composable
private fun BreadcrumbAndAddBar(
    path: String,
    onNavigateTo: (String) -> Unit,
    onCreateFolder: () -> Unit,
    showAddButton: Boolean,
) {
    val segments = path.split('/').filter { it.isNotEmpty() }
    val listState = rememberLazyListState()

    LaunchedEffect(path) {
        if (segments.isNotEmpty()) {
            listState.animateScrollToItem(index = (segments.size * 2).coerceAtLeast(0))
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface)
            .border(1.dp, Border1)
            .padding(horizontal = Spacing.Md, vertical = Spacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LazyRow(
            modifier          = Modifier.weight(1f),
            state             = listState,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Xs),
        ) {
            item {
                BreadcrumbChip(
                    label    = "/",
                    isLast   = segments.isEmpty(),
                    onClick  = { onNavigateTo("/") },
                )
            }
            segments.forEachIndexed { index, segment ->
                val targetPath = "/" + segments.subList(0, index + 1).joinToString("/")
                item(key = "sep_$index") {
                    Icon(
                        Lucide.ChevronRight,
                        contentDescription = null,
                        tint     = TextDisabled,
                        modifier = Modifier.size(12.dp),
                    )
                }
                item(key = "seg_$index") {
                    BreadcrumbChip(
                        label   = segment,
                        isLast  = index == segments.lastIndex,
                        onClick = { onNavigateTo(targetPath) },
                    )
                }
            }
        }

        if (showAddButton) {
            Spacer(Modifier.width(Spacing.Sm))
            BtnAddPrimary(onClick = onCreateFolder)
        }
    }
}

@Composable
private fun BreadcrumbChip(label: String, isLast: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(Radii.Sm))
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.Xs, vertical = 2.dp),
    ) {
        Text(
            text       = label,
            fontFamily = JetBrainsMonoFamily,
            fontWeight = if (isLast) FontWeight.SemiBold else FontWeight.Normal,
            fontSize   = 12.sp,
            color      = if (isLast) TextPrimary else Gold,
            maxLines   = 1,
        )
    }
}

@Composable
private fun BtnAddPrimary(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(Radii.Md))
            .background(Burgundy, RoundedCornerShape(Radii.Md))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Lucide.FolderPlus,
            contentDescription = stringResource(R.string.sftp_dialog_mkdir_title),
            tint = White,
            modifier = Modifier.size(18.dp),
        )
    }
}

// ── Parent directory item ────────────────────────────────────────────────────

@Composable
private fun ParentDirectoryItem(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.Md))
            .background(Surface, RoundedCornerShape(Radii.Md))
            .border(1.dp, Border1, RoundedCornerShape(Radii.Md))
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.Md, vertical = Spacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(Radii.Sm))
                .background(GoldMuted.copy(alpha = 0.10f), RoundedCornerShape(Radii.Sm)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Lucide.CornerLeftUp,
                contentDescription = stringResource(R.string.sftp_parent_directory),
                tint     = GoldMuted,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text       = "..",
            fontFamily = JetBrainsMonoFamily,
            fontSize   = 14.sp,
            color      = TextSecondary,
        )
    }
}

// ── States ──────────────────────────────────────────────────────────────────

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(Spacing.Xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.Md),
    ) {
        Icon(
            Lucide.TriangleAlert,
            contentDescription = null,
            tint     = ErrorRed,
            modifier = Modifier.size(40.dp),
        )
        Text(
            text       = message,
            fontFamily = SpaceGroteskFamily,
            fontSize   = 13.sp,
            color      = ErrorRed,
            textAlign  = TextAlign.Center,
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(Radii.Md))
                .background(Burgundy, RoundedCornerShape(Radii.Md))
                .clickable(onClick = onRetry)
                .padding(horizontal = Spacing.Lg, vertical = Spacing.Sm),
        ) {
            Text(
                text       = stringResource(R.string.action_retry),
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize   = 13.sp,
                color      = White,
            )
        }
    }
}

@Composable
private fun EmptyDirState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(Spacing.Xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.Md),
    ) {
        Icon(
            Lucide.FolderOpen,
            contentDescription = null,
            tint     = GoldMuted,
            modifier = Modifier.size(56.dp),
        )
        Text(
            text       = stringResource(R.string.sftp_empty_directory),
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize   = 15.sp,
            color      = TextPrimary,
            textAlign  = TextAlign.Center,
        )
        Text(
            text       = stringResource(R.string.sftp_empty_directory_subtitle),
            fontFamily = SpaceGroteskFamily,
            fontSize   = 12.sp,
            color      = TextSecondary,
            textAlign  = TextAlign.Center,
        )
    }
}

// ── BottomSheet d'actions ────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SftpFileActionsSheet(
    file: SftpFile,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onPreview: () -> Unit,
    onRename: () -> Unit,
    onChmod: () -> Unit,
    onDelete: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = Surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = Spacing.Md),
        ) {
            // En-tête fichier
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.Lg, vertical = Spacing.Md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(Radii.Sm))
                        .background(
                            (if (file.isDirectory) Gold else TextSecondary).copy(alpha = 0.10f),
                            RoundedCornerShape(Radii.Sm),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (file.isDirectory) Lucide.Folder else Lucide.File,
                        contentDescription = null,
                        tint     = if (file.isDirectory) Gold else TextSecondary,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text(
                    text       = file.name,
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize   = 15.sp,
                    color      = TextPrimary,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis,
                    modifier   = Modifier.weight(1f),
                )
            }

            HorizontalDivider(color = Border1, thickness = 1.dp)
            Spacer(Modifier.height(Spacing.Xs))

            if (!file.isDirectory) {
                ActionRow(
                    icon       = Lucide.Download,
                    iconTint   = Gold,
                    label      = stringResource(R.string.sftp_action_download),
                    onClick    = onDownload,
                )
                ActionRow(
                    icon       = Lucide.Eye,
                    iconTint   = if (file.isPreviewable) Gold else TextDisabled,
                    label      = stringResource(R.string.sftp_action_preview),
                    labelColor = if (file.isPreviewable) TextPrimary else TextDisabled,
                    enabled    = file.isPreviewable,
                    onClick    = onPreview,
                )
                Spacer(Modifier.height(Spacing.Xs))
                HorizontalDivider(color = Border1, thickness = 1.dp)
                Spacer(Modifier.height(Spacing.Xs))
            }

            ActionRow(
                icon    = Lucide.Pencil,
                iconTint = Gold,
                label   = stringResource(R.string.sftp_action_rename),
                onClick = onRename,
            )
            ActionRow(
                icon    = Lucide.Lock,
                iconTint = InfoBlue,
                label   = stringResource(R.string.sftp_action_chmod),
                onClick = onChmod,
            )
            Spacer(Modifier.height(Spacing.Xs))
            HorizontalDivider(color = Border1, thickness = 1.dp)
            Spacer(Modifier.height(Spacing.Xs))
            ActionRow(
                icon    = Lucide.Trash2,
                iconTint = ErrorRed,
                label   = stringResource(R.string.sftp_action_delete),
                labelColor = ErrorRed,
                onClick = onDelete,
            )
        }
    }
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    iconTint: androidx.compose.ui.graphics.Color,
    label: String,
    labelColor: androidx.compose.ui.graphics.Color = TextPrimary,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (enabled) it.clickable(onClick = onClick) else it }
            .padding(horizontal = Spacing.Lg, vertical = Spacing.Md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text       = label,
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.Medium,
            fontSize   = 14.sp,
            color      = labelColor,
        )
    }
}

// ── Dialogs ──────────────────────────────────────────────────────────────────

@Composable
private fun MkdirDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var dirName by remember { mutableStateOf("") }

    DialogShell(
        onDismiss = onDismiss,
        accent    = Burgundy,
        icon      = Lucide.FolderPlus,
        title     = stringResource(R.string.sftp_dialog_mkdir_title),
        body = {
            DialogTextField(
                value         = dirName,
                onValueChange = { dirName = it },
                placeholder   = stringResource(R.string.sftp_dialog_mkdir_hint),
            )
        },
        confirmLabel    = stringResource(R.string.action_create),
        confirmEnabled  = dirName.isNotBlank(),
        confirmDanger   = false,
        onConfirm       = { if (dirName.isNotBlank()) onConfirm(dirName.trim()) },
    )
}

@Composable
private fun RenameDialog(
    file: SftpFile,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(file) { mutableStateOf(file.name) }

    DialogShell(
        onDismiss = onDismiss,
        accent    = Gold,
        icon      = Lucide.Pencil,
        title     = stringResource(R.string.sftp_dialog_rename_title),
        body = {
            DialogTextField(
                value         = name,
                onValueChange = { name = it },
                placeholder   = file.name,
            )
        },
        confirmLabel    = stringResource(R.string.action_confirm),
        confirmEnabled  = name.isNotBlank() && name.trim() != file.name,
        confirmDanger   = false,
        onConfirm       = { if (name.isNotBlank() && name.trim() != file.name) onConfirm(name.trim()) },
    )
}

@Composable
private fun ChmodDialog(
    file: SftpFile,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var ownerR by remember(file) { mutableStateOf(file.permissions and 0b100_000_000 != 0) }
    var ownerW by remember(file) { mutableStateOf(file.permissions and 0b010_000_000 != 0) }
    var ownerX by remember(file) { mutableStateOf(file.permissions and 0b001_000_000 != 0) }
    var groupR by remember(file) { mutableStateOf(file.permissions and 0b000_100_000 != 0) }
    var groupW by remember(file) { mutableStateOf(file.permissions and 0b000_010_000 != 0) }
    var groupX by remember(file) { mutableStateOf(file.permissions and 0b000_001_000 != 0) }
    var othersR by remember(file) { mutableStateOf(file.permissions and 0b000_000_100 != 0) }
    var othersW by remember(file) { mutableStateOf(file.permissions and 0b000_000_010 != 0) }
    var othersX by remember(file) { mutableStateOf(file.permissions and 0b000_000_001 != 0) }

    val computed = computePermissions(
        ownerR, ownerW, ownerX,
        groupR, groupW, groupX,
        othersR, othersW, othersX,
    )

    DialogShell(
        onDismiss = onDismiss,
        accent    = InfoBlue,
        icon      = Lucide.Lock,
        title     = stringResource(R.string.sftp_dialog_chmod_title),
        body = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Md)) {
                Text(
                    text       = "%04o".format(computed),
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize   = 18.sp,
                    color      = Gold,
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.width(80.dp))
                    listOf(
                        stringResource(R.string.sftp_chmod_read),
                        stringResource(R.string.sftp_chmod_write),
                        stringResource(R.string.sftp_chmod_execute),
                    ).forEach {
                        Text(
                            text       = it,
                            fontFamily = JetBrainsMonoFamily,
                            fontSize   = 10.sp,
                            color      = TextDisabled,
                            textAlign  = TextAlign.Center,
                            modifier   = Modifier.weight(1f),
                        )
                    }
                }
                ChmodRow(stringResource(R.string.sftp_chmod_owner),  ownerR,  ownerW,  ownerX,  { ownerR  = it }, { ownerW  = it }, { ownerX  = it })
                ChmodRow(stringResource(R.string.sftp_chmod_group),  groupR,  groupW,  groupX,  { groupR  = it }, { groupW  = it }, { groupX  = it })
                ChmodRow(stringResource(R.string.sftp_chmod_others), othersR, othersW, othersX, { othersR = it }, { othersW = it }, { othersX = it })
            }
        },
        confirmLabel    = stringResource(R.string.action_confirm),
        confirmEnabled  = true,
        confirmDanger   = false,
        onConfirm       = { onConfirm(computed) },
    )
}

@Composable
private fun ChmodRow(
    label: String,
    read: Boolean,
    write: Boolean,
    execute: Boolean,
    onReadChange: (Boolean) -> Unit,
    onWriteChange: (Boolean) -> Unit,
    onExecuteChange: (Boolean) -> Unit,
) {
    Row(
        modifier          = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text       = label,
            fontFamily = SpaceGroteskFamily,
            fontSize   = 12.sp,
            color      = TextPrimary,
            modifier   = Modifier.width(80.dp),
        )
        listOf(read to onReadChange, write to onWriteChange, execute to onExecuteChange).forEach { (v, change) ->
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Checkbox(
                    checked         = v,
                    onCheckedChange = change,
                    colors          = CheckboxDefaults.colors(
                        checkedColor   = Burgundy,
                        checkmarkColor = White,
                        uncheckedColor = TextSecondary,
                    ),
                )
            }
        }
    }
}

private fun computePermissions(
    ownerR: Boolean, ownerW: Boolean, ownerX: Boolean,
    groupR: Boolean, groupW: Boolean, groupX: Boolean,
    othersR: Boolean, othersW: Boolean, othersX: Boolean,
): Int {
    var bits = 0
    if (ownerR)  bits = bits or 0b100_000_000
    if (ownerW)  bits = bits or 0b010_000_000
    if (ownerX)  bits = bits or 0b001_000_000
    if (groupR)  bits = bits or 0b000_100_000
    if (groupW)  bits = bits or 0b000_010_000
    if (groupX)  bits = bits or 0b000_001_000
    if (othersR) bits = bits or 0b000_000_100
    if (othersW) bits = bits or 0b000_000_010
    if (othersX) bits = bits or 0b000_000_001
    return bits
}

@Composable
private fun DeleteConfirmDialog(
    file: SftpFile,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    DialogShell(
        onDismiss = onDismiss,
        accent    = ErrorRed,
        icon      = Lucide.Trash2,
        title     = stringResource(R.string.sftp_dialog_delete_title),
        body = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
                Text(
                    text       = stringResource(R.string.sftp_dialog_delete_message, file.name),
                    fontFamily = SpaceGroteskFamily,
                    fontSize   = 13.sp,
                    color      = TextPrimary,
                )
                Text(
                    text       = if (file.isDirectory)
                        stringResource(R.string.sftp_dialog_delete_dir_warning)
                    else
                        stringResource(R.string.sftp_dialog_delete_file_warning),
                    fontFamily = SpaceGroteskFamily,
                    fontSize   = 12.sp,
                    color      = ErrorRed,
                )
            }
        },
        confirmLabel    = stringResource(R.string.action_delete),
        confirmEnabled  = true,
        confirmDanger   = true,
        onConfirm       = onConfirm,
    )
}

@Composable
private fun MultiDeleteConfirmDialog(
    count: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    DialogShell(
        onDismiss = onDismiss,
        accent    = ErrorRed,
        icon      = Lucide.Trash2,
        title     = stringResource(R.string.sftp_dialog_multi_delete_title, count),
        body = {
            Text(
                text       = stringResource(R.string.sftp_dialog_multi_delete_message),
                fontFamily = SpaceGroteskFamily,
                fontSize   = 13.sp,
                color      = ErrorRed,
            )
        },
        confirmLabel    = stringResource(R.string.action_delete),
        confirmEnabled  = true,
        confirmDanger   = true,
        onConfirm       = onConfirm,
    )
}

@Composable
private fun DialogShell(
    onDismiss: () -> Unit,
    accent: androidx.compose.ui.graphics.Color,
    icon: ImageVector,
    title: String,
    body: @Composable () -> Unit,
    confirmLabel: String,
    confirmEnabled: Boolean,
    confirmDanger: Boolean,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = Surface,
        shape            = RoundedCornerShape(Radii.Lg),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.Md)) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(Radii.Sm))
                        .background(accent.copy(alpha = 0.12f), RoundedCornerShape(Radii.Sm)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint     = accent,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Text(
                    text       = title,
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize   = 16.sp,
                    color      = TextPrimary,
                )
            }
        },
        text = { body() },
        confirmButton = {
            if (confirmDanger) {
                OutlinedButton(
                    onClick = onConfirm,
                    enabled = confirmEnabled,
                    border  = androidx.compose.foundation.BorderStroke(0.dp, androidx.compose.ui.graphics.Color.Transparent),
                    colors  = ButtonDefaults.outlinedButtonColors(
                        containerColor = ErrorRed,
                        contentColor   = White,
                    ),
                    shape = RoundedCornerShape(Radii.Md),
                ) {
                    Text(
                        text       = confirmLabel,
                        fontFamily = SpaceGroteskFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize   = 13.sp,
                    )
                }
            } else {
                Button(
                    onClick = onConfirm,
                    enabled = confirmEnabled,
                    colors  = ButtonDefaults.buttonColors(
                        containerColor = Burgundy,
                        contentColor   = White,
                        disabledContainerColor = BurgundyDark,
                        disabledContentColor   = TextSecondary,
                    ),
                    shape = RoundedCornerShape(Radii.Md),
                ) {
                    Text(
                        text       = confirmLabel,
                        fontFamily = SpaceGroteskFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize   = 13.sp,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text       = stringResource(R.string.action_cancel),
                    fontFamily = SpaceGroteskFamily,
                    fontSize   = 13.sp,
                    color      = TextSecondary,
                )
            }
        },
    )
}

@Composable
private fun DialogTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
) {
    OutlinedTextField(
        value         = value,
        onValueChange = onValueChange,
        placeholder   = {
            Text(
                text       = placeholder,
                fontFamily = JetBrainsMonoFamily,
                fontSize   = 13.sp,
                color      = TextDisabled,
            )
        },
        textStyle     = androidx.compose.ui.text.TextStyle(
            fontFamily = JetBrainsMonoFamily,
            fontSize   = 13.sp,
            color      = TextPrimary,
        ),
        singleLine    = true,
        shape         = RoundedCornerShape(Radii.Md),
        colors        = OutlinedTextFieldDefaults.colors(
            focusedBorderColor   = Gold,
            unfocusedBorderColor = Border2,
            cursorColor          = Gold,
            focusedContainerColor   = SurfaceVariant,
            unfocusedContainerColor = SurfaceVariant,
        ),
        modifier      = Modifier.fillMaxWidth(),
    )
}

// ── Sort menu ────────────────────────────────────────────────────────────────

@Composable
private fun SortDropdownMenu(
    expanded: Boolean,
    currentOrder: SortOrder,
    onDismiss: () -> Unit,
    onSelect: (SortOrder) -> Unit,
) {
    DropdownMenu(
        expanded         = expanded,
        onDismissRequest = onDismiss,
        modifier         = Modifier.background(Surface),
    ) {
        SortOrder.entries.forEach { order ->
            DropdownMenuItem(
                text = {
                    Text(
                        text       = sortOrderLabel(order),
                        fontFamily = SpaceGroteskFamily,
                        fontSize   = 13.sp,
                        color      = if (order == currentOrder) Gold else TextPrimary,
                    )
                },
                leadingIcon = if (order == currentOrder) {
                    {
                        Icon(
                            Lucide.Check,
                            contentDescription = null,
                            tint     = Gold,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                } else null,
                onClick = { onSelect(order) },
            )
        }
    }
}

@Composable
private fun sortOrderLabel(order: SortOrder): String = when (order) {
    SortOrder.NAME_ASC  -> "${stringResource(R.string.sftp_sort_name)} (${stringResource(R.string.sftp_sort_ascending)})"
    SortOrder.NAME_DESC -> "${stringResource(R.string.sftp_sort_name)} (${stringResource(R.string.sftp_sort_descending)})"
    SortOrder.SIZE_ASC  -> "${stringResource(R.string.sftp_sort_size)} (${stringResource(R.string.sftp_sort_ascending)})"
    SortOrder.SIZE_DESC -> "${stringResource(R.string.sftp_sort_size)} (${stringResource(R.string.sftp_sort_descending)})"
    SortOrder.DATE_ASC  -> "${stringResource(R.string.sftp_sort_date)} (${stringResource(R.string.sftp_sort_ascending)})"
    SortOrder.DATE_DESC -> "${stringResource(R.string.sftp_sort_date)} (${stringResource(R.string.sftp_sort_descending)})"
}

// ── Disconnected overlay ──────────────────────────────────────────────────────

@Composable
private fun SftpDisconnectedOverlay(onReconnect: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier            = modifier
            .clip(RoundedCornerShape(Radii.Lg))
            .background(Surface, RoundedCornerShape(Radii.Lg))
            .border(1.dp, Border1, RoundedCornerShape(Radii.Lg))
            .padding(Spacing.Xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.Md),
    ) {
        Icon(
            Lucide.WifiOff,
            contentDescription = null,
            tint     = ErrorRed,
            modifier = Modifier.size(40.dp),
        )
        Text(
            text       = stringResource(R.string.sftp_disconnected),
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize   = 14.sp,
            color      = ErrorRed,
            textAlign  = TextAlign.Center,
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(Radii.Md))
                .background(Burgundy, RoundedCornerShape(Radii.Md))
                .clickable(onClick = onReconnect)
                .padding(horizontal = Spacing.Lg, vertical = Spacing.Sm),
        ) {
            Text(
                text       = stringResource(R.string.sftp_reconnect),
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize   = 13.sp,
                color      = White,
            )
        }
    }
}

// ── Transfers banner ──────────────────────────────────────────────────────────

/** Nombre de transferts detailles dans le bandeau avant regroupement. */
private const val MAX_TRANSFER_ROWS = 3

@Composable
private fun SftpTransfersBanner(
    transfers: List<fr.techtical.nextsh.core.ssh.TransferState>,
    onCancel: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val knownProgress = transfers.mapNotNull { it.progress }
    val overallProgress = if (knownProgress.isNotEmpty()) knownProgress.average().toFloat() else null

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Surface)
            .border(1.dp, Border1)
            .navigationBarsPadding()
            .padding(horizontal = Spacing.Md, vertical = Spacing.Sm),
    ) {
        // Une ligne par transfert, chacune avec son propre bouton d'annulation :
        // un bouton unique au-dessus d'une liste ne dirait pas ce qu'il annule.
        transfers.take(MAX_TRANSFER_ROWS).forEach { transfer ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        Lucide.ArrowUpDown,
                        contentDescription = null,
                        tint     = Gold,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text       = transfer.request.displayName,
                        fontFamily = SpaceGroteskFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize   = 12.sp,
                        color      = TextPrimary,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis,
                    )
                }
                transfer.progressPercent?.let { percent ->
                    Text(
                        text       = stringResource(R.string.sftp_transfer_progress, percent),
                        fontFamily = JetBrainsMonoFamily,
                        fontSize   = 11.sp,
                        color      = Gold,
                    )
                }
                IconButton(
                    onClick  = { onCancel(transfer.request.id) },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        Lucide.X,
                        contentDescription = stringResource(R.string.sftp_transfer_cancel_action),
                        tint     = TextSecondary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        if (transfers.size > MAX_TRANSFER_ROWS) {
            Text(
                text       = stringResource(
                    R.string.sftp_transfer_more,
                    transfers.size - MAX_TRANSFER_ROWS,
                ),
                fontFamily = SpaceGroteskFamily,
                fontSize   = 11.sp,
                color      = TextSecondary,
            )
        }
        Spacer(Modifier.height(Spacing.Xs))
        if (overallProgress != null) {
            LinearProgressIndicator(
                progress   = overallProgress,
                modifier   = Modifier.fillMaxWidth(),
                color      = Gold,
                trackColor = NearBlack,
            )
        } else {
            LinearProgressIndicator(
                modifier   = Modifier.fillMaxWidth(),
                color      = Gold,
                trackColor = NearBlack,
            )
        }
    }
}

// ── SAF helpers ──────────────────────────────────────────────────────────────

private fun resolveDisplayName(context: android.content.Context, uri: android.net.Uri): String {
    return try {
        context.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null, null, null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx != -1) cursor.getString(idx) else null
            } else null
        } ?: uri.lastPathSegment ?: "fichier"
    } catch (e: Exception) {
        uri.lastPathSegment ?: "fichier"
    }
}

private fun resolveFileSize(context: android.content.Context, uri: android.net.Uri): Long {
    return try {
        context.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.SIZE),
            null, null, null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (idx != -1 && !cursor.isNull(idx)) cursor.getLong(idx) else 0L
            } else 0L
        } ?: 0L
    } catch (e: Exception) {
        0L
    }
}
