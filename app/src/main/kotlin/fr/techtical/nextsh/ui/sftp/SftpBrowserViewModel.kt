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
import fr.techtical.nextsh.core.ssh.SftpManager
import fr.techtical.nextsh.core.ssh.TransferDirection
import fr.techtical.nextsh.core.ssh.TransferStatus
import fr.techtical.nextsh.core.ssh.TransferState
import fr.techtical.nextsh.core.ssh.TransferTracker
import fr.techtical.nextsh.domain.model.SftpFile
import fr.techtical.nextsh.domain.model.SortOrder
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.domain.usecase.ChangePermissionsUseCase
import fr.techtical.nextsh.domain.usecase.CreateDirectoryUseCase
import fr.techtical.nextsh.domain.usecase.DeleteFileUseCase
import fr.techtical.nextsh.domain.usecase.ListDirectoryUseCase
import fr.techtical.nextsh.domain.usecase.PreviewFileUseCase
import fr.techtical.nextsh.domain.usecase.RenameFileUseCase
import fr.techtical.nextsh.service.SftpTransferService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    // Snackbar message
    val message: String? = null,
    /** Colorise le message en erreur et le laisse affiché plus longtemps. */
    val messageIsError: Boolean = false,
    // Disconnection state: true when the SFTP session was lost
    val isDisconnected: Boolean = false,
)

/**
 * ViewModel pour l'explorateur SFTP.
 *
 * Cycle de vie SFTP :
 * - [init] ouvre le client SFTP et navigue vers le home directory.
 * - [onCleared] ferme proprement le client SFTP.
 *
 * Navigation :
 * - [navigateTo] liste un répertoire et empile le chemin précédent dans [pathHistory].
 * - [navigateUp] remonte au dossier parent.
 * - [goBack] dépile l'historique ; retourne false si on ne peut pas remonter davantage.
 *
 * CRUD :
 * - [createDirectory] crée un répertoire dans le dossier courant.
 * - [deleteFile] supprime un fichier ou répertoire (avec confirmation préalable).
 * - [renameFile] renomme un fichier ou répertoire.
 * - [changePermissions] modifie les permissions POSIX d'une entrée.
 */
