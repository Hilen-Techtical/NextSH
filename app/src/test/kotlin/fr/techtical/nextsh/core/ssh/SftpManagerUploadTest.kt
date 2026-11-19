// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.shared.domain.model.SshResult
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.RenameFlags
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPEngine
import net.schmizz.sshj.sftp.SFTPException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.TimeoutException

/**
 * The upload never replaces a file on its own: [SftpManager.writeFile] opens the destination in
 * exclusive mode unless the user confirmed the replacement, and [SftpManager.checkUploadTarget] tells
 * what holds the name before anything is sent. A confirmed replacement goes through a hidden temporary
 * file (the details are in `SftpManagerReplaceTest`).
 *
 * The SFTP client is a strict mock: a call that a test did not stub fails it.
 */
class SftpManagerUploadTest {

    private val sid = "session-1"
    private val path = "/home/user/report.pdf"

    private val sessionManager: SshSessionManager = mockk(relaxed = true)
    private val sftpManager = SftpManager(sessionManager)

    /** A session with its SFTP client open. */
    private suspend fun openedSftp(): SFTPClient {
        val ssh = mockk<SSHClient>()
        val sftp = mockk<SFTPClient>()
        every { ssh.newSFTPClient() } returns sftp
        every { sessionManager.getClient(sid) } returns ssh
        sftpManager.openSftp(sid)
        return sftp
    }

    private fun attrs(kind: FileMode.Type): FileAttributes {
        val attributes = mockk<FileAttributes>()
        every { attributes.type } returns kind
        return attributes
    }

    private fun SshResult<*>.messageOfError(): String = (this as SshResult.Error).message

    private fun content(size: Int = 3) = ByteArrayInputStream(ByteArray(size) { it.toByte() })

    /** The attributes of a regular file, built for real the way an OpenSSH server sends them. */
    private fun regularFile(permissions: Int = 0b110_100_100): FileAttributes =
        FileAttributes.Builder()
            .withType(FileMode.Type.REGULAR)
            .withPermissions(permissions)
            .withUIDGID(1000, 1000)
            .build()

    /** This client talks to a server that announced `posix-rename@openssh.com`. */
    private fun SFTPClient.announcingPosixRename() {
        val engine = mockk<SFTPEngine>()
        every { sftpEngine } returns engine
        every { engine.supportsServerExtension("posix-rename", "openssh.com") } returns true
    }

    // ── writeFile: the open mode ─────────────────────────────────────────────────

