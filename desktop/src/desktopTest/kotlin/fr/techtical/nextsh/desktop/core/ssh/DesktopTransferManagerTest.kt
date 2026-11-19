// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.ssh

import fr.techtical.nextsh.desktop.captureLogs
import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_local_exists
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_local_read_failed
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_local_write_failed
import fr.techtical.nextsh.desktop.generated.resources.transfers_failed_default
import fr.techtical.nextsh.desktop.taggedBy
import fr.techtical.nextsh.shared.domain.model.SshErrorCode
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.shared.domain.model.TransferDirection
import fr.techtical.nextsh.shared.domain.model.TransferState
import fr.techtical.nextsh.shared.util.AppScope
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.compose.resources.getString
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [DesktopTransferManager] over a mocked [DesktopSftpManager] and a real temporary folder:
 * what is asked of the server and of the local disk when a transfer may or may not replace
 * an existing file, and the messages a failed transfer ends with.
 *
 * Real time on purpose: transfers run in the app scope, so the tests wait on the transfers
 * flow instead of advancing a virtual clock. Messages are compared with the resources, never
 * with a literal: the text depends on the machine language.
 */
class DesktopTransferManagerTest {

    private class TestAppScope(override val coroutineScope: CoroutineScope) : AppScope {
        override fun onDestroy() = coroutineScope.cancel()
    }

