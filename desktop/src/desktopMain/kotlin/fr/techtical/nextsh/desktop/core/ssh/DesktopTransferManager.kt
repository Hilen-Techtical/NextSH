// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.ssh

import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_local_exists
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_local_read_failed
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_local_write_failed
import fr.techtical.nextsh.desktop.generated.resources.transfers_failed_default
import fr.techtical.nextsh.shared.domain.model.SshErrorCode
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.shared.domain.model.TransferDirection
import fr.techtical.nextsh.shared.domain.model.TransferRequest
import fr.techtical.nextsh.shared.domain.model.TransferState
import fr.techtical.nextsh.shared.util.AppScope
import fr.techtical.nextsh.shared.util.LogId
import fr.techtical.nextsh.shared.util.Logger
import fr.techtical.nextsh.shared.util.errorClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.getString
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "DesktopTransferManager"

/**
 * Most bytes (in UTF-8) of the downloaded file's name that are kept in the name of its
 * temporary file. Bytes and not characters: file systems limit a name in bytes (255 on ext4)
 * and a CJK character takes three of them.
 */
private const val TEMP_NAME_MAX_BYTES = 120

/** Length of the random suffix that tells two temporary files of the same name apart. */
private const val TEMP_ID_CHARS = 8

/** The permissions of a temporary file: its owner only, since it may replace a private file. */
private val OWNER_ONLY = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)

