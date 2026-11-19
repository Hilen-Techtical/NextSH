// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.testutil.RecordingTree
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * What the manager puts in the error it returns: a fixed text ([SftpMessages.kt]), without the
 * identifier of the session, the path, the size of a file or the message of an exception, which the
 * browser would show to the user as they are.
 */
@ExtendWith(MockKExtension::class)
class SftpManagerMessagesTest {

    @MockK
    private lateinit var sessionManager: SshSessionManager

    private lateinit var sftpManager: SftpManager

    /** An identifier the user must never read in a message. */
    private val sid = "3f2a9c1e-77b0-4c1d-9a55-session"

    @BeforeEach
    fun setUp() {
        every { sessionManager.addSessionEndListener(any()) } just Runs
        sftpManager = SftpManager(sessionManager)
    }

    private suspend fun openedClient(): SFTPClient {
        val ssh = mockk<SSHClient>()
        val sftp = mockk<SFTPClient>()
        every { ssh.newSFTPClient() } returns sftp
        coEvery { sessionManager.getClient(sid) } returns ssh
        sftpManager.openSftp(sid)
        return sftp
    }

    private fun SshResult<*>.messageOfError(): String = (this as SshResult.Error).message

    // ── Open ─────────────────────────────────────────────────────────────────────

    @Test
    fun `a session that is gone says the fixed text, without its identifier`() = runTest {
        coEvery { sessionManager.getClient(sid) } returns null

        val message = sftpManager.openSftp(sid).messageOfError()

        assertEquals(SFTP_SESSION_NOT_FOUND_MESSAGE, message)
        assertFalse(message.contains(sid))
    }

    @Test
    fun `a channel that does not open says the fixed text, not the message of the exception`() = runTest {
        val ssh = mockk<SSHClient>()
        every { ssh.newSFTPClient() } throws IOException("db.interne.example:22 Connection refused (secret detail)")
        coEvery { sessionManager.getClient(sid) } returns ssh

        val message = sftpManager.openSftp(sid).messageOfError()

        assertEquals(SFTP_OPEN_FAILED_MESSAGE, message)
        assertFalse(message.contains("secret"))
        assertFalse(message.contains("db.interne"))
    }

    @Test
    fun `a reservation on a session that is gone says the fixed text, without its identifier`() = runTest {
        every { sessionManager.acquire(sid) } returns false

        val message = sftpManager.reserve(sid).messageOfError()

        assertEquals(SFTP_SESSION_NOT_FOUND_MESSAGE, message)
        assertFalse(message.contains(sid))
    }

    // ── No client ────────────────────────────────────────────────────────────────

    @Test
    fun `every operation on a session without an SFTP client says the same fixed text, without the identifier`() = runTest {
        val results: List<SshResult<*>> = listOf(
            sftpManager.getHomeDirectory(sid),
            sftpManager.listDirectory(sid, "/a"),
            sftpManager.createDirectory(sid, "/a", "b"),
            sftpManager.createFile(sid, "/a", "b"),
            sftpManager.delete(sid, "/a"),
            sftpManager.deleteRecursive(sid, "/a"),
            sftpManager.rename(sid, "/a", "b"),
            sftpManager.chmod(sid, "/a", 420),
            sftpManager.readPreview(sid, "/a", 10L),
            sftpManager.readFile(sid, "/a", ByteArrayOutputStream()) { _, _ -> },
            sftpManager.writeFile(sid, "/a", ByteArrayInputStream(ByteArray(0)), 0L, replaceExisting = false) { _, _ -> },
            sftpManager.stat(sid, "/a"),
            sftpManager.checkUploadTarget(sid, "/a"),
        )

        results.forEach { result ->
            assertEquals(SFTP_NO_SESSION_MESSAGE, result.messageOfError())
            assertFalse(result.messageOfError().contains(sid))
        }
    }

    // ── Paths ────────────────────────────────────────────────────────────────────

