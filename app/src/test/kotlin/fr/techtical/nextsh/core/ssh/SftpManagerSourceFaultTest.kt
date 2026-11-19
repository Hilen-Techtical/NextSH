// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.testutil.RecordingTree
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.RenameFlags
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import timber.log.Timber
import java.io.IOException

/**
 * A source that fails while it is read (a provider that closes its pipe with an error, an access that
 * is revoked) is the device's failure: it says the source cannot be read, on the three ways an upload
 * is written, and not that the SFTP connection is lost. A transport that fails still says that.
 */
class SftpManagerSourceFaultTest {

    private val fixture = UploadFixture()
    private val sid = fixture.sid
    private val path = fixture.path

    private fun SshResult<*>.messageOfError(): String = (this as SshResult.Error).message

    @Test
    fun `a source that fails during the copy under a free name says the source cannot be read and removes the partial file`() = runTest {
        val remote = mockk<RemoteFile>(relaxed = true)
        val sftp = fixture.creating(remote)

        val result = fixture.manager.writeFile(sid, path, UploadFixture.FailingSource(goodBlocks = 1), 100_000L, replaceExisting = false) { _, _ -> }

        assertEquals(SFTP_SOURCE_UNAVAILABLE_MESSAGE, result.messageOfError())
        verify(exactly = 1) { remote.write(any(), any<ByteArray>(), any(), any()) }
        verify(exactly = 1) { sftp.rm(path) }
    }

    @Test
    fun `a source that fails during the copy through a temporary file says so, removes it and renames nothing`() = runTest {
        val s = fixture.replacing(extension = true)

        val result = fixture.manager.writeFile(sid, path, UploadFixture.FailingSource(goodBlocks = 1), 100_000L, replaceExisting = true) { _, _ -> }

        assertEquals(SFTP_SOURCE_UNAVAILABLE_MESSAGE, result.messageOfError())
        verify(exactly = 1) { s.temp.write(any(), any<ByteArray>(), any(), any()) }
        verify(exactly = 1) { s.sftp.rm(s.tempPath) }
        verify(exactly = 0) { s.sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) }
        verify(exactly = 0) { s.sftp.open(any<String>(), any<Set<OpenMode>>()) }
    }

    @Test
    fun `a source that fails after the first block written in place says so and deletes nothing`() = runTest {
        val s = fixture.replacing(extension = false)
        val target = mockk<RemoteFile>(relaxed = true)
        every { s.sftp.open(path, fixture.inPlaceModes) } returns target

        val result = fixture.manager.writeFile(sid, path, UploadFixture.FailingSource(goodBlocks = 1), 100_000L, replaceExisting = true) { _, _ -> }

        assertEquals(SFTP_SOURCE_UNAVAILABLE_MESSAGE, result.messageOfError())
        verify(exactly = 1) { target.write(any(), any<ByteArray>(), any(), any()) }
        verify(exactly = 1) { target.close() }
        verify(exactly = 0) { s.sftp.rm(any<String>()) }
    }

    @Test
    fun `a transport that fails during the copy still says the connection is lost`() = runTest {
        val remote = mockk<RemoteFile>(relaxed = true)
        every { remote.write(any(), any<ByteArray>(), any(), any()) } throws IOException("Broken pipe")
        val sftp = fixture.creating(remote)
        every { sftp.rm(path) } throws IOException("Broken pipe")

        val result = fixture.manager.writeFile(sid, path, java.io.ByteArrayInputStream(ByteArray(3)), 3L, replaceExisting = false) { _, _ -> }

        assertEquals(SFTP_CONNECTION_LOST_MESSAGE, result.messageOfError())
    }

    @Test
    fun `the upload does not close the source, its owner does`() = runTest {
        fixture.creating(mockk<RemoteFile>(relaxed = true))
        val source = UploadFixture.FailingSource(goodBlocks = 0)

        fixture.manager.writeFile(sid, path, source, 100_000L, replaceExisting = false) { _, _ -> }

        assertFalse(source.closed)
    }

    @Test
    fun `the log of a source that fails holds no document name, no path and no exception`() = runTest {
        val tree = RecordingTree()
        Timber.plant(tree)
        try {
            fixture.creating(mockk<RemoteFile>(relaxed = true))
            fixture.manager.writeFile(sid, path, UploadFixture.FailingSource(goodBlocks = 0), 100_000L, replaceExisting = false) { _, _ -> }

            assertTrue(tree.entries.isNotEmpty())
            tree.entries.forEach { entry ->
                assertFalse(entry.message.contains("secret-document"), entry.message)
                assertFalse(entry.message.contains("/home"), entry.message)
                assertFalse(entry.message.contains(sid), entry.message)
                assertNull(entry.throwable, "an exception was passed to the log: ${entry.message}")
            }
        } finally {
            Timber.uproot(tree)
        }
    }
}
