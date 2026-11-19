// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.ssh

import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_cannot_delete_root
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_chmod_failed
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_client_not_ready
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_close_failed
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_connection_lost
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_create_dir_failed
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_create_file_failed
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_delete_failed
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_delete_recursive_failed
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_download_failed
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_download_generic
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_entry_name_invalid
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_generic
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_invalid_file_name
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_invalid_folder_name
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_invalid_path
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_invalid_permissions
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_is_directory
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_local_read_failed
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_local_write_failed
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_no_space
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_not_found
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_permission_denied
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_preview_too_large
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_read_failed
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_read_generic
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_rename_failed
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_replace_failed_kept
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_session_not_found
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_stat_failed
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_target_exists
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_upload_failed
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_upload_generic
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_upload_target_changed
import fr.techtical.nextsh.shared.domain.model.SftpFile
import fr.techtical.nextsh.shared.domain.model.SshErrorCode
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.shared.util.LogId
import fr.techtical.nextsh.shared.util.Logger
import fr.techtical.nextsh.shared.util.errorClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.RenameFlags
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.xfer.FilePermission
import org.jetbrains.compose.resources.getString
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "DesktopSftpManager"

/** Length of the random suffix that tells two temporary files of the same upload apart. */
private const val TEMP_ID_CHARS = 8

/** Size of the blocks of an upload (the first one, read before an in-place open, included). */
private const val COPY_BLOCK_SIZE = 32768

/** How many names of temporary files known gone are remembered (see [DesktopSftpManager.knownUploadTempNames]). */
private const val GONE_TEMPS_KEPT = 256

/** Names tried for the temporary file of one upload before giving up (a taken name is refused by `EXCL`). */
private const val TEMP_CREATE_ATTEMPTS = 3

/** What sits between the (cut) name of the file and the random suffix, as in the temporary files of a download. */
private const val UPLOAD_TEMP_MARKER = ".nextsh-part-"

/**
 * What the name of a leftover temporary file tells: the start of the name of the file it was to replace, and
 * whether that start may have been cut (a name of 117 to 120 bytes may be whole, which is shown with an
 * ellipsis anyway: cosmetic).
 */
internal data class UploadLeftoverName(val original: String, val cut: Boolean)

/** Permissions of the temporary file at creation: its owner only, 0600. Kotlin has no octal literal. */
private const val TEMP_FILE_MODE = 0b110_000_000

/** The write bit of the owner (0200), absent on a file that is write-protected. */
private const val OWNER_WRITE_BIT = 0b010_000_000

/** The permission bits that are given back to the new file, 0777: never setuid, setgid nor sticky. */
private const val PLAIN_PERMISSION_BITS = 0b111_111_111

/** The extension of OpenSSH that renames over an existing name, atomically (`rename(2)`). */
private const val POSIX_RENAME_NAME = "posix-rename"
private const val POSIX_RENAME_DOMAIN = "openssh.com"

/**
 * A failure of the local side of a transfer, the computer's disk and not the connection
 * (disk full, drive removed, file locked). It only exists to tell such an [IOException]
 * apart from a lost SFTP connection, which is an [IOException] too: [LocalSink] and
 * [LocalSource] throw it, [DesktopSftpManager.readFile] and [DesktopSftpManager.writeFile]
 * map it to their local error message. Its message is fixed, the cause (which can quote a
 * local path) is only kept for debugging and never shown nor logged.
 */
internal class LocalIoException(cause: IOException) : IOException("local I/O failed", cause)

/** Runs [block] and marks any [IOException] it throws as a local one. */
private inline fun <T> markingLocal(block: () -> T): T = try {
    block()
} catch (e: LocalIoException) {
    throw e
} catch (e: IOException) {
    throw LocalIoException(e)
}

/** Output stream handed to a download: its failures are the computer's, see [LocalIoException]. Never closes [delegate]. */
private class LocalSink(private val delegate: OutputStream) : OutputStream() {
    override fun write(b: Int) = markingLocal { delegate.write(b) }
    override fun write(b: ByteArray, off: Int, len: Int) = markingLocal { delegate.write(b, off, len) }
    override fun flush() = markingLocal { delegate.flush() }
}

/** Input stream handed to an upload: its failures are the computer's, see [LocalIoException]. Never closes [delegate]. */
private class LocalSource(private val delegate: InputStream) : InputStream() {
    override fun read(): Int = markingLocal { delegate.read() }
    override fun read(b: ByteArray, off: Int, len: Int): Int = markingLocal { delegate.read(b, off, len) }
}

/**
 * What a remote path designates, as far as an upload is concerned: nothing yet
 * ([ABSENT]), a file that would be replaced ([FILE]), a folder that cannot be
 * written over ([DIRECTORY]), or any other kind of entry (socket, FIFO, device) that
 * is never overwritten ([OTHER]).
 */
enum class RemoteEntryKind { ABSENT, FILE, DIRECTORY, OTHER }

/**
 * Desktop port of `app/.../SftpManager.kt` (Android). SSHJ is pure JVM, so
 * the implementation is effectively a copy minus Android hooks (Timber,
 * Hilt annotations) and with the client lookup wired to
 * [DesktopSshSessionManager] instead of the Android `SshSessionManager`.
 *
 * Each SSH session may have one associated [SFTPClient], keyed by sessionId.
 * All I/O runs on [Dispatchers.IO]; all results flow through [SshResult]:
 * exceptions never bubble to the UI.
 *
 * Security: [sanitizePath] rejects null-bytes and out-of-root traversals
 * (paths are literal, never URL-decoded). A typed name (new folder, new file,
 * rename) is refused by [isValidEntryName] when it is not a bare name, never
 * corrected; it is joined to its parent, which alone goes through [sanitizePath].
 */
