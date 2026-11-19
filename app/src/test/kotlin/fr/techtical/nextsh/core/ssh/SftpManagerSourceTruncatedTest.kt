// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.testutil.RecordingTree
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.RenameFlags
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import timber.log.Timber
import java.io.ByteArrayInputStream

/**
 * A source that delivers FEWER bytes than the size its provider announced (a pipe closed without an
 * error by a provider that failed) makes the upload fail, on the three ways it is written. More bytes
 * than announced are accepted, and an unknown size (not positive) is not checked.
 */
class SftpManagerSourceTruncatedTest {

    private val fixture = UploadFixture()
    private val sid = fixture.sid
    private val path = fixture.path

    private fun SshResult<*>.messageOfError(): String = (this as SshResult.Error).message

    private fun bytes(count: Int) = ByteArrayInputStream(ByteArray(count) { (it % 251).toByte() })

    @Test
    fun `a source shorter than announced under a free name fails and removes the partial file`() = runTest {
        val remote = mockk<RemoteFile>(relaxed = true)
        val sftp = fixture.creating(remote)

        val result = fixture.manager.writeFile(sid, path, bytes(3), 5L, replaceExisting = false) { _, _ -> }

        assertEquals(SFTP_SOURCE_TRUNCATED_MESSAGE, result.messageOfError())
        verify(exactly = 1) { sftp.rm(path) }
    }

    @Test
    fun `a source shorter than announced through a temporary file renames nothing and removes the temporary file`() = runTest {
        val s = fixture.replacing(extension = true)

        val result = fixture.manager.writeFile(sid, path, bytes(3), 5L, replaceExisting = true) { _, _ -> }

        assertEquals(SFTP_SOURCE_TRUNCATED_MESSAGE, result.messageOfError())
        verify(exactly = 0) { s.sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) }
        verify(exactly = 1) { s.sftp.rm(s.tempPath) }
        // The old file is neither opened nor deleted.
        verify(exactly = 0) { s.sftp.open(any<String>(), any<Set<OpenMode>>()) }
        verify(exactly = 0) { s.sftp.rm(path) }
    }

    @Test
    fun `a source that ends at once while a size is announced is refused before the open in place`() = runTest {
        val s = fixture.replacing(extension = false)

        val result = fixture.manager.writeFile(sid, path, bytes(0), 5L, replaceExisting = true) { _, _ -> }

        assertEquals(SFTP_SOURCE_TRUNCATED_MESSAGE, result.messageOfError())
        // Nothing was opened, so the old file was not emptied.
        verify(exactly = 0) { s.sftp.open(any<String>(), any<Set<OpenMode>>()) }
        verify(exactly = 0) { s.sftp.open(any<String>(), any<Set<OpenMode>>(), any<FileAttributes>()) }
        verify(exactly = 0) { s.sftp.rm(any<String>()) }
    }

    @Test
    fun `a source that stops later than the first block written in place is reported and nothing is deleted`() = runTest {
        val s = fixture.replacing(extension = false)
        val target = mockk<RemoteFile>(relaxed = true)
        every { s.sftp.open(path, fixture.inPlaceModes) } returns target

        val result = fixture.manager.writeFile(sid, path, bytes(40_000), 100_000L, replaceExisting = true) { _, _ -> }

        // The file is already cut short, which cannot be undone: the message says it is incomplete.
        assertEquals(SFTP_SOURCE_TRUNCATED_IN_PLACE_MESSAGE, result.messageOfError())
        verify(exactly = 2) { target.write(any(), any<ByteArray>(), any(), any()) }
        verify(exactly = 1) { target.close() }
        verify(exactly = 0) { s.sftp.rm(any<String>()) }
    }

    @Test
    fun `a source that ends at once with no size announced still empties the file in place`() = runTest {
        val s = fixture.replacing(extension = false)
        val target = mockk<RemoteFile>(relaxed = true)
        every { s.sftp.open(path, fixture.inPlaceModes) } returns target

        val result = fixture.manager.writeFile(sid, path, bytes(0), 0L, replaceExisting = true) { _, _ -> }

        assertTrue(result is SshResult.Success)
        verify(exactly = 1) { s.sftp.open(path, fixture.inPlaceModes) }
        verify(exactly = 0) { target.write(any(), any<ByteArray>(), any(), any()) }
    }

    @Test
    fun `a source longer than announced is accepted under a free name`() = runTest {
        val remote = mockk<RemoteFile>(relaxed = true)
        fixture.creating(remote)

        val result = fixture.manager.writeFile(sid, path, bytes(5), 3L, replaceExisting = false) { _, _ -> }

        assertTrue(result is SshResult.Success)
        verify(exactly = 1) { remote.write(0L, any<ByteArray>(), 0, 5) }
    }

    @Test
    fun `a source longer than announced is accepted through a temporary file`() = runTest {
        val s = fixture.replacing(extension = true)

        val result = fixture.manager.writeFile(sid, path, bytes(5), 3L, replaceExisting = true) { _, _ -> }

        assertTrue(result is SshResult.Success)
        verify(exactly = 1) { s.sftp.rename(s.tempPath, path, setOf(RenameFlags.OVERWRITE)) }
    }

    @Test
    fun `a source of unknown size is not checked`() = runTest {
        val remote = mockk<RemoteFile>(relaxed = true)
        fixture.creating(remote)

        val result = fixture.manager.writeFile(sid, path, bytes(3), 0L, replaceExisting = false) { _, _ -> }

        assertTrue(result is SshResult.Success)
    }

    @Test
    fun `the log of a source that is too short holds no path and no exception`() = runTest {
        val tree = RecordingTree()
        Timber.plant(tree)
        try {
            fixture.creating(mockk<RemoteFile>(relaxed = true))
            fixture.manager.writeFile(sid, path, bytes(3), 5L, replaceExisting = false) { _, _ -> }

            assertTrue(tree.entries.isNotEmpty())
            tree.entries.forEach { entry ->
                assertFalse(entry.message.contains("/home"), entry.message)
                assertFalse(entry.message.contains("report.pdf"), entry.message)
                assertFalse(entry.message.contains(sid), entry.message)
                assertNull(entry.throwable, "an exception was passed to the log: ${entry.message}")
            }
        } finally {
            Timber.uproot(tree)
        }
    }
}