    @Test
    fun `an upload without replacement opens with exactly write, create and exclusive`() = runTest {
        val sftp = openedSftp()
        val remote = mockk<RemoteFile>(relaxed = true)
        val modes = slot<Set<OpenMode>>()
        every { sftp.open(path, capture(modes)) } returns remote

        val result = sftpManager.writeFile(sid, path, content(3), 3L, replaceExisting = false) { _, _ -> }

        assertTrue(result is SshResult.Success)
        assertEquals(setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL), modes.captured)
        verify(exactly = 1) { remote.write(0L, any<ByteArray>(), 0, 3) }
        verify(exactly = 1) { remote.close() }
    }

    @Test
    fun `an upload with a confirmed replacement opens a hidden temporary file of the same folder, never the target`() = runTest {
        val sftp = openedSftp()
        sftp.announcingPosixRename()
        every { sftp.lstat(path) } returns regularFile()
        val remote = mockk<RemoteFile>(relaxed = true)
        every { remote.fetchAttributes() } returns regularFile()
        val opened = slot<String>()
        val modes = slot<Set<OpenMode>>()
        val attributes = slot<FileAttributes>()
        every { sftp.open(capture(opened), capture(modes), capture(attributes)) } returns remote
        every { sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) } just Runs

        val result = sftpManager.writeFile(sid, path, content(3), 3L, replaceExisting = true) { _, _ -> }

        assertTrue(result is SshResult.Success)
        assertTrue(Regex("""^/home/user/\.report\.pdf\.nextsh-part-[0-9a-f]{8}$""").matches(opened.captured), opened.captured)
        // New (a link laid in advance is not followed), write only, and private from the open itself.
        assertEquals(setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL), modes.captured)
        assertEquals(0b110_000_000, attributes.captured.mode.permissionsMask)
        verify(exactly = 0) { sftp.open(path, any<Set<OpenMode>>()) }
        verify(exactly = 0) { sftp.open(path, any<Set<OpenMode>>(), any<FileAttributes>()) }
    }

    // ── writeFile: a refusal of the open ─────────────────────────────────────────

    @Test
    fun `an exclusive open refused with a failure and a name that is there says the name is taken, and writes nothing`() = runTest {
        val sftp = openedSftp()
        // OpenSSH (SFTP v3) answers FAILURE, not FILE_ALREADY_EXISTS, to an exclusive open of a taken name.
        every { sftp.open(path, any<Set<OpenMode>>()) } throws SFTPException(Response.StatusCode.FAILURE, "Failure")
        every { sftp.lstat(path) } returns attrs(FileMode.Type.REGULAR)
        val source = content(5)

        val result = sftpManager.writeFile(sid, path, source, 5L, replaceExisting = false) { _, _ -> }

        assertEquals(SFTP_UPLOAD_TARGET_EXISTS_MESSAGE, result.messageOfError())
        // Nothing was read from the source, so nothing was written.
        assertEquals(5, source.available())
    }

    @Test
    fun `an exclusive open refused with the status file already exists says the name is taken, without a lookup`() = runTest {
        val sftp = openedSftp()
        every {
            sftp.open(path, any<Set<OpenMode>>())
        } throws SFTPException(Response.StatusCode.FILE_ALREADY_EXISTS, "File already exists")

        val result = sftpManager.writeFile(sid, path, content(), 3L, replaceExisting = false) { _, _ -> }

        assertEquals(SFTP_UPLOAD_TARGET_EXISTS_MESSAGE, result.messageOfError())
        verify(exactly = 0) { sftp.lstat(any<String>()) }
    }

    @Test
    fun `an exclusive open refused on a broken link says the name is taken`() = runTest {
        val sftp = openedSftp()
        every { sftp.open(path, any<Set<OpenMode>>()) } throws SFTPException(Response.StatusCode.FAILURE, "Failure")
        // lstat does not follow the link, so it sees the link itself where stat would find nothing.
        every { sftp.lstat(path) } returns attrs(FileMode.Type.SYMLINK)

        val result = sftpManager.writeFile(sid, path, content(), 3L, replaceExisting = false) { _, _ -> }

        assertEquals(SFTP_UPLOAD_TARGET_EXISTS_MESSAGE, result.messageOfError())
    }

    @Test
    fun `an exclusive open that loses the channel says a lost connection without looking the name up`() = runTest {
        // Opened once: the manager keeps its client, the loop only stubs it again.
        val sftp = openedSftp()
        for (lost in listOf(SFTPException("EOF while reading packet"), SFTPException(TimeoutException()))) {
            every { sftp.open(path, any<Set<OpenMode>>()) } throws lost

            val result = sftpManager.writeFile(sid, path, content(), 3L, replaceExisting = false) { _, _ -> }

            assertEquals(SFTP_CONNECTION_LOST_MESSAGE, result.messageOfError(), lost.toString())
            verify(exactly = 0) { sftp.lstat(any<String>()) }
            verify(exactly = 0) { sftp.statExistence(any<String>()) }
        }
    }

    @Test
    fun `a refusal for lack of rights on a name that is free says the permission text, not the name taken`() = runTest {
        val sftp = openedSftp()
        every { sftp.open(path, any<Set<OpenMode>>()) } throws SFTPException("Permission denied")
        every { sftp.lstat(path) } throws SFTPException(Response.StatusCode.NO_SUCH_FILE, "No such file")

        val result = sftpManager.writeFile(sid, path, content(), 3L, replaceExisting = false) { _, _ -> }

        assertEquals(SFTP_PERMISSION_DENIED_MESSAGE, result.messageOfError())
    }

    @Test
    fun `a refusal whose lookup fails too keeps the usual translation`() = runTest {
        val sftp = openedSftp()
        every { sftp.open(path, any<Set<OpenMode>>()) } throws SFTPException(Response.StatusCode.FAILURE, "Failure")
        every { sftp.lstat(path) } throws IOException("Broken pipe")

        val result = sftpManager.writeFile(sid, path, content(), 3L, replaceExisting = false) { _, _ -> }

        assertEquals(SFTP_UPLOAD_FAILED_MESSAGE, result.messageOfError())
    }

    @Test
    fun `a replacement whose temporary file is refused for lack of rights is written in place, and removes nothing`() = runTest {
        val sftp = openedSftp()
        sftp.announcingPosixRename()
        every { sftp.lstat(path) } returns regularFile()
        every {
            sftp.open(any<String>(), any<Set<OpenMode>>(), any<FileAttributes>())
        } throws SFTPException(Response.StatusCode.PERMISSION_DENIED, "Permission denied")
        val remote = mockk<RemoteFile>(relaxed = true)
        val modes = slot<Set<OpenMode>>()
        every { sftp.open(path, capture(modes)) } returns remote

        val result = sftpManager.writeFile(sid, path, content(3), 3L, replaceExisting = true) { _, _ -> }

        assertTrue(result is SshResult.Success)
        assertEquals(setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC), modes.captured)
        verify(exactly = 0) { sftp.rm(any<String>()) }
        verify(exactly = 1) { remote.close() }
    }

    @Test
    fun `the message of a name taken carries neither the path nor the status`() = runTest {
        val sftp = openedSftp()
        every { sftp.open(path, any<Set<OpenMode>>()) } throws SFTPException(Response.StatusCode.FAILURE, "Failure on /home/user/report.pdf")
        every { sftp.lstat(path) } returns attrs(FileMode.Type.REGULAR)

        val message = sftpManager.writeFile(sid, path, content(), 3L, replaceExisting = false) { _, _ -> }.messageOfError()

        assertEquals(SFTP_UPLOAD_TARGET_EXISTS_MESSAGE, message)
        assertFalse(message.contains("report"))
        assertFalse(message.contains("FAILURE"))
    }

    // ── checkUploadTarget: what holds the name ───────────────────────────────────

    @Test
    fun `a name with nothing behind it is free, found in a single lookup`() = runTest {
        val sftp = openedSftp()
        every { sftp.lstat(path) } throws SFTPException(Response.StatusCode.NO_SUCH_FILE, "No such file")

        val result = sftpManager.checkUploadTarget(sid, path)

        assertEquals(UploadTarget.FREE, (result as SshResult.Success).data)
        verify(exactly = 1) { sftp.lstat(path) }
        verify(exactly = 0) { sftp.statExistence(any<String>()) }
        verify(exactly = 0) { sftp.open(any<String>(), any<Set<OpenMode>>()) }
    }

    @Test
    fun `the status no such path is free as well`() = runTest {
        val sftp = openedSftp()
        every { sftp.lstat(path) } throws SFTPException(Response.StatusCode.NO_SUCH_PATH, "No such path")

        val result = sftpManager.checkUploadTarget(sid, path)

        assertEquals(UploadTarget.FREE, (result as SshResult.Success).data)
    }

    @Test
    fun `a regular file is a file`() = runTest {
        val sftp = openedSftp()
        every { sftp.lstat(path) } returns attrs(FileMode.Type.REGULAR)

        val result = sftpManager.checkUploadTarget(sid, path)

        assertEquals(UploadTarget.FILE, (result as SshResult.Success).data)
        verify(exactly = 0) { sftp.statExistence(any<String>()) }
        verify(exactly = 0) { sftp.open(any<String>(), any<Set<OpenMode>>()) }
    }

    @Test
    fun `a folder is a directory`() = runTest {
        val sftp = openedSftp()
        every { sftp.lstat(path) } returns attrs(FileMode.Type.DIRECTORY)

        val result = sftpManager.checkUploadTarget(sid, path)

        assertEquals(UploadTarget.DIRECTORY, (result as SshResult.Success).data)
    }

    @Test
    fun `an entry whose type the server does not give counts as a file`() = runTest {
        val sftp = openedSftp()
        every { sftp.lstat(path) } returns attrs(FileMode.Type.UNKNOWN)

        val result = sftpManager.checkUploadTarget(sid, path)

        assertEquals(UploadTarget.FILE, (result as SshResult.Success).data)
    }

    @Test
    fun `a named pipe is other`() = runTest {
        val sftp = openedSftp()
        every { sftp.lstat(path) } returns attrs(FileMode.Type.FIFO_SPECIAL)

        val result = sftpManager.checkUploadTarget(sid, path)

        assertEquals(UploadTarget.OTHER, (result as SshResult.Success).data)
    }

    @Test
    fun `a socket is other`() = runTest {
        val sftp = openedSftp()
        every { sftp.lstat(path) } returns attrs(FileMode.Type.SOCKET_SPECIAL)

        val result = sftpManager.checkUploadTarget(sid, path)

        assertEquals(UploadTarget.OTHER, (result as SshResult.Success).data)
    }

    @Test
    fun `a device is other, block or character`() = runTest {
        val sftp = openedSftp()
        every { sftp.lstat(path) } returns attrs(FileMode.Type.BLOCK_SPECIAL)
        assertEquals(UploadTarget.OTHER, (sftpManager.checkUploadTarget(sid, path) as SshResult.Success).data)

        every { sftp.lstat(path) } returns attrs(FileMode.Type.CHAR_SPECIAL)
        assertEquals(UploadTarget.OTHER, (sftpManager.checkUploadTarget(sid, path) as SshResult.Success).data)
    }

    @Test
    fun `a link to a file is a file, found by following the link`() = runTest {
        val sftp = openedSftp()
        every { sftp.lstat(path) } returns attrs(FileMode.Type.SYMLINK)
        every { sftp.statExistence(path) } returns attrs(FileMode.Type.REGULAR)

        val result = sftpManager.checkUploadTarget(sid, path)

        assertEquals(UploadTarget.FILE, (result as SshResult.Success).data)
    }

    @Test
    fun `a link to a folder is a directory`() = runTest {
        val sftp = openedSftp()
        every { sftp.lstat(path) } returns attrs(FileMode.Type.SYMLINK)
        every { sftp.statExistence(path) } returns attrs(FileMode.Type.DIRECTORY)

        val result = sftpManager.checkUploadTarget(sid, path)

        assertEquals(UploadTarget.DIRECTORY, (result as SshResult.Success).data)
    }

    @Test
    fun `a broken link is other`() = runTest {
        val sftp = openedSftp()
        every { sftp.lstat(path) } returns attrs(FileMode.Type.SYMLINK)
        every { sftp.statExistence(path) } returns null

        val result = sftpManager.checkUploadTarget(sid, path)

        assertEquals(UploadTarget.OTHER, (result as SshResult.Success).data)
    }

    @Test
    fun `a link that the server cannot follow is other, refused at once`() = runTest {
        val sftp = openedSftp()
        every { sftp.lstat(path) } returns attrs(FileMode.Type.SYMLINK)
        // OpenSSH reports a loop of links (ELOOP) as a plain failure.
        every { sftp.statExistence(path) } throws SFTPException(Response.StatusCode.FAILURE, "Failure")

        val result = sftpManager.checkUploadTarget(sid, path)

        assertEquals(UploadTarget.OTHER, (result as SshResult.Success).data)
    }

    @Test
    fun `a link whose target the server refuses to show is other, not an error of the check`() = runTest {
        val sftp = openedSftp()
        every { sftp.lstat(path) } returns attrs(FileMode.Type.SYMLINK)
        every { sftp.statExistence(path) } throws SFTPException(Response.StatusCode.PERMISSION_DENIED, "Permission denied")

        val result = sftpManager.checkUploadTarget(sid, path)

        assertEquals(UploadTarget.OTHER, (result as SshResult.Success).data)
    }

    @Test
    fun `a connection that drops while the link is followed says the fixed text of a lost connection`() = runTest {
        val sftp = openedSftp()
        every { sftp.lstat(path) } returns attrs(FileMode.Type.SYMLINK)
        every { sftp.statExistence(path) } throws IOException("Broken pipe")

        val result = sftpManager.checkUploadTarget(sid, path)

        assertEquals(SFTP_CONNECTION_LOST_MESSAGE, result.messageOfError())
    }

    @Test
    fun `a channel lost while the link is followed is a lost connection, not an entry that cannot be replaced`() = runTest {
        val sftp = openedSftp()
        every { sftp.lstat(path) } returns attrs(FileMode.Type.SYMLINK)
        // SSHJ raises a channel that is lost as an SFTPException with the status UNKNOWN.
        every { sftp.statExistence(path) } throws SFTPException("EOF while reading packet")

        val result = sftpManager.checkUploadTarget(sid, path)

        assertEquals(SFTP_CONNECTION_LOST_MESSAGE, result.messageOfError())
    }

    @Test
    fun `a server that stays silent while the link is followed is a lost connection too`() = runTest {
        val sftp = openedSftp()
        every { sftp.lstat(path) } returns attrs(FileMode.Type.SYMLINK)
        // ... and a server that does not answer as one that carries the timeout as its cause.
        every { sftp.statExistence(path) } throws SFTPException(TimeoutException())

        val result = sftpManager.checkUploadTarget(sid, path)

        assertEquals(SFTP_CONNECTION_LOST_MESSAGE, result.messageOfError())
    }

    @Test
    fun `a link answered with a status of the server stays an entry that cannot be replaced, whatever the status`() = runTest {
        val sftp = openedSftp()
        every { sftp.lstat(path) } returns attrs(FileMode.Type.SYMLINK)
        for (status in listOf(Response.StatusCode.FAILURE, Response.StatusCode.PERMISSION_DENIED, Response.StatusCode.LINK_LOOP)) {
            every { sftp.statExistence(path) } throws SFTPException(status, "Answer of the server")

            val result = sftpManager.checkUploadTarget(sid, path)

            assertEquals(UploadTarget.OTHER, (result as SshResult.Success).data, status.name)
        }
    }

    // ── A server without lstat ───────────────────────────────────────────────────

    @Test
    fun `a server that does not support lstat is asked stat, and a file it shows is a file`() = runTest {
        val sftp = openedSftp()
        every { sftp.lstat(path) } throws SFTPException(Response.StatusCode.OP_UNSUPPORTED, "Unsupported")
        every { sftp.statExistence(path) } returns attrs(FileMode.Type.REGULAR)

        val result = sftpManager.checkUploadTarget(sid, path)

        assertEquals(UploadTarget.FILE, (result as SshResult.Success).data)
    }

    @Test
    fun `a server that does not support lstat and shows nothing at the name says the name is free`() = runTest {
        val sftp = openedSftp()
        every { sftp.lstat(path) } throws SFTPException(Response.StatusCode.OP_UNSUPPORTED, "Unsupported")
        every { sftp.statExistence(path) } returns null

        val result = sftpManager.checkUploadTarget(sid, path)

        assertEquals(UploadTarget.FREE, (result as SshResult.Success).data)
    }

    @Test
    fun `a server that does not support lstat and shows a folder says it is a directory`() = runTest {
        val sftp = openedSftp()
        every { sftp.lstat(path) } throws SFTPException(Response.StatusCode.OP_UNSUPPORTED, "Unsupported")
        every { sftp.statExistence(path) } returns attrs(FileMode.Type.DIRECTORY)

        val result = sftpManager.checkUploadTarget(sid, path)

        assertEquals(UploadTarget.DIRECTORY, (result as SshResult.Success).data)
    }

    @Test
    fun `a server that does not support lstat and refuses stat says the permission text`() = runTest {
        val sftp = openedSftp()
        every { sftp.lstat(path) } throws SFTPException(Response.StatusCode.OP_UNSUPPORTED, "Unsupported")
        every { sftp.statExistence(path) } throws SFTPException(Response.StatusCode.PERMISSION_DENIED, "Permission denied")

        val result = sftpManager.checkUploadTarget(sid, path)

        assertEquals(SFTP_PERMISSION_DENIED_MESSAGE, result.messageOfError())
    }

    @Test
    fun `an exclusive open refused on a server without lstat names the taken name through stat`() = runTest {
        val sftp = openedSftp()
        every { sftp.open(path, any<Set<OpenMode>>()) } throws SFTPException(Response.StatusCode.FAILURE, "Failure")
        every { sftp.lstat(path) } throws SFTPException(Response.StatusCode.OP_UNSUPPORTED, "Unsupported")
        every { sftp.statExistence(path) } returns attrs(FileMode.Type.REGULAR)

        val result = sftpManager.writeFile(sid, path, content(), 3L, replaceExisting = false) { _, _ -> }

        assertEquals(SFTP_UPLOAD_TARGET_EXISTS_MESSAGE, result.messageOfError())
    }

    @Test
    fun `an exclusive open refused on a server without lstat where stat finds nothing keeps the usual translation`() = runTest {
        val sftp = openedSftp()
        every { sftp.open(path, any<Set<OpenMode>>()) } throws SFTPException(Response.StatusCode.FAILURE, "Failure")
        every { sftp.lstat(path) } throws SFTPException(Response.StatusCode.OP_UNSUPPORTED, "Unsupported")
        every { sftp.statExistence(path) } returns null

        val result = sftpManager.writeFile(sid, path, content(), 3L, replaceExisting = false) { _, _ -> }

        assertEquals(SFTP_UPLOAD_FAILED_MESSAGE, result.messageOfError())
    }

    // ── checkUploadTarget: failures ──────────────────────────────────────────────

    @Test
    fun `a connection that drops during the check says the fixed text of a lost connection`() = runTest {
        val sftp = openedSftp()
        every { sftp.lstat(path) } throws IOException("Broken pipe")

        val result = sftpManager.checkUploadTarget(sid, path)

        assertEquals(SFTP_CONNECTION_LOST_MESSAGE, result.messageOfError())
        verify(exactly = 0) { sftp.open(any<String>(), any<Set<OpenMode>>()) }
    }

    @Test
    fun `a lookup refused for lack of rights says the permission text, by its status`() = runTest {
        val sftp = openedSftp()
        every { sftp.lstat(path) } throws SFTPException(Response.StatusCode.PERMISSION_DENIED, "Denied")

        val result = sftpManager.checkUploadTarget(sid, path)

        assertEquals(SFTP_PERMISSION_DENIED_MESSAGE, result.messageOfError())
    }

    @Test
    fun `a lookup refused for lack of rights says the permission text, by its message`() = runTest {
        val sftp = openedSftp()
        every { sftp.lstat(path) } throws SFTPException("Permission denied")

        val result = sftpManager.checkUploadTarget(sid, path)

        assertEquals(SFTP_PERMISSION_DENIED_MESSAGE, result.messageOfError())
    }

    @Test
    fun `a channel lost during the lookup is a lost connection, and the upload does not go on`() = runTest {
        val sftp = openedSftp()
        every { sftp.lstat(path) } throws SFTPException("EOF while reading packet")

        val result = sftpManager.checkUploadTarget(sid, path)

        assertEquals(SFTP_CONNECTION_LOST_MESSAGE, result.messageOfError())
        verify(exactly = 0) { sftp.open(any<String>(), any<Set<OpenMode>>()) }
    }

    @Test
    fun `a server that stays silent during the lookup is a lost connection too`() = runTest {
        val sftp = openedSftp()
        every { sftp.lstat(path) } throws SFTPException(TimeoutException())

        val result = sftpManager.checkUploadTarget(sid, path)

        assertEquals(SFTP_CONNECTION_LOST_MESSAGE, result.messageOfError())
    }

    @Test
    fun `a server that does not support lstat and loses the channel on stat says a lost connection`() = runTest {
        val sftp = openedSftp()
        every { sftp.lstat(path) } throws SFTPException(Response.StatusCode.OP_UNSUPPORTED, "Unsupported")
        every { sftp.statExistence(path) } throws SFTPException("EOF while reading packet")

        val result = sftpManager.checkUploadTarget(sid, path)

        assertEquals(SFTP_CONNECTION_LOST_MESSAGE, result.messageOfError())
    }

    @Test
    fun `any other refusal of the server says the fixed unknown text`() = runTest {
        val sftp = openedSftp()
        every { sftp.lstat(path) } throws SFTPException(Response.StatusCode.BAD_MESSAGE, "Bad message")

        val result = sftpManager.checkUploadTarget(sid, path)

        assertEquals(SFTP_UNKNOWN_MESSAGE, result.messageOfError())
    }

    @Test
    fun `a failure that is not the server's says the fixed unknown text, not the exception`() = runTest {
        val sftp = openedSftp()
        every { sftp.lstat(path) } throws IllegalStateException("db.interne.example secret detail")

        val message = sftpManager.checkUploadTarget(sid, path).messageOfError()

        assertEquals(SFTP_UNKNOWN_MESSAGE, message)
        assertFalse(message.contains("secret"))
    }
}
