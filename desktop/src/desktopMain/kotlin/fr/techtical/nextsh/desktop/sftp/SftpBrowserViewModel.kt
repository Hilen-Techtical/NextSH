// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sftp

import fr.techtical.nextsh.desktop.core.ssh.DesktopSftpManager
import fr.techtical.nextsh.desktop.core.ssh.DesktopTransferManager
import fr.techtical.nextsh.desktop.core.ssh.RemoteEntryKind
import fr.techtical.nextsh.desktop.data.di.DesktopContainer
import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_download_target_is_folder
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_generic
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_upload_target_is_folder
import fr.techtical.nextsh.desktop.generated.resources.sftp_copy_path_refused
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_upload_target_not_file
import fr.techtical.nextsh.desktop.generated.resources.sftp_file_created
import fr.techtical.nextsh.desktop.sessions.DesktopSessionManager
import fr.techtical.nextsh.desktop.sessions.hostOfSessionIn
import fr.techtical.nextsh.shared.domain.model.SftpFile
import fr.techtical.nextsh.shared.domain.model.SortOrder
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.shared.domain.model.TransferState
import fr.techtical.nextsh.shared.util.AppScope
import fr.techtical.nextsh.shared.util.LogId
import fr.techtical.nextsh.shared.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import java.io.File
import java.util.UUID


private const val TAG = "SftpBrowserViewModel"

sealed interface SftpDialog {
    data object NewFolder : SftpDialog
    data object NewFile : SftpDialog
    data class Rename(val target: SftpFile) : SftpDialog
    data class Chmod(val target: SftpFile) : SftpDialog

    /** [targets] empty → no-op (safety). 1 entry → "Supprimer fichier" copy, n>1 → "n éléments". */
    data class DeleteConfirm(val targets: List<SftpFile>) : SftpDialog

    /**
     * Deletion of leftover temporary files of interrupted uploads. [targets] is the list frozen when the
     * dialog opens: a reload of the folder while it is open adds nothing to what is deleted.
     */
    data class DeleteLeftovers(val targets: List<SftpFile>) : SftpDialog
}

/**
 * A transfer that waits for the user to confirm that an existing file is replaced.
 *
 * The questions live in [SftpTransferInbox] (shown through [SftpBrowserViewModel.replaceQueue]),
 * not in [SftpBrowserUiState.Loaded.dialog]: every reload of the folder, including the one run
 * when an earlier upload finishes, rebuilds [SftpBrowserUiState.Loaded] with no dialog, which
 * would close the confirmation under the user's cursor; and the view model itself is created
 * again when the screen comes back, which would lose it.
 */
sealed interface ReplacePrompt {
    /**
     * Tells this question from any other, even one about the same file: an answer is only
     * applied to the question it was given for. Not part of what the user reads.
     */
    val id: String

    /**
     * The SSH session of the browser that asked. A transfer started by a "yes" always runs
     * on this session and on no other: the view model that receives the answer checks that
     * it is the one that asked, before starting anything.
     */
    val sessionId: String

    /** The pane (`TabContent.Sftp.paneId`) of the browser that asked, checked the same way. */
    val paneId: String

    /**
     * The label of the host the question is about, read when it was asked (null when it is
     * not known). Shown by the dialog so that the user can tell which server a question
     * concerns when several are waiting: two windows of two browsers look alike. It is for
     * the screen only and must never reach a log.
     */
    val hostLabel: String?

    /** Name of the file that would be replaced, shown in the dialog. */
    val name: String

    /** Where that file is: the remote path for an upload, the absolute local path for a download. */
    val targetPath: String

    /** [localFile] would replace the file [targetPath] on the server, in [remoteDir]. */
    data class Upload(
        val localFile: File,
        val remoteDir: String,
        override val targetPath: String,
        override val sessionId: String,
        override val paneId: String,
        override val hostLabel: String? = null,
        override val id: String = UUID.randomUUID().toString(),
    ) : ReplacePrompt {
        /** The name on the server of the file that would be replaced: not the local name, which may differ. */
        override val name: String get() = targetPath.substringAfterLast('/')
    }