    @Test
    fun `a path that is refused says the fixed text, without the path`() = runTest {
        openedClient()
        val secret = "/../../etc/passwd"

        val results: List<SshResult<*>> = listOf(
            sftpManager.listDirectory(sid, secret),
            sftpManager.createDirectory(sid, secret, "new"),
            sftpManager.createFile(sid, secret, "new.txt"),
            sftpManager.delete(sid, secret),
            sftpManager.deleteRecursive(sid, secret),
            sftpManager.rename(sid, secret, "b"),
            sftpManager.chmod(sid, secret, 420),
            sftpManager.readPreview(sid, secret, 10L),
            sftpManager.readFile(sid, secret, ByteArrayOutputStream()) { _, _ -> },
            sftpManager.writeFile(sid, secret, ByteArrayInputStream(ByteArray(0)), 0L, replaceExisting = false) { _, _ -> },
            sftpManager.stat(sid, secret),
            sftpManager.checkUploadTarget(sid, secret),
        )

        results.forEach { result ->
            assertEquals(SFTP_INVALID_PATH_MESSAGE, result.messageOfError())
            assertFalse(result.messageOfError().contains("passwd"))
        }
    }

    @Test
    fun `a new name that leads out of the root says the fixed text of an invalid name, not the one of a path`() = runTest {
        openedClient()

        // The name ".." is refused as a name, before any path is built: it is not resolved above the root.
        val message = sftpManager.rename(sid, "/file.txt", "..").messageOfError()

        assertEquals(SFTP_ENTRY_NAME_INVALID_MESSAGE, message)
    }

    @Test
    fun `permissions out of range say the fixed text, without the value`() = runTest {
        openedClient()

        val message = sftpManager.chmod(sid, "/a", 999).messageOfError()

        assertEquals(SFTP_PERMISSIONS_INVALID_MESSAGE, message)
        assertFalse(message.any { it.isDigit() })
    }

    // ── Failures of the server or of the connection ──────────────────────────────

    @Test
    fun `a connection that drops during a listing says the fixed text of a lost connection`() = runTest {
        val sftp = openedClient()
        every { sftp.ls("/a") } throws IOException("Broken pipe")

        val message = sftpManager.listDirectory(sid, "/a").messageOfError()

        assertEquals(SFTP_CONNECTION_LOST_MESSAGE, message)
    }

    @Test
    fun `a failure of the listing that is not the server's says the fixed unknown text, not the exception`() = runTest {
        val sftp = openedClient()
        every { sftp.ls("/a") } throws IllegalStateException("db.interne.example:22 secret detail")

        val message = sftpManager.listDirectory(sid, "/a").messageOfError()

        assertEquals(SFTP_UNKNOWN_MESSAGE, message)
        assertFalse(message.contains("secret"))
    }

    @Test
    fun `a deletion that the server refuses says one text, folder or file`() = runTest {
        val sftp = openedClient()
        every { sftp.rm("/folder") } throws SFTPException("Cannot remove directory")
        every { sftp.ls("/other") } throws SFTPException("Failure")

        assertEquals(SFTP_DELETE_FAILED_MESSAGE, sftpManager.delete(sid, "/folder").messageOfError())
        assertEquals(SFTP_DELETE_FAILED_MESSAGE, sftpManager.deleteRecursive(sid, "/other").messageOfError())
        assertEquals(SFTP_DELETE_FAILED_MESSAGE, sftpManager.deleteRecursive(sid, "/").messageOfError())
    }

    @Test
    fun `a rename onto a name that exists says item, whether it is a file or a folder`() = runTest {
        val sftp = openedClient()
        every { sftp.lstat("/a/new") } throws
            SFTPException(net.schmizz.sshj.sftp.Response.StatusCode.NO_SUCH_FILE, "No such file")
        every { sftp.rename("/a/old", "/a/new") } throws SFTPException("File already exists")

        assertEquals(SFTP_NAME_EXISTS_MESSAGE, sftpManager.rename(sid, "/a/old", "new").messageOfError())
    }

