// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.ssh

import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_connection_lost
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_create_dir_failed
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_entry_name_invalid
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_invalid_folder_name
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_invalid_path
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_permission_denied
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_rename_failed
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_target_exists
import fr.techtical.nextsh.shared.domain.model.SshErrorCode
import fr.techtical.nextsh.shared.domain.model.SshResult
import io.mockk.Called
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import org.jetbrains.compose.resources.getString
import java.io.IOException
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The names of a new folder and of a renamed entry: a name that is not a bare name is refused (never
 * corrected, never resolved), the name is joined to its parent folder, and a name that is already taken
 * is looked up on the server before anything is created or renamed.
 *
 * The SFTP client is a strict mock: a request the test did not stub fails the test. Messages are compared
 * with the resources, never with a literal: the text depends on the language of the machine.
 */
class DesktopSftpManagerEntryNameTest {

    private val sessionId = "session-1"

    private val sftp = mockk<SFTPClient>()

    /** A manager whose [sessionId] already has the strict mock [sftp] open. */
    private suspend fun openedManager(): DesktopSftpManager {
        val ssh = mockk<SSHClient>()
        every { ssh.newSFTPClient() } returns sftp
        val terminal = mockk<DesktopSshTerminalSession>()
        every { terminal.getSshClient() } returns ssh
        val sessions = mockk<DesktopSshSessionManager>()
        every { sessions.getTerminalSession(sessionId) } returns terminal
        return DesktopSftpManager(sessions).also { assertIs<SshResult.Success<*>>(it.openSftp(sessionId)) }
    }

    private fun assertError(expectedMessage: String, result: SshResult<Unit>) {
        val error = assertIs<SshResult.Error>(result)
        assertEquals(SshErrorCode.UNKNOWN, error.code)
        assertEquals(expectedMessage, error.message)
    }

    /** What the server answers to the lstat of a name that is free: the status that says "nothing here". */
    private fun noSuchFile() = SFTPException(Response.StatusCode.NO_SUCH_FILE, "No such file")

    /** An entry as the server describes it, of the given type. */
    private fun attributesOf(type: FileMode.Type): FileAttributes {
        val attributes = mockk<FileAttributes>()
        every { attributes.type } returns type
        return attributes
    }

    /** Names that are not bare names. Control characters are written as escapes, never raw. */
    private val refusedNames = listOf(
        "",
        "   ",
        ".",
        "..",
        " .. ",
        "../x",
        "a/b",
        "a\\b",
        "/",
        "\\",
        "x/",
        "/x",
        "x\u0000y",
        "x\u0001y",
        "x\ny",
        "x\u007Fy",
        "x\u0085y",
        "x\u009By",
        "a".repeat(256),
        "é".repeat(128),
        " notes",
        "notes ",
    )

    /** Names that look like a path or an encoding, and are only characters of a name. */
    private val literalNames = listOf("x+y", "100%", "%2e%2e", "a%2Fb", "..%2F..%2Ftmp%2Fx", "a b", "..x", "x..", "...")

    // ── createDirectory ──────────────────────────────────────────────────────────

    @Test
    fun `createDirectory looks the name up, then makes the folder at the exact path`() = runTest {
        val manager = openedManager()
        every { sftp.statExistence("/home/user/new") } returns null
        every { sftp.mkdir("/home/user/new") } just Runs

        val result = manager.createDirectory(sessionId, "/home/user", "new")

        assertTrue(result is SshResult.Success)
        verifyOrder {
            sftp.statExistence("/home/user/new")
            sftp.mkdir("/home/user/new")
        }
    }

    @Test
    fun `createDirectory joins the name to the root with a single slash`() = runTest {
        val manager = openedManager()
        every { sftp.statExistence("/new") } returns null
        every { sftp.mkdir("/new") } just Runs

        val result = manager.createDirectory(sessionId, "/", "new")

        assertTrue(result is SshResult.Success)
        verify(exactly = 1) { sftp.mkdir("/new") }
    }

