// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sftp

import fr.techtical.nextsh.desktop.core.ssh.DesktopSftpManager
import fr.techtical.nextsh.desktop.core.ssh.DesktopTransferManager
import fr.techtical.nextsh.desktop.data.di.DesktopContainer
import fr.techtical.nextsh.desktop.sessions.DesktopSessionManager
import fr.techtical.nextsh.desktop.sessions.PaneSlot
import fr.techtical.nextsh.desktop.sessions.TabContent
import fr.techtical.nextsh.desktop.sessions.TerminalTabStatus
import fr.techtical.nextsh.shared.domain.model.SftpFile
import fr.techtical.nextsh.shared.domain.model.SortOrder
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.shared.domain.model.TransferState
import fr.techtical.nextsh.shared.util.AppScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

sealed interface SftpDialog {
    data object NewFolder : SftpDialog
    data class Rename(val target: SftpFile) : SftpDialog
    data class Chmod(val target: SftpFile) : SftpDialog

    /** [targets] empty → no-op (safety). 1 entry → "Supprimer fichier" copy, n>1 → "n éléments". */
    data class DeleteConfirm(val targets: List<SftpFile>) : SftpDialog
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
        /** Transient toast-style feedback for CRUD errors ("Permission refusée", etc.). */
        val lastError: String? = null,
    ) : SftpBrowserUiState {
        /** Presentation-ready list: filter dotfiles (if hidden is off) then apply [sortOrder]. */
        val visibleFiles: List<SftpFile>
            get() {
                val filtered = if (hiddenVisible) rawFiles
                else rawFiles.filterNot { it.name.startsWith('.') }
                return filtered.sortedWith(comparatorFor(sortOrder))
            }

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
    initialCwd: String,
    /**
     * `null` when the browser is the whole tab (`TabContent.Sftp` at root);
     * non-null when it lives inside a split pane so `updateSftpCwd` routes
     * the cwd update to the right cell of the Split.
     */
    private val paneSlot: PaneSlot? = null,
    private val sftpManager: DesktopSftpManager = DesktopContainer.sftpManager,
    private val sessionManager: DesktopSessionManager = DesktopContainer.sessionManager,
    private val transferManager: DesktopTransferManager = DesktopContainer.transferManager,
    private val appScope: AppScope = DesktopContainer.appScope,
) {
    private val _state = MutableStateFlow<SftpBrowserUiState>(SftpBrowserUiState.Loading)
    val state: StateFlow<SftpBrowserUiState> = _state.asStateFlow()

    init {
        navigateTo(initialCwd)
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    fun navigateTo(path: String) {
        _state.update { SftpBrowserUiState.Loading }
        appScope.coroutineScope.launch {
            when (val result = sftpManager.listDirectory(sessionId, path)) {
                is SshResult.Success -> {
                    val canonical = sanitized(path)
                    _state.value = SftpBrowserUiState.Loaded(cwd = canonical, rawFiles = result.data)
                    sessionManager.updateSftpCwd(tabId, canonical, paneSlot)
                }
                is SshResult.Error -> {
                    _state.value = SftpBrowserUiState.Error(result.message.ifBlank { result.code.name })
                }
            }
        }
    }

    fun navigateUp() {
        val current = (state.value as? SftpBrowserUiState.Loaded)?.cwd ?: return
        if (current == "/") return
        val parent = current.substringBeforeLast('/').ifEmpty { "/" }
        navigateTo(parent)
    }

    fun refresh() {
        val current = (state.value as? SftpBrowserUiState.Loaded)?.cwd ?: return
        navigateTo(current)
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
    }

    fun toggleHidden() {
        updateLoaded { it.copy(hiddenVisible = !it.hiddenVisible) }
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────

    fun openDialog(dialog: SftpDialog) {
        updateLoaded { it.copy(dialog = dialog) }
    }

    fun dismissDialog() {
        updateLoaded { it.copy(dialog = null) }
    }

    fun dismissError() {
        updateLoaded { it.copy(lastError = null) }
    }

    // ── Preview side panel ────────────────────────────────────────────────────

    fun openPreview(file: SftpFile) {
        if (!file.isPreviewable) return
        val cap = if (file.isImageFile) IMAGE_PREVIEW_LIMIT else TEXT_PREVIEW_LIMIT
        if (file.size > cap) {
            updateLoaded { it.copy(preview = PreviewState.Error(file, "Fichier trop volumineux (max ${cap / 1024} Ko)")) }
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
     * Enqueue an upload of [localFile] into the current cwd. When the transfer
     * reaches a terminal state we refresh the listing on success so the new
     * file appears immediately.
     */
    fun uploadFile(localFile: File) {
        val cwd = (state.value as? SftpBrowserUiState.Loaded)?.cwd ?: return
        val id = transferManager.enqueueUpload(sessionId, cwd, localFile, hostLabel = resolveHostLabel())
        appScope.coroutineScope.launch {
            val terminal = transferManager.transfers
                .first { map ->
                    val s = map[id]
                    s is TransferState.Completed || s is TransferState.Failed || s is TransferState.Cancelled
                }
            if (terminal[id] is TransferState.Completed) refresh()
        }
    }

    /** Enqueue a download of [file] to [localFile] (overwrite). */
    fun downloadFile(file: SftpFile, localFile: File) {
        transferManager.enqueueDownload(
            sessionId = sessionId,
            remotePath = file.path,
            fileSize = file.size,
            localFile = localFile,
            displayName = file.name,
            hostLabel = resolveHostLabel(),
        )
    }

    /**
     * Resolve a friendly host label by walking every [TabContent] tree under
     * [DesktopSessionManager.tabs] and finding a Terminal pane in
     * `Connected` state whose SSH session matches [sessionId]. The owning
     * `tab.host` is returned, accurate even when the SFTP lives in a
     * split pane sharing the terminal's SSHClient.
     */
    private fun resolveHostLabel(): String? = sessionManager.tabs.value
        .firstOrNull { tab -> contentMatchesSession(tab.content) }
        ?.host
        ?.label

    private fun contentMatchesSession(content: TabContent): Boolean = when (content) {
        is TabContent.Terminal ->
            (content.status as? TerminalTabStatus.Connected)?.sshSessionId == sessionId
        is TabContent.Sftp -> content.linkedSshSessionId == sessionId
        is TabContent.Split ->
            contentMatchesSession(content.first) || contentMatchesSession(content.second)
    }

    // ── CRUD operations ───────────────────────────────────────────────────────

    fun createFolder(name: String) {
        val loaded = _state.value as? SftpBrowserUiState.Loaded ?: return
        val fullPath = joinPath(loaded.cwd, name)
        updateLoaded { it.copy(dialog = null) }
        appScope.coroutineScope.launch {
            when (val r = sftpManager.createDirectory(sessionId, fullPath)) {
                is SshResult.Success -> refresh()
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
                    lastError = "${file.name}: ${result.message.ifBlank { result.code.name }}"
                }
            }
            if (lastError != null) setError(lastError)
            refresh()
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun sanitized(path: String): String = sftpManager.sanitizePath(path) ?: "/"

    private fun updateLoaded(transform: (SftpBrowserUiState.Loaded) -> SftpBrowserUiState.Loaded) {
        _state.update { current -> if (current is SftpBrowserUiState.Loaded) transform(current) else current }
    }

    private fun setError(message: String) {
        updateLoaded { it.copy(lastError = message.ifBlank { "Erreur SFTP" }) }
    }

    private fun joinPath(cwd: String, name: String): String =
        if (cwd.endsWith('/')) cwd + name else "$cwd/$name"

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