    // ── Files ────────────────────────────────────────────────────────────────────

    @Test
    fun `a creation of a file that fails for no reason of the server says the fixed text, not the exception`() = runTest {
        val sftp = openedClient()
        every { sftp.statExistence("/home/user/new.txt") } throws IllegalStateException("db.interne.example secret detail")

        val message = sftpManager.createFile(sid, "/home/user", "new.txt").messageOfError()

        assertEquals(SFTP_CREATE_FILE_FAILED_MESSAGE, message)
        assertFalse(message.contains("secret"))
        assertFalse(message.contains("db.interne"))
    }

    @Test
    fun `a file too big for the preview says the fixed text, without its size`() = runTest {
        val sftp = openedClient()
        val remote = mockk<RemoteFile>(relaxed = true)
        every { sftp.open("/home/user/big.log") } returns remote
        every { remote.length() } returns 7_654_321L

        val message = sftpManager.readPreview(sid, "/home/user/big.log", 1_000L).messageOfError()

        assertEquals(SFTP_PREVIEW_TOO_LARGE_MESSAGE, message)
        assertFalse(message.any { it.isDigit() })
    }

    @Test
    fun `a preview that fails for no reason of the server says the fixed read text`() = runTest {
        val sftp = openedClient()
        val remote = mockk<RemoteFile>(relaxed = true)
        every { sftp.open("/home/user/a.txt") } returns remote
        every { remote.length() } returns 10L
        every { remote.read(any(), any<ByteArray>(), any(), any()) } throws IllegalStateException("secret detail")

        val message = sftpManager.readPreview(sid, "/home/user/a.txt", 1_000L).messageOfError()

        assertEquals(SFTP_READ_FAILED_MESSAGE, message)
    }

    @Test
    fun `a download that fails says the fixed text, not the exception`() = runTest {
        val sftp = openedClient()
        val remote = mockk<RemoteFile>(relaxed = true)
        every { sftp.open("/home/user/a.txt") } returns remote
        every { remote.length() } returns 10L
        every { remote.read(any(), any<ByteArray>(), any(), any()) } throws IllegalStateException("secret detail")

        val message = sftpManager.readFile(sid, "/home/user/a.txt", ByteArrayOutputStream()) { _, _ -> }.messageOfError()

        assertEquals(SFTP_DOWNLOAD_FAILED_MESSAGE, message)
    }

    @Test
    fun `an upload to a full disk says the fixed text of the missing room`() = runTest {
        val sftp = openedClient()
        every { sftp.open("/home/user/a.bin", any<Set<OpenMode>>()) } throws SFTPException("No space left on device")
        // The refusal of the open is looked up by name (the upload does not replace): nothing is there.
        every { sftp.lstat("/home/user/a.bin") } throws SFTPException(Response.StatusCode.NO_SUCH_FILE, "No such file")

        val message = sftpManager
            .writeFile(sid, "/home/user/a.bin", ByteArrayInputStream(ByteArray(0)), 0L, replaceExisting = false) { _, _ -> }
            .messageOfError()

        assertEquals(SFTP_NO_SPACE_MESSAGE, message)
    }

    @Test
    fun `an upload that fails says the fixed text, not the exception`() = runTest {
        val sftp = openedClient()
        every { sftp.open("/home/user/a.bin", any<Set<OpenMode>>()) } throws IllegalStateException("secret detail")

        val message = sftpManager
            .writeFile(sid, "/home/user/a.bin", ByteArrayInputStream(ByteArray(0)), 0L, replaceExisting = false) { _, _ -> }
            .messageOfError()

        assertEquals(SFTP_UPLOAD_FAILED_MESSAGE, message)
    }

    // ── Folders and renames: a failure that is not the server's ──────────────────