class DesktopSftpManager(
    private val sshSessionManager: DesktopSshSessionManager,
) {

    private val sftpClients = ConcurrentHashMap<String, SFTPClient>()
    private val openMutex = Mutex()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    suspend fun openSftp(sessionId: String): SshResult<Unit> = withContext(Dispatchers.IO) {
        openMutex.withLock {
            if (sftpClients.containsKey(sessionId)) return@withContext SshResult.Success(Unit)
            val client = sshSessionManager.getTerminalSession(sessionId)?.getSshClient()
                ?: return@withContext SshResult.Error(
                    SshErrorCode.UNKNOWN,
                    getString(Res.string.sftp_error_session_not_found, sessionId),
                )
            return@withContext try {
                val sftp = client.newSFTPClient()
                sftpClients[sessionId] = sftp
                SshResult.Success(Unit)
            } catch (e: Exception) {
                SshResult.Error(SshErrorCode.UNKNOWN, e.message ?: getString(Res.string.sftp_error_generic))
            }
        }
    }

    suspend fun closeSftp(sessionId: String): SshResult<Unit> = withContext(Dispatchers.IO) {
        return@withContext try {
            sftpClients.remove(sessionId)?.close()
            SshResult.Success(Unit)
        } catch (e: Exception) {
            SshResult.Error(SshErrorCode.UNKNOWN, e.message ?: getString(Res.string.sftp_error_close_failed))
        }
    }

    fun isOpen(sessionId: String): Boolean = sftpClients.containsKey(sessionId)

    // ── Navigation ────────────────────────────────────────────────────────────

    suspend fun getHomeDirectory(sessionId: String): SshResult<String> = withContext(Dispatchers.IO) {
        val sftp = sftpClients[sessionId]
            ?: return@withContext SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_client_not_ready))
        return@withContext try {
            SshResult.Success(sftp.canonicalize("."))
        } catch (_: Exception) {
            SshResult.Success("/")
        }
    }

    suspend fun listDirectory(sessionId: String, path: String): SshResult<List<SftpFile>> =
        withContext(Dispatchers.IO) {
            val sftp = sftpClients[sessionId]
                ?: return@withContext SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_client_not_ready))
            val safePath = sanitizePath(path)
                ?: return@withContext SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_invalid_path))
            return@withContext try {
                val entries: List<RemoteResourceInfo> = sftp.ls(safePath)
                SshResult.Success(entries.map { it.toSftpFile() })
            } catch (e: net.schmizz.sshj.sftp.SFTPException) {
                SshResult.Error(SshErrorCode.UNKNOWN, sftpErrorMessage(e, getString(Res.string.sftp_error_generic)))
            } catch (_: java.io.IOException) {
                SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_connection_lost))
            } catch (_: Exception) {
                SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_generic))
            }
        }

    // ── CRUD (wired for Wave 2.2, kept here to match the Android API) ────────

    /**
     * Creates the folder [name] inside [parentDir].
     *
     * [name] must be a bare entry name (see [isValidEntryName]) and is checked
     * BEFORE being joined to [parentDir]: a name such as `../x` or `a/b` is
     * refused instead of creating a folder outside, or below, the displayed
     * directory. Both parts are used literally (no URL decoding).
     *
     * The name is looked up on the server first: the `SSH_FXP_MKDIR` of an SFTP v3 server refuses
     * an existing folder with a plain `FAILURE` that tells nothing. The lookup only serves the
     * message, `mkdir` never replaces anything: a lookup the server refuses (a permission, on a
     * write-only folder for example) proves nothing, and `mkdir` is sent all the same. A channel that
     * is lost during the lookup, or a server that stays silent (see [isChannelFailure]), is not an
     * answer: everything stops with the message of a lost connection, since `mkdir` would only wait
     * for the timeout once more on a dead channel.
     */
    suspend fun createDirectory(sessionId: String, parentDir: String, name: String): SshResult<Unit> =
        withContext(Dispatchers.IO) {
            val sftp = sftpClients[sessionId]
                ?: return@withContext SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_client_not_ready))
            if (!isValidEntryName(name)) {
                return@withContext SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_invalid_folder_name))
            }
            val safeParent = sanitizePath(parentDir)
                ?: return@withContext SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_invalid_path))
            val safePath = if (safeParent == "/") "/$name" else "$safeParent/$name"
            return@withContext try {
                val taken = try {
                    sftp.statExistence(safePath) != null
                } catch (e: net.schmizz.sshj.sftp.SFTPException) {
                    // An answer of the server proves nothing about the name: mkdir is sent all the same.
                    // A lost channel is no answer, mkdir would wait for the timeout again: stop here, as a
                    // lost connection (caught below).
                    if (isChannelFailure(e)) throw IOException("SFTP channel lost during the lookup", e)
                    false
                }
                if (taken) {
                    return@withContext SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_target_exists))
                }
                sftp.mkdir(safePath)
                SshResult.Success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: net.schmizz.sshj.sftp.SFTPException) {
                SshResult.Error(SshErrorCode.UNKNOWN, sftpErrorMessage(e, getString(Res.string.sftp_error_create_dir_failed)))
            } catch (_: java.io.IOException) {
                SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_connection_lost))
            } catch (_: Exception) {
                SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_create_dir_failed))
            }
        }

    /**
     * Creates an empty file named [name] inside [parentDir]. Never overwrites: an
     * existing entry (file, folder or live symlink) is refused up front with a clear
     * message, then the open itself uses `EXCL` so the server also rejects the
     * creation atomically if the entry appears in between. `TRUNC` must never be
     * added here, it would wipe a file created by someone else in that window
     * (unlike [writeFile] with `replaceExisting`, which replaces on purpose after the
     * user confirmed, through a temporary file or in place).
     *
     * The name arrives bare, never joined to the folder first: a joined path would
     * let `a/b.txt` or `../x` pass the name check on their last segment and land in
     * another folder than the one displayed. [isValidEntryName] refuses any
     * separator, then the manager does the joining itself.
     *
     * Only the parent goes through [sanitizePath]; the name is used as is, so a
     * literal `+` or `%41` typed by the user stays in the name.
     *
     * The lookup only serves the message: a lost channel or a silent server during it (see
     * [isChannelFailure]) stops everything with the connection lost message, nothing is opened.
     * Any other answer of the server keeps its own message.
     */
    suspend fun createFile(sessionId: String, parentDir: String, name: String): SshResult<Unit> =
        withContext(Dispatchers.IO) {
            val sftp = sftpClients[sessionId]
                ?: return@withContext SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_client_not_ready))
            if (!isValidEntryName(name)) {
                return@withContext SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_invalid_file_name))
            }
            val safeParent = sanitizePath(parentDir)
                ?: return@withContext SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_invalid_path))
            val safePath = if (safeParent == "/") "/$name" else "$safeParent/$name"
            var remoteFile: net.schmizz.sshj.sftp.RemoteFile? = null
            return@withContext try {
                val taken = try {
                    sftp.statExistence(safePath) != null
                } catch (e: net.schmizz.sshj.sftp.SFTPException) {
                    // A lost channel is no answer: the open would only wait for the timeout again. Stop here,
                    // as a lost connection (caught below). An answer of the server keeps its own message.
                    if (isChannelFailure(e)) throw java.io.IOException("SFTP channel lost during the lookup", e)
                    throw e
                }
                if (taken) {
                    return@withContext SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_target_exists))
                }
                remoteFile = sftp.open(safePath, setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL))
                SshResult.Success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: net.schmizz.sshj.sftp.SFTPException) {
                SshResult.Error(SshErrorCode.UNKNOWN, sftpErrorMessage(e, getString(Res.string.sftp_error_create_file_failed)))
            } catch (_: java.io.IOException) {
                SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_connection_lost))
            } catch (_: Exception) {
                SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_create_file_failed))
            } finally {
                try { remoteFile?.close() } catch (_: Exception) {}
            }
        }

    suspend fun delete(sessionId: String, path: String): SshResult<Unit> =
        withContext(Dispatchers.IO) {
            val sftp = sftpClients[sessionId]
                ?: return@withContext SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_client_not_ready))
            val safePath = sanitizePath(path)
                ?: return@withContext SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_invalid_path))
            return@withContext try {
                sftp.rm(safePath)
                SshResult.Success(Unit)
            } catch (e: net.schmizz.sshj.sftp.SFTPException) {
                SshResult.Error(SshErrorCode.UNKNOWN, sftpErrorMessage(e, getString(Res.string.sftp_error_delete_failed)))
            } catch (_: java.io.IOException) {
                SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_connection_lost))
            }
        }

    suspend fun deleteRecursive(sessionId: String, path: String): SshResult<Unit> =
        withContext(Dispatchers.IO) {
            val sftp = sftpClients[sessionId]
                ?: return@withContext SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_client_not_ready))
            val safePath = sanitizePath(path)
                ?: return@withContext SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_invalid_path))
            if (safePath == "/") {
                return@withContext SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_cannot_delete_root))
            }
            return@withContext try {
                deleteRecursiveInternal(sftp, safePath)
                SshResult.Success(Unit)
            } catch (e: net.schmizz.sshj.sftp.SFTPException) {
                SshResult.Error(
                    SshErrorCode.UNKNOWN,
                    sftpErrorMessage(e, getString(Res.string.sftp_error_delete_recursive_failed)),
                )
            } catch (_: java.io.IOException) {
                SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_connection_lost))
            }
        }

    private fun deleteRecursiveInternal(sftp: SFTPClient, path: String) {
        for (entry in sftp.ls(path)) {
            if (entry.name == "." || entry.name == "..") continue
            val isSymlink = entry.attributes.type == FileMode.Type.SYMLINK
            when {
                isSymlink -> sftp.rm(entry.path)
                // The recursive call removes the subfolder itself: a second rmdir
                // would fail on a real server and stop the deletion half way.
                entry.isDirectory -> deleteRecursiveInternal(sftp, entry.path)
                else -> sftp.rm(entry.path)
            }
        }
        sftp.rmdir(path)
    }

    /**
     * Renames the entry at [oldPath] to [newName], inside the same folder.
     *
     * [newName] is a bare name: it is refused when it is not one ([isValidEntryName]), never
     * corrected (`a/b` does not become `ab`) and never resolved (`..` does not designate the
     * parent). It is joined to the folder of the entry, which alone goes through [sanitizePath].
     *
     * The new name is looked up first: the `SSH_FXP_RENAME` of an SFTP v3 server refuses an
     * existing target with a plain `FAILURE` that tells nothing, and a non-conforming server would
     * replace it. The lookup does not follow symbolic links, so a broken link sitting under the new
     * name counts as taken, and a lookup the server refuses for any reason other than "nothing
     * there" blocks the rename (a channel lost during the lookup, see [isChannelFailure], gives the
     * message of a lost connection). Renaming to the current name succeeds without a request. A change of
     * case only (`Rapport.docx` to `rapport.docx`) is not looked up: a case-insensitive server would
     * find the entry itself there, so the server decides. The root has no name to change.
     */
    suspend fun rename(sessionId: String, oldPath: String, newName: String): SshResult<Unit> =
        withContext(Dispatchers.IO) {
            val sftp = sftpClients[sessionId]
                ?: return@withContext SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_client_not_ready))
            if (!isValidEntryName(newName)) {
                return@withContext SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_entry_name_invalid))
            }
            val safeOldPath = sanitizePath(oldPath)
                ?: return@withContext SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_invalid_path))
            // The root has no name to change, and no parent to put a new one in.
            if (safeOldPath == "/") {
                return@withContext SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_invalid_path))
            }
            // Joined, not resolved: a bare name keeps the new path in the parent folder.
            val parentDir = safeOldPath.substringBeforeLast('/', "/").ifEmpty { "/" }
            val newPath = "${parentDir.trimEnd('/')}/$newName"
            // Same name: nothing to ask the server, which would answer that the target exists (itself).
            if (newPath == safeOldPath) return@withContext SshResult.Success(Unit)
            // Case only: a case-insensitive server would find the entry itself. The server decides.
            val caseChangeOnly = newPath.equals(safeOldPath, ignoreCase = true)
            return@withContext try {
                if (!caseChangeOnly && lstatTarget(sftp, newPath) != null) {
                    return@withContext SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_target_exists))
                }
                sftp.rename(safeOldPath, newPath)
                SshResult.Success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: net.schmizz.sshj.sftp.SFTPException) {
                SshResult.Error(SshErrorCode.UNKNOWN, sftpErrorMessage(e, getString(Res.string.sftp_error_rename_failed)))
            } catch (_: java.io.IOException) {
                SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_connection_lost))
            } catch (_: Exception) {
                SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_rename_failed))
            }
        }

    /**
     * What sits at [path], a symbolic link included (lstat does not follow it), or null when nothing is there.
     *
     * `statExistence` follows links, so a broken link in the place of a new name would look free and a
     * server that replaces on rename would replace it. Only NO_SUCH_FILE and NO_SUCH_PATH mean "free":
     * any other answer (a refused permission, for example) proves nothing about the name and is
     * rethrown for the caller. A lost channel or a silent server (see [isChannelFailure]) is no answer
     * at all: it is rethrown as the [IOException] of a lost connection, which the caller reports as such.
     */
    private fun lstatTarget(sftp: SFTPClient, path: String): FileAttributes? = try {
        sftp.lstat(path)
    } catch (e: net.schmizz.sshj.sftp.SFTPException) {
        when {
            e.statusCode == Response.StatusCode.NO_SUCH_FILE || e.statusCode == Response.StatusCode.NO_SUCH_PATH -> null
            isChannelFailure(e) -> throw IOException("SFTP channel lost during the lookup", e)
            else -> throw e
        }
    }

    suspend fun chmod(sessionId: String, path: String, permissions: Int): SshResult<Unit> =
        withContext(Dispatchers.IO) {
            if (permissions !in 0..511) {
                return@withContext SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_invalid_permissions))
            }
            val sftp = sftpClients[sessionId]
                ?: return@withContext SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_client_not_ready))
            val safePath = sanitizePath(path)
                ?: return@withContext SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_invalid_path))
            return@withContext try {
                sftp.chmod(safePath, permissions)
                SshResult.Success(Unit)
            } catch (e: net.schmizz.sshj.sftp.SFTPException) {
                SshResult.Error(
                    SshErrorCode.UNKNOWN,
                    sftpErrorMessage(e, getString(Res.string.sftp_error_chmod_failed)),
                )
            } catch (_: java.io.IOException) {
                SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_connection_lost))
            }
        }

    // ── Transfers (Wave 2.4 target, stubs with identical API to Android) ─────

    suspend fun readPreview(sessionId: String, path: String, maxBytes: Long): SshResult<ByteArray> =
        withContext(Dispatchers.IO) {
            val sftp = sftpClients[sessionId]
                ?: return@withContext SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_client_not_ready))
            val safePath = sanitizePath(path)
                ?: return@withContext SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_invalid_path))
            var remoteFile: net.schmizz.sshj.sftp.RemoteFile? = null
            return@withContext try {
                remoteFile = sftp.open(safePath)
                val size = remoteFile.length()
                if (size > maxBytes) {
                    return@withContext SshResult.Error(
                        SshErrorCode.UNKNOWN,
                        getString(Res.string.sftp_error_preview_too_large, size),
                    )
                }
                val buffer = ByteArray(size.toInt())
                remoteFile.read(0, buffer, 0, buffer.size)
                SshResult.Success(buffer)
            } catch (e: net.schmizz.sshj.sftp.SFTPException) {
                SshResult.Error(SshErrorCode.UNKNOWN, sftpErrorMessage(e, getString(Res.string.sftp_error_read_failed)))
            } catch (_: java.io.IOException) {
                SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_connection_lost))
            } catch (_: Exception) {
                SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_read_generic))
            } finally {
                try { remoteFile?.close() } catch (_: Exception) {}
            }
        }

    suspend fun readFile(
        sessionId: String,
        remotePath: String,
        outputStream: OutputStream,
        onProgress: (bytesTransferred: Long, totalBytes: Long) -> Unit,
    ): SshResult<Unit> = withContext(Dispatchers.IO) {
        val sftp = sftpClients[sessionId]
            ?: return@withContext SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_client_not_ready))
        val safePath = sanitizePath(remotePath)
            ?: return@withContext SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_invalid_path))
        // A failure of the local stream (disk full, drive removed) must not be told apart
        // from a lost connection by its type alone: both are IOExceptions. The wrapper
        // marks the local ones.
        val localOutput = LocalSink(outputStream)
        var remoteFile: net.schmizz.sshj.sftp.RemoteFile? = null
        return@withContext try {
            remoteFile = sftp.open(safePath)
            val size = remoteFile.length()
            var offset = 0L
            val buffer = ByteArray(32768)
            while (offset < size) {
                // Cooperative cancellation: SSHJ's read() is blocking native IO,
                // so a raw coroutine cancel can't interrupt it mid-chunk. Checking
                // between chunks lets a user-triggered Cancel propagate within
                // ~32 KB of transfer.
                coroutineContext.ensureActive()
                val toRead = minOf(buffer.size.toLong(), size - offset).toInt()
                val read = remoteFile.read(offset, buffer, 0, toRead)
                if (read <= 0) break
                localOutput.write(buffer, 0, read)
                offset += read
                onProgress(offset, size)
            }
            SshResult.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: net.schmizz.sshj.sftp.SFTPException) {
            SshResult.Error(SshErrorCode.UNKNOWN, sftpErrorMessage(e, getString(Res.string.sftp_error_download_failed)))
        } catch (_: LocalIoException) {
            SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_local_write_failed))
        } catch (_: java.io.IOException) {
            SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_connection_lost))
        } catch (_: Exception) {
            SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_download_generic))
        } finally {
            try { remoteFile?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Writes [inputStream] to [remotePath], reporting progress through [onProgress].
     *
     * [replaceExisting] has no default on purpose: a caller must decide, so that nothing
     * is ever replaced by omission. It must be true only once the user confirmed.
     *
     * Without it the file is opened with `EXCL`: the server itself refuses a name that is
     * already taken (atomically, so an entry created after the caller's own check is left
     * intact) and `TRUNC` is never sent. A refused `EXCL` open is reported as "already
     * exists" rather than as a generic upload failure. OpenSSH on SFTP v3 has no status for
     * it and answers a plain `FAILURE` ("Failure"), so on that status (or an unknown one) a
     * stat confirms that the name really is taken. That check only follows a failure of the
     * open itself, before any byte was written: a failure during the copy is never
     * second-guessed. If the copy then fails or is cancelled, the incomplete file that this
     * very call created is deleted, best effort (see [writeDirect]).
     *
     * With it, the content of an existing file is replaced without a moment where the old
     * one is half gone. The data goes to a hidden temporary file next to the target
     * (`.name.nextsh-part-<id>`, created with `EXCL` in 0600), which takes the place of the
     * target by `posix-rename@openssh.com` once it is complete and closed. That rename
     * replaces atomically, so until it happens the old file is untouched: a refused write, a
     * full disk, a lost connection or a cancellation leave it as it was. The temporary file
     * is deleted, best effort, when the rename did not happen, except after a lost
     * connection (a delete or a close would only wait for the timeout).
     * Every decision about the route is taken BEFORE the first byte is sent, since the source
     * stream cannot be rewound. The target is read once more just before the rename: if it
     * is no longer the file that was judged (type, owner, group, size, modification time,
     * permissions), because somebody else replaced it during the copy, nothing is renamed and
     * the upload says that the target changed.
     *
     * A symbolic link to a file is followed (`REALPATH` asked of the server): the file it
     * points to is replaced and the link stays, as a write through the link would do. Only a
     * link of the connected user that leads DIRECTLY to the file is followed (see
     * [linkLeadsDirectlyTo]): the server answers `REALPATH` in user space, without the checks that
     * the kernel makes when a file is opened (such as `fs.protected_symlinks`, for a link laid by
     * somebody else in a sticky folder open to all). Any other link is written through in place:
     * that is the behavior of 1.1.0 and nothing more, it promises nothing about what the kernel
     * checks on each hop of a chain of links or on each link of a folder. Limit: a `readlink` that
     * SSHJ fails without a status (a server older than SFTP v3) cannot be told from a lost channel,
     * and is told as a lost connection instead of being written in place.
     *
     * That route is taken only when it changes nothing but the content. Otherwise the file
     * is written in place (`TRUNC`, the file is emptied only after the first block of the source
     * was read), as it was before this route existed, and the reason is logged:
     * - the server does not announce `posix-rename@openssh.com`;
     * - the server does not state the permissions or the owner of the file, or of the new one
     *   (Windows servers, mostly): they could not be kept;
     * - the file is write-protected for its owner (the open then decides, root passes);
     * - the folder cannot be written (the temporary file is refused);
     * - the owner or the group of the file cannot be given to the new one;
     * - the target is reached through a link that the server does not resolve, that is not
     *   the user's own, whose owner is not stated, that does not lead directly to the file
     *   (a chain of links, a link of a folder on the path, a `readlink` that fails), or by a
     *   server without `lstat`.
     * A target that is no longer a file (a folder, a socket...) or a link that no longer
     * leads to one is refused with a message, and nothing is opened.
     *
     * Known limits, on purpose, since SFTP v3 gives no way to see or fix them: the new file
     * is a new inode, so a hard link to the old one is broken and its ACLs, extended
     * attributes and SELinux context are not carried over. Only the permission bits are
     * copied (never setuid, setgid nor sticky); if that fails the file stays in 0600,
     * stricter. That is not "never more open than the old file" when the folder has a default
     * ACL or a SELinux context to inherit: the temporary file receives them, the final
     * permissions then reopen the mask of the ACL, and a user named in the default ACL gets an
     * access that the old file did not give (the same goes for the inherited context). A
     * file that is mounted on its own (a bind mount of one
     * file) cannot be renamed over: such an upload fails and the file is left intact. Both
     * versions coexist until the rename, so a nearly full disk or a tight quota can refuse
     * what an in-place write would have accepted. A process killed during the transfer
     * leaves its hidden `.nextsh-part-` file on the server, incomplete: nothing deletes it
     * on its own, since it could be the file of a transfer under way from another device.
     */
    suspend fun writeFile(
        sessionId: String,
        remotePath: String,
        inputStream: InputStream,
        size: Long,
        replaceExisting: Boolean,
        onProgress: (bytesTransferred: Long, totalBytes: Long) -> Unit,
    ): SshResult<Unit> = withContext(Dispatchers.IO) {
        val sftp = sftpClients[sessionId]
            ?: return@withContext SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_client_not_ready))
        val safePath = sanitizePath(remotePath)
            ?: return@withContext SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_invalid_path))
        // Same marking of the local side as in readFile.
        val localInput = LocalSource(inputStream)
        return@withContext if (replaceExisting) {
            replaceFile(sessionId, sftp, safePath, localInput, size, onProgress)
        } else {
            writeDirect(sessionId, sftp, safePath, exclusive = true, source = localInput, size = size, onProgress = onProgress)
        }
    }

    /**
     * Writes [source] to [safePath] with a single open: `EXCL` when [exclusive] (the name
     * must be free, the server refuses it otherwise), `TRUNC` otherwise (the file is
     * written in place, which is only meant to follow an explicit confirmation of the user).
     *
     * With [exclusive], the open is what created the file, so a copy that fails or is
     * cancelled leaves a file that this very call made and that is incomplete under its real
     * name: it is deleted, best effort, so that nothing that looks finished is left behind.
     * Not after a lost connection (the delete would only wait for the timeout, and could not
     * be answered), and never in place: that file was there before, it is not ours to delete.
     * The delete goes by a NAME, and another writer may have put a file of his own under that
     * name since (a rename replaces it): what the handle shows (type, size, modification time,
     * owner, group, permissions) is read before it is closed, and the file is deleted only if the name still shows
     * the same (see [removePartialIfOurs]).
     *
     * The open is a point of no return for an in-place write (`TRUNC` empties the file at once),
     * so the first block of the source is read BEFORE it and the cancellation is looked at just
     * before it: a source that cannot be read, or a job cancelled while the target was being
     * examined, while a temporary file was being removed or while that block was read, leaves the
     * old file intact. Once opened, that block is written at once, with no cancellation point in
     * between. What remains: a cancellation during the open request itself, a lost channel or a
     * refused first write.
     */
    private suspend fun writeDirect(
        sessionId: String,
        sftp: SFTPClient,
        safePath: String,
        exclusive: Boolean,
        source: InputStream,
        size: Long,
        onProgress: (bytesTransferred: Long, totalBytes: Long) -> Unit,
    ): SshResult<Unit> {
        val modes = if (exclusive) {
            setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL)
        } else {
            setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)
        }
        coroutineContext.ensureActive()
        var remoteFile: RemoteFile? = null
        var failure: Exception? = null
        try {
            if (exclusive) {
                remoteFile = sftp.open(safePath, modes)
                copyChunks(source, remoteFile, size, onProgress)
            } else {
                // In place: the first block is read BEFORE the open, which empties the file. A source
                // that cannot be read (a LocalIoException, caught below with nothing opened) or a job
                // cancelled during that read leaves the old file untouched.
                val head = ByteArray(COPY_BLOCK_SIZE)
                val headLength = source.read(head)
                coroutineContext.ensureActive()
                remoteFile = sftp.open(safePath, modes)
                // No cancellation point between the open and this write: the file is already empty,
                // so the block that was read goes in. An empty source writes nothing.
                if (headLength > 0) {
                    remoteFile.write(0L, head, 0, headLength)
                    onProgress(headLength.toLong(), size)
                    copyChunks(source, remoteFile, size, onProgress, startOffset = headLength.toLong())
                }
            }
            return SshResult.Success(Unit)
        } catch (e: CancellationException) {
            failure = e
            throw e
        } catch (e: Exception) {
            failure = e
            // Only an open that failed (remoteFile still null) can be a refused EXCL.
            val nameTaken = exclusive && remoteFile == null && e is SFTPException &&
                refusalMeansNameTaken(sftp, safePath, e)
            return uploadError(e, nameTaken)
        } finally {
            // After a lost channel nothing is sent: a close or a delete would wait for the timeout, again.
            val cleanUp = exclusive && remoteFile != null && failure != null && !channelLost(failure)
            // Read while the handle is open: what the file is, to tell it from another one later.
            // That call can itself show that the channel is lost: nothing more is sent then.
            var readFailure: Exception? = null
            val created = if (cleanUp) {
                try {
                    remoteFile?.fetchAttributes()
                } catch (e: Exception) {
                    readFailure = e
                    null
                }
            } else {
                null
            }
            val lost = channelLost(failure) || channelLost(readFailure)
            if (!lost) {
                try { remoteFile?.close() } catch (_: Exception) {}
            }
            if (cleanUp && !lost) removePartialIfOurs(sessionId, sftp, safePath, created)
        }
    }

    /**
     * Deletes the incomplete file at [path] if it is still the one that was just created, whose
     * attributes were [created] while its handle was open: what the handle showed (type, size, modification
     * time, owner, group, permissions) must still be what the name shows, and not a link (see [isSameFile]). Otherwise another writer
     * replaced it since, and it is left alone. A name that cannot be checked is left alone too: a
     * leftover costs less than a file that is not ours.
     */
    private fun removePartialIfOurs(sessionId: String, sftp: SFTPClient, path: String, created: FileAttributes?) {
        val now = if (created == null) null else try { lstatExistence(sftp, path) } catch (_: Exception) { null }
        if (created == null || now == null || !isSameFile(created, now)) {
            Logger.w(TAG, "upload ${LogId.of(sessionId)}: unfinished file kept, it could not be confirmed as the one just created")
            return
        }
        removeQuietly(sessionId, sftp, path)
    }

    /**
     * Whether [now], read by name, is the file whose handle gave [created], as far as the server tells: the same
     * kind (a type the server does not state counts as a regular file), neither of them a link, and the same size,
     * modification time, owner, group and permission bits (setuid, setgid and sticky included). An attribute that
     * neither states is not compared, one that only one of them states is a difference. The size, the
     * modification time and the owner must be stated by both: without them nothing tells the file from another
     * one. Same rule as Android (`SftpManager.sameEntry`, strict). Not [isSameTarget], which serves the replacement.
     */
    private fun isSameFile(created: FileAttributes, now: FileAttributes): Boolean {
        fun kindOf(attributes: FileAttributes) =
            if (attributes.type == FileMode.Type.UNKNOWN) FileMode.Type.REGULAR else attributes.type
        fun same(flag: FileAttributes.Flag, equal: () -> Boolean): Boolean =
            created.has(flag) == now.has(flag) && (!created.has(flag) || equal())
        val stated = listOf(FileAttributes.Flag.SIZE, FileAttributes.Flag.ACMODTIME, FileAttributes.Flag.UIDGID)
            .all { created.has(it) && now.has(it) }
        val link = created.type == FileMode.Type.SYMLINK || now.type == FileMode.Type.SYMLINK
        return stated && !link && kindOf(created) == kindOf(now) &&
            same(FileAttributes.Flag.SIZE) { created.size == now.size } &&
            same(FileAttributes.Flag.ACMODTIME) { created.mtime == now.mtime } &&
            same(FileAttributes.Flag.UIDGID) { created.uid == now.uid && created.gid == now.gid } &&
            same(FileAttributes.Flag.MODE) { created.mode.permissionsMask == now.mode.permissionsMask }
    }

    /**
     * Copies [source] to [remoteFile] by blocks, reporting progress, with a cancellation point between two blocks.
     * [startOffset] is where the first block lands, for a copy that goes on after a block already written.
     */
    private suspend fun copyChunks(
        source: InputStream,
        remoteFile: RemoteFile,
        size: Long,
        onProgress: (bytesTransferred: Long, totalBytes: Long) -> Unit,
        startOffset: Long = 0L,
    ) {
        var offset = startOffset
        val buffer = ByteArray(COPY_BLOCK_SIZE)
        while (true) {
            // Same cancellation point as readFile: between chunks.
            coroutineContext.ensureActive()
            val read = source.read(buffer)
            if (read <= 0) break
            remoteFile.write(offset, buffer, 0, read)
            offset += read
            onProgress(offset, size)
        }
    }

    /** The message of a failed upload that wrote straight to its name. [nameTaken]: the refusal of an `EXCL` open is about the name. */
    private suspend fun uploadError(e: Exception, nameTaken: Boolean): SshResult.Error {
        val message = when (e) {
            is SFTPException -> if (nameTaken) {
                getString(Res.string.sftp_error_target_exists)
            } else {
                sftpErrorMessage(e, getString(Res.string.sftp_error_upload_failed))
            }
            is LocalIoException -> getString(Res.string.sftp_error_local_read_failed)
            is IOException -> getString(Res.string.sftp_error_connection_lost)
            else -> getString(Res.string.sftp_error_upload_generic)
        }
        return SshResult.Error(SshErrorCode.UNKNOWN, message)
    }

    // ── Replacing a file ──────────────────────────────────────────────────────

    /** Why an upload that replaces a file is written in place. The code is what the log line says, fixed text. */
    private enum class InPlaceReason(val code: String) {
        NO_POSIX_RENAME("no-posix-rename"),
        READ_ONLY("read-only"),
        DIR_NOT_WRITABLE("dir-not-writable"),
        OWNER_NOT_KEPT("owner-not-kept"),

        /** The server does not state the permissions or the owner of the file (or of the new one): they could not be kept. */
        ATTRIBUTES_UNKNOWN("attributes-unknown"),

        /** The link is not followed here: the server does not resolve it, or it is not the user's own. */
        LINK_NOT_RESOLVED("link-not-resolved"),
    }

    /** What an upload that replaces a file does, decided before the first byte is sent. */
    private sealed interface ReplacePlan {
        /** Nothing is at the name any more: create it, `EXCL`. */
        object Free : ReplacePlan

        /** The target is not a file any more, or a link leads to no file: refuse, open nothing. */
        object Changed : ReplacePlan

        /** The temporary file route is impossible or would change more than the content. */
        class InPlace(val reason: InPlaceReason) : ReplacePlan

        /**
         * Write a temporary file and rename it over [target], a regular file whose attributes are [old]
         * (they state the permissions and the owner). [linkOwner] is the owner of the symbolic link that
         * led to [target], null when there was no link: the route is only valid if the user is that owner.
         */
        class Atomic(val target: String, val old: FileAttributes, val linkOwner: Int?) : ReplacePlan
    }

    /** How the temporary file route ended: with a result, or with the decision to write in place after all. */
    private sealed interface TempOutcome {
        class Done(val result: SshResult<Unit>) : TempOutcome
        class FallBack(val reason: InPlaceReason) : TempOutcome
    }

    /** The upload of a file the user agreed to replace: look at the target, then take the safest route that keeps what it must. */
    private suspend fun replaceFile(
        sessionId: String,
        sftp: SFTPClient,
        safePath: String,
        source: InputStream,
        size: Long,
        onProgress: (bytesTransferred: Long, totalBytes: Long) -> Unit,
    ): SshResult<Unit> {
        val plan = try {
            planReplacement(sftp, safePath)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Nothing was created yet.
            return replaceError(e, nothingCreated = true)
        }
        return when (plan) {
            ReplacePlan.Free ->
                writeDirect(sessionId, sftp, safePath, exclusive = true, source = source, size = size, onProgress = onProgress)
            ReplacePlan.Changed ->
                SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_upload_target_changed))
            is ReplacePlan.InPlace -> {
                logInPlace(sessionId, plan.reason)
                writeDirect(sessionId, sftp, safePath, exclusive = false, source = source, size = size, onProgress = onProgress)
            }
            is ReplacePlan.Atomic -> when (
                val outcome = replaceThroughTemp(sessionId, sftp, plan.target, plan.old, plan.linkOwner, source, size, onProgress)
            ) {
                is TempOutcome.Done -> outcome.result
                is TempOutcome.FallBack -> {
                    logInPlace(sessionId, outcome.reason)
                    writeDirect(sessionId, sftp, safePath, exclusive = false, source = source, size = size, onProgress = onProgress)
                }
            }
        }
    }

    /**
     * Looks at what is at [safePath] and decides how to replace it. Blocking SFTP calls, no byte is sent.
     *
     * - nothing there: [ReplacePlan.Free];
     * - a regular file (or one whose type the server does not state): the file itself;
     * - a symbolic link: the file it leads to, as the server resolves it (`REALPATH`), when that is a
     *   regular file; a link to nothing, or to something that is not a file, is [ReplacePlan.Changed];
     *   a server that does not resolve the link gives [ReplacePlan.InPlace]. So does a link that does
     *   not lead DIRECTLY to that file (a chain of links, a link of a folder on the path, a `readlink`
     *   that fails: see [linkLeadsDirectlyTo]). So does a link that is
     *   not the user's own, or whose owner the server does not state: `REALPATH` is answered in user
     *   space by the server and does not make the checks of the kernel (such as `fs.protected_symlinks`,
     *   for a link of somebody else in a sticky folder open to all, `/tmp`). An in-place write goes
     *   through the link in the kernel, which decides as it did before: that is the behavior of 1.1.0,
     *   not more. A `REALPATH` that is refused, other than as "no such file" or as a lost channel (a loop
     *   of links), is the same: the open decides. That the user is the owner is checked
     *   once the temporary file exists, see [replaceThroughTemp] (the owner of the user is not known
     *   before: a file created by the user is what tells);
     * - anything else (folder, FIFO, socket, device): [ReplacePlan.Changed].
     *
     * A server without `lstat` cannot tell a link from the file it leads to (`stat` follows it), and a
     * rename would replace the link itself: nothing there is still [ReplacePlan.Free], a file is
     * written in place, and a folder, a FIFO, a socket, a device, or a link that a stat should not
     * return (by what the stat shows) is [ReplacePlan.Changed].
     *
     * Then, for a file: no `posix-rename@openssh.com` on the server, permissions or owner that the
     * server does not state (they could not be kept), or an owner write bit that is absent (a
     * write-protected file must not be replaced by a rename, which ignores that bit), mean
     * [ReplacePlan.InPlace], where the open decides as it always did.
     */
    private fun planReplacement(sftp: SFTPClient, safePath: String): ReplacePlan {
        val looked = try {
            lstatExistence(sftp, safePath)
        } catch (e: SFTPException) {
            if (e.statusCode != Response.StatusCode.OP_UNSUPPORTED) throw e
            val followed = sftp.statExistence(safePath) ?: return ReplacePlan.Free
            // What the stat shows is the file a link leads to, or the entry itself. A folder, a FIFO, a socket
            // or a device is refused as everywhere: an in-place open of a FIFO that nobody reads would block
            // the server. So is a link, which a stat that follows links should never return (a faulty server):
            // nothing can be said of it. Only a file, or a type that is not stated, is written in place.
            return when (followed.type) {
                FileMode.Type.REGULAR, FileMode.Type.UNKNOWN -> ReplacePlan.InPlace(InPlaceReason.LINK_NOT_RESOLVED)
                else -> ReplacePlan.Changed
            }
        }
        val own = looked ?: return ReplacePlan.Free
        var linkOwner: Int? = null
        val (target, old) = when (own.type) {
            FileMode.Type.REGULAR, FileMode.Type.UNKNOWN -> safePath to own
            FileMode.Type.SYMLINK -> {
                if (!own.has(FileAttributes.Flag.UIDGID)) return ReplacePlan.InPlace(InPlaceReason.LINK_NOT_RESOLVED)
                linkOwner = own.uid
                val canonical = try {
                    sftp.canonicalize(safePath)
                } catch (e: SFTPException) {
                    if (e.statusCode == Response.StatusCode.NO_SUCH_FILE || e.statusCode == Response.StatusCode.NO_SUCH_PATH) {
                        return ReplacePlan.Changed
                    }
                    // A lost channel is a lost connection; any other refusal (a loop of links, ELOOP, a plain
                    // failure) is not an answer about the file: the open decides, in place, as it always did.
                    if (isChannelFailure(e)) throw e
                    return ReplacePlan.InPlace(InPlaceReason.LINK_NOT_RESOLVED)
                }
                val real = sanitizePath(canonical) ?: return ReplacePlan.InPlace(InPlaceReason.LINK_NOT_RESOLVED)
                val realAttributes = lstatExistence(sftp, real) ?: return ReplacePlan.Changed
                when (realAttributes.type) {
                    FileMode.Type.SYMLINK -> return ReplacePlan.InPlace(InPlaceReason.LINK_NOT_RESOLVED)
                    FileMode.Type.REGULAR, FileMode.Type.UNKNOWN ->
                        // Only the owner of the FIRST link is known: a chain, or a link of a folder on the path,
                        // would make the rename cross a link of somebody else with none of the kernel's checks.
                        if (linkLeadsDirectlyTo(sftp, safePath, real)) {
                            real to realAttributes
                        } else {
                            return ReplacePlan.InPlace(InPlaceReason.LINK_NOT_RESOLVED)
                        }
                    else -> return ReplacePlan.Changed
                }
            }
            else -> return ReplacePlan.Changed
        }
        if (!sftp.sftpEngine.supportsServerExtension(POSIX_RENAME_NAME, POSIX_RENAME_DOMAIN)) {
            return ReplacePlan.InPlace(InPlaceReason.NO_POSIX_RENAME)
        }
        // Without them the rename would give a file in 0600, or take a file of another owner: not better than before.
        if (!old.has(FileAttributes.Flag.MODE) || !old.has(FileAttributes.Flag.UIDGID)) {
            return ReplacePlan.InPlace(InPlaceReason.ATTRIBUTES_UNKNOWN)
        }
        if ((old.mode.permissionsMask and OWNER_WRITE_BIT) == 0) {
            return ReplacePlan.InPlace(InPlaceReason.READ_ONLY)
        }
        return ReplacePlan.Atomic(target, old, linkOwner)
    }

    /**
     * Whether the symbolic link at [linkPath] leads DIRECTLY to [real], the canonical path that the
     * server resolved for it. `REALPATH` resolves the whole chain, whereas only the owner of the first
     * link is checked: a link that leads to another link of somebody else (in a sticky folder open to all),
     * or a path that crosses a link of a folder, would be followed by the rename without any of the
     * checks that the kernel makes when a file is opened. Not following it is not a promise that the kernel
     * checks it in place: an in-place write is the behavior of 1.1.0, nothing more.
     *
     * So `readlink` is asked what the link says; when it is relative it is joined to the folder of the link
     * AS WRITTEN in [linkPath] (not canonicalized), when it is absolute it is taken as it is; then it is
     * normalized by [sanitizePath]. That must be EXACTLY [real]: with a link in the chain, or in a folder of
     * the path, the canonical path is another one. A `readlink` that is refused or not supported is false.
     * A lost channel is rethrown.
     *
     * A target that has a `..` segment (relative or absolute) is never followed: [sanitizePath] resolves
     * `..` by the text, whereas the server and the kernel resolve it on the disk, link by link. Through a link
     * of a folder, `/home/t/conf/../x` is `/home/t/x` for the text and another file for the disk, and the two
     * can be made to agree on the canonical path by a link laid by somebody else. A name that merely contains
     * two dots (`a..b`) is not a `..` segment and is accepted.
     *
     * Limit: SSHJ raises, for a `readlink` on a server older than SFTP v3 or for an answer it cannot read, an
     * [SFTPException] without a status ("READLINK is not supported in SFTPv...", "Unexpected data in NAME").
     * [isChannelFailure] reads it as a lost channel, so such a server is told as "connection lost" instead of
     * being written in place. It has the same shape as a real loss and cannot be told apart from it; servers
     * older than SFTP v3 are almost nonexistent.
     */
    private fun linkLeadsDirectlyTo(sftp: SFTPClient, linkPath: String, real: String): Boolean {
        val written = try {
            sftp.readlink(linkPath)
        } catch (e: SFTPException) {
            if (isChannelFailure(e)) throw e
            return false
        }
        if (written.split('/').any { it == ".." }) return false
        val joined = if (written.startsWith("/")) written else linkPath.substringBeforeLast('/', "") + "/" + written
        return sanitizePath(joined) == real
    }

    /**
     * Writes [source] to a temporary file next to [target], then renames it over [target]; [old] are
     * the attributes of [target] as they were read.
     *
     * 1. The temporary file is created with `EXCL` and 0600 in one call: nobody else can read the
     *    part of the data that is there, and a name already taken (or a link laid in advance) is
     *    refused instead of followed. A refusal of permission means the folder cannot be written:
     *    [TempOutcome.FallBack]. A taken name gets a new random suffix, [TEMP_CREATE_ATTEMPTS] times.
     * 2. The owner of the new file is read (`FSTAT` of the handle). It is the user, who created it:
     *    - if [linkOwner] is set ([target] was reached through a symbolic link), it must be that user.
     *      Otherwise the link is not the user's own (it may be laid by somebody else in a sticky
     *      folder, towards a file of the user), and the upload falls back to an in-place write
     *      through the link, as in 1.1.0;
     *    - if it does not belong to the owner and group of the old file, they are given back
     *      (`FSETSTAT`). When the server refuses (a user cannot give a file away), or does not state
     *      an owner, the empty temporary file is deleted and the upload falls back to an in-place
     *      write, which keeps the owner.
     * 3. The data is copied, with a cancellation point between two blocks.
     * 4. The permission bits of the old file (0777 at most) are set, best effort: a refusal is
     *    logged and the file stays in 0600.
     * 5. The temporary file is closed, and a failure to do so fails the upload (a full disk or a
     *    quota may only be reported there). Then [target] is read again: if it is no longer the file
     *    that was judged (see [isSameTarget]), nothing is renamed and the upload says that the target
     *    changed. Otherwise the temporary file is renamed over [target].
     *
     * Whatever happens before the rename, the old file is not touched. The temporary file is deleted
     * if the rename did not happen, unless the channel is lost (see [channelLost]). [old] states its
     * permissions and owner (see [planReplacement]).
     */
    private suspend fun replaceThroughTemp(
        sessionId: String,
        sftp: SFTPClient,
        target: String,
        old: FileAttributes,
        linkOwner: Int?,
        source: InputStream,
        size: Long,
        onProgress: (bytesTransferred: Long, totalBytes: Long) -> Unit,
    ): TempOutcome {
        var temp: String? = null
        var remoteFile: RemoteFile? = null
        var renamed = false
        var failure: Exception? = null
        try {
            coroutineContext.ensureActive()
            val parent = target.substringBeforeLast('/', "")
            val name = target.substringAfterLast('/')
            var attempts = 0
            while (remoteFile == null) {
                attempts++
                val candidate = uploadTempPath(parent, name, UUID.randomUUID().toString().take(TEMP_ID_CHARS))
                try {
                    remoteFile = sftp.open(
                        candidate,
                        setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL),
                        FileAttributes.Builder().withPermissions(TEMP_FILE_MODE).build(),
                    )
                    temp = candidate
                    liveUploadTempNames.add(candidate.substringAfterLast('/'))
                } catch (e: SFTPException) {
                    if (isChannelFailure(e)) throw e
                    if (isPermissionRefusal(e)) return TempOutcome.FallBack(InPlaceReason.DIR_NOT_WRITABLE)
                    val taken = (e.statusCode == Response.StatusCode.FAILURE || e.statusCode == Response.StatusCode.FILE_ALREADY_EXISTS) &&
                        entryExists(sftp, candidate)
                    if (!taken) throw e
                    if (attempts >= TEMP_CREATE_ATTEMPTS) {
                        return TempOutcome.Done(SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_replace_failed_kept)))
                    }
                }
            }
            val file = checkNotNull(remoteFile)
            val tempPath = checkNotNull(temp)

            try {
                val created = file.fetchAttributes()
                // The owner of the new file is the user. Without it nothing can be checked, nor kept.
                if (!created.has(FileAttributes.Flag.UIDGID)) return TempOutcome.FallBack(InPlaceReason.ATTRIBUTES_UNKNOWN)
                // A link of somebody else is not followed here: it is written through in place, as in 1.1.0.
                if (linkOwner != null && created.uid != linkOwner) return TempOutcome.FallBack(InPlaceReason.LINK_NOT_RESOLVED)
                if (created.uid != old.uid || created.gid != old.gid) {
                    file.setAttributes(FileAttributes.Builder().withUIDGID(old.uid, old.gid).build())
                }
            } catch (e: SFTPException) {
                if (isChannelFailure(e)) throw e
                // The owner cannot be read or given back: keep it by writing in place.
                return TempOutcome.FallBack(InPlaceReason.OWNER_NOT_KEPT)
            }

            copyChunks(source, file, size, onProgress)

            try {
                file.setAttributes(
                    FileAttributes.Builder().withPermissions(old.mode.permissionsMask and PLAIN_PERMISSION_BITS).build(),
                )
            } catch (e: SFTPException) {
                if (isChannelFailure(e)) throw e
                Logger.w(TAG, "upload ${LogId.of(sessionId)}: permissions of the replaced file not kept (${errorClass(e)})")
            }

            // Closed on purpose out of the finally below: a failure here fails the upload.
            remoteFile = null
            file.close()
            coroutineContext.ensureActive()

            // The target was judged (type, owner, protection) before the whole copy. Someone else may have
            // replaced it since: a rename would overwrite what an in-place write could not have changed.
            val now = lstatExistence(sftp, target)
            if (now == null || !isSameTarget(old, now)) {
                return TempOutcome.Done(
                    SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_upload_target_changed)),
                )
            }

            try {
                sftp.rename(tempPath, target, EnumSet.of(RenameFlags.OVERWRITE))
            } catch (e: SFTPException) {
                if (isChannelFailure(e)) throw e
                return TempOutcome.Done(renameRefusal(sftp, e, target))
            }
            renamed = true
            return TempOutcome.Done(SshResult.Success(Unit))
        } catch (e: CancellationException) {
            failure = e
            throw e
        } catch (e: Exception) {
            failure = e
            return TempOutcome.Done(replaceError(e, nothingCreated = temp == null))
        } finally {
            // After a lost channel nothing is sent: a close would wait for the timeout, again.
            val lost = channelLost(failure)
            if (!lost) {
                try { remoteFile?.close() } catch (_: Exception) {}
            }
            val leftover = temp
            if (leftover != null) {
                val removed = !renamed && !lost && removeQuietly(sessionId, sftp, leftover)
                // Gone for good (renamed over the target, or deleted): remembered, so that a listing taken
                // meanwhile that still shows it does not take it for a leftover. One that stays (lost
                // channel, rm refused) is a real leftover and is NOT remembered. Remembered BEFORE it
                // stops being under way, so that no read of the two sets can miss it.
                val name = leftover.substringAfterLast('/')
                if (renamed || removed) rememberGone(name)
                liveUploadTempNames.remove(name)
            }
        }
    }

    /**
     * The NAMES (last segment) of the temporary files that the replacements under way are writing to,
     * whatever the session or the folder. A name, not a path: a replacement that follows a link writes
     * next to the real target, which a listing reached through a folder link shows under another path.
     * The random identifier of 8 hexadecimal digits makes the name unique. Added once the file is
     * created, removed when the replacement ends.
     */
    private val liveUploadTempNames: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * The names of the last [GONE_TEMPS_KEPT] temporary files that are known gone (renamed over their
     * target, or deleted), least recently added first. A listing that was under way when one disappeared
     * may still show it. Guarded by `synchronized(goneUploadTempNames)`.
     */
    private val goneUploadTempNames = object : LinkedHashMap<String, Unit>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?): Boolean = size > GONE_TEMPS_KEPT
    }

    private fun rememberGone(name: String) {
        synchronized(goneUploadTempNames) { goneUploadTempNames[name] = Unit }
    }

    /**
     * A copy of the names of the temporary files of this app that are under way or known gone. The
     * browser reads it before a listing and after it and sets aside only the entries whose NAME is in
     * the union: a temporary file created after the first read and finished before the second is in the
     * second. A temporary file left by a lost channel or a failed `rm` is never in it: it is a leftover.
     */
    fun knownUploadTempNames(): Set<String> {
        val gone = synchronized(goneUploadTempNames) { goneUploadTempNames.keys.toList() }
        return HashSet(liveUploadTempNames).also { it.addAll(gone) }
    }

    /** Whether the temporary file named [name] belongs to an upload that is under way right now. */
    fun isUploadTempUnderWay(name: String): Boolean = name in liveUploadTempNames

    /**
     * Whether [now], the target read just before the rename, is still the file that was judged when
     * [old] was read: the same type, owner, group, size, modification time and permission bits. An
     * attribute that neither states is not compared; one that only one of them states is a difference.
     */
    private fun isSameTarget(old: FileAttributes, now: FileAttributes): Boolean {
        fun same(flag: FileAttributes.Flag, equal: () -> Boolean): Boolean =
            old.has(flag) == now.has(flag) && (!old.has(flag) || equal())
        return old.type == now.type &&
            same(FileAttributes.Flag.UIDGID) { old.uid == now.uid && old.gid == now.gid } &&
            same(FileAttributes.Flag.SIZE) { old.size == now.size } &&
            same(FileAttributes.Flag.ACMODTIME) { old.mtime == now.mtime } &&
            same(FileAttributes.Flag.MODE) { old.mode.permissionsMask == now.mode.permissionsMask }
    }

    /** The message when the rename over [target] was refused by the server (not a lost channel). */
    private suspend fun renameRefusal(sftp: SFTPClient, e: SFTPException, target: String): SshResult.Error {
        if (isPermissionRefusal(e)) {
            return SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_permission_denied))
        }
        // A target that became a folder (or another entry that is not a file) since the plan.
        val now = try {
            lstatExistence(sftp, target)
        } catch (_: Exception) {
            null
        }
        val changed = now != null && when (now.type) {
            FileMode.Type.REGULAR, FileMode.Type.UNKNOWN, FileMode.Type.SYMLINK -> false
            else -> true
        }
        val message = if (changed) {
            getString(Res.string.sftp_error_upload_target_changed)
        } else {
            getString(Res.string.sftp_error_replace_failed_kept)
        }
        return SshResult.Error(SshErrorCode.UNKNOWN, message)
    }

    /**
     * The message of a replacement that failed. A lost channel and a refused permission keep the messages
     * they have everywhere; any other refusal of the server says that the existing file was not changed,
     * unless [nothingCreated] (the failure came before the temporary file existed, the usual translation
     * of a refused open applies).
     */
    private suspend fun replaceError(e: Exception, nothingCreated: Boolean): SshResult.Error {
        val message = when {
            e is SFTPException && isChannelFailure(e) -> getString(Res.string.sftp_error_connection_lost)
            e is SFTPException && isPermissionRefusal(e) -> getString(Res.string.sftp_error_permission_denied)
            e is SFTPException && nothingCreated -> sftpErrorMessage(e, getString(Res.string.sftp_error_upload_failed))
            e is SFTPException -> getString(Res.string.sftp_error_replace_failed_kept)
            e is LocalIoException -> getString(Res.string.sftp_error_local_read_failed)
            e is IOException -> getString(Res.string.sftp_error_connection_lost)
            else -> getString(Res.string.sftp_error_upload_generic)
        }
        return SshResult.Error(SshErrorCode.UNKNOWN, message)
    }

    /** The reason is a fixed code: never the path, the name of the file nor any text of the server. */
    private fun logInPlace(sessionId: String, reason: InPlaceReason) {
        Logger.w(TAG, "upload ${LogId.of(sessionId)}: written in place, reason ${reason.code}")
    }

    /** Deletes [path], best effort: a failure is logged by class and swallowed, the error of the upload stays the one reported. */
    private fun removeQuietly(sessionId: String, sftp: SFTPClient, path: String): Boolean =
        try {
            sftp.rm(path)
            true
        } catch (e: Exception) {
            Logger.w(TAG, "upload ${LogId.of(sessionId)}: unfinished file not removed (${errorClass(e)})")
            false
        }

    /**
     * Whether [e] is a failure of the channel and not an answer of the server. SSHJ raises a channel
     * that is lost as an [SFTPException] with the status UNKNOWN, and a server that stays silent as one
     * that carries the timeout as its cause. A status that the server sent (FAILURE, PERMISSION_DENIED,
     * NO_SUCH_FILE...) is an answer and has no cause.
     */
    private fun isChannelFailure(e: SFTPException): Boolean =
        e.statusCode == Response.StatusCode.UNKNOWN || e.cause != null

    /** Whether [failure] is a lost channel: a call on it would only wait for the timeout, so none is made. */
    private fun channelLost(failure: Throwable?): Boolean = failure is SFTPException && isChannelFailure(failure)

    /** Whether the server refused for lack of permission: by its status, or, on SFTP v3, by the text it puts on a plain failure. */
    private fun isPermissionRefusal(e: SFTPException): Boolean =
        e.statusCode == Response.StatusCode.PERMISSION_DENIED ||
            e.message?.contains("permission", ignoreCase = true) == true

    /**
     * The attributes of [path] without following a link, or null when nothing is there. A server that
     * does not support `lstat` (OP_UNSUPPORTED) is an error here, not a fallback on `stat`: that one
     * follows links, and a caller that believes it looks at the entry itself would take a link for the
     * file it leads to (see [planReplacement], which handles that case).
     */
    private fun lstatExistence(sftp: SFTPClient, path: String): FileAttributes? = try {
        sftp.lstat(path)
    } catch (e: SFTPException) {
        when (e.statusCode) {
            Response.StatusCode.NO_SUCH_FILE, Response.StatusCode.NO_SUCH_PATH -> null
            else -> throw e
        }
    }

    /** Whether something is at [path]. A lookup that fails counts as nothing there. */
    private fun entryExists(sftp: SFTPClient, path: String): Boolean = try {
        lstatExistence(sftp, path) != null
    } catch (_: Exception) {
        false
    }

    suspend fun stat(sessionId: String, path: String): SshResult<SftpFile> =
        withContext(Dispatchers.IO) {
            val sftp = sftpClients[sessionId]
                ?: return@withContext SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_client_not_ready))
            val safePath = sanitizePath(path)
                ?: return@withContext SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_invalid_path))
            return@withContext try {
                val attrs = sftp.stat(safePath)
                val name = safePath.substringAfterLast('/').ifEmpty { "/" }
                SshResult.Success(
                    SftpFile(
                        name = name,
                        path = safePath,
                        size = attrs.size,
                        permissions = filePermissionsToInt(attrs.permissions),
                        isDirectory = attrs.type == FileMode.Type.DIRECTORY,
                        modifiedAt = attrs.mtime * 1000L,
                    ),
                )
            } catch (_: Exception) {
                SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_stat_failed))
            }
        }

    /**
     * Tells what [path] designates, so that a caller can decide before it writes there.
     *
     * Unlike [stat], which folds every failure into one message and so cannot tell an
     * absent entry from a failed call, only a server answer "no such file" gives
     * [RemoteEntryKind.ABSENT]. Any other failure (permission, lost connection...) is an
     * error, never an "absent": a wrong "absent" would let a replacement go unannounced.
     *
     * The stat follows symbolic links, so a link to a file reads as that file and a link
     * to a folder as a folder. A link that reaches no entry reads as absent.
     */
    suspend fun entryKind(sessionId: String, path: String): SshResult<RemoteEntryKind> =
        withContext(Dispatchers.IO) {
            val sftp = sftpClients[sessionId]
                ?: return@withContext SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_client_not_ready))
            val safePath = sanitizePath(path)
                ?: return@withContext SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_invalid_path))
            return@withContext try {
                SshResult.Success(entryKindOf(sftp.statExistence(safePath)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: net.schmizz.sshj.sftp.SFTPException) {
                SshResult.Error(SshErrorCode.UNKNOWN, sftpErrorMessage(e, getString(Res.string.sftp_error_stat_failed)))
            } catch (_: java.io.IOException) {
                SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_connection_lost))
            } catch (_: Exception) {
                SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_stat_failed))
            }
        }

    /**
     * Maps the attributes returned by `statExistence` (null when the entry does not
     * exist) to a [RemoteEntryKind]. A regular file, a type the server did not state and
     * a symbolic link (which a stat that follows links should not return, classed as a
     * file to be safe) are files; sockets, FIFOs and devices are [RemoteEntryKind.OTHER].
     */
    private fun entryKindOf(attrs: FileAttributes?): RemoteEntryKind {
        if (attrs == null) return RemoteEntryKind.ABSENT
        val type: FileMode.Type = attrs.type
        return when (type) {
            FileMode.Type.DIRECTORY -> RemoteEntryKind.DIRECTORY
            FileMode.Type.REGULAR,
            FileMode.Type.UNKNOWN,
            FileMode.Type.SYMLINK -> RemoteEntryKind.FILE
            FileMode.Type.FIFO_SPECIAL,
            FileMode.Type.SOCKET_SPECIAL,
            FileMode.Type.BLOCK_SPECIAL,
            FileMode.Type.CHAR_SPECIAL -> RemoteEntryKind.OTHER
        }
    }

    /**
     * Whether a refused open of [safePath] means "this name is already taken". True on the
     * dedicated status. A server without it (OpenSSH on SFTP v3) answers a generic
     * `FAILURE`, or a status SSHJ does not know: a stat then confirms the name, and any
     * failure of that stat counts as "not confirmed" so the caller reports the original
     * error. Any other status (permission, no space...) is not about the name.
     */
    private fun refusalMeansNameTaken(
        sftp: SFTPClient,
        safePath: String,
        e: net.schmizz.sshj.sftp.SFTPException,
    ): Boolean = when (e.statusCode) {
        Response.StatusCode.FILE_ALREADY_EXISTS -> true
        Response.StatusCode.FAILURE,
        Response.StatusCode.UNKNOWN -> try {
            sftp.statExistence(safePath) != null
        } catch (_: Exception) {
            false
        }
        else -> false
    }

    // ── Path sanitization ─────────────────────────────────────────────────────

    /**
     * Sanitizes a remote SFTP path.
     *
     * An SFTP path is not a URL: it is used as-is, with no decoding at all.
     * `+` and `%XX` are ordinary file name characters (a folder named `x+y` or
     * `100%` must stay reachable) and `%2e%2e` is a literal name, not a parent
     * reference: the server does not decode it either.
     *
     * - rejects blank paths and null-bytes
     * - keeps `\` as is (an ordinary name character on a POSIX server)
     * - collapses repeated slashes and `.` segments
     * - resolves `..` segments, rejecting any path that climbs above `/`
     * - always returns an absolute path (starts with `/`)
     *
     * @return the sanitized path, or null when it is invalid or unsafe.
     */
    fun sanitizePath(path: String): String? {
        if (path.isBlank()) return null
        if (path.contains('\u0000')) return null
        // "\" is not converted: on a POSIX server it is an ordinary name character, and a
        // folder named "x\..\.." would otherwise target its grandparent (a deletion would
        // empty another folder). Windows servers (Win32-OpenSSH) already use "/".
        val normalized = path
        val resolved = ArrayDeque<String>()
        for (segment in normalized.split('/')) {
            when {
                segment.isEmpty() || segment == "." -> Unit
                segment == ".." -> {
                    if (resolved.isEmpty()) return null
                    resolved.removeLast()
                }
                else -> resolved.addLast(segment)
            }
        }
        return "/" + resolved.joinToString("/")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Maps an SSHJ failure to a user-facing message. The SFTP status code comes first:
     * it is what the server really said. The message text is only a fallback for the
     * servers (OpenSSH on SFTP v3, notably) that answer a generic `FAILURE` and put the
     * reason in free text. In that text, "does not exist" / "no such file" must be
     * tested before "exist", which would otherwise swallow them as "already exists".
     */
    private suspend fun sftpErrorMessage(e: net.schmizz.sshj.sftp.SFTPException, fallback: String): String {
        when (e.statusCode) {
            Response.StatusCode.PERMISSION_DENIED -> return getString(Res.string.sftp_error_permission_denied)
            Response.StatusCode.NO_SUCH_FILE,
            Response.StatusCode.NO_SUCH_PATH -> return getString(Res.string.sftp_error_not_found)
            Response.StatusCode.FILE_ALREADY_EXISTS -> return getString(Res.string.sftp_error_target_exists)
            Response.StatusCode.NO_SPACE_ON_FILESYSTEM -> return getString(Res.string.sftp_error_no_space)
            Response.StatusCode.FILE_IS_A_DIRECTORY -> return getString(Res.string.sftp_error_is_directory)
            else -> Unit
        }
        val msg = e.message ?: return fallback
        return when {
            msg.contains("permission", ignoreCase = true) -> getString(Res.string.sftp_error_permission_denied)
            msg.contains("no such", ignoreCase = true) ||
                msg.contains("not exist", ignoreCase = true) ||
                msg.contains("n't exist", ignoreCase = true) -> getString(Res.string.sftp_error_not_found)
            msg.contains("exist", ignoreCase = true) -> getString(Res.string.sftp_error_target_exists)
            msg.contains("no space", ignoreCase = true) -> getString(Res.string.sftp_error_no_space)
            msg.contains("directory", ignoreCase = true) -> getString(Res.string.sftp_error_is_directory)
            else -> fallback
        }
    }

    private fun RemoteResourceInfo.toSftpFile(): SftpFile {
        val attrs = this.attributes
        val isSymlink = attrs.type == FileMode.Type.SYMLINK
        val isDir = this.isDirectory
        return SftpFile(
            name = this.name,
            path = this.path,
            size = if (isDir) 0L else attrs.size,
            permissions = filePermissionsToInt(attrs.permissions),
            isDirectory = isDir,
            isSymlink = isSymlink,
            modifiedAt = attrs.mtime * 1000L,
        )
    }

    private fun filePermissionsToInt(permissions: Set<FilePermission>): Int {
        var bits = 0
        for (perm in permissions) bits = bits or PERMISSION_BIT_MAP.getOrDefault(perm, 0)
        return bits
    }

    companion object {
        /**
         * Path of the temporary file an upload that replaces the file [targetName] of [parentDir]
         * writes to: `.report.pdf.nextsh-part-1a2b3c4d`, in the same folder (a rename across folders
         * could cross a file system and fail), hidden on Unix, and the same shape as the temporary
         * files of a download, so that a leftover is recognized as an unfinished NextSH transfer.
         * The name is cut by [DesktopTransferManager.temporaryNamePart] (120 bytes of UTF-8, between two
         * code points), so the whole name stays under the 255 bytes of the usual file systems.
         * [parentDir] may be the root: no double slash is written.
         */
        internal fun uploadTempPath(parentDir: String, targetName: String, id: String): String =
            parentDir.trimEnd('/') + "/." + DesktopTransferManager.temporaryNamePart(targetName) + UPLOAD_TEMP_MARKER + id

        /**
         * What the name of a leftover temporary file tells (see [uploadLeftoverName]), or null when [name]
         * is not the shape of [uploadTempPath]: `.` + the start of the name of the file it was to replace
         * (1 to 120 bytes of UTF-8) + `.nextsh-part-` + 8 lowercase hexadecimal digits.
         *
         * Strict, and linear: no regular expression, nothing is searched, the whole name must have the
         * shape. A name that merely looks alike is refused, which misses a leftover and never invents one.
         * The same function, with the same table of names in its tests, as on Android.
         */
        internal fun uploadLeftoverName(name: String): UploadLeftoverName? {
            val idStart = name.length - TEMP_ID_CHARS
            val markerStart = idStart - UPLOAD_TEMP_MARKER.length
            if (markerStart < 2 || name[0] != '.') return null
            if (!name.startsWith(UPLOAD_TEMP_MARKER, markerStart)) return null
            if (!name.substring(idStart).all { it in '0'..'9' || it in 'a'..'f' }) return null
            val part = name.substring(1, markerStart)
            if (part == "." || part == "..") return null
            if (part.any { it == '/' || it == '\\' || it.isISOControl() }) return null
            if (DesktopTransferManager.temporaryNamePart(part) != part) return null
            // A cut start weighs 117 to 120 bytes (a code point takes 4 bytes at most): one of 116 or less is whole.
            return UploadLeftoverName(part, cut = DesktopTransferManager.temporaryNamePart(part, maxBytes = 116) != part)
        }

        /**
         * Whether [file] is reported as the leftover of an interrupted upload: an ordinary file (neither a
         * folder nor a link, which a third party could plant under that name to have it deleted) with the
         * shape of [uploadLeftoverName].
         */
        internal fun isUploadLeftover(file: SftpFile): Boolean =
            !file.isDirectory && !file.isSymlink && uploadLeftoverName(file.name) != null

        private val PERMISSION_BIT_MAP = mapOf(
            FilePermission.USR_R to 0b100_000_000,
            FilePermission.USR_W to 0b010_000_000,
            FilePermission.USR_X to 0b001_000_000,
            FilePermission.GRP_R to 0b000_100_000,
            FilePermission.GRP_W to 0b000_010_000,
            FilePermission.GRP_X to 0b000_001_000,
            FilePermission.OTH_R to 0b000_000_100,
            FilePermission.OTH_W to 0b000_000_010,
            FilePermission.OTH_X to 0b000_000_001,
        )
    }
}
