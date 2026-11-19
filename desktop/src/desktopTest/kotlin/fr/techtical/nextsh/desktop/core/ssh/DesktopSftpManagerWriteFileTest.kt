// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.ssh

import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_client_not_ready
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_connection_lost
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_invalid_path
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_permission_denied
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_stat_failed
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_target_exists
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_upload_failed
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_upload_generic
import fr.techtical.nextsh.shared.domain.model.SshErrorCode
import fr.techtical.nextsh.shared.domain.model.SshResult
import io.mockk.Runs
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CancellationException
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
import org.jetbrains.compose.resources.getString
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [DesktopSftpManager.writeFile] and [DesktopSftpManager.entryKind] against a mocked SSHJ
 * [SFTPClient]. The mock is strict on purpose: any call the implementation was not expected
 * to make fails the test. Messages are compared with the resources, never with a literal:
 * the text depends on the machine language.
 *
 * The point of these tests: without `replaceExisting` the open is `EXCL` and never `TRUNC`
 * (nothing is ever replaced unasked), and a refused `EXCL` open is reported as "already
 * exists", including for OpenSSH on SFTP v3 which answers a bare `FAILURE`.
 */
class DesktopSftpManagerWriteFileTest {

    private val sftp = mockk<SFTPClient>()
    private val remoteFile = mockk<RemoteFile>(relaxed = true)

    /** A manager whose [SESSION] already has the mocked [sftp] client open. */
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

    private suspend fun DesktopSftpManager.write(
        replaceExisting: Boolean,
        data: ByteArray = ByteArray(10),
        path: String = TARGET,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): SshResult<Unit> = writeFile(
        SESSION,
        path,
        ByteArrayInputStream(data),
        data.size.toLong(),
        replaceExisting,
        onProgress,
    )

    /** Makes every write to the mocked remote file append to [sink]. */
    private fun recordWritesInto(sink: ByteArrayOutputStream) {
        every { remoteFile.write(any<Long>(), any<ByteArray>(), any<Int>(), any<Int>()) } answers {
            sink.write(secondArg<ByteArray>(), thirdArg<Int>(), arg<Int>(3))
        }
    }

    // ── writeFile: success ────────────────────────────────────────────────────

