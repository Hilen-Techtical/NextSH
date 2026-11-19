// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.ssh

import fr.techtical.nextsh.desktop.captureLogs
import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_connection_lost
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_local_read_failed
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_permission_denied
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_replace_failed_kept
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_target_exists
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_upload_failed
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_upload_target_changed
import fr.techtical.nextsh.desktop.taggedBy
import fr.techtical.nextsh.shared.domain.model.SshErrorCode
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.shared.util.LogId
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
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
import org.jetbrains.compose.resources.getString
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [DesktopSftpManager.writeFile] with `replaceExisting`: the route through a hidden temporary file and a
 * `posix-rename@openssh.com`, the cases where it is impossible and the upload is written in place, and
 * every way it can fail. The SFTP client is a strict mock: any call the implementation was not expected
 * to make fails the test. Messages are compared with the resources, never with a literal.
 *
 * The point of these tests: the file that is replaced is never touched before the new content is complete
 * and closed, whatever fails; and the route is only taken when it changes nothing but the content.
 */
class DesktopSftpManagerReplaceTest {

    private val sftp = mockk<SFTPClient>()
    private val engine = mockk<SFTPEngine>()
    private val tempFile = mockk<RemoteFile>(relaxed = true)
    private val targetFile = mockk<RemoteFile>(relaxed = true)

    /** What the implementation asked of the server for its temporary file, in order. */
    private val tempNames = mutableListOf<String>()
    private val tempModes = mutableListOf<Set<OpenMode>>()
    private val tempAttributes = mutableListOf<FileAttributes>()

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

    private fun isTemp(path: String): Boolean = path.contains(".nextsh-part-")

    /** A regular file with its permissions and owner, and its size and modification time when they are given. */
    private fun regular(
        permissions: Int = 0b110_100_100,
        uid: Int = 1000,
        gid: Int = 1000,
        size: Long? = null,
        mtime: Long? = null,
    ): FileAttributes {
        val builder = FileAttributes.Builder().withType(FileMode.Type.REGULAR).withPermissions(permissions).withUIDGID(uid, gid)
        if (size != null) builder.withSize(size)
        if (mtime != null) builder.withAtimeMtime(mtime, mtime)
        return builder.build()
    }

    /** A symbolic link of the owner [uid] (the connected user is 1000, see [regular]). */
    private fun link(uid: Int = 1000): FileAttributes =
        FileAttributes.Builder().withType(FileMode.Type.SYMLINK).withUIDGID(uid, uid).build()

    private fun typed(type: FileMode.Type): FileAttributes = FileAttributes.Builder().withType(type).build()

    private fun noSuchFile() = SFTPException(Response.StatusCode.NO_SUCH_FILE, "No such file")

    private fun failure() = SFTPException(Response.StatusCode.FAILURE, "Failure")