    @Test
    fun `createDirectory resolves the parent folder and never the name`() = runTest {
        val manager = openedManager()
        every { sftp.statExistence("/home/user/new") } returns null
        every { sftp.mkdir("/home/user/new") } just Runs

        // The parent is a path: its dots and its trailing slash are normalized. The name is not a path.
        val result = manager.createDirectory(sessionId, "/home/./user/docs/../", "new")

        assertTrue(result is SshResult.Success)
        verify(exactly = 1) { sftp.mkdir("/home/user/new") }
    }

    @Test
    fun `createDirectory refuses every name that is not a bare name, without any request`() = runTest {
        val manager = openedManager()
        val expected = getString(Res.string.sftp_error_invalid_folder_name)

        refusedNames.forEach { name ->
            val result = manager.createDirectory(sessionId, "/home/user", name)

            val error = assertIs<SshResult.Error>(result, "createDirectory refuses <$name>")
            assertEquals(expected, error.message, "<$name>")
        }
        verify { sftp wasNot Called }
    }

    @Test
    fun `createDirectory keeps plus, percent and encoded slash in the name, as characters of the name`() = runTest {
        val manager = openedManager()
        val created = mutableListOf<String>()
        every { sftp.statExistence(any()) } returns null
        every { sftp.mkdir(capture(created)) } just Runs

        literalNames.forEach { name ->
            val result = manager.createDirectory(sessionId, "/home/u", name)

            assertTrue(result is SshResult.Success, "createDirectory must accept <$name>")
        }

        assertEquals(literalNames.map { "/home/u/$it" }, created)
    }

    @Test
    fun `createDirectory refuses a name that is already taken and creates nothing`() = runTest {
        val manager = openedManager()
        // A file or a folder answers the lookup the same way: the name is taken, whatever it names.
        every { sftp.statExistence("/home/user/docs") } returns mockk<FileAttributes>()

        val result = manager.createDirectory(sessionId, "/home/user", "docs")

        assertError(getString(Res.string.sftp_error_target_exists), result)
        verify(exactly = 0) { sftp.mkdir(any<String>()) }
    }

    @Test
    fun `createDirectory says the folder was not created when the server only answers failure`() = runTest {
        val manager = openedManager()
        every { sftp.statExistence("/home/user/new") } returns null
        // A server of the SFTP version 3 refuses an existing target with a plain failure.
        every { sftp.mkdir("/home/user/new") } throws SFTPException("Failure")

        val result = manager.createDirectory(sessionId, "/home/user", "new")

        assertError(getString(Res.string.sftp_error_create_dir_failed), result)
    }

    @Test
    fun `createDirectory says permission denied when the server refuses for lack of rights`() = runTest {
        val manager = openedManager()
        every { sftp.statExistence("/root/new") } returns null
        every { sftp.mkdir("/root/new") } throws SFTPException("Permission denied")

        val result = manager.createDirectory(sessionId, "/root", "new")

        assertError(getString(Res.string.sftp_error_permission_denied), result)
    }

    @Test
    fun `createDirectory says the connection is lost when mkdir drops`() = runTest {
        val manager = openedManager()
        every { sftp.statExistence("/home/user/new") } returns null
        every { sftp.mkdir("/home/user/new") } throws IOException("Broken pipe")

        val result = manager.createDirectory(sessionId, "/home/user", "new")

        assertError(getString(Res.string.sftp_error_connection_lost), result)
    }

    @Test
    fun `createDirectory says the connection is lost when the lookup drops, and makes nothing`() = runTest {
        val manager = openedManager()
        every { sftp.statExistence("/home/user/new") } throws IOException("Broken pipe")

        val result = manager.createDirectory(sessionId, "/home/user", "new")

        assertError(getString(Res.string.sftp_error_connection_lost), result)
        verify(exactly = 0) { sftp.mkdir(any<String>()) }
    }

    @Test
    fun `createDirectory stops on a channel lost during the lookup and makes nothing`() = runTest {
        val manager = openedManager()
        // SSHJ reports a channel lost in the middle of a request as an SFTPException with the status UNKNOWN.
        every { sftp.statExistence("/home/user/new") } throws SFTPException("EOF while reading packet")

        val result = manager.createDirectory(sessionId, "/home/user", "new")

        assertError(getString(Res.string.sftp_error_connection_lost), result)
        verify(exactly = 0) { sftp.mkdir(any<String>()) }
    }

