// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.testutil.RecordingTree
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.TimeoutException

/**
 * A confirmed replacement ([SftpManager.writeFile] with `replaceExisting`) never empties the old file
 * before the new one is complete: the content goes to a hidden temporary file of the same folder, which
 * takes the place of the old file by `posix-rename@openssh.com` once it is closed. When that cannot be
 * done safely, the old way (open with truncate) is kept, decided before the first byte.
 *
 * The SFTP client is a strict mock: a call that a test did not stub fails it. The attributes are built
 * for real, as an OpenSSH server sends them.
 */
class SftpManagerReplaceTest {

    private val sid = "3f2a9c1e-77b0-4c1d-9a55-session"
    private val path = "/home/user/report.pdf"
    private val tempPattern = Regex("""^/home/user/\.report\.pdf\.nextsh-part-[0-9a-f]{8}$""")

    private val inPlaceModes = setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)
    private val exclusiveModes = setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL)
    private val overwrite = setOf(RenameFlags.OVERWRITE)

    private val sessionManager: SshSessionManager = mockk(relaxed = true)

    /** A new manager for every [server]: an open client is kept by the manager, a test that loops needs a fresh one. */
    private var sftpManager = SftpManager(sessionManager)

    /** The mocks of one test: the client, and the handle that every temporary file opens as. */
    private class Server(val sftp: SFTPClient, val engine: SFTPEngine, val temp: RemoteFile) {
        /** The names the temporary file was opened under, in order. */
        val tempPaths = mutableListOf<String>()

        /** The one temporary name that was drawn. */
        val tempPath: String get() = tempPaths.single()
    }

    /**
     * A session with its SFTP client open, a server that has [old] at [path] (nothing when null), the
     * extension `posix-rename@openssh.com` when [extension], and a temporary file that opens as [Server.temp]
     * and whose owner is the one of [tempOwner]. Renaming and deleting work unless a test says otherwise.
     */
    private suspend fun server(
        old: FileAttributes? = file(),
        extension: Boolean = true,
        tempOwner: FileAttributes = file(),
    ): Server {
        sftpManager = SftpManager(sessionManager)
        val ssh = mockk<SSHClient>()
        val sftp = mockk<SFTPClient>()
        every { ssh.newSFTPClient() } returns sftp
        every { sessionManager.getClient(sid) } returns ssh
        sftpManager.openSftp(sid)

        val engine = mockk<SFTPEngine>()
        every { sftp.sftpEngine } returns engine
        every { engine.supportsServerExtension("posix-rename", "openssh.com") } returns extension

        if (old == null) {
            every { sftp.lstat(path) } throws SFTPException(Response.StatusCode.NO_SUCH_FILE, "No such file")
        } else {
            every { sftp.lstat(path) } returns old
        }

        val temp = mockk<RemoteFile>(relaxed = true)
        every { temp.fetchAttributes() } returns tempOwner
        val server = Server(sftp, engine, temp)
        every { sftp.open(any<String>(), any<Set<OpenMode>>(), any<FileAttributes>()) } answers {
            server.tempPaths += firstArg<String>()
            temp
        }
        every { sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) } just Runs
        every { sftp.rm(any<String>()) } just Runs
        return server
    }

    /**
     * Attributes the way an OpenSSH server sends them: a type, the nine permission bits, an owner. The
     * size and the time of modification are only there when a test asks for them.
     */
    private fun file(
        permissions: Int = 0b110_100_100,
        type: FileMode.Type = FileMode.Type.REGULAR,
        owner: Pair<Int, Int>? = 1000 to 1000,
        size: Long? = null,
        mtime: Long? = null,
    ): FileAttributes {
        val builder = FileAttributes.Builder().withType(type).withPermissions(permissions)
        if (owner != null) builder.withUIDGID(owner.first, owner.second)
        if (size != null) builder.withSize(size)
        if (mtime != null) builder.withAtimeMtime(mtime, mtime)
        return builder.build()
    }

    /** The attributes of a server that shows an owner but neither a type nor permissions. */
    private fun fileWithoutMode(): FileAttributes = FileAttributes.Builder().withUIDGID(1000, 1000).build()

    private fun source(size: Int = 3) = ByteArrayInputStream(ByteArray(size) { it.toByte() })

    private suspend fun replace(size: Int = 3, onProgress: (Long, Long) -> Unit = { _, _ -> }): SshResult<Unit> =
        sftpManager.writeFile(sid, path, source(size), size.toLong(), replaceExisting = true, onProgress = onProgress)

    private fun SshResult<*>.messageOfError(): String = (this as SshResult.Error).message

    private fun Server.verifyNoTemporaryFile() {
        verify(exactly = 0) { sftp.open(any<String>(), any<Set<OpenMode>>(), any<FileAttributes>()) }
    }

    private fun Server.verifyNoRename() {
        verify(exactly = 0) { sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) }
    }

    private fun Server.verifyNothingDeleted() {
        verify(exactly = 0) { sftp.rm(any<String>()) }
    }

    // ── The way through: temporary file, close, rename ───────────────────────────

    @Test
    fun `a replacement writes a hidden temporary file, closes it, then renames it over the target`() = runTest {
        val s = server()

        val result = replace(size = 5)

        assertTrue(result is SshResult.Success)
        assertTrue(tempPattern.matches(s.tempPath), s.tempPath)
        verifyOrder {
            s.temp.write(0L, any<ByteArray>(), 0, 5)
            s.temp.close()
            s.sftp.rename(s.tempPath, path, overwrite)
        }
        // The target is never opened, and the temporary file is closed once, by the end of the copy.
        verify(exactly = 0) { s.sftp.open(path, any<Set<OpenMode>>()) }
        verify(exactly = 1) { s.temp.close() }
        s.verifyNothingDeleted()
    }

    @Test
    fun `the temporary file is created new and private, with the permissions in the open itself`() = runTest {
        val s = server()
        val modes = mutableListOf<Set<OpenMode>>()
        val attributes = mutableListOf<FileAttributes>()
        every { s.sftp.open(any<String>(), capture(modes), capture(attributes)) } returns s.temp

        replace()

        assertEquals(listOf(exclusiveModes), modes)
        val sent = attributes.single()
        assertTrue(sent.has(FileAttributes.Flag.MODE))
        assertEquals(0b110_000_000, sent.mode.permissionsMask)
        assertFalse(sent.has(FileAttributes.Flag.UIDGID))
    }

    @Test
    fun `the progress is reported while the temporary file is written`() = runTest {
        server()
        val progress = mutableListOf<Pair<Long, Long>>()

        replace(size = 5) { done, total -> progress += done to total }

        assertEquals(listOf(5L to 5L), progress)
    }

    @Test
    fun `the name of the temporary file is known during the copy and stays known once it was renamed`() = runTest {
        val s = server()
        val during = mutableListOf<Set<String>>()
        every { s.temp.write(any(), any<ByteArray>(), any(), any()) } answers {
            during += sftpManager.knownUploadTempNames()
        }
        assertTrue(sftpManager.knownUploadTempNames().isEmpty())

        replace(size = 5)

        val name = s.tempPath.substringAfterLast('/')
        assertEquals(listOf(setOf(name)), during)
        // Gone from the server by the rename: remembered, for a listing that started before.
        assertEquals(setOf(name), sftpManager.knownUploadTempNames())
    }

    @Test
    fun `a temporary file deleted with success after a failure stays known by its name`() = runTest {
        val s = server()
        every { s.temp.write(any(), any<ByteArray>(), any(), any()) } throws
            SFTPException(Response.StatusCode.FAILURE, "refused")

        val result = replace(size = 5)

        assertTrue(result is SshResult.Error)
        assertEquals(setOf(s.tempPath.substringAfterLast('/')), sftpManager.knownUploadTempNames())
    }

    @Test
    fun `a temporary file left on the server after a lost channel is not known, it is a leftover`() = runTest {
        val s = server()
        // A lost channel, as SSHJ reports it (no status): not a local I/O error, which removes the file.
        every { s.temp.write(any(), any<ByteArray>(), any(), any()) } throws SFTPException("EOF while reading packet")

        val result = replace(size = 5)

        assertTrue(result is SshResult.Error)
        s.verifyNothingDeleted()
        assertTrue(sftpManager.knownUploadTempNames().isEmpty())
    }

    @Test
    fun `a temporary file whose deletion was refused is not known, it is a leftover`() = runTest {
        val s = server()
        every { s.temp.write(any(), any<ByteArray>(), any(), any()) } throws
            SFTPException(Response.StatusCode.FAILURE, "refused")
        every { s.sftp.rm(any<String>()) } throws SFTPException(Response.StatusCode.PERMISSION_DENIED, "denied")

        replace(size = 5)

        assertTrue(sftpManager.knownUploadTempNames().isEmpty())
    }

    @Test
    fun `only the last names of vanished temporary files are kept`() = runTest {
        server()

        repeat(TEMP_NAMES_GONE_KEPT + 10) { replace(size = 1) }

        assertEquals(TEMP_NAMES_GONE_KEPT, sftpManager.knownUploadTempNames().size)
    }

    @Test
    fun `an empty file is created, closed and renamed like any other`() = runTest {
        val s = server()

        val result = replace(size = 0)

        assertTrue(result is SshResult.Success)
        verify(exactly = 0) { s.temp.write(any(), any<ByteArray>(), any(), any()) }
        verifyOrder {
            s.temp.close()
            s.sftp.rename(s.tempPath, path, overwrite)
        }
    }

    @Test
    fun `a file at the root has its temporary file at the root, with a single slash`() = runTest {
        val s = server()
        every { s.sftp.lstat("/report.pdf") } returns file()
        val names = mutableListOf<String>()
        every { s.sftp.open(capture(names), any<Set<OpenMode>>(), any<FileAttributes>()) } returns s.temp

        val result = sftpManager.writeFile(sid, "/report.pdf", source(), 3L, replaceExisting = true) { _, _ -> }

        assertTrue(result is SshResult.Success)
        assertTrue(Regex("""^/\.report\.pdf\.nextsh-part-[0-9a-f]{8}$""").matches(names.single()), names.toString())
    }

    @Test
    fun `an entry whose type the server does not give is replaced like a file`() = runTest {
        val s = server(old = file(type = FileMode.Type.UNKNOWN))

        val result = replace()

        assertTrue(result is SshResult.Success)
        verify(exactly = 1) { s.sftp.rename(s.tempPath, path, overwrite) }
    }

    // ── Permissions and owner ────────────────────────────────────────────────────

    @Test
    fun `the permissions of the old file are copied before the close, without setuid`() = runTest {
        val s = server(old = file(permissions = 0b100_111_101_101))
        val applied = mutableListOf<FileAttributes>()
        every { s.temp.setAttributes(capture(applied)) } just Runs

        val result = replace()

        assertTrue(result is SshResult.Success)
        val sent = applied.single()
        assertTrue(sent.has(FileAttributes.Flag.MODE))
        assertEquals(0b111_101_101, sent.mode.permissionsMask)
        assertFalse(sent.has(FileAttributes.Flag.UIDGID))
        verifyOrder {
            s.temp.setAttributes(any())
            s.temp.close()
            s.sftp.rename(s.tempPath, path, overwrite)
        }
    }

    @Test
    fun `permissions that cannot be copied are not fatal, the file is renamed all the same`() = runTest {
        val s = server()
        every { s.temp.setAttributes(any()) } throws SFTPException(Response.StatusCode.PERMISSION_DENIED, "Permission denied")

        val result = replace()

        assertTrue(result is SshResult.Success)
        verify(exactly = 1) { s.sftp.rename(s.tempPath, path, overwrite) }
        s.verifyNothingDeleted()
    }

    @Test
    fun `a different owner that can be given back keeps the atomic way`() = runTest {
        val s = server(old = file(owner = 1001 to 1002), tempOwner = file(owner = 1000 to 1000))
        val applied = mutableListOf<FileAttributes>()
        every { s.temp.setAttributes(capture(applied)) } just Runs

        val result = replace()

        assertTrue(result is SshResult.Success)
        val owner = applied.first { it.has(FileAttributes.Flag.UIDGID) }
        assertEquals(1001, owner.uid)
        assertEquals(1002, owner.gid)
        verify(exactly = 1) { s.sftp.rename(s.tempPath, path, overwrite) }
        verify(exactly = 0) { s.sftp.open(path, any<Set<OpenMode>>()) }
    }

    @Test
    fun `a different owner that cannot be given back closes and removes the temporary file, then writes in place`() = runTest {
        val s = server(old = file(owner = 0 to 0), tempOwner = file(owner = 1000 to 1000))
        every {
            s.temp.setAttributes(match { it.has(FileAttributes.Flag.UIDGID) })
        } throws SFTPException(Response.StatusCode.PERMISSION_DENIED, "Operation not permitted")
        val target = mockk<RemoteFile>(relaxed = true)
        every { s.sftp.open(path, inPlaceModes) } returns target

        val result = replace()

        assertTrue(result is SshResult.Success)
        // Nothing of the source went to the temporary file, which is closed then removed.
        verify(exactly = 0) { s.temp.write(any(), any<ByteArray>(), any(), any()) }
        verifyOrder {
            s.temp.close()
            s.sftp.rm(s.tempPath)
            s.sftp.open(path, inPlaceModes)
            target.write(0L, any<ByteArray>(), 0, 3)
        }
        s.verifyNoRename()
    }

    @Test
    fun `an old file whose owner the server does not show gets the old way, with no temporary file`() = runTest {
        val s = server(old = file(owner = null))
        val target = mockk<RemoteFile>(relaxed = true)
        every { s.sftp.open(path, inPlaceModes) } returns target

        val result = replace()

        assertTrue(result is SshResult.Success)
        verify(exactly = 1) { target.write(0L, any<ByteArray>(), 0, 3) }
        s.verifyNoTemporaryFile()
        s.verifyNoRename()
        s.verifyNothingDeleted()
    }

    @Test
    fun `a temporary file whose owner the server does not show is closed and removed, then the old way is taken`() = runTest {
        val s = server(tempOwner = file(owner = null))
        val target = mockk<RemoteFile>(relaxed = true)
        every { s.sftp.open(path, inPlaceModes) } returns target

        val result = replace()

        assertTrue(result is SshResult.Success)
        verify(exactly = 0) { s.temp.write(any(), any<ByteArray>(), any(), any()) }
        verifyOrder {
            s.temp.close()
            s.sftp.rm(s.tempPath)
            s.sftp.open(path, inPlaceModes)
            target.write(0L, any<ByteArray>(), 0, 3)
        }
        s.verifyNoRename()
    }

    @Test
    fun `an old file whose permissions the server does not show gets the old way, with no temporary file`() = runTest {
        val s = server(old = fileWithoutMode())
        val target = mockk<RemoteFile>(relaxed = true)
        every { s.sftp.open(path, inPlaceModes) } returns target

        val result = replace()

        assertTrue(result is SshResult.Success)
        verify(exactly = 1) { target.write(0L, any<ByteArray>(), 0, 3) }
        s.verifyNoTemporaryFile()
        s.verifyNoRename()
        s.verifyNothingDeleted()
    }

    @Test
    fun `a server that refuses to show the attributes of the temporary file gets the old way, the temporary file closed and removed`() = runTest {
        val s = server()
        every { s.temp.fetchAttributes() } throws SFTPException(Response.StatusCode.FAILURE, "Failure")
        val target = mockk<RemoteFile>(relaxed = true)
        every { s.sftp.open(path, inPlaceModes) } returns target

        val result = replace()

        assertTrue(result is SshResult.Success)
        verify(exactly = 0) { s.temp.write(any(), any<ByteArray>(), any(), any()) }
        verifyOrder {
            s.temp.close()
            s.sftp.rm(s.tempPath)
            s.sftp.open(path, inPlaceModes)
            target.write(0L, any<ByteArray>(), 0, 3)
        }
        s.verifyNoRename()
    }

    @Test
    fun `a channel lost while the attributes of the temporary file are read says the connection is lost, and nothing more is asked`() = runTest {
        val s = server()
        every { s.temp.fetchAttributes() } throws SFTPException("EOF while reading packet")

        val result = replace()

        assertEquals(SFTP_CONNECTION_LOST_MESSAGE, result.messageOfError())
        verify(exactly = 0) { s.temp.close() }
        s.verifyNothingDeleted()
        s.verifyNoRename()
    }

    // ── What the atomic way cannot do: the old way, decided before the first byte ─

    @Test
    fun `a server without posix-rename gets the old way, with no temporary file and no rename`() = runTest {
        val s = server(extension = false)
        val target = mockk<RemoteFile>(relaxed = true)
        every { s.sftp.open(path, inPlaceModes) } returns target

        val result = replace()

        assertTrue(result is SshResult.Success)
        verify(exactly = 1) { target.write(0L, any<ByteArray>(), 0, 3) }
        s.verifyNoTemporaryFile()
        s.verifyNoRename()
        s.verifyNothingDeleted()
    }

    @Test
    fun `a file its owner protected against writing is opened in place, which decides`() = runTest {
        val s = server(old = file(permissions = 0b100_100_100))
        every {
            s.sftp.open(path, inPlaceModes)
        } throws SFTPException(Response.StatusCode.PERMISSION_DENIED, "Permission denied")

        val result = replace()

        assertEquals(SFTP_PERMISSION_DENIED_MESSAGE, result.messageOfError())
        s.verifyNoTemporaryFile()
        s.verifyNoRename()
        s.verifyNothingDeleted()
    }

    @Test
    fun `a file protected against writing is still written when the server lets the open pass, as root`() = runTest {
        val s = server(old = file(permissions = 0b100_100_100))
        val target = mockk<RemoteFile>(relaxed = true)
        every { s.sftp.open(path, inPlaceModes) } returns target

        val result = replace()

        assertTrue(result is SshResult.Success)
        verify(exactly = 1) { target.write(0L, any<ByteArray>(), 0, 3) }
        s.verifyNoTemporaryFile()
    }

    @Test
    fun `a folder where the temporary file cannot be created gets the old way, by status or by text`() = runTest {
        for (refusal in listOf(
            SFTPException(Response.StatusCode.PERMISSION_DENIED, "Failure"),
            SFTPException("Permission denied"),
        )) {
            val s = server()
            every { s.sftp.open(any<String>(), any<Set<OpenMode>>(), any<FileAttributes>()) } throws refusal
            val target = mockk<RemoteFile>(relaxed = true)
            every { s.sftp.open(path, inPlaceModes) } returns target

            val result = replace()

            assertTrue(result is SshResult.Success, refusal.toString())
            verify(exactly = 1) { target.write(0L, any<ByteArray>(), 0, 3) }
            s.verifyNoRename()
            s.verifyNothingDeleted()
        }
    }

    // ── The name of the temporary file is taken ──────────────────────────────────

    @Test
    fun `a temporary name that is taken is drawn again`() = runTest {
        val s = server()
        every { s.sftp.lstat(match { it.contains("nextsh-part") }) } returns file()
        val drawn = mutableListOf<String>()
        every { s.sftp.open(any<String>(), any<Set<OpenMode>>(), any<FileAttributes>()) } answers {
            drawn += firstArg<String>()
            if (drawn.size == 1) throw SFTPException(Response.StatusCode.FAILURE, "Failure") else s.temp
        }

        val result = replace()

        assertTrue(result is SshResult.Success)
        assertEquals(2, drawn.size)
        assertNotEquals(drawn[0], drawn[1])
        assertTrue(tempPattern.matches(drawn[1]), drawn[1])
        verify(exactly = 1) { s.sftp.rename(drawn[1], path, overwrite) }
    }

    @Test
    fun `three temporary names taken in a row end the upload, with nothing created and nothing renamed`() = runTest {
        val s = server()
        every { s.sftp.lstat(match { it.contains("nextsh-part") }) } returns file()
        every {
            s.sftp.open(any<String>(), any<Set<OpenMode>>(), any<FileAttributes>())
        } throws SFTPException(Response.StatusCode.FILE_ALREADY_EXISTS, "File already exists")

        val result = replace()

        assertEquals(SFTP_REPLACE_FAILED_KEPT_MESSAGE, result.messageOfError())
        verify(exactly = 3) { s.sftp.open(any<String>(), any<Set<OpenMode>>(), any<FileAttributes>()) }
        s.verifyNoRename()
        s.verifyNothingDeleted()
        verify(exactly = 0) { s.temp.write(any(), any<ByteArray>(), any(), any()) }
    }

    @Test
    fun `a refusal that is not a name taken stops at the first try`() = runTest {
        val s = server()
        every { s.sftp.lstat(match { it.contains("nextsh-part") }) } throws
            SFTPException(Response.StatusCode.NO_SUCH_FILE, "No such file")
        every {
            s.sftp.open(any<String>(), any<Set<OpenMode>>(), any<FileAttributes>())
        } throws SFTPException(Response.StatusCode.FAILURE, "Failure")

        val result = replace()

        assertEquals(SFTP_REPLACE_FAILED_KEPT_MESSAGE, result.messageOfError())
        verify(exactly = 1) { s.sftp.open(any<String>(), any<Set<OpenMode>>(), any<FileAttributes>()) }
        s.verifyNoRename()
        s.verifyNothingDeleted()
    }

    // ── A failure after the creation: the old file stays, the temporary file goes ─

    @Test
    fun `a failure of the server during the copy removes the temporary file and renames nothing`() = runTest {
        val s = server()
        every { s.temp.write(any(), any<ByteArray>(), any(), any()) } throws SFTPException(Response.StatusCode.FAILURE, "Failure")

        val result = replace()

        assertEquals(SFTP_REPLACE_FAILED_KEPT_MESSAGE, result.messageOfError())
        verifyOrder {
            s.temp.close()
            s.sftp.rm(s.tempPath)
        }
        s.verifyNoRename()
    }

    @Test
    fun `a disk that is full says the room is missing when the server says so`() = runTest {
        val s = server()
        every { s.temp.write(any(), any<ByteArray>(), any(), any()) } throws SFTPException("No space left on device")

        val result = replace()

        assertEquals(SFTP_NO_SPACE_MESSAGE, result.messageOfError())
        verify(exactly = 1) { s.sftp.rm(s.tempPath) }
        s.verifyNoRename()
    }

    @Test
    fun `a cancellation during the copy rethrows, removes the temporary file and renames nothing`() = runTest {
        val s = server()
        var reports = 0
        lateinit var job: Job
        job = launch {
            // Four blocks of 32 Ko: the job is cancelled by the second report, before the third block.
            replace(size = 100_000) { _, _ -> if (++reports == 2) job.cancel() }
        }

        job.join()

        assertTrue(job.isCancelled)
        verify(exactly = 1) { s.sftp.rm(s.tempPath) }
        verify(exactly = 2) { s.temp.write(any(), any<ByteArray>(), any(), any()) }
        s.verifyNoRename()
    }

    @Test
    fun `a channel lost during the copy says the connection is lost, and nothing more is asked of the server`() = runTest {
        for (lost in listOf(SFTPException("EOF while reading packet"), SFTPException(TimeoutException()))) {
            val s = server()
            every { s.temp.write(any(), any<ByteArray>(), any(), any()) } throws lost

            val result = replace()

            assertEquals(SFTP_CONNECTION_LOST_MESSAGE, result.messageOfError(), lost.toString())
            // Neither a delete nor a close: both would wait for a server that does not answer.
            verify(exactly = 0) { s.temp.close() }
            s.verifyNothingDeleted()
            s.verifyNoRename()
        }
    }

    @Test
    fun `a transport that fails during the copy says the connection is lost, and the removal is tried and may fail`() = runTest {
        val s = server()
        every { s.temp.write(any(), any<ByteArray>(), any(), any()) } throws IOException("Broken pipe")
        every { s.sftp.rm(any<String>()) } throws IOException("Broken pipe")

        val result = replace()

        assertEquals(SFTP_CONNECTION_LOST_MESSAGE, result.messageOfError())
        verify(exactly = 1) { s.sftp.rm(s.tempPath) }
        s.verifyNoRename()
    }

    @Test
    fun `a close that fails ends the upload, nothing is renamed and the temporary file is removed`() = runTest {
        val s = server()
        every { s.temp.close() } throws SFTPException(Response.StatusCode.FAILURE, "Failure")

        val result = replace()

        assertEquals(SFTP_REPLACE_FAILED_KEPT_MESSAGE, result.messageOfError())
        // Closed once: the clean-up does not try a second time.
        verify(exactly = 1) { s.temp.close() }
        verify(exactly = 1) { s.sftp.rm(s.tempPath) }
        s.verifyNoRename()
    }

    @Test
    fun `a failure that is not the server's says the same sentence, not the exception`() = runTest {
        val s = server()
        every { s.temp.write(any(), any<ByteArray>(), any(), any()) } throws IllegalStateException("db.interne.example secret detail")

        val message = replace().messageOfError()

        assertEquals(SFTP_REPLACE_FAILED_KEPT_MESSAGE, message)
        assertFalse(message.contains("secret"))
        verify(exactly = 1) { s.sftp.rm(s.tempPath) }
    }

    // ── The rename fails ─────────────────────────────────────────────────────────

    @Test
    fun `a rename onto a target that became a folder says the target changed, and removes the temporary file`() = runTest {
        val s = server()
        every { s.sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) } throws
            SFTPException(Response.StatusCode.FAILURE, "Failure")
        // The plan, the look before the rename, then the look after its refusal.
        every { s.sftp.lstat(path) } returnsMany
            listOf(file(), file(), file(type = FileMode.Type.DIRECTORY, permissions = 0b111_101_101))

        val result = replace()

        assertEquals(SFTP_UPLOAD_TARGET_CHANGED_MESSAGE, result.messageOfError())
        verify(exactly = 1) { s.sftp.rm(s.tempPath) }
    }

    @Test
    fun `a rename that fails when the target is still a file says the old file was kept, and the removal may fail too`() = runTest {
        val s = server()
        every { s.sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) } throws
            SFTPException(Response.StatusCode.FAILURE, "Failure")
        every { s.sftp.rm(any<String>()) } throws IOException("Broken pipe")

        val result = replace()

        // The error is the one of the rename, and the failed removal does not escape.
        assertEquals(SFTP_REPLACE_FAILED_KEPT_MESSAGE, result.messageOfError())
        verify(exactly = 1) { s.sftp.rm(s.tempPath) }
    }

    @Test
    fun `a rename refused for lack of rights says the permission text, and removes the temporary file`() = runTest {
        val s = server()
        every { s.sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) } throws
            SFTPException(Response.StatusCode.PERMISSION_DENIED, "Permission denied")

        val result = replace()

        assertEquals(SFTP_PERMISSION_DENIED_MESSAGE, result.messageOfError())
        verify(exactly = 1) { s.sftp.rm(s.tempPath) }
    }

    @Test
    fun `a rename that loses the channel says the connection is lost, without looking at the target nor removing`() = runTest {
        val s = server()
        every { s.sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) } throws SFTPException("EOF while reading packet")

        val result = replace()

        assertEquals(SFTP_CONNECTION_LOST_MESSAGE, result.messageOfError())
        // The lookups of the target are the plan and the look before the rename, none after the failure.
        verify(exactly = 2) { s.sftp.lstat(path) }
        s.verifyNothingDeleted()
    }

    // ── A link ───────────────────────────────────────────────────────────────────

    @Test
    fun `a link to a file replaces the real file, with a temporary file in the folder of the real file`() = runTest {
        val s = server()
        every { s.sftp.lstat(path) } returns file(type = FileMode.Type.SYMLINK, permissions = 0b111_111_111)
        every { s.sftp.canonicalize(path) } returns "/srv/data/real.conf"
        every { s.sftp.readlink(path) } returns "/srv/data/real.conf"
        every { s.sftp.lstat("/srv/data/real.conf") } returns file(permissions = 0b110_100_000)
        val applied = mutableListOf<FileAttributes>()
        every { s.temp.setAttributes(capture(applied)) } just Runs

        val result = replace()

        assertTrue(result is SshResult.Success)
        assertTrue(Regex("""^/srv/data/\.real\.conf\.nextsh-part-[0-9a-f]{8}$""").matches(s.tempPath), s.tempPath)
        verify(exactly = 1) { s.sftp.rename(s.tempPath, "/srv/data/real.conf", overwrite) }
        // The permissions are the ones of the real file, not the ones of the link.
        assertEquals(0b110_100_000, applied.single().mode.permissionsMask)
        // The link itself is neither opened nor renamed.
        verify(exactly = 0) { s.sftp.open(path, any<Set<OpenMode>>()) }
        verify(exactly = 0) { s.sftp.rename(any<String>(), path, any<Set<RenameFlags>>()) }
    }

    @Test
    fun `a link that the server does not resolve is written through in place`() = runTest {
        val s = server()
        every { s.sftp.lstat(path) } returns file(type = FileMode.Type.SYMLINK)
        // The server answers with a path that is still a link.
        every { s.sftp.canonicalize(path) } returns "/home/user/other-link"
        every { s.sftp.lstat("/home/user/other-link") } returns file(type = FileMode.Type.SYMLINK)
        val target = mockk<RemoteFile>(relaxed = true)
        every { s.sftp.open(path, inPlaceModes) } returns target

        val result = replace()

        assertTrue(result is SshResult.Success)
        verify(exactly = 1) { target.write(0L, any<ByteArray>(), 0, 3) }
        s.verifyNoTemporaryFile()
        s.verifyNoRename()
    }

    @Test
    fun `a server that cannot resolve a link at all gets the old way too`() = runTest {
        val s = server()
        every { s.sftp.lstat(path) } returns file(type = FileMode.Type.SYMLINK)
        every { s.sftp.canonicalize(path) } throws SFTPException(Response.StatusCode.OP_UNSUPPORTED, "Unsupported")
        val target = mockk<RemoteFile>(relaxed = true)
        every { s.sftp.open(path, inPlaceModes) } returns target

        val result = replace()

        assertTrue(result is SshResult.Success)
        s.verifyNoTemporaryFile()
        s.verifyNoRename()
    }

    @Test
    fun `a link that went broken is refused, with nothing opened`() = runTest {
        val s = server()
        every { s.sftp.lstat(path) } returns file(type = FileMode.Type.SYMLINK)
        every { s.sftp.canonicalize(path) } throws SFTPException(Response.StatusCode.NO_SUCH_FILE, "No such file")

        val result = replace()

        assertEquals(SFTP_UPLOAD_TARGET_CHANGED_MESSAGE, result.messageOfError())
        s.verifyNoTemporaryFile()
        verify(exactly = 0) { s.sftp.open(any<String>(), any<Set<OpenMode>>()) }
    }

    @Test
    fun `a link whose resolved path is gone is refused, with nothing opened`() = runTest {
        val s = server()
        every { s.sftp.lstat(path) } returns file(type = FileMode.Type.SYMLINK)
        every { s.sftp.canonicalize(path) } returns "/srv/data/gone.conf"
        every { s.sftp.lstat("/srv/data/gone.conf") } throws SFTPException(Response.StatusCode.NO_SUCH_FILE, "No such file")

        val result = replace()

        assertEquals(SFTP_UPLOAD_TARGET_CHANGED_MESSAGE, result.messageOfError())
        s.verifyNoTemporaryFile()
        verify(exactly = 0) { s.sftp.open(any<String>(), any<Set<OpenMode>>()) }
    }

    @Test
    fun `a link that leads to a folder is refused, with nothing opened`() = runTest {
        val s = server()
        every { s.sftp.lstat(path) } returns file(type = FileMode.Type.SYMLINK)
        every { s.sftp.canonicalize(path) } returns "/srv/data"
        every { s.sftp.lstat("/srv/data") } returns file(type = FileMode.Type.DIRECTORY)

        val result = replace()

        assertEquals(SFTP_UPLOAD_TARGET_CHANGED_MESSAGE, result.messageOfError())
        s.verifyNoTemporaryFile()
        verify(exactly = 0) { s.sftp.open(any<String>(), any<Set<OpenMode>>()) }
    }

    @Test
    fun `a channel lost while the link is resolved says the connection is lost`() = runTest {
        val s = server()
        every { s.sftp.lstat(path) } returns file(type = FileMode.Type.SYMLINK)
        every { s.sftp.canonicalize(path) } throws SFTPException("EOF while reading packet")

        val result = replace()

        assertEquals(SFTP_CONNECTION_LOST_MESSAGE, result.messageOfError())
        s.verifyNoTemporaryFile()
    }

    // ── What is at the target is not a file, or no longer there ──────────────────

    @Test
    fun `a target that became a folder, a pipe, a socket or a device is refused before anything is opened`() = runTest {
        for (type in listOf(
            FileMode.Type.DIRECTORY,
            FileMode.Type.FIFO_SPECIAL,
            FileMode.Type.SOCKET_SPECIAL,
            FileMode.Type.BLOCK_SPECIAL,
            FileMode.Type.CHAR_SPECIAL,
        )) {
            val s = server(old = file(type = type))

            val result = replace()

            assertEquals(SFTP_UPLOAD_TARGET_CHANGED_MESSAGE, result.messageOfError(), type.name)
            s.verifyNoTemporaryFile()
            verify(exactly = 0) { s.sftp.open(any<String>(), any<Set<OpenMode>>()) }
        }
    }

    @Test
    fun `a target that disappeared is created like a free name, exclusively and without a temporary file`() = runTest {
        val s = server(old = null)
        val target = mockk<RemoteFile>(relaxed = true)
        every { s.sftp.open(path, exclusiveModes) } returns target

        val result = replace()

        assertTrue(result is SshResult.Success)
        verify(exactly = 1) { target.write(0L, any<ByteArray>(), 0, 3) }
        s.verifyNoTemporaryFile()
        s.verifyNoRename()
    }

    @Test
    fun `a name that came back after the lookup is refused by the exclusive open, nothing is replaced`() = runTest {
        val s = server(old = null)
        // Free for the plan, taken when the open is refused.
        every { s.sftp.lstat(path) } throws SFTPException(Response.StatusCode.NO_SUCH_FILE, "No such file") andThen file()
        every { s.sftp.open(path, exclusiveModes) } throws SFTPException(Response.StatusCode.FAILURE, "Failure")

        val result = replace()

        assertEquals(SFTP_UPLOAD_TARGET_EXISTS_MESSAGE, result.messageOfError())
        s.verifyNoTemporaryFile()
        // Nothing was created by this upload, so there is nothing to remove, and the file that is there stays.
        s.verifyNothingDeleted()
    }

    @Test
    fun `a lookup of the target that fails says why, and nothing is opened`() = runTest {
        val s = server()
        every { s.sftp.lstat(path) } throws IOException("Broken pipe")

        assertEquals(SFTP_CONNECTION_LOST_MESSAGE, replace().messageOfError())

        every { s.sftp.lstat(path) } throws SFTPException(Response.StatusCode.FAILURE, "Failure")

        assertEquals(SFTP_REPLACE_FAILED_KEPT_MESSAGE, replace().messageOfError())

        every { s.sftp.lstat(path) } throws SFTPException(Response.StatusCode.PERMISSION_DENIED, "Permission denied")

        assertEquals(SFTP_PERMISSION_DENIED_MESSAGE, replace().messageOfError())
        s.verifyNoTemporaryFile()
        verify(exactly = 0) { s.sftp.open(any<String>(), any<Set<OpenMode>>()) }
    }

    // ── Another link case: a loop, a folder where nothing can be created ─────────

    @Test
    fun `a link in a loop is not resolved, the old way goes through it and the server decides`() = runTest {
        val s = server()
        every { s.sftp.lstat(path) } returns file(type = FileMode.Type.SYMLINK)
        every { s.sftp.canonicalize(path) } throws SFTPException(Response.StatusCode.FAILURE, "Failure")
        every { s.sftp.open(path, inPlaceModes) } throws SFTPException(Response.StatusCode.FAILURE, "Failure")

        val result = replace()

        // The old way, which may have emptied the file: the usual sentence, not "intact".
        assertEquals(SFTP_UPLOAD_FAILED_MESSAGE, result.messageOfError())
        s.verifyNoTemporaryFile()
        s.verifyNoRename()
        s.verifyNothingDeleted()
    }

    @Test
    fun `a link to a file in a folder where nothing can be created is written through in place, by the name of the link`() = runTest {
        val s = server()
        every { s.sftp.lstat(path) } returns file(type = FileMode.Type.SYMLINK, permissions = 0b111_111_111)
        every { s.sftp.canonicalize(path) } returns "/srv/data/real.conf"
        every { s.sftp.readlink(path) } returns "/srv/data/real.conf"
        every { s.sftp.lstat("/srv/data/real.conf") } returns file()
        every {
            s.sftp.open(any<String>(), any<Set<OpenMode>>(), any<FileAttributes>())
        } throws SFTPException(Response.StatusCode.PERMISSION_DENIED, "Permission denied")
        val target = mockk<RemoteFile>(relaxed = true)
        every { s.sftp.open(path, inPlaceModes) } returns target

        val result = replace()

        assertTrue(result is SshResult.Success)
        verify(exactly = 1) { target.write(0L, any<ByteArray>(), 0, 3) }
        // The real file is never opened by its own name: the link is what is written through.
        verify(exactly = 0) { s.sftp.open("/srv/data/real.conf", any<Set<OpenMode>>()) }
        s.verifyNoRename()
        s.verifyNothingDeleted()
    }

    @Test
    fun `a server without lstat shows a link as a file, so the old way goes through it`() = runTest {
        val s = server()
        every { s.sftp.lstat(path) } throws SFTPException(Response.StatusCode.OP_UNSUPPORTED, "Unsupported")
        every { s.sftp.statExistence(path) } returns file()
        val target = mockk<RemoteFile>(relaxed = true)
        every { s.sftp.open(path, inPlaceModes) } returns target

        val result = replace()

        assertTrue(result is SshResult.Success)
        verify(exactly = 1) { target.write(0L, any<ByteArray>(), 0, 3) }
        s.verifyNoTemporaryFile()
        s.verifyNoRename()
    }

    @Test
    fun `a server without lstat that shows nothing at the name creates the file exclusively`() = runTest {
        val s = server()
        every { s.sftp.lstat(path) } throws SFTPException(Response.StatusCode.OP_UNSUPPORTED, "Unsupported")
        every { s.sftp.statExistence(path) } returns null
        val target = mockk<RemoteFile>(relaxed = true)
        every { s.sftp.open(path, exclusiveModes) } returns target

        val result = replace()

        assertTrue(result is SshResult.Success)
        s.verifyNoTemporaryFile()
        s.verifyNoRename()
    }

    @Test
    fun `a server without lstat that shows a pipe, a folder, a socket or a device refuses the replacement, nothing is opened`() = runTest {
        for (type in listOf(
            FileMode.Type.FIFO_SPECIAL,
            FileMode.Type.DIRECTORY,
            FileMode.Type.SOCKET_SPECIAL,
            FileMode.Type.BLOCK_SPECIAL,
            FileMode.Type.CHAR_SPECIAL,
        )) {
            val s = server()
            every { s.sftp.lstat(path) } throws SFTPException(Response.StatusCode.OP_UNSUPPORTED, "Unsupported")
            every { s.sftp.statExistence(path) } returns file(type = type)

            val result = replace()

            // An open with truncate of a pipe that nobody reads would hold the server of the user.
            assertEquals(SFTP_UPLOAD_TARGET_CHANGED_MESSAGE, result.messageOfError(), type.name)
            s.verifyNoTemporaryFile()
            verify(exactly = 0) { s.sftp.open(any<String>(), any<Set<OpenMode>>()) }
            s.verifyNoRename()
        }
    }

    // ── A cancellation never gets to the open that empties the old file ──────────

    @Test
    fun `a job cancelled while the plan is made does not get to the open in place`() = runTest {
        val s = server(extension = false)
        lateinit var job: Job
        every { s.sftp.lstat(path) } answers {
            job.cancel()
            file()
        }

        job = launch { replace() }
        job.join()

        assertTrue(job.isCancelled)
        verify(exactly = 0) { s.sftp.open(any<String>(), any<Set<OpenMode>>()) }
        s.verifyNoTemporaryFile()
        s.verifyNoRename()
    }

    @Test
    fun `a job cancelled while a temporary file is made and given up does not get to the open in place`() = runTest {
        val s = server(old = file(owner = 0 to 0), tempOwner = file(owner = 1000 to 1000))
        lateinit var job: Job
        every { s.temp.setAttributes(match { it.has(FileAttributes.Flag.UIDGID) }) } answers {
            job.cancel()
            throw SFTPException(Response.StatusCode.PERMISSION_DENIED, "Operation not permitted")
        }

        job = launch { replace() }
        job.join()

        assertTrue(job.isCancelled)
        verify(exactly = 0) { s.sftp.open(path, any<Set<OpenMode>>()) }
        // The temporary file is given up all the same.
        verify(exactly = 1) { s.sftp.rm(s.tempPath) }
        s.verifyNoRename()
    }

    @Test
    fun `a cancellation right after the rename leaves the new file in place, and removes nothing`() = runTest {
        val s = server()
        lateinit var job: Job
        every { s.sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) } answers { job.cancel() }

        job = launch { replace() }
        job.join()

        assertTrue(job.isCancelled)
        verify(exactly = 1) { s.sftp.rename(s.tempPath, path, overwrite) }
        // Neither the temporary file (it is the file now) nor the target is ever deleted.
        s.verifyNothingDeleted()
    }

    // ── The target is looked at again before the rename ──────────────────────────

    /** A server whose target shows [before] at the plan and [after] just before the rename (nothing when null). */
    private suspend fun serverWhoseTargetShows(before: FileAttributes, after: FileAttributes?): Server {
        val s = server(old = before)
        var lookups = 0
        every { s.sftp.lstat(path) } answers {
            if (++lookups == 1) before else after ?: throw SFTPException(Response.StatusCode.NO_SUCH_FILE, "No such file")
        }
        return s
    }

    private fun Server.verifyRefusedBeforeTheRename(result: SshResult<Unit>) {
        assertEquals(SFTP_UPLOAD_TARGET_CHANGED_MESSAGE, result.messageOfError())
        verify(exactly = 0) { sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) }
        verify(exactly = 1) { sftp.rm(tempPath) }
    }

    @Test
    fun `a target that another writer replaced during the upload is not overwritten, by its size`() = runTest {
        val s = serverWhoseTargetShows(file(size = 100, mtime = 1_700_000_000), file(size = 250, mtime = 1_700_000_000))

        s.verifyRefusedBeforeTheRename(replace())
    }

    @Test
    fun `a target that another writer replaced during the upload is not overwritten, by its time of modification`() = runTest {
        val s = serverWhoseTargetShows(file(size = 100, mtime = 1_700_000_000), file(size = 100, mtime = 1_700_000_500))

        s.verifyRefusedBeforeTheRename(replace())
    }

    @Test
    fun `a target that another writer replaced during the upload is not overwritten, by its owner`() = runTest {
        val s = serverWhoseTargetShows(file(owner = 1000 to 1000), file(owner = 1001 to 1000))

        s.verifyRefusedBeforeTheRename(replace())
    }

    @Test
    fun `a target that disappeared during the upload is not renamed over`() = runTest {
        val s = serverWhoseTargetShows(file(), null)

        s.verifyRefusedBeforeTheRename(replace())
    }

    @Test
    fun `a target that became a link during the upload is not renamed over`() = runTest {
        val s = serverWhoseTargetShows(file(), file(type = FileMode.Type.SYMLINK))

        s.verifyRefusedBeforeTheRename(replace())
    }

    @Test
    fun `an attribute that only one of the two looks shows is a change`() = runTest {
        val s = serverWhoseTargetShows(file(), file(mtime = 1_700_000_000))

        s.verifyRefusedBeforeTheRename(replace())
    }

    @Test
    fun `a target whose owner changed its permissions during the upload is not overwritten, whatever the bits`() = runTest {
        // chmod 0444 (protected against writing), chmod 0600 (a secret), and a setuid bit that appeared.
        for (changed in listOf(0b100_100_100, 0b110_000_000, 0b100_110_100_100)) {
            val s = serverWhoseTargetShows(file(permissions = 0b110_100_100), file(permissions = changed))

            s.verifyRefusedBeforeTheRename(replace())
        }
    }

    @Test
    fun `permissions that only one of the two looks shows are a change`() = runTest {
        val s = serverWhoseTargetShows(file(), fileWithoutMode())

        s.verifyRefusedBeforeTheRename(replace())
    }

    @Test
    fun `a target that shows the same size, time and owner is renamed over`() = runTest {
        val shown = file(size = 100, mtime = 1_700_000_000)
        val s = serverWhoseTargetShows(shown, file(size = 100, mtime = 1_700_000_000))

        val result = replace()

        assertTrue(result is SshResult.Success)
        verify(exactly = 1) { s.sftp.rename(s.tempPath, path, overwrite) }
        s.verifyNothingDeleted()
    }

    @Test
    fun `the target is looked at again after the close of the temporary file and before the rename`() = runTest {
        val s = server()

        replace()

        verifyOrder {
            s.temp.close()
            s.sftp.lstat(path)
            s.sftp.rename(s.tempPath, path, overwrite)
        }
        verify(exactly = 2) { s.sftp.lstat(path) }
    }
    // ── The upload of a free name does not change ────────────────────────────────

    @Test
    fun `an upload without replacement asks nothing of the engine and renames nothing`() = runTest {
        val s = server()
        val target = mockk<RemoteFile>(relaxed = true)
        every { s.sftp.open(path, exclusiveModes) } returns target

        val result = sftpManager.writeFile(sid, path, source(), 3L, replaceExisting = false) { _, _ -> }

        assertTrue(result is SshResult.Success)
        verify(exactly = 0) { s.sftp.sftpEngine }
        verify(exactly = 0) { s.sftp.lstat(any<String>()) }
        s.verifyNoRename()
        s.verifyNoTemporaryFile()
    }

    // ── The log ──────────────────────────────────────────────────────────────────

    @Test
    fun `the log of a replacement holds no path, no name, no temporary name and no exception`() = runTest {
        val secret = "/home/user/secret-report.pdf"
        val tree = RecordingTree()
        Timber.plant(tree)
        try {
            // A replacement that goes through, one that takes the old way, one that fails, and a failed removal.
            val ok = server()
            every { ok.sftp.lstat(secret) } returns file()
            sftpManager.writeFile(sid, secret, source(), 3L, replaceExisting = true) { _, _ -> }

            val noExtension = server(extension = false)
            every { noExtension.sftp.lstat(secret) } returns file()
            every { noExtension.sftp.open(secret, inPlaceModes) } returns mockk<RemoteFile>(relaxed = true)
            sftpManager.writeFile(sid, secret, source(), 3L, replaceExisting = true) { _, _ -> }

            val failing = server()
            every { failing.sftp.lstat(secret) } returns file()
            every {
                failing.temp.write(any(), any<ByteArray>(), any(), any())
            } throws SFTPException(Response.StatusCode.FAILURE, "Failure on /home/user/secret-report.pdf")
            every { failing.sftp.rm(any<String>()) } throws IOException("Cannot remove /home/user/.secret-report.pdf")
            sftpManager.writeFile(sid, secret, source(), 3L, replaceExisting = true) { _, _ -> }

            assertTrue(tree.entries.isNotEmpty())
            tree.entries.forEach { entry ->
                assertFalse(entry.message.contains("secret-report"), entry.message)
                assertFalse(entry.message.contains("nextsh-part"), entry.message)
                assertFalse(entry.message.contains("/home"), entry.message)
                assertFalse(entry.message.contains(sid), entry.message)
                assertNull(entry.throwable, "an exception was passed to the log: ${entry.message}")
            }
            assertTrue(tree.entries.any { it.message.contains("reason=NO_POSIX_RENAME") }, "the reason is not logged")
        } finally {
            Timber.uproot(tree)
        }
    }
}
