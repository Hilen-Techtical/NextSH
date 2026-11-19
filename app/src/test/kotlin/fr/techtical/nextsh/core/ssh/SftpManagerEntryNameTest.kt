// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.shared.domain.model.SshResult
import io.mockk.Called
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.io.IOException
import java.util.concurrent.TimeoutException

/**
 * The names of a new folder and of a renamed entry: a name that is not a bare name is refused (never
 * corrected, never resolved), the name is joined to its parent folder, and a name that is already taken
 * is looked up on the server before anything is created or renamed.
 *
 * The SFTP client is a strict mock: a request the test did not stub fails the test.
 */
@ExtendWith(MockKExtension::class)
class SftpManagerEntryNameTest {

    @MockK
    private lateinit var sessionManager: SshSessionManager

    private lateinit var sftpManager: SftpManager

    private val sessionId = "session-1"

    @BeforeEach
    fun setUp() {
        every { sessionManager.addSessionEndListener(any()) } just Runs
        sftpManager = SftpManager(sessionManager)
    }

    /** A session with its SFTP client open. The client is a strict mock: a call the test did not stub fails. */
    private suspend fun openedSftp(): SFTPClient {
        val ssh = mockk<SSHClient>()
        val sftp = mockk<SFTPClient>()
        every { ssh.newSFTPClient() } returns sftp
        coEvery { sessionManager.getClient(sessionId) } returns ssh
        sftpManager.openSftp(sessionId)
        return sftp
    }

    private fun SshResult<*>.messageOfError(): String = (this as SshResult.Error).message

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
        val sftp = openedSftp()
        every { sftp.statExistence("/home/user/new") } returns null
        every { sftp.mkdir("/home/user/new") } just Runs

        val result = sftpManager.createDirectory(sessionId, "/home/user", "new")