    @Test
    fun `createDirectory stops on a timeout of the lookup and makes nothing`() = runTest {
        val manager = openedManager()
        // A server that stays silent: the timeout is the cause of the SFTPException.
        every { sftp.statExistence("/home/user/new") } throws
            SFTPException(TimeoutException("Timeout expired: 30000 MILLISECONDS"))

        val result = manager.createDirectory(sessionId, "/home/user", "new")

        assertError(getString(Res.string.sftp_error_connection_lost), result)
        verify(exactly = 0) { sftp.mkdir(any<String>()) }
    }

    @Test
    fun `createDirectory makes the folder all the same when the server refuses the lookup for lack of rights`() = runTest {
        val manager = openedManager()
        // A folder the user may write in but not read (a drop box): the lookup is refused, mkdir is not.
        every { sftp.statExistence("/srv/dropbox/new") } throws
            SFTPException(Response.StatusCode.PERMISSION_DENIED, "Permission denied")
        every { sftp.mkdir("/srv/dropbox/new") } just Runs

        val result = manager.createDirectory(sessionId, "/srv/dropbox", "new")

        assertTrue(result is SshResult.Success)
        verify(exactly = 1) { sftp.mkdir("/srv/dropbox/new") }
    }

    @Test
    fun `createDirectory makes the folder all the same when the lookup fails for another reason`() = runTest {
        val manager = openedManager()
        every { sftp.statExistence("/home/user/new") } throws SFTPException(Response.StatusCode.FAILURE, "Failure")
        every { sftp.mkdir("/home/user/new") } just Runs

        val result = manager.createDirectory(sessionId, "/home/user", "new")

        assertTrue(result is SshResult.Success)
        verify(exactly = 1) { sftp.mkdir("/home/user/new") }
    }

    @Test
    fun `createDirectory says permission denied when the lookup and mkdir are both refused`() = runTest {
        val manager = openedManager()
        every { sftp.statExistence("/root/new") } throws
            SFTPException(Response.StatusCode.PERMISSION_DENIED, "Permission denied")
        every { sftp.mkdir("/root/new") } throws SFTPException(Response.StatusCode.PERMISSION_DENIED, "Permission denied")

        val result = manager.createDirectory(sessionId, "/root", "new")

        assertError(getString(Res.string.sftp_error_permission_denied), result)
        verify(exactly = 1) { sftp.mkdir("/root/new") }
    }

    @Test
    fun `createDirectory turns an unexpected exception into the create failed message`() = runTest {
        val manager = openedManager()
        every { sftp.statExistence("/home/user/new") } returns null
        every { sftp.mkdir("/home/user/new") } throws IllegalStateException("boom")

        val result = manager.createDirectory(sessionId, "/home/user", "new")

        assertError(getString(Res.string.sftp_error_create_dir_failed), result)
    }

    @Test
    fun `createDirectory does not swallow a cancellation`() = runTest {
        val manager = openedManager()
        every { sftp.statExistence("/home/user/new") } throws CancellationException("cancelled")

        assertFailsWith<CancellationException> { manager.createDirectory(sessionId, "/home/user", "new") }
        verify(exactly = 0) { sftp.mkdir(any<String>()) }
    }

    @Test
    fun `createDirectory refuses a parent folder above the root without any request`() = runTest {
        val manager = openedManager()

        val result = manager.createDirectory(sessionId, "/../etc", "new")

        assertError(getString(Res.string.sftp_error_invalid_path), result)
        verify { sftp wasNot Called }
    }

    // ── rename ───────────────────────────────────────────────────────────────────

    @Test
    fun `rename looks the new name up with lstat, then renames in the same folder`() = runTest {
        val manager = openedManager()
        every { sftp.lstat("/home/user/docs/new.txt") } throws noSuchFile()
        every { sftp.rename("/home/user/docs/old.txt", "/home/user/docs/new.txt") } just Runs

        val result = manager.rename(sessionId, "/home/user/docs/old.txt", "new.txt")

        assertTrue(result is SshResult.Success)
        verifyOrder {
            sftp.lstat("/home/user/docs/new.txt")
            sftp.rename("/home/user/docs/old.txt", "/home/user/docs/new.txt")
        }
        // lstat does not follow a link, statExistence does: the lookup of a rename is the first.
        verify(exactly = 0) { sftp.statExistence(any<String>()) }
    }

