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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import fr.techtical.nextsh.ui.components.SavedTextLimits
import fr.techtical.nextsh.ui.components.boundedTextSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.composables.icons.lucide.ArrowUpDown
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.CornerLeftUp
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Eye
import com.composables.icons.lucide.EyeOff
import com.composables.icons.lucide.File
import com.composables.icons.lucide.FilePlus
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
import fr.techtical.nextsh.core.ssh.isVirtualDocument
import fr.techtical.nextsh.core.ssh.pickedFileName
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
 *    bouton primary "+" à droite (parité HostList FilterAndAddBar), qui ouvre
 *    un menu « Nouveau dossier » / « Nouveau fichier ».
 *  - Un élément sélectionné seul (mode sélection) : le ⋮ de la barre du bas ouvre la
 *    même feuille d'actions que le toucher sur un fichier, ce qui donne accès à
 *    Renommer et Permissions pour un dossier aussi.
 *  - Liste : LazyColumn de [SftpFileItem] cards Surface+Border1+Radii.Md.
 *
 * [locked] : l'app est derrière l'écran de verrou (`AppLock`). Le dialogue de remplacement d'un
 * fichier (`UploadReplaceDialog`) arrive après la réponse du serveur, donc peut-être après la création
 * de la fenêtre de verrou : une fenêtre créée après elle passerait au-dessus et resterait touchable.
 * Il ne s'affiche donc pas tant que l'app est verrouillée. L'état reste dans le ViewModel et le
 * dialogue réapparaît au déverrouillage. Sans valeur par défaut : un appelant qui l'oublierait
 * laisserait passer ce dialogue.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SftpBrowserScreen(
    sessionId: String,
    hostLabel: String = "",
    onBack: () -> Unit,
    locked: Boolean,
    modifier: Modifier = Modifier,
    enableBackHandler: Boolean = true,
    ownsSession: Boolean = true,
    viewModel: SftpBrowserViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // Le Snackbar Material a été retiré : il se plaçait au même endroit que le
    // bandeau de transfert et le recouvrait pendant toute sa durée. Le message
    // NextSH s'empile au-dessus du bandeau, et l'application garde la main sur
    // son apparence comme sur sa durée.

    // Tells the view model whether anyone can see this screen: a view model announces (and so
    // acknowledges) the failure of a transfer only while its screen is on show. The one of a split
    // pane lives in the navigation entry of the terminal and keeps collecting while the full-screen
    // browser of the same session is open above it: without this, it could say the failure to nobody
    // and take it away from the browser that is on screen. Not visible once the screen is stopped
    // (the app went to the background, a picker is open) or leaves the composition.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.setVisible(true)
                Lifecycle.Event.ON_STOP  -> viewModel.setVisible(false)
                else                     -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.setVisible(false)
        }
    }

    LaunchedEffect(sessionId) { viewModel.init(sessionId, hostLabel, ownsSession) }

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

    // L'unique élément sélectionné, celui sur lequel agit le ⋮ de la barre de sélection ; null sinon
    // (plusieurs éléments, ou un chemin resté sélectionné d'un autre dossier que celui qui est listé).
    val singleSelected = remember(uiState.files, uiState.selectedFiles) {
        if (uiState.selectedFiles.size == 1) {
            uiState.files.firstOrNull { it.path in uiState.selectedFiles }
        } else {
            null
        }
    }

    // The name each leftover of an interrupted upload stands for, by path: the badge of its row. A file
    // that is not in the map is an ordinary one.
    val leftoverLabels = remember(uiState.uploadLeftovers) {
        uiState.uploadLeftovers.mapNotNull { file -> leftoverOriginalLabel(file.name)?.let { file.path to it } }.toMap()
    }

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

    // Le maintien au premier plan qui couvre le sélecteur (voir PickerHolds) : sans lui, Android
    // coupe la connexion SSH de l'explorateur environ six secondes après le départ de l'app, et au
    // retour la session est morte. Son identifiant est en rememberSaveable pour la même raison que
    // la destination : après une recréation de l'activité, c'est lui qui permet de rendre le bon
    // maintien. Il est rendu à CHAQUE retour du sélecteur, résultat ou annulation : un maintien ne
    // doit jamais survivre au sélecteur (le service a un délai de garde, mais il n'est qu'un filet).
    var pickerHoldId by rememberSaveable { mutableStateOf<String?>(null) }

    val endPickerHold: () -> Unit = {
        viewModel.endPickerHold(pickerHoldId)
        pickerHoldId = null
    }

    // Ouvre le sélecteur seulement si la session SSH est encore là : sinon l'écran passe à l'état
    // déconnecté, et l'utilisateur n'a pas à choisir un fichier pour apprendre que rien ne partira.
    val launchPicker: (() -> Unit) -> Unit = { open ->
        if (viewModel.checkLink()) {
            // Un maintien resté d'un sélecteur dont le retour s'est perdu ne doublonne pas le nouveau.
            endPickerHold()
            pickerHoldId = viewModel.beginPickerHold()
            open()
        }
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
        // Après la demande de transfert : le service a déjà son transfert, le rendre ne l'arrête pas.
        endPickerHold()
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
            // Un document virtuel (Google Docs de Drive) n'a pas de contenu à envoyer : le
            // ViewModel le refuse en le disant, sans démarrer de transfert.
            val isVirtual   = isVirtualDocument(context, sourceUri)
            // Le maintien du sélecteur est rendu juste après, au retour du sélecteur, donc avant la
            // fin de la vérification du nom sur le serveur : l'app est alors au premier plan, et la
            // vérification tient sa propre réservation de la session (voir le ViewModel). Un fichier
            // du même nom ouvre un dialogue, rien ne part sans la réponse de l'utilisateur.
            viewModel.uploadFile(
                sessionId   = sessionId,
                remoteDir   = remoteDir,
                sourceUri   = sourceUri,
                displayName = displayName,
                fileSize    = fileSize,
                isVirtual   = isVirtual,
            )
        }
        endPickerHold()
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
                    launchPicker {
                        pendingUploadDir = uiState.currentPath
                        uploadLauncher.launch(arrayOf("*/*"))
                    }
                },
                uploadEnabled   = !uiState.uploadCheckInProgress,
            )
        },
        bottomBar = {
            if (uiState.isSelectionMode) {
                SftpActionBar(
                    selectedCount     = uiState.selectedFiles.size,
                    onSelectAll       = viewModel::selectAll,
                    onDeleteSelected  = viewModel::showMultiDeleteConfirmation,
                    onCancelSelection = viewModel::clearSelection,
                    // Seulement pour un élément seul : la feuille d'actions agit sur un élément à la fois.
                    onMoreActions     = singleSelected?.let { file -> { selectedFile = file } },
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
                onCreateFile   = viewModel::showNewFileDialog,
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

                    // Le panneau « déconnecté » plus bas dit quoi faire : ni liste périmée derrière lui,
                    // ni message d'erreur avec un « Réessayer » qui reboucle sur la même panne.
                    uiState.isDisconnected -> Unit

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

                            // An element of the list, not a bar above it: it goes with the list when the
                            // link is lost. Not in selection mode, where the selection bar takes over.
                            if (uiState.uploadLeftovers.isNotEmpty() &&
                                !uiState.leftoverBannerDismissed &&
                                !uiState.isSelectionMode
                            ) {
                                item(key = "upload-leftovers") {
                                    UploadLeftoversBanner(
                                        count     = uiState.uploadLeftovers.size,
                                        onDelete  = { viewModel.showLeftoverDeleteConfirmation(uiState.uploadLeftovers) },
                                        onDismiss = viewModel::dismissLeftoverBanner,
                                    )
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
                                    leftoverOf      = leftoverLabels[file.path],
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

                // Pas pendant un chargement : le bouton d'actualisation de la barre du haut relance un
                // listing, dont le résultat dira si le lien est revenu.
                if (uiState.isDisconnected && !uiState.isLoading) {
                    val disconnectedTitle = stringResource(R.string.sftp_disconnected)
                    SftpDisconnectedOverlay(
                        sessionEnded = uiState.sessionEnded,
                        ownsSession  = ownsSession,
                        // Le détail d'un échec de reconnexion (ouverture refusée...), sauf s'il répète le titre.
                        detail       = uiState.error?.takeUnless { it == disconnectedTitle },
                        onReconnect  = viewModel::reconnect,
                        // Même geste que la flèche du haut : plein écran, retour à la liste ; en
                        // panneau de split, fermeture du split (voir EmbeddedSftpBrowser).
                        onLeave      = onBack,
                        modifier     = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = Spacing.Lg),
                    )
                }
            }
        }
    }

    // ── BottomSheet d'actions ─────────────────────────────────────────────────
    val current = selectedFile
    if (current != null) {
        // Une action de la feuille met fin à la sélection qui y a mené (le ⋮ de la barre du bas) : un
        // renommage ou une suppression laisserait sinon un chemin périmé dans la sélection. Sans
        // sélection (toucher sur un fichier), vider la sélection ne change rien. Fermer la feuille sans
        // action (dismiss) garde la sélection, pour en choisir un autre geste.
        val endAction: () -> Unit = {
            selectedFile = null
            viewModel.clearSelection()
        }
        SftpFileActionsSheet(
            file      = current,
            onDismiss = { selectedFile = null },
            onDownload = {
                endAction()
                launchPicker {
                    pendingDownload = PendingDownload(current.path, current.name, current.size)
                    // The name offered for the local file carries no invisible character (see
                    // downloadEntryName); the transfer keeps the real name (PendingDownload.name).
                    downloadLauncher.launch(downloadEntryName(current.name))
                }
            },
            onPreview = {
                endAction()
                viewModel.openPreview(current)
            },
            onRename = {
                endAction()
                viewModel.showRenameDialog(current)
            },
            onChmod = {
                endAction()
                viewModel.showChmodDialog(current)
            },
            onDelete = {
                endAction()
                // A leftover has a dialog of its own: it says what the file is and what deleting it does.
                // Anything else, a file whose name only looks like a leftover included, goes the usual way.
                if (current.path in leftoverLabels) {
                    viewModel.showLeftoverDeleteConfirmation(listOf(current))
                } else {
                    viewModel.showDeleteConfirmation(current)
                }
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

    if (uiState.newFileDialog) {
        NewFileDialog(
            onDismiss = viewModel::dismissNewFileDialog,
            onConfirm = viewModel::createFile,
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

    val leftoverDeleteState = uiState.leftoverDeleteConfirmation
    if (leftoverDeleteState != null) {
        UploadLeftoverDeleteDialog(
            targets   = leftoverDeleteState,
            onDismiss = viewModel::dismissLeftoverDeleteConfirmation,
            onConfirm = viewModel::confirmLeftoverDelete,
        )
    }

    if (uiState.multiDeleteConfirmation) {
        MultiDeleteConfirmDialog(
            count     = uiState.selectedFiles.size,
            onDismiss = viewModel::dismissMultiDeleteConfirmation,
            onConfirm = viewModel::confirmMultiDelete,
        )
    }

    // Un fichier du dossier porte déjà le nom de celui qui vient d'être choisi : rien n'est envoyé
    // tant que l'utilisateur n'a pas répondu. L'état est dans le ViewModel, pas en rememberSaveable.
    // Pas tant que l'app est verrouillée (voir [locked]) : l'état reste dans le ViewModel.
    val uploadConflict = uiState.uploadConflict
    if (uploadConflict != null && !locked) {
        UploadReplaceDialog(
            name      = uploadConflict.name,
            // Le dossier de destination du choix, pas le dossier affiché : l'utilisateur a pu naviguer
            // pendant la vérification, et le panneau d'un split peut redonner le dialogue hors contexte.
            folder    = uploadConflict.folder,
            onDismiss = viewModel::dismissUploadConflict,
            onConfirm = viewModel::confirmUploadReplace,
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
    uploadEnabled: Boolean,
) {
    TopAppBar(
        title = {
            if (isSelectionMode) {
                Text(
                    text       = pluralStringResource(R.plurals.sftp_selection_count, selectionCount, selectionCount),
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
                    // Out of the selection the button leaves the browser: it goes back, it cancels nothing.
                    contentDescription = stringResource(
                        if (isSelectionMode) R.string.action_cancel else R.string.action_back,
                    ),
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
                // Désactivé pendant que le nom du fichier choisi est cherché sur le serveur : sans
                // retour, un second toucher ouvrirait un second sélecteur sur un envoi pas encore décidé.
                IconButton(onClick = onUpload, enabled = uploadEnabled) {
                    Icon(
                        Lucide.Upload,
                        contentDescription = stringResource(R.string.sftp_action_upload),
                        tint = if (uploadEnabled) TextSecondary else TextDisabled,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = NearBlack),
    )
}

// ── Breadcrumb + bouton "+" (nouveau dossier / nouveau fichier) ───────────────

@Composable
private fun BreadcrumbAndAddBar(
    path: String,
    onNavigateTo: (String) -> Unit,
    onCreateFolder: () -> Unit,
    onCreateFile: () -> Unit,
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
            // Dans la branche : le menu ne reste pas ouvert en mémoire pendant une sélection.
            var showCreateMenu by remember { mutableStateOf(false) }
            Spacer(Modifier.width(Spacing.Sm))
            // La Box donne au menu son ancre : il s'ouvre sous le bouton.
            Box {
                BtnAddPrimary(onClick = { showCreateMenu = true })
                CreateDropdownMenu(
                    expanded         = showCreateMenu,
                    onDismiss        = { showCreateMenu = false },
                    onCreateFolder   = {
                        showCreateMenu = false
                        onCreateFolder()
                    },
                    onCreateFile     = {
                        showCreateMenu = false
                        onCreateFile()
                    },
                )
            }
        }
    }
}

@Composable
private fun CreateDropdownMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onCreateFolder: () -> Unit,
    onCreateFile: () -> Unit,
) {
    DropdownMenu(
        expanded         = expanded,
        onDismissRequest = onDismiss,
        modifier         = Modifier.background(Surface),
    ) {
        CreateMenuItem(
            icon    = Lucide.FolderPlus,
            label   = stringResource(R.string.sftp_action_new_folder),
            onClick = onCreateFolder,
        )
        CreateMenuItem(
            icon    = Lucide.FilePlus,
            label   = stringResource(R.string.sftp_action_new_file),
            onClick = onCreateFile,
        )
    }
}

@Composable
private fun CreateMenuItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Text(
                text       = label,
                fontFamily = SpaceGroteskFamily,
                fontSize   = 13.sp,
                color      = TextPrimary,
            )
        },
        leadingIcon = {
            Icon(
                imageVector        = icon,
                contentDescription = null,
                tint               = Gold,
                modifier           = Modifier.size(16.dp),
            )
        },
        onClick = onClick,
    )
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
            Lucide.Plus,
            contentDescription = stringResource(R.string.action_create),
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
                // In landscape the sheet is lower than its actions: they scroll, Delete included.
                .verticalScroll(rememberScrollState())
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
                    text       = displayEntryName(file.name),
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
    // rememberSaveable: the name survives a rotation (the dialog itself is held by the ViewModel).
    // A folder name is no secret, so the Bundle may carry it.
    var dirName by rememberSaveable(stateSaver = boundedTextSaver(SavedTextLimits.SFTP_NAME_BYTES)) { mutableStateOf("") }
    // The rule of the manager, said here before the server has a say: what is wrong with the name,
    // and a confirm button that waits for a valid one.
    val check = checkEntryName(dirName)

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
                errorText     = check.helpRes?.let { stringResource(it) },
            )
        },
        confirmLabel    = stringResource(R.string.action_create),
        confirmEnabled  = check.canConfirm,
        confirmDanger   = false,
        onConfirm       = { checkEntryName(dirName).let { if (it.canConfirm) onConfirm(it.name) } },
    )
}

@Composable
private fun NewFileDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    // rememberSaveable: the name survives a rotation (the dialog itself is held by the ViewModel).
    // A file name is no secret, so the Bundle may carry it.
    var fileName by rememberSaveable(stateSaver = boundedTextSaver(SavedTextLimits.SFTP_NAME_BYTES)) { mutableStateOf("") }
    val check = checkEntryName(fileName)

    DialogShell(
        onDismiss = onDismiss,
        accent    = Burgundy,
        icon      = Lucide.FilePlus,
        title     = stringResource(R.string.sftp_dialog_new_file_title),
        body = {
            DialogTextField(
                value         = fileName,
                onValueChange = { fileName = it },
                placeholder   = stringResource(R.string.sftp_dialog_new_file_hint),
                errorText     = check.helpRes?.let { stringResource(it) },
            )
        },
        confirmLabel    = stringResource(R.string.action_create),
        confirmEnabled  = check.canConfirm,
        confirmDanger   = false,
        onConfirm       = { checkEntryName(fileName).let { if (it.canConfirm) onConfirm(it.name) } },
    )
}

@Composable
private fun RenameDialog(
    file: SftpFile,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    // Saved by path: a rotation keeps what was typed, another file starts from its own name. Bounded,
    // so a huge pasted text cannot overflow the saved state of the activity.
    var name by rememberSaveable(file.path, stateSaver = boundedTextSaver(SavedTextLimits.SFTP_NAME_BYTES)) {
        mutableStateOf(file.name)
    }
    val check = checkEntryName(name)

    DialogShell(
        onDismiss = onDismiss,
        accent    = Gold,
        icon      = Lucide.Pencil,
        title     = stringResource(R.string.sftp_dialog_rename_title),
        body = {
            DialogTextField(
                value         = name,
                onValueChange = { name = it },
                placeholder   = displayEntryName(file.name),
                errorText     = check.helpRes?.let { stringResource(it) },
            )
        },
        confirmLabel    = stringResource(R.string.action_confirm),
        // The text itself has to change, not only its edge spaces: an entry called "notes " opens with
        // the typed text equal to its name, and one tap must not rename it to "notes".
        confirmEnabled  = check.canConfirm && name != file.name && check.name != file.name,
        confirmDanger   = false,
        onConfirm       = {
            checkEntryName(name).let {
                if (it.canConfirm && name != file.name && it.name != file.name) onConfirm(it.name)
            }
        },
    )
}

@Composable
private fun ChmodDialog(
    file: SftpFile,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    // The nine boxes are one Int (rwx rwx rwx): a single value for the saved state, kept by path.
    var bits by rememberSaveable(file.path) { mutableIntStateOf(file.permissions and PERMISSION_BITS_MASK) }
    val computed = bits

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
                ChmodRow(stringResource(R.string.sftp_chmod_owner),  bits, 0b100_000_000, 0b010_000_000, 0b001_000_000) { bits = it }
                ChmodRow(stringResource(R.string.sftp_chmod_group),  bits, 0b000_100_000, 0b000_010_000, 0b000_001_000) { bits = it }
                ChmodRow(stringResource(R.string.sftp_chmod_others), bits, 0b000_000_100, 0b000_000_010, 0b000_000_001) { bits = it }
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
    bits: Int,
    readMask: Int,
    writeMask: Int,
    executeMask: Int,
    onBitsChange: (Int) -> Unit,
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
        listOf(readMask, writeMask, executeMask).forEach { mask ->
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Checkbox(
                    checked         = bits and mask != 0,
                    onCheckedChange = { on -> onBitsChange(withPermissionBit(bits, mask, on)) },
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

/** The nine permission bits (rwx for owner, group, others). */
internal const val PERMISSION_BITS_MASK = 0x1FF

/**
 * [bits] with the permission bit [mask] set or cleared. Only the nine permission bits can change:
 * anything outside [PERMISSION_BITS_MASK] in [bits] or in [mask] is dropped.
 */
internal fun withPermissionBit(bits: Int, mask: Int, on: Boolean): Int {
    val kept = bits and PERMISSION_BITS_MASK
    val bit = mask and PERMISSION_BITS_MASK
    return if (on) kept or bit else kept and bit.inv()
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
                    text       = stringResource(R.string.sftp_dialog_delete_message, displayEntryName(file.name)),
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
        title     = pluralStringResource(R.plurals.sftp_dialog_multi_delete_title, count, count),
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

/**
 * Asked when the file to send has the name of a file already in the folder: replacing it destroys
 * its content, so the confirm button is the red one of the deletions. Cancel, the back gesture and a
 * touch outside all dismiss it, and nothing is sent. [folder] is named in the sentence: it is the
 * destination of the pick, which is not always the folder on screen.
 */
@Composable
private fun UploadReplaceDialog(
    name: String,
    folder: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    DialogShell(
        onDismiss = onDismiss,
        accent    = ErrorRed,
        icon      = Lucide.TriangleAlert,
        title     = stringResource(R.string.sftp_upload_replace_title),
        body = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
                Text(
                    text       = stringResource(R.string.sftp_upload_replace_message, name, folder),
                    fontFamily = SpaceGroteskFamily,
                    fontSize   = 13.sp,
                    color      = TextPrimary,
                )
                Text(
                    text       = stringResource(R.string.sftp_upload_replace_warning),
                    fontFamily = SpaceGroteskFamily,
                    fontSize   = 12.sp,
                    color      = ErrorRed,
                )
            }
        },
        confirmLabel    = stringResource(R.string.sftp_upload_replace_action),
        confirmEnabled  = true,
        confirmDanger   = true,
        onConfirm       = onConfirm,
    )
}

/**
 * The banner at the head of a folder that holds the temporary files of interrupted uploads. It only
 * informs and offers two ways out: delete them (after a confirmation that lists them) or dismiss the
 * banner for this folder. The rows keep their badge either way.
 */
@Composable
private fun UploadLeftoversBanner(
    count: Int,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val shape = RoundedCornerShape(Radii.Md)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(WarningAmber.copy(alpha = 0.12f), shape)
            .border(1.dp, WarningAmber.copy(alpha = 0.40f), shape)
            .padding(horizontal = Spacing.Md, vertical = Spacing.Sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.Xs),
    ) {
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
        ) {
            Icon(
                Lucide.TriangleAlert,
                contentDescription = null,
                tint     = WarningAmber,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text       = pluralStringResource(R.plurals.sftp_leftover_banner, count, count),
                fontFamily = SpaceGroteskFamily,
                fontSize   = 13.sp,
                color      = TextPrimary,
                modifier   = Modifier.weight(1f),
            )
        }
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Sm, Alignment.End),
        ) {
            // The visible word is short; a screen reader says what it acts on.
            val dismissDescription = stringResource(R.string.sftp_leftover_banner_dismiss_description)
            val deleteDescription = stringResource(R.string.sftp_leftover_banner_delete_description)
            TextButton(
                onClick  = onDismiss,
                modifier = Modifier.semantics { contentDescription = dismissDescription },
            ) {
                Text(
                    text       = stringResource(R.string.sftp_leftover_banner_dismiss),
                    fontFamily = SpaceGroteskFamily,
                    fontSize   = 13.sp,
                    color      = TextSecondary,
                )
            }
            TextButton(
                onClick  = onDelete,
                modifier = Modifier.semantics { contentDescription = deleteDescription },
            ) {
                Text(
                    text       = stringResource(R.string.action_delete),
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize   = 13.sp,
                    color      = ErrorRed,
                )
            }
        }
    }
}