    /** The remote [file] would replace the local file [localFile]. */
    data class Download(
        val file: SftpFile,
        val localFile: File,
        override val sessionId: String,
        override val paneId: String,
        override val hostLabel: String? = null,
        override val id: String = UUID.randomUUID().toString(),
    ) : ReplacePrompt {
        override val name: String get() = localFile.name
        override val targetPath: String get() = localFile.absolutePath
    }
}

/**
 * In-place preview of a file in the side panel. [Loading] covers the SFTP
 * fetch; [Text] / [Image] carry the decoded payload. Size guards in the
 * ViewModel cap text to 512 KB and images to 2 MB (parity with Android).
 */
sealed interface PreviewState {
    val file: SftpFile

    data class Loading(override val file: SftpFile) : PreviewState
    data class Text(override val file: SftpFile, val content: String) : PreviewState
    data class Image(override val file: SftpFile, val bytes: ByteArray) : PreviewState {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Image) return false
            return file == other.file && bytes.contentEquals(other.bytes)
        }
        override fun hashCode(): Int = 31 * file.hashCode() + bytes.contentHashCode()
    }

    /** The file is over the preview limit; the panel translates the text from [maxKb]. */
    data class TooLarge(override val file: SftpFile, val maxKb: Int) : PreviewState

    data class Error(override val file: SftpFile, val message: String) : PreviewState
}

sealed interface SftpBrowserUiState {
    data object Loading : SftpBrowserUiState
    data class Loaded(
        val cwd: String,
        /** All remote entries, pre-sort, pre-filter: the source of truth from SSHJ. */
        val rawFiles: List<SftpFile>,
        val selection: Set<String> = emptySet(),
        val sortOrder: SortOrder = SortOrder.NAME_ASC,
        val hiddenVisible: Boolean = false,
        val dialog: SftpDialog? = null,
        /** Side-panel preview (text or image). Null when panel is hidden. */
        val preview: PreviewState? = null,
        /** Transient confirmation ("Fichier créé"). Dropped by the next navigation or reload. */
        val lastInfo: String? = null,
        /**
         * Paths of the entries reported as leftovers of interrupted uploads (see
         * [DesktopSftpManager.isUploadLeftover]), except the temporary files of the uploads of this app
         * that are under way. They stay in the list when the hidden files are off.
         */
        val leftoverPaths: Set<String> = emptySet(),
        /** The user dismissed the banner of this folder (kept by the view model until it is closed). */
        val leftoverBannerDismissed: Boolean = false,
    ) : SftpBrowserUiState {
        /**
         * Presentation-ready list: filter dotfiles (if hidden is off, a leftover of an interrupted upload
         * excepted) then apply [sortOrder].
         */
        val visibleFiles: List<SftpFile>
            get() {
                val filtered = if (hiddenVisible) rawFiles
                else rawFiles.filterNot { it.name.startsWith('.') && it.path !in leftoverPaths }
                return filtered.sortedWith(comparatorFor(sortOrder))
            }

        /** The leftovers of interrupted uploads of the folder, in the order of the list. */
        val leftovers: List<SftpFile>
            get() = if (leftoverPaths.isEmpty()) emptyList()
            else rawFiles.filter { it.path in leftoverPaths }.sortedWith(comparatorFor(sortOrder))

        val isSelectionMode: Boolean get() = selection.isNotEmpty()
    }
    data class Error(val message: String) : SftpBrowserUiState
}

/**
 * Per-tab view model for an SFTP browser. Wave 2.2 scope: navigation +
 * listing + CRUD (mkdir / rename / delete / chmod) + selection + sort +
 * hidden toggle. Upload / download land in Wave 2.4.
 *
 * [sessionId] is the terminal's SSH session that owns the shared SSHClient;
 * SFTP ops piggyback on it via [DesktopSftpManager]. [tabId] is the SFTP
 * tab id (for surfacing the cwd back to the tab bar).
 */