    @Test
    fun `rename refuses every name that is not a bare name, without any request`() = runTest {
        val manager = openedManager()
        val expected = getString(Res.string.sftp_error_entry_name_invalid)

        refusedNames.forEach { name ->
            val result = manager.rename(sessionId, "/home/user/docs/f.txt", name)

            val error = assertIs<SshResult.Error>(result, "rename refuses <$name>")
            assertEquals(expected, error.message, "<$name>")
        }
        // In particular "..": it used to move /home/user/docs/f.txt onto /home/user.
        verify { sftp wasNot Called }
    }

    @Test
    fun `rename keeps the new path in the parent folder, whatever the name`() = runTest {
        val manager = openedManager()
        val olds = mutableListOf<String>()
        val news = mutableListOf<String>()
        every { sftp.lstat(any()) } throws noSuchFile()
        every { sftp.rename(capture(olds), capture(news)) } just Runs

        literalNames.forEach { name ->
            val result = manager.rename(sessionId, "/home/user/docs/f.txt", name)

            assertTrue(result is SshResult.Success, "rename must accept <$name>")
        }

        assertEquals(literalNames.map { "/home/user/docs/$it" }, news)
        assertTrue(news.all { it.substringBeforeLast('/') == "/home/user/docs" })
        assertTrue(olds.all { it == "/home/user/docs/f.txt" })
    }

    @Test
    fun `rename resolves the old path and never the new name`() = runTest {
        val manager = openedManager()
        every { sftp.lstat("/home/user/g.txt") } throws noSuchFile()
        every { sftp.rename("/home/user/f.txt", "/home/user/g.txt") } just Runs

        val result = manager.rename(sessionId, "/home/user/docs/../f.txt", "g.txt")

        assertTrue(result is SshResult.Success)
        verify(exactly = 1) { sftp.rename("/home/user/f.txt", "/home/user/g.txt") }
    }

    @Test
    fun `rename refuses a name that is already taken and renames nothing`() = runTest {
        val manager = openedManager()
        // A file or a folder answers the lookup the same way: the name is taken, whatever it names.
        every { sftp.lstat("/home/user/taken.txt") } returns attributesOf(FileMode.Type.REGULAR)
        every { sftp.lstat("/home/user/taken-folder") } returns attributesOf(FileMode.Type.DIRECTORY)
        val expected = getString(Res.string.sftp_error_target_exists)

        listOf("taken.txt", "taken-folder").forEach { name ->
            val result = manager.rename(sessionId, "/home/user/old.txt", name)

            assertError(expected, result)
        }
        verify(exactly = 0) { sftp.rename(any<String>(), any<String>()) }
    }

    @Test
    fun `rename refuses a name held by a broken symbolic link and renames nothing`() = runTest {
        val manager = openedManager()
        // stat follows the link and finds nothing, lstat finds the link itself: a server that replaces
        // on rename would have replaced it, the name is taken.
        every { sftp.lstat("/home/user/link") } returns attributesOf(FileMode.Type.SYMLINK)

        val result = manager.rename(sessionId, "/home/user/old.txt", "link")

        assertError(getString(Res.string.sftp_error_target_exists), result)
        verify(exactly = 0) { sftp.rename(any<String>(), any<String>()) }
        verify(exactly = 0) { sftp.statExistence(any<String>()) }
    }

    @Test
    fun `rename takes the status no such path for a free name as well`() = runTest {
        val manager = openedManager()
        every { sftp.lstat("/home/user/new.txt") } throws SFTPException(Response.StatusCode.NO_SUCH_PATH, "No such path")
        every { sftp.rename("/home/user/old.txt", "/home/user/new.txt") } just Runs

        val result = manager.rename(sessionId, "/home/user/old.txt", "new.txt")

        assertTrue(result is SshResult.Success)
        verify(exactly = 1) { sftp.rename("/home/user/old.txt", "/home/user/new.txt") }
    }