        assertTrue(result is SshResult.Success)
        verifyOrder {
            sftp.statExistence("/home/user/new")
            sftp.mkdir("/home/user/new")
        }
    }

    @Test
    fun `createDirectory joins the name to the root with a single slash`() = runTest {
        val sftp = openedSftp()
        every { sftp.statExistence("/new") } returns null
        every { sftp.mkdir("/new") } just Runs

        val result = sftpManager.createDirectory(sessionId, "/", "new")

        assertTrue(result is SshResult.Success)
        verify(exactly = 1) { sftp.mkdir("/new") }
    }

    @Test
    fun `createDirectory resolves the parent folder and never the name`() = runTest {
        val sftp = openedSftp()
        every { sftp.statExistence("/home/user/new") } returns null
        every { sftp.mkdir("/home/user/new") } just Runs

        // The parent is a path: its dots and its trailing slash are normalized. The name is not a path.
        val result = sftpManager.createDirectory(sessionId, "/home/./user/docs/../", "new")

        assertTrue(result is SshResult.Success)
        verify(exactly = 1) { sftp.mkdir("/home/user/new") }
    }

    @Test
    fun `createDirectory refuses every name that is not a bare name, without any request`() = runTest {
        val sftp = openedSftp()

        refusedNames.forEach { name ->
            val result = sftpManager.createDirectory(sessionId, "/home/user", name)

            assertTrue(result is SshResult.Error, "createDirectory refuses <$name>")
            assertEquals(SFTP_FOLDER_NAME_INVALID_MESSAGE, result.messageOfError(), "<$name>")
        }
        verify { sftp wasNot Called }
    }

    @Test
    fun `createDirectory keeps plus, percent and encoded slash in the name, as characters of the name`() = runTest {
        val sftp = openedSftp()
        val created = mutableListOf<String>()
        every { sftp.statExistence(any()) } returns null
        every { sftp.mkdir(capture(created)) } just Runs

        literalNames.forEach { name ->
            val result = sftpManager.createDirectory(sessionId, "/home/u", name)

            assertTrue(result is SshResult.Success, "createDirectory must accept <$name>")
        }

        assertEquals(literalNames.map { "/home/u/$it" }, created)
    }

    @Test
    fun `createDirectory refuses a name that is already taken and creates nothing`() = runTest {
        val sftp = openedSftp()
        // A file or a folder answers the lookup the same way: the name is taken, whatever it names.
        every { sftp.statExistence("/home/user/docs") } returns mockk<FileAttributes>()

        val result = sftpManager.createDirectory(sessionId, "/home/user", "docs")

        assertEquals(SFTP_NAME_EXISTS_MESSAGE, result.messageOfError())
        verify(exactly = 0) { sftp.mkdir(any<String>()) }
    }

    @Test
    fun `createDirectory says the folder was not created when the server only answers failure`() = runTest {
        val sftp = openedSftp()
        every { sftp.statExistence("/home/user/new") } returns null
        // A server of the SFTP version 3 refuses an existing target with a plain failure.
        every { sftp.mkdir("/home/user/new") } throws SFTPException("Failure")

        val result = sftpManager.createDirectory(sessionId, "/home/user", "new")

        assertEquals(SFTP_MKDIR_FAILED_MESSAGE, result.messageOfError())
    }

    @Test
    fun `createDirectory says permission denied when the server refuses for lack of rights`() = runTest {
        val sftp = openedSftp()
        every { sftp.statExistence("/root/new") } returns null
        every { sftp.mkdir("/root/new") } throws SFTPException("Permission denied")

        val result = sftpManager.createDirectory(sessionId, "/root", "new")

        assertEquals(SFTP_PERMISSION_DENIED_MESSAGE, result.messageOfError())
    }

    @Test
    fun `createDirectory says the connection is lost when mkdir drops`() = runTest {
        val sftp = openedSftp()
        every { sftp.statExistence("/home/user/new") } returns null
        every { sftp.mkdir("/home/user/new") } throws IOException("Broken pipe")

        val result = sftpManager.createDirectory(sessionId, "/home/user", "new")

        assertEquals(SFTP_CONNECTION_LOST_MESSAGE, result.messageOfError())
    }

    @Test
    fun `createDirectory says the connection is lost when the lookup drops, and makes nothing`() = runTest {
        val sftp = openedSftp()
        every { sftp.statExistence("/home/user/new") } throws IOException("Broken pipe")

        val result = sftpManager.createDirectory(sessionId, "/home/user", "new")

        assertEquals(SFTP_CONNECTION_LOST_MESSAGE, result.messageOfError())
        verify(exactly = 0) { sftp.mkdir(any<String>()) }
    }

    @Test
    fun `createDirectory says the connection is lost when the lookup loses the channel, and makes nothing`() = runTest {
        // Opened once: the manager keeps its client, the loop only stubs it again.
        val sftp = openedSftp()
        for (lost in listOf(SFTPException("EOF while reading packet"), SFTPException(TimeoutException()))) {
            every { sftp.statExistence("/home/user/new") } throws lost

            val result = sftpManager.createDirectory(sessionId, "/home/user", "new")

            assertEquals(SFTP_CONNECTION_LOST_MESSAGE, result.messageOfError(), lost.toString())
            verify(exactly = 0) { sftp.mkdir(any<String>()) }
        }
    }

    @Test
    fun `createDirectory makes the folder all the same when the server refuses the lookup for lack of rights`() = runTest {
        val sftp = openedSftp()
        // A folder the user may write in but not read (a drop box): the lookup is refused, mkdir is not.
        every { sftp.statExistence("/srv/dropbox/new") } throws
            SFTPException(Response.StatusCode.PERMISSION_DENIED, "Permission denied")
        every { sftp.mkdir("/srv/dropbox/new") } just Runs

        val result = sftpManager.createDirectory(sessionId, "/srv/dropbox", "new")

        assertTrue(result is SshResult.Success)
        verify(exactly = 1) { sftp.mkdir("/srv/dropbox/new") }
    }

    @Test
    fun `createDirectory makes the folder all the same when the lookup is refused by a permission text without a status`() = runTest {
        val sftp = openedSftp()
        // No status (UNKNOWN) but a text that says permission: a refusal, not a lost channel.
        every { sftp.statExistence("/srv/dropbox/new") } throws SFTPException("Permission denied")
        every { sftp.mkdir("/srv/dropbox/new") } just Runs

        val result = sftpManager.createDirectory(sessionId, "/srv/dropbox", "new")

        assertTrue(result is SshResult.Success)
        verify(exactly = 1) { sftp.mkdir("/srv/dropbox/new") }
    }

    @Test
    fun `createDirectory makes the folder all the same when the lookup fails for another reason`() = runTest {
        val sftp = openedSftp()
        every { sftp.statExistence("/home/user/new") } throws
            SFTPException(Response.StatusCode.FAILURE, "Failure")
        every { sftp.mkdir("/home/user/new") } just Runs

        val result = sftpManager.createDirectory(sessionId, "/home/user", "new")

        assertTrue(result is SshResult.Success)
        verify(exactly = 1) { sftp.mkdir("/home/user/new") }
    }

    @Test
    fun `createDirectory says permission denied when the lookup and mkdir are both refused`() = runTest {
        val sftp = openedSftp()
        every { sftp.statExistence("/root/new") } throws
            SFTPException(Response.StatusCode.PERMISSION_DENIED, "Permission denied")
        every { sftp.mkdir("/root/new") } throws SFTPException(Response.StatusCode.PERMISSION_DENIED, "Permission denied")

        val result = sftpManager.createDirectory(sessionId, "/root", "new")

        assertEquals(SFTP_PERMISSION_DENIED_MESSAGE, result.messageOfError())
        verify(exactly = 1) { sftp.mkdir("/root/new") }
    }

    @Test
    fun `createDirectory does not swallow a cancellation`() = runTest {
        val sftp = openedSftp()
        every { sftp.statExistence("/home/user/new") } throws CancellationException("cancelled")

        var rethrown = false
        try {
            sftpManager.createDirectory(sessionId, "/home/user", "new")
        } catch (e: CancellationException) {
            rethrown = true
        }

        assertTrue(rethrown)
        verify(exactly = 0) { sftp.mkdir(any<String>()) }
    }

    @Test
    fun `createDirectory refuses a parent folder above the root without any request`() = runTest {
        val sftp = openedSftp()

        val result = sftpManager.createDirectory(sessionId, "/../etc", "new")

        assertEquals(SFTP_INVALID_PATH_MESSAGE, result.messageOfError())
        verify { sftp wasNot Called }
    }

    // ── rename ───────────────────────────────────────────────────────────────────

    @Test
    fun `rename looks the new name up with lstat, then renames in the same folder`() = runTest {
        val sftp = openedSftp()
        every { sftp.lstat("/home/user/docs/new.txt") } throws noSuchFile()
        every { sftp.rename("/home/user/docs/old.txt", "/home/user/docs/new.txt") } just Runs

        val result = sftpManager.rename(sessionId, "/home/user/docs/old.txt", "new.txt")

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
        val sftp = openedSftp()

        refusedNames.forEach { name ->
            val result = sftpManager.rename(sessionId, "/home/user/docs/f.txt", name)

            assertTrue(result is SshResult.Error, "rename refuses <$name>")
            assertEquals(SFTP_ENTRY_NAME_INVALID_MESSAGE, result.messageOfError(), "<$name>")
        }
        // In particular "..": it used to move /home/user/docs/f.txt onto /home/user.
        verify { sftp wasNot Called }
    }

    @Test
    fun `rename keeps the new path in the parent folder, whatever the name`() = runTest {
        val sftp = openedSftp()
        val olds = mutableListOf<String>()
        val news = mutableListOf<String>()
        every { sftp.lstat(any()) } throws noSuchFile()
        every { sftp.rename(capture(olds), capture(news)) } just Runs

        literalNames.forEach { name ->
            val result = sftpManager.rename(sessionId, "/home/user/docs/f.txt", name)

            assertTrue(result is SshResult.Success, "rename must accept <$name>")
        }

        assertEquals(literalNames.map { "/home/user/docs/$it" }, news)
        assertTrue(news.all { it.substringBeforeLast('/') == "/home/user/docs" })
        assertTrue(olds.all { it == "/home/user/docs/f.txt" })
    }

    @Test
    fun `rename resolves the old path and never the new name`() = runTest {
        val sftp = openedSftp()
        every { sftp.lstat("/home/user/g.txt") } throws noSuchFile()
        every { sftp.rename("/home/user/f.txt", "/home/user/g.txt") } just Runs

        val result = sftpManager.rename(sessionId, "/home/user/docs/../f.txt", "g.txt")

        assertTrue(result is SshResult.Success)
        verify(exactly = 1) { sftp.rename("/home/user/f.txt", "/home/user/g.txt") }
    }

    @Test
    fun `rename refuses a name that is already taken and renames nothing`() = runTest {
        val sftp = openedSftp()
        // A file or a folder answers the lookup the same way: the name is taken, whatever it names.
        every { sftp.lstat("/home/user/taken.txt") } returns attributesOf(FileMode.Type.REGULAR)
        every { sftp.lstat("/home/user/taken-folder") } returns attributesOf(FileMode.Type.DIRECTORY)

        listOf("taken.txt", "taken-folder").forEach { name ->
            val result = sftpManager.rename(sessionId, "/home/user/old.txt", name)

            assertEquals(SFTP_NAME_EXISTS_MESSAGE, result.messageOfError(), name)
        }
        verify(exactly = 0) { sftp.rename(any<String>(), any<String>()) }
    }

    @Test
    fun `rename refuses a name held by a broken symbolic link and renames nothing`() = runTest {
        val sftp = openedSftp()
        // stat follows the link and finds nothing, lstat finds the link itself: a server that replaces
        // on rename would have replaced it, the name is taken.
        every { sftp.lstat("/home/user/link") } returns attributesOf(FileMode.Type.SYMLINK)

        val result = sftpManager.rename(sessionId, "/home/user/old.txt", "link")

        assertEquals(SFTP_NAME_EXISTS_MESSAGE, result.messageOfError())
        verify(exactly = 0) { sftp.rename(any<String>(), any<String>()) }
        verify(exactly = 0) { sftp.statExistence(any<String>()) }
    }

    @Test
    fun `rename takes the status no such path for a free name as well`() = runTest {
        val sftp = openedSftp()
        every { sftp.lstat("/home/user/new.txt") } throws
            SFTPException(Response.StatusCode.NO_SUCH_PATH, "No such path")
        every { sftp.rename("/home/user/old.txt", "/home/user/new.txt") } just Runs

        val result = sftpManager.rename(sessionId, "/home/user/old.txt", "new.txt")

        assertTrue(result is SshResult.Success)
        verify(exactly = 1) { sftp.rename("/home/user/old.txt", "/home/user/new.txt") }
    }

    @Test
    fun `rename does not conclude from a lookup the server refuses, and renames nothing`() = runTest {
        val sftp = openedSftp()
        every { sftp.lstat("/home/user/locked.txt") } throws
            SFTPException(Response.StatusCode.PERMISSION_DENIED, "Permission denied")
        every { sftp.lstat("/home/user/odd.txt") } throws
            SFTPException(Response.StatusCode.FAILURE, "Failure")

        val refused = sftpManager.rename(sessionId, "/home/user/old.txt", "locked.txt")
        val failed = sftpManager.rename(sessionId, "/home/user/old.txt", "odd.txt")

        assertEquals(SFTP_PERMISSION_DENIED_MESSAGE, refused.messageOfError())
        assertEquals(SFTP_RENAME_FAILED_MESSAGE, failed.messageOfError())
        verify(exactly = 0) { sftp.rename(any<String>(), any<String>()) }
    }

    @Test
    fun `rename that changes only the case is left to the server, whatever the lookup says`() = runTest {
        val sftp = openedSftp()
        // A server that ignores the case: the lookup of the new name finds the entry itself.
        every { sftp.lstat("/home/user/rapport.docx") } returns attributesOf(FileMode.Type.REGULAR)
        every { sftp.rename("/home/user/Rapport.docx", "/home/user/rapport.docx") } just Runs

        val result = sftpManager.rename(sessionId, "/home/user/Rapport.docx", "rapport.docx")

        assertTrue(result is SshResult.Success)
        verify(exactly = 1) { sftp.rename("/home/user/Rapport.docx", "/home/user/rapport.docx") }
        verify(exactly = 0) { sftp.lstat(any<String>()) }
        verify(exactly = 0) { sftp.statExistence(any<String>()) }
    }

    @Test
    fun `rename that changes only the case reports the refusal of the server`() = runTest {
        val sftp = openedSftp()
        // A server that tells the two names apart and holds both: it is the one to refuse.
        every { sftp.rename("/home/user/Rapport.docx", "/home/user/rapport.docx") } throws
            SFTPException(Response.StatusCode.FAILURE, "Failure")

        val result = sftpManager.rename(sessionId, "/home/user/Rapport.docx", "rapport.docx")

        assertEquals(SFTP_RENAME_FAILED_MESSAGE, result.messageOfError())
    }

    @Test
    fun `rename that changes the case and more is still looked up`() = runTest {
        val sftp = openedSftp()
        every { sftp.lstat("/home/user/rapport2.docx") } returns attributesOf(FileMode.Type.REGULAR)

        val result = sftpManager.rename(sessionId, "/home/user/Rapport.docx", "rapport2.docx")

        assertEquals(SFTP_NAME_EXISTS_MESSAGE, result.messageOfError())
        verify(exactly = 0) { sftp.rename(any<String>(), any<String>()) }
    }

    @Test
    fun `rename to the name the entry already has succeeds without any request`() = runTest {
        val sftp = openedSftp()

        val result = sftpManager.rename(sessionId, "/home/user/docs/f.txt", "f.txt")

        assertTrue(result is SshResult.Success)
        verify { sftp wasNot Called }
    }

    @Test
    fun `rename moves an entry of the root to a path of the root`() = runTest {
        val sftp = openedSftp()
        every { sftp.lstat("/b.txt") } throws noSuchFile()
        every { sftp.rename("/a.txt", "/b.txt") } just Runs

        val result = sftpManager.rename(sessionId, "/a.txt", "b.txt")

        assertTrue(result is SshResult.Success)
        verify(exactly = 1) { sftp.rename("/a.txt", "/b.txt") }
    }

    @Test
    fun `rename refuses the root, which has no name to change`() = runTest {
        val sftp = openedSftp()

        val result = sftpManager.rename(sessionId, "/", "x")

        assertEquals(SFTP_INVALID_PATH_MESSAGE, result.messageOfError())
        verify { sftp wasNot Called }
    }

    @Test
    fun `rename refuses an old path above the root without any request`() = runTest {
        val sftp = openedSftp()

        val result = sftpManager.rename(sessionId, "/../etc/passwd", "x")

        assertEquals(SFTP_INVALID_PATH_MESSAGE, result.messageOfError())
        verify { sftp wasNot Called }
    }

    @Test
    fun `rename says the entry was not renamed when the server only answers failure`() = runTest {
        val sftp = openedSftp()
        every { sftp.lstat("/home/user/new.txt") } throws noSuchFile()
        every { sftp.rename("/home/user/old.txt", "/home/user/new.txt") } throws SFTPException("Failure")

        val result = sftpManager.rename(sessionId, "/home/user/old.txt", "new.txt")

        assertEquals(SFTP_RENAME_FAILED_MESSAGE, result.messageOfError())
    }

    @Test
    fun `rename says permission denied when the server refuses for lack of rights`() = runTest {
        val sftp = openedSftp()
        every { sftp.lstat("/root/new.txt") } throws noSuchFile()
        every { sftp.rename("/root/old.txt", "/root/new.txt") } throws SFTPException("Permission denied")

        val result = sftpManager.rename(sessionId, "/root/old.txt", "new.txt")

        assertEquals(SFTP_PERMISSION_DENIED_MESSAGE, result.messageOfError())
    }

    @Test
    fun `rename says the connection is lost when the lookup drops, and renames nothing`() = runTest {
        val sftp = openedSftp()
        every { sftp.lstat("/home/user/new.txt") } throws IOException("Broken pipe")

        val result = sftpManager.rename(sessionId, "/home/user/old.txt", "new.txt")

        assertEquals(SFTP_CONNECTION_LOST_MESSAGE, result.messageOfError())
        verify(exactly = 0) { sftp.rename(any<String>(), any<String>()) }
    }

    @Test
    fun `rename says the connection is lost when the lookup loses the channel, and renames nothing`() = runTest {
        // Opened once: the manager keeps its client, the loop only stubs it again.
        val sftp = openedSftp()
        for (lost in listOf(SFTPException("EOF while reading packet"), SFTPException(TimeoutException()))) {
            every { sftp.lstat("/home/user/new.txt") } throws lost

            val result = sftpManager.rename(sessionId, "/home/user/old.txt", "new.txt")

            assertEquals(SFTP_CONNECTION_LOST_MESSAGE, result.messageOfError(), lost.toString())
            verify(exactly = 0) { sftp.rename(any<String>(), any<String>()) }
        }
    }

    @Test
    fun `rename says the connection is lost when the rename drops`() = runTest {
        val sftp = openedSftp()
        every { sftp.lstat("/home/user/new.txt") } throws noSuchFile()
        every { sftp.rename("/home/user/old.txt", "/home/user/new.txt") } throws IOException("Broken pipe")

        val result = sftpManager.rename(sessionId, "/home/user/old.txt", "new.txt")

        assertEquals(SFTP_CONNECTION_LOST_MESSAGE, result.messageOfError())
    }

    @Test
    fun `rename does not swallow a cancellation`() = runTest {
        val sftp = openedSftp()
        every { sftp.lstat("/home/user/new.txt") } throws CancellationException("cancelled")

        var rethrown = false
        try {
            sftpManager.rename(sessionId, "/home/user/old.txt", "new.txt")
        } catch (e: CancellationException) {
            rethrown = true
        }

        assertTrue(rethrown)
        verify(exactly = 0) { sftp.rename(any<String>(), any<String>()) }
    }
}
