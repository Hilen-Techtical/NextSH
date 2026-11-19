// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.ssh

import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_connection_lost
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_local_read_failed
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_local_write_failed
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_permission_denied
import fr.techtical.nextsh.shared.domain.model.SshErrorCode
import fr.techtical.nextsh.shared.domain.model.SshResult
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import org.jetbrains.compose.resources.getString
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

/**
 * [DesktopSftpManager.readFile] and [DesktopSftpManager.writeFile] when it is the LOCAL stream
 * that fails (disk full, drive removed): both are IOExceptions, like a lost connection, and
 * used to be reported as "SFTP connection lost". The local stream is wrapped to tell them
 * apart; a failure of the connection or of the server keeps its own message.
 *
 * The SFTP client is a strict mock. Messages are compared with the resources, never with a
 * literal: the text depends on the machine language.
 */
class DesktopSftpManagerLocalIoTest {

    private val sftp = mockk<SFTPClient>()
    private val remoteFile = mockk<RemoteFile>(relaxed = true)

    private suspend fun openedManager(): DesktopSftpManager {
        val ssh = mockk<SSHClient>()
        every { ssh.newSFTPClient() } returns sftp
        val terminal = mockk<DesktopSshTerminalSession>()
        every { terminal.getSshClient() } returns ssh
        val sessions = mockk<DesktopSshSessionManager>()
        every { sessions.getTerminalSession(SESSION) } returns terminal
        return DesktopSftpManager(sessions).also { assertIs<SshResult.Success<*>>(it.openSftp(SESSION)) }
    }

    private fun assertError(expectedMessage: String, result: SshResult<*>) {
        val error = assertIs<SshResult.Error>(result)
        assertEquals(SshErrorCode.UNKNOWN, error.code)
        assertEquals(expectedMessage, error.message)
    }

    /** A remote file of [size] bytes whose every read answers a full chunk of 7s. */
    private fun remoteFileOfSize(size: Long) {
        every { sftp.open(PATH) } returns remoteFile
        every { remoteFile.length() } returns size
        every { remoteFile.read(any<Long>(), any<ByteArray>(), any<Int>(), any<Int>()) } answers {
            val buffer = secondArg<ByteArray>()
            val offset = thirdArg<Int>()
            val length = arg<Int>(3)
            buffer.fill(7, offset, offset + length)
            length
        }
    }

    private class BrokenSink : OutputStream() {
        override fun write(b: Int) {
            throw IOException("No space left on device")
        }
    }

    private class BrokenSource : InputStream() {
        override fun read(): Int = throw IOException("Input/output error")
    }

    // ── readFile: the computer's disk fails ───────────────────────────────────

    @Test
    fun `a download whose local write fails reports the local write message, not a lost connection`() = runTest {
        val manager = openedManager()
        remoteFileOfSize(10L)

        val result = manager.readFile(SESSION, PATH, BrokenSink()) { _, _ -> }

        assertError(getString(Res.string.sftp_error_local_write_failed), result)
        assertNotEquals(getString(Res.string.sftp_error_connection_lost), (result as SshResult.Error).message)
        verify(exactly = 1) { remoteFile.close() }
    }

    @Test
    fun `a download copies the bytes and leaves the stream open for its owner`() = runTest {
        val manager = openedManager()
        remoteFileOfSize(70_000L)
        var closed = false
        val sink = object : ByteArrayOutputStream() {
            override fun close() {
                closed = true
                super.close()
            }
        }

        val result = manager.readFile(SESSION, PATH, sink) { _, _ -> }

        assertEquals<SshResult<Unit>>(SshResult.Success(Unit), result)
        assertContentEquals(ByteArray(70_000) { 7 }, sink.toByteArray())
        assertFalse(closed, "the stream belongs to the caller")
    }