/**
 * Asked before the leftovers of interrupted uploads are deleted. [targets] is the list fixed when the
 * dialog was opened, and the dialog names every file it will delete, by its exact name (invisible
 * characters made visible): nothing else goes. It warns, in the red of the deletions, that an upload
 * still running from another device would fail.
 */
@Composable
private fun UploadLeftoverDeleteDialog(
    targets: List<SftpFile>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val count = targets.size
    DialogShell(
        onDismiss = onDismiss,
        accent    = ErrorRed,
        icon      = Lucide.Trash2,
        title     = pluralStringResource(R.plurals.sftp_leftover_delete_title, count, count),
        body = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
                Text(
                    text = if (count == 1) {
                        stringResource(
                            R.string.sftp_leftover_delete_message_single,
                            leftoverOriginalLabel(targets.single().name).orEmpty(),
                        )
                    } else {
                        stringResource(R.string.sftp_leftover_delete_message_multi)
                    },
                    fontFamily = SpaceGroteskFamily,
                    fontSize   = 13.sp,
                    color      = TextPrimary,
                )
                // The warning comes before the list: in landscape or with a large font the list gives way
                // (it takes what is left, at most 140 dp), the warning never disappears.
                Text(
                    text       = stringResource(R.string.sftp_leftover_delete_warning),
                    fontFamily = SpaceGroteskFamily,
                    fontSize   = 12.sp,
                    color      = ErrorRed,
                )
                // The exact names that will be deleted. Scrolls past a few lines (a folder may hold many),
                // and a fade at the bottom says there is more to see.
                val listScroll = rememberScrollState()
                // The color the dialog really paints: Material tints a container that is the surface color
                // by its tonal elevation (6 dp for a dialog), so the fade ends on that tint, not on the
                // plain color, or a grey band shows under the list.
                val scheme = MaterialTheme.colorScheme
                val fadeEnd = if (Surface == scheme.surface) {
                    scheme.surfaceColorAtElevation(LocalAbsoluteTonalElevation.current)
                } else {
                    Surface
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .heightIn(max = 140.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(listScroll),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        targets.forEach { file ->
                            Text(
                                text       = displayEntryName(file.name),
                                fontFamily = JetBrainsMonoFamily,
                                fontSize   = 11.sp,
                                color      = TextSecondary,
                            )
                        }
                    }
                    if (listScroll.canScrollForward) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(24.dp)
                                .background(
                                    androidx.compose.ui.graphics.Brush.verticalGradient(
                                        listOf(androidx.compose.ui.graphics.Color.Transparent, fadeEnd),
                                    ),
                                ),
                        )
                    }
                }
            }
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
        // The body scrolls: in landscape the dialog is lower than its content, and AlertDialog would
        // squeeze it (the chmod boxes overlapped).
        text = { Box(Modifier.verticalScroll(rememberScrollState())) { body() } },
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
    errorText: String? = null,
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
        // Shown under the field and part of it for accessibility: the reason a name is refused is
        // announced by TalkBack when it appears (polite live region), not only painted in red.
        // The slot is always there, empty when nothing is wrong, and it keeps the height of two lines
        // (the longest sentences wrap): the field does not grow and shrink under the finger while a
        // name like "..config" is typed, and the buttons of the dialog stay where they are.
        supportingText = {
            Text(
                text     = errorText.orEmpty(),
                color    = ErrorRed,
                fontSize = 12.sp,
                minLines = 2,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        },
        isError       = errorText != null,
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
            errorBorderColor     = ErrorRed,
            cursorColor          = Gold,
            errorCursorColor     = ErrorRed,
            focusedContainerColor   = SurfaceVariant,
            unfocusedContainerColor = SurfaceVariant,
            errorContainerColor     = SurfaceVariant,
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
private fun sortOrderLabel(order: SortOrder): String {
    val criterion = when (order) {
        SortOrder.NAME_ASC, SortOrder.NAME_DESC -> R.string.sftp_sort_name
        SortOrder.SIZE_ASC, SortOrder.SIZE_DESC -> R.string.sftp_sort_size
        SortOrder.DATE_ASC, SortOrder.DATE_DESC -> R.string.sftp_sort_date
    }
    val direction = when (order) {
        SortOrder.NAME_ASC, SortOrder.SIZE_ASC, SortOrder.DATE_ASC -> R.string.sftp_sort_ascending
        SortOrder.NAME_DESC, SortOrder.SIZE_DESC, SortOrder.DATE_DESC -> R.string.sftp_sort_descending
    }
    return stringResource(R.string.sftp_sort_option, stringResource(criterion), stringResource(direction))
}

// ── Disconnected overlay ──────────────────────────────────────────────────────

/**
 * Le panneau de l'état déconnecté, qui remplace le contenu de l'explorateur.
 *
 * Deux cas, qui n'appellent pas le même geste :
 *  - le canal SFTP est perdu mais la session SSH vit : « Reconnecter » le rouvre ;
 *  - la session SSH elle-même est tombée ([sessionEnded]) : rien à rouvrir d'ici, la liste d'hôtes
 *    reconnecte. Le panneau le dit et ne propose que le retour, sans bouton qui ne peut pas aboutir.
 *    Le plein écran s'ouvre depuis la liste d'hôtes ou depuis le terminal, et « Retour » revient à
 *    l'écran d'où il vient : le texte ne nomme donc ni l'un ni l'autre. Dans le panneau d'un split
 *    ([ownsSession] faux) il n'y a pas de liste derrière : le terminal à côté se reconnecte, le
 *    panneau le suit, et le bouton ferme le split.
 *
 * [detail] complète le cas du canal perdu (la raison d'une reconnexion qui échoue).
 */
@Composable
private fun SftpDisconnectedOverlay(
    sessionEnded: Boolean,
    ownsSession: Boolean,
    detail: String?,
    onReconnect: () -> Unit,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(
        when {
            !sessionEnded -> R.string.sftp_disconnected
            ownsSession   -> R.string.sftp_session_ended
            else          -> R.string.sftp_session_ended_pane
        },
    )
    val buttonLabel = stringResource(
        when {
            !sessionEnded -> R.string.sftp_reconnect
            ownsSession   -> R.string.action_back
            else          -> R.string.action_close
        },
    )
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
            text       = title,
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize   = 14.sp,
            color      = ErrorRed,
            textAlign  = TextAlign.Center,
        )
        if (!sessionEnded && detail != null) {
            Text(
                text       = detail,
                fontFamily = SpaceGroteskFamily,
                fontSize   = 12.sp,
                color      = TextSecondary,
                textAlign  = TextAlign.Center,
            )
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(Radii.Md))
                .background(Burgundy, RoundedCornerShape(Radii.Md))
                .clickable(onClick = if (sessionEnded) onLeave else onReconnect)
                .padding(horizontal = Spacing.Lg, vertical = Spacing.Sm),
        ) {
            Text(
                text       = buttonLabel,
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
            val hidden = transfers.size - MAX_TRANSFER_ROWS
            Text(
                text       = pluralStringResource(R.plurals.sftp_transfer_more, hidden, hidden),
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

/**
 * Nom proposé pour le fichier choisi : `DISPLAY_NAME` du fournisseur, à défaut
 * le dernier segment de l'URI (voir [pickedFileName]), à défaut un nom générique.
 *
 * Seule la requête au `ContentResolver` et le dernier recours vivent ici. Le nom
 * reste une donnée d'une autre application : l'envoi le contrôle
 * (`uploadEntryName`).
 */
private fun resolveDisplayName(context: android.content.Context, uri: android.net.Uri): String {
    val providerName = try {
        context.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null, null, null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx != -1) cursor.getString(idx) else null
            } else null
        }
    } catch (e: Exception) {
        null
    }
    return pickedFileName(providerName, uri.lastPathSegment)
        ?: context.getString(R.string.sftp_upload_default_name)
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