class SftpBrowserViewModel(
    private val sessionId: String,
    private val tabId: String,
    /**
     * The identity of this browser ([TabContent.Sftp.paneId]): the key of its questions and
     * its notice in the [SftpTransferInbox], and the check that an answer is for this very
     * browser, and the key under which its current folder is remembered in the pane tree
     * (`updateSftpPaneCwd`). Not derived from [tabId]: it does not identify a pane.
     */
    private val paneId: String,
    initialCwd: String,
    private val sftpManager: DesktopSftpManager = DesktopContainer.sftpManager,
    private val sessionManager: DesktopSessionManager = DesktopContainer.sessionManager,
    private val transferManager: DesktopTransferManager = DesktopContainer.transferManager,
    private val appScope: AppScope = DesktopContainer.appScope,
    private val inbox: SftpTransferInbox = DesktopContainer.sftpTransferInbox,
) {
    private val _state = MutableStateFlow<SftpBrowserUiState>(SftpBrowserUiState.Loading)
    val state: StateFlow<SftpBrowserUiState> = _state.asStateFlow()

    private val paneKey = SftpPaneKey(paneId)

    /**
     * The questions "replace this file?" waiting for the user, oldest first: the screen shows
     * the first one. Kept in the [SftpTransferInbox], so a question survives a reload of the
     * folder and the re-creation of this view model (see [ReplacePrompt]).
     */
    val replaceQueue: StateFlow<List<ReplacePrompt>> = inbox.prompts(paneKey)

    /**
     * The last message that explains why a transfer was refused or why an operation (create,
     * rename, permissions, delete) failed, or null. Like [replaceQueue] it is not part of
     * [state]: a reload of the folder must not wipe it before the user has read it. Cleared
     * by [dismissNotice].
     */
    val notice: StateFlow<String?> = inbox.notice(paneKey)

    /**
     * The path of the last folder asked for, whatever became of the listing: what [refresh] reloads
     * when there is no folder on screen (a failed load, or a load still under way).
     */
    @Volatile
    private var lastRequestedPath: String? = null

    /**
     * Guards [loadsInFlight] and [reloadPending] together: a [refresh] asked while a listing is under
     * way raises the flag, and the end of the last listing lowers it and replays the reload. Read and
     * written apart, a refresh could be lost, and the flag left up would reload at a random time later.
     */
    private val loadLock = Any()

    /** Listings under way. A [refresh] asked meanwhile is kept in [reloadPending] and runs after them. */
    private var loadsInFlight = 0

    private var reloadPending = false

    /** The collector of the reload requests of the pane (see [SftpTransferInbox.requestReload]). */
    private var reloadJob: Job? = null

    /**
     * Set by [dispose] when the screen leaves the composition: a view model that was replaced (tab
     * change, another view) may still see a transfer end, and must not reload nor write the folder of
     * the pane in the tab tree any more, which now belongs to its successor.
     */
    @Volatile
    private var disposed = false

    /** Called when the screen of this view model leaves the composition. Idempotent. */
    fun dispose() {
        disposed = true
        reloadJob?.cancel()
    }

    init {
        navigateTo(initialCwd)
        // The end of an upload asks the pane for a reload through the inbox, not through the view model
        // that started it: that one may be gone, and this one (or its successor) is the one on screen.
        // The count read now is the baseline: only a request made after this point reloads.
        val requests = inbox.reloadRequests(paneKey)
        var seen = requests.value
        reloadJob = appScope.coroutineScope.launch {
            requests.collect { count ->
                if (count != seen) {
                    seen = count
                    refresh()
                }
            }
        }
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    fun navigateTo(path: String) {
        load(path)
    }

    /**
     * Lists [path] and swaps the state. [info] rides along into the new
     * [SftpBrowserUiState.Loaded]: setting it before the reload would lose it
     * to the intermediate Loading state.
     *
     * Reloading the folder already on screen (refresh, or the reload after a
     * creation, rename, delete...) keeps its sort order and its hidden-files
     * toggle: without that, a `.env` just created would vanish from the list.
     * The previous state is captured before the swap to Loading, which drops it.
     * Navigating to another folder starts again from the defaults.
     */
    private fun load(path: String, info: String? = null) {
        val previous = _state.value as? SftpBrowserUiState.Loaded
        lastRequestedPath = path
        synchronized(loadLock) { loadsInFlight++ }
        _state.update { SftpBrowserUiState.Loading }
        appScope.coroutineScope.launch {
            try {
                // The names of the temporary files under way or known gone are read BEFORE the listing and
                // again after it: a temporary file that exists at any moment of the listing, even one
                // created and finished within it, is in one of the two reads and is never taken for a
                // leftover. Whatever the session or the folder.
                val knownBefore = sftpManager.knownUploadTempNames()
                when (val result = sftpManager.listDirectory(sessionId, path)) {
                    is SshResult.Success -> {
                        val canonical = sanitized(path)
                        val sameFolder = previous?.takeIf { it.cwd == canonical }
                        // Without a previous state (a view model re-created by a tab change, or a reload
                        // asked during a listing) the options of the pane are taken up again, for this
                        // folder only: another folder starts from the defaults.
                        val remembered = inbox.viewOptions(paneKey)?.takeIf { it.folder == canonical }
                        val hidden = sameFolder?.hiddenVisible ?: remembered?.hiddenVisible ?: false
                        val sort = sameFolder?.sortOrder ?: remembered?.sortOrder ?: SortOrder.NAME_ASC
                        val known = knownBefore + sftpManager.knownUploadTempNames()
                        // By NAME: the random identifier makes it unique, and the path of the same file
                        // differs when the folder is reached through a link.
                        val leftoverPaths = result.data
                            .filter { DesktopSftpManager.isUploadLeftover(it) && it.name !in known }
                            .mapTo(HashSet()) { it.path }
                        // Remembered BEFORE the state is published: a toggle of the hidden files or of the
                        // sort order made as soon as the listing shows would otherwise be overwritten here.
                        if (!disposed) {
                            sessionManager.updateSftpPaneCwd(tabId, paneId, canonical)
                            inbox.setViewOptions(paneKey, SftpViewOptions(canonical, hidden, sort))
                        }
                        _state.value = SftpBrowserUiState.Loaded(
                            cwd = canonical,
                            rawFiles = result.data,
                            leftoverPaths = leftoverPaths,
                            leftoverBannerDismissed = inbox.isLeftoverBannerDismissed(paneKey, canonical),
                            sortOrder = sort,
                            hiddenVisible = hidden,
                            lastInfo = info,
                        )
                    }
                    is SshResult.Error -> {
                        _state.value = SftpBrowserUiState.Error(result.message.ifBlank { result.code.name })
                    }
                }
            } finally {
                // A reload asked while this listing was under way (the end of an upload, for instance)
                // runs now, on the state this listing produced.
                val replay = synchronized(loadLock) {
                    loadsInFlight--
                    (loadsInFlight == 0 && reloadPending).also { if (it) reloadPending = false }
                }
                val again = lastRequestedPath
                if (replay && again != null && !disposed) load(again)
            }
        }
    }

    fun navigateUp() {
        val current = (state.value as? SftpBrowserUiState.Loaded)?.cwd ?: return
        if (current == "/") return
        val parent = current.substringBeforeLast('/').ifEmpty { "/" }
        navigateTo(parent)
    }

    /**
     * Reloads the folder on screen. While a listing is under way the reload is kept and runs when it
     * ends (it used to be lost). After a failed load it reloads the folder that was asked for, which
     * is what "Retry" does. Nothing happens once the screen is gone ([dispose]).
     */
    fun refresh() {
        if (disposed) return
        val path = (state.value as? SftpBrowserUiState.Loaded)?.cwd ?: lastRequestedPath ?: return
        val busy = synchronized(loadLock) {
            (loadsInFlight > 0).also { if (it) reloadPending = true }
        }
        if (!busy) navigateTo(path)
    }

    // ── Selection ─────────────────────────────────────────────────────────────

    fun toggleSelection(path: String) {
        updateLoaded { loaded ->
            val next = loaded.selection.toMutableSet()
            if (!next.add(path)) next.remove(path)
            loaded.copy(selection = next)
        }
    }

    fun selectOnly(path: String) {
        updateLoaded { it.copy(selection = setOf(path)) }
    }

    fun selectRange(anchorPath: String, targetPath: String) {
        updateLoaded { loaded ->
            val order = loaded.visibleFiles.map { it.path }
            val a = order.indexOf(anchorPath)
            val b = order.indexOf(targetPath)
            if (a < 0 || b < 0) return@updateLoaded loaded
            val (lo, hi) = if (a <= b) a to b else b to a
            val next = order.subList(lo, hi + 1).toSet()
            loaded.copy(selection = next)
        }
    }

    fun selectAll() {
        updateLoaded { loaded -> loaded.copy(selection = loaded.visibleFiles.map { it.path }.toSet()) }
    }

    fun clearSelection() {
        updateLoaded { it.copy(selection = emptySet()) }
    }

    // ── View options ──────────────────────────────────────────────────────────

    fun setSortOrder(order: SortOrder) {
        updateLoaded { it.copy(sortOrder = order) }
        rememberViewOptions()
    }

    fun toggleHidden() {
        updateLoaded { it.copy(hiddenVisible = !it.hiddenVisible) }
        rememberViewOptions()
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────

    fun openDialog(dialog: SftpDialog) {
        updateLoaded { it.copy(dialog = dialog) }
    }

    fun dismissDialog() {
        updateLoaded { it.copy(dialog = null) }
    }

    fun dismissInfo() {
        updateLoaded { it.copy(lastInfo = null) }
    }

    // ── Preview side panel ────────────────────────────────────────────────────

    fun openPreview(file: SftpFile) {
        if (!file.isPreviewable) return
        val cap = if (file.isImageFile) IMAGE_PREVIEW_LIMIT else TEXT_PREVIEW_LIMIT
        if (file.size > cap) {
            updateLoaded { it.copy(preview = PreviewState.TooLarge(file, (cap / 1024).toInt())) }
            return
        }
        updateLoaded { it.copy(preview = PreviewState.Loading(file)) }
        appScope.coroutineScope.launch {
            when (val result = sftpManager.readPreview(sessionId, file.path, cap)) {
                is SshResult.Success -> {
                    val state = if (file.isImageFile) {
                        PreviewState.Image(file, result.data)
                    } else {
                        PreviewState.Text(file, String(result.data, Charsets.UTF_8))
                    }
                    updateLoaded { current ->
                        // Discard the update if the user already moved on to
                        // another file or closed the panel.
                        if (current.preview?.file?.path == file.path) current.copy(preview = state) else current
                    }
                }
                is SshResult.Error -> updateLoaded { current ->
                    if (current.preview?.file?.path == file.path) {
                        current.copy(preview = PreviewState.Error(file, result.message.ifBlank { result.code.name }))
                    } else current
                }
            }
        }
    }

    fun closePreview() {
        updateLoaded { it.copy(preview = null) }
    }

    // ── Transfers ─────────────────────────────────────────────────────────────

    /**
     * Uploads [localFile] into the current cwd (ignored while no folder is loaded). The
     * target is checked on the server first, and nothing existing is replaced unasked:
     *
     * - nothing there: the upload starts;
     * - a file there: a [ReplacePrompt] joins the [replaceQueue] and nothing is sent until
     *   [confirmReplace];
     * - a folder there, or an entry that is neither a file nor a folder: refused with a
     *   [notice] that names the folder, since the user may have moved on meanwhile;
     * - the check itself failed: the upload starts anyway, but still without permission to
     *   replace, so the server refuses a taken name and any error shows up in the transfers.
     */
    fun uploadFile(localFile: File) {
        val cwd = (state.value as? SftpBrowserUiState.Loaded)?.cwd ?: return
        val remotePath = DesktopTransferManager.uploadTargetPath(cwd, localFile.name)
        appScope.coroutineScope.launch {
            when (val kind = sftpManager.entryKind(sessionId, remotePath)) {
                is SshResult.Success -> when (kind.data) {
                    RemoteEntryKind.ABSENT -> startUpload(cwd, localFile, replaceExisting = false)
                    RemoteEntryKind.FILE -> askReplace(
                        ReplacePrompt.Upload(
                            localFile,
                            cwd,
                            remotePath,
                            sessionId = sessionId,
                            paneId = paneId,
                            hostLabel = resolveHostLabel(),
                        ),
                    )
                    RemoteEntryKind.DIRECTORY -> postNotice(
                        getString(Res.string.sftp_error_upload_target_is_folder, remotePath.substringAfterLast('/'), cwd),
                    )
                    RemoteEntryKind.OTHER -> postNotice(
                        getString(Res.string.sftp_error_upload_target_not_file, remotePath.substringAfterLast('/'), cwd),
                    )
                }
                is SshResult.Error -> startUpload(cwd, localFile, replaceExisting = false)
            }
        }
    }

    /**
     * Downloads [file] to [localFile]. An existing [localFile] is replaced only after
     * [confirmReplace]: a [ReplacePrompt] joins the [replaceQueue] and nothing is written
     * before. A folder at that path is refused with a [notice].
     */
    fun downloadFile(file: SftpFile, localFile: File) {
        appScope.coroutineScope.launch {
            // Local disk checks, possibly slow on a network drive: off the default workers.
            val isFolder = withContext(Dispatchers.IO) { localFile.isDirectory }
            val exists = !isFolder && withContext(Dispatchers.IO) { localFile.exists() }
            when {
                isFolder -> postNotice(getString(Res.string.sftp_error_download_target_is_folder, localFile.name))
                exists -> askReplace(
                    ReplacePrompt.Download(
                        file,
                        localFile,
                        sessionId = sessionId,
                        paneId = paneId,
                        hostLabel = resolveHostLabel(),
                    ),
                )
                else -> startDownload(file, localFile, replaceExisting = false)
            }
        }
    }

    /**
     * Adds [prompt] at the end of the queue. A question that arrives while another one is on
     * screen waits behind it, it never takes its place: an answer can only be given to a
     * question the user had the time to read.
     */
    private fun askReplace(prompt: ReplacePrompt) {
        inbox.enqueue(paneKey, prompt)
    }

    /** "Copy path" was refused: the path holds a character that the lists show as U+FFFD. Says why, in a notice. */
    fun onCopyPathRefused() {
        appScope.coroutineScope.launch { postNotice(getString(Res.string.sftp_copy_path_refused)) }
    }

    private fun postNotice(message: String) {
        inbox.postNotice(paneKey, message)
    }

    /**
     * Dismisses the notice on screen only if it is still [shown]: a double click on "OK" must not take
     * off the next notice too, which nobody read.
     */
    fun dismissNotice(shown: String) {
        inbox.dismissNotice(paneKey, shown)
    }

    fun dismissNotice() {
        inbox.dismissNotice(paneKey)
    }

    /**
     * The user accepted the replacement asked by [prompt]: starts that transfer with
     * permission to replace. It only acts if [prompt] was asked by THIS browser (same SSH
     * session and same pane: the transfer runs on this view model's session, so a question
     * of another browser must never be answered "yes" here), and if it is still the question
     * on screen (the head of the queue). It takes it off atomically: a second click on the
     * same answer, or an answer that comes late for a question that is gone, does nothing.
     */
    fun confirmReplace(prompt: ReplacePrompt) {
        if (!isOwnQuestion(prompt)) return
        if (!inbox.removeHead(paneKey, prompt)) return
        when (prompt) {
            is ReplacePrompt.Upload -> startUpload(prompt.remoteDir, prompt.localFile, replaceExisting = true)
            is ReplacePrompt.Download -> startDownload(prompt.file, prompt.localFile, replaceExisting = true)
        }
    }

    /**
     * The user refused the replacement asked by [prompt]: the transfer is dropped, nothing
     * was touched. Same rules as [confirmReplace]: only a question of this browser, and only
     * the one on screen, can be dismissed.
     */
    fun dismissReplace(prompt: ReplacePrompt) {
        if (!isOwnQuestion(prompt)) return
        inbox.removeHead(paneKey, prompt)
    }

    /**
     * Defense in depth: the inbox already gives each browser its own queue, so a question
     * of another browser cannot normally be here. If one ever were, answering it must still
     * never start a transfer on this browser's session.
     */
    private fun isOwnQuestion(prompt: ReplacePrompt): Boolean =
        prompt.sessionId == sessionId && prompt.paneId == paneId

    /**
     * Enqueues the upload. When the transfer reaches a terminal state the listing is
     * refreshed on success so the new file appears immediately.
     */
    private fun startUpload(remoteDir: String, localFile: File, replaceExisting: Boolean) {
        val id = transferManager.enqueueUpload(
            sessionId = sessionId,
            remoteDir = remoteDir,
            localFile = localFile,
            replaceExisting = replaceExisting,
            hostLabel = resolveHostLabel(),
        )
        // Only the flow, the inbox and the key are captured, not this view model: it may be replaced
        // (tab change) before the upload ends, and the browser that is on screen then must reload.
        val transfers = transferManager.transfers
        val reloads = inbox
        val key = paneKey
        appScope.coroutineScope.launch {
            val terminal = transfers
                .first { map ->
                    val s = map[id]
                    s is TransferState.Completed || s is TransferState.Failed || s is TransferState.Cancelled
                }
            if (terminal[id] is TransferState.Completed) reloads.requestReload(key)
        }
    }

    private fun startDownload(file: SftpFile, localFile: File, replaceExisting: Boolean) {
        transferManager.enqueueDownload(
            sessionId = sessionId,
            remotePath = file.path,
            fileSize = file.size,
            localFile = localFile,
            displayName = file.name,
            replaceExisting = replaceExisting,
            hostLabel = resolveHostLabel(),
        )
    }

    /**
     * Resolve a friendly host label by walking every tab tree under
     * [DesktopSessionManager.tabs] and finding the pane that carries the SSH session
     * [sessionId]. The label is the host of THAT pane, not of the tab: it stays accurate
     * when the browser lives in a split whose panes target different servers.
     */
    private fun resolveHostLabel(): String? = sessionManager.tabs.value
        .firstNotNullOfOrNull { tab -> hostOfSessionIn(tab.content, sessionId) }
        ?.label

    // ── CRUD operations ───────────────────────────────────────────────────────

    /**
     * Creates [name] inside the displayed folder. The bare name goes to the
     * manager together with the parent directory: the manager validates it
     * before joining, so `../x` or `a/b` cannot escape the current folder.
     */
    fun createFolder(name: String) {
        val loaded = _state.value as? SftpBrowserUiState.Loaded ?: return
        val parentDir = loaded.cwd
        updateLoaded { it.copy(dialog = null) }
        appScope.coroutineScope.launch {
            when (val r = sftpManager.createDirectory(sessionId, parentDir, name)) {
                is SshResult.Success -> refresh()
                is SshResult.Error -> setError(r.message)
            }
        }
    }

    /**
     * Creates an empty file named [name] in the folder on screen. The name goes to the
     * manager bare, not joined to the folder: the manager validates it as a single
     * name, which a joined path would defeat (`a/b`, `../x`).
     */
    fun createFile(name: String) {
        val loaded = _state.value as? SftpBrowserUiState.Loaded ?: return
        val requestedCwd = loaded.cwd
        updateLoaded { it.copy(dialog = null) }
        appScope.coroutineScope.launch {
            when (val r = sftpManager.createFile(sessionId, requestedCwd, name)) {
                is SshResult.Success -> {
                    // Same as refresh(), plus the confirmation carried into the reloaded state.
                    // Only when the user is still in the folder the file was requested for:
                    // otherwise the confirmation would show up in an unrelated folder.
                    val current = (_state.value as? SftpBrowserUiState.Loaded)?.cwd
                    if (current == requestedCwd) load(requestedCwd, getString(Res.string.sftp_file_created))
                    // Still listing (no folder on screen yet): the new file must not be missing from the
                    // result, so the listing is replayed once it ends.
                    else if (current == null) refresh()
                }
                is SshResult.Error -> setError(r.message)
            }
        }
    }

    fun rename(oldPath: String, newName: String) {
        updateLoaded { it.copy(dialog = null) }
        appScope.coroutineScope.launch {
            when (val r = sftpManager.rename(sessionId, oldPath, newName)) {
                is SshResult.Success -> refresh()
                is SshResult.Error -> setError(r.message)
            }
        }
    }

    fun chmod(path: String, permissions: Int) {
        updateLoaded { it.copy(dialog = null) }
        appScope.coroutineScope.launch {
            when (val r = sftpManager.chmod(sessionId, path, permissions)) {
                is SshResult.Success -> refresh()
                is SshResult.Error -> setError(r.message)
            }
        }
    }

    /** Delete the given targets. Directories go through recursive delete. */
    fun deleteTargets(targets: List<SftpFile>) {
        updateLoaded { it.copy(dialog = null, selection = emptySet()) }
        appScope.coroutineScope.launch {
            var lastError: String? = null
            for (file in targets) {
                val result = if (file.isDirectory && !file.isSymlink) {
                    sftpManager.deleteRecursive(sessionId, file.path)
                } else {
                    sftpManager.delete(sessionId, file.path)
                }
                if (result is SshResult.Error) {
                    lastError = "${displayEntryName(file.name)}: ${result.message.ifBlank { result.code.name }}"
                }
            }
            if (lastError != null) setError(lastError)
            refresh()
        }
    }

    /** Hides the leftover banner of the folder on screen until this browser is closed. The badges stay. */
    fun dismissLeftoverBanner() {
        val loaded = _state.value as? SftpBrowserUiState.Loaded ?: return
        inbox.dismissLeftoverBanner(paneKey, loaded.cwd)
        updateLoaded { it.copy(leftoverBannerDismissed = true) }
    }

    /**
     * Deletes the leftover temporary files [targets] (the list frozen when the dialog opened), each by its
     * exact name and with a plain `rm`: NEVER a recursive delete, and a folder is skipped. A third party
     * who can write in a shared folder can plant a folder under a leftover name; it must not be wiped.
     * No success message, like [deleteTargets]: the banner goes away when the folder reloads.
     */
    fun deleteLeftovers(targets: List<SftpFile>) {
        updateLoaded { it.copy(dialog = null, selection = emptySet()) }
        appScope.coroutineScope.launch {
            var lastError: String? = null
            for (file in targets) {
                if (file.isDirectory) continue
                // An upload of this app may have created this temporary file since the dialog opened.
                if (sftpManager.isUploadTempUnderWay(file.name)) continue
                val result = sftpManager.delete(sessionId, file.path)
                if (result is SshResult.Error) {
                    Logger.w(TAG, "leftover delete ${LogId.of(sessionId)} failed, reason ${result.code}")
                    lastError = "${displayEntryName(file.name)}: ${result.message.ifBlank { result.code.name }}"
                }
            }
            if (lastError != null) setError(lastError)
            refresh()
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    /** Keeps the options on screen in the inbox, so that the next view model of this pane takes them up. */
    private fun rememberViewOptions() {
        val loaded = _state.value as? SftpBrowserUiState.Loaded ?: return
        if (!disposed) inbox.setViewOptions(paneKey, SftpViewOptions(loaded.cwd, loaded.hiddenVisible, loaded.sortOrder))
    }

    private fun sanitized(path: String): String = sftpManager.sanitizePath(path) ?: "/"

    private fun updateLoaded(transform: (SftpBrowserUiState.Loaded) -> SftpBrowserUiState.Loaded) {
        _state.update { current -> if (current is SftpBrowserUiState.Loaded) transform(current) else current }
    }

    /**
     * Shows why an operation failed, through the pane's notice: unlike the state of the list,
     * it survives the reload that follows, a navigation, a failed load and the re-creation of
     * this view model.
     */
    private suspend fun setError(message: String) {
        postNotice(message.ifBlank { getString(Res.string.sftp_error_generic) })
    }

    companion object {
        const val TEXT_PREVIEW_LIMIT: Long = 512L * 1024           // 512 KB
        const val IMAGE_PREVIEW_LIMIT: Long = 2L * 1024 * 1024     // 2 MB
    }
}

private fun comparatorFor(order: SortOrder): Comparator<SftpFile> {
    val directoriesFirst = compareBy<SftpFile> { !it.isDirectory }
    val tie: Comparator<SftpFile> = when (order) {
        SortOrder.NAME_ASC -> compareBy { it.name.lowercase() }
        SortOrder.NAME_DESC -> compareByDescending { it.name.lowercase() }
        SortOrder.SIZE_ASC -> compareBy { it.size }
        SortOrder.SIZE_DESC -> compareByDescending { it.size }
        SortOrder.DATE_ASC -> compareBy { it.modifiedAt }
        SortOrder.DATE_DESC -> compareByDescending { it.modifiedAt }
    }
    return directoriesFirst.then(tie)
}