/**
 * Singleton (via [fr.techtical.nextsh.desktop.data.di.DesktopContainer]) that
 * orchestrates SFTP uploads / downloads on top of [DesktopSftpManager].
 *
 * Each transfer is a coroutine [Job] on [AppScope] so `cancel()` delivers a
 * clean [CancellationException] to the SFTP stream loop. State for every
 * transfer (queued, in-progress with bytes, completed, failed, cancelled)
 * is exposed as a [StateFlow] the UI observes directly.
 *
 * Nothing is ever replaced on its own. A transfer that is not flagged
 * `replaceExisting` refuses a destination that already exists, on the server
 * (`EXCL` open) as on the computer (a move that never replaces), and fails
 * with a message instead of overwriting: the server's check is atomic, so a
 * name taken between the caller's own verification and the transfer is
 * protected too. A caller passes `replaceExisting = true` only after the user
 * confirmed the replacement (see `SftpBrowserViewModel`).
 *
 * An upload that replaces a file on the server does not lose it either: the data goes to
 * a hidden temporary file next to it on the server, renamed over it once complete
 * (see [DesktopSftpManager.writeFile] for the cases where it is written in place instead).
 *
 * A download never touches its destination before it has succeeded: the data
 * goes to a temporary file next to it (`.name.nextsh-part-<id>`, a hidden name on
 * Unix, merely a recognizable one on Windows, where a leading dot hides nothing),
 * which is moved into place at the end and deleted on failure or cancellation. A
 * file the user agreed to replace is therefore still there if the server refuses,
 * the session drops or the transfer is cancelled. [deleteTemporaries] removes
 * those still around when the application quits or purges its data.
 *
 * Replacing a file this way puts a NEW file in its place, not new content in the
 * old one: on Unix it keeps the permission bits of the replaced file (a key or a
 * `.env` in 0600 stays private) but not its owner, and a hard link to the old
 * file is broken. A symbolic link at the destination is replaced by a regular
 * file, the file the link pointed to is left as it is. A file downloaded under a
 * name that was free is created readable by its owner only (0600) on Unix.
 *
 * Every error shown in the transfers panel is a translated message: a raw
 * exception message (OS text, local path) never reaches the UI.
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

    /** Ids of the transfers still running when [clearAll] emptied the list: their later state writes are ignored. */
    private val dropped: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Records the state of a transfer, unless the list was cleared while it ran. */
    private fun put(id: String, state: TransferState) {
        _transfers.update { map -> if (id in dropped) map else map + (id to state) }
    }

    /** The temporary files of the downloads under way, so that quitting can delete them. */
    private val liveTemporaries: MutableSet<Path> = ConcurrentHashMap.newKeySet()

    // ── Enqueue ───────────────────────────────────────────────────────────────

    /**
     * Queue an upload of [localFile] to [uploadTargetPath] (`[remoteDir]/[localFile.name]`,
     * where each control or invisible format character of the name becomes `_`).
     * Returns the transfer id so the caller can observe progress or cancel. The
     * remote name is otherwise preserved, no timestamp suffix. The name shown in the
     * transfers panel is the remote one.
     *
     * [replaceExisting] says whether a file already on the server under that name may be
     * replaced. It must be true only once the user confirmed. When false the server
     * refuses a taken name and the transfer fails with a message, whatever happened since
     * the caller checked. A local file that can no longer be read fails the transfer too,
     * before anything is sent. When true, the file on the server is replaced through a
     * temporary file (see [DesktopSftpManager.writeFile]): a failed or cancelled upload
     * leaves it as it was.
     */
    fun enqueueUpload(
        sessionId: String,
        remoteDir: String,
        localFile: File,
        replaceExisting: Boolean,
        hostLabel: String? = null,
    ): String {
        val remotePath = uploadTargetPath(remoteDir, localFile.name)
        val request = TransferRequest(
            sessionId = sessionId,
            remotePath = remotePath,
            localUri = localFile.absolutePath,
            direction = TransferDirection.UPLOAD,
            fileSize = localFile.length(),
            displayName = remotePath.substringAfterLast('/'),
            hostLabel = hostLabel,
        )
        launchTransfer(request) {
            // Opening a file is blocking disk I/O: off the default workers.
            withContext(Dispatchers.IO) {
                val input = try {
                    FileInputStream(localFile)
                } catch (_: IOException) {
                    return@withContext localReadFailed()
                } catch (_: SecurityException) {
                    return@withContext localReadFailed()
                }
                input.use { stream ->
                    sftpManager.writeFile(sessionId, remotePath, stream, request.fileSize, replaceExisting) { done, total ->
                        put(request.id, TransferState.InProgress(request, done, total))
                    }
                }
            }
        }
        return request.id
    }

    /**
     * Queue a download of [remotePath] (size [fileSize]) to [localFile].
     *
     * [replaceExisting] says whether [localFile] may be replaced if it exists. It must be
     * true only once the user confirmed. When false a destination that exists fails the
     * transfer with a message, before anything is read from the server; one that appears
     * while the file is downloaded fails it at the end, when the file is moved into place.
     * Either way the existing file is left untouched. A destination that cannot be written
     * fails the transfer too.
     *
     * The data goes to a temporary file next to [localFile] first, see [downloadThroughTemp]:
     * a failed or cancelled download never costs the user the file it was to replace.
     */
    fun enqueueDownload(
        sessionId: String,
        remotePath: String,
        fileSize: Long,
        localFile: File,
        displayName: String,
        replaceExisting: Boolean,
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
            // All the local disk work (checks, creation, move, deletion) is blocking I/O: it
            // runs on the I/O workers, not on the default ones. The cleanup in `finally` does
            // not suspend, so a cancelled download still deletes its temporary file.
            withContext(Dispatchers.IO) {
                downloadThroughTemp(request.id, sessionId, remotePath, localFile, replaceExisting) { done, total ->
                    put(request.id, TransferState.InProgress(request, done, total))
                }
            }
        }
        return request.id
    }

    /**
     * Deletes the temporary files of the downloads still under way. Synchronous, for the
     * application to call when it quits or purges its data, once the sessions are closed: the
     * download that was writing one runs on a daemon thread that dies with the process, before
     * its own cleanup could run (the purge ends the process right after). These files sit next
     * to the chosen destinations, outside the folders the purge deletes. Best effort, a file
     * that cannot be deleted is left.
     */
    fun deleteTemporaries() {
        for (temp in liveTemporaries.toList()) {
            deleteQuietly(temp)
            liveTemporaries.remove(temp)
        }
    }

    // ── Cancel / dismiss ──────────────────────────────────────────────────────

    fun cancel(id: String) {
        jobs[id]?.cancel()
    }

    /**
     * Cancels every transfer and waits for them to end, at most [timeoutMs]: a cancelled download deletes
     * its temporary file and a cancelled replacement leaves the original as it was, as long as the channel
     * is still open, so this runs BEFORE the sessions are closed. A transfer stuck on a dead route does not
     * hold the caller back: the wait gives up at the delay and the transfer is left to the session close.
     * Returns whether every transfer ended in time.
     */
    suspend fun cancelAll(timeoutMs: Long = 5_000): Boolean {
        val running = jobs.values.toList()
        running.forEach { it.cancel() }
        return withTimeoutOrNull(timeoutMs) { running.joinAll() } != null
    }

    /** Empties the list, whatever the state of its entries: it carries host labels and local and remote paths. */
    fun clearAll() {
        // A transfer that outlived [cancelAll] writes its state again when it finally ends: its id is
        // remembered here, and the writes of a dropped id are ignored.
        dropped.addAll(jobs.keys)
        _transfers.value = emptyMap()
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
        put(request.id, TransferState.Queued(request))
        // The message of a failure nobody caught, for the completion handler below, which
        // cannot suspend to read a string: the body reads it first thing.
        val failureMessage = AtomicReference<String?>(null)
        // Lazy, so that the job is registered in `jobs` before its body can run: on a
        // multi-threaded scope a quick transfer could otherwise end (and try to remove its
        // entry) before it was added, which would leave the entry behind for good.
        val job = appScope.coroutineScope.launch(start = CoroutineStart.LAZY) {
            failureMessage.set(getString(Res.string.transfers_failed_default))
            try {
                put(request.id, TransferState.InProgress(request, 0, request.fileSize))
                when (val result = block()) {
                    is SshResult.Success -> put(request.id, TransferState.Completed(request))
                    is SshResult.Error -> {
                        // Read once, outside the update: the text comes from the resources.
                        val message = result.message.ifBlank { getString(Res.string.transfers_failed_default) }
                        put(request.id, TransferState.Failed(request, message))
                    }
                }
            } catch (e: CancellationException) {
                put(request.id, TransferState.Cancelled(request))
                throw e
            } catch (e: Exception) {
                // The exception message is never shown nor logged: it can carry a local
                // path or a file name. The class chain is enough to find the cause.
                Logger.w(TAG, "transfer ${LogId.of(request.id)} failed: ${errorClass(e)}")
                // Not cancellable: if the job was cancelled at the same time, a plain
                // `getString` would throw out of this handler and the entry would stay
                // "in progress" for ever.
                val message = withContext(NonCancellable) { getString(Res.string.transfers_failed_default) }
                put(request.id, TransferState.Failed(request, message))
            } finally {
                jobs.remove(request.id)
            }
        }
        jobs[request.id] = job
        // The net under the handlers of the body, for the two ways out they do not see:
        // - a job cancelled before its body ever ran, which would stay "queued" for good;
        // - a Throwable that is not an Exception (NoClassDefFoundError...), which the body does
        //   not catch and which would leave the entry "in progress" for good.
        // Only an entry that is still queued or in progress is touched: a transfer that
        // reached its own end keeps it.
        job.invokeOnCompletion { cause ->
            if (cause == null) {
                dropped.remove(request.id)
                return@invokeOnCompletion
            }
            jobs.remove(request.id)
            if (cause !is CancellationException) {
                Logger.w(TAG, "transfer ${LogId.of(request.id)} ended abnormally: ${errorClass(cause)}")
            }
            _transfers.update { map ->
                val state = map[request.id]
                if (request.id in dropped) {
                    map
                } else if (state is TransferState.Queued || state is TransferState.InProgress) {
                    val ended = if (cause is CancellationException) {
                        TransferState.Cancelled(request)
                    } else {
                        TransferState.Failed(request, failureMessage.get().orEmpty())
                    }
                    map + (request.id to ended)
                } else {
                    map
                }
            }
            dropped.remove(request.id)
        }
        job.start()
    }

    /**
     * Downloads [remotePath] to [localFile] without touching [localFile] before the end.
     *
     * 1. Without [replaceExisting], a destination that already exists fails at once,
     *    before the server is even asked for the file.
     * 2. The data is written to a temporary file in the same folder, created with
     *    `CREATE_NEW` so that no existing file can be written to by mistake, and readable
     *    by its owner only (0600) where the file system has Unix permissions.
     * 3. On success, with [replaceExisting], the permission bits of the file about to be
     *    replaced are copied onto the temporary one (best effort), then the temporary file
     *    is moved onto [localFile], atomically where the file system allows it. Without
     *    [replaceExisting] it is put in place by [addTemporary], which never replaces: a
     *    file that appeared meanwhile makes it fail with the "already exists" message and
     *    stays intact.
     * 4. Whatever the outcome, the temporary file is deleted if it is still there: failure,
     *    cancellation (rethrown, never swallowed) or unexpected exception. One exception: if
     *    a replacement failed half way and [localFile] is gone, the temporary file holds the
     *    only copy of the data and is kept.
     *
     * Runs on the I/O workers. [transferId] only serves to tell the log lines apart.
     */
    private suspend fun downloadThroughTemp(
        transferId: String,
        sessionId: String,
        remotePath: String,
        localFile: File,
        replaceExisting: Boolean,
        onProgress: (bytesTransferred: Long, totalBytes: Long) -> Unit,
    ): SshResult<Unit> {
        val (target, temp) = try {
            val path = localFile.toPath()
            path to temporaryPathFor(path)
        } catch (_: InvalidPathException) {
            return localWriteFailed()
        }
        if (!replaceExisting && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_local_exists))
        }
        var created = false
        var placing = false
        var placed = false
        try {
            val output = try {
                createTemporary(temp)
            } catch (_: IOException) {
                return localWriteFailed()
            } catch (_: SecurityException) {
                return localWriteFailed()
            }
            created = true
            liveTemporaries.add(temp)
            val result = output.use { stream -> sftpManager.readFile(sessionId, remotePath, stream, onProgress) }
            if (result !is SshResult.Success) return result
            if (replaceExisting) keepPermissions(transferId, from = target, to = temp)
            placing = true
            val outcome = if (replaceExisting) replaceWithTemporary(temp, target) else addTemporary(temp, target)
            placed = outcome is SshResult.Success
            return outcome
        } finally {
            if (created) {
                val onlyCopy = placing && replaceExisting && !placed && !Files.exists(target)
                if (onlyCopy) {
                    Logger.w(TAG, "transfer ${LogId.of(transferId)}: replacement failed and the destination is gone, temporary file kept")
                } else {
                    deleteQuietly(temp)
                }
                liveTemporaries.remove(temp)
            }
        }
    }

    /**
     * Creates the temporary file, empty and open for writing. `CREATE_NEW`: it never opens
     * a file that exists. On a file system with Unix permissions it is created readable by
     * its owner only, in the same system call: the file may be about to replace a private
     * one (a key, a `.env`), and must never be readable by others, not even for a moment.
     */
    private fun createTemporary(temp: Path): OutputStream =
        if (hasPosixPermissions(temp)) {
            val options = setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            val channel = Files.newByteChannel(temp, options, PosixFilePermissions.asFileAttribute(OWNER_ONLY))
            Channels.newOutputStream(channel)
        } else {
            Files.newOutputStream(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        }

    private fun hasPosixPermissions(path: Path): Boolean =
        "posix" in path.fileSystem.supportedFileAttributeViews()

    /**
     * Copies the permission bits of [from], the file about to be replaced, onto [to], the
     * file that will replace it, so that a private file stays private. Best effort: a
     * failure (nothing to copy from, no Unix permissions, not allowed) never fails the
     * download, it only costs the bits (the temporary file stays in 0600), and the class of
     * the error is logged. Not copied: the owner, the group, the setuid and sticky bits.
     * Follows a symbolic link, so the bits are those of the file the link points to.
     */
    private fun keepPermissions(transferId: String, from: Path, to: Path) {
        if (!hasPosixPermissions(to)) return
        try {
            Files.setPosixFilePermissions(to, Files.getPosixFilePermissions(from))
        } catch (_: NoSuchFileException) {
            // Nothing to replace after all: the temporary file keeps its 0600.
        } catch (e: IOException) {
            logPermissionsNotKept(transferId, e)
        } catch (e: UnsupportedOperationException) {
            logPermissionsNotKept(transferId, e)
        } catch (e: SecurityException) {
            logPermissionsNotKept(transferId, e)
        }
    }

    private fun logPermissionsNotKept(transferId: String, e: Exception) {
        Logger.w(TAG, "transfer ${LogId.of(transferId)}: permissions of the replaced file not kept: ${errorClass(e)}")
    }

    /**
     * Puts [temp] in place of [target], which the user agreed to replace: a move that replaces,
     * atomic where the file system can, otherwise not (the caller keeps [temp] if [target] is
     * gone by the time a failure comes back).
     */
    private suspend fun replaceWithTemporary(temp: Path, target: Path): SshResult<Unit> {
        try {
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
            return SshResult.Success(Unit)
        } catch (_: IOException) {
            return localWriteFailed()
        } catch (_: SecurityException) {
            return localWriteFailed()
        }
    }

    /**
     * Puts [temp] under the name [target], which must NOT replace anything: if [target]
     * exists, even created a moment ago by someone else, nothing is changed and the result
     * says so.
     *
     * Where the file system has hard links (Unix), the name is created with `createLink`:
     * the system call fails if the name exists, so the check and the creation are one
     * atomic step. A plain move first looks, then renames, and the rename would replace a
     * file created in between. The temporary name is deleted afterwards by the caller.
     * Where hard links are not available (FAT, exFAT, Windows) the move is used: Windows
     * refuses to move onto an existing name, atomically.
     */
    private suspend fun addTemporary(temp: Path, target: Path): SshResult<Unit> {
        try {
            if (hasPosixPermissions(temp)) {
                try {
                    Files.createLink(target, temp)
                    return SshResult.Success(Unit)
                } catch (e: FileAlreadyExistsException) {
                    throw e
                } catch (_: UnsupportedOperationException) {
                    // No hard links here: move instead.
                } catch (_: FileSystemException) {
                    // Hard links refused by this file system (FAT, exFAT...): move instead.
                }
            }
            Files.move(temp, target)
            return SshResult.Success(Unit)
        } catch (_: FileAlreadyExistsException) {
            return SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_local_exists))
        } catch (_: IOException) {
            return localWriteFailed()
        } catch (_: SecurityException) {
            return localWriteFailed()
        }
    }

    private fun deleteQuietly(path: Path) {
        try {
            Files.deleteIfExists(path)
        } catch (_: IOException) {
            // A hidden leftover is all it costs.
        } catch (_: SecurityException) {
            // Same.
        }
    }

    /**
     * A name next to [target], with nothing but a part of the file's own name and a random
     * suffix: `.report.pdf.nextsh-part-1a2b3c4d`. The part of the name is cut by
     * [temporaryNamePart], so that the whole name stays within the limits of the file system.
     */
    private fun temporaryPathFor(target: Path): Path {
        val name = temporaryNamePart(target.fileName?.toString().orEmpty())
        val suffix = UUID.randomUUID().toString().take(TEMP_ID_CHARS)
        return target.resolveSibling(".$name.nextsh-part-$suffix")
    }

    private suspend fun localReadFailed(): SshResult.Error =
        SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_local_read_failed))

    private suspend fun localWriteFailed(): SshResult.Error =
        SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_local_write_failed))

    companion object {
        /**
         * Remote path an upload of a file named [localName] into [remoteDir] writes to. The
         * single source of that path, and so of the remote name: the browser verifies it before
         * asking the user, and [enqueueUpload] writes to it. Each control character and each
         * invisible format character of the local name becomes `_` ([replaceInvisibleCharacters]), and
         * so does each `\` (a new name, not a path walked: same rule as Android `uploadEntryName`, and a
         * Windows server could take the `\` for a separator); nothing else changes (no trim, no cut).
         */
        internal fun uploadTargetPath(remoteDir: String, localName: String): String {
            val name = replaceInvisibleCharacters(localName, '_').replace('\\', '_')
            return if (remoteDir.endsWith('/')) remoteDir + name else "$remoteDir/$name"
        }

        /**
         * The beginning of [name] that fits in [maxBytes] bytes of UTF-8, cut between two code
         * points: never inside a surrogate pair (Linux rejects the resulting name), and never
         * past the byte limit of the file system, which a name of three-byte characters
         * (CJK) reaches long before it reaches a limit counted in characters.
         */
        internal fun temporaryNamePart(name: String, maxBytes: Int = TEMP_NAME_MAX_BYTES): String {
            var bytes = 0
            var end = 0
            while (end < name.length) {
                val codePoint = name.codePointAt(end)
                val size = when {
                    codePoint < 0x80 -> 1
                    codePoint < 0x800 -> 2
                    codePoint < 0x10000 -> 3
                    else -> 4
                }
                if (bytes + size > maxBytes) break
                bytes += size
                end += Character.charCount(codePoint)
            }
            return name.substring(0, end)
        }
    }
}
