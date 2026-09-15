// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.ssh

import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.shared.domain.model.TransferDirection
import fr.techtical.nextsh.shared.domain.model.TransferRequest
import fr.techtical.nextsh.shared.domain.model.TransferState
import fr.techtical.nextsh.shared.util.AppScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Singleton (via [fr.techtical.nextsh.desktop.data.di.DesktopContainer]) that
 * orchestrates SFTP uploads / downloads on top of [DesktopSftpManager].
 *
 * Each transfer is a coroutine [Job] on [AppScope] so `cancel()` delivers a
 * clean [CancellationException] to the SFTP stream loop. State for every
 * transfer (queued, in-progress with bytes, completed, failed, cancelled)
 * is exposed as a [StateFlow] the UI observes directly.
 *
 * Local IO uses plain `java.io.File` streams (JFileChooser supplies the
 * local path). No OS notifications on Phase 2.x: the in-app transfers
 * panel is the single progress surface.
 */
class DesktopTransferManager(
    private val sftpManager: DesktopSftpManager,
    private val appScope: AppScope,
) {

    private val _transfers = MutableStateFlow<Map<String, TransferState>>(emptyMap())
    val transfers: StateFlow<Map<String, TransferState>> = _transfers.asStateFlow()

    /** Count of transfers currently in-flight: drives the sidebar badge. */
    val activeCount: StateFlow<Int> = transfers
        .map { map -> map.values.count { it is TransferState.InProgress || it is TransferState.Queued } }
        .stateIn(appScope.coroutineScope, SharingStarted.Eagerly, 0)

    private val jobs = ConcurrentHashMap<String, Job>()

    // ── Enqueue ───────────────────────────────────────────────────────────────

    /**
     * Queue an upload of [localFile] to `[remoteDir]/[localFile.name]`. Returns
     * the transfer id so the caller can observe progress or cancel. The
     * remote name is preserved; no timestamp suffix, no collision check (SFTP
     * writeFile truncates).
     */
    fun enqueueUpload(
        sessionId: String,
        remoteDir: String,
        localFile: File,
        hostLabel: String? = null,
    ): String {
        val remotePath = if (remoteDir.endsWith('/')) remoteDir + localFile.name
        else "$remoteDir/${localFile.name}"
        val request = TransferRequest(
            sessionId = sessionId,
            remotePath = remotePath,
            localUri = localFile.absolutePath,
            direction = TransferDirection.UPLOAD,
            fileSize = localFile.length(),
            displayName = localFile.name,
            hostLabel = hostLabel,
        )
        launchTransfer(request) {
            FileInputStream(localFile).use { input ->
                sftpManager.writeFile(sessionId, remotePath, input, request.fileSize) { done, total ->
                    _transfers.update { it + (request.id to TransferState.InProgress(request, done, total)) }
                }
            }
        }
        return request.id
    }

    /**
     * Queue a download of [remotePath] (size [fileSize]) to [localFile].
     * Overwrites the destination if it exists: the caller is responsible
     * for any prompt.
     */
    fun enqueueDownload(
        sessionId: String,
        remotePath: String,
        fileSize: Long,
        localFile: File,
        displayName: String,
        hostLabel: String? = null,
    ): String {
        val request = TransferRequest(
            sessionId = sessionId,
            remotePath = remotePath,
            localUri = localFile.absolutePath,
            direction = TransferDirection.DOWNLOAD,
            fileSize = fileSize,
            displayName = displayName,
            hostLabel = hostLabel,
        )
        launchTransfer(request) {
            FileOutputStream(localFile).use { output ->
                sftpManager.readFile(sessionId, remotePath, output) { done, total ->
                    _transfers.update { it + (request.id to TransferState.InProgress(request, done, total)) }
                }
            }
        }
        return request.id
    }

    // ── Cancel / dismiss ──────────────────────────────────────────────────────

    fun cancel(id: String) {
        jobs[id]?.cancel()
    }

    /** Remove a terminal-state entry from the map (Completed / Failed / Cancelled). */
    fun dismiss(id: String) {
        _transfers.update { it - id }
    }

    fun dismissAllCompleted() {
        _transfers.update { map ->
            map.filterValues { it !is TransferState.Completed && it !is TransferState.Cancelled && it !is TransferState.Failed }
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun launchTransfer(
        request: TransferRequest,
        block: suspend () -> SshResult<Unit>,
    ) {
        _transfers.update { it + (request.id to TransferState.Queued(request)) }
        val job = appScope.coroutineScope.launch {
            try {
                _transfers.update { it + (request.id to TransferState.InProgress(request, 0, request.fileSize)) }
                when (val result = block()) {
                    is SshResult.Success -> _transfers.update {
                        it + (request.id to TransferState.Completed(request))
                    }
                    is SshResult.Error -> _transfers.update {
                        it + (request.id to TransferState.Failed(request, result.message.ifBlank { result.code.name }))
                    }
                }
            } catch (e: CancellationException) {
                _transfers.update { it + (request.id to TransferState.Cancelled(request)) }
                throw e
            } catch (e: Exception) {
                _transfers.update {
                    it + (request.id to TransferState.Failed(request, e.message ?: "Erreur inconnue"))
                }
            } finally {
                jobs.remove(request.id)
            }
        }
        jobs[request.id] = job
    }
}