    @Test
    fun `a creation of a folder that fails in the lookup for no reason of the server says the fixed text, not the exception`() = runTest {
        val sftp = openedClient()
        every { sftp.statExistence("/home/user/new") } throws IllegalStateException("db.interne.example secret detail")

        val message = sftpManager.createDirectory(sid, "/home/user", "new").messageOfError()

        assertEquals(SFTP_MKDIR_FAILED_MESSAGE, message)
        assertFalse(message.contains("secret"))
        assertFalse(message.contains("db.interne"))
    }

    @Test
    fun `a creation of a folder that fails in mkdir for no reason of the server says the fixed text, not the exception`() = runTest {
        val sftp = openedClient()
        every { sftp.statExistence("/home/user/new") } returns null
        every { sftp.mkdir("/home/user/new") } throws IllegalStateException("db.interne.example secret detail")

        val message = sftpManager.createDirectory(sid, "/home/user", "new").messageOfError()

        assertEquals(SFTP_MKDIR_FAILED_MESSAGE, message)
        assertFalse(message.contains("secret"))
        assertFalse(message.contains("db.interne"))
    }

    @Test
    fun `a rename that fails in the lookup for no reason of the server says the fixed text, not the exception`() = runTest {
        val sftp = openedClient()
        every { sftp.lstat("/home/user/new.txt") } throws IllegalStateException("db.interne.example secret detail")

        val message = sftpManager.rename(sid, "/home/user/old.txt", "new.txt").messageOfError()

        assertEquals(SFTP_RENAME_FAILED_MESSAGE, message)
        assertFalse(message.contains("secret"))
        assertFalse(message.contains("db.interne"))
    }

    @Test
    fun `a rename that fails in the rename itself for no reason of the server says the fixed text, not the exception`() = runTest {
        val sftp = openedClient()
        every { sftp.lstat("/home/user/new.txt") } throws
            SFTPException(net.schmizz.sshj.sftp.Response.StatusCode.NO_SUCH_FILE, "No such file")
        every { sftp.rename("/home/user/old.txt", "/home/user/new.txt") } throws
            IllegalStateException("db.interne.example secret detail")

        val message = sftpManager.rename(sid, "/home/user/old.txt", "new.txt").messageOfError()

        assertEquals(SFTP_RENAME_FAILED_MESSAGE, message)
        assertFalse(message.contains("secret"))
        assertFalse(message.contains("db.interne"))
    }

    // ── The log: the class and the status only, never the exception nor its text ─

    /** Runs [block] with a tree planted that records what the manager logs. */
    private suspend fun recordedLogs(block: suspend () -> Unit): RecordingTree {
        val tree = RecordingTree()
        Timber.plant(tree)
        try {
            block()
        } finally {
            Timber.uproot(tree)
        }
        return tree
    }

    /** Nothing in the log may carry an exception, a path, a word of the exception text or the session identifier. */
    private fun assertLogIsClean(tree: RecordingTree) {
        assertTrue(tree.entries.isNotEmpty(), "nothing was logged")
        tree.entries.forEach { entry ->
            assertNull(entry.throwable, "an exception was passed to the log: ${entry.message}")
            assertFalse(entry.message.contains("secret"), entry.message)
            assertFalse(entry.message.contains("/home"), entry.message)
            assertFalse(entry.message.contains("db.interne"), entry.message)
            assertFalse(entry.message.contains(sid), entry.message)
        }
    }

    @Test
    fun `no connection that drops during a file operation passes the exception to the log, nor its text`() = runTest {
        val sftp = openedClient()
        val dropped = IOException("Cannot read /home/user/secret.txt")
        every { sftp.canonicalize(".") } throws dropped
        every { sftp.ls(any<String>()) } throws dropped
        every { sftp.rm(any<String>()) } throws dropped
        every { sftp.chmod(any<String>(), any<Int>()) } throws dropped
        every { sftp.open(any<String>()) } throws dropped
        every { sftp.stat(any<String>()) } throws dropped

        val tree = recordedLogs {
            sftpManager.getHomeDirectory(sid)
            sftpManager.listDirectory(sid, "/home/user")
            sftpManager.delete(sid, "/home/user/secret.txt")
            sftpManager.deleteRecursive(sid, "/home/user/secret")
            sftpManager.chmod(sid, "/home/user/secret.txt", 420)
            sftpManager.readPreview(sid, "/home/user/secret.txt", 10L)
            sftpManager.readFile(sid, "/home/user/secret.txt", ByteArrayOutputStream()) { _, _ -> }
            sftpManager.stat(sid, "/home/user/secret.txt")
        }

        assertLogIsClean(tree)
        assertTrue(tree.entries.any { it.message.contains("IOException") }, "the class of the failure is not logged")
    }

