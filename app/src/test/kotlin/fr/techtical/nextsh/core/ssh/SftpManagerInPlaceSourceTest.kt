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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * An upload in place (the way a confirmed replacement falls back to when the atomic way is not
 * possible) reads the first block of its source BEFORE the open that empties the old file: a source that
 * cannot be read, or a job cancelled while it is read, leaves the old file as it was.
 *
 * The SFTP client is a strict mock: a call that a test did not stub fails it.
 */
class SftpManagerInPlaceSourceTest {

    private val sid = "3f2a9c1e-77b0-4c1d-9a55-session"
    private val path = "/home/user/report.pdf"
    private val inPlaceModes = setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)

    private val sessionManager: SshSessionManager = mockk(relaxed = true)
    private var sftpManager = SftpManager(sessionManager)

    private class Server(val sftp: SFTPClient, val temp: RemoteFile)

    /** One write the mock target received: the offset and a copy of the bytes (the buffer is reused). */
    private class Write(val offset: Long, val bytes: ByteArray)

    private fun file(owner: Pair<Int, Int> = 1000 to 1000): FileAttributes =
        FileAttributes.Builder().withType(FileMode.Type.REGULAR).withPermissions(0b110_100_100)
            .withUIDGID(owner.first, owner.second).build()

    /**
     * A session whose server has a regular file at [path]. Without [extension] the server has no
     * `posix-rename@openssh.com`: the replacement is written in place, with no temporary file.
     */
    private suspend fun server(
        extension: Boolean = false,
        old: FileAttributes = file(),
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
        every { sftp.lstat(path) } returns old
        val temp = mockk<RemoteFile>(relaxed = true)
        every { temp.fetchAttributes() } returns tempOwner
        every { sftp.open(any<String>(), any<Set<OpenMode>>(), any<FileAttributes>()) } returns temp
        every { sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) } just Runs
        every { sftp.rm(any<String>()) } just Runs
        return Server(sftp, temp)
    }

    /** A target that records every write it receives into [writes]. */
    private fun recordingTarget(writes: MutableList<Write>): RemoteFile {
        val target = mockk<RemoteFile>(relaxed = true)
        every { target.write(any(), any<ByteArray>(), any(), any()) } answers {
            val buffer = secondArg<ByteArray>()
            val from = thirdArg<Int>()
            writes += Write(firstArg<Long>(), buffer.copyOfRange(from, from + arg<Int>(3)))
        }
        return target
    }

    private fun bytes(count: Int) = ByteArray(count) { (it % 251).toByte() }

    private fun SshResult<*>.messageOfError(): String = (this as SshResult.Error).message

    private fun Server.verifyNothingOpened() {
        verify(exactly = 0) { sftp.open(any<String>(), any<Set<OpenMode>>()) }
        verify(exactly = 0) { sftp.open(any<String>(), any<Set<OpenMode>>(), any<FileAttributes>()) }
        verify(exactly = 0) { sftp.rm(any<String>()) }
        verify(exactly = 0) { sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) }
    }

    /** A source that fails at its first read, with a text that quotes a path. */
    private fun unreadable() = object : InputStream() {
        override fun read(): Int = throw IOException("Input/output error on /storage/secret-document.pdf")
    }

    // ── A source that cannot be read leaves the old file alone ───────────────────

    @Test
    fun `a source that cannot be read at its first block opens nothing and leaves the old file untouched`() = runTest {
        val s = server()

        val result = sftpManager.writeFile(sid, path, unreadable(), 3L, replaceExisting = true) { _, _ -> }

        assertEquals(SFTP_SOURCE_UNAVAILABLE_MESSAGE, result.messageOfError())
        s.verifyNothingOpened()
    }

    @Test
    fun `a source that cannot be read after the temporary file was given up opens nothing in place`() = runTest {
        val s = server(extension = true, old = file(owner = 0 to 0), tempOwner = file(owner = 1000 to 1000))
        every {
            s.temp.setAttributes(match { it.has(FileAttributes.Flag.UIDGID) })
        } throws SFTPException(Response.StatusCode.PERMISSION_DENIED, "Operation not permitted")

        val result = sftpManager.writeFile(sid, path, unreadable(), 3L, replaceExisting = true) { _, _ -> }

        assertEquals(SFTP_SOURCE_UNAVAILABLE_MESSAGE, result.messageOfError())
        // The temporary file was made, closed and removed; the old file was never opened.
        verify(exactly = 0) { s.sftp.open(any<String>(), any<Set<OpenMode>>()) }
        verify(exactly = 0) { s.sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) }
        verify(exactly = 1) { s.sftp.rm(any<String>()) }
    }

    @Test
    fun `the first block of the source is read before the open in place`() = runTest {
        val s = server()
        var reads = 0
        val source = object : InputStream() {
            private val inner = ByteArrayInputStream(bytes(3))
            override fun read(): Int = inner.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                reads++
                return inner.read(b, off, len)
            }
        }
        var readsAtOpen = -1
        val target = mockk<RemoteFile>(relaxed = true)
        every { s.sftp.open(path, inPlaceModes) } answers {
            readsAtOpen = reads
            target
        }

        val result = sftpManager.writeFile(sid, path, source, 3L, replaceExisting = true) { _, _ -> }

        assertTrue(result is SshResult.Success)
        assertEquals(1, readsAtOpen, "the first block must have been read when the open is sent")
    }

    // ── A source that is really empty ────────────────────────────────────────────

    @Test
    fun `an empty source still empties the file in place and writes nothing`() = runTest {
        val s = server()
        val target = mockk<RemoteFile>(relaxed = true)
        every { s.sftp.open(path, inPlaceModes) } returns target

        val result = sftpManager.writeFile(sid, path, ByteArrayInputStream(ByteArray(0)), 0L, replaceExisting = true) { _, _ -> }

        assertTrue(result is SshResult.Success)
        verify(exactly = 1) { s.sftp.open(path, inPlaceModes) }
        verify(exactly = 0) { target.write(any(), any<ByteArray>(), any(), any()) }
        verify(exactly = 1) { target.close() }
    }

    // ── Cancellation ─────────────────────────────────────────────────────────────

    @Test
    fun `a job cancelled while the first block is read does not get to the open in place`() = runTest {
        val s = server()
        lateinit var job: Job
        val source = object : InputStream() {
            override fun read(): Int = 1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                job.cancel()
                b[off] = 7
                return 1
            }
        }

        job = launch { sftpManager.writeFile(sid, path, source, 1L, replaceExisting = true) { _, _ -> } }
        job.join()

        assertTrue(job.isCancelled)
        s.verifyNothingOpened()
    }

    @Test
    fun `a job cancelled during the open in place still writes the block already read`() = runTest {
        val s = server()
        lateinit var job: Job
        val writes = mutableListOf<Write>()
        val target = recordingTarget(writes)
        every { s.sftp.open(path, inPlaceModes) } answers {
            job.cancel()
            target
        }

        job = launch { sftpManager.writeFile(sid, path, ByteArrayInputStream(bytes(3)), 3L, replaceExisting = true) { _, _ -> } }
        job.join()

        assertTrue(job.isCancelled)
        // The old file was emptied by the open: the block that was read goes in, once, and is not lost.
        assertEquals(1, writes.size)
        assertEquals(0L, writes.single().offset)
        assertTrue(bytes(3).contentEquals(writes.single().bytes))
        // The file is the user's: closed, never deleted.
        verify(exactly = 1) { target.close() }
        verify(exactly = 0) { s.sftp.rm(any<String>()) }
    }

    // ── The head and the rest, without a gap or a repetition ─────────────────────

    @Test
    fun `the whole source is written in place at contiguous offsets and the progress counts the first block`() = runTest {
        val s = server()
        val writes = mutableListOf<Write>()
        every { s.sftp.open(path, inPlaceModes) } returns recordingTarget(writes)
        val content = bytes(70_000)
        val progress = mutableListOf<Pair<Long, Long>>()

        val result = sftpManager.writeFile(sid, path, ByteArrayInputStream(content), 70_000L, replaceExisting = true) { done, total ->
            progress += done to total
        }

        assertTrue(result is SshResult.Success)
        assertEquals(listOf(0L, 32_768L, 65_536L), writes.map { it.offset })
        assertTrue(content.contentEquals(writes.fold(ByteArray(0)) { acc, w -> acc + w.bytes }))
        assertEquals(listOf(32_768L to 70_000L, 65_536L to 70_000L, 70_000L to 70_000L), progress)
    }

    @Test
    fun `a source of one short block is written once at offset zero`() = runTest {
        val s = server()
        val writes = mutableListOf<Write>()
        every { s.sftp.open(path, inPlaceModes) } returns recordingTarget(writes)

        val result = sftpManager.writeFile(sid, path, ByteArrayInputStream(bytes(3)), 3L, replaceExisting = true) { _, _ -> }

        assertTrue(result is SshResult.Success)
        assertEquals(1, writes.size)
        assertEquals(0L, writes.single().offset)
        assertEquals(3, writes.single().bytes.size)
    }

    @Test
    fun `a replacement that falls back from the temporary file writes the whole source in place`() = runTest {
        val s = server(extension = true, old = file(owner = 0 to 0), tempOwner = file(owner = 1000 to 1000))
        every {
            s.temp.setAttributes(match { it.has(FileAttributes.Flag.UIDGID) })
        } throws SFTPException(Response.StatusCode.PERMISSION_DENIED, "Operation not permitted")
        val writes = mutableListOf<Write>()
        every { s.sftp.open(path, inPlaceModes) } returns recordingTarget(writes)
        val content = bytes(70_000)

        val result = sftpManager.writeFile(sid, path, ByteArrayInputStream(content), 70_000L, replaceExisting = true) { _, _ -> }

        assertTrue(result is SshResult.Success)
        // Nothing of the source was consumed by the temporary file that was given up.
        verify(exactly = 0) { s.temp.write(any(), any<ByteArray>(), any(), any()) }
        assertEquals(listOf(0L, 32_768L, 65_536L), writes.map { it.offset })
        assertTrue(content.contentEquals(writes.fold(ByteArray(0)) { acc, w -> acc + w.bytes }))
    }

    // ── The log ──────────────────────────────────────────────────────────────────

    @Test
    fun `the log of a source that cannot be read holds no path and no exception`() = runTest {
        val tree = RecordingTree()
        Timber.plant(tree)
        try {
            server()
            sftpManager.writeFile(sid, path, unreadable(), 3L, replaceExisting = true) { _, _ -> }

            assertTrue(tree.entries.isNotEmpty())
            tree.entries.forEach { entry ->
                assertFalse(entry.message.contains("secret-document"), entry.message)
                assertFalse(entry.message.contains("/storage"), entry.message)
                assertFalse(entry.message.contains("report.pdf"), entry.message)
                assertFalse(entry.message.contains(sid), entry.message)
                assertNull(entry.throwable, "an exception was passed to the log: ${entry.message}")
            }
        } finally {
            Timber.uproot(tree)
        }
    }
}
