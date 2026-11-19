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
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPEngine
import net.schmizz.sshj.sftp.SFTPException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.TimeoutException

/**
 * An upload to a free name that fails or is cancelled removes, at best, the partial file that it just
 * created under the real name (it was opened with [OpenMode.EXCL], so the file is the upload's own),
 * unless the channel is lost, and only if the name still leads to that file: its size, time of
 * modification and owner, read through the handle before the close, must be the ones `lstat` shows. A
 * file that was there before is never removed: not when the name is taken, not when a replacement
 * overwrites it in place, not when another writer replaced the entry during the copy.
 *
 * The SFTP client is a strict mock: a deletion that a test did not allow is seen as one.
 */
class SftpManagerUploadCleanupTest {

    private val sid = "3f2a9c1e-77b0-4c1d-9a55-session"
    private val path = "/home/user/report.pdf"
    private val exclusive = setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL)
    private val truncate = setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)

    private val sessionManager: SshSessionManager = mockk(relaxed = true)

    /** A new manager for every client: an open client is kept by the manager, a test that loops needs a fresh one. */
    private var sftpManager = SftpManager(sessionManager)

    private suspend fun openedSftp(): SFTPClient {
        sftpManager = SftpManager(sessionManager)
        val ssh = mockk<SSHClient>()
        val sftp = mockk<SFTPClient>()
        every { ssh.newSFTPClient() } returns sftp
        every { sessionManager.getClient(sid) } returns ssh
        sftpManager.openSftp(sid)
        return sftp
    }

    private fun regularFile(): FileAttributes =
        FileAttributes.Builder().withType(FileMode.Type.REGULAR).withPermissions(0b110_100_100).withUIDGID(1000, 1000).build()

    private fun content(size: Int = 3) = ByteArrayInputStream(ByteArray(size) { it.toByte() })

    private fun SshResult<*>.messageOfError(): String = (this as SshResult.Error).message

    /** What the partial file looks like: a size, a time of modification and an owner, as `lstat` shows them. */
    private fun partial(
        size: Long? = 32768,
        mtime: Long? = 1_700_000_100,
        owner: Pair<Int, Int>? = 1000 to 1000,
        type: FileMode.Type? = FileMode.Type.REGULAR,
        permissions: Int? = 0b110_100_100,
    ): FileAttributes {
        val builder = FileAttributes.Builder()
        if (type != null) builder.withType(type)
        if (permissions != null) builder.withPermissions(permissions)
        if (size != null) builder.withSize(size)
        if (mtime != null) builder.withAtimeMtime(mtime, mtime)
        if (owner != null) builder.withUIDGID(owner.first, owner.second)
        return builder.build()
    }

    /**
     * A client whose exclusive open of [path] gives [remote], whose deletion works, and where the partial
     * file shows [seenByTheHandle] through its handle and [seenAtTheName] at the name (nothing when null).
     */
    private suspend fun clientCreating(
        remote: RemoteFile,
        seenByTheHandle: FileAttributes = partial(),
        seenAtTheName: FileAttributes? = partial(),
    ): SFTPClient {
        val sftp = openedSftp()
        every { sftp.open(path, exclusive) } returns remote
        every { remote.fetchAttributes() } returns seenByTheHandle
        if (seenAtTheName == null) {
            every { sftp.lstat(path) } throws SFTPException(Response.StatusCode.NO_SUCH_FILE, "No such file")
        } else {
            every { sftp.lstat(path) } returns seenAtTheName
        }
        every { sftp.rm(path) } just Runs
        return sftp
    }

    private suspend fun upload(size: Int = 3, onProgress: (Long, Long) -> Unit = { _, _ -> }): SshResult<Unit> =
        sftpManager.writeFile(sid, path, content(size), size.toLong(), replaceExisting = false, onProgress = onProgress)

    // ── A name that is free: the partial file goes ───────────────────────────────

    @Test
    fun `a failure of the server during the copy removes the partial file, after closing it`() = runTest {
        val remote = mockk<RemoteFile>(relaxed = true)
        every { remote.write(any(), any<ByteArray>(), any(), any()) } throws SFTPException(Response.StatusCode.FAILURE, "Failure")
        val sftp = clientCreating(remote)

        val result = upload()

        assertEquals(SFTP_UPLOAD_FAILED_MESSAGE, result.messageOfError())
        verifyOrder {
            remote.close()
            sftp.rm(path)
        }
    }

    @Test
    fun `a disk that is full says the room is missing and still removes the partial file`() = runTest {
        val remote = mockk<RemoteFile>(relaxed = true)
        every { remote.write(any(), any<ByteArray>(), any(), any()) } throws SFTPException("No space left on device")
        val sftp = clientCreating(remote)

        val result = upload()

        assertEquals(SFTP_NO_SPACE_MESSAGE, result.messageOfError())
        verify(exactly = 1) { sftp.rm(path) }
    }

    @Test
    fun `a cancellation during the copy rethrows and removes the partial file`() = runTest {
        val remote = mockk<RemoteFile>(relaxed = true)
        val sftp = clientCreating(remote)
        var reports = 0
        lateinit var job: Job
        job = launch {
            // Four blocks of 32 Ko: the job is cancelled by the second report, before the third block.
            upload(size = 100_000) { _, _ -> if (++reports == 2) job.cancel() }
        }

        job.join()

        assertTrue(job.isCancelled)
        verify(exactly = 1) { sftp.rm(path) }
        verify(exactly = 2) { remote.write(any(), any<ByteArray>(), any(), any()) }
    }

    @Test
    fun `the partial file is looked at through its handle before the close, and by its name before the removal`() = runTest {
        val remote = mockk<RemoteFile>(relaxed = true)
        every { remote.write(any(), any<ByteArray>(), any(), any()) } throws SFTPException(Response.StatusCode.FAILURE, "Failure")
        val sftp = clientCreating(remote)

        upload()

        verifyOrder {
            remote.fetchAttributes()
            remote.close()
            sftp.lstat(path)
            sftp.rm(path)
        }
    }

    @Test
    fun `a transport that fails during the copy says the connection is lost, and the removal is tried and may fail`() = runTest {
        val remote = mockk<RemoteFile>(relaxed = true)
        every { remote.write(any(), any<ByteArray>(), any(), any()) } throws IOException("Broken pipe")
        val sftp = clientCreating(remote)
        every { sftp.rm(path) } throws IOException("Broken pipe")

        val result = upload()

        assertEquals(SFTP_CONNECTION_LOST_MESSAGE, result.messageOfError())
        verify(exactly = 1) { sftp.rm(path) }
    }

    @Test
    fun `a source that cannot be read during the copy removes the partial file`() = runTest {
        val remote = mockk<RemoteFile>(relaxed = true)
        val sftp = clientCreating(remote)
        val broken = object : java.io.InputStream() {
            override fun read(): Int = throw IOException("Provider closed")
            override fun read(b: ByteArray): Int = throw IOException("Provider closed")
        }

        val result = sftpManager.writeFile(sid, path, broken, 3L, replaceExisting = false) { _, _ -> }

        // The provider failed, not the connection.
        assertEquals(SFTP_SOURCE_UNAVAILABLE_MESSAGE, result.messageOfError())
        verify(exactly = 1) { sftp.rm(path) }
    }

    // ── The channel is lost: nothing more is asked of the server ─────────────────

    @Test
    fun `a channel lost during the copy says the connection is lost, and no removal is tried`() = runTest {
        for (lost in listOf(SFTPException("EOF while reading packet"), SFTPException(TimeoutException()))) {
            val remote = mockk<RemoteFile>(relaxed = true)
            every { remote.write(any(), any<ByteArray>(), any(), any()) } throws lost
            val sftp = clientCreating(remote)

            val result = upload()

            assertEquals(SFTP_CONNECTION_LOST_MESSAGE, result.messageOfError(), lost.toString())
            // Neither a delete nor a close nor a look at the file: each would wait for a server that is gone.
            verify(exactly = 0) { sftp.rm(any<String>()) }
            verify(exactly = 0) { remote.close() }
            verify(exactly = 0) { remote.fetchAttributes() }
        }
    }

    // ── The name no longer leads to the file this upload made ────────────────────

    /** The upload fails after its file was made, and the name shows [atTheName] when the clean-up looks. */
    private suspend fun failedUploadWhoseNameShows(atTheName: FileAttributes?, byTheHandle: FileAttributes = partial()): SFTPClient {
        val remote = mockk<RemoteFile>(relaxed = true)
        every { remote.write(any(), any<ByteArray>(), any(), any()) } throws SFTPException(Response.StatusCode.FAILURE, "Failure")
        val sftp = clientCreating(remote, seenByTheHandle = byTheHandle, seenAtTheName = atTheName)

        assertEquals(SFTP_UPLOAD_FAILED_MESSAGE, upload().messageOfError())
        return sftp
    }

    @Test
    fun `an entry that another writer put at the name meanwhile is not removed, by its size`() = runTest {
        val sftp = failedUploadWhoseNameShows(partial(size = 5_000_000))

        verify(exactly = 0) { sftp.rm(any<String>()) }
    }

    @Test
    fun `an entry that another writer put at the name meanwhile is not removed, by its time of modification`() = runTest {
        val sftp = failedUploadWhoseNameShows(partial(mtime = 1_700_000_900))

        verify(exactly = 0) { sftp.rm(any<String>()) }
    }

    @Test
    fun `an entry that another writer put at the name meanwhile is not removed, by its owner`() = runTest {
        val sftp = failedUploadWhoseNameShows(partial(owner = 1001 to 1000))

        verify(exactly = 0) { sftp.rm(any<String>()) }
    }

    @Test
    fun `a name that shows a link meanwhile is not removed`() = runTest {
        val sftp = failedUploadWhoseNameShows(partial(type = FileMode.Type.SYMLINK))

        verify(exactly = 0) { sftp.rm(any<String>()) }
    }

    @Test
    fun `a name that shows nothing any more has nothing to remove`() = runTest {
        val sftp = failedUploadWhoseNameShows(null)

        verify(exactly = 0) { sftp.rm(any<String>()) }
    }

    @Test
    fun `without a time of modification nothing tells the file from another one, so nothing is removed`() = runTest {
        val sftp = failedUploadWhoseNameShows(partial(mtime = null), byTheHandle = partial(mtime = null))

        verify(exactly = 0) { sftp.rm(any<String>()) }
    }

    @Test
    fun `without a size shown by both nothing tells the file from another one, so nothing is removed`() = runTest {
        val sftp = failedUploadWhoseNameShows(partial(size = null), byTheHandle = partial(size = null))

        verify(exactly = 0) { sftp.rm(any<String>()) }
    }

    @Test
    fun `without an owner shown by both nothing tells the file from another one, so nothing is removed`() = runTest {
        val sftp = failedUploadWhoseNameShows(partial(owner = null), byTheHandle = partial(owner = null))

        verify(exactly = 0) { sftp.rm(any<String>()) }
    }

    @Test
    fun `an attribute that only the name shows is not enough, nothing is removed`() = runTest {
        val sftp = failedUploadWhoseNameShows(partial(), byTheHandle = partial(owner = null))

        verify(exactly = 0) { sftp.rm(any<String>()) }
    }

    @Test
    fun `an entry that is a link is never removed, even when its attributes are the ones of the file`() = runTest {
        val sftp = failedUploadWhoseNameShows(partial(type = FileMode.Type.SYMLINK), byTheHandle = partial(type = FileMode.Type.SYMLINK))

        verify(exactly = 0) { sftp.rm(any<String>()) }
    }

    @Test
    fun `an entry that another writer put at the name meanwhile is not removed, by its group`() = runTest {
        val sftp = failedUploadWhoseNameShows(partial(owner = 1000 to 1001))

        verify(exactly = 0) { sftp.rm(any<String>()) }
    }

    @Test
    fun `an entry that another writer put at the name meanwhile is not removed, by its permissions`() = runTest {
        val sftp = failedUploadWhoseNameShows(partial(permissions = 0b110_110_110))

        verify(exactly = 0) { sftp.rm(any<String>()) }
    }

    @Test
    fun `an entry that another writer put at the name meanwhile is not removed, by a setuid bit`() = runTest {
        val sftp = failedUploadWhoseNameShows(partial(permissions = 0b100_110_100_100))

        verify(exactly = 0) { sftp.rm(any<String>()) }
    }

    @Test
    fun `a folder at the name is not removed`() = runTest {
        val sftp = failedUploadWhoseNameShows(partial(type = FileMode.Type.DIRECTORY))

        verify(exactly = 0) { sftp.rm(any<String>()) }
    }

    @Test
    fun `permissions that only the handle shows are not enough, nothing is removed`() = runTest {
        val sftp = failedUploadWhoseNameShows(partial(type = null, permissions = null))

        verify(exactly = 0) { sftp.rm(any<String>()) }
    }

    @Test
    fun `a server that states neither type nor permissions on either side still has its partial file removed`() = runTest {
        val bare = partial(type = null, permissions = null)
        val sftp = failedUploadWhoseNameShows(bare, byTheHandle = bare)

        verify(exactly = 1) { sftp.rm(path) }
    }

    @Test
    fun `a server without lstat is not asked stat, which follows links, and nothing is removed`() = runTest {
        val remote = mockk<RemoteFile>(relaxed = true)
        every { remote.write(any(), any<ByteArray>(), any(), any()) } throws SFTPException(Response.StatusCode.FAILURE, "Failure")
        val sftp = clientCreating(remote)
        every { sftp.lstat(path) } throws SFTPException(Response.StatusCode.OP_UNSUPPORTED, "Unsupported")

        upload()

        verify(exactly = 0) { sftp.statExistence(any<String>()) }
        verify(exactly = 0) { sftp.rm(any<String>()) }
        verify(exactly = 1) { remote.close() }
    }

    // ── A channel lost at the look at the partial file ───────────────────────────

    @Test
    fun `a channel lost at the look at the partial file ends it all, no close, no lookup of the name, no delete`() = runTest {
        for (lost in listOf(SFTPException("EOF while reading packet"), SFTPException(TimeoutException()))) {
            val remote = mockk<RemoteFile>(relaxed = true)
            // The copy fails for a reason of the server, the channel is lost only at the look.
            every { remote.write(any(), any<ByteArray>(), any(), any()) } throws SFTPException(Response.StatusCode.FAILURE, "Failure")
            val sftp = clientCreating(remote)
            every { remote.fetchAttributes() } throws lost

            val result = upload()

            // The error of the upload stays the one that is reported.
            assertEquals(SFTP_UPLOAD_FAILED_MESSAGE, result.messageOfError(), lost.toString())
            verify(exactly = 0) { remote.close() }
            verify(exactly = 0) { sftp.lstat(any<String>()) }
            verify(exactly = 0) { sftp.rm(any<String>()) }
        }
    }

    @Test
    fun `a handle whose attributes the server does not give leaves the file alone`() = runTest {
        val remote = mockk<RemoteFile>(relaxed = true)
        every { remote.write(any(), any<ByteArray>(), any(), any()) } throws SFTPException(Response.StatusCode.FAILURE, "Failure")
        val sftp = clientCreating(remote)
        every { remote.fetchAttributes() } throws SFTPException(Response.StatusCode.FAILURE, "Failure")

        upload()

        verify(exactly = 0) { sftp.rm(any<String>()) }
        verify(exactly = 1) { remote.close() }
    }

    @Test
    fun `a name that cannot be looked up leaves the file alone`() = runTest {
        val remote = mockk<RemoteFile>(relaxed = true)
        every { remote.write(any(), any<ByteArray>(), any(), any()) } throws SFTPException(Response.StatusCode.FAILURE, "Failure")
        val sftp = clientCreating(remote)
        every { sftp.lstat(path) } throws IOException("Broken pipe")

        upload()

        verify(exactly = 0) { sftp.rm(any<String>()) }
    }

    // ── What is not the upload's own is never removed ────────────────────────────

    @Test
    fun `an upload that goes through removes nothing`() = runTest {
        val remote = mockk<RemoteFile>(relaxed = true)
        val sftp = clientCreating(remote)

        val result = upload()

        assertTrue(result is SshResult.Success)
        verify(exactly = 0) { sftp.rm(any<String>()) }
    }

    @Test
    fun `a name that is taken is refused and the file that is there is not removed`() = runTest {
        val sftp = openedSftp()
        every { sftp.open(path, exclusive) } throws SFTPException(Response.StatusCode.FAILURE, "Failure")
        every { sftp.lstat(path) } returns regularFile()

        val result = upload()

        assertEquals(SFTP_UPLOAD_TARGET_EXISTS_MESSAGE, result.messageOfError())
        verify(exactly = 0) { sftp.rm(any<String>()) }
    }

    @Test
    fun `an open that fails for another reason created nothing, so removes nothing`() = runTest {
        val sftp = openedSftp()
        every { sftp.open(path, exclusive) } throws SFTPException("Permission denied")
        every { sftp.lstat(path) } throws SFTPException(Response.StatusCode.NO_SUCH_FILE, "No such file")

        val result = upload()

        assertEquals(SFTP_PERMISSION_DENIED_MESSAGE, result.messageOfError())
        verify(exactly = 0) { sftp.rm(any<String>()) }
    }

    @Test
    fun `a replacement written in place never removes the file, whatever happens to the copy`() = runTest {
        val sftp = openedSftp()
        val engine = mockk<SFTPEngine>()
        every { sftp.sftpEngine } returns engine
        // A server without posix-rename: the old way, which overwrites the file where it is.
        every { engine.supportsServerExtension("posix-rename", "openssh.com") } returns false
        every { sftp.lstat(path) } returns regularFile()
        val remote = mockk<RemoteFile>(relaxed = true)
        every { remote.write(any(), any<ByteArray>(), any(), any()) } throws SFTPException(Response.StatusCode.FAILURE, "Failure")
        every { sftp.open(path, truncate) } returns remote

        val result = sftpManager.writeFile(sid, path, content(), 3L, replaceExisting = true) { _, _ -> }

        // The old way says the usual sentence: the file was emptied, it is not "intact".
        assertEquals(SFTP_UPLOAD_FAILED_MESSAGE, result.messageOfError())
        verify(exactly = 0) { sftp.rm(any<String>()) }
        verify(exactly = 1) { remote.close() }
    }

    @Test
    fun `a replacement of a target that disappeared is a free name, and its partial file goes`() = runTest {
        val sftp = openedSftp()
        // Nothing at the name for the plan, then the partial file of this upload for the clean-up.
        every { sftp.lstat(path) } throws SFTPException(Response.StatusCode.NO_SUCH_FILE, "No such file") andThen partial()
        val remote = mockk<RemoteFile>(relaxed = true)
        every { remote.write(any(), any<ByteArray>(), any(), any()) } throws SFTPException(Response.StatusCode.FAILURE, "Failure")
        every { remote.fetchAttributes() } returns partial()
        every { sftp.open(path, exclusive) } returns remote
        every { sftp.rm(path) } just Runs

        val result = sftpManager.writeFile(sid, path, content(), 3L, replaceExisting = true) { _, _ -> }

        assertEquals(SFTP_UPLOAD_FAILED_MESSAGE, result.messageOfError())
        verify(exactly = 1) { sftp.rm(path) }
    }

    // ── The log ──────────────────────────────────────────────────────────────────

    @Test
    fun `a removal that fails is logged with its class only, never the path nor the exception`() = runTest {
        val tree = RecordingTree()
        Timber.plant(tree)
        try {
            val remote = mockk<RemoteFile>(relaxed = true)
            every { remote.write(any(), any<ByteArray>(), any(), any()) } throws SFTPException(Response.StatusCode.FAILURE, "Failure")
            val sftp = clientCreating(remote)
            every { sftp.rm(path) } throws IOException("Cannot remove /home/user/report.pdf")

            upload()

            assertTrue(tree.entries.isNotEmpty())
            tree.entries.forEach { entry ->
                assertFalse(entry.message.contains("report"), entry.message)
                assertFalse(entry.message.contains("/home"), entry.message)
                assertFalse(entry.message.contains(sid), entry.message)
                assertNull(entry.throwable, "an exception was passed to the log: ${entry.message}")
            }
            assertTrue(tree.entries.any { it.message.contains("IOException") }, "the class of the failed removal is not logged")
        } finally {
            Timber.uproot(tree)
        }
    }
}