    @Test
    fun `without replacement the open is WRITE, CREAT and EXCL, never TRUNC, and the bytes are written`() = runTest {
        val manager = openedManager()
        val modes = slot<Set<OpenMode>>()
        every { sftp.open(TARGET, capture(modes)) } returns remoteFile
        val written = ByteArrayOutputStream()
        recordWritesInto(written)
        // More than two chunks of 32 KiB, so the copy loop runs several times.
        val data = ByteArray(70_000) { (it % 251).toByte() }
        val progress = mutableListOf<Pair<Long, Long>>()

        val result = manager.write(replaceExisting = false, data = data) { done, total -> progress += done to total }

        assertEquals<SshResult<Unit>>(SshResult.Success(Unit), result)
        assertEquals(setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL), modes.captured)
        assertFalse(OpenMode.TRUNC in modes.captured, "TRUNC would wipe a file created in the meantime")
        assertContentEquals(data, written.toByteArray())
        assertEquals(70_000L to 70_000L, progress.last())
        verify(exactly = 1) { remoteFile.close() }
    }

    /**
     * A regular file of the user, readable and writable. The server can replace it by a rename, unless
     * [posixRename] is false (it does not announce `posix-rename@openssh.com`).
     */
    private fun givenReplaceableTarget(posixRename: Boolean = true) {
        val engine = mockk<SFTPEngine>()
        every { sftp.lstat(TARGET) } returns FileAttributes.Builder()
            .withType(FileMode.Type.REGULAR)
            .withPermissions(0b110_100_100)
            .withUIDGID(1000, 1000)
            .build()
        every { sftp.sftpEngine } returns engine
        every { engine.supportsServerExtension("posix-rename", "openssh.com") } returns posixRename
    }

    @Test
    fun `with replacement the data goes to a hidden temporary file of the same folder, never to the target`() = runTest {
        val manager = openedManager()
        givenReplaceableTarget()
        val temp = slot<String>()
        val modes = slot<Set<OpenMode>>()
        val attributes = slot<FileAttributes>()
        every { sftp.open(capture(temp), capture(modes), capture(attributes)) } returns remoteFile
        every { remoteFile.fetchAttributes() } returns FileAttributes.Builder().withUIDGID(1000, 1000).build()
        every { sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) } just Runs
        val written = ByteArrayOutputStream()
        recordWritesInto(written)
        val data = ByteArray(1_000) { it.toByte() }

        val result = manager.write(replaceExisting = true, data = data)

        assertEquals<SshResult<Unit>>(SshResult.Success(Unit), result)
        assertTrue(Regex("""/home/t/\.a\.bin\.nextsh-part-[0-9a-f]{8}""").matches(temp.captured), temp.captured)
        assertEquals(setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL), modes.captured)
        assertFalse(OpenMode.TRUNC in modes.captured, "TRUNC would empty the file before the first byte of the new one")
        assertEquals(0b110_000_000, attributes.captured.mode.permissionsMask)
        assertContentEquals(data, written.toByteArray())
        verify(exactly = 1) { sftp.rename(temp.captured, TARGET, setOf(RenameFlags.OVERWRITE)) }
        verify(exactly = 0) { sftp.open(TARGET, any<Set<OpenMode>>()) }
    }

    @Test
    fun `an empty input opens and closes the file and writes nothing`() = runTest {
        val manager = openedManager()
        every { sftp.open(TARGET, any()) } returns remoteFile

        val result = manager.write(replaceExisting = false, data = ByteArray(0))

        assertEquals<SshResult<Unit>>(SshResult.Success(Unit), result)
        verify(exactly = 0) { remoteFile.write(any<Long>(), any<ByteArray>(), any<Int>(), any<Int>()) }
        verify(exactly = 1) { remoteFile.close() }
    }

    // ── writeFile: a refused EXCL open means the name is taken ────────────────

    @Test
    fun `the already exists status gives the exists message and nothing is written`() = runTest {
        val manager = openedManager()
        every { sftp.open(TARGET, any()) } throws SFTPException(Response.StatusCode.FILE_ALREADY_EXISTS, "File exists")

        val result = manager.write(replaceExisting = false)

        assertError(getString(Res.string.sftp_error_target_exists), result)
        verify(exactly = 0) { remoteFile.write(any<Long>(), any<ByteArray>(), any<Int>(), any<Int>()) }
        verify(exactly = 0) { remoteFile.close() }
    }

    @Test
    fun `a bare FAILURE on the open gives the exists message when the stat finds the name`() = runTest {
        // OpenSSH on SFTP v3 has no "already exists" status: it answers a plain "Failure".
        val manager = openedManager()
        every { sftp.open(TARGET, any()) } throws SFTPException(Response.StatusCode.FAILURE, "Failure")
        every { sftp.statExistence(TARGET) } returns FileAttributes.EMPTY

        val result = manager.write(replaceExisting = false)

        assertError(getString(Res.string.sftp_error_target_exists), result)
        verify(exactly = 1) { sftp.statExistence(TARGET) }
    }

    @Test
    fun `a refusal without a status gives the exists message when the stat finds the name`() = runTest {
        val manager = openedManager()
        every { sftp.open(TARGET, any()) } throws SFTPException("Failure")
        every { sftp.statExistence(TARGET) } returns FileAttributes.EMPTY

        assertError(getString(Res.string.sftp_error_target_exists), manager.write(replaceExisting = false))
    }

    @Test
    fun `a bare FAILURE on the open stays an upload failure when the stat finds nothing`() = runTest {
        val manager = openedManager()
        every { sftp.open(TARGET, any()) } throws SFTPException(Response.StatusCode.FAILURE, "Failure")
        every { sftp.statExistence(TARGET) } returns null

        assertError(getString(Res.string.sftp_error_upload_failed), manager.write(replaceExisting = false))
    }

    @Test
    fun `a bare FAILURE on the open stays an upload failure when the stat itself fails`() = runTest {
        val manager = openedManager()
        every { sftp.open(TARGET, any()) } throws SFTPException(Response.StatusCode.FAILURE, "Failure")
        every { sftp.statExistence(TARGET) } throws IOException("socket closed")

        assertError(getString(Res.string.sftp_error_upload_failed), manager.write(replaceExisting = false))
    }

    @Test
    fun `a refusal that is not about the name is not second-guessed by a stat`() = runTest {
        val manager = openedManager()
        every { sftp.open(TARGET, any()) } throws SFTPException(Response.StatusCode.PERMISSION_DENIED, "Permission denied")
        // The worst answer, to prove it is never asked for.
        every { sftp.statExistence(any()) } returns FileAttributes.EMPTY

        val result = manager.write(replaceExisting = false)

        assertError(getString(Res.string.sftp_error_permission_denied), result)
        verify(exactly = 0) { sftp.statExistence(any()) }
    }

    @Test
    fun `with replacement a failed in-place open is an upload failure and no stat is made`() = runTest {
        // A server without posix-rename: the upload is written in place, the open decides.
        val manager = openedManager()
        givenReplaceableTarget(posixRename = false)
        every { sftp.open(TARGET, any()) } throws SFTPException(Response.StatusCode.FAILURE, "Failure")
        every { sftp.statExistence(any()) } returns FileAttributes.EMPTY

        val result = manager.write(replaceExisting = true)

        assertError(getString(Res.string.sftp_error_upload_failed), result)
        verify(exactly = 0) { sftp.statExistence(any()) }
    }

    @Test
    fun `with replacement a refusal of permission on the temporary file falls back to an in-place write`() = runTest {
        val manager = openedManager()
        givenReplaceableTarget()
        every { sftp.open(match<String> { it.contains(".nextsh-part-") }, any<Set<OpenMode>>(), any<FileAttributes>()) } throws
            SFTPException(Response.StatusCode.PERMISSION_DENIED, "Permission denied")
        val modes = slot<Set<OpenMode>>()
        every { sftp.open(TARGET, capture(modes)) } returns remoteFile

        val result = manager.write(replaceExisting = true)

        assertEquals<SshResult<Unit>>(SshResult.Success(Unit), result)
        assertEquals(setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC), modes.captured)
        verify(exactly = 0) { sftp.statExistence(any()) }
        verify(exactly = 0) { sftp.rm(any<String>()) }
    }

    @Test
    fun `a failure during the copy is an upload failure, no stat names it, and the incomplete file is deleted`() = runTest {
        val manager = openedManager()
        every { sftp.open(TARGET, any()) } returns remoteFile
        every { remoteFile.write(any<Long>(), any<ByteArray>(), any<Int>(), any<Int>()) } throws
            SFTPException(Response.StatusCode.FAILURE, "Failure")
        every { sftp.statExistence(any()) } returns FileAttributes.EMPTY
        // The file this call created: what its handle states is what the name still shows, so it is ours to delete.
        val created = FileAttributes.Builder()
            .withType(FileMode.Type.REGULAR)
            .withUIDGID(1000, 1000)
            .withSize(0)
            .withAtimeMtime(1_000, 1_000)
            .build()
        every { remoteFile.fetchAttributes() } returns created
        every { sftp.lstat(TARGET) } returns created
        every { sftp.rm(TARGET) } just Runs

        val result = manager.write(replaceExisting = false)

        assertError(getString(Res.string.sftp_error_upload_failed), result)
        verify(exactly = 0) { sftp.statExistence(any()) }
        verify(exactly = 1) { remoteFile.close() }
        verify(exactly = 1) { sftp.rm(TARGET) }
    }

    // ── writeFile: other errors and refused input ─────────────────────────────

    @Test
    fun `a connection lost during the open gives the connection lost message`() = runTest {
        val manager = openedManager()
        every { sftp.open(TARGET, any()) } throws IOException("socket closed")

        assertError(getString(Res.string.sftp_error_connection_lost), manager.write(replaceExisting = false))
    }

    @Test
    fun `an unexpected exception gives the generic upload message instead of escaping`() = runTest {
        val manager = openedManager()
        every { sftp.open(TARGET, any()) } throws IllegalStateException("boom")

        assertError(getString(Res.string.sftp_error_upload_generic), manager.write(replaceExisting = false))
    }

    @Test
    fun `a path that climbs above the root is refused without any SFTP call`() = runTest {
        val manager = openedManager()

        val result = manager.write(replaceExisting = false, path = "/..")

        assertError(getString(Res.string.sftp_error_invalid_path), result)
        confirmVerified(sftp)
    }

    @Test
    fun `an unknown session reports the client not ready message`() = runTest {
        val manager = DesktopSftpManager(mockk())

        val result = manager.writeFile("no-such-session", TARGET, ByteArrayInputStream(ByteArray(1)), 1L, false) { _, _ -> }

        assertError(getString(Res.string.sftp_error_client_not_ready), result)
    }

    // ── entryKind ─────────────────────────────────────────────────────────────

    private fun attributesOf(type: FileMode.Type): FileAttributes = FileAttributes.Builder().withType(type).build()

    private suspend fun kindOf(attributes: FileAttributes?): SshResult<RemoteEntryKind> {
        val manager = openedManager()
        every { sftp.statExistence(TARGET) } returns attributes
        return manager.entryKind(SESSION, TARGET)
    }

    @Test
    fun `an entry the server does not know is absent`() = runTest {
        assertEquals<SshResult<RemoteEntryKind>>(SshResult.Success(RemoteEntryKind.ABSENT), kindOf(null))
    }

    @Test
    fun `a regular file, a type the server did not state and a link are files`() = runTest {
        val expected = SshResult.Success(RemoteEntryKind.FILE)

        assertEquals<SshResult<RemoteEntryKind>>(expected, kindOf(attributesOf(FileMode.Type.REGULAR)))
        assertEquals<SshResult<RemoteEntryKind>>(expected, kindOf(FileAttributes.EMPTY))
        assertEquals<SshResult<RemoteEntryKind>>(expected, kindOf(attributesOf(FileMode.Type.UNKNOWN)))
        assertEquals<SshResult<RemoteEntryKind>>(expected, kindOf(attributesOf(FileMode.Type.SYMLINK)))
    }

    @Test
    fun `a directory is a directory`() = runTest {
        assertEquals<SshResult<RemoteEntryKind>>(
            SshResult.Success(RemoteEntryKind.DIRECTORY),
            kindOf(attributesOf(FileMode.Type.DIRECTORY)),
        )
    }

    @Test
    fun `a FIFO, a socket and a device are another kind of entry`() = runTest {
        val expected = SshResult.Success(RemoteEntryKind.OTHER)
        val types = listOf(
            FileMode.Type.FIFO_SPECIAL,
            FileMode.Type.SOCKET_SPECIAL,
            FileMode.Type.BLOCK_SPECIAL,
            FileMode.Type.CHAR_SPECIAL,
        )

        for (type in types) {
            assertEquals<SshResult<RemoteEntryKind>>(expected, kindOf(attributesOf(type)), "type $type")
        }
    }

    @Test
    fun `the path is normalized before the stat`() = runTest {
        val manager = openedManager()
        every { sftp.statExistence("/home/t/a.bin") } returns null

        val result = manager.entryKind(SESSION, "/home//t/./a.bin")

        assertEquals<SshResult<RemoteEntryKind>>(SshResult.Success(RemoteEntryKind.ABSENT), result)
        verify(exactly = 1) { sftp.statExistence("/home/t/a.bin") }
    }

    @Test
    fun `a failed stat is an error and never an absent entry`() = runTest {
        val manager = openedManager()

        every { sftp.statExistence(TARGET) } throws SFTPException(Response.StatusCode.PERMISSION_DENIED, "Permission denied")
        assertError(getString(Res.string.sftp_error_permission_denied), manager.entryKind(SESSION, TARGET))

        every { sftp.statExistence(TARGET) } throws SFTPException(Response.StatusCode.FAILURE, "Failure")
        assertError(getString(Res.string.sftp_error_stat_failed), manager.entryKind(SESSION, TARGET))

        every { sftp.statExistence(TARGET) } throws IOException("socket closed")
        assertError(getString(Res.string.sftp_error_connection_lost), manager.entryKind(SESSION, TARGET))

        every { sftp.statExistence(TARGET) } throws IllegalStateException("boom")
        assertError(getString(Res.string.sftp_error_stat_failed), manager.entryKind(SESSION, TARGET))
    }

    @Test
    fun `a cancellation is not turned into an error result`() = runTest {
        val manager = openedManager()
        every { sftp.statExistence(TARGET) } throws CancellationException("cancelled")

        assertFailsWith<CancellationException> { manager.entryKind(SESSION, TARGET) }
    }

    @Test
    fun `a path that climbs above the root is refused by entryKind without any SFTP call`() = runTest {
        val manager = openedManager()

        val result = manager.entryKind(SESSION, "/..")

        assertError(getString(Res.string.sftp_error_invalid_path), result)
        confirmVerified(sftp)
    }

    @Test
    fun `entryKind on an unknown session reports the client not ready message`() = runTest {
        val manager = DesktopSftpManager(mockk())

        assertError(getString(Res.string.sftp_error_client_not_ready), manager.entryKind("no-such-session", TARGET))
    }

    @Test
    fun `the kinds are the four the browser reasons about`() {
        assertTrue(RemoteEntryKind.entries.map { it.name }.toSet() == setOf("ABSENT", "FILE", "DIRECTORY", "OTHER"))
    }

    private companion object {
        const val SESSION = "s1"
        const val TARGET = "/home/t/a.bin"
    }
}