    @Test
    fun `no refusal of the server during a file operation passes the exception to the log, nor its text`() = runTest {
        val sftp = openedClient()
        val refused = SFTPException(Response.StatusCode.PERMISSION_DENIED, "/home/user/secret.txt: Permission denied")
        every { sftp.ls(any<String>()) } throws refused
        every { sftp.rm(any<String>()) } throws refused
        every { sftp.chmod(any<String>(), any<Int>()) } throws refused
        every { sftp.open(any<String>()) } throws refused

        val tree = recordedLogs {
            sftpManager.listDirectory(sid, "/home/user")
            sftpManager.delete(sid, "/home/user/secret.txt")
            sftpManager.deleteRecursive(sid, "/home/user/secret")
            sftpManager.chmod(sid, "/home/user/secret.txt", 420)
            sftpManager.readPreview(sid, "/home/user/secret.txt", 10L)
            sftpManager.readFile(sid, "/home/user/secret.txt", ByteArrayOutputStream()) { _, _ -> }
        }

        assertLogIsClean(tree)
        assertTrue(tree.entries.any { it.message.contains("status=PERMISSION_DENIED") }, "the status is not logged")
    }

    @Test
    fun `no unexpected failure of a file operation passes the exception to the log, nor its text`() = runTest {
        val sftp = openedClient()
        val unexpected = IllegalStateException("db.interne.example secret detail")
        every { sftp.ls(any<String>()) } throws unexpected
        every { sftp.open(any<String>()) } throws unexpected
        every { sftp.stat(any<String>()) } throws unexpected

        val tree = recordedLogs {
            sftpManager.listDirectory(sid, "/home/user")
            sftpManager.readPreview(sid, "/home/user/a.txt", 10L)
            sftpManager.readFile(sid, "/home/user/a.txt", ByteArrayOutputStream()) { _, _ -> }
            sftpManager.stat(sid, "/home/user/a.txt")
        }

        assertLogIsClean(tree)
        assertTrue(tree.entries.any { it.message.contains("IllegalStateException") }, "the class of the failure is not logged")
    }

    @Test
    fun `a remote file that does not close is logged with its class only, after a preview and after a download`() = runTest {
        val sftp = openedClient()
        val remote = mockk<RemoteFile>(relaxed = true)
        every { sftp.open(any<String>()) } returns remote
        every { remote.length() } returns 3L
        every { remote.close() } throws IOException("Cannot close /home/user/secret.txt")

        val tree = recordedLogs {
            sftpManager.readPreview(sid, "/home/user/secret.txt", 10L)
            sftpManager.readFile(sid, "/home/user/secret.txt", ByteArrayOutputStream()) { _, _ -> }
        }

        assertLogIsClean(tree)
        assertEquals(2, tree.entries.count { it.message.contains("Error closing a remote file") && it.message.contains("IOException") })
    }

    @Test
    fun `a client that does not close is logged with its class only`() = runTest {
        val sftp = openedClient()
        every { sftp.close() } throws IOException("Cannot close /home/user/secret.txt")

        val tree = recordedLogs {
            assertEquals(SFTP_UNKNOWN_MESSAGE, sftpManager.closeSftp(sid).messageOfError())
        }

        assertLogIsClean(tree)
        assertTrue(tree.entries.any { it.message.contains("IOException") }, "the class of the failure is not logged")
    }
}
