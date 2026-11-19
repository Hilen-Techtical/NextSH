// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sftp

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.techtical.nextsh.R
import fr.techtical.nextsh.core.ssh.SFTP_CONNECTION_LOST_MESSAGE
import fr.techtical.nextsh.core.ssh.SFTP_NO_SESSION_MESSAGE
import fr.techtical.nextsh.core.ssh.SFTP_SESSION_NOT_FOUND_MESSAGE
import fr.techtical.nextsh.core.ssh.SessionHandoff
import fr.techtical.nextsh.core.ssh.SftpManager
import fr.techtical.nextsh.core.ssh.SshSessionManager
import fr.techtical.nextsh.core.ssh.TransferDirection
import fr.techtical.nextsh.core.ssh.TransferStatus
import fr.techtical.nextsh.core.ssh.TransferState
import fr.techtical.nextsh.core.ssh.TransferTracker
import fr.techtical.nextsh.core.ssh.UploadTarget
import fr.techtical.nextsh.core.ssh.isUploadLeftover
import fr.techtical.nextsh.core.ssh.sftpMessageRes
import fr.techtical.nextsh.core.ssh.uploadEntryName
import fr.techtical.nextsh.domain.model.SftpFile
import fr.techtical.nextsh.domain.model.SortOrder
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.domain.usecase.ChangePermissionsUseCase
import fr.techtical.nextsh.domain.usecase.CreateDirectoryUseCase
import fr.techtical.nextsh.domain.usecase.CreateFileUseCase
import fr.techtical.nextsh.domain.usecase.DeleteFileUseCase
import fr.techtical.nextsh.domain.usecase.ListDirectoryUseCase
import fr.techtical.nextsh.domain.usecase.PreviewFileUseCase
import fr.techtical.nextsh.domain.usecase.RenameFileUseCase
import fr.techtical.nextsh.service.SftpPickerHoldController
import fr.techtical.nextsh.service.SftpTransferService
import fr.techtical.nextsh.shared.util.LogId
import fr.techtical.nextsh.shared.util.errorClass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

// ── Dialog state data classes ─────────────────────────────────────────────────

data class RenameDialogState(
    val file: SftpFile,
    val currentName: String = file.name,
)

data class ChmodDialogState(
    val file: SftpFile,
    val currentPermissions: Int = file.permissions,
)

data class DeleteConfirmState(
    val file: SftpFile,
)

/**
 * An upload that waits for the answer of the user: a file already has the name of the one that was
 * picked. Everything the upload needs is here, so that "Replace" starts it without the picker, which
 * is long gone, and without reading the current folder of the browser, which may have changed.
 *
 * Kept in the state of the ViewModel and not in the saved state of the screen: the conflict is found
 * after the picker returned, on a ViewModel that is alive, and nothing is sent if the activity dies
 * before the answer.
 */
data class UploadConflictState(
    val sessionId: String,
    val remotePath: String,
    val sourceUri: Uri,
    /** The sanitized name of the file, the one the dialog shows and the transfer displays. */
    val name: String,
    val fileSize: Long,
) {
    /**
     * The folder the file is to go to, the parent of [remotePath] as it is (the root is `/`). The dialog
     * names it: the user may have gone to another folder since the file was picked (only the upload
     * button is held back during the check), and the dialog of a split pane may come back out of context.
     */
    val folder: String
        get() = remotePath.substringBeforeLast('/', "").ifEmpty { "/" }
}

// ── Preview state ─────────────────────────────────────────────────────────────

/**
 * État de l'aperçu de fichier affiché dans [SftpPreviewOverlay].
 *
 * Le contenu ([content]) doit être effacé (`Arrays.fill(it, 0)`) à la fermeture
 * de l'overlay pour éviter de laisser des données sensibles en mémoire.
 */
data class PreviewState(
    val file: SftpFile,
    val content: ByteArray? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
) {
    val isImage: Boolean get() = file.isImageFile
    val isText: Boolean get() = file.isTextFile

    // ByteArray nécessite equals/hashCode personnalisés pour éviter les comparaisons par référence.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PreviewState) return false
        if (file != other.file) return false
        if (isLoading != other.isLoading) return false
        if (error != other.error) return false
        // Comparaison structurelle null-safe des ByteArray
        val c1 = content
        val c2 = other.content
        if (c1 == null && c2 == null) return true
        if (c1 == null || c2 == null) return false
        return c1.contentEquals(c2)
    }

    override fun hashCode(): Int {
        var result = file.hashCode()
        result = 31 * result + (content?.contentHashCode() ?: 0)
        result = 31 * result + isLoading.hashCode()
        result = 31 * result + (error?.hashCode() ?: 0)
        return result
    }
}

// ── UI State ──────────────────────────────────────────────────────────────────

data class SftpBrowserUiState(
    val currentPath: String = "/",
    val files: List<SftpFile> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val sortOrder: SortOrder = SortOrder.NAME_ASC,
    val showHiddenFiles: Boolean = false,
    val hostLabel: String = "",
    // Dialog states
    val renameDialog: RenameDialogState? = null,
    val mkdirDialog: Boolean = false,
    val newFileDialog: Boolean = false,
    val chmodDialog: ChmodDialogState? = null,
    val deleteConfirmation: DeleteConfirmState? = null,
    // Multi-select state
    val selectedFiles: Set<String> = emptySet(),   // Set of file paths
    val isSelectionMode: Boolean = false,
    val multiDeleteConfirmation: Boolean = false,
    // Preview state
    val previewState: PreviewState? = null,
    // Active transfers
    val activeTransfers: List<TransferState> = emptyList(),
    /** An upload held back because a file already has this name, until the user answers. */
    val uploadConflict: UploadConflictState? = null,
    /**
     * True from the moment a file is picked until the browser has decided what to do with it (start the
     * upload, ask, or refuse): the name is being looked up on the server, which can take a while on a
     * link that hangs. The screen holds its upload button back meanwhile.
     */
    val uploadCheckInProgress: Boolean = false,
    // Snackbar message
    val message: String? = null,
    /** Colorise le message en erreur et le laisse affiché plus longtemps. */
    val messageIsError: Boolean = false,
    // Disconnection state: true when the link to the host was lost
    val isDisconnected: Boolean = false,
    /**
     * With [isDisconnected]: the SSH session itself is gone, not only its SFTP channel. The channel
     * can be reopened from the browser, the session cannot (the host list reconnects), so the
     * screen offers the way back instead of a reconnection that could not work.
     */
    val sessionEnded: Boolean = false,
    /**
     * The files of the current folder that are the temporary files of an interrupted upload
     * (`.name.nextsh-part-xxxxxxxx`, see [isUploadLeftover]), minus those of an upload of this app that is
     * still running. They are also in [files] (listed even with the hidden files off), this is the subset
     * the banner and the badge speak about.
     */
    val uploadLeftovers: List<SftpFile> = emptyList(),
    /** The user chose "Dismiss" on the banner for the current folder (until the browser is closed). */
    val leftoverBannerDismissed: Boolean = false,
    /**
     * The leftovers the deletion dialog is about, fixed when it was opened: a refresh meanwhile does not
     * add a file to the list the user reads. Null when no such dialog is open.
     */
    val leftoverDeleteConfirmation: List<SftpFile>? = null,
)

