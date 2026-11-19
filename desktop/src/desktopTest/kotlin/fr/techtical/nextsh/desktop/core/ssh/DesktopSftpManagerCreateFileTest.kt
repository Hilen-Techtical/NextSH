// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.ssh

import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_client_not_ready
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_connection_lost
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_create_file_failed
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_invalid_file_name
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_invalid_path
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_no_space
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_not_found
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_permission_denied
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_target_exists
import fr.techtical.nextsh.shared.domain.model.SshErrorCode
import fr.techtical.nextsh.shared.domain.model.SshResult
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import org.jetbrains.compose.resources.getString
import java.io.IOException
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [DesktopSftpManager.createFile] against a mocked SSHJ [SFTPClient]. The mock is
 * strict on purpose: any call the implementation was not expected to make (an open
 * with TRUNC, a stat on a refused name...) fails the test. Messages are compared
 * with the resources, never with a literal: the text depends on the machine language.
 */
class DesktopSftpManagerCreateFileTest {

    private val sftp = mockk<SFTPClient>()
    private val remoteFile = mockk<RemoteFile>(relaxed = true)

    /** A manager whose [SESSION] already has the mocked [sftp] client open. */
    private suspend fun openedManager(): DesktopSftpManager {
        val ssh = mockk<SSHClient>()
        every { ssh.newSFTPClient() } returns sftp
        val terminal = mockk<DesktopSshTerminalSession>()
        every { terminal.getSshClient() } returns ssh
        val sessions = mockk<DesktopSshSessionManager>()
        every { sessions.getTerminalSession(SESSION) } returns terminal
        return DesktopSftpManager(sessions).also { assertIs<SshResult.Success<*>>(it.openSftp(SESSION)) }
    }

    private fun assertError(expectedMessage: String, result: SshResult<Unit>) {
        val error = assertIs<SshResult.Error>(result)
        assertEquals(SshErrorCode.UNKNOWN, error.code)
        assertEquals(expectedMessage, error.message)
    }

    // ── Success ───────────────────────────────────────────────────────────────