    @Test
    fun `rename does not conclude from a lookup the server refuses, and renames nothing`() = runTest {
        val manager = openedManager()
        every { sftp.lstat("/home/user/locked.txt") } throws
            SFTPException(Response.StatusCode.PERMISSION_DENIED, "Permission denied")
        every { sftp.lstat("/home/user/odd.txt") } throws SFTPException(Response.StatusCode.FAILURE, "Failure")
        every { sftp.lstat("/home/user/unsupported.txt") } throws
            SFTPException(Response.StatusCode.OP_UNSUPPORTED, "Unsupported")

        val refused = manager.rename(sessionId, "/home/user/old.txt", "locked.txt")
        val failed = manager.rename(sessionId, "/home/user/old.txt", "odd.txt")
        val unsupported = manager.rename(sessionId, "/home/user/old.txt", "unsupported.txt")

        assertError(getString(Res.string.sftp_error_permission_denied), refused)
        assertError(getString(Res.string.sftp_error_rename_failed), failed)
        // lstat is mandatory in SFTP v3: a server that does not support it proves nothing about the name.
        assertError(getString(Res.string.sftp_error_rename_failed), unsupported)
        verify(exactly = 0) { sftp.rename(any<String>(), any<String>()) }
    }

    @Test
    fun `rename that changes only the case is left to the server, whatever the lookup says`() = runTest {
        val manager = openedManager()
        // A server that ignores the case: the lookup of the new name would find the entry itself.
        every { sftp.lstat("/home/user/rapport.docx") } returns attributesOf(FileMode.Type.REGULAR)
        every { sftp.rename("/home/user/Rapport.docx", "/home/user/rapport.docx") } just Runs

        val result = manager.rename(sessionId, "/home/user/Rapport.docx", "rapport.docx")

        assertTrue(result is SshResult.Success)
        verify(exactly = 1) { sftp.rename("/home/user/Rapport.docx", "/home/user/rapport.docx") }
        verify(exactly = 0) { sftp.lstat(any<String>()) }
        verify(exactly = 0) { sftp.statExistence(any<String>()) }
    }

    @Test
    fun `rename that changes only the case reports the refusal of the server`() = runTest {
        val manager = openedManager()
        // A server that tells the two names apart and holds both: it is the one to refuse.
        every { sftp.rename("/home/user/Rapport.docx", "/home/user/rapport.docx") } throws
            SFTPException(Response.StatusCode.FAILURE, "Failure")

        val result = manager.rename(sessionId, "/home/user/Rapport.docx", "rapport.docx")

        assertError(getString(Res.string.sftp_error_rename_failed), result)
    }

    @Test
    fun `rename that changes the case and more is still looked up`() = runTest {
        val manager = openedManager()
        every { sftp.lstat("/home/user/rapport2.docx") } returns attributesOf(FileMode.Type.REGULAR)

        val result = manager.rename(sessionId, "/home/user/Rapport.docx", "rapport2.docx")

        assertError(getString(Res.string.sftp_error_target_exists), result)
        verify(exactly = 0) { sftp.rename(any<String>(), any<String>()) }
    }

    @Test
    fun `rename to the name the entry already has succeeds without any request`() = runTest {
        val manager = openedManager()

        val result = manager.rename(sessionId, "/home/user/docs/f.txt", "f.txt")

        assertTrue(result is SshResult.Success)
        verify { sftp wasNot Called }
    }

    @Test
    fun `rename moves an entry of the root to a path of the root`() = runTest {
        val manager = openedManager()
        every { sftp.lstat("/b.txt") } throws noSuchFile()
        every { sftp.rename("/a.txt", "/b.txt") } just Runs

        val result = manager.rename(sessionId, "/a.txt", "b.txt")

        assertTrue(result is SshResult.Success)
        verify(exactly = 1) { sftp.rename("/a.txt", "/b.txt") }
    }

    @Test
    fun `rename refuses the root, which has no name to change`() = runTest {
        val manager = openedManager()

        val result = manager.rename(sessionId, "/", "x")

        assertError(getString(Res.string.sftp_error_invalid_path), result)
        verify { sftp wasNot Called }
    }