/**
 * ViewModel pour l'explorateur SFTP.
 *
 * Cycle de vie SFTP :
 * - [init] prend sa référence sur la session SSH, ouvre le client SFTP et navigue vers le home
 *   directory.
 * - [onCleared] ferme proprement le client SFTP, puis rend la référence sur la session.
 *
 * Référence sur la session. L'explorateur est un utilisateur de la session comme un autre et
 * possède la sienne, qu'il rend à [onCleared] (voir la note de [SshSessionManager]). Elle vient de
 * deux endroits : la liste d'hôtes, qui vient de connecter pour lui, lui a proposé la session
 * ([SessionHandoff]), et la reprendre le rend propriétaire de la référence de la liste ; sinon la
 * session est celle d'un onglet terminal, qu'il ne peut pas fermer, et il prend la sienne avec
 * [SshSessionManager.acquire]. Fermer l'onglet n'emporte donc pas l'explorateur, ni un transfert
 * qu'il a lancé. Seul le panneau d'un split ne possède rien (voir [init], `ownsSession`).
 *
 * Navigation :
 * - [navigateTo] liste un répertoire et empile le chemin précédent dans [pathHistory].
 * - [navigateUp] remonte au dossier parent.
 * - [goBack] dépile l'historique ; retourne false si on ne peut pas remonter davantage.
 *
 * CRUD :
 * - [createDirectory] crée un répertoire dans le dossier courant.
 * - [createFile] crée un fichier vide dans le dossier courant, sans jamais remplacer un élément.
 * - [deleteFile] supprime un fichier ou répertoire (avec confirmation préalable).
 * - [renameFile] renomme un fichier ou répertoire.
 * - [changePermissions] modifie les permissions POSIX d'une entrée.
 */