@HiltViewModel
class SftpBrowserViewModel @Inject constructor(
    private val sftpManager: SftpManager,
    private val listDirectoryUseCase: ListDirectoryUseCase,
    private val createDirectoryUseCase: CreateDirectoryUseCase,
    private val deleteFileUseCase: DeleteFileUseCase,
    private val renameFileUseCase: RenameFileUseCase,
    private val changePermissionsUseCase: ChangePermissionsUseCase,
    private val previewFileUseCase: PreviewFileUseCase,
    private val transferTracker: TransferTracker,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SftpBrowserUiState())
    val uiState: StateFlow<SftpBrowserUiState> = _uiState.asStateFlow()

    /** Pile des chemins précédents, permet de revenir en arrière. */
    private val pathHistory = ArrayDeque<String>()

    @Volatile
    private var sessionId: String = ""

    /** Scope de nettoyage qui survit à l'annulation de viewModelScope. */
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Initialisation ────────────────────────────────────────────────────────────

    /**
     * Initialise le ViewModel avec la session SSH.
     * Appeler une seule fois depuis [SftpBrowserScreen].
     */
    fun init(sessionId: String, hostLabel: String = "") {
        if (this.sessionId == sessionId) return  // déjà initialisé
        this.sessionId = sessionId
        _uiState.update { it.copy(hostLabel = hostLabel, isLoading = true, error = null) }
        observeTransfers()

        viewModelScope.launch {
            when (val openResult = sftpManager.openSftp(sessionId)) {
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
                    Timber.e("Impossible d'ouvrir SFTP pour $sessionId : ${openResult.message}")
                    _uiState.update {
                        it.copy(isLoading = false, error = openResult.message)
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
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            val currentPath = _uiState.value.currentPath
            val sortOrder = _uiState.value.sortOrder
            val showHidden = _uiState.value.showHiddenFiles

            when (val result = listDirectoryUseCase(sessionId, path, sortOrder, showHidden)) {
                is SshResult.Success -> {
                    if (pushHistory && currentPath != path) {
                        pathHistory.addLast(currentPath)
                    }
                    _uiState.update {
                        it.copy(
                            currentPath = path,
                            files       = result.data,
                            isLoading   = false,
                            error       = null,
                        )
                    }
                    Timber.d("SFTP navigateTo '$path' : ${result.data.size} entrées")
                }
                is SshResult.Error -> {
                    Timber.w("SFTP navigateTo '$path' échoué : ${result.message}")
                    val isConnectionDrop = isConnectionError(result.message)
                    _uiState.update {
                        it.copy(
                            isLoading      = false,
                            error          = result.message,
                            isDisconnected = isConnectionDrop,
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
     * Recharge le répertoire courant.
     */
    fun refresh() {
        navigateTo(_uiState.value.currentPath, pushHistory = false)
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
     * Crée un répertoire dans le dossier courant.
     */
    fun createDirectory(name: String) {
        val parentPath = _uiState.value.currentPath
        viewModelScope.launch {
            when (val result = createDirectoryUseCase(sessionId, parentPath, name)) {
                is SshResult.Success -> {
                    _uiState.update { it.copy(mkdirDialog = false) }
                    refresh()
                    _uiState.update { it.copy(message = "Dossier créé") }
                    Timber.i("SFTP mkdir '$name' dans '$parentPath'")
                }
                is SshResult.Error -> {
                    _uiState.update {
                        it.copy(mkdirDialog = false, message = result.message, messageIsError = true)
                    }
                    Timber.w("SFTP mkdir '$name' échoué : ${result.message}")
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
                    _uiState.update { it.copy(message = "Élément supprimé") }
                    Timber.i("SFTP delete '${file.path}'")
                }
                is SshResult.Error -> {
                    _uiState.update { it.copy(message = result.message, messageIsError = true) }
                    Timber.w("SFTP delete '${file.path}' échoué : ${result.message}")
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
                    _uiState.update { it.copy(message = "Fichier renommé") }
                    Timber.i("SFTP rename '$oldPath' → '$newName'")
                }
                is SshResult.Error -> {
                    _uiState.update { it.copy(message = result.message, messageIsError = true) }
                    Timber.w("SFTP rename '$oldPath' échoué : ${result.message}")
                }
            }
        }
    }

    /**
     * Modifie les permissions POSIX d'une entrée.
     *
     * @param path chemin absolu de l'entrée cible.
     * @param permissions valeur entière des permissions POSIX (0–511).
     */
    fun changePermissions(path: String, permissions: Int) {
        viewModelScope.launch {
            _uiState.update { it.copy(chmodDialog = null) }
            when (val result = changePermissionsUseCase(sessionId, path, permissions)) {
                is SshResult.Success -> {
                    refresh()
                    _uiState.update { it.copy(message = "Permissions modifiées") }
                    Timber.i("SFTP chmod %o sur '$path'", permissions)
                }
                is SshResult.Error -> {
                    _uiState.update { it.copy(message = result.message, messageIsError = true) }
                    Timber.w("SFTP chmod sur '$path' échoué : ${result.message}")
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
                    is SshResult.Success -> Timber.i("SFTP multi-delete '${file.path}'")
                    is SshResult.Error   -> {
                        errorCount++
                        Timber.w("SFTP multi-delete '${file.path}' échoué : ${result.message}")
                    }
                }
            }
            clearSelection()
            refresh()
            val deletedCount = toDelete.size - errorCount
            _uiState.update {
                it.copy(
                    message = if (errorCount == 0) {
                        "$deletedCount élément(s) supprimé(s)"
                    } else {
                        "$deletedCount supprimé(s), $errorCount erreur(s)"
                    },
                    messageIsError = errorCount > 0,
                )
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

    /**
     * Observe [TransferTracker.transfers] et met à jour [SftpBrowserUiState.activeTransfers].
     * Appelé une seule fois à l'initialisation de la session.
     */
    /** IDs des transferts déjà détectés comme terminés (évite les refresh multiples). */
    private val completedTransferIds = mutableSetOf<String>()

    /** Echecs deja signales, pour ne pas repeter le message a chaque emission. */
    private val failedTransferIds = mutableSetOf<String>()

    private fun observeTransfers() {
        viewModelScope.launch {
            transferTracker.transfers.collect { transferMap ->
                val transfers = transferMap.values.toList()
                _uiState.update { state ->
                    state.copy(activeTransfers = transfers)
                }
                // Auto-refresh quand un upload se termine
                for (transfer in transfers) {
                    if (transfer.status == TransferStatus.COMPLETED
                        && transfer.request.direction == TransferDirection.UPLOAD
                        && completedTransferIds.add(transfer.request.id)
                    ) {
                        Timber.d("Upload terminé (${transfer.request.displayName}), auto-refresh")
                        refresh()
                    }
                    // Un echec n'apparaissait nulle part : le bandeau ne montre
                    // que les transferts en cours ou en attente, et la
                    // notification disparait avec le service. L'utilisateur se
                    // retrouvait avec un fichier vide et aucune explication.
                    if (transfer.status == TransferStatus.FAILED
                        && failedTransferIds.add(transfer.request.id)
                    ) {
                        val reason = transfer.errorMessage
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
        Timber.i("SftpBrowserViewModel: download lancé pour '$displayName'")
        _uiState.update { it.copy(message = "Téléchargement de $displayName démarré") }
    }

    /**
     * Lance l'envoi d'un fichier local vers le répertoire courant sur le serveur.
     *
     * Démarre [SftpTransferService] via un Intent foreground service.
     *
     * @param sourceUri   URI SAF (content://) du fichier source local.
     * @param displayName nom de fichier affiché (depuis les métadonnées SAF).
     * @param fileSize    taille du fichier en octets (0 si inconnue).
     */
    fun uploadFile(sourceUri: Uri, displayName: String, fileSize: Long = 0L) =
        uploadFile(sessionId, _uiState.value.currentPath, sourceUri, displayName, fileSize)

    /**
     * Variante avec session et répertoire de destination explicites.
     *
     * Même raison que pour [downloadFile] : au retour du sélecteur après
     * recréation de l'activité, ni l'identifiant de session ni le répertoire
     * courant de ce ViewModel ne sont encore renseignés. Le répertoire vaudrait
     * la racine et le fichier atterrirait au mauvais endroit.
     */
    fun uploadFile(
        sessionId: String,
        remoteDir: String,
        sourceUri: Uri,
        displayName: String,
        fileSize: Long = 0L,
    ) {
        if (!ensureSession(sessionId)) return
        val remotePath = if (remoteDir.endsWith('/')) {
            "$remoteDir$displayName"
        } else {
            "$remoteDir/$displayName"
        }
        val intent = Intent(context, SftpTransferService::class.java).apply {
            putExtra(SftpTransferService.EXTRA_SESSION_ID,   sessionId)
            putExtra(SftpTransferService.EXTRA_REMOTE_PATH,  remotePath)
            putExtra(SftpTransferService.EXTRA_LOCAL_URI,    sourceUri.toString())
            putExtra(SftpTransferService.EXTRA_DIRECTION,    TransferDirection.UPLOAD.name)
            putExtra(SftpTransferService.EXTRA_FILE_SIZE,    fileSize)
            putExtra(SftpTransferService.EXTRA_DISPLAY_NAME, displayName)
        }
        if (!startTransferService(intent)) return
        Timber.i("SftpBrowserViewModel: upload lancé pour '$displayName' → '$remotePath'")
        _uiState.update { it.copy(message = "Envoi de $displayName démarré") }
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
            Timber.i("SftpBrowserViewModel: annulation demandée pour $transferId")
        } catch (e: Exception) {
            Timber.w(e, "SftpBrowserViewModel: annulation impossible pour $transferId")
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
     */
    private fun ensureSession(sessionId: String): Boolean {
        if (sessionId.isNotBlank()) return true
        Timber.w("SftpBrowserViewModel: transfert refusé, session non initialisée")
        _uiState.update {
            it.copy(message = context.getString(R.string.sftp_transfer_no_session), messageIsError = true)
        }
        return false
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
        Timber.w(e, "SftpBrowserViewModel: démarrage du service de transfert refusé")
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
                            error     = result.message,
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
     * Détecte si un message d'erreur SFTP correspond à une perte de connexion.
     * Utilisé pour basculer [SftpBrowserUiState.isDisconnected].
     */
    private fun isConnectionError(message: String): Boolean {
        val lower = message.lowercase()
        return lower.contains("connexion sftp perdue") ||
               lower.contains("sftp connection lost") ||
               lower.contains("channel is closed") ||
               lower.contains("session is not connected") ||
               lower.contains("broken pipe") ||
               lower.contains("connection reset")
    }

    // ── Reconnexion ───────────────────────────────────────────────────────────────

    /**
     * Tente de rouvrir la session SFTP après une déconnexion.
     *
     * Réinitialise [SftpBrowserUiState.isDisconnected] immédiatement, puis retente
     * [SftpManager.openSftp]. En cas de succès, rafraîchit le répertoire courant.
     * En cas d'échec, repositionne [isDisconnected] à true et affiche l'erreur.
     */
    fun reconnect() {
        _uiState.update { it.copy(isDisconnected = false, isLoading = true, error = null) }
        viewModelScope.launch {
            when (val result = sftpManager.openSftp(sessionId)) {
                is SshResult.Success -> refresh()
                is SshResult.Error   -> {
                    _uiState.update {
                        it.copy(
                            isDisconnected = true,
                            isLoading      = false,
                            error          = result.message,
                        )
                    }
                    Timber.w("SFTP reconnexion échouée pour $sessionId : ${result.message}")
                }
            }
        }
    }

    // ── Cycle de vie ──────────────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        val sid = sessionId
        if (sid.isNotEmpty()) {
            cleanupScope.launch {
                withContext(NonCancellable) {
                    sftpManager.closeSftp(sid)
                }
            }
        }
    }
}
