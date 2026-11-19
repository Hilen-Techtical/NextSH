// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.ssh

import fr.techtical.nextsh.desktop.captureLogs
import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_connection_lost
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_local_read_failed
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_permission_denied
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_target_exists
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_upload_failed
import fr.techtical.nextsh.desktop.taggedBy
import fr.techtical.nextsh.shared.domain.model.SshErrorCode
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.shared.util.LogId
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPEngine
import net.schmizz.sshj.sftp.SFTPException
import org.jetbrains.compose.resources.getString
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * An upload to a FREE name that fails or is cancelled after the file was created: the incomplete file
 * that this very call made is deleted, best effort, so that nothing that looks finished is left under
 * its real name. The rules, tested here:
 *
 * - only a file the call created (`EXCL` open that succeeded) is ever deleted: never one that was
 *   there before (an in-place write, a refused `EXCL`), never the target of a replacement through a
 *   temporary file;
 * - the delete goes by a name, so it only happens if the name still shows the file that was created:
 *   the type, the size, the modification time, the owner, the group and the permissions read on the
 *   handle (before it is closed) are those read by name just before the delete. Another writer that replaced the entry meanwhile keeps his file;
 * - nothing is sent after a lost channel (neither a close nor a delete), where each call could only
 *   wait for the timeout;
 * - a delete that fails is swallowed and logged by class, the error of the upload stays the one reported.
 *
 * The SFTP client is a strict mock: a call the implementation was not expected to make fails the test.
 */
class DesktopSftpManagerPartialUploadTest {

    private val sftp = mockk<SFTPClient>()
    private val remoteFile = mockk<RemoteFile>(relaxed = true)

    private suspend fun openedManager(): DesktopSftpManager {
        val ssh = mockk<SSHClient>()
        every { ssh.newSFTPClient() } returns sftp
        val terminal = mockk<DesktopSshTerminalSession>()
        every { terminal.getSshClient() } returns ssh
        val sessions = mockk<DesktopSshSessionManager>()
        every { sessions.getTerminalSession(SESSION) } returns terminal
        return DesktopSftpManager(sessions).also { assertIs<SshResult.Success<*>>(it.openSftp(SESSION)) }
    }

    private fun assertError(expectedMessage: String, result: SshResult<*>) {
        val error = assertIs<SshResult.Error>(result)
        assertEquals(SshErrorCode.UNKNOWN, error.code)
        assertEquals(expectedMessage, error.message)
    }

    private suspend fun DesktopSftpManager.write(
        replaceExisting: Boolean = false,
        source: InputStream = ByteArrayInputStream(ByteArray(10)),
    ): SshResult<Unit> = writeFile(SESSION, TARGET, source, 10L, replaceExisting) { _, _ -> }

    private fun failWritesWith(cause: Throwable) {
        every { remoteFile.write(any<Long>(), any<ByteArray>(), any<Int>(), any<Int>()) } throws cause
    }

    /** A regular file with the attributes the delete compares: size, modification time, owner, group and permissions. */
    private fun file(
        size: Long = 10,
        mtime: Long = 1_000,
        uid: Int = 1000,
        gid: Int = uid,
        permissions: Int = 0b110_100_100,
    ): FileAttributes =
        FileAttributes.Builder()
            .withType(FileMode.Type.REGULAR)
            .withPermissions(permissions)
            .withUIDGID(uid, gid)
            .withSize(size)
            .withAtimeMtime(mtime, mtime)
            .build()

    /** The handle of the created file states [file], and the name shows [shown]: the one that was created, by default. */
    private fun givenFileCreated(shown: FileAttributes = file()) {
        every { sftp.open(TARGET, any<Set<OpenMode>>()) } returns remoteFile
        every { remoteFile.fetchAttributes() } returns file()
        every { sftp.lstat(TARGET) } returns shown
    }

    // ── An exclusive upload that fails after the file was created ─────────────

    @Test
    fun `a server failure during the copy deletes the incomplete file, recognized by name after the handle was read and closed`() = runTest {
        val manager = openedManager()
        givenFileCreated()
        failWritesWith(SFTPException(Response.StatusCode.FAILURE, "Failure"))
        every { sftp.rm(TARGET) } just Runs

        val result = manager.write()

        assertError(getString(Res.string.sftp_error_upload_failed), result)
        verifyOrder {
            remoteFile.fetchAttributes()
            remoteFile.close()
            sftp.lstat(TARGET)
            sftp.rm(TARGET)
        }
    }