@HiltViewModel
class SftpBrowserViewModel @Inject constructor(
    private val sftpManager: SftpManager,
    private val listDirectoryUseCase: ListDirectoryUseCase,
    private val createDirectoryUseCase: CreateDirectoryUseCase,
    private val createFileUseCase: CreateFileUseCase,
    private val deleteFileUseCase: DeleteFileUseCase,
    private val renameFileUseCase: RenameFileUseCase,
    private val changePermissionsUseCase: ChangePermissionsUseCase,
    private val previewFileUseCase: PreviewFileUseCase,
    private val transferTracker: TransferTracker,
    @ApplicationContext private val context: Context,
    private val sessionManager: SshSessionManager,
    private val sessionHandoff: SessionHandoff,
    private val pickerHold: SftpPickerHoldController,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SftpBrowserUiState())
    val uiState: StateFlow<SftpBrowserUiState> = _uiState.asStateFlow()

    /** Pile des chemins précédents, permet de revenir en arrière. */
    private val pathHistory = ArrayDeque<String>()

    /** Number of the last [navigateTo]: a listing that is not the last one started is dropped. */
    private var navigationGeneration = 0

    /** The target (path, push to the history) of the most recent [navigateTo] that has not ended yet. */
    private var pendingNavigation: Pair<String, Boolean>? = null

    /** The folders whose leftover banner the user dismissed, for the life of this ViewModel. */
    private val dismissedLeftoverFolders = mutableSetOf<String>()

    @Volatile
    private var sessionId: String = ""

    /**
     * Vrai tant que ce ViewModel possède une référence sur [sessionId], reprise de la liste d'hôtes
     * ou prise à la session (voir la note de la classe) : [onCleared] la rend, une seule fois.
     */
    @Volatile
    private var holdsReference = false

    /**
     * True while this ViewModel is one of the users of the SFTP client of [sessionId], counted by
     * [SftpManager.openSftp]: set once the client is open ([openSftpAsUser]; a refusal counts for
     * nothing). [onCleared] gives the use back, once, only while it is held, and [reconnect] gives
     * it back before it asks for a new one, so the count never drifts: a use counted twice keeps the
     * client open for ever, a use given back twice closes the client under a transfer that still
     * holds one. A ViewModel cleared while the channel opens gives its use back from the opening.
     */
    @Volatile
    private var holdsSftp = false

    /**
     * True once [onCleared] ran. A channel that was still opening at that moment finishes anyway (see
     * [openSftpAsUser]) and finds this set: [onCleared] saw no use to give back, so the opening gives
     * back its own.
     */
    @Volatile
    private var cleared = false

    /**
     * Whether the screen of this ViewModel is on show, set by the screen from its lifecycle
     * ([setVisible]). False until the screen says otherwise: nobody sees a screen that is not there yet.
     */
    private val visible = MutableStateFlow(false)

    /** The collector of [TransferTracker.transfers], restarted with the session ([init]). */
    private var transfersJob: Job? = null

    /** Scope de nettoyage qui survit à l'annulation de viewModelScope. */
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Initialisation ────────────────────────────────────────────────────────────

    /**
     * Initialise le ViewModel avec la session SSH.
     * Appeler une seule fois depuis [SftpBrowserScreen].
     *
     * [ownsSession] est faux pour le panneau SFTP d'un split : son ViewModel est rangé sous une clé
     * du nav entry du terminal et survit au panneau jusqu'à la sortie de la route, il ne doit donc
     * pas tenir la session d'un onglet fermé entre-temps. Il l'emprunte : ni reprise de la liste
     * ([SessionHandoff.claim]), ni référence ([SshSessionManager.acquire]), ni rien à rendre. Un
     * panneau visible a toujours son onglet à côté, qui tient une référence, et un transfert lancé
     * depuis le panneau réserve la sienne. Le client SFTP, lui, s'ouvre et se ferme comme partout.
     */
    fun init(sessionId: String, hostLabel: String = "", ownsSession: Boolean = true) {
        if (this.sessionId == sessionId) return  // déjà initialisé
        // Une autre session que celle qu'on tenait : on ne garde pas l'ancienne ouverte pour rien.
        closeSession(this.sessionId, holdsReference, holdsSftp)
        holdsSftp = false
        this.sessionId = sessionId
        // La session proposée par la liste d'hôtes, sinon celle d'un onglet : sans référence, le
        // client SFTP qui suit échoue de lui-même si la session n'existe plus.
        holdsReference = ownsSession && (sessionHandoff.claim(sessionId) || sessionManager.acquire(sessionId))
        _uiState.update {
            it.copy(hostLabel = hostLabel, isLoading = true, error = null, isDisconnected = false, sessionEnded = false)
        }
        observeTransfers()

        viewModelScope.launch {
            // Counted as a user of the client only once the channel is open, and given back by the
            // opening itself when this ViewModel is gone by then (see openSftpAsUser).
            val openResult = openSftpAsUser(sessionId) ?: return@launch
            when (openResult) {
                is SshResult.Success -> {
                    // Récupérer le home directory
                    val homePath = when (val homeResult = sftpManager.getHomeDirectory(sessionId)) {
                        is SshResult.Success -> homeResult.data
                        is SshResult.Error   -> {
                            Timber.w("Impossible de récupérer le home SFTP, fallback /")
                            "/"
                        }
                    }
                    navigateTo(homePath, pushHistory = false)
                }
                is SshResult.Error -> {
                    Timber.e("Cannot open SFTP for session ${LogId.of(sessionId)} (${openResult.code})")
                    // The raw message tells a session that is gone from a channel that did not open.
                    val sessionGone = openResult.message == SFTP_SESSION_NOT_FOUND_MESSAGE
                    val errorText = sftpText(openResult.message)
                    _uiState.update {
                        it.copy(isLoading = false, error = errorText, isDisconnected = sessionGone, sessionEnded = sessionGone)
                    }
                }
            }
        }
    }

    // ── Navigation ────────────────────────────────────────────────────────────────

    /**
     * Navigue vers [path] en listant son contenu.
     *
     * @param pushHistory si true (défaut), empile le chemin courant dans l'historique.
     */
    fun navigateTo(path: String, pushHistory: Boolean = true) {
        // Numbered when it starts: a listing that finishes after a more recent one was started is out of
        // date, and must not overwrite it (a slow folder listed before a quick one, for instance).
        val generation = ++navigationGeneration
        // Remembered until this navigation, the most recent, ends: a refresh meanwhile (end of an upload,
        // sort, hidden files) reloads THIS target and not the folder still on screen, which would take the
        // place of the user's navigation and drop it.
        pendingNavigation = path to pushHistory
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            val currentPath = _uiState.value.currentPath
            val sortOrder = _uiState.value.sortOrder
            val showHidden = _uiState.value.showHiddenFiles

            // The names of the temporary files being written or known to be gone, read BEFORE the listing
            // and again after it: one that was being written at the start, or that was created and ended
            // during the listing, is in the union. Names, not paths: a folder reached through a link, or a
            // replacement that followed a link, shows the same file under another path.
            val tempsBefore = sftpManager.knownUploadTempNames()
            val result = listDirectoryUseCase(sessionId, path, sortOrder, showHidden)
            if (generation != navigationGeneration) return@launch
            // The most recent navigation ends, with a listing or an error: nothing is pending any more.
            pendingNavigation = null

            when (result) {
                is SshResult.Success -> {
                    if (pushHistory && currentPath != path) {
                        pathHistory.addLast(currentPath)
                    }
                    // The temporary files of interrupted uploads, except those of an upload of this app that
                    // is under way or just ended (any session, any link followed): that one is an ordinary
                    // hidden file (shown with the hidden files, and gone when the upload ends), not a
                    // leftover. Matched on the name of the temporary file the manager created.
                    val leftovers = result.data.filter(::isUploadLeftover)
                    val known = tempsBefore + sftpManager.knownUploadTempNames()
                    val ownTemps = leftovers.filter { it.name in known }
                    val ownTempPaths = ownTemps.mapTo(HashSet()) { it.path }
                    val listed = if (showHidden) result.data else result.data.filter { it.path !in ownTempPaths }
                    // A listing that worked says the link is there, whatever the screen thought before.
                    _uiState.update {
                        it.copy(
                            currentPath    = path,
                            files          = listed,
                            uploadLeftovers = leftovers.filter { temp -> temp.path !in ownTempPaths },
                            leftoverBannerDismissed = path in dismissedLeftoverFolders,
                            isLoading      = false,
                            error          = null,
                            isDisconnected = false,
                            sessionEnded   = false,
                        )
                    }
                    Timber.d("SFTP navigateTo done: ${result.data.size} entries")
                }
                is SshResult.Error -> {
                    Timber.w("SFTP navigateTo failed (${result.code})")
                    // The raw message tells a lost link from another failure, before it is translated.
                    val linkLost = isLinkLoss(result.message)
                    // What the link really is: the SSH session may be gone, or only its SFTP channel.
                    val sessionEnded = linkLost && !sessionManager.isConnected(sessionId)
                    val errorText = sftpText(result.message)
                    _uiState.update {
                        it.copy(
                            isLoading      = false,
                            error          = errorText,
                            isDisconnected = linkLost,
                            sessionEnded   = sessionEnded,
                        )
                    }
                }
            }
        }
    }

    /**
     * Remonte au répertoire parent du chemin courant.
     */
    fun navigateUp() {
        val current = _uiState.value.currentPath
        val parent = current.substringBeforeLast('/', "/").ifEmpty { "/" }
        if (current == "/") return  // déjà à la racine
        navigateTo(parent)
    }

    /**
     * Dépile l'historique de navigation.
     *
     * @return false si l'historique est vide (l'écran peut alors se fermer).
     */
    fun goBack(): Boolean {
        if (pathHistory.isEmpty()) return false
        val previous = pathHistory.removeLast()
        navigateTo(previous, pushHistory = false)
        return true
    }

    /**
     * Recharge le répertoire courant, ou la destination d'une navigation en cours : un rechargement
     * automatique (fin d'un envoi, tri, fichiers cachés) ne doit pas annuler la navigation de l'utilisateur.
     */
    fun refresh() {
        val pending = pendingNavigation
        if (pending != null) {
            navigateTo(pending.first, pending.second)
        } else {
            navigateTo(_uiState.value.currentPath, pushHistory = false)
        }
    }

    // ── Filtres et tri ────────────────────────────────────────────────────────────

    fun toggleHiddenFiles() {
        _uiState.update { it.copy(showHiddenFiles = !it.showHiddenFiles) }
        refresh()
    }

    fun changeSortOrder(order: SortOrder) {
        if (_uiState.value.sortOrder == order) return
        _uiState.update { it.copy(sortOrder = order) }
        refresh()
    }

    // ── Dialog state: Rename ──────────────────────────────────────────────────────

    fun showRenameDialog(file: SftpFile) {
        _uiState.update { it.copy(renameDialog = RenameDialogState(file)) }
    }

    fun dismissRenameDialog() {
        _uiState.update { it.copy(renameDialog = null) }
    }

    // ── Dialog state: Mkdir ────────────────────────────────────────────────────────

    fun showMkdirDialog() {
        _uiState.update { it.copy(mkdirDialog = true) }
    }

    fun dismissMkdirDialog() {
        _uiState.update { it.copy(mkdirDialog = false) }
    }

    // ── Dialog state: New file ─────────────────────────────────────────────────────

    fun showNewFileDialog() {
        _uiState.update { it.copy(newFileDialog = true) }
    }

    fun dismissNewFileDialog() {
        _uiState.update { it.copy(newFileDialog = false) }
    }

    // ── Dialog state: Chmod ────────────────────────────────────────────────────────

    fun showChmodDialog(file: SftpFile) {
        _uiState.update { it.copy(chmodDialog = ChmodDialogState(file)) }
    }

    fun dismissChmodDialog() {
        _uiState.update { it.copy(chmodDialog = null) }
    }

    // ── Dialog state: Delete confirmation ─────────────────────────────────────────

    fun showDeleteConfirmation(file: SftpFile) {
        _uiState.update { it.copy(deleteConfirmation = DeleteConfirmState(file)) }
    }

    fun dismissDeleteConfirmation() {
        _uiState.update { it.copy(deleteConfirmation = null) }
    }

    // ── CRUD actions ──────────────────────────────────────────────────────────────

    /**
     * Crée un répertoire dans le dossier courant. Le dialogue se ferme au lancement, avant le résultat
     * (comme pour [renameFile]) : resté ouvert, un second toucher sur « Créer » lancerait une seconde
     * création et afficherait en rouge l'échec d'un dossier qui existe déjà. Le résultat, succès ou
     * échec, passe par le message.
     */
    fun createDirectory(name: String) {
        val parentPath = _uiState.value.currentPath
        _uiState.update { it.copy(mkdirDialog = false) }
        viewModelScope.launch {
            when (val result = createDirectoryUseCase(sessionId, parentPath, name)) {
                is SshResult.Success -> {
                    refresh()
                    showMessage(context.getString(R.string.sftp_success_mkdir), isError = false)
                    Timber.i("SFTP mkdir done")
                }
                is SshResult.Error -> {
                    showMessage(sftpText(result.message), isError = true)
                    noteLinkLoss(result.message)
                    Timber.w("SFTP mkdir failed (${result.code})")
                }
            }
        }
    }

    /**
     * Crée un fichier vide dans le dossier courant. Le dialogue se ferme au lancement, avant le
     * résultat (comme pour [renameFile]) : resté ouvert, un second toucher sur « Créer » lancerait une
     * seconde création, refusée parce que le fichier existe déjà, et l'erreur rouge ferait croire que
     * rien n'a été créé. Après un succès la liste est rechargée et le message le dit, après un échec le
     * message d'erreur le dit (un nom déjà pris compris : rien n'est jamais remplacé).
     */
    fun createFile(name: String) {
        val parentPath = _uiState.value.currentPath
        _uiState.update { it.copy(newFileDialog = false) }
        viewModelScope.launch {
            when (val result = createFileUseCase(sessionId, parentPath, name)) {
                is SshResult.Success -> {
                    refresh()
                    showMessage(context.getString(R.string.sftp_success_create_file), isError = false)
                    Timber.i("SFTP createFile done")
                }
                is SshResult.Error -> {
                    showMessage(sftpText(result.message), isError = true)
                    noteLinkLoss(result.message)
                    Timber.w("SFTP createFile failed (${result.code})")
                }
            }
        }
    }

    /**
     * Supprime le fichier ou répertoire confirmé.
     */
    fun deleteFile(file: SftpFile) {
        viewModelScope.launch {
            _uiState.update { it.copy(deleteConfirmation = null) }
            when (val result = deleteFileUseCase(sessionId, file.path, file.isDirectory)) {
                is SshResult.Success -> {
                    refresh()
                    showMessage(context.getString(R.string.sftp_success_delete), isError = false)
                    Timber.i("SFTP delete done (directory=${file.isDirectory})")
                }
                is SshResult.Error -> {
                    showMessage(sftpText(result.message), isError = true)
                    noteLinkLoss(result.message)
                    Timber.w("SFTP delete failed (${result.code})")
                }
            }
        }
    }

    /**
     * Renomme un fichier ou répertoire.
     *
     * @param oldPath chemin absolu de l'entrée à renommer.
     * @param newName nouveau nom (sans chemin).
     */
    fun renameFile(oldPath: String, newName: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(renameDialog = null) }
            when (val result = renameFileUseCase(sessionId, oldPath, newName)) {
                is SshResult.Success -> {
                    refresh()
                    showMessage(context.getString(R.string.sftp_success_rename), isError = false)
                    Timber.i("SFTP rename done")
                }
                is SshResult.Error -> {
                    showMessage(sftpText(result.message), isError = true)
                    noteLinkLoss(result.message)
                    Timber.w("SFTP rename failed (${result.code})")
                }
            }
        }
    }

    /**
     * Modifie les permissions POSIX d'une entrée.
     *
     * @param path chemin absolu de l'entrée cible.
     * @param permissions valeur entière des permissions POSIX (de 0 à 511).
     */
    fun changePermissions(path: String, permissions: Int) {
        viewModelScope.launch {
            _uiState.update { it.copy(chmodDialog = null) }
            when (val result = changePermissionsUseCase(sessionId, path, permissions)) {
                is SshResult.Success -> {
                    refresh()
                    showMessage(context.getString(R.string.sftp_success_chmod), isError = false)
                    Timber.i("SFTP chmod %o done", permissions)
                }
                is SshResult.Error -> {
                    showMessage(sftpText(result.message), isError = true)
                    noteLinkLoss(result.message)
                    Timber.w("SFTP chmod failed (${result.code})")
                }
            }
        }
    }

    // ── Multi-select ──────────────────────────────────────────────────────────────

    /**
     * Entre en mode sélection avec [file] pré-sélectionné.
     */
    fun enterSelectionMode(file: SftpFile) {
        _uiState.update {
            it.copy(
                isSelectionMode = true,
                selectedFiles   = setOf(file.path),
            )
        }
    }

    /**
     * Ajoute ou retire [file] de la sélection courante.
     * Sort du mode sélection si la sélection devient vide.
     */
    fun toggleSelection(file: SftpFile) {
        _uiState.update { state ->
            val updated = if (file.path in state.selectedFiles) {
                state.selectedFiles - file.path
            } else {
                state.selectedFiles + file.path
            }
            state.copy(
                selectedFiles   = updated,
                isSelectionMode = updated.isNotEmpty(),
            )
        }
    }

    /**
     * Sélectionne tous les fichiers du répertoire courant.
     */
    fun selectAll() {
        _uiState.update { state ->
            state.copy(selectedFiles = state.files.map { it.path }.toSet())
        }
    }

    /**
     * Vide la sélection et quitte le mode sélection.
     */
    fun clearSelection() {
        _uiState.update {
            it.copy(
                isSelectionMode = false,
                selectedFiles   = emptySet(),
            )
        }
    }

    /**
     * Retourne la liste des [SftpFile] correspondant aux chemins sélectionnés.
     */
    fun getSelectedFiles(): List<SftpFile> {
        val state = _uiState.value
        return state.files.filter { it.path in state.selectedFiles }
    }

    // ── Multi-delete ──────────────────────────────────────────────────────────────

    fun showMultiDeleteConfirmation() {
        _uiState.update { it.copy(multiDeleteConfirmation = true) }
    }

    fun dismissMultiDeleteConfirmation() {
        _uiState.update { it.copy(multiDeleteConfirmation = false) }
    }

    /**
     * Supprime tous les fichiers sélectionnés un par un, puis rafraîchit et vide la sélection.
     */
    fun confirmMultiDelete() {
        val toDelete = getSelectedFiles()
        _uiState.update { it.copy(multiDeleteConfirmation = false) }

        viewModelScope.launch {
            var errorCount = 0
            for (file in toDelete) {
                when (val result = deleteFileUseCase(sessionId, file.path, file.isDirectory)) {
                    is SshResult.Success -> Timber.i("SFTP multi-delete done (directory=${file.isDirectory})")
                    is SshResult.Error   -> {
                        errorCount++
                        Timber.w("SFTP multi-delete failed (${result.code})")
                    }
                }
            }
            clearSelection()
            refresh()
            val deletedCount = toDelete.size - errorCount
            showMessage(multiDeleteSummary(deletedCount, errorCount), isError = errorCount > 0)
        }
    }

    /**
     * What the deletion of several entries says: the number that went, then the number that did not
     * (two deleted and one that could not be, for instance). When none went, the first part would
     * say that zero entries were deleted, so the sentence says that none could be deleted instead.
     */
    private fun multiDeleteSummary(deletedCount: Int, errorCount: Int): String {
        fun deleted() =
            context.resources.getQuantityString(R.plurals.sftp_multi_delete_done, deletedCount, deletedCount)
        fun failed() =
            context.resources.getQuantityString(R.plurals.sftp_multi_delete_failed, errorCount, errorCount)
        return when {
            errorCount == 0 -> deleted()
            deletedCount == 0 -> context.getString(R.string.sftp_multi_delete_none)
            else -> context.getString(R.string.sftp_multi_delete_partial, deleted(), failed())
        }
    }

    // ── Restes d'envois interrompus ───────────────────────────────────────────────

    /** The user chose "Dismiss" on the banner: it stays away for this folder until the browser is closed. */
    fun dismissLeftoverBanner() {
        val folder = _uiState.value.currentPath
        dismissedLeftoverFolders.add(folder)
        _uiState.update { it.copy(leftoverBannerDismissed = true) }
    }

    /**
     * Opens the deletion dialog for [targets], the leftovers the user is about to delete. The list is
     * fixed here: only entries that are leftovers (an ordinary file with the shape of the name, see
     * [isUploadLeftover]) are kept, so that a folder or a link never reaches the dialog.
     */
    fun showLeftoverDeleteConfirmation(targets: List<SftpFile>) {
        val leftovers = targets.filter(::isUploadLeftover)
        if (leftovers.isEmpty()) return
        _uiState.update { it.copy(leftoverDeleteConfirmation = leftovers) }
    }

    fun dismissLeftoverDeleteConfirmation() {
        _uiState.update { it.copy(leftoverDeleteConfirmation = null) }
    }

    /**
     * Deletes the leftovers the dialog listed, one by one, by their exact name, then refreshes and says
     * how many went (the same sentence as the deletion of several items).
     *
     * Never recursive: a leftover is an ordinary file, and `isDirectory` is false here whatever the entry
     * says. A folder that took the name since the listing is refused by the server (a remove on a
     * folder fails), and nothing is lost. Nothing is logged but the outcome: no name, no path.
     */
    fun confirmLeftoverDelete() {
        val targets = _uiState.value.leftoverDeleteConfirmation ?: return
        _uiState.update { it.copy(leftoverDeleteConfirmation = null) }

        viewModelScope.launch {
            var errorCount = 0
            var deletedCount = 0
            for (file in targets) {
                // Looked at again just before each deletion: an upload of this app may have started
                // writing a temporary file of this name since the dialog opened. Such a file is skipped.
                if (file.name in sftpManager.knownUploadTempNames()) continue
                when (val result = deleteFileUseCase(sessionId, file.path, isDirectory = false)) {
                    is SshResult.Success -> {
                        deletedCount++
                        Timber.i("SFTP leftover delete done")
                    }
                    is SshResult.Error   -> {
                        errorCount++
                        noteLinkLoss(result.message)
                        Timber.w("SFTP leftover delete failed (${result.code})")
                    }
                }
            }
            refresh()
            // Everything skipped: nothing was deleted and nothing failed, nothing to say.
            if (deletedCount + errorCount > 0) {
                showMessage(multiDeleteSummary(deletedCount, errorCount), isError = errorCount > 0)
            }
        }
    }

    /**
     * Efface le message snackbar courant.
     */
    fun clearMessage() {
        _uiState.update { it.copy(message = null, messageIsError = false) }
    }

    // ── Transferts SFTP ───────────────────────────────────────────────────────────

    /** IDs des transferts déjà détectés comme terminés (évite les refresh multiples). */
    private val completedTransferIds = mutableSetOf<String>()

    /**
     * Observe [TransferTracker.transfers] et met à jour [SftpBrowserUiState.activeTransfers].
     * Appelé à l'initialisation de la session, et de nouveau si l'explorateur change de session : le
     * collecteur de la précédente s'arrête.
     *
     * Le tracker est un singleton qui garde les transferts terminés pour toute la vie du processus.
     * Un explorateur ne s'occupe donc que des transferts de SA session, et un échec qu'il annonce
     * est acquitté dans le tracker ([TransferTracker.acknowledge]) : annoncé une fois, dans
     * l'explorateur de la session concernée, jamais rejoué par un explorateur ouvert plus tard ni
     * montré dans celui d'une autre session.
     *
     * Annoncé par un explorateur dont l'écran est visible ([setVisible]) et par lui seul : le
     * ViewModel d'un panneau de split continue de collecter quand l'explorateur plein écran de la
     * même session est ouvert par-dessus, et s'il passait le premier il acquitterait un échec que
     * personne ne voit. Un échec vu pendant l'invisibilité reste donc dans le tracker, et le retour
     * à l'écran l'annonce (le flux de visibilité relance le traitement du dernier état connu).
     */
    private fun observeTransfers() {
        transfersJob?.cancel()
        transfersJob = viewModelScope.launch {
            combine(transferTracker.transfers, visible) { transferMap, isVisible ->
                transferMap to isVisible
            }.collect { (transferMap, isVisible) ->
                val transfers = transferMap.values.toList()
                _uiState.update { state ->
                    state.copy(activeTransfers = transfers)
                }
                for (transfer in transfers) {
                    if (transfer.request.sessionId != sessionId) continue
                    // Auto-refresh quand un upload se termine
                    if (transfer.status == TransferStatus.COMPLETED
                        && transfer.request.direction == TransferDirection.UPLOAD
                        && completedTransferIds.add(transfer.request.id)
                    ) {
                        Timber.d("Upload completed, auto-refresh")
                        refresh()
                    }
                    // Un echec n'apparaissait nulle part : le bandeau ne montre
                    // que les transferts en cours ou en attente, et la
                    // notification disparait avec le service. L'utilisateur se
                    // retrouvait avec un fichier vide et aucune explication.
                    if (transfer.status == TransferStatus.FAILED && isVisible) {
                        // Acknowledged before it is said: the next emission no longer holds it.
                        transferTracker.acknowledge(transfer.request.id)
                        // The tracker carries the text of the SFTP layer (a French literal) or one the
                        // service already resolved: the first is translated, the second shown as it is.
                        val reason = transfer.errorMessage?.let { sftpText(it) }
                            ?: context.getString(R.string.sftp_transfer_failed)
                        _uiState.update { state ->
                            state.copy(
                                message = context.getString(
                                    R.string.sftp_transfer_failed_detail,
                                    transfer.request.displayName,
                                    reason,
                                ),
                                messageIsError = true,
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Says whether the screen of this ViewModel is on show. Called by the screen from its lifecycle:
     * visible from the start of the screen, not visible once it stops or leaves the composition.
     * Only a visible ViewModel announces the failure of a transfer, and acknowledges it ([observeTransfers]).
     * Back on screen, a ViewModel announces the failure that came in while it was not.
     */
    fun setVisible(isVisible: Boolean) {
        visible.value = isVisible
    }

    /**
     * Lance le téléchargement d'un fichier distant vers un URI local SAF.
     *
     * Démarre [SftpTransferService] via un Intent foreground service.
     *
     * @param file           fichier SFTP à télécharger.
     * @param destinationUri URI SAF (content://) de destination choisi par l'utilisateur.
     */
    fun downloadFile(file: SftpFile, destinationUri: Uri) =
        downloadFile(sessionId, file.path, file.name, file.size, destinationUri)

    /**
     * Variante à partir des seules données nécessaires au transfert.
     *
     * L'écran ne conserve que ces valeurs pendant l'aller-retour vers le
     * sélecteur de destination, car elles sont sauvegardables dans l'état
     * d'instance, contrairement à un [SftpFile].
     *
     * [sessionId] est passé explicitement et non lu sur ce ViewModel : au
     * retour du sélecteur après recréation de l'activité, le rappel du lanceur
     * s'exécute AVANT [init], sur un ViewModel neuf dont l'identifiant de
     * session est encore vide. L'écran, lui, tient l'identifiant de sa route de
     * navigation, restaurée par le système.
     */
    fun downloadFile(
        sessionId: String,
        remotePath: String,
        displayName: String,
        fileSize: Long,
        destinationUri: Uri,
    ) {
        if (!ensureSession(sessionId)) return
        val intent = Intent(context, SftpTransferService::class.java).apply {
            putExtra(SftpTransferService.EXTRA_SESSION_ID,   sessionId)
            putExtra(SftpTransferService.EXTRA_REMOTE_PATH,  remotePath)
            putExtra(SftpTransferService.EXTRA_LOCAL_URI,    destinationUri.toString())
            putExtra(SftpTransferService.EXTRA_DIRECTION,    TransferDirection.DOWNLOAD.name)
            putExtra(SftpTransferService.EXTRA_FILE_SIZE,    fileSize)
            putExtra(SftpTransferService.EXTRA_DISPLAY_NAME, displayName)
        }
        if (!startTransferService(intent)) return
        Timber.i("SftpBrowserViewModel: download started ($fileSize bytes)")
        showMessage(context.getString(R.string.sftp_transfer_download_started, displayName), isError = false)
    }

    /**
     * Lance l'envoi d'un fichier local vers le répertoire courant sur le serveur.
     *
     * Démarre [SftpTransferService] via un Intent foreground service, sauf si le nom est déjà pris
     * (voir la variante à six arguments, qui détaille le déroulé).
     *
     * @param sourceUri   URI SAF (content://) du fichier source local.
     * @param displayName nom donné par le fournisseur de documents, assaini par
     *                    [uploadEntryName] avant usage. Un nom inexploitable
     *                    affiche « Nom de fichier invalide » sans démarrer le service.
     * @param fileSize    taille du fichier en octets (0 si inconnue).
     * @param isVirtual   `true` si le fichier choisi est un document virtuel (voir
     *                    `isVirtualDocument`) : il n'a pas de contenu brut, l'envoi est
     *                    refusé par un message qui dit de l'exporter, sans démarrer le service.
     */
    fun uploadFile(sourceUri: Uri, displayName: String, fileSize: Long = 0L, isVirtual: Boolean = false) =
        uploadFile(sessionId, _uiState.value.currentPath, sourceUri, displayName, fileSize, isVirtual)

    /**
     * Variante avec session et répertoire de destination explicites.
     *
     * Même raison que pour [downloadFile] : au retour du sélecteur après
     * recréation de l'activité, ni l'identifiant de session ni le répertoire
     * courant de ce ViewModel ne sont encore renseignés. Le répertoire vaudrait
     * la racine et le fichier atterrirait au mauvais endroit.
     *
     * Déroulé. Après les refus de la session, du document virtuel et du nom, la session est
     * réservée ([SftpManager.reserve], avec l'identifiant passé ici : le canal SFTP de l'explorateur
     * peut ne pas être ouvert) et [SftpManager.checkUploadTarget] cherche ce qui porte déjà le nom :
     * - rien : le service démarre, sans remplacement ;
     * - un fichier : rien ne démarre, [SftpBrowserUiState.uploadConflict] pose le dialogue, et
     *   [confirmUploadReplace] lance l'envoi avec remplacement (ou [dismissUploadConflict] l'abandonne) ;
     * - un dossier, ou une entrée qui ne se remplace pas : un message, rien ne démarre ;
     * - une vérification impossible : si le lien est perdu, l'écran passe à l'état déconnecté ; sinon
     *   le service démarre quand même, sans remplacement, et le serveur refuse un nom pris (`EXCL`).
     * La réservation est rendue après la décision. Jamais de remplacement sans la confirmation.
     * [SftpBrowserUiState.uploadCheckInProgress] est vrai pendant tout ce déroulé, pour que l'écran
     * retienne son bouton d'envoi.
     *
     * @param displayName nom donné par le fournisseur de documents, assaini par
     *                    [uploadEntryName] avant usage. Un nom inexploitable
     *                    affiche « Nom de fichier invalide » sans démarrer le service.
     * @param isVirtual   `true` si le fichier choisi est un document virtuel (voir
     *                    `isVirtualDocument`) : l'envoi est refusé par un message qui dit
     *                    de l'exporter, sans démarrer le service. Le refus passe après celui
     *                    de la session et avant celui du nom : le nom ne change rien à un
     *                    document qu'on ne peut pas lire.
     */
    fun uploadFile(
        sessionId: String,
        remoteDir: String,
        sourceUri: Uri,
        displayName: String,
        fileSize: Long = 0L,
        isVirtual: Boolean = false,
    ) {
        if (!ensureSession(sessionId)) return
        if (isVirtual) {
            Timber.w("SftpBrowserViewModel: envoi refusé, document virtuel")
            _uiState.update {
                it.copy(message = context.getString(R.string.sftp_error_source_virtual), messageIsError = true)
            }
            return
        }
        // Le nom vient du fournisseur de documents, donc d'une autre application :
        // on ne le colle jamais tel quel au dossier distant (voir uploadEntryName).
        val name = uploadEntryName(displayName)
        if (name == null) {
            Timber.w("SftpBrowserViewModel: envoi refusé, nom de fichier inexploitable")
            _uiState.update {
                it.copy(message = context.getString(R.string.sftp_error_name_invalid), messageIsError = true)
            }
            return
        }
        val remotePath = if (remoteDir.endsWith('/')) {
            "$remoteDir$name"
        } else {
            "$remoteDir/$name"
        }
        // Raised before the coroutine starts and lowered when it ends, whatever way: the screen holds
        // its upload button back meanwhile, the check can take as long as the server answers.
        _uiState.update { it.copy(uploadCheckInProgress = true) }
        viewModelScope.launch {
            try {
                checkTargetAndDecide(sessionId, remotePath, sourceUri, name, fileSize)
            } finally {
                _uiState.update { it.copy(uploadCheckInProgress = false) }
            }
        }
    }

    /**
     * The check of [uploadFile], from the reservation to the decision. The SFTP channel is reserved for
     * the check, with the session of the caller: the browser may not have opened its own yet (a
     * ViewModel that is new, before [init]), and the check must not run on a channel that closes under
     * it.
     *
     * The reservation is not cancellable, like the opening of the channel in [openSftpAsUser]:
     * [SftpManager.reserve] counts a user of the SFTP client inside the call, and a cancellation that
     * cut it short would leave that user counted and never given back. It runs to its end, and a scope
     * cancelled meanwhile (the ViewModel was cleared) stops right after it, the reservation going back
     * in the `finally`, before anything is checked or started.
     */
    private suspend fun checkTargetAndDecide(
        sessionId: String,
        remotePath: String,
        sourceUri: Uri,
        name: String,
        fileSize: Long,
    ) {
        val reservation = when (val reserved = withContext(NonCancellable) { sftpManager.reserve(sessionId) }) {
            is SshResult.Success -> reserved.data
            is SshResult.Error -> {
                // The raw message tells a session that is gone from a channel that did not open.
                if (reserved.message == SFTP_SESSION_NOT_FOUND_MESSAGE) {
                    markDisconnected(sessionEnded = true)
                } else {
                    showMessage(sftpText(reserved.message), isError = true)
                }
                Timber.w("SftpBrowserViewModel: upload refused, SFTP not available (${reserved.code})")
                return
            }
        }
        try {
            // The scope may have been cancelled while the reservation was being taken (it is not
            // cancellable): nothing is checked or started for a ViewModel that is gone.
            currentCoroutineContext().ensureActive()
            when (val check = sftpManager.checkUploadTarget(sessionId, remotePath)) {
                is SshResult.Success -> when (check.data) {
                    UploadTarget.FREE -> startUpload(sessionId, remotePath, sourceUri, name, fileSize, replaceExisting = false)
                    UploadTarget.FILE -> {
                        // Nothing starts, nothing is said: the dialog asks, and "Replace" starts the upload.
                        Timber.i("SftpBrowserViewModel: upload held, a file has this name")
                        _uiState.update {
                            it.copy(uploadConflict = UploadConflictState(sessionId, remotePath, sourceUri, name, fileSize))
                        }
                    }
                    UploadTarget.DIRECTORY -> {
                        Timber.w("SftpBrowserViewModel: upload refused, a folder has this name")
                        showMessage(context.getString(R.string.sftp_upload_target_is_folder, name), isError = true)
                    }
                    UploadTarget.OTHER -> {
                        Timber.w("SftpBrowserViewModel: upload refused, the entry with this name cannot be replaced")
                        showMessage(context.getString(R.string.sftp_upload_target_not_replaceable, name), isError = true)
                    }
                }
                is SshResult.Error -> if (isLinkLoss(check.message)) {
                    Timber.w("SftpBrowserViewModel: upload refused, link lost during the check (${check.code})")
                    showMessage(sftpText(check.message), isError = true)
                    // sessionId is the argument: this ViewModel may not have its own yet, see above.
                    markDisconnected(sessionEnded = !sessionManager.isConnected(sessionId))
                } else {
                    // The check is a courtesy, EXCL is the guarantee: a check that cannot tell
                    // (permission, unknown, lstat not supported) sends without replacement, and the
                    // server refuses the name if it is taken.
                    Timber.w("SftpBrowserViewModel: upload target check failed (${check.code}), sending without replacement")
                    startUpload(sessionId, remotePath, sourceUri, name, fileSize, replaceExisting = false)
                }
            }
        } finally {
            // Given back after the decision: in the free case the service is started by now, and
            // takes its own reservation. Not cancellable, a reservation not given back is lost.
            withContext(NonCancellable) { reservation.release() }
        }
    }

    /**
     * Starts the transfer service for an upload, and says so. [replaceExisting] is true only after the
     * user answered "Replace"; it is always put in the intent, so the service never guesses.
     */
    private fun startUpload(
        sessionId: String,
        remotePath: String,
        sourceUri: Uri,
        name: String,
        fileSize: Long,
        replaceExisting: Boolean,
    ) {
        val intent = Intent(context, SftpTransferService::class.java).apply {
            putExtra(SftpTransferService.EXTRA_SESSION_ID,   sessionId)
            putExtra(SftpTransferService.EXTRA_REMOTE_PATH,  remotePath)
            putExtra(SftpTransferService.EXTRA_LOCAL_URI,    sourceUri.toString())
            putExtra(SftpTransferService.EXTRA_DIRECTION,    TransferDirection.UPLOAD.name)
            putExtra(SftpTransferService.EXTRA_FILE_SIZE,    fileSize)
            putExtra(SftpTransferService.EXTRA_DISPLAY_NAME, name)
            putExtra(SftpTransferService.EXTRA_REPLACE_EXISTING, replaceExisting)
        }
        if (!startTransferService(intent)) return
        Timber.i("SftpBrowserViewModel: upload started ($fileSize bytes, replace=$replaceExisting)")
        showMessage(context.getString(R.string.sftp_transfer_upload_started, name), isError = false)
    }

    /**
     * The user answered "Replace" to the dialog of [SftpBrowserUiState.uploadConflict]: starts the
     * upload that was held back, this time allowed to overwrite. The dialog closes before anything
     * starts, so a second touch finds no conflict and does nothing. Does nothing either without a
     * conflict. The session is checked again, the dialog may have waited a long time.
     */
    fun confirmUploadReplace() {
        val conflict = _uiState.value.uploadConflict ?: return
        _uiState.update { it.copy(uploadConflict = null) }
        if (!ensureSession(conflict.sessionId)) return
        startUpload(
            conflict.sessionId,
            conflict.remotePath,
            conflict.sourceUri,
            conflict.name,
            conflict.fileSize,
            replaceExisting = true,
        )
    }

    /** The user answered "Cancel" to the dialog of [SftpBrowserUiState.uploadConflict]: nothing is sent, nothing is said. */
    fun dismissUploadConflict() {
        _uiState.update { it.copy(uploadConflict = null) }
    }

    /**
     * Annule un transfert en cours depuis l'application.
     *
     * Jusqu'ici l'annulation n'existait que dans la notification, donc
     * inaccessible si la permission de notification etait refusee ou si
     * l'utilisateur restait dans l'app.
     */
    fun cancelTransfer(transferId: String) {
        val intent = Intent(context, SftpTransferService::class.java).apply {
            action = SftpTransferService.ACTION_CANCEL_TRANSFER
            putExtra(SftpTransferService.EXTRA_TRANSFER_ID, transferId)
        }
        try {
            // startService et non startForegroundService : le service tourne
            // deja et cette branche ne passe jamais au premier plan.
            context.startService(intent)
            Timber.i("SftpBrowserViewModel: cancellation requested for transfer ${LogId.of(transferId)}")
        } catch (e: Exception) {
            Timber.w("SftpBrowserViewModel: cannot cancel transfer ${LogId.of(transferId)} (${errorClass(e)})")
            // Repli : marquer l'annulation dans le tracker. La boucle de copie
            // ne s'arretera pas, mais l'etat affiche reste coherent.
            transferTracker.cancel(transferId)
        }
    }

    /**
     * Refuse un transfert sans session utilisable, en le disant.
     *
     * Sans ce garde-fou, un identifiant vide partait jusqu'au service, qui
     * echouait a ouvrir le client SFTP et marquait le transfert en echec. Le
     * bandeau ne montrant que les transferts en cours ou en attente, l'echec
     * etait totalement invisible : notification apparue puis disparue en
     * quelques millisecondes, et un fichier vide chez l'utilisateur.
     *
     * Elle refuse aussi une session SSH qui n'existe plus (le système a coupé la connexion pendant le
     * sélecteur de fichiers, par exemple) : le service ne démarre pas et ne dit pas « démarré », et
     * l'écran passe à l'état déconnecté, qui dit quoi faire. Seule la session SSH compte ici, pas
     * le canal SFTP de l'explorateur : le service ouvre le sien, et pour un envoi la vérification du
     * nom ouvre le sien aussi, par [SftpManager.reserve].
     */
    private fun ensureSession(sessionId: String): Boolean {
        if (sessionId.isBlank()) {
            Timber.w("SftpBrowserViewModel: transfert refusé, session non initialisée")
            _uiState.update {
                it.copy(message = context.getString(R.string.sftp_transfer_no_session), messageIsError = true)
            }
            return false
        }
        if (!sessionManager.isConnected(sessionId)) {
            Timber.w("SftpBrowserViewModel: transfer refused, session ${LogId.of(sessionId)} is gone")
            markDisconnected(sessionEnded = true)
            return false
        }
        return true
    }

    /**
     * Whether the link to the host is still there, asked before a file picker opens so that the user
     * is not made to choose a file only to be told afterwards that nothing can be sent. When the SSH
     * session is gone the screen goes to its disconnected state and this returns false: the picker
     * must not open. True while the browser has no session yet (the first [init] has not run), there
     * is nothing to say then.
     */
    fun checkLink(): Boolean {
        val sid = sessionId
        if (sid.isEmpty() || sessionManager.isConnected(sid)) return true
        markDisconnected(sessionEnded = true)
        return false
    }

    /**
     * Keeps the app in the foreground, with the session of this browser, while the system file
     * picker is open (see [SftpPickerHoldController]). Returns the id of the hold, which the screen
     * keeps in its saved state and gives back to [endPickerHold] when the picker returns, or null
     * when no hold could be taken: the picker then opens as it always did.
     */
    fun beginPickerHold(): String? = sessionId.takeIf { it.isNotEmpty() }?.let { pickerHold.begin(it) }

    /**
     * Gives back the hold of the picker that returned, with a result or without (a cancelled picker
     * returns too). Takes the id as an argument and reads no state of this ViewModel: after a
     * recreation of the activity it runs on a new ViewModel, before [init], like [downloadFile].
     */
    fun endPickerHold(holdId: String?) {
        if (holdId != null) pickerHold.end(holdId)
    }

    /**
     * Démarre le service de transfert en absorbant un refus du système.
     *
     * Sur Android 15+, un `ForegroundServiceStartNotAllowedException` est levé
     * si le quota `dataSync` de 6 h est épuisé et que l'app n'est pas au
     * premier plan. Sans ce filet, l'appel non protégé faisait planter l'app au
     * lieu d'expliquer la situation.
     *
     * @return false si le transfert n'a pas pu démarrer, un message étant déjà
     *         posé dans l'état pour l'utilisateur.
     */
    private fun startTransferService(intent: Intent): Boolean = try {
        ContextCompat.startForegroundService(context, intent)
        true
    } catch (e: Exception) {
        Timber.w("SftpBrowserViewModel: transfer service start refused (${errorClass(e)})")
        _uiState.update {
            it.copy(message = context.getString(R.string.sftp_transfer_start_refused), messageIsError = true)
        }
        false
    }

    // ── Aperçu de fichier ─────────────────────────────────────────────────────────

    /**
     * Ouvre l'aperçu du fichier [file].
     *
     * Met immédiatement l'état à `isLoading = true`, puis charge le contenu
     * en coroutine. Le contenu est limité par [PreviewFileUseCase] selon le type.
     */
    fun openPreview(file: SftpFile) {
        _uiState.update { it.copy(previewState = PreviewState(file = file, isLoading = true)) }
        viewModelScope.launch {
            val result = previewFileUseCase(
                sessionId = sessionId,
                path      = file.path,
                isImage   = file.isImageFile,
            )
            // Said before the update: the lambda of an update may run again, and reads resources.
            val errorText = (result as? SshResult.Error)?.let { sftpText(it.message) }
            _uiState.update { state ->
                when (result) {
                    is SshResult.Success -> state.copy(
                        previewState = state.previewState?.copy(
                            content   = result.data,
                            isLoading = false,
                            error     = null,
                        )
                    )
                    is SshResult.Error -> state.copy(
                        previewState = state.previewState?.copy(
                            content   = null,
                            isLoading = false,
                            error     = errorText,
                        )
                    )
                }
            }
        }
    }

    /**
     * Ferme l'aperçu et efface le contenu chargé en mémoire.
     *
     * Le ByteArray est rempli de zéros avant abandon pour éviter de laisser
     * des données de fichier sensibles en mémoire heap.
     */
    fun dismissPreview() {
        val currentContent = _uiState.value.previewState?.content
        currentContent?.let { java.util.Arrays.fill(it, 0) }
        _uiState.update { it.copy(previewState = null) }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    /**
     * Détecte si un message d'erreur SFTP correspond à un lien perdu : la connexion qui tombe en
     * cours d'opération ([SFTP_CONNECTION_LOST_MESSAGE]), ou un client SFTP qui n'est plus là
     * ([SFTP_NO_SESSION_MESSAGE], la session a pris fin et son canal avec elle). Utilisé pour
     * basculer [SftpBrowserUiState.isDisconnected].
     *
     * Lit le message brut de [SftpManager], avant [sftpText] : l'unique producteur des erreurs de
     * listing ne renvoie que les textes fixes de `SftpMessages.kt`, jamais le message d'une
     * exception ("broken pipe", "channel is closed"...), qu'une IOException devient
     * [SFTP_CONNECTION_LOST_MESSAGE].
     */
    private fun isLinkLoss(message: String): Boolean =
        message == SFTP_CONNECTION_LOST_MESSAGE || message == SFTP_NO_SESSION_MESSAGE

    /**
     * Puts the browser in its disconnected state if the raw [message] of a failed operation says the
     * link is lost. Any other failure (a refused permission, a name already taken) says nothing about
     * the link and leaves the screen as it is.
     */
    private fun noteLinkLoss(message: String) {
        if (!isLinkLoss(message)) return
        markDisconnected(sessionEnded = !sessionManager.isConnected(sessionId))
    }

    /**
     * The disconnected state: [sessionEnded] when the SSH session is gone (the screen offers the way
     * back), else only the SFTP channel is lost (it offers to reconnect).
     */
    private fun markDisconnected(sessionEnded: Boolean) {
        _uiState.update { it.copy(isLoading = false, isDisconnected = true, sessionEnded = sessionEnded) }
    }

    /**
     * [message] dans la langue de l'utilisateur. Les textes fixes de la couche SFTP sont des
     * littéraux français que [sftpMessageRes] associe à une ressource ; tout autre message (un
     * texte que le service de transfert a déjà résolu) est affiché tel quel.
     */
    private fun sftpText(message: String): String =
        sftpMessageRes(message)?.let { context.getString(it) } ?: message

    /** Affiche [text] dans le bandeau de message ; [isError] le colorise et le laisse plus longtemps. */
    private fun showMessage(text: String, isError: Boolean) {
        _uiState.update { it.copy(message = text, messageIsError = isError) }
    }

    // ── Reconnexion ───────────────────────────────────────────────────────────────

    /**
     * Tente de rouvrir le canal SFTP après une déconnexion.
     *
     * Une session SSH qui n'existe plus ne se rouvre pas d'ici (la liste d'hôtes reconnecte, avec
     * l'authentification que cela demande) : l'écran passe à l'état « session terminée », sans appel
     * à [SftpManager.openSftp].
     *
     * Sinon, réinitialise [SftpBrowserUiState.isDisconnected] immédiatement, rend l'usage du client
     * SFTP que ce ViewModel tenait, puis retente [SftpManager.openSftp]. Le rendre d'abord garde le
     * compte des utilisateurs juste : [SftpManager.openSftp] compte un utilisateur de plus quand le
     * client est encore enregistré, et [onCleared] n'en rend qu'un. En cas de succès, efface le
     * message resté à l'écran et rafraîchit le répertoire courant. En cas d'échec, repositionne
     * [isDisconnected] à true, avec l'état réel de la session, et affiche l'erreur.
     */
    fun reconnect() {
        val sid = sessionId
        if (sid.isEmpty()) return
        if (!sessionManager.isConnected(sid)) {
            markDisconnected(sessionEnded = true)
            return
        }
        _uiState.update { it.copy(isDisconnected = false, sessionEnded = false, isLoading = true, error = null) }
        viewModelScope.launch {
            if (holdsSftp) {
                holdsSftp = false
                // Not cancellable: the use is given back even if this ViewModel goes meanwhile.
                withContext(NonCancellable) { sftpManager.closeSftp(sid) }
            }
            val result = openSftpAsUser(sid) ?: return@launch
            when (result) {
                is SshResult.Success -> {
                    // The failure that was on screen is about the link that has just been replaced.
                    _uiState.update { it.copy(message = null, messageIsError = false) }
                    refresh()
                }
                is SshResult.Error   -> {
                    val errorText = sftpText(result.message)
                    // The channel may have failed to open because the session died in the meantime.
                    val ended = !sessionManager.isConnected(sid)
                    _uiState.update {
                        it.copy(
                            isDisconnected = true,
                            sessionEnded   = ended,
                            isLoading      = false,
                            error          = errorText,
                        )
                    }
                    Timber.w("SFTP reconnection failed for session ${LogId.of(sid)} (${result.code})")
                }
            }
        }
    }

    // ── Cycle de vie ──────────────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        cleared = true
        closeSession(sessionId, holdsReference, holdsSftp)
        holdsReference = false
        holdsSftp = false
    }

    /**
     * Opens the SFTP client of [sid] and counts this ViewModel as one of its users, [holdsSftp],
     * only once the channel is open: a refusal counts for nothing.
     *
     * The opening is not cancellable. [SftpManager.openSftp] counts the user inside the call: a
     * cancellation that cut it short left the count and [holdsSftp] saying two different things, and
     * the client stayed open for ever (a use counted and never given back), or a use that was never
     * counted was given back later, which closes the client under a transfer. So it runs to its end,
     * and what it finds then decides: if this ViewModel was cleared meanwhile, or moved to another
     * session ([init]), nobody is left to give the use back, so the opening gives it back itself and
     * the caller stops.
     *
     * @return the result of the opening, or null when the ViewModel is gone: there is nothing left to
     *         tell the screen.
     */
    private suspend fun openSftpAsUser(sid: String): SshResult<Unit>? {
        val result = withContext(NonCancellable) { sftpManager.openSftp(sid) }
        if (result is SshResult.Success) {
            if (cleared || sessionId != sid) {
                closeSession(sid, heldSession = false, heldSftp = true)
                return null
            }
            holdsSftp = true
        }
        return result
    }

    /**
     * Ferme le client SFTP de [sid] si ce ViewModel en était un utilisateur ([heldSftp]), puis rend
     * la référence sur la session si [heldSession]. Dans cet ordre : le canal SFTP se ferme sur une
     * session encore ouverte, et c'est cette libération qui peut fermer la session si l'explorateur
     * en était le dernier utilisateur.
     */
    private fun closeSession(sid: String, heldSession: Boolean, heldSftp: Boolean) {
        if (sid.isEmpty()) return
        cleanupScope.launch {
            withContext(NonCancellable) {
                if (heldSftp) sftpManager.closeSftp(sid)
                if (heldSession) sessionManager.release(sid)
            }
        }
    }
}