    @Test
    fun `a lost connection while downloading keeps the connection lost message`() = runTest {
        val manager = openedManager()
        every { sftp.open(PATH) } returns remoteFile
        every { remoteFile.length() } returns 10L
        every { remoteFile.read(any<Long>(), any<ByteArray>(), any<Int>(), any<Int>()) } throws IOException("socket closed")

        val result = manager.readFile(SESSION, PATH, ByteArrayOutputStream()) { _, _ -> }

        assertError(getString(Res.string.sftp_error_connection_lost), result)
    }

    @Test
    fun `a server refusal while downloading keeps its own message`() = runTest {
        val manager = openedManager()
        every { sftp.open(PATH) } throws SFTPException(Response.StatusCode.PERMISSION_DENIED, "Permission denied")

        val result = manager.readFile(SESSION, PATH, ByteArrayOutputStream()) { _, _ -> }

        assertError(getString(Res.string.sftp_error_permission_denied), result)
    }

    @Test
    fun `a cancellation while downloading is not turned into an error result`() = runTest {
        val manager = openedManager()
        every { sftp.open(PATH) } returns remoteFile
        every { remoteFile.length() } returns 10L
        every { remoteFile.read(any<Long>(), any<ByteArray>(), any<Int>(), any<Int>()) } throws CancellationException("cancelled")

        assertFailsWith<CancellationException> { manager.readFile(SESSION, PATH, ByteArrayOutputStream()) { _, _ -> } }
        verify(exactly = 1) { remoteFile.close() }
    }

    // ── writeFile: the computer's disk fails ──────────────────────────────────

    @Test
    fun `an upload whose local read fails reports the local read message, not a lost connection`() = runTest {
        val manager = openedManager()
        givenCreatedFile()

        val result = manager.writeFile(SESSION, PATH, BrokenSource(), 10L, false) { _, _ -> }

        assertError(getString(Res.string.sftp_error_local_read_failed), result)
        verify(exactly = 0) { remoteFile.write(any<Long>(), any<ByteArray>(), any<Int>(), any<Int>()) }
        verify(exactly = 1) { remoteFile.close() }
        // The session answers: the incomplete file this upload created is deleted.
        verify(exactly = 1) { sftp.rm(PATH) }
    }

    @Test
    fun `a lost connection while uploading keeps the connection lost message`() = runTest {
        val manager = openedManager()
        every { sftp.open(PATH, any()) } returns remoteFile
        every { remoteFile.write(any<Long>(), any<ByteArray>(), any<Int>(), any<Int>()) } throws IOException("socket closed")
        // The transport is closed: the handle cannot be read, so the file cannot be recognized and is left.
        every { remoteFile.fetchAttributes() } throws IOException("socket closed")

        val result = manager.writeFile(SESSION, PATH, ByteArray(10).inputStream(), 10L, false) { _, _ -> }

        assertError(getString(Res.string.sftp_error_connection_lost), result)
        verify(exactly = 0) { sftp.rm(any<String>()) }
    }

    @Test
    fun `a cancellation while uploading is not turned into an error result`() = runTest {
        val manager = openedManager()
        givenCreatedFile()
        every { remoteFile.write(any<Long>(), any<ByteArray>(), any<Int>(), any<Int>()) } throws CancellationException("cancelled")

        assertFailsWith<CancellationException> {
            manager.writeFile(SESSION, PATH, ByteArray(10).inputStream(), 10L, false) { _, _ -> }
        }
        verify(exactly = 1) { remoteFile.close() }
        verify(exactly = 1) { sftp.rm(PATH) }
    }

    /** The upload opens [PATH] with `EXCL`: the file it created is what its handle states and what the name shows. */
    private fun givenCreatedFile() {
        val created = FileAttributes.Builder()
            .withType(FileMode.Type.REGULAR)
            .withUIDGID(1000, 1000)
            .withSize(0)
            .withAtimeMtime(1_000, 1_000)
            .build()
        every { sftp.open(PATH, any()) } returns remoteFile
        every { remoteFile.fetchAttributes() } returns created
        every { sftp.lstat(PATH) } returns created
        every { sftp.rm(PATH) } just Runs
    }

    private companion object {
        const val SESSION = "s1"
        const val PATH = "/home/t/a.bin"
    }
}
