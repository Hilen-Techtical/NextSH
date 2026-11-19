// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.shared.domain.model.SshResult
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

/**
 * A link is followed by a replacement only if the user who uploads owns it. `realpath` of an OpenSSH
 * server follows any link, where the kernel refuses (`fs.protected_symlinks`, on by default on Debian,
 * Ubuntu, Fedora and RHEL, root included) to follow, in a sticky folder such as `/tmp`, a link that
 * another user laid there. Renaming over the file such a link leads to would overwrite a file that
 * nobody agreed to replace, which the open with truncate of 1.1.0 never could. The owner of the user is
 * the one of the temporary file that was just created.
 *
 * The SFTP client is a strict mock: a call that a test did not stub fails it.
 */
class SftpManagerReplaceLinkOwnerTest {

    private val sid = "3f2a9c1e-77b0-4c1d-9a55-session"
    private val link = "/tmp/report.pdf"
    private val real = "/home/alice/.ssh/authorized_keys"
    private val inPlaceModes = setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)

    private val sessionManager: SshSessionManager = mockk(relaxed = true)
    private val sftpManager = SftpManager(sessionManager)

    private class Server(val sftp: SFTPClient, val temp: RemoteFile) {
        val tempPaths = mutableListOf<String>()
        val tempPath: String get() = tempPaths.single()
    }

    private fun file(type: FileMode.Type, owner: Pair<Int, Int>?): FileAttributes {
        val builder = FileAttributes.Builder().withType(type).withPermissions(0b110_100_100)
        if (owner != null) builder.withUIDGID(owner.first, owner.second)
        return builder.build()
    }

    /**
     * A server where [link] leads to [real], a file of user 1000. The link belongs to [linkOwner], and
     * the user who uploads is [user] (the owner the temporary file gets), nobody known when null.
     */
    private suspend fun server(linkOwner: Pair<Int, Int>?, user: Pair<Int, Int>?): Server {
        val ssh = mockk<SSHClient>()
        val sftp = mockk<SFTPClient>()
        every { ssh.newSFTPClient() } returns sftp
        every { sessionManager.getClient(sid) } returns ssh
        sftpManager.openSftp(sid)

        val engine = mockk<SFTPEngine>()
        every { sftp.sftpEngine } returns engine
        every { engine.supportsServerExtension("posix-rename", "openssh.com") } returns true
        every { sftp.lstat(link) } returns file(FileMode.Type.SYMLINK, linkOwner)
        every { sftp.canonicalize(link) } returns real
        every { sftp.readlink(link) } returns real
        every { sftp.lstat(real) } returns file(FileMode.Type.REGULAR, 1000 to 1000)

        val temp = mockk<RemoteFile>(relaxed = true)
        every { temp.fetchAttributes() } returns file(FileMode.Type.REGULAR, user)
        val server = Server(sftp, temp)
        every { sftp.open(any<String>(), any<Set<OpenMode>>(), any<FileAttributes>()) } answers {
            server.tempPaths += firstArg<String>()
            temp
        }
        every { sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) } just Runs
        every { sftp.rm(any<String>()) } just Runs
        return server
    }

    private suspend fun replaceTheLink(): SshResult<Unit> =
        sftpManager.writeFile(sid, link, ByteArrayInputStream(ByteArray(3)), 3L, replaceExisting = true) { _, _ -> }

    /** The old way took over: the temporary file is gone, nothing of the source went to it, and no rename. */
    private fun Server.verifyWrittenInPlaceThroughTheLink(target: RemoteFile) {
        verify(exactly = 0) { temp.write(any(), any<ByteArray>(), any(), any()) }
        verify(exactly = 0) { sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) }
        verifyOrder {
            temp.close()
            sftp.rm(tempPath)
            sftp.open(link, inPlaceModes)
            target.write(0L, any<ByteArray>(), 0, 3)
        }
        // The file behind the link is never opened by name: the kernel decides what the link allows.
        verify(exactly = 0) { sftp.open(real, any<Set<OpenMode>>()) }
    }

    @Test
    fun `a link that another user owns is not followed, the open in place goes through it and the kernel decides`() = runTest {
        val s = server(linkOwner = 2000 to 2000, user = 1000 to 1000)
        val target = mockk<RemoteFile>(relaxed = true)
        every { s.sftp.open(link, inPlaceModes) } returns target

        val result = replaceTheLink()

        assertTrue(result is SshResult.Success)
        s.verifyWrittenInPlaceThroughTheLink(target)
    }

    @Test
    fun `a link that the kernel refuses to follow stays an error of the open, and nothing is renamed`() = runTest {
        val s = server(linkOwner = 2000 to 2000, user = 1000 to 1000)
        every {
            s.sftp.open(link, inPlaceModes)
        } throws SFTPException(Response.StatusCode.PERMISSION_DENIED, "Permission denied")

        val result = replaceTheLink()

        assertTrue(result is SshResult.Error)
        verify(exactly = 0) { s.sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) }
        verify(exactly = 0) { s.temp.write(any(), any<ByteArray>(), any(), any()) }
    }

    @Test
    fun `root is not an exception, a link of another user is not followed for root either`() = runTest {
        val s = server(linkOwner = 1000 to 1000, user = 0 to 0)
        val target = mockk<RemoteFile>(relaxed = true)
        every { s.sftp.open(link, inPlaceModes) } returns target

        val result = replaceTheLink()

        assertTrue(result is SshResult.Success)
        s.verifyWrittenInPlaceThroughTheLink(target)
    }

    @Test
    fun `a link whose owner the server does not give is not followed`() = runTest {
        val s = server(linkOwner = null, user = 1000 to 1000)
        val target = mockk<RemoteFile>(relaxed = true)
        every { s.sftp.open(link, inPlaceModes) } returns target

        val result = replaceTheLink()

        assertTrue(result is SshResult.Success)
        s.verifyWrittenInPlaceThroughTheLink(target)
    }

    @Test
    fun `a link is not followed when the user is not known either`() = runTest {
        val s = server(linkOwner = 1000 to 1000, user = null)
        val target = mockk<RemoteFile>(relaxed = true)
        every { s.sftp.open(link, inPlaceModes) } returns target

        val result = replaceTheLink()

        assertTrue(result is SshResult.Success)
        s.verifyWrittenInPlaceThroughTheLink(target)
    }

    @Test
    fun `a link that the user owns is followed, the file behind it is replaced`() = runTest {
        val s = server(linkOwner = 1000 to 100, user = 1000 to 1000)

        val result = replaceTheLink()

        assertTrue(result is SshResult.Success)
        verify(exactly = 1) { s.sftp.rename(s.tempPath, real, setOf(RenameFlags.OVERWRITE)) }
        verify(exactly = 0) { s.sftp.open(link, any<Set<OpenMode>>()) }
        verify(exactly = 0) { s.sftp.rm(any<String>()) }
    }
}