    @Test
    fun `rename refuses an old path above the root without any request`() = runTest {
        val manager = openedManager()

        val result = manager.rename(sessionId, "/../etc/passwd", "x")

        assertError(getString(Res.string.sftp_error_invalid_path), result)
        verify { sftp wasNot Called }
    }

    @Test
    fun `rename says the entry was not renamed when the server only answers failure`() = runTest {
        val manager = openedManager()
        every { sftp.lstat("/home/user/new.txt") } throws noSuchFile()
        every { sftp.rename("/home/user/old.txt", "/home/user/new.txt") } throws SFTPException("Failure")

        val result = manager.rename(sessionId, "/home/user/old.txt", "new.txt")

        assertError(getString(Res.string.sftp_error_rename_failed), result)
    }

    @Test
    fun `rename says permission denied when the server refuses for lack of rights`() = runTest {
        val manager = openedManager()
        every { sftp.lstat("/root/new.txt") } throws noSuchFile()
        every { sftp.rename("/root/old.txt", "/root/new.txt") } throws SFTPException("Permission denied")

        val result = manager.rename(sessionId, "/root/old.txt", "new.txt")

        assertError(getString(Res.string.sftp_error_permission_denied), result)
    }

    @Test
    fun `rename says the connection is lost when the lookup drops, and renames nothing`() = runTest {
        val manager = openedManager()
        every { sftp.lstat("/home/user/new.txt") } throws IOException("Broken pipe")

        val result = manager.rename(sessionId, "/home/user/old.txt", "new.txt")

        assertError(getString(Res.string.sftp_error_connection_lost), result)
        verify(exactly = 0) { sftp.rename(any<String>(), any<String>()) }
    }

    @Test
    fun `rename says the connection is lost when the lookup reports a lost channel, and renames nothing`() = runTest {
        val manager = openedManager()
        // SSHJ reports a channel lost in the middle of a request as an SFTPException with the status UNKNOWN.
        every { sftp.lstat("/home/user/new.txt") } throws SFTPException("EOF while reading packet")

        val result = manager.rename(sessionId, "/home/user/old.txt", "new.txt")

        assertError(getString(Res.string.sftp_error_connection_lost), result)
        verify(exactly = 0) { sftp.rename(any<String>(), any<String>()) }
    }

    @Test
    fun `rename says the connection is lost when the lookup times out, and renames nothing`() = runTest {
        val manager = openedManager()
        // A server that stays silent: the timeout is the cause of the SFTPException.
        every { sftp.lstat("/home/user/new.txt") } throws
            SFTPException(TimeoutException("Timeout expired: 30000 MILLISECONDS"))

        val result = manager.rename(sessionId, "/home/user/old.txt", "new.txt")

        assertError(getString(Res.string.sftp_error_connection_lost), result)
        verify(exactly = 0) { sftp.rename(any<String>(), any<String>()) }
    }

    @Test
    fun `rename says the connection is lost when the rename drops`() = runTest {
        val manager = openedManager()
        every { sftp.lstat("/home/user/new.txt") } throws noSuchFile()
        every { sftp.rename("/home/user/old.txt", "/home/user/new.txt") } throws IOException("Broken pipe")

        val result = manager.rename(sessionId, "/home/user/old.txt", "new.txt")

        assertError(getString(Res.string.sftp_error_connection_lost), result)
    }

    @Test
    fun `rename turns an unexpected exception into the rename failed message`() = runTest {
        val manager = openedManager()
        every { sftp.lstat("/home/user/new.txt") } throws noSuchFile()
        every { sftp.rename("/home/user/old.txt", "/home/user/new.txt") } throws IllegalStateException("boom")

        val result = manager.rename(sessionId, "/home/user/old.txt", "new.txt")

        assertError(getString(Res.string.sftp_error_rename_failed), result)
    }

    @Test
    fun `rename does not swallow a cancellation`() = runTest {
        val manager = openedManager()
        every { sftp.lstat("/home/user/new.txt") } throws CancellationException("cancelled")

        assertFailsWith<CancellationException> { manager.rename(sessionId, "/home/user/old.txt", "new.txt") }
        verify(exactly = 0) { sftp.rename(any<String>(), any<String>()) }
    }
}