    /**
     * What reached the scope uncaught (an [Error] thrown by a transfer, on purpose in one test).
     * Without this handler it would go to the global handler, where the coroutine test library
     * picks it up and fails the next `runTest` of the run, in another class.
     */
    private val uncaught = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
    private val app = TestAppScope(
        CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> uncaught += e }),
    )
    private val sftpManager = mockk<DesktopSftpManager>()
    private val manager = DesktopTransferManager(sftpManager, app)
    private val tempDir: File = Files.createTempDirectory("nextsh-transfer-test").toFile()

    @AfterTest
    fun tearDown() {
        app.onDestroy()
        tempDir.deleteRecursively()
    }

    private fun isTerminal(state: TransferState?): Boolean =
        state is TransferState.Completed || state is TransferState.Failed || state is TransferState.Cancelled

    /** The terminal state the transfer [id] ends with. */
    private suspend fun awaitTerminal(id: String): TransferState =
        withTimeout(5_000L) { manager.transfers.first { isTerminal(it[id]) }.getValue(id) }

    private suspend fun awaitFailure(id: String): String = assertIs<TransferState.Failed>(awaitTerminal(id)).error

    private fun localFile(name: String, content: String? = null): File =
        File(tempDir, name).also { if (content != null) it.writeText(content) }

    private fun stubUploadSucceeds() {
        coEvery { sftpManager.writeFile(any(), any(), any(), any(), any(), any()) } returns SshResult.Success(Unit)
    }

    /** Makes the mocked download write [content] to the stream it is given. */
    private fun stubDownloadWrites(content: String) {
        coEvery { sftpManager.readFile(any(), any(), any(), any()) } coAnswers {
            thirdArg<OutputStream>().write(content.toByteArray())
            SshResult.Success(Unit)
        }
    }

    // ── The remote path ───────────────────────────────────────────────────────

    @Test
    fun `the upload path joins the folder and the name with exactly one slash`() {
        assertEquals("/a.txt", DesktopTransferManager.uploadTargetPath("/", "a.txt"))
        assertEquals("/x/a.txt", DesktopTransferManager.uploadTargetPath("/x/", "a.txt"))
        assertEquals("/x/a.txt", DesktopTransferManager.uploadTargetPath("/x", "a.txt"))
    }

    @Test
    fun `the upload path replaces the controls and the invisible format characters of the name with underscores`() {
        assertEquals("/srv/a_txt.sh", DesktopTransferManager.uploadTargetPath("/srv", "a\u202Etxt.sh"))
        assertEquals("/srv/a_b", DesktopTransferManager.uploadTargetPath("/srv", "a\nb"))
        assertEquals("/srv/__", DesktopTransferManager.uploadTargetPath("/srv/", "\u200B\u0001"))
    }

    @Test
    fun `the upload path keeps the joiners and the edge spaces of the name`() {
        val family = String(Character.toChars(0x1F468)) + 0x200D.toChar() + String(Character.toChars(0x1F469))
        assertEquals("/srv/$family", DesktopTransferManager.uploadTargetPath("/srv", family))
        assertEquals("/srv/ a ", DesktopTransferManager.uploadTargetPath("/srv", " a "))
    }

    @Test
    fun `the upload path replaces a backslash of the name with an underscore`() {
        assertEquals("/srv/a_b", DesktopTransferManager.uploadTargetPath("/srv", "a\\b"))
        assertEquals("/srv/_", DesktopTransferManager.uploadTargetPath("/srv/", "\\"))
    }

    // ── Upload ────────────────────────────────────────────────────────────────

    @Test
    fun `an upload without replacement tells the manager not to replace`() = runBlocking<Unit> {
        stubUploadSucceeds()
        val id = manager.enqueueUpload(SESSION, "/srv", localFile("a.txt", "hello"), replaceExisting = false)

        assertIs<TransferState.Completed>(awaitTerminal(id))

        coVerify(exactly = 1) { sftpManager.writeFile(SESSION, "/srv/a.txt", any(), 5L, false, any()) }
        coVerify(exactly = 0) { sftpManager.writeFile(any(), any(), any(), any(), true, any()) }
    }

    @Test
    fun `an upload with replacement tells the manager to replace`() = runBlocking<Unit> {
        stubUploadSucceeds()
        val id = manager.enqueueUpload(SESSION, "/srv/", localFile("a.txt", "hello"), replaceExisting = true)

        assertIs<TransferState.Completed>(awaitTerminal(id))

        coVerify(exactly = 1) { sftpManager.writeFile(SESSION, "/srv/a.txt", any(), 5L, true, any()) }
    }

    @Test
    fun `an upload of a file with a hidden character in its name writes under the cleaned name and shows it`() = runBlocking<Unit> {
        stubUploadSucceeds()
        // U+202E is a legal character of a file name on NTFS and ext4: the file is real.
        val id = manager.enqueueUpload(SESSION, "/srv", localFile("a\u202Etxt.sh", "hello"), replaceExisting = false)

        val done = assertIs<TransferState.Completed>(awaitTerminal(id))

        coVerify(exactly = 1) { sftpManager.writeFile(SESSION, "/srv/a_txt.sh", any(), 5L, false, any()) }
        assertEquals("a_txt.sh", done.request.displayName)
    }

    @Test
    fun `an upload whose local file is gone fails with the read message before anything is sent`() = runBlocking<Unit> {
        stubUploadSucceeds()
        val id = manager.enqueueUpload(SESSION, "/srv", localFile("gone.txt"), replaceExisting = false)

        assertEquals(getString(Res.string.sftp_error_local_read_failed), awaitFailure(id))

        coVerify(exactly = 0) { sftpManager.writeFile(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `an upload refused by the server shows the server message`() = runBlocking<Unit> {
        coEvery { sftpManager.writeFile(any(), any(), any(), any(), any(), any()) } returns
            SshResult.Error(SshErrorCode.UNKNOWN, "An item with this name already exists")
        val id = manager.enqueueUpload(SESSION, "/srv", localFile("a.txt", "hello"), replaceExisting = false)

        assertEquals("An item with this name already exists", awaitFailure(id))
    }

    // ── Download: the local destination ───────────────────────────────────────

    @Test
    fun `a download without replacement leaves an existing file untouched and reads nothing`() = runBlocking<Unit> {
        stubDownloadWrites("new")
        val target = localFile("a.txt", "keep me")

        val id = manager.enqueueDownload(SESSION, "/srv/a.txt", 3L, target, "a.txt", replaceExisting = false)

        assertEquals(getString(Res.string.sftp_error_local_exists), awaitFailure(id))
        assertEquals("keep me", target.readText())
        coVerify(exactly = 0) { sftpManager.readFile(any(), any(), any(), any()) }
    }

    @Test
    fun `a download without replacement creates a destination that does not exist`() = runBlocking<Unit> {
        stubDownloadWrites("remote content")
        val target = localFile("a.txt")

        val id = manager.enqueueDownload(SESSION, "/srv/a.txt", 14L, target, "a.txt", replaceExisting = false)

        assertIs<TransferState.Completed>(awaitTerminal(id))
        assertEquals("remote content", target.readText())
        coVerify(exactly = 1) { sftpManager.readFile(SESSION, "/srv/a.txt", any(), any()) }
    }

    @Test
    fun `a download with replacement overwrites an existing file`() = runBlocking<Unit> {
        stubDownloadWrites("new")
        val target = localFile("a.txt", "a much longer previous content")

        val id = manager.enqueueDownload(SESSION, "/srv/a.txt", 3L, target, "a.txt", replaceExisting = true)

        assertIs<TransferState.Completed>(awaitTerminal(id))
        assertEquals("new", target.readText())
    }

    @Test
    fun `a download into a folder that does not exist fails with the write message`() = runBlocking<Unit> {
        stubDownloadWrites("x")
        val target = File(File(tempDir, "no-such-folder"), "a.txt")
        val expected = getString(Res.string.sftp_error_local_write_failed)

        for (replaceExisting in listOf(false, true)) {
            val id = manager.enqueueDownload(SESSION, "/srv/a.txt", 1L, target, "a.txt", replaceExisting)

            assertEquals(expected, awaitFailure(id), "replaceExisting=$replaceExisting")
        }
        coVerify(exactly = 0) { sftpManager.readFile(any(), any(), any(), any()) }
    }

    // ── Download: the destination is never touched before the end ─────────────

    /** The names in the download folder, which must be the downloaded file alone. */
    private fun folderContent(): List<String> = tempDir.list().orEmpty().sorted()

    @Test
    fun `a download that fails on the server leaves the file it was to replace intact`() = runBlocking<Unit> {
        val target = localFile("a.txt", "keep me")
        coEvery { sftpManager.readFile(any(), any(), any(), any()) } coAnswers {
            thirdArg<OutputStream>().write("half a file".toByteArray())
            SshResult.Error(SshErrorCode.UNKNOWN, "Permission denied")
        }

        val id = manager.enqueueDownload(SESSION, "/srv/a.txt", 11L, target, "a.txt", replaceExisting = true)

        assertEquals("Permission denied", awaitFailure(id))
        assertEquals("keep me", target.readText())
        assertEquals(listOf("a.txt"), folderContent(), "no temporary file is left behind")
    }

    @Test
    fun `a download that throws leaves the file it was to replace intact and no temporary file`() = runBlocking<Unit> {
        val target = localFile("a.txt", "keep me")
        coEvery { sftpManager.readFile(any(), any(), any(), any()) } coAnswers {
            thirdArg<OutputStream>().write("half a file".toByteArray())
            throw IllegalStateException("session dropped")
        }

        val id = manager.enqueueDownload(SESSION, "/srv/a.txt", 11L, target, "a.txt", replaceExisting = true)

        assertEquals(getString(Res.string.transfers_failed_default), awaitFailure(id))
        assertEquals("keep me", target.readText())
        assertEquals(listOf("a.txt"), folderContent())
    }

    @Test
    fun `a cancelled download leaves the file it was to replace intact and no temporary file`() = runBlocking<Unit> {
        val target = localFile("a.txt", "keep me")
        val started = CompletableDeferred<Unit>()
        coEvery { sftpManager.readFile(any(), any(), any(), any()) } coAnswers {
            thirdArg<OutputStream>().write("half a file".toByteArray())
            started.complete(Unit)
            awaitCancellation()
        }
        val id = manager.enqueueDownload(SESSION, "/srv/a.txt", 11L, target, "a.txt", replaceExisting = true)
        withTimeout(5_000L) { started.await() }

        manager.cancel(id)

        assertIs<TransferState.Cancelled>(awaitTerminal(id))
        assertEquals("keep me", target.readText())
        assertEquals(listOf("a.txt"), folderContent())
    }

    @Test
    fun `a cancelled download that was not yet in progress still ends as cancelled`() = runBlocking<Unit> {
        coEvery { sftpManager.readFile(any(), any(), any(), any()) } coAnswers { awaitCancellation() }
        val id = manager.enqueueDownload(SESSION, "/srv/a.txt", 11L, localFile("a.txt"), "a.txt", replaceExisting = true)

        // Right after the call, before the transfer had the time to start: it must not stay queued for ever.
        manager.cancel(id)

        assertIs<TransferState.Cancelled>(awaitTerminal(id))
    }

    @Test
    fun `a successful download leaves the downloaded file alone in its folder`() = runBlocking<Unit> {
        stubDownloadWrites("remote content")

        val id = manager.enqueueDownload(SESSION, "/srv/a.txt", 14L, localFile("a.txt"), "a.txt", replaceExisting = false)

        assertIs<TransferState.Completed>(awaitTerminal(id))
        assertEquals(listOf("a.txt"), folderContent())
    }

    @Test
    fun `the data goes to a hidden temporary file next to the destination while the download runs`() = runBlocking<Unit> {
        val whileRunning = CompletableDeferred<List<String>>()
        coEvery { sftpManager.readFile(any(), any(), any(), any()) } coAnswers {
            thirdArg<OutputStream>().write("data".toByteArray())
            whileRunning.complete(folderContent())
            SshResult.Success(Unit)
        }

        val id = manager.enqueueDownload(SESSION, "/srv/a.txt", 4L, localFile("a.txt"), "a.txt", replaceExisting = false)
        assertIs<TransferState.Completed>(awaitTerminal(id))

        val names = withTimeout(5_000L) { whileRunning.await() }
        assertEquals(1, names.size, "the destination does not exist yet: $names")
        assertTrue(Regex("""\.a\.txt\.nextsh-part-[0-9a-f]{8}""").matches(names.single()), names.single())
    }

    @Test
    fun `a file that appears while the download runs is kept and the download reports it`() = runBlocking<Unit> {
        val target = localFile("a.txt")
        coEvery { sftpManager.readFile(any(), any(), any(), any()) } coAnswers {
            thirdArg<OutputStream>().write("downloaded".toByteArray())
            // Someone else creates the file after the manager looked, before the download ends.
            target.writeText("appeared meanwhile")
            SshResult.Success(Unit)
        }

        val id = manager.enqueueDownload(SESSION, "/srv/a.txt", 10L, target, "a.txt", replaceExisting = false)

        assertEquals(getString(Res.string.sftp_error_local_exists), awaitFailure(id))
        assertEquals("appeared meanwhile", target.readText())
        assertEquals(listOf("a.txt"), folderContent())
    }

    @Test
    fun `a confirmed replacement puts the new content in place and leaves no temporary file`() = runBlocking<Unit> {
        stubDownloadWrites("new")
        val target = localFile("a.txt", "a much longer previous content")

        val id = manager.enqueueDownload(SESSION, "/srv/a.txt", 3L, target, "a.txt", replaceExisting = true)

        assertIs<TransferState.Completed>(awaitTerminal(id))
        assertEquals("new", target.readText())
        assertEquals(listOf("a.txt"), folderContent())
    }

    @Test
    fun `a very long file name still gets a usable temporary name`() = runBlocking<Unit> {
        stubDownloadWrites("data")
        // Longer than the part of the name kept in the temporary one, short enough for any path limit.
        val longName = "n".repeat(110) + ".txt"

        val id = manager.enqueueDownload(SESSION, "/srv/x", 4L, localFile(longName), longName, replaceExisting = false)

        assertIs<TransferState.Completed>(awaitTerminal(id))
        assertEquals(listOf(longName), folderContent())
    }

    // ── The name of the temporary file ────────────────────────────────────────

    private fun cjk(count: Int): String = String(Character.toChars(0x6F22)).repeat(count)

    private fun emoji(count: Int): String = String(Character.toChars(0x1F600)).repeat(count)

    private fun utf8Size(text: String): Int = text.toByteArray(Charsets.UTF_8).size

    @Test
    fun `the kept part of a name is cut in bytes of UTF-8, on a boundary of code points`() {
        // 40 characters of 3 bytes = exactly the 120 bytes allowed.
        assertEquals(cjk(40), DesktopTransferManager.temporaryNamePart(cjk(40)))
        assertEquals(cjk(40), DesktopTransferManager.temporaryNamePart(cjk(70)))
        assertEquals("a".repeat(120), DesktopTransferManager.temporaryNamePart("a".repeat(300)))
        assertEquals("short.txt", DesktopTransferManager.temporaryNamePart("short.txt"))
        assertEquals("", DesktopTransferManager.temporaryNamePart(""))
        // A character that does not fit whole is left out, not cut.
        assertEquals(cjk(1), DesktopTransferManager.temporaryNamePart(cjk(5), maxBytes = 4))
        assertEquals("", DesktopTransferManager.temporaryNamePart(cjk(5), maxBytes = 2))
    }

    @Test
    fun `a pair of surrogates is never cut in two`() {
        // 40 emoji = 160 bytes, 4 bytes (2 chars) each: 30 fit in 120 bytes.
        val kept = DesktopTransferManager.temporaryNamePart(emoji(40))

        assertEquals(emoji(30), kept)
        assertEquals(120, utf8Size(kept))
        // A lone surrogate would not survive the round trip through UTF-8 (it becomes a "?").
        assertEquals(kept, String(kept.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
        for (limit in 1..9) {
            val part = DesktopTransferManager.temporaryNamePart(emoji(5), maxBytes = limit)
            assertEquals(part, String(part.toByteArray(Charsets.UTF_8), Charsets.UTF_8), "limit $limit")
            assertTrue(utf8Size(part) <= limit, "limit $limit")
        }
    }

    @Test
    fun `the kept part never takes the name past 255 bytes, even with a name of 3-byte characters`() {
        val kept = DesktopTransferManager.temporaryNamePart(cjk(80))

        // ".<name>.nextsh-part-<8 characters>"
        assertTrue(utf8Size(".$kept.nextsh-part-12345678") <= 255)
    }

    @Test
    fun `a download to a file with a long name of 3-byte characters gets a usable temporary name`() = runBlocking<Unit> {
        val name = cjk(70) + ".txt"
        // The file system must be able to hold such a name at all (not so with a C locale).
        val encoding = System.getProperty("sun.jnu.encoding") ?: return@runBlocking
        if (!java.nio.charset.Charset.forName(encoding).newEncoder().canEncode(name)) return@runBlocking
        stubDownloadWrites("data")

        val id = manager.enqueueDownload(SESSION, "/srv/x", 4L, localFile(name), name, replaceExisting = false)

        assertIs<TransferState.Completed>(awaitTerminal(id))
        assertEquals(listOf(name), folderContent())
    }

    // ── Permissions (Unix) ────────────────────────────────────────────────────

    /** Unix permissions: not on Windows, where there is nothing to check. */
    private val hasPosixPermissions: Boolean =
        tempDir.toPath().fileSystem.supportedFileAttributeViews().contains("posix")

    private fun permissionsOf(file: File): Set<PosixFilePermission> = Files.getPosixFilePermissions(file.toPath())

    @Test
    fun `the temporary file is readable by its owner only`() = runBlocking<Unit> {
        if (!hasPosixPermissions) return@runBlocking
        val seen = CompletableDeferred<Set<PosixFilePermission>>()
        coEvery { sftpManager.readFile(any(), any(), any(), any()) } coAnswers {
            val temporary = tempDir.listFiles().orEmpty().single { it.name.startsWith(".") }
            seen.complete(permissionsOf(temporary))
            thirdArg<OutputStream>().write("data".toByteArray())
            SshResult.Success(Unit)
        }

        val id = manager.enqueueDownload(SESSION, "/srv/a.txt", 4L, localFile("a.txt"), "a.txt", replaceExisting = false)
        assertIs<TransferState.Completed>(awaitTerminal(id))

        assertEquals(PosixFilePermissions.fromString("rw-------"), withTimeout(5_000L) { seen.await() })
    }

    @Test
    fun `a file downloaded under a free name is private to its owner`() = runBlocking<Unit> {
        if (!hasPosixPermissions) return@runBlocking
        stubDownloadWrites("data")
        val target = localFile("a.txt")

        val id = manager.enqueueDownload(SESSION, "/srv/a.txt", 4L, target, "a.txt", replaceExisting = false)

        assertIs<TransferState.Completed>(awaitTerminal(id))
        assertEquals(PosixFilePermissions.fromString("rw-------"), permissionsOf(target))
    }

    @Test
    fun `a file put in place without replacement is left with one name only`() = runBlocking<Unit> {
        // On Unix the name is created as a hard link of the temporary file, which is then
        // deleted: the file must not stay reachable under two names.
        if (!hasPosixPermissions) return@runBlocking
        stubDownloadWrites("data")
        val target = localFile("a.txt")
        val id = manager.enqueueDownload(SESSION, "/srv/a.txt", 4L, target, "a.txt", replaceExisting = false)
        assertIs<TransferState.Completed>(awaitTerminal(id))

        val links = try {
            Files.getAttribute(target.toPath(), "unix:nlink") as Int
        } catch (_: UnsupportedOperationException) {
            return@runBlocking
        } catch (_: IllegalArgumentException) {
            return@runBlocking
        }

        assertEquals(1, links)
        assertEquals(listOf("a.txt"), folderContent())
    }

    @Test
    fun `a replaced file keeps the permission bits it had`() = runBlocking<Unit> {
        if (!hasPosixPermissions) return@runBlocking
        stubDownloadWrites("new")

        for (bits in listOf("rw-------", "rwxr-x---", "rw-r--r--", "r--------")) {
            val target = localFile("file-${bits.hashCode()}", "old")
            Files.setPosixFilePermissions(target.toPath(), PosixFilePermissions.fromString(bits))

            val id = manager.enqueueDownload(SESSION, "/srv/a", 3L, target, target.name, replaceExisting = true)

            assertIs<TransferState.Completed>(awaitTerminal(id), bits)
            assertEquals("new", target.readText(), bits)
            assertEquals(PosixFilePermissions.fromString(bits), permissionsOf(target), bits)
        }
    }

    @Test
    fun `a replacement of a file that is gone meanwhile still succeeds`() = runBlocking<Unit> {
        // Nothing left to copy the permissions from: the download must not fail for that.
        coEvery { sftpManager.readFile(any(), any(), any(), any()) } coAnswers {
            thirdArg<OutputStream>().write("new".toByteArray())
            SshResult.Success(Unit)
        }
        val target = localFile("a.txt")

        val id = manager.enqueueDownload(SESSION, "/srv/a.txt", 3L, target, "a.txt", replaceExisting = true)

        assertIs<TransferState.Completed>(awaitTerminal(id))
        assertEquals("new", target.readText())
    }

    // ── Temporary files when the application quits ────────────────────────────

    @Test
    fun `deleting the temporary files removes the ones of the downloads under way`() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { sftpManager.readFile(any(), any(), any(), any()) } coAnswers {
            thirdArg<OutputStream>().write("partial".toByteArray())
            started.complete(Unit)
            release.await()
            SshResult.Success(Unit)
        }
        val id = manager.enqueueDownload(SESSION, "/srv/a.txt", 7L, localFile("a.txt"), "a.txt", replaceExisting = false)
        withTimeout(5_000L) { started.await() }
        assertEquals(1, folderContent().size, "the temporary file exists while the download runs")

        manager.deleteTemporaries()

        assertEquals(emptyList(), folderContent())
        // The download, which does not know, ends: it fails, it does not come back to life.
        release.complete(Unit)
        assertIs<TransferState.Failed>(awaitTerminal(id))
        assertEquals(emptyList(), folderContent())
    }

    @Test
    fun `deleting the temporary files leaves every other file alone, hidden or not`() = runBlocking<Unit> {
        stubDownloadWrites("data")
        val stranger = localFile(".b.txt.nextsh-part-12345678", "not ours")
        val visible = localFile("c.txt", "mine")
        val id = manager.enqueueDownload(SESSION, "/srv/a.txt", 4L, localFile("a.txt"), "a.txt", replaceExisting = false)
        assertIs<TransferState.Completed>(awaitTerminal(id))

        manager.deleteTemporaries()
        manager.deleteTemporaries()

        assertEquals("not ours", stranger.readText())
        assertEquals("mine", visible.readText())
        assertEquals("data", File(tempDir, "a.txt").readText())
    }

    // ── The disk work is on the I/O workers ───────────────────────────────────

    @Test
    fun `the local disk work of a download runs on the I-O dispatcher`() = runBlocking<Unit> {
        val seen = CompletableDeferred<Any?>()
        coEvery { sftpManager.readFile(any(), any(), any(), any()) } coAnswers {
            seen.complete(currentCoroutineContext()[ContinuationInterceptor])
            SshResult.Success(Unit)
        }

        val id = manager.enqueueDownload(SESSION, "/srv/a.txt", 0L, localFile("a.txt"), "a.txt", replaceExisting = false)
        awaitTerminal(id)

        assertEquals(Dispatchers.IO, withTimeout(5_000L) { seen.await() })
    }

    @Test
    fun `the local disk work of an upload runs on the I-O dispatcher`() = runBlocking<Unit> {
        val seen = CompletableDeferred<Any?>()
        coEvery { sftpManager.writeFile(any(), any(), any(), any(), any(), any()) } coAnswers {
            seen.complete(currentCoroutineContext()[ContinuationInterceptor])
            SshResult.Success(Unit)
        }

        val id = manager.enqueueUpload(SESSION, "/srv", localFile("a.txt", "hello"), replaceExisting = false)
        awaitTerminal(id)

        assertEquals(Dispatchers.IO, withTimeout(5_000L) { seen.await() })
    }

    // ── A failure that is not an Exception ────────────────────────────────────

    @Test
    fun `an error that is not an exception still ends the transfer instead of leaving it in progress`() = runBlocking<Unit> {
        coEvery { sftpManager.writeFile(any(), any(), any(), any(), any(), any()) } coAnswers {
            throw NoClassDefFoundError("missing class")
        }

        val id = manager.enqueueUpload(SESSION, "/srv", localFile("a.txt", "hello"), replaceExisting = false)

        val message = awaitFailure(id)
        assertEquals(getString(Res.string.transfers_failed_default), message)
        assertFalse(message.contains("missing"), message)
        // The error still goes up to the scope (it is not swallowed), where this class catches it.
        assertTrue(uncaught.any { it is NoClassDefFoundError }, "got $uncaught")
    }

    // ── Failures never show a raw message ─────────────────────────────────────

    @Test
    fun `an exception raised after the transfer was cancelled still ends it with the default message`() = runBlocking<Unit> {
        // The handler of the exception reads a string, which cannot be done on a cancelled job
        // unless it is protected: the entry would stay in progress for ever.
        coEvery { sftpManager.writeFile(any(), any(), any(), any(), any(), any()) } coAnswers {
            currentCoroutineContext().job.cancel()
            throw IllegalStateException("late failure")
        }

        val id = manager.enqueueUpload(SESSION, "/srv", localFile("a.txt", "hello"), replaceExisting = false)

        assertEquals(getString(Res.string.transfers_failed_default), awaitFailure(id))
    }

    @Test
    fun `an unexpected exception ends with the default message, shown and logged without its text`() = runBlocking<Unit> {
        coEvery { sftpManager.writeFile(any(), any(), any(), any(), any(), any()) } throws
            IllegalStateException("boom in /home/secret/path/a.txt")
        val upload = localFile("a.txt", "hello")

        val lines = captureLogs {
            val id = manager.enqueueUpload(SESSION, "/srv", upload, replaceExisting = false)
            val error = awaitFailure(id)

            assertEquals(getString(Res.string.transfers_failed_default), error)
            assertFalse(error.contains("boom"), error)
            assertFalse(error.contains("Erreur inconnue"), error)
        }.taggedBy("DesktopTransferManager")

        assertTrue(lines.any { it.contains("IllegalStateException") }, "the class of the exception is logged: $lines")
        assertTrue(lines.none { it.contains("boom") || it.contains("secret") || it.contains("a.txt") }, "no text, no path: $lines")
    }

    @Test
    fun `an error without a message ends with the default message and not with the error code`() = runBlocking<Unit> {
        val expected = getString(Res.string.transfers_failed_default)

        for (blank in listOf("", "   ")) {
            coEvery { sftpManager.writeFile(any(), any(), any(), any(), any(), any()) } returns
                SshResult.Error(SshErrorCode.UNKNOWN, blank)
            val id = manager.enqueueUpload(SESSION, "/srv", localFile("a.txt", "hello"), replaceExisting = false)

            val error = awaitFailure(id)

            assertEquals(expected, error, "message '$blank'")
            assertFalse(error.contains(SshErrorCode.UNKNOWN.name), error)
        }
    }

    // ── Cancel ────────────────────────────────────────────────────────────────

    @Test
    fun `a cancelled transfer ends as cancelled`() = runBlocking<Unit> {
        coEvery { sftpManager.writeFile(any(), any(), any(), any(), any(), any()) } coAnswers { awaitCancellation() }
        val id = manager.enqueueUpload(SESSION, "/srv", localFile("a.txt", "hello"), replaceExisting = false)
        withTimeout(5_000L) { manager.transfers.first { it[id] is TransferState.InProgress } }

        manager.cancel(id)

        assertIs<TransferState.Cancelled>(awaitTerminal(id))
    }

    // ── The request keeps what the panel shows ────────────────────────────────

    @Test
    fun `the request carries the remote path, the local path and the direction`() = runBlocking<Unit> {
        stubUploadSucceeds()
        val upload = localFile("a.txt", "hello")

        val id = manager.enqueueUpload(SESSION, "/srv", upload, replaceExisting = false, hostLabel = "prod")
        val state = awaitTerminal(id)

        assertEquals("/srv/a.txt", state.request.remotePath)
        assertEquals(upload.absolutePath, state.request.localUri)
        assertEquals(TransferDirection.UPLOAD, state.request.direction)
        assertEquals("a.txt", state.request.displayName)
        assertEquals("prod", state.request.hostLabel)
    }

    private companion object {
        const val SESSION = "ssh-1"
    }
}