    @Test
    fun `a cancellation during the copy deletes the incomplete file and is rethrown`() = runTest {
        val manager = openedManager()
        givenFileCreated()
        failWritesWith(CancellationException("cancelled"))
        every { sftp.rm(TARGET) } just Runs

        assertFailsWith<CancellationException> { manager.write() }

        verifyOrder {
            remoteFile.close()
            sftp.rm(TARGET)
        }
    }

    @Test
    fun `a job cancelled between two blocks deletes the incomplete file`() = runTest {
        val manager = openedManager()
        givenFileCreated()
        every { sftp.rm(TARGET) } just Runs
        lateinit var job: Job
        var reads = 0
        val source = object : InputStream() {
            override fun read(): Int = throw UnsupportedOperationException()

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                reads++
                if (reads == 2) job.cancel()
                return if (reads <= 2) 10 else -1
            }
        }
        job = launch(Dispatchers.Default, start = CoroutineStart.LAZY) { manager.write(source = source) }

        job.start()
        job.join()

        assertTrue(job.isCancelled)
        verify(exactly = 1) { sftp.rm(TARGET) }
    }

    @Test
    fun `a local file that cannot be read deletes the incomplete file and says so`() = runTest {
        val manager = openedManager()
        givenFileCreated()
        every { sftp.rm(TARGET) } just Runs
        val broken = object : InputStream() {
            override fun read(): Int = throw IOException("Input/output error")
        }

        val result = manager.write(source = broken)

        assertError(getString(Res.string.sftp_error_local_read_failed), result)
        verify(exactly = 1) { sftp.rm(TARGET) }
    }

    @Test
    fun `a delete that fails changes neither the result nor the exception, and is logged by class`() = runTest {
        val manager = openedManager()
        givenFileCreated()
        failWritesWith(IOException("socket closed"))
        every { sftp.rm(TARGET) } throws IOException("socket closed")

        val lines = captureLogs {
            assertError(getString(Res.string.sftp_error_connection_lost), manager.write())
        }.taggedBy("DesktopSftpManager")

        verify(exactly = 1) { sftp.rm(TARGET) }
        assertTrue(lines.any { it.contains("unfinished file not removed") && it.contains("IOException") && it.contains(LogId.of(SESSION)) }, "got $lines")
        assertTrue(lines.none { it.contains("a.bin") || it.contains("/home") }, "got $lines")
    }

    @Test
    fun `a lost channel during the copy sends nothing more, neither a close nor a delete`() = runTest {
        val manager = openedManager()
        givenFileCreated()
        val lost = listOf(
            // The channel dies: SSHJ gives no status.
            SFTPException("EOF while reading packet"),
            // A server that stays silent: the timeout is the cause.
            SFTPException("Timeout", TimeoutException("expired")),
        )

        for (cause in lost) {
            failWritesWith(cause)

            // The message is the one an upload always had: this change does not touch it.
            assertError(getString(Res.string.sftp_error_upload_failed), manager.write())
        }
        verify(exactly = 0) { remoteFile.close() }
        verify(exactly = 0) { remoteFile.fetchAttributes() }
        verify(exactly = 0) { sftp.lstat(any<String>()) }
        verify(exactly = 0) { sftp.rm(any<String>()) }
    }

    // ── The name is no longer the file that was created ───────────────────────

    @Test
    fun `an entry that another writer put under the name meanwhile is not deleted`() = runTest {
        val manager = openedManager()
        failWritesWith(SFTPException(Response.StatusCode.FAILURE, "Failure"))
        val shown = mapOf<String, () -> FileAttributes>(
            // a complete file of somebody else, renamed over the name
            "owner" to { file(uid = 2000) },
            "group" to { file(gid = 2000) },
            "permissions" to { file(permissions = 0b110_110_110) },
            "setuid" to { file(permissions = 0b100_110_100_100) },
            // the handle states the permissions, the name does not (and states no type either)
            "permissions not stated by the name" to {
                FileAttributes.Builder().withUIDGID(1000, 1000).withSize(10).withAtimeMtime(1_000, 1_000).build()
            },
            "folder" to {
                FileAttributes.Builder()
                    .withType(FileMode.Type.DIRECTORY)
                    .withPermissions(0b110_100_100)
                    .withUIDGID(1000, 1000)
                    .withSize(10)
                    .withAtimeMtime(1_000, 1_000)
                    .build()
            },
            "size" to { file(size = 11) },
            "mtime" to { file(mtime = 1_001) },
            // what the server does not state cannot be compared
            "mtime not stated" to {
                FileAttributes.Builder().withType(FileMode.Type.REGULAR).withUIDGID(1000, 1000).withSize(10).build()
            },
            "owner not stated" to {
                FileAttributes.Builder().withType(FileMode.Type.REGULAR).withSize(10).withAtimeMtime(1_000, 1_000).build()
            },
            "size not stated" to {
                FileAttributes.Builder().withType(FileMode.Type.REGULAR).withUIDGID(1000, 1000).withAtimeMtime(1_000, 1_000).build()
            },
            "link" to { FileAttributes.Builder().withType(FileMode.Type.SYMLINK).withUIDGID(1000, 1000).withSize(10).withAtimeMtime(1_000, 1_000).build() },
            "gone" to { throw SFTPException(Response.StatusCode.NO_SUCH_FILE, "No such file") },
            "unreadable" to { throw IOException("socket closed") },
        )

        val lines = captureLogs {
            for ((_, now) in shown) {
                givenFileCreated()
                every { sftp.lstat(TARGET) } answers { now() }

                assertError(getString(Res.string.sftp_error_upload_failed), manager.write())
            }
        }.taggedBy("DesktopSftpManager")

        verify(exactly = 0) { sftp.rm(any<String>()) }
        assertEquals(shown.size, lines.count { it.contains("unfinished file kept") }, "got $lines")
        assertTrue(lines.none { it.contains("a.bin") || it.contains("/home") }, "got $lines")
    }

    @Test
    fun `a file whose handle cannot be read is not deleted`() = runTest {
        val manager = openedManager()
        givenFileCreated()
        every { remoteFile.fetchAttributes() } throws SFTPException(Response.StatusCode.FAILURE, "Failure")
        failWritesWith(SFTPException(Response.StatusCode.FAILURE, "Failure"))

        val lines = captureLogs {
            assertError(getString(Res.string.sftp_error_upload_failed), manager.write())
        }.taggedBy("DesktopSftpManager")

        verify(exactly = 1) { remoteFile.close() }
        verify(exactly = 0) { sftp.rm(any<String>()) }
        assertTrue(lines.any { it.contains("unfinished file kept") }, "got $lines")
    }

    @Test
    fun `a read of the handle that shows a lost channel sends nothing more, neither a close nor a lookup nor a delete`() = runTest {
        val manager = openedManager()
        givenFileCreated()
        failWritesWith(SFTPException(Response.StatusCode.FAILURE, "Failure"))
        val lost = listOf(
            // The channel dies: SSHJ gives no status.
            SFTPException("EOF while reading packet"),
            // A server that stays silent: the timeout is the cause.
            SFTPException("Timeout", TimeoutException("expired")),
        )

        for (cause in lost) {
            every { remoteFile.fetchAttributes() } throws cause

            // The error told is the one of the copy, not the one of the cleanup.
            assertError(getString(Res.string.sftp_error_upload_failed), manager.write())
        }
        verify(exactly = 0) { remoteFile.close() }
        verify(exactly = 0) { sftp.lstat(any<String>()) }
        verify(exactly = 0) { sftp.rm(any<String>()) }
    }

    @Test
    fun `a handle that states no modification time cannot be recognized and its file is not deleted`() = runTest {
        val manager = openedManager()
        givenFileCreated()
        every { remoteFile.fetchAttributes() } returns
            FileAttributes.Builder().withType(FileMode.Type.REGULAR).withUIDGID(1000, 1000).withSize(10).build()
        failWritesWith(SFTPException(Response.StatusCode.FAILURE, "Failure"))

        assertError(getString(Res.string.sftp_error_upload_failed), manager.write())

        verify(exactly = 0) { sftp.rm(any<String>()) }
    }

    @Test
    fun `a server that states neither type nor permissions on either side still has its incomplete file deleted`() = runTest {
        val manager = openedManager()
        val bare = {
            FileAttributes.Builder().withUIDGID(1000, 1000).withSize(10).withAtimeMtime(1_000, 1_000).build()
        }
        every { sftp.open(TARGET, any<Set<OpenMode>>()) } returns remoteFile
        every { remoteFile.fetchAttributes() } returns bare()
        every { sftp.lstat(TARGET) } returns bare()
        every { sftp.rm(TARGET) } just Runs
        failWritesWith(SFTPException(Response.StatusCode.FAILURE, "Failure"))

        assertError(getString(Res.string.sftp_error_upload_failed), manager.write())

        verify(exactly = 1) { sftp.rm(TARGET) }
    }

    @Test
    fun `a handle that states no owner or no size cannot be recognized and its file is not deleted`() = runTest {
        val manager = openedManager()
        failWritesWith(SFTPException(Response.StatusCode.FAILURE, "Failure"))
        val handles = listOf(
            FileAttributes.Builder().withType(FileMode.Type.REGULAR).withSize(10).withAtimeMtime(1_000, 1_000).build(),
            FileAttributes.Builder().withType(FileMode.Type.REGULAR).withUIDGID(1000, 1000).withAtimeMtime(1_000, 1_000).build(),
        )

        for (handle in handles) {
            givenFileCreated()
            every { remoteFile.fetchAttributes() } returns handle

            assertError(getString(Res.string.sftp_error_upload_failed), manager.write())
        }

        verify(exactly = 0) { sftp.rm(any<String>()) }
    }

    // ── What is never deleted ─────────────────────────────────────────────────

    @Test
    fun `a name that was taken is refused and the file already there is not deleted`() = runTest {
        val manager = openedManager()
        every { sftp.open(TARGET, any<Set<OpenMode>>()) } throws SFTPException(Response.StatusCode.FILE_ALREADY_EXISTS, "File exists")

        assertError(getString(Res.string.sftp_error_target_exists), manager.write())

        verify(exactly = 0) { sftp.rm(any<String>()) }
    }

    @Test
    fun `an open that is refused creates nothing and deletes nothing`() = runTest {
        val manager = openedManager()
        every { sftp.open(TARGET, any<Set<OpenMode>>()) } throws SFTPException(Response.StatusCode.PERMISSION_DENIED, "Permission denied")

        assertError(getString(Res.string.sftp_error_permission_denied), manager.write())

        verify(exactly = 0) { sftp.rm(any<String>()) }
    }

    @Test
    fun `a complete upload deletes nothing`() = runTest {
        val manager = openedManager()
        every { sftp.open(TARGET, any<Set<OpenMode>>()) } returns remoteFile

        assertEquals<SshResult<Unit>>(SshResult.Success(Unit), manager.write())

        verify(exactly = 0) { sftp.rm(any<String>()) }
        verify(exactly = 0) { remoteFile.fetchAttributes() }
    }

    @Test
    fun `an in-place write that fails never deletes the file that was there before`() = runTest {
        val manager = openedManager()
        // A server without posix-rename: the replacement is written in place, with TRUNC.
        val engine = mockk<SFTPEngine>()
        every { sftp.lstat(TARGET) } returns file()
        every { sftp.sftpEngine } returns engine
        every { engine.supportsServerExtension("posix-rename", "openssh.com") } returns false
        every { sftp.open(TARGET, any<Set<OpenMode>>()) } returns remoteFile
        failWritesWith(SFTPException(Response.StatusCode.FAILURE, "Failure"))

        val result = manager.write(replaceExisting = true)

        assertError(getString(Res.string.sftp_error_upload_failed), result)
        verify(exactly = 0) { sftp.rm(any<String>()) }
    }

    @Test
    fun `a replacement through a temporary file deletes the temporary file and never the target`() = runTest {
        val manager = openedManager()
        val engine = mockk<SFTPEngine>()
        every { sftp.lstat(TARGET) } returns file()
        every { sftp.sftpEngine } returns engine
        every { engine.supportsServerExtension("posix-rename", "openssh.com") } returns true
        every { sftp.open(match<String> { it.contains(".nextsh-part-") }, any<Set<OpenMode>>(), any<FileAttributes>()) } returns remoteFile
        every { remoteFile.fetchAttributes() } returns file()
        failWritesWith(SFTPException(Response.StatusCode.FAILURE, "Failure"))
        every { sftp.rm(any<String>()) } just Runs

        manager.write(replaceExisting = true)

        verify(exactly = 1) { sftp.rm(match<String> { it.contains(".nextsh-part-") }) }
        verify(exactly = 0) { sftp.rm(TARGET) }
    }

    // ── A replacement whose target is gone creates it, and owns what it created ─

    @Test
    fun `a replacement whose target disappeared creates it and deletes it if the copy fails`() = runTest {
        val manager = openedManager()
        // The plan finds nothing at the name; the delete finds the file that was created.
        var looks = 0
        every { sftp.lstat(TARGET) } answers {
            if (++looks == 1) throw SFTPException(Response.StatusCode.NO_SUCH_FILE, "No such file") else file()
        }
        every { sftp.open(TARGET, any<Set<OpenMode>>()) } returns remoteFile
        every { remoteFile.fetchAttributes() } returns file()
        failWritesWith(SFTPException(Response.StatusCode.FAILURE, "Failure"))
        every { sftp.rm(TARGET) } just Runs

        val result = manager.write(replaceExisting = true)

        assertError(getString(Res.string.sftp_error_upload_failed), result)
        verify(exactly = 1) { sftp.rm(TARGET) }
    }

    private companion object {
        const val SESSION = "s1"
        const val TARGET = "/home/t/a.bin"
    }
}
