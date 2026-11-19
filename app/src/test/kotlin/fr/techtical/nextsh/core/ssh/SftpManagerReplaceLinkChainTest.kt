// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.shared.domain.model.SshResult
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

/**
 * A replacement follows a link only if the link leads straight to the file. `realpath` of the server
 * follows the whole chain in user space, where the open in place of 1.1.0 is decided by the kernel,
 * link by link. A link of the user to /tmp/report.pdf, which a third party turned into a link to
 * ~/.ssh/authorized_keys, would pass a check of the first link only, and the rename would overwrite
 * authorized_keys.
 *
 * So what `readlink` says, resolved against the folder of the link as it is written (nothing resolved,
 * an absolute target taken as it is) and normalized, must be exactly the path of `realpath`. Anything
 * else is written in place, through the link, which gives the behaviour of 1.1.0 and nothing more (the
 * application does not redo the checks of the kernel). A target with a `..` segment is never followed:
 * the text resolves it by the names, the kernel by the folders, and through a link of a folder the two
 * can agree by chance.
 *
 * The SFTP client is a strict mock: a call that a test did not stub fails it.
 */
class SftpManagerReplaceLinkChainTest {

    private val sid = "3f2a9c1e-77b0-4c1d-9a55-session"
    private val inPlaceModes = setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)

    private val sessionManager: SshSessionManager = mockk(relaxed = true)
    private val sftpManager = SftpManager(sessionManager)

    private class Server(val sftp: SFTPClient, val link: String, val real: String) {
        val tempPaths = mutableListOf<String>()
        val tempPath: String get() = tempPaths.single()
    }

    private fun file(type: FileMode.Type): FileAttributes =
        FileAttributes.Builder().withType(type).withPermissions(0b110_100_100).withUIDGID(1000, 1000).build()

    /**
     * A server where [link] is a link of the user (uid 1000, like the temporary file), for which `realpath`
     * says [canonical], a file of the user, and `readlink` says [written] or fails with [readlinkFailure].
     */
    private suspend fun server(
        link: String,
        canonical: String,
        written: String = "",
        readlinkFailure: Exception? = null,
    ): Server {
        val ssh = mockk<SSHClient>()
        val sftp = mockk<SFTPClient>()
        every { ssh.newSFTPClient() } returns sftp
        every { sessionManager.getClient(sid) } returns ssh
        sftpManager.openSftp(sid)

        val engine = mockk<SFTPEngine>()
        every { sftp.sftpEngine } returns engine
        every { engine.supportsServerExtension("posix-rename", "openssh.com") } returns true
        every { sftp.lstat(link) } returns file(FileMode.Type.SYMLINK)
        every { sftp.canonicalize(link) } returns canonical
        every { sftp.lstat(canonical) } returns file(FileMode.Type.REGULAR)
        if (readlinkFailure != null) {
            every { sftp.readlink(link) } throws readlinkFailure
        } else {
            every { sftp.readlink(link) } returns written
        }

        val temp = mockk<RemoteFile>(relaxed = true)
        every { temp.fetchAttributes() } returns file(FileMode.Type.REGULAR)
        val server = Server(sftp, link, canonical)
        every { sftp.open(any<String>(), any<Set<OpenMode>>(), any<FileAttributes>()) } answers {
            server.tempPaths += firstArg<String>()
            temp
        }
        every { sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) } just Runs
        every { sftp.rm(any<String>()) } just Runs
        return server
    }

    private suspend fun replace(s: Server): SshResult<Unit> =
        sftpManager.writeFile(sid, s.link, ByteArrayInputStream(ByteArray(3)), 3L, replaceExisting = true) { _, _ -> }

    /** Written in place through the link: no temporary file at all, no rename, the file behind it never opened by name. */
    private suspend fun assertWrittenInPlaceThroughTheLink(s: Server) {
        val target = mockk<RemoteFile>(relaxed = true)
        every { s.sftp.open(s.link, inPlaceModes) } returns target

        val result = replace(s)

        assertTrue(result is SshResult.Success, result.toString())
        verify(exactly = 1) { target.write(0L, any<ByteArray>(), 0, 3) }
        verify(exactly = 0) { s.sftp.open(any<String>(), any<Set<OpenMode>>(), any<FileAttributes>()) }
        verify(exactly = 0) { s.sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) }
        verify(exactly = 0) { s.sftp.open(s.real, any<Set<OpenMode>>()) }
    }

    /** The real file is replaced: the temporary file is next to it, and it is renamed over it. */
    private suspend fun assertRenamedOverTheRealFile(s: Server) {
        val result = replace(s)

        assertTrue(result is SshResult.Success, result.toString())
        val folder = s.real.substringBeforeLast('/').ifEmpty { "" }
        assertTrue(s.tempPath.startsWith("$folder/."), s.tempPath)
        verify(exactly = 1) { s.sftp.rename(s.tempPath, s.real, setOf(RenameFlags.OVERWRITE)) }
        verify(exactly = 0) { s.sftp.open(s.link, any<Set<OpenMode>>()) }
    }

    // ── What is not a direct link: written in place ──────────────────────────────

    @Test
    fun `a chain of two links is written in place, the rename would not go through the links as the open does`() = runTest {
        // ~/report.pdf is the user's link to /tmp/report.pdf, which a third party turned into a link to authorized_keys.
        val s = server(
            link = "/home/alice/report.pdf",
            canonical = "/home/alice/.ssh/authorized_keys",
            written = "/tmp/report.pdf",
        )

        assertWrittenInPlaceThroughTheLink(s)
    }

    @Test
    fun `a chain of links written with a relative target is written in place too`() = runTest {
        val s = server(
            link = "/home/alice/report.pdf",
            canonical = "/home/alice/.ssh/authorized_keys",
            written = "second.pdf",
        )

        assertWrittenInPlaceThroughTheLink(s)
    }

    @Test
    fun `a target that goes through a link of a folder is written in place`() = runTest {
        // /tmp/dlink is a link to ~/.ssh: what the link says is not what the path leads to. The old way
        // is no protection, it is the behaviour of 1.1.0: the open goes through the links, the kernel decides.
        val s = server(
            link = "/home/alice/report.pdf",
            canonical = "/home/alice/.ssh/authorized_keys",
            written = "/tmp/dlink/authorized_keys",
        )

        assertWrittenInPlaceThroughTheLink(s)
    }

    @Test
    fun `a link in a folder that is reached by a link of a folder is written in place`() = runTest {
        // /home/alice/dlink leads to /srv/d: the real folder of the link is not the one of its path.
        val s = server(
            link = "/home/alice/dlink/report.pdf",
            canonical = "/srv/d/real.conf",
            written = "real.conf",
        )

        assertWrittenInPlaceThroughTheLink(s)
    }

    @Test
    fun `a readlink that the server refuses is written in place`() = runTest {
        val s = server(
            link = "/home/alice/report.pdf",
            canonical = "/home/alice/real.conf",
            readlinkFailure = SFTPException(Response.StatusCode.FAILURE, "Failure"),
        )

        assertWrittenInPlaceThroughTheLink(s)
    }

    @Test
    fun `a server without readlink is written in place`() = runTest {
        val s = server(
            link = "/home/alice/report.pdf",
            canonical = "/home/alice/real.conf",
            readlinkFailure = SFTPException(Response.StatusCode.OP_UNSUPPORTED, "Unsupported"),
        )

        assertWrittenInPlaceThroughTheLink(s)
    }

    @Test
    fun `a target that readlink gives empty does not lead to the file, it is written in place`() = runTest {
        val s = server(link = "/home/alice/report.pdf", canonical = "/home/alice/real.conf", written = "")

        assertWrittenInPlaceThroughTheLink(s)
    }

    @Test
    fun `a target that leaves the root does not lead to the file, it is written in place`() = runTest {
        val s = server(link = "/home/alice/report.pdf", canonical = "/home/alice/real.conf", written = "../../../../x")

        assertWrittenInPlaceThroughTheLink(s)
    }

    @Test
    fun `a channel lost while readlink is asked says the connection is lost, and nothing is opened`() = runTest {
        val s = server(
            link = "/home/alice/report.pdf",
            canonical = "/home/alice/real.conf",
            readlinkFailure = SFTPException("EOF while reading packet"),
        )

        val result = replace(s)

        assertEquals(SFTP_CONNECTION_LOST_MESSAGE, (result as SshResult.Error).message)
        verify(exactly = 0) { s.sftp.open(any<String>(), any<Set<OpenMode>>(), any<FileAttributes>()) }
        verify(exactly = 0) { s.sftp.open(any<String>(), any<Set<OpenMode>>()) }
        verify(exactly = 0) { s.sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) }
    }

    // ── A link that leads straight to the file: replaced through the temporary file ──

    @Test
    fun `a simple link of the user with an absolute target replaces the real file`() = runTest {
        val s = server(link = "/home/alice/report.pdf", canonical = "/srv/data/real.conf", written = "/srv/data/real.conf")

        assertRenamedOverTheRealFile(s)
    }

    @Test
    fun `a simple link of the user with a relative target replaces the real file`() = runTest {
        val s = server(link = "/home/alice/link.conf", canonical = "/home/alice/real.conf", written = "real.conf")

        assertRenamedOverTheRealFile(s)
    }

    @Test
    fun `a relative target with a segment of two dots is written in place, even when nothing on its way is a link`() = runTest {
        val s = server(
            link = "/home/alice/conf/link.conf",
            canonical = "/home/alice/data/real.conf",
            written = "../data/real.conf",
        )

        assertWrittenInPlaceThroughTheLink(s)
    }

    @Test
    fun `a link reached through a link of a folder whose target goes up is written in place, text and disk agreeing by chance`() = runTest {
        // ~/conf is a link to /tmp/c, /tmp/c/out a link of the user to ../x, and a third party made /tmp/x a
        // link to /home/t/x. For /home/t/conf/out, realpath says /home/t/x and so does the text
        // /home/t/conf/../x, where the open in place would have gone to /tmp/x, which the kernel refuses.
        val s = server(
            link = "/home/t/conf/out",
            canonical = "/home/t/x",
            written = "../x",
        )

        assertWrittenInPlaceThroughTheLink(s)
    }

    @Test
    fun `an absolute target with a segment of two dots is written in place too`() = runTest {
        val s = server(
            link = "/home/alice/report.pdf",
            canonical = "/srv/data/real.conf",
            written = "/srv/other/../data/real.conf",
        )

        assertWrittenInPlaceThroughTheLink(s)
    }

    @Test
    fun `a name that holds two dots without being a segment is followed`() = runTest {
        val s = server(link = "/home/alice/link.conf", canonical = "/home/alice/a..b", written = "a..b")

        assertRenamedOverTheRealFile(s)
    }

    @Test
    fun `a name that starts with two dots is followed as well`() = runTest {
        val s = server(link = "/home/alice/link.conf", canonical = "/home/alice/..env", written = "..env")

        assertRenamedOverTheRealFile(s)
    }

    @Test
    fun `a name that ends with two dots is followed as well`() = runTest {
        val s = server(link = "/home/alice/link.conf", canonical = "/home/alice/sub/conf..", written = "sub/conf..")

        assertRenamedOverTheRealFile(s)
    }

    @Test
    fun `a relative target that starts with a dot replaces the real file`() = runTest {
        val s = server(link = "/home/alice/link.conf", canonical = "/home/alice/real.conf", written = "./real.conf")

        assertRenamedOverTheRealFile(s)
    }

    @Test
    fun `a link at the root with a relative target replaces the real file`() = runTest {
        val s = server(link = "/link.conf", canonical = "/real.conf", written = "real.conf")

        assertRenamedOverTheRealFile(s)
    }

    @Test
    fun `an absolute target written with extra slashes still leads straight to the file`() = runTest {
        val s = server(link = "/home/alice/report.pdf", canonical = "/srv/data/real.conf", written = "//srv/data//real.conf")

        assertRenamedOverTheRealFile(s)
    }
}