    /**
     * A target that can be replaced by the temporary file route: [old] is what `lstat` says about [target],
     * the server announces `posix-rename` unless [extension] is false, and the temporary file is created
     * with the attributes [created] (the owner it would get from the server).
     */
    private fun arrangeReplacement(
        old: FileAttributes = regular(),
        created: FileAttributes = regular(permissions = 0b110_000_000),
        extension: Boolean = true,
        target: String = TARGET,
    ) {
        every { sftp.lstat(target) } returns old
        every { sftp.sftpEngine } returns engine
        every { engine.supportsServerExtension("posix-rename", "openssh.com") } returns extension
        every { sftp.open(match<String> { isTemp(it) }, any<Set<OpenMode>>(), any<FileAttributes>()) } answers {
            tempNames += firstArg<String>()
            tempModes += secondArg<Set<OpenMode>>()
            tempAttributes += thirdArg<FileAttributes>()
            tempFile
        }
        every { tempFile.fetchAttributes() } returns created
        every { sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) } just Runs
        every { sftp.rm(any<String>()) } just Runs
    }

    /** The in-place open of [path]: what the upload falls back to. Its modes land in [modes]. */
    private fun arrangeInPlaceOpen(modes: MutableList<Set<OpenMode>>, path: String = TARGET) {
        every { sftp.open(path, any<Set<OpenMode>>()) } answers {
            modes += secondArg<Set<OpenMode>>()
            targetFile
        }
    }

    private fun recordWritesInto(file: RemoteFile): ByteArrayOutputStream {
        val sink = ByteArrayOutputStream()
        every { file.write(any<Long>(), any<ByteArray>(), any<Int>(), any<Int>()) } answers {
            sink.write(secondArg<ByteArray>(), thirdArg<Int>(), arg<Int>(3))
        }
        return sink
    }

    private suspend fun DesktopSftpManager.write(
        data: ByteArray = ByteArray(10),
        path: String = TARGET,
        source: InputStream = ByteArrayInputStream(data),
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): SshResult<Unit> = writeFile(SESSION, path, source, data.size.toLong(), true, onProgress)

    private fun verifyNothingOpened() {
        verify(exactly = 0) { sftp.open(any<String>(), any<Set<OpenMode>>(), any<FileAttributes>()) }
        verify(exactly = 0) { sftp.open(any<String>(), any<Set<OpenMode>>()) }
        verify(exactly = 0) { sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) }
        verify(exactly = 0) { sftp.rm(any<String>()) }
    }

    // ── The route through a temporary file ────────────────────────────────────

    @Test
    fun `a replacement writes a hidden temporary file in 0600 next to the target, closes it, then renames it over the target`() = runTest {
        val manager = openedManager()
        arrangeReplacement()
        val written = recordWritesInto(tempFile)
        // More than two blocks of 32 KiB, so that the copy loop runs several times.
        val data = ByteArray(70_000) { (it % 251).toByte() }
        val progress = mutableListOf<Pair<Long, Long>>()

        val result = manager.write(data = data) { done, total -> progress += done to total }

        assertEquals<SshResult<Unit>>(SshResult.Success(Unit), result)
        val temp = tempNames.single()
        assertTrue(Regex("""/home/t/\.a\.bin\.nextsh-part-[0-9a-f]{8}""").matches(temp), temp)
        assertEquals(setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL), tempModes.single())
        assertTrue(tempAttributes.single().has(FileAttributes.Flag.MODE))
        assertEquals(0b110_000_000, tempAttributes.single().mode.permissionsMask)
        assertContentEquals(data, written.toByteArray())
        assertEquals(70_000L to 70_000L, progress.last())
        verifyOrder {
            tempFile.close()
            sftp.rename(temp, TARGET, setOf(RenameFlags.OVERWRITE))
        }
        // The target is never opened, and the temporary file is not removed: it became the target.
        verify(exactly = 0) { sftp.open(any<String>(), any<Set<OpenMode>>()) }
        verify(exactly = 0) { sftp.rm(any<String>()) }
    }

    @Test
    fun `an empty file still goes through the temporary file and is renamed`() = runTest {
        val manager = openedManager()
        arrangeReplacement()

        val result = manager.write(data = ByteArray(0))

        assertEquals<SshResult<Unit>>(SshResult.Success(Unit), result)
        verify(exactly = 0) { tempFile.write(any<Long>(), any<ByteArray>(), any<Int>(), any<Int>()) }
        verify(exactly = 1) { tempFile.close() }
        verify(exactly = 1) { sftp.rename(tempNames.single(), TARGET, setOf(RenameFlags.OVERWRITE)) }
    }

    @Test
    fun `the temporary file of a target at the root of the server is written without a double slash`() = runTest {
        val manager = openedManager()
        arrangeReplacement(target = "/a.bin")

        val result = manager.write(path = "/a.bin")

        assertEquals<SshResult<Unit>>(SshResult.Success(Unit), result)
        assertTrue(Regex("""/\.a\.bin\.nextsh-part-[0-9a-f]{8}""").matches(tempNames.single()), tempNames.single())
    }

    // ── The temporary files under way ─────────────────────────────────────────

    @Test
    fun `the name of the temporary file is under way while the replacement runs, and known gone once it was renamed`() = runTest {
        val manager = openedManager()
        arrangeReplacement()
        assertTrue(manager.knownUploadTempNames().isEmpty())
        val seenWhileCopying = mutableListOf<Set<String>>()
        val underWayWhileCopying = mutableListOf<Boolean>()
        every { tempFile.write(any<Long>(), any<ByteArray>(), any<Int>(), any<Int>()) } answers {
            seenWhileCopying += manager.knownUploadTempNames()
            underWayWhileCopying += manager.isUploadTempUnderWay(tempNames.single().substringAfterLast('/'))
        }

        val result = manager.write()

        assertEquals<SshResult<Unit>>(SshResult.Success(Unit), result)
        val name = tempNames.single().substringAfterLast('/')
        assertEquals(setOf(name), seenWhileCopying.single())
        assertEquals(listOf(true), underWayWhileCopying)
        // It was renamed over the target: it is not under way any more, and a listing taken meanwhile
        // that still shows it must not take it for a leftover.
        assertFalse(manager.isUploadTempUnderWay(name))
        assertEquals(setOf(name), manager.knownUploadTempNames())
    }

    @Test
    fun `a temporary file deleted after a failed replacement is known gone`() = runTest {
        val manager = openedManager()
        arrangeReplacement()
        every { tempFile.write(any<Long>(), any<ByteArray>(), any<Int>(), any<Int>()) } throws failure()

        manager.write()

        val name = tempNames.single().substringAfterLast('/')
        verify(exactly = 1) { sftp.rm(tempNames.single()) }
        assertEquals(setOf(name), manager.knownUploadTempNames())
        assertFalse(manager.isUploadTempUnderWay(name))
    }

    @Test
    fun `a temporary file that stays after a lost channel is a leftover, neither under way nor known gone`() = runTest {
        val manager = openedManager()
        arrangeReplacement()
        every { tempFile.write(any<Long>(), any<ByteArray>(), any<Int>(), any<Int>()) } throws
            SFTPException(Response.StatusCode.UNKNOWN, "channel lost")

        manager.write()

        verify(exactly = 0) { sftp.rm(any<String>()) }
        assertTrue(manager.knownUploadTempNames().isEmpty())
        assertFalse(manager.isUploadTempUnderWay(tempNames.single().substringAfterLast('/')))
    }

    @Test
    fun `a temporary file that could not be deleted is a leftover, neither under way nor known gone`() = runTest {
        val manager = openedManager()
        arrangeReplacement()
        every { tempFile.write(any<Long>(), any<ByteArray>(), any<Int>(), any<Int>()) } throws failure()
        every { sftp.rm(any<String>()) } throws SFTPException(Response.StatusCode.PERMISSION_DENIED, "denied")

        manager.write()

        assertTrue(manager.knownUploadTempNames().isEmpty())
    }

    @Test
    fun `only the last 256 names known gone are kept`() = runTest {
        val manager = openedManager()
        arrangeReplacement()

        repeat(260) { manager.write() }

        assertEquals(256, manager.knownUploadTempNames().size)
        assertFalse(manager.knownUploadTempNames().contains(tempNames.first().substringAfterLast('/')))
        assertTrue(manager.knownUploadTempNames().contains(tempNames.last().substringAfterLast('/')))
    }
    // ── Permissions ───────────────────────────────────────────────────────────

    @Test
    fun `the permission bits of the old file are given to the new one before it is closed, without setuid`() = runTest {
        val manager = openedManager()
        // 04755: setuid and rwxr-xr-x.
        arrangeReplacement(old = regular(permissions = 0b100_111_101_101))
        val attributes = mutableListOf<FileAttributes>()
        every { tempFile.setAttributes(capture(attributes)) } just Runs

        val result = manager.write()

        assertEquals<SshResult<Unit>>(SshResult.Success(Unit), result)
        val set = attributes.single()
        assertTrue(set.has(FileAttributes.Flag.MODE))
        assertEquals(0b111_101_101, set.mode.permissionsMask)
        verifyOrder {
            tempFile.setAttributes(any())
            tempFile.close()
            sftp.rename(tempNames.single(), TARGET, setOf(RenameFlags.OVERWRITE))
        }
    }

    @Test
    fun `a refusal to copy the permissions does not stop the replacement and is logged`() = runTest {
        val manager = openedManager()
        arrangeReplacement()
        every { tempFile.setAttributes(any()) } throws SFTPException(Response.StatusCode.PERMISSION_DENIED, "Permission denied")

        val lines = captureLogs {
            val result = manager.write()
            assertEquals<SshResult<Unit>>(SshResult.Success(Unit), result)
        }.taggedBy("DesktopSftpManager")

        verify(exactly = 1) { sftp.rename(tempNames.single(), TARGET, setOf(RenameFlags.OVERWRITE)) }
        verify(exactly = 0) { sftp.rm(any<String>()) }
        assertTrue(lines.any { it.contains("permissions of the replaced file not kept") && it.contains("SFTPException") }, "got $lines")
    }

    @Test
    fun `an old file whose permissions or owner the server does not state is written in place, never renamed over`() = runTest {
        val manager = openedManager()
        arrangeReplacement()
        val modes = mutableListOf<Set<OpenMode>>()
        arrangeInPlaceOpen(modes)
        val unstated = listOf(
            // No type, no mode, no owner: what a server that gives few attributes answers.
            FileAttributes.EMPTY,
            // The permissions are stated, the owner is not: it could not be kept.
            FileAttributes.Builder().withType(FileMode.Type.REGULAR).withPermissions(0b110_100_100).build(),
            // The owner is stated, the permissions are not: the new file would stay in 0600. In SFTP v3
            // the type travels inside the mode, so no type either (withType would state a mode of 0).
            FileAttributes.Builder().withUIDGID(1000, 1000).build(),
        )

        val lines = captureLogs {
            for (old in unstated) {
                every { sftp.lstat(TARGET) } returns old

                assertEquals<SshResult<Unit>>(SshResult.Success(Unit), manager.write())
            }
        }.taggedBy("DesktopSftpManager")

        assertEquals(3, modes.size)
        assertTrue(modes.all { it == setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC) })
        assertTrue(tempNames.isEmpty())
        verify(exactly = 0) { sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) }
        assertEquals(3, lines.count { it.contains("reason attributes-unknown") }, "got $lines")
    }

    // ── Owner and group ───────────────────────────────────────────────────────

    @Test
    fun `a new file that is not owned by the owner of the old one is given back to it before the copy`() = runTest {
        val manager = openedManager()
        arrangeReplacement(old = regular(uid = 33, gid = 33), created = regular(permissions = 0b110_000_000, uid = 0, gid = 0))
        val attributes = mutableListOf<FileAttributes>()
        every { tempFile.setAttributes(capture(attributes)) } just Runs
        recordWritesInto(tempFile)

        val result = manager.write()

        assertEquals<SshResult<Unit>>(SshResult.Success(Unit), result)
        val owner = attributes.first()
        assertTrue(owner.has(FileAttributes.Flag.UIDGID))
        assertEquals(33, owner.uid)
        assertEquals(33, owner.gid)
        assertEquals(2, attributes.size, "the owner, then the permission bits")
        verifyOrder {
            tempFile.setAttributes(match { it.has(FileAttributes.Flag.UIDGID) })
            tempFile.write(any<Long>(), any<ByteArray>(), any<Int>(), any<Int>())
            tempFile.close()
            sftp.rename(tempNames.single(), TARGET, setOf(RenameFlags.OVERWRITE))
        }
    }

    @Test
    fun `a new file owned by the owner of the old one is not given back`() = runTest {
        val manager = openedManager()
        arrangeReplacement()

        manager.write()

        verify(exactly = 0) { tempFile.setAttributes(match { it.has(FileAttributes.Flag.UIDGID) }) }
        verify(exactly = 1) { sftp.rename(tempNames.single(), TARGET, setOf(RenameFlags.OVERWRITE)) }
    }

    @Test
    fun `a new file whose owner the server does not state cannot be checked, so the upload is written in place`() = runTest {
        val manager = openedManager()
        arrangeReplacement(old = regular(uid = 33, gid = 33), created = FileAttributes.EMPTY)
        val modes = mutableListOf<Set<OpenMode>>()
        arrangeInPlaceOpen(modes)

        val lines = captureLogs {
            assertEquals<SshResult<Unit>>(SshResult.Success(Unit), manager.write())
        }.taggedBy("DesktopSftpManager")

        assertEquals(listOf(setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)), modes)
        verify(exactly = 0) { tempFile.write(any<Long>(), any<ByteArray>(), any<Int>(), any<Int>()) }
        verify(exactly = 0) { sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) }
        verifyOrder {
            tempFile.close()
            sftp.rm(tempNames.single())
            sftp.open(TARGET, any<Set<OpenMode>>())
        }
        assertTrue(lines.any { it.contains("reason attributes-unknown") }, "got $lines")
    }

    @Test
    fun `a refused read of the owner of the new file falls back to an in-place write once the temporary file is closed and removed`() = runTest {
        val manager = openedManager()
        arrangeReplacement()
        val modes = mutableListOf<Set<OpenMode>>()
        arrangeInPlaceOpen(modes)
        // A refusal of the server, not a lost channel: the session is still there to close and remove.
        val refusals = listOf(
            SFTPException(Response.StatusCode.PERMISSION_DENIED, "Permission denied"),
            SFTPException(Response.StatusCode.FAILURE, "Failure"),
        )

        val lines = captureLogs {
            for (refusal in refusals) {
                every { tempFile.fetchAttributes() } throws refusal

                assertEquals<SshResult<Unit>>(SshResult.Success(Unit), manager.write())

                verifyOrder {
                    tempFile.close()
                    sftp.rm(tempNames.last())
                    sftp.open(TARGET, any<Set<OpenMode>>())
                }
            }
        }.taggedBy("DesktopSftpManager")

        assertEquals(2, tempNames.size)
        assertEquals(2, modes.size)
        assertTrue(modes.all { it == setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC) })
        verify(exactly = 0) { tempFile.write(any<Long>(), any<ByteArray>(), any<Int>(), any<Int>()) }
        verify(exactly = 0) { sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) }
        assertEquals(2, lines.count { it.contains("reason owner-not-kept") }, "got $lines")
    }

    @Test
    fun `when the owner cannot be given back the empty temporary file is removed and the upload is written in place`() = runTest {
        val manager = openedManager()
        arrangeReplacement(old = regular(uid = 0, gid = 0), created = regular(permissions = 0b110_000_000, uid = 1000, gid = 1000))
        every { tempFile.setAttributes(any()) } throws SFTPException(Response.StatusCode.PERMISSION_DENIED, "Permission denied")
        val modes = mutableListOf<Set<OpenMode>>()
        arrangeInPlaceOpen(modes)
        val written = recordWritesInto(targetFile)
        val data = ByteArray(1_000) { it.toByte() }

        val lines = captureLogs {
            assertEquals<SshResult<Unit>>(SshResult.Success(Unit), manager.write(data = data))
        }.taggedBy("DesktopSftpManager")

        assertEquals(listOf(setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)), modes)
        assertContentEquals(data, written.toByteArray())
        verify(exactly = 0) { tempFile.write(any<Long>(), any<ByteArray>(), any<Int>(), any<Int>()) }
        verify(exactly = 0) { sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) }
        verifyOrder {
            tempFile.close()
            sftp.rm(tempNames.single())
            sftp.open(TARGET, any<Set<OpenMode>>())
        }
        assertTrue(lines.any { it.contains("reason owner-not-kept") }, "got $lines")
    }

    // ── Where the route is impossible: written in place, as before ────────────

    @Test
    fun `a server without posix-rename gets an in-place write and no temporary file`() = runTest {
        val manager = openedManager()
        arrangeReplacement(extension = false)
        val modes = mutableListOf<Set<OpenMode>>()
        arrangeInPlaceOpen(modes)
        val written = recordWritesInto(targetFile)
        val data = ByteArray(1_000) { it.toByte() }

        val lines = captureLogs {
            assertEquals<SshResult<Unit>>(SshResult.Success(Unit), manager.write(data = data))
        }.taggedBy("DesktopSftpManager")

        assertEquals(listOf(setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)), modes)
        assertContentEquals(data, written.toByteArray())
        assertTrue(tempNames.isEmpty())
        verify(exactly = 0) { sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) }
        verify(exactly = 0) { sftp.rm(any<String>()) }
        assertTrue(lines.any { it.contains("reason no-posix-rename") && it.contains(LogId.of(SESSION)) }, "got $lines")
    }

    @Test
    fun `a file that is write-protected for its owner is written in place, where the server decides`() = runTest {
        val manager = openedManager()
        arrangeReplacement(old = regular(permissions = 0b100_100_100))
        val modes = mutableListOf<Set<OpenMode>>()
        arrangeInPlaceOpen(modes)

        val lines = captureLogs {
            assertEquals<SshResult<Unit>>(SshResult.Success(Unit), manager.write())
        }.taggedBy("DesktopSftpManager")

        assertEquals(listOf(setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)), modes)
        assertTrue(tempNames.isEmpty())
        verify(exactly = 0) { sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) }
        assertTrue(lines.any { it.contains("reason read-only") }, "got $lines")
    }

    @Test
    fun `a write-protected file that the server refuses to open stays untouched and is reported as a permission refusal`() = runTest {
        val manager = openedManager()
        arrangeReplacement(old = regular(permissions = 0b100_100_100))
        every { sftp.open(TARGET, any<Set<OpenMode>>()) } throws SFTPException(Response.StatusCode.PERMISSION_DENIED, "Permission denied")

        val result = manager.write()

        assertError(getString(Res.string.sftp_error_permission_denied), result)
        assertTrue(tempNames.isEmpty())
        verify(exactly = 0) { sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) }
    }

    @Test
    fun `a folder that cannot be written gets an in-place write, and nothing is removed`() = runTest {
        val manager = openedManager()
        arrangeReplacement()
        every { sftp.open(match<String> { isTemp(it) }, any<Set<OpenMode>>(), any<FileAttributes>()) } throws
            SFTPException(Response.StatusCode.PERMISSION_DENIED, "Permission denied")
        val modes = mutableListOf<Set<OpenMode>>()
        arrangeInPlaceOpen(modes)

        val lines = captureLogs {
            assertEquals<SshResult<Unit>>(SshResult.Success(Unit), manager.write())
        }.taggedBy("DesktopSftpManager")

        assertEquals(listOf(setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)), modes)
        verify(exactly = 0) { sftp.rm(any<String>()) }
        verify(exactly = 0) { sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) }
        assertTrue(lines.any { it.contains("reason dir-not-writable") }, "got $lines")
    }

    @Test
    fun `a plain failure that says permission is read as a folder that cannot be written`() = runTest {
        val manager = openedManager()
        arrangeReplacement()
        every { sftp.open(match<String> { isTemp(it) }, any<Set<OpenMode>>(), any<FileAttributes>()) } throws
            SFTPException(Response.StatusCode.FAILURE, "Permission denied")
        val modes = mutableListOf<Set<OpenMode>>()
        arrangeInPlaceOpen(modes)

        assertEquals<SshResult<Unit>>(SshResult.Success(Unit), manager.write())

        assertEquals(listOf(setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)), modes)
    }

    // ── The name of the temporary file is taken ───────────────────────────────

    private fun arrangeCollisions(count: Int) {
        var refused = 0
        every { sftp.open(match<String> { isTemp(it) }, any<Set<OpenMode>>(), any<FileAttributes>()) } answers {
            tempNames += firstArg<String>()
            if (++refused <= count) throw failure()
            tempFile
        }
        // The refused names exist: that is why they were refused.
        every { sftp.lstat(match<String> { isTemp(it) }) } returns FileAttributes.EMPTY
    }

    @Test
    fun `a taken temporary name is retried under another name`() = runTest {
        val manager = openedManager()
        arrangeReplacement()
        arrangeCollisions(count = 2)

        val result = manager.write()

        assertEquals<SshResult<Unit>>(SshResult.Success(Unit), result)
        assertEquals(3, tempNames.size)
        assertEquals(3, tempNames.toSet().size, "each try uses a new name: $tempNames")
        verify(exactly = 1) { sftp.rename(tempNames.last(), TARGET, setOf(RenameFlags.OVERWRITE)) }
        verify(exactly = 0) { sftp.rm(any<String>()) }
    }

    @Test
    fun `three taken temporary names fail the upload with the existing file kept, and nothing was created`() = runTest {
        val manager = openedManager()
        arrangeReplacement()
        arrangeCollisions(count = 3)

        val result = manager.write()

        assertError(getString(Res.string.sftp_error_replace_failed_kept), result)
        assertEquals(3, tempNames.size)
        verify(exactly = 0) { sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) }
        verify(exactly = 0) { sftp.rm(any<String>()) }
        verify(exactly = 0) { sftp.open(any<String>(), any<Set<OpenMode>>()) }
    }

    @Test
    fun `a refused creation of the temporary file that is not about its name is an ordinary upload failure`() = runTest {
        val manager = openedManager()
        arrangeReplacement()
        every { sftp.open(match<String> { isTemp(it) }, any<Set<OpenMode>>(), any<FileAttributes>()) } throws failure()
        // The temporary name is free: the refusal is something else (quota, read-only file system).
        every { sftp.lstat(match<String> { isTemp(it) }) } throws noSuchFile()

        val result = manager.write()

        assertError(getString(Res.string.sftp_error_upload_failed), result)
        verifyOpenedOnlyTheTemporaryFile()
    }

    private fun verifyOpenedOnlyTheTemporaryFile() {
        verify(exactly = 0) { sftp.open(any<String>(), any<Set<OpenMode>>()) }
        verify(exactly = 0) { sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) }
        verify(exactly = 0) { sftp.rm(any<String>()) }
    }

    // ── Failures once the temporary file exists ───────────────────────────────

    @Test
    fun `a server failure during the copy removes the temporary file and keeps the old one`() = runTest {
        val manager = openedManager()
        arrangeReplacement()
        every { tempFile.write(any<Long>(), any<ByteArray>(), any<Int>(), any<Int>()) } throws failure()

        val result = manager.write()

        assertError(getString(Res.string.sftp_error_replace_failed_kept), result)
        verifyOrder {
            tempFile.close()
            sftp.rm(tempNames.single())
        }
        verify(exactly = 0) { sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) }
        verify(exactly = 0) { sftp.open(any<String>(), any<Set<OpenMode>>()) }
    }

    @Test
    fun `a cancellation during the copy removes the temporary file, renames nothing and is rethrown`() = runTest {
        val manager = openedManager()
        arrangeReplacement()
        recordWritesInto(tempFile)
        lateinit var job: Job
        var reads = 0
        val source = object : InputStream() {
            override fun read(): Int = throw UnsupportedOperationException()

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                reads++
                // The second block is read, then the upload is cancelled: the next block never starts.
                if (reads == 2) job.cancel()
                return if (reads <= 2) 10 else -1
            }
        }
        job = launch(Dispatchers.Default, start = CoroutineStart.LAZY) { manager.write(source = source) }

        job.start()
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(2, reads)
        verifyOrder {
            tempFile.close()
            sftp.rm(tempNames.single())
        }
        verify(exactly = 0) { sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) }
    }

    @Test
    fun `a cancellation after the temporary file is closed stops before the rename`() = runTest {
        val manager = openedManager()
        arrangeReplacement()
        lateinit var job: Job
        every { tempFile.close() } answers { job.cancel() }
        job = launch(Dispatchers.Default, start = CoroutineStart.LAZY) { manager.write() }

        job.start()
        job.join()

        assertTrue(job.isCancelled)
        verify(exactly = 1) { sftp.rm(tempNames.single()) }
        verify(exactly = 0) { sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) }
    }

    @Test
    fun `a lost channel during the copy is a lost connection, and neither a close nor a removal is attempted`() = runTest {
        val manager = openedManager()
        arrangeReplacement()
        val lost = listOf(
            // The channel dies: SSHJ gives no status.
            SFTPException("EOF while reading packet"),
            // A server that stays silent: the timeout is the cause.
            SFTPException("Timeout expired", TimeoutException("expired")),
        )

        for (cause in lost) {
            every { tempFile.write(any<Long>(), any<ByteArray>(), any<Int>(), any<Int>()) } throws cause

            val result = manager.write()

            assertError(getString(Res.string.sftp_error_connection_lost), result)
        }
        // A close would wait for the timeout once more, a removal too: nothing is sent on a lost channel.
        verify(exactly = 0) { tempFile.close() }
        verify(exactly = 0) { sftp.rm(any<String>()) }
        verify(exactly = 0) { sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) }
    }

    @Test
    fun `a closed transport during the copy is a lost connection, the removal is tried and its failure is swallowed`() = runTest {
        val manager = openedManager()
        arrangeReplacement()
        every { tempFile.write(any<Long>(), any<ByteArray>(), any<Int>(), any<Int>()) } throws IOException("socket closed")
        every { sftp.rm(any<String>()) } throws IOException("socket closed")

        val lines = captureLogs {
            assertError(getString(Res.string.sftp_error_connection_lost), manager.write())
        }.taggedBy("DesktopSftpManager")

        verify(exactly = 1) { sftp.rm(tempNames.single()) }
        assertTrue(lines.any { it.contains("unfinished file not removed") && it.contains("IOException") }, "got $lines")
    }

    @Test
    fun `a local file that cannot be read removes the temporary file and says so`() = runTest {
        val manager = openedManager()
        arrangeReplacement()
        val broken = object : InputStream() {
            override fun read(): Int = throw IOException("Input/output error")
        }

        val result = manager.write(source = broken)

        assertError(getString(Res.string.sftp_error_local_read_failed), result)
        verify(exactly = 0) { tempFile.write(any<Long>(), any<ByteArray>(), any<Int>(), any<Int>()) }
        verify(exactly = 1) { sftp.rm(tempNames.single()) }
        verify(exactly = 0) { sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) }
    }

    @Test
    fun `a failure to close the temporary file fails the upload, renames nothing and removes the file`() = runTest {
        val manager = openedManager()
        arrangeReplacement()
        // A full disk or a quota may only be reported when the file is closed.
        every { tempFile.close() } throws failure()

        val result = manager.write()

        assertError(getString(Res.string.sftp_error_replace_failed_kept), result)
        verify(exactly = 1) { tempFile.close() }
        verify(exactly = 1) { sftp.rm(tempNames.single()) }
        verify(exactly = 0) { sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) }
    }

    // ── The rename is refused ─────────────────────────────────────────────────

    @Test
    fun `a refused rename onto a target that became a folder is reported as a changed target`() = runTest {
        val manager = openedManager()
        arrangeReplacement()
        // The plan, the look just before the rename, then the look that names the refusal.
        every { sftp.lstat(TARGET) } returns regular() andThen regular() andThen typed(FileMode.Type.DIRECTORY)
        every { sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) } throws failure()

        val result = manager.write()

        assertError(getString(Res.string.sftp_error_upload_target_changed), result)
        verify(exactly = 1) { sftp.rm(tempNames.single()) }
    }

    @Test
    fun `a refused rename onto a target that is still a file keeps the old file and says so`() = runTest {
        val manager = openedManager()
        arrangeReplacement()
        every { sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) } throws failure()

        val result = manager.write()

        assertError(getString(Res.string.sftp_error_replace_failed_kept), result)
        verify(exactly = 1) { sftp.rm(tempNames.single()) }
    }

    @Test
    fun `a rename refused for lack of permission is a permission refusal, the temporary file is removed`() = runTest {
        val manager = openedManager()
        arrangeReplacement()
        every { sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) } throws
            SFTPException(Response.StatusCode.PERMISSION_DENIED, "Permission denied")

        val result = manager.write()

        assertError(getString(Res.string.sftp_error_permission_denied), result)
        verify(exactly = 1) { sftp.rm(tempNames.single()) }
        // The target was read for the plan and just before the rename, and not again to name the refusal.
        verify(exactly = 2) { sftp.lstat(TARGET) }
    }

    @Test
    fun `a channel lost during the rename is a lost connection and the temporary file is left alone`() = runTest {
        val manager = openedManager()
        arrangeReplacement()
        every { sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) } throws SFTPException("EOF while reading packet")

        val result = manager.write()

        assertError(getString(Res.string.sftp_error_connection_lost), result)
        verify(exactly = 0) { sftp.rm(any<String>()) }
    }

    @Test
    fun `a failure to remove the temporary file after a refused rename does not hide that refusal`() = runTest {
        val manager = openedManager()
        arrangeReplacement()
        every { sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) } throws failure()
        every { sftp.rm(any<String>()) } throws failure()

        val result = manager.write()

        assertError(getString(Res.string.sftp_error_replace_failed_kept), result)
        verify(exactly = 1) { sftp.rm(tempNames.single()) }
    }

    // ── What the target is ────────────────────────────────────────────────────

    @Test
    fun `a link to a file replaces the file it points to, in the folder of that file, and leaves the link`() = runTest {
        val manager = openedManager()
        arrangeReplacement(old = link(), target = LINK)
        every { sftp.canonicalize(LINK) } returns REAL
        every { sftp.readlink(LINK) } returns REAL
        every { sftp.lstat(REAL) } returns regular()

        val result = manager.write(path = LINK)

        assertEquals<SshResult<Unit>>(SshResult.Success(Unit), result)
        val temp = tempNames.single()
        assertTrue(Regex("""/srv/app/conf/\.real\.conf\.nextsh-part-[0-9a-f]{8}""").matches(temp), temp)
        verify(exactly = 1) { sftp.rename(temp, REAL, setOf(RenameFlags.OVERWRITE)) }
        verify(exactly = 0) { sftp.rename(any<String>(), LINK, any<Set<RenameFlags>>()) }
        verify(exactly = 0) { sftp.open(LINK, any<Set<OpenMode>>()) }
    }

    @Test
    fun `a link that the server does not resolve gets an in-place write through the link`() = runTest {
        val manager = openedManager()
        arrangeReplacement(old = link(), target = LINK)
        every { sftp.canonicalize(LINK) } returns LINK
        val modes = mutableListOf<Set<OpenMode>>()
        arrangeInPlaceOpen(modes, path = LINK)

        val lines = captureLogs {
            assertEquals<SshResult<Unit>>(SshResult.Success(Unit), manager.write(path = LINK))
        }.taggedBy("DesktopSftpManager")

        assertEquals(listOf(setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)), modes)
        assertTrue(tempNames.isEmpty())
        assertTrue(lines.any { it.contains("reason link-not-resolved") }, "got $lines")
    }

    @Test
    fun `a resolved path that is not valid is not trusted and gets an in-place write through the link`() = runTest {
        val manager = openedManager()
        arrangeReplacement(old = link(), target = LINK)
        every { sftp.canonicalize(LINK) } returns "/../escape"
        val modes = mutableListOf<Set<OpenMode>>()
        arrangeInPlaceOpen(modes, path = LINK)

        assertEquals<SshResult<Unit>>(SshResult.Success(Unit), manager.write(path = LINK))

        assertEquals(listOf(setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)), modes)
        assertTrue(tempNames.isEmpty())
    }

    @Test
    fun `a link that became broken is refused and nothing is opened`() = runTest {
        val manager = openedManager()
        arrangeReplacement(old = link(), target = LINK)

        // The server cannot resolve it.
        every { sftp.canonicalize(LINK) } throws noSuchFile()
        assertError(getString(Res.string.sftp_error_upload_target_changed), manager.write(path = LINK))

        // The server resolves it to a name where nothing is.
        every { sftp.canonicalize(LINK) } returns REAL
        every { sftp.lstat(REAL) } throws noSuchFile()
        assertError(getString(Res.string.sftp_error_upload_target_changed), manager.write(path = LINK))

        verifyNothingOpened()
    }

    @Test
    fun `a link that leads to a folder is refused and nothing is opened`() = runTest {
        val manager = openedManager()
        arrangeReplacement(old = link(), target = LINK)
        every { sftp.canonicalize(LINK) } returns REAL
        every { sftp.lstat(REAL) } returns typed(FileMode.Type.DIRECTORY)

        assertError(getString(Res.string.sftp_error_upload_target_changed), manager.write(path = LINK))

        verifyNothingOpened()
    }

    @Test
    fun `a target that is no longer a file is refused and nothing is opened`() = runTest {
        val manager = openedManager()
        arrangeReplacement()
        val others = listOf(
            FileMode.Type.DIRECTORY,
            FileMode.Type.FIFO_SPECIAL,
            FileMode.Type.SOCKET_SPECIAL,
            FileMode.Type.BLOCK_SPECIAL,
            FileMode.Type.CHAR_SPECIAL,
        )

        for (type in others) {
            every { sftp.lstat(TARGET) } returns typed(type)

            assertError(getString(Res.string.sftp_error_upload_target_changed), manager.write())
        }
        verifyNothingOpened()
    }

    @Test
    fun `a target that disappeared is created with EXCL, without a temporary file nor a look at the extensions`() = runTest {
        val manager = openedManager()
        every { sftp.lstat(TARGET) } throws noSuchFile()
        val modes = mutableListOf<Set<OpenMode>>()
        arrangeInPlaceOpen(modes)
        val written = recordWritesInto(targetFile)
        val data = ByteArray(1_000) { it.toByte() }

        val result = manager.write(data = data)

        assertEquals<SshResult<Unit>>(SshResult.Success(Unit), result)
        assertEquals(listOf(setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL)), modes)
        assertContentEquals(data, written.toByteArray())
        verify(exactly = 0) { sftp.sftpEngine }
        verify(exactly = 0) { sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) }
    }

    @Test
    fun `a name that came back between the look and the open is refused by EXCL as a taken name`() = runTest {
        val manager = openedManager()
        every { sftp.lstat(TARGET) } throws noSuchFile()
        every { sftp.open(TARGET, any<Set<OpenMode>>()) } throws SFTPException(Response.StatusCode.FILE_ALREADY_EXISTS, "File exists")

        val result = manager.write()

        assertError(getString(Res.string.sftp_error_target_exists), result)
    }

    @Test
    fun `a server without lstat cannot tell a link from its file, so what exists is written in place and never renamed over`() = runTest {
        val manager = openedManager()
        arrangeReplacement()
        every { sftp.lstat(TARGET) } throws SFTPException(Response.StatusCode.OP_UNSUPPORTED, "Unsupported")
        // The stat follows links: a link to a file reads as that file, and a rename would replace the link.
        every { sftp.statExistence(TARGET) } returns regular()
        val modes = mutableListOf<Set<OpenMode>>()
        arrangeInPlaceOpen(modes)

        val lines = captureLogs {
            assertEquals<SshResult<Unit>>(SshResult.Success(Unit), manager.write())
        }.taggedBy("DesktopSftpManager")

        assertEquals(listOf(setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)), modes)
        assertTrue(tempNames.isEmpty())
        verify(exactly = 0) { sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) }
        verify(exactly = 0) { sftp.sftpEngine }
        assertTrue(lines.any { it.contains("reason link-not-resolved") }, "got $lines")
    }

    @Test
    fun `a server without lstat and nothing at the name creates the file with EXCL`() = runTest {
        val manager = openedManager()
        every { sftp.lstat(TARGET) } throws SFTPException(Response.StatusCode.OP_UNSUPPORTED, "Unsupported")
        every { sftp.statExistence(TARGET) } returns null
        val modes = mutableListOf<Set<OpenMode>>()
        arrangeInPlaceOpen(modes)

        assertEquals<SshResult<Unit>>(SshResult.Success(Unit), manager.write())

        assertEquals(listOf(setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL)), modes)
        verify(exactly = 0) { sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) }
    }

    @Test
    fun `a server without lstat refuses a folder, a FIFO, a socket, a device and a link instead of opening them`() = runTest {
        val manager = openedManager()
        every { sftp.lstat(TARGET) } throws SFTPException(Response.StatusCode.OP_UNSUPPORTED, "Unsupported")
        val others = listOf(
            FileMode.Type.DIRECTORY,
            FileMode.Type.FIFO_SPECIAL,
            FileMode.Type.SOCKET_SPECIAL,
            FileMode.Type.BLOCK_SPECIAL,
            FileMode.Type.CHAR_SPECIAL,
            // A stat that follows links should never show one: a faulty server, nothing can be said of it.
            FileMode.Type.SYMLINK,
        )

        for (type in others) {
            // The stat follows links: what it shows is the entry, or the thing a link leads to.
            every { sftp.statExistence(TARGET) } returns typed(type)

            assertError(getString(Res.string.sftp_error_upload_target_changed), manager.write())
        }
        // An in-place open of a FIFO that nobody reads would block the server.
        verifyNothingOpened()
    }

    @Test
    fun `a lost channel on the read of the owner of the temporary file sends nothing more`() = runTest {
        val manager = openedManager()
        arrangeReplacement()
        every { tempFile.fetchAttributes() } throws SFTPException("EOF while reading packet")

        assertError(getString(Res.string.sftp_error_connection_lost), manager.write())

        // A close or a removal would wait for the timeout again.
        verify(exactly = 0) { tempFile.close() }
        verify(exactly = 0) { sftp.rm(any<String>()) }
        verify(exactly = 0) { sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) }
        verify(exactly = 0) { sftp.open(TARGET, any<Set<OpenMode>>()) }
    }

    // ── A link of somebody else is not followed ───────────────────────────────

    @Test
    fun `a link laid by somebody else is not followed, the upload goes through the link in place and nothing is renamed`() = runTest {
        val manager = openedManager()
        // The link belongs to 2000, the file it leads to to 1000, who is the connected user: the temporary
        // file he creates is his, and shows that the link is not.
        arrangeReplacement(
            old = link(uid = 2000),
            target = LINK,
            created = regular(permissions = 0b110_000_000, uid = 1000, gid = 1000),
        )
        every { sftp.canonicalize(LINK) } returns REAL
        every { sftp.readlink(LINK) } returns REAL
        every { sftp.lstat(REAL) } returns regular(uid = 1000, gid = 1000)
        val modes = mutableListOf<Set<OpenMode>>()
        arrangeInPlaceOpen(modes, path = LINK)

        val lines = captureLogs {
            assertEquals<SshResult<Unit>>(SshResult.Success(Unit), manager.write(path = LINK))
        }.taggedBy("DesktopSftpManager")

        assertEquals(listOf(setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)), modes)
        verify(exactly = 0) { sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) }
        verify(exactly = 0) { sftp.open(REAL, any<Set<OpenMode>>()) }
        verify(exactly = 0) { tempFile.write(any<Long>(), any<ByteArray>(), any<Int>(), any<Int>()) }
        // The empty temporary file that told the owner is closed and removed before the open through the link.
        verifyOrder {
            tempFile.close()
            sftp.rm(tempNames.single())
            sftp.open(LINK, any<Set<OpenMode>>())
        }
        assertTrue(lines.any { it.contains("reason link-not-resolved") }, "got $lines")
    }

    @Test
    fun `a link whose owner the server does not state is not followed, and not even resolved`() = runTest {
        val manager = openedManager()
        arrangeReplacement(old = typed(FileMode.Type.SYMLINK), target = LINK)
        val modes = mutableListOf<Set<OpenMode>>()
        arrangeInPlaceOpen(modes, path = LINK)

        val lines = captureLogs {
            assertEquals<SshResult<Unit>>(SshResult.Success(Unit), manager.write(path = LINK))
        }.taggedBy("DesktopSftpManager")

        assertEquals(listOf(setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)), modes)
        assertTrue(tempNames.isEmpty())
        verify(exactly = 0) { sftp.canonicalize(any<String>()) }
        verify(exactly = 0) { sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) }
        assertTrue(lines.any { it.contains("reason link-not-resolved") }, "got $lines")
    }

    @Test
    fun `a link of the user himself is followed`() = runTest {
        val manager = openedManager()
        arrangeReplacement(old = link(uid = 1000), target = LINK)
        every { sftp.canonicalize(LINK) } returns REAL
        every { sftp.readlink(LINK) } returns REAL
        every { sftp.lstat(REAL) } returns regular()

        assertEquals<SshResult<Unit>>(SshResult.Success(Unit), manager.write(path = LINK))

        verify(exactly = 1) { sftp.rename(tempNames.single(), REAL, setOf(RenameFlags.OVERWRITE)) }
    }

    @Test
    fun `a link in a loop, which the server cannot resolve, is written in place and the open decides`() = runTest {
        val manager = openedManager()
        arrangeReplacement(old = link(), target = LINK)
        val modes = mutableListOf<Set<OpenMode>>()
        arrangeInPlaceOpen(modes, path = LINK)
        // ELOOP is a plain failure on OpenSSH; a refusal is not an answer about the file either.
        val refusals = listOf(
            failure(),
            SFTPException(Response.StatusCode.PERMISSION_DENIED, "Permission denied"),
        )

        val lines = captureLogs {
            for (refusal in refusals) {
                every { sftp.canonicalize(LINK) } throws refusal

                assertEquals<SshResult<Unit>>(SshResult.Success(Unit), manager.write(path = LINK))
            }
        }.taggedBy("DesktopSftpManager")

        assertEquals(2, modes.size)
        assertTrue(modes.all { it == setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC) })
        assertTrue(tempNames.isEmpty())
        verify(exactly = 0) { sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) }
        assertEquals(2, lines.count { it.contains("reason link-not-resolved") }, "got $lines")
    }

    @Test
    fun `a link in a loop whose open is refused by the server is told as the open says`() = runTest {
        val manager = openedManager()
        arrangeReplacement(old = link(), target = LINK)
        every { sftp.canonicalize(LINK) } throws failure()
        every { sftp.open(LINK, any<Set<OpenMode>>()) } throws failure()

        val result = manager.write(path = LINK)

        // The open of the link gives ELOOP, which OpenSSH tells as a plain failure.
        assertError(getString(Res.string.sftp_error_upload_failed), result)
        verify(exactly = 0) { sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) }
    }

    @Test
    fun `a canonicalize on a lost channel is a lost connection and nothing is opened`() = runTest {
        val manager = openedManager()
        arrangeReplacement(old = link(), target = LINK)
        every { sftp.canonicalize(LINK) } throws SFTPException("EOF while reading packet")

        assertError(getString(Res.string.sftp_error_connection_lost), manager.write(path = LINK))

        verifyNothingOpened()
    }

    @Test
    fun `a link to a file in a folder that cannot be written gets an in-place write through the link`() = runTest {
        val manager = openedManager()
        arrangeReplacement(old = link(), target = LINK)
        every { sftp.canonicalize(LINK) } returns REAL
        every { sftp.readlink(LINK) } returns REAL
        every { sftp.lstat(REAL) } returns regular()
        every { sftp.open(match<String> { isTemp(it) }, any<Set<OpenMode>>(), any<FileAttributes>()) } throws
            SFTPException(Response.StatusCode.PERMISSION_DENIED, "Permission denied")
        val modes = mutableListOf<Set<OpenMode>>()
        arrangeInPlaceOpen(modes, path = LINK)

        val lines = captureLogs {
            assertEquals<SshResult<Unit>>(SshResult.Success(Unit), manager.write(path = LINK))
        }.taggedBy("DesktopSftpManager")

        assertEquals(listOf(setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)), modes)
        verify(exactly = 0) { sftp.open(REAL, any<Set<OpenMode>>()) }
        verify(exactly = 0) { sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) }
        verify(exactly = 0) { sftp.rm(any<String>()) }
        assertTrue(lines.any { it.contains("reason dir-not-writable") }, "got $lines")
    }

    // ── A link is only followed if it leads DIRECTLY to the file ──────────────

    /**
     * A link of the user at [path]: the server resolves it to [canonical], and `readlink` says [written].
     * What [canonical] designates is a plain file of the user.
     */
    private fun arrangeLinkTo(path: String, canonical: String, written: String) {
        arrangeReplacement(old = link(), target = path)
        every { sftp.canonicalize(path) } returns canonical
        every { sftp.readlink(path) } returns written
        every { sftp.lstat(canonical) } returns regular()
    }

    @Test
    fun `a chain of two links is not followed, the upload goes through the link in place`() = runTest {
        val manager = openedManager()
        // out -> /tmp/rapport.txt, which somebody else turned into a link to the file of the user: the server
        // resolves the whole chain, the kernel would refuse the second hop for an in-place write.
        arrangeLinkTo(LINK, canonical = "/home/t/.bashrc", written = "/tmp/rapport.txt")
        val modes = mutableListOf<Set<OpenMode>>()
        arrangeInPlaceOpen(modes, path = LINK)

        val lines = captureLogs {
            assertEquals<SshResult<Unit>>(SshResult.Success(Unit), manager.write(path = LINK))
        }.taggedBy("DesktopSftpManager")

        assertEquals(listOf(setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)), modes)
        assertTrue(tempNames.isEmpty())
        verify(exactly = 0) { sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) }
        verify(exactly = 0) { sftp.open(match<String> { it.endsWith(".bashrc") }, any<Set<OpenMode>>()) }
        assertTrue(lines.any { it.contains("reason link-not-resolved") }, "got $lines")
    }

    @Test
    fun `a link to a file in a folder reached through a link of a folder is not followed`() = runTest {
        val manager = openedManager()
        // /home/t/dirlink is a link to /srv/app/conf: the link says real.conf, the server resolves to the other folder.
        val path = "/home/t/dirlink/current.conf"
        arrangeLinkTo(path, canonical = REAL, written = "real.conf")
        val modes = mutableListOf<Set<OpenMode>>()
        arrangeInPlaceOpen(modes, path = path)

        assertEquals<SshResult<Unit>>(SshResult.Success(Unit), manager.write(path = path))

        assertEquals(listOf(setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)), modes)
        assertTrue(tempNames.isEmpty())
        verify(exactly = 0) { sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) }
    }

    @Test
    fun `a simple link of the user, relative to its folder, is followed`() = runTest {
        val manager = openedManager()
        arrangeLinkTo(LINK, canonical = "/home/t/real.conf", written = "real.conf")

        assertEquals<SshResult<Unit>>(SshResult.Success(Unit), manager.write(path = LINK))

        val temp = tempNames.single()
        assertTrue(Regex("""/home/t/\.real\.conf\.nextsh-part-[0-9a-f]{8}""").matches(temp), temp)
        verify(exactly = 1) { sftp.rename(temp, "/home/t/real.conf", setOf(RenameFlags.OVERWRITE)) }
    }

    @Test
    fun `a link that goes up a folder is not followed even when the text and the server agree on the file`() = runTest {
        val manager = openedManager()
        // /home/t/conf is a link to /tmp/c, where out is a link of the user to ../x. Somebody else made /tmp/x a
        // link to /home/t/x. The server resolves /home/t/conf/out to /home/t/x, and the text /home/t/conf/../x is
        // /home/t/x too: they agree, but on the disk ../x is /tmp/x, which the kernel would refuse in place.
        val path = "/home/t/conf/out"
        arrangeLinkTo(path, canonical = "/home/t/x", written = "../x")
        val modes = mutableListOf<Set<OpenMode>>()
        arrangeInPlaceOpen(modes, path = path)

        assertEquals<SshResult<Unit>>(SshResult.Success(Unit), manager.write(path = path))

        assertEquals(listOf(setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)), modes)
        assertTrue(tempNames.isEmpty())
        verify(exactly = 0) { sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) }
        verify(exactly = 0) { sftp.open(match<String> { it.endsWith("/x") }, any<Set<OpenMode>>()) }
    }

    @Test
    fun `an absolute target that has a dot dot segment is not followed either`() = runTest {
        val manager = openedManager()
        // The text of /srv/app/../app/conf/real.conf is REAL, the same as the server's answer.
        arrangeLinkTo(LINK, canonical = REAL, written = "/srv/app/../app/conf/real.conf")
        val modes = mutableListOf<Set<OpenMode>>()
        arrangeInPlaceOpen(modes, path = LINK)

        assertEquals<SshResult<Unit>>(SshResult.Success(Unit), manager.write(path = LINK))

        assertEquals(listOf(setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)), modes)
        assertTrue(tempNames.isEmpty())
        verify(exactly = 0) { sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) }
    }

    @Test
    fun `a name that contains two dots without being a dot dot segment is followed`() = runTest {
        val manager = openedManager()
        arrangeLinkTo(LINK, canonical = "/home/t/real..conf", written = "real..conf")

        assertEquals<SshResult<Unit>>(SshResult.Success(Unit), manager.write(path = LINK))

        val temp = tempNames.single()
        assertTrue(Regex("""/home/t/\.real\.\.conf\.nextsh-part-[0-9a-f]{8}""").matches(temp), temp)
        verify(exactly = 1) { sftp.rename(temp, "/home/t/real..conf", setOf(RenameFlags.OVERWRITE)) }
    }

    @Test
    fun `a relative link that goes up one folder is not followed when the folder is a link`() = runTest {
        val manager = openedManager()
        // /home/t/conf is a link to /srv/c: ../x written in it is /srv/x for the server, /home/t/x as written.
        val path = "/home/t/conf/out"
        arrangeLinkTo(path, canonical = "/srv/x", written = "../x")
        val modes = mutableListOf<Set<OpenMode>>()
        arrangeInPlaceOpen(modes, path = path)

        assertEquals<SshResult<Unit>>(SshResult.Success(Unit), manager.write(path = path))

        assertEquals(listOf(setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)), modes)
        verify(exactly = 0) { sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) }
    }

    @Test
    fun `a readlink that is refused or not supported leaves the upload in place`() = runTest {
        val manager = openedManager()
        arrangeLinkTo(LINK, canonical = REAL, written = REAL)
        val modes = mutableListOf<Set<OpenMode>>()
        arrangeInPlaceOpen(modes, path = LINK)
        val refusals = listOf(
            SFTPException(Response.StatusCode.OP_UNSUPPORTED, "Unsupported"),
            SFTPException(Response.StatusCode.PERMISSION_DENIED, "Permission denied"),
            SFTPException(Response.StatusCode.FAILURE, "Failure"),
        )

        for (refusal in refusals) {
            every { sftp.readlink(LINK) } throws refusal

            assertEquals<SshResult<Unit>>(SshResult.Success(Unit), manager.write(path = LINK))
        }

        assertEquals(3, modes.size)
        assertTrue(modes.all { it == setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC) })
        assertTrue(tempNames.isEmpty())
        verify(exactly = 0) { sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) }
    }

    @Test
    fun `a readlink on a lost channel is a lost connection and nothing is opened`() = runTest {
        val manager = openedManager()
        arrangeLinkTo(LINK, canonical = REAL, written = REAL)
        every { sftp.readlink(LINK) } throws SFTPException("EOF while reading packet")

        assertError(getString(Res.string.sftp_error_connection_lost), manager.write(path = LINK))

        verifyNothingOpened()
    }

    // ── The cancellation is looked at before an in-place open ─────────────────

    @Test
    fun `a cancellation while the target is examined sends no open, which would empty the file`() = runTest {
        val manager = openedManager()
        // A server without posix-rename: the upload goes in place, with TRUNC.
        arrangeReplacement(extension = false)
        arrangeInPlaceOpen(mutableListOf())
        lateinit var job: Job
        every { sftp.lstat(TARGET) } answers {
            job.cancel()
            regular()
        }
        job = launch(Dispatchers.Default, start = CoroutineStart.LAZY) { manager.write() }

        job.start()
        job.join()

        assertTrue(job.isCancelled)
        verifyNothingOpened()
    }

    @Test
    fun `a cancellation while the temporary file of a fallback is removed sends no in-place open`() = runTest {
        val manager = openedManager()
        // The owner cannot be given back: the temporary file is removed, then the upload goes in place.
        arrangeReplacement(old = regular(uid = 0, gid = 0), created = regular(permissions = 0b110_000_000, uid = 1000, gid = 1000))
        every { tempFile.setAttributes(any()) } throws SFTPException(Response.StatusCode.PERMISSION_DENIED, "Permission denied")
        arrangeInPlaceOpen(mutableListOf())
        lateinit var job: Job
        every { sftp.rm(any<String>()) } answers { job.cancel() }
        job = launch(Dispatchers.Default, start = CoroutineStart.LAZY) { manager.write() }

        job.start()
        job.join()

        assertTrue(job.isCancelled)
        verify(exactly = 1) { sftp.rm(tempNames.single()) }
        verify(exactly = 0) { sftp.open(TARGET, any<Set<OpenMode>>()) }
        verify(exactly = 0) { sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) }
    }

    @Test
    fun `a cancellation right after the rename removes nothing`() = runTest {
        val manager = openedManager()
        arrangeReplacement()
        lateinit var job: Job
        every { sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) } answers { job.cancel() }
        job = launch(Dispatchers.Default, start = CoroutineStart.LAZY) { manager.write() }

        job.start()
        job.join()

        // The replacement happened: the temporary file is the target now, and the target is not removed.
        verify(exactly = 1) { sftp.rename(tempNames.single(), TARGET, setOf(RenameFlags.OVERWRITE)) }
        verify(exactly = 0) { sftp.rm(any<String>()) }
    }

    // ── An in-place write reads the source before it opens ────────────────────

    /** A source whose [read] is the given one: the first block is what the in-place write asks for first. */
    private fun sourceReading(onRead: (ByteArray, Int, Int) -> Int) = object : InputStream() {
        override fun read(): Int = throw UnsupportedOperationException()

        override fun read(b: ByteArray, off: Int, len: Int): Int = onRead(b, off, len)
    }

    @Test
    fun `a source that cannot be read at its first block leaves the old file untouched and nothing is opened`() = runTest {
        val manager = openedManager()
        arrangeReplacement(extension = false)
        arrangeInPlaceOpen(mutableListOf())
        val broken = object : InputStream() {
            override fun read(): Int = throw IOException("Input/output error")
        }

        val result = manager.write(source = broken)

        assertError(getString(Res.string.sftp_error_local_read_failed), result)
        verifyNothingOpened()
    }

    @Test
    fun `the first block of the source is read before the open in place`() = runTest {
        val manager = openedManager()
        arrangeReplacement(extension = false)
        var read = false
        every { sftp.open(TARGET, any<Set<OpenMode>>()) } answers {
            assertTrue(read, "the target was opened before the source was read")
            targetFile
        }
        val source = sourceReading { _, _, _ -> read = true; -1 }

        assertEquals<SshResult<Unit>>(SshResult.Success(Unit), manager.write(source = source))

        verify(exactly = 1) { sftp.open(TARGET, any<Set<OpenMode>>()) }
    }

    @Test
    fun `an empty source still empties the file in place and writes nothing`() = runTest {
        val manager = openedManager()
        arrangeReplacement(extension = false)
        val modes = mutableListOf<Set<OpenMode>>()
        arrangeInPlaceOpen(modes)

        assertEquals<SshResult<Unit>>(SshResult.Success(Unit), manager.write(data = ByteArray(0)))

        assertEquals(listOf(setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)), modes)
        verify(exactly = 0) { targetFile.write(any<Long>(), any<ByteArray>(), any<Int>(), any<Int>()) }
        verify(exactly = 1) { targetFile.close() }
    }

    @Test
    fun `a cancellation while the first block is read sends no open, which would empty the file`() = runTest {
        val manager = openedManager()
        arrangeReplacement(extension = false)
        arrangeInPlaceOpen(mutableListOf())
        lateinit var job: Job
        val source = sourceReading { _, _, len -> job.cancel(); len }
        job = launch(Dispatchers.Default, start = CoroutineStart.LAZY) { manager.write(source = source) }

        job.start()
        job.join()

        assertTrue(job.isCancelled)
        verifyNothingOpened()
    }

    @Test
    fun `the whole source is written in place at contiguous offsets, first block included`() = runTest {
        val manager = openedManager()
        arrangeReplacement(extension = false)
        arrangeInPlaceOpen(mutableListOf())
        val offsets = mutableListOf<Long>()
        val sink = ByteArrayOutputStream()
        every { targetFile.write(any<Long>(), any<ByteArray>(), any<Int>(), any<Int>()) } answers {
            offsets += firstArg<Long>()
            sink.write(secondArg<ByteArray>(), thirdArg<Int>(), arg<Int>(3))
        }
        val data = ByteArray(70_000) { (it * 7).toByte() }
        val progress = mutableListOf<Long>()

        assertEquals<SshResult<Unit>>(
            SshResult.Success(Unit),
            manager.write(data = data, onProgress = { done, _ -> progress += done }),
        )

        assertEquals(listOf(0L, 32768L, 65536L), offsets)
        assertContentEquals(data, sink.toByteArray())
        assertEquals(listOf(32768L, 65536L, 70_000L), progress)
    }

    @Test
    fun `a cancellation during the open in place still writes the first block that was read`() = runTest {
        val manager = openedManager()
        arrangeReplacement(extension = false)
        lateinit var job: Job
        every { sftp.open(TARGET, any<Set<OpenMode>>()) } answers {
            job.cancel()
            targetFile
        }
        val written = recordWritesInto(targetFile)
        val data = ByteArray(100) { it.toByte() }
        job = launch(Dispatchers.Default, start = CoroutineStart.LAZY) { manager.write(data = data) }

        job.start()
        job.join()

        assertTrue(job.isCancelled)
        verify(exactly = 1) { targetFile.write(0L, any<ByteArray>(), 0, 100) }
        assertContentEquals(data, written.toByteArray())
        verify(exactly = 0) { sftp.rm(any<String>()) }
    }

    @Test
    fun `a fallback from the temporary file writes the whole source in place, as the stream was not started`() = runTest {
        val manager = openedManager()
        arrangeReplacement(old = regular(uid = 0, gid = 0), created = regular(permissions = 0b110_000_000, uid = 1000, gid = 1000))
        every { tempFile.setAttributes(any()) } throws SFTPException(Response.StatusCode.PERMISSION_DENIED, "Permission denied")
        arrangeInPlaceOpen(mutableListOf())
        val written = recordWritesInto(targetFile)
        val data = ByteArray(70_000) { (it % 251).toByte() }

        assertEquals<SshResult<Unit>>(SshResult.Success(Unit), manager.write(data = data))

        assertContentEquals(data, written.toByteArray())
        verify(exactly = 0) { tempFile.write(any<Long>(), any<ByteArray>(), any<Int>(), any<Int>()) }
    }

    // ── The target is read again before the rename ────────────────────────────

    /** The first look at [TARGET] (the plan) sees [first], the next ones (just before the rename...) see [later]. */
    private fun arrangeTargetSeenLater(first: FileAttributes, later: () -> FileAttributes) {
        var looks = 0
        every { sftp.lstat(TARGET) } answers { if (++looks == 1) first else later() }
    }

    @Test
    fun `a target that did not change during the copy is replaced, size and modification time compared`() = runTest {
        val manager = openedManager()
        arrangeReplacement()
        val judged = regular(size = 100, mtime = 1_000)
        arrangeTargetSeenLater(judged) { regular(size = 100, mtime = 1_000) }

        assertEquals<SshResult<Unit>>(SshResult.Success(Unit), manager.write())

        verify(exactly = 1) { sftp.rename(tempNames.single(), TARGET, setOf(RenameFlags.OVERWRITE)) }
    }

    @Test
    fun `a target that changed during the copy is not overwritten, the temporary file is removed and the change is told`() = runTest {
        val manager = openedManager()
        arrangeReplacement()
        val judged = regular(size = 100, mtime = 1_000)
        val changes = mapOf<String, () -> FileAttributes>(
            // somebody else replaced the file by his own
            "owner" to { regular(uid = 2000, gid = 2000, size = 100, mtime = 1_000) },
            "group" to { regular(gid = 2000, size = 100, mtime = 1_000) },
            "size" to { regular(size = 101, mtime = 1_000) },
            "mtime" to { regular(size = 100, mtime = 1_001) },
            "permissions" to { regular(permissions = 0b100_100_100, size = 100, mtime = 1_000) },
            // a value the plan saw is no longer stated
            "size no longer stated" to { regular(mtime = 1_000) },
            "type" to { link() },
            "gone" to { throw noSuchFile() },
        )

        for ((name, change) in changes) {
            arrangeTargetSeenLater(judged, change)

            assertError(getString(Res.string.sftp_error_upload_target_changed), manager.write())

            verify(exactly = 1) { sftp.rm(tempNames.last()) }
            assertTrue(tempNames.isNotEmpty(), name)
        }
        verify(exactly = 0) { sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) }
        assertEquals(changes.size, tempNames.size)
    }

    @Test
    fun `a failure to read the target again before the rename keeps the old file and renames nothing`() = runTest {
        val manager = openedManager()
        arrangeReplacement()
        arrangeTargetSeenLater(regular()) { throw failure() }

        val result = manager.write()

        assertError(getString(Res.string.sftp_error_replace_failed_kept), result)
        verify(exactly = 1) { sftp.rm(tempNames.single()) }
        verify(exactly = 0) { sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) }
    }

    @Test
    fun `a look at the target that fails is told as it always was and nothing is opened`() = runTest {
        val manager = openedManager()
        arrangeReplacement()

        every { sftp.lstat(TARGET) } throws SFTPException(Response.StatusCode.PERMISSION_DENIED, "Permission denied")
        assertError(getString(Res.string.sftp_error_permission_denied), manager.write())

        every { sftp.lstat(TARGET) } throws IOException("socket closed")
        assertError(getString(Res.string.sftp_error_connection_lost), manager.write())

        every { sftp.lstat(TARGET) } throws SFTPException("EOF while reading packet")
        assertError(getString(Res.string.sftp_error_connection_lost), manager.write())

        verifyNothingOpened()
    }

    // ── An upload that replaces nothing is not touched ────────────────────────

    @Test
    fun `an upload without replacement makes no look at the target and no look at the extensions`() = runTest {
        val manager = openedManager()
        every { sftp.open(TARGET, any<Set<OpenMode>>()) } returns targetFile

        val result = manager.writeFile(SESSION, TARGET, ByteArrayInputStream(ByteArray(10)), 10L, false) { _, _ -> }

        assertEquals<SshResult<Unit>>(SshResult.Success(Unit), result)
        verify(exactly = 0) { sftp.sftpEngine }
        verify(exactly = 0) { sftp.lstat(any<String>()) }
        verify(exactly = 0) { sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) }
    }

    // ── entryKind is unchanged ────────────────────────────────────────────────

    @Test
    fun `a link to a file still reads as a file for the question asked before the upload`() = runTest {
        val manager = openedManager()
        // The stat follows the link: what it answers is the file.
        every { sftp.statExistence(LINK) } returns typed(FileMode.Type.REGULAR)

        val result = manager.entryKind(SESSION, LINK)

        assertEquals<SshResult<RemoteEntryKind>>(SshResult.Success(RemoteEntryKind.FILE), result)
        verify(exactly = 0) { sftp.lstat(any<String>()) }
    }

    // ── The name of the temporary file ────────────────────────────────────────

    @Test
    fun `the temporary path is a hidden name in the folder of the target`() {
        assertEquals("/home/t/.a.bin.nextsh-part-0123abcd", DesktopSftpManager.uploadTempPath("/home/t", "a.bin", "0123abcd"))
        assertEquals("/home/t/.a.bin.nextsh-part-0123abcd", DesktopSftpManager.uploadTempPath("/home/t/", "a.bin", "0123abcd"))
    }

    @Test
    fun `the temporary path at the root has no double slash`() {
        assertEquals("/.a.bin.nextsh-part-0123abcd", DesktopSftpManager.uploadTempPath("/", "a.bin", "0123abcd"))
        assertEquals("/.a.bin.nextsh-part-0123abcd", DesktopSftpManager.uploadTempPath("", "a.bin", "0123abcd"))
    }

    @Test
    fun `a name that starts with a dot gives a valid name that is not dot dot`() {
        assertEquals("/home/t/..env.nextsh-part-0123abcd", DesktopSftpManager.uploadTempPath("/home/t", ".env", "0123abcd"))
    }

    @Test
    fun `a long name is cut between two code points and the whole name fits in 255 bytes`() {
        val names = listOf(
            "a".repeat(255),
            // Three-byte characters: 765 bytes.
            "あ".repeat(255),
            // Four-byte characters (surrogate pairs): 1020 bytes.
            "😀".repeat(255),
            // Two-byte characters, then one of four so that the cut falls awkwardly.
            "é".repeat(59) + "😀".repeat(10),
        )

        for (name in names) {
            val path = DesktopSftpManager.uploadTempPath("/home/t", name, "0123abcd")

            val last = path.substringAfterLast('/')
            val bytes = last.toByteArray(Charsets.UTF_8)
            assertTrue(bytes.size <= 255, "${bytes.size} bytes for a name of ${name.length} chars")
            // A cut inside a surrogate pair would not survive the round trip through UTF-8.
            assertEquals(last, String(bytes, Charsets.UTF_8), "a lone surrogate in the temporary name")
            assertTrue(last.startsWith(".") && last.endsWith(".nextsh-part-0123abcd"), last)
        }
    }

    // ── Logs ──────────────────────────────────────────────────────────────────

    @Test
    fun `the log lines of a replacement carry no path, no file name and no temporary name`() = runTest {
        val manager = openedManager()
        val results = mutableListOf<SshResult<Unit>>()

        val lines = captureLogs {
            // 1. a fallback in place (no extension)
            arrangeReplacement(extension = false)
            arrangeInPlaceOpen(mutableListOf())
            results += manager.write()
            // 2. a permission copy refused
            arrangeReplacement()
            every { tempFile.setAttributes(any()) } throws SFTPException(Response.StatusCode.PERMISSION_DENIED, "Permission denied /home/t/secret.bin")
            results += manager.write()
            // 3. a removal that fails
            every { tempFile.write(any<Long>(), any<ByteArray>(), any<Int>(), any<Int>()) } throws failure()
            every { sftp.rm(any<String>()) } throws SFTPException(Response.StatusCode.FAILURE, "cannot remove /home/t/.a.bin")
            results += manager.write()
        }.taggedBy("DesktopSftpManager")

        assertEquals(3, results.size)
        assertTrue(lines.size >= 3, "got $lines")
        assertTrue(lines.all { it.contains(LogId.of(SESSION)) }, "got $lines")
        for (forbidden in listOf("a.bin", "/home", "secret", "nextsh-part", "Permission denied", "cannot remove")) {
            assertTrue(lines.none { it.contains(forbidden) }, "'$forbidden' in $lines")
        }
    }

    @Test
    fun `the messages of a failed replacement are two distinct texts that name no file`() = runTest {
        val kept = getString(Res.string.sftp_error_replace_failed_kept)
        val changed = getString(Res.string.sftp_error_upload_target_changed)

        assertTrue(kept.isNotBlank() && changed.isNotBlank())
        assertFalse(kept == changed)
        assertFalse(kept.contains("%") || changed.contains("%"))
    }

    private companion object {
        const val SESSION = "s1"
        const val TARGET = "/home/t/a.bin"
        const val LINK = "/home/t/current.conf"
        const val REAL = "/srv/app/conf/real.conf"
    }
}
