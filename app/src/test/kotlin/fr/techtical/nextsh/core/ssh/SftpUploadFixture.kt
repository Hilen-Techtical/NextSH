// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.RenameFlags
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPEngine
import java.io.IOException
import java.io.InputStream

/**
 * The strict SFTP client mock of the tests of what an upload does when its SOURCE misbehaves, for the
 * three ways an upload is written: under a free name (exclusive), through a temporary file, in place.
 */
internal class UploadFixture {
    val sid = "3f2a9c1e-77b0-4c1d-9a55-session"
    val path = "/home/user/report.pdf"
    val exclusiveModes = setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL)
    val inPlaceModes = setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)

    private val sessionManager: SshSessionManager = mockk(relaxed = true)

    /** The manager of the last client opened: an open client is kept by the manager, so each one gets its own. */
    var manager = SftpManager(sessionManager)
        private set

    private suspend fun client(): SFTPClient {
        manager = SftpManager(sessionManager)
        val ssh = mockk<SSHClient>()
        val sftp = mockk<SFTPClient>()
        every { ssh.newSFTPClient() } returns sftp
        every { sessionManager.getClient(sid) } returns ssh
        manager.openSftp(sid)
        return sftp
    }

    fun regularFile(owner: Pair<Int, Int> = 1000 to 1000): FileAttributes =
        FileAttributes.Builder().withType(FileMode.Type.REGULAR).withPermissions(0b110_100_100)
            .withUIDGID(owner.first, owner.second).build()

    /** What the partial file looks like to the handle and to `lstat`: the same, so that it is the upload's own. */
    private fun partial(): FileAttributes =
        FileAttributes.Builder().withType(FileMode.Type.REGULAR).withPermissions(0b110_100_100)
            .withSize(32768).withAtimeMtime(1_700_000_100, 1_700_000_100).withUIDGID(1000, 1000).build()

    /** A client whose exclusive open of [path] gives [remote], and whose deletion of the partial file works. */
    suspend fun creating(remote: RemoteFile): SFTPClient {
        val sftp = client()
        every { sftp.open(path, exclusiveModes) } returns remote
        every { remote.fetchAttributes() } returns partial()
        every { sftp.lstat(path) } returns partial()
        every { sftp.rm(path) } just Runs
        return sftp
    }

    /** The mocks of a replacement: the client, the temporary file, and the names it was opened under. */
    class Replacing(val sftp: SFTPClient, val temp: RemoteFile) {
        val tempPaths = mutableListOf<String>()
        val tempPath: String get() = tempPaths.single()
    }

    /**
     * A server that has [old] at [path]. With [extension] it has `posix-rename@openssh.com`: the
     * replacement goes through a temporary file that opens as [Replacing.temp] and whose owner is
     * [tempOwner]. Without it, the replacement is written in place.
     */
    suspend fun replacing(
        extension: Boolean,
        old: FileAttributes = regularFile(),
        tempOwner: FileAttributes = regularFile(),
    ): Replacing {
        val sftp = client()
        val engine = mockk<SFTPEngine>()
        every { sftp.sftpEngine } returns engine
        every { engine.supportsServerExtension("posix-rename", "openssh.com") } returns extension
        every { sftp.lstat(path) } returns old
        val temp = mockk<RemoteFile>(relaxed = true)
        every { temp.fetchAttributes() } returns tempOwner
        val server = Replacing(sftp, temp)
        every { sftp.open(any<String>(), any<Set<OpenMode>>(), any<FileAttributes>()) } answers {
            server.tempPaths += firstArg<String>()
            temp
        }
        every { sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) } just Runs
        every { sftp.rm(any<String>()) } just Runs
        return server
    }

    /**
     * A source that gives [goodBlocks] full blocks of 32 Ko and then fails with a text that quotes a
     * document name. [closed] says whether the upload closed it (it must not: its owner does).
     */
    class FailingSource(private val goodBlocks: Int) : InputStream() {
        private var calls = 0
        var closed = false
            private set

        override fun read(): Int = throw IOException("Provider closed secret-document.pdf")

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (calls++ >= goodBlocks) throw IOException("Provider closed secret-document.pdf")
            val count = minOf(len, 32768)
            for (i in 0 until count) b[off + i] = (i % 251).toByte()
            return count
        }

        override fun close() {
            closed = true
        }
    }
}
