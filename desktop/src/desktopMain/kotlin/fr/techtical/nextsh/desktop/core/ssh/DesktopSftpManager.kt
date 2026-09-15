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
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_delete_failed
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_delete_recursive_failed
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_download_failed
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_download_generic
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_generic
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_invalid_file_name
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_invalid_folder_name
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_invalid_new_path
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_invalid_path
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_invalid_permissions
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_is_directory
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_no_space
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_not_found
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_permission_denied
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_preview_too_large
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_read_failed
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_read_generic
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_rename_failed
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_session_not_found
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_stat_failed
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_target_exists
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_upload_failed
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_upload_generic
import fr.techtical.nextsh.shared.domain.model.SftpFile
import fr.techtical.nextsh.shared.domain.model.SshErrorCode
import fr.techtical.nextsh.shared.domain.model.SshResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.xfer.FilePermission
import org.jetbrains.compose.resources.getString
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

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
 * Security: [sanitizePath] rejects null-bytes and out-of-root traversals,
 * [sanitizeFilename] strips path separators and control chars (mirrors
 * Android parity).
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

    suspend fun createDirectory(sessionId: String, path: String): SshResult<Unit> =
        withContext(Dispatchers.IO) {
            val sftp = sftpClients[sessionId]
                ?: return@withContext SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_client_not_ready))
            val name = path.substringAfterLast('/')
            if (sanitizeFilename(name) == null) {
                return@withContext SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_invalid_folder_name))
            }
            val safePath = sanitizePath(path)
                ?: return@withContext SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_invalid_path))
            return@withContext try {
                sftp.mkdir(safePath)
                SshResult.Success(Unit)
            } catch (e: net.schmizz.sshj.sftp.SFTPException) {
                SshResult.Error(SshErrorCode.UNKNOWN, sftpErrorMessage(e, getString(Res.string.sftp_error_create_dir_failed)))
            } catch (_: java.io.IOException) {
                SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_connection_lost))
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
                entry.isDirectory -> {
                    deleteRecursiveInternal(sftp, entry.path)
                    sftp.rmdir(entry.path)
                }
                else -> sftp.rm(entry.path)
            }
        }
        sftp.rmdir(path)
    }

    suspend fun rename(sessionId: String, oldPath: String, newName: String): SshResult<Unit> =
        withContext(Dispatchers.IO) {
            val sftp = sftpClients[sessionId]
                ?: return@withContext SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_client_not_ready))
            val sanitizedName = sanitizeFilename(newName)
                ?: return@withContext SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_invalid_file_name))
            val safeOldPath = sanitizePath(oldPath)
                ?: return@withContext SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_invalid_path))
            val parentDir = safeOldPath.substringBeforeLast('/', "/").ifEmpty { "/" }
            val newPath = sanitizePath("$parentDir/$sanitizedName")
                ?: return@withContext SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_invalid_new_path))
            return@withContext try {
                sftp.rename(safeOldPath, newPath)
                SshResult.Success(Unit)
            } catch (e: net.schmizz.sshj.sftp.SFTPException) {
                SshResult.Error(SshErrorCode.UNKNOWN, sftpErrorMessage(e, getString(Res.string.sftp_error_rename_failed)))
            } catch (_: java.io.IOException) {
                SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_connection_lost))
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
                outputStream.write(buffer, 0, read)
                offset += read
                onProgress(offset, size)
            }
            SshResult.Success(Unit)
        } catch (e: net.schmizz.sshj.sftp.SFTPException) {
            SshResult.Error(SshErrorCode.UNKNOWN, sftpErrorMessage(e, getString(Res.string.sftp_error_download_failed)))
        } catch (_: java.io.IOException) {
            SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_connection_lost))
        } catch (_: Exception) {
            SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_download_generic))
        } finally {
            try { remoteFile?.close() } catch (_: Exception) {}
        }
    }

    suspend fun writeFile(
        sessionId: String,
        remotePath: String,
        inputStream: InputStream,
        size: Long,
        onProgress: (bytesTransferred: Long, totalBytes: Long) -> Unit,
    ): SshResult<Unit> = withContext(Dispatchers.IO) {
        val sftp = sftpClients[sessionId]
            ?: return@withContext SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_client_not_ready))
        val safePath = sanitizePath(remotePath)
            ?: return@withContext SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_invalid_path))
        var remoteFile: net.schmizz.sshj.sftp.RemoteFile? = null
        return@withContext try {
            remoteFile = sftp.open(safePath, setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC))
            var offset = 0L
            val buffer = ByteArray(32768)
            while (true) {
                // Same cancellation point as readFile: between chunks.
                coroutineContext.ensureActive()
                val read = inputStream.read(buffer)
                if (read <= 0) break
                remoteFile.write(offset, buffer, 0, read)
                offset += read
                onProgress(offset, size)
            }
            SshResult.Success(Unit)
        } catch (e: net.schmizz.sshj.sftp.SFTPException) {
            SshResult.Error(SshErrorCode.UNKNOWN, sftpErrorMessage(e, getString(Res.string.sftp_error_upload_failed)))
        } catch (_: java.io.IOException) {
            SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_connection_lost))
        } catch (_: Exception) {
            SshResult.Error(SshErrorCode.UNKNOWN, getString(Res.string.sftp_error_upload_generic))
        } finally {
            try { remoteFile?.close() } catch (_: Exception) {}
        }
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

    // ── Path sanitization ─────────────────────────────────────────────────────

    fun sanitizePath(path: String): String? {
        if (path.isBlank()) return null
        if (path.contains('\u0000')) return null
        val decoded = try {
            java.net.URLDecoder.decode(path, "UTF-8")
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (decoded.contains('\u0000')) return null
        val normalized = decoded.replace('\\', '/')
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

    fun sanitizeFilename(filename: String): String? {
        val sanitized = filename
            .replace(Regex("[/\\\\\u0000\\p{Cntrl}]"), "")
            .trim()
            .take(255)
        return sanitized.ifEmpty { null }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private suspend fun sftpErrorMessage(e: net.schmizz.sshj.sftp.SFTPException, fallback: String): String {
        val msg = e.message ?: return fallback
        return when {
            msg.contains("permission", ignoreCase = true) -> getString(Res.string.sftp_error_permission_denied)
            msg.contains("no such", ignoreCase = true) -> getString(Res.string.sftp_error_not_found)
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