    @Test
    fun `creates the file with WRITE, CREAT and EXCL, never TRUNC, and closes the remote file`() = runTest {
        val manager = openedManager()
        val modes = slot<Set<OpenMode>>()
        every { sftp.statExistence("/home/t/notes.txt") } returns null
        every { sftp.open("/home/t/notes.txt", capture(modes)) } returns remoteFile

        val result = manager.createFile(SESSION, "/home/t", "notes.txt")

        assertEquals<SshResult<Unit>>(SshResult.Success(Unit), result)
        assertEquals(setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL), modes.captured)
        assertFalse(OpenMode.TRUNC in modes.captured, "TRUNC would wipe a file created in the meantime")
        verify(exactly = 1) { remoteFile.close() }
        verifyOrder {
            sftp.statExistence("/home/t/notes.txt")
            sftp.open("/home/t/notes.txt", any())
        }
    }

    @Test
    fun `a file at the root is created at the root`() = runTest {
        val manager = openedManager()
        every { sftp.statExistence("/notes.txt") } returns null
        every { sftp.open("/notes.txt", any()) } returns remoteFile

        assertEquals<SshResult<Unit>>(SshResult.Success(Unit), manager.createFile(SESSION, "/", "notes.txt"))
        verify(exactly = 1) { sftp.open("/notes.txt", any()) }
    }

    @Test
    fun `a trailing slash on the parent folder does not leak into the path`() = runTest {
        val manager = openedManager()
        every { sftp.statExistence("/home/t/notes.txt") } returns null
        every { sftp.open("/home/t/notes.txt", any()) } returns remoteFile

        assertEquals<SshResult<Unit>>(SshResult.Success(Unit), manager.createFile(SESSION, "/home/t/", "notes.txt"))
        verify(exactly = 1) { sftp.open("/home/t/notes.txt", any()) }
    }

    @Test
    fun `a plus or a percent in the name is kept literally, not URL-decoded`() = runTest {
        val manager = openedManager()
        every { sftp.statExistence(any()) } returns null
        every { sftp.open(any(), any()) } returns remoteFile

        for (name in listOf("a+b.txt", "100%.txt", "%41.txt")) {
            assertEquals<SshResult<Unit>>(SshResult.Success(Unit), manager.createFile(SESSION, "/home/t", name))
            verify(exactly = 1) { sftp.open("/home/t/$name", any()) }
        }
    }

    @Test
    fun `a failing close does not turn a created file into an error`() = runTest {
        val manager = openedManager()
        every { sftp.statExistence(any()) } returns null
        every { sftp.open(any(), any()) } returns remoteFile
        every { remoteFile.close() } throws IOException("close failed")

        assertEquals<SshResult<Unit>>(SshResult.Success(Unit), manager.createFile(SESSION, "/home/t", "notes.txt"))
    }

    // ── Existing entry ────────────────────────────────────────────────────────

    @Test
    fun `an existing entry is refused before any open`() = runTest {
        val manager = openedManager()
        every { sftp.statExistence("/home/t/notes.txt") } returns FileAttributes.EMPTY

        val result = manager.createFile(SESSION, "/home/t", "notes.txt")

        assertError(getString(Res.string.sftp_error_target_exists), result)
        verify(exactly = 0) { sftp.open(any(), any()) }
    }

    @Test
    fun `the server refusing the EXCL open as already existing gives the exists message`() = runTest {
        val manager = openedManager()
        every { sftp.statExistence(any()) } returns null
        every { sftp.open(any(), any()) } throws SFTPException("File exists")

        assertError(
            getString(Res.string.sftp_error_target_exists),
            manager.createFile(SESSION, "/home/t", "notes.txt"),
        )
        verify(exactly = 0) { remoteFile.close() }
    }

    // ── Server and connection errors ──────────────────────────────────────────

    @Test
    fun `permission denied is mapped to the permission message`() = runTest {
        val manager = openedManager()
        every { sftp.statExistence(any()) } returns null
        every { sftp.open(any(), any()) } throws SFTPException("Permission denied")

        assertError(
            getString(Res.string.sftp_error_permission_denied),
            manager.createFile(SESSION, "/root", "notes.txt"),
        )
    }

    @Test
    fun `an unclassified server failure gives the create failed message`() = runTest {
        val manager = openedManager()
        every { sftp.statExistence(any()) } returns null
        every { sftp.open(any(), any()) } throws SFTPException("Failure")

        assertError(
            getString(Res.string.sftp_error_create_file_failed),
            manager.createFile(SESSION, "/home/t", "notes.txt"),
        )
    }

    @Test
    fun `a connection lost during the open gives the connection lost message`() = runTest {
        val manager = openedManager()
        every { sftp.statExistence(any()) } returns null
        every { sftp.open(any(), any()) } throws IOException("socket closed")

        assertError(
            getString(Res.string.sftp_error_connection_lost),
            manager.createFile(SESSION, "/home/t", "notes.txt"),
        )
    }

    @Test
    fun `a connection lost during the existence check gives the connection lost message`() = runTest {
        val manager = openedManager()
        every { sftp.statExistence(any()) } throws IOException("socket closed")

        val result = manager.createFile(SESSION, "/home/t", "notes.txt")

        assertError(getString(Res.string.sftp_error_connection_lost), result)
        verify(exactly = 0) { sftp.open(any(), any()) }
    }

    @Test
    fun `a channel lost during the existence check gives the connection lost message and opens nothing`() = runTest {
        val manager = openedManager()
        every { sftp.statExistence(any()) } throws SFTPException("EOF while reading packet")

        val result = manager.createFile(SESSION, "/home/t", "notes.txt")

        assertError(getString(Res.string.sftp_error_connection_lost), result)
        verify(exactly = 0) { sftp.open(any(), any()) }
    }

    @Test
    fun `a timeout of the existence check gives the connection lost message and opens nothing`() = runTest {
        val manager = openedManager()
        every { sftp.statExistence(any()) } throws SFTPException(TimeoutException("Timeout expired: 30000 MILLISECONDS"))

        val result = manager.createFile(SESSION, "/home/t", "notes.txt")

        assertError(getString(Res.string.sftp_error_connection_lost), result)
        verify(exactly = 0) { sftp.open(any(), any()) }
    }

    @Test
    fun `a lookup the server refuses keeps the server message and opens nothing`() = runTest {
        val manager = openedManager()
        val answers = listOf(
            SFTPException(Response.StatusCode.PERMISSION_DENIED, "Permission denied") to getString(Res.string.sftp_error_permission_denied),
            SFTPException(Response.StatusCode.NO_SUCH_PATH, "No such path") to getString(Res.string.sftp_error_not_found),
        )

        for ((answer, message) in answers) {
            every { sftp.statExistence(any()) } throws answer

            assertError(message, manager.createFile(SESSION, "/home/t", "notes.txt"))
        }
        verify(exactly = 0) { sftp.open(any(), any()) }
    }

    @Test
    fun `an unexpected exception gives the create failed message instead of escaping`() = runTest {
        val manager = openedManager()
        every { sftp.statExistence(any()) } returns null
        every { sftp.open(any(), any()) } throws IllegalStateException("boom")

        assertError(
            getString(Res.string.sftp_error_create_file_failed),
            manager.createFile(SESSION, "/home/t", "notes.txt"),
        )
    }

    @Test
    fun `a cancellation is rethrown instead of being turned into an error message`() = runTest {
        val manager = openedManager()
        every { sftp.statExistence(any()) } throws CancellationException("cancelled")

        assertFailsWith<CancellationException> { manager.createFile(SESSION, "/home/t", "notes.txt") }
        verify(exactly = 0) { sftp.open(any(), any()) }
    }

    // ── Error mapping: status code first, then the text ───────────────────────

    /** Makes the open fail with [error] and returns the message `createFile` reports. */
    private suspend fun messageForOpenFailure(error: SFTPException): String {
        val manager = openedManager()
        every { sftp.statExistence(any()) } returns null
        every { sftp.open(any(), any()) } throws error
        val result = manager.createFile(SESSION, "/home/t", "notes.txt")
        return assertIs<SshResult.Error>(result).message
    }

    @Test
    fun `the SFTP status code decides the message even when the text says nothing useful`() = runTest {
        // OpenSSH answers a generic text; the code is what the server really said.
        val cases = mapOf(
            Response.StatusCode.NO_SUCH_FILE to getString(Res.string.sftp_error_not_found),
            Response.StatusCode.NO_SUCH_PATH to getString(Res.string.sftp_error_not_found),
            Response.StatusCode.PERMISSION_DENIED to getString(Res.string.sftp_error_permission_denied),
            Response.StatusCode.FILE_ALREADY_EXISTS to getString(Res.string.sftp_error_target_exists),
            Response.StatusCode.NO_SPACE_ON_FILESYSTEM to getString(Res.string.sftp_error_no_space),
        )

        for ((code, expected) in cases) {
            assertEquals(expected, messageForOpenFailure(SFTPException(code, "Failure")), "status $code")
        }
    }

    @Test
    fun `the status code wins over a misleading text`() = runTest {
        val message = messageForOpenFailure(SFTPException(Response.StatusCode.PERMISSION_DENIED, "File exists"))

        assertEquals(getString(Res.string.sftp_error_permission_denied), message)
    }

    @Test
    fun `without a status code, does not exist and no such file are not reported as already existing`() = runTest {
        val notFound = getString(Res.string.sftp_error_not_found)

        for (text in listOf("No such file", "Path does not exist", "The file doesn't exist", "NO SUCH PATH")) {
            assertEquals(notFound, messageForOpenFailure(SFTPException(text)), "text: $text")
        }
    }

    @Test
    fun `without a status code, an already existing entry is still reported as such`() = runTest {
        val exists = getString(Res.string.sftp_error_target_exists)

        for (text in listOf("File exists", "File already exists")) {
            assertEquals(exists, messageForOpenFailure(SFTPException(text)), "text: $text")
        }
    }

    // ── Refused input ─────────────────────────────────────────────────────────

    @Test
    fun `names that are not usable as is are refused without any SFTP call`() = runTest {
        val manager = openedManager()
        val invalidNames = listOf(
            ".",
            "..",
            "",
            " ",
            " leading",
            "trailing ",
            "back\\slash",
            "control\u0001char",
            // A C1 control (next line), and 128 two-byte letters: 128 characters, 256 bytes.
            "x\u0085y",
            "é".repeat(128),
            "x".repeat(256),
        )

        for (name in invalidNames) {
            val result = manager.createFile(SESSION, "/home/t", name)
            assertError(getString(Res.string.sftp_error_invalid_file_name), result)
        }
        confirmVerified(sftp)
    }

    @Test
    fun `a name carrying a separator is refused instead of landing in another folder`() = runTest {
        val manager = openedManager()
        val escapingNames = listOf(
            "a/b",
            "a/b.txt",
            "../x",
            "..\\x",
            "a\\b",
            "sub/../x",
            "/etc/x",
            "x/",
            "./x",
        )

        for (name in escapingNames) {
            val result = manager.createFile(SESSION, "/home/t", name)
            assertError(getString(Res.string.sftp_error_invalid_file_name), result)
        }
        confirmVerified(sftp)
    }

    @Test
    fun `ordinary names including dotfiles are accepted by the name check`() {
        val accepted = listOf("notes.txt", ".bashrc", "...", "..hidden", "a b", "été.txt", "x".repeat(255), "é".repeat(127))
        for (name in accepted) {
            assertTrue(isValidEntryName(name), "should be accepted: ${name.take(20)}")
        }
        for (name in listOf(".", "..", "", " ", "a/b", "a\\b", "../x", "x/", "x\u0085y", "é".repeat(128))) {
            assertFalse(isValidEntryName(name), "should be refused: '$name'")
        }
    }

    @Test
    fun `a parent that climbs above the root is refused with the invalid path message`() = runTest {
        val manager = openedManager()

        val result = manager.createFile(SESSION, "/..", "notes.txt")

        assertError(getString(Res.string.sftp_error_invalid_path), result)
        confirmVerified(sftp)
    }

    @Test
    fun `an empty parent folder is refused instead of silently meaning the root`() = runTest {
        val manager = openedManager()

        val result = manager.createFile(SESSION, "", "notes.txt")

        assertError(getString(Res.string.sftp_error_invalid_path), result)
        confirmVerified(sftp)
    }

    @Test
    fun `an unknown session reports the client not ready message`() = runTest {
        val manager = DesktopSftpManager(mockk())

        val result = manager.createFile("no-such-session", "/home/t", "notes.txt")

        assertError(getString(Res.string.sftp_error_client_not_ready), result)
    }

    private companion object {
        const val SESSION = "s1"
    }
}
