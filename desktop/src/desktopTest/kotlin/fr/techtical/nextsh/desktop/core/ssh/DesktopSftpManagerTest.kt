// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.ssh

import fr.techtical.nextsh.shared.domain.model.SshResult
import io.mockk.Called
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.SFTPClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An SFTP path is not a URL. These tests pin that `+` and `%XX` reach the SFTP
 * client untouched (a folder named `x+y` or `100%` must stay reachable and
 * deletable) and that folder creation validates the BARE name before joining
 * it to the parent directory.
 *
 * SFTP clients are strict mocks on purpose: any call with an argument the test
 * did not stub (for example the decoded `/home/u/x y`) throws and fails the
 * test, so a regression cannot pass silently.
 */
class DesktopSftpManagerTest {

    private val sessionId = "session-1"

    /** A manager with no open SFTP client, enough for the pure helpers. */
    private val pureManager = DesktopSftpManager(mockk(relaxed = true))

    /** Builds a manager whose [sessionId] already has [sftp] open. */
    private suspend fun managerWith(sftp: SFTPClient): DesktopSftpManager {
        val sshClient = mockk<SSHClient>()
        every { sshClient.newSFTPClient() } returns sftp
        val terminal = mockk<DesktopSshTerminalSession>()
        every { terminal.getSshClient() } returns sshClient
        val sessions = mockk<DesktopSshSessionManager>()
        every { sessions.getTerminalSession(sessionId) } returns terminal
        val manager = DesktopSftpManager(sessions)
        assertTrue(manager.openSftp(sessionId) is SshResult.Success)
        return manager
    }

    // ── sanitizePath ──────────────────────────────────────────────────────────

    @Test
    fun `sanitizePath keeps plus sign literal`() {
        assertEquals("/home/u/x+y", pureManager.sanitizePath("/home/u/x+y"))
        assertEquals("/home/u/a b+c", pureManager.sanitizePath("/home/u/a b+c"))
    }

    @Test
    fun `sanitizePath keeps percent sequences literal`() {
        assertEquals("/home/u/100%", pureManager.sanitizePath("/home/u/100%"))
        assertEquals("/home/u/50%zz", pureManager.sanitizePath("/home/u/50%zz"))
        assertEquals("/home/u/a%20b", pureManager.sanitizePath("/home/u/a%20b"))
        assertEquals("/home/u/%2e%2e", pureManager.sanitizePath("/home/u/%2e%2e"))
    }

    @Test
    fun `sanitizePath treats percent-encoded dots as a literal name, not a parent`() {
        assertEquals("/%2e%2e/%2e%2e/etc/passwd", pureManager.sanitizePath("/%2e%2e/%2e%2e/etc/passwd"))
        assertEquals("/home/%2e%2e/x", pureManager.sanitizePath("/home/%2e%2e/x"))
    }

    @Test
    fun `sanitizePath keeps percent-encoded null as literal text and rejects a real null-byte`() {
        assertEquals("/home/user%00/file", pureManager.sanitizePath("/home/user%00/file"))
        assertNull(pureManager.sanitizePath("/home/user${Char(0)}/file"))
    }

    @Test
    fun `sanitizePath keeps backslashes as part of the name`() {
        // On a POSIX server "\" is a name character: "x\..\.." must not climb two folders.
        assertEquals("/home/u/x\\..\\..", pureManager.sanitizePath("/home/u/x\\..\\.."))
        assertEquals("/home/u/..\\.bashrc", pureManager.sanitizePath("/home/u/..\\.bashrc"))
    }

    @Test
    fun `sanitizePath rejects blank paths`() {
        assertNull(pureManager.sanitizePath(""))
        assertNull(pureManager.sanitizePath("   "))
    }

    @Test
    fun `sanitizePath still resolves dot segments and repeated slashes`() {
        assertEquals("/home/other", pureManager.sanitizePath("/home/user/../other"))
        assertEquals("/home/user/docs", pureManager.sanitizePath("/home//user/./docs/"))
        assertEquals("/", pureManager.sanitizePath("/"))
        assertEquals("/", pureManager.sanitizePath("."))
        assertEquals("/user", pureManager.sanitizePath("user"))
    }

    @Test
    fun `sanitizePath still rejects traversal above the root`() {
        assertNull(pureManager.sanitizePath(".."))
        assertNull(pureManager.sanitizePath("/../etc/passwd"))
        assertNull(pureManager.sanitizePath("/var/www/../../../../etc/passwd"))
    }

    // ── isValidEntryName ──────────────────────────────────────────────────────

    @Test
    fun `isValidEntryName accepts ordinary names including plus and percent`() {
        val accepted = listOf("x+y", "100%", "%2e%2e", "a%20b", "a b", "..x", "x..", "...", ".hidden")
        for ((index, name) in accepted.withIndex()) {
            assertTrue(pureManager.isValidEntryName(name), "accepted name #$index was rejected")
        }
    }

    @Test
    fun `isValidEntryName rejects dot names, separators, control characters and blanks`() {
        val rejected = listOf(
            "", "   ", ".", "..", " .. ", "../x", "a/b", "a\\b", "/", "\\", "x/", "/x",
            "x${Char(0)}y", "x${Char(1)}y", "x${Char(10)}y", "x${Char(0x7f)}y",
        )
        for ((index, name) in rejected.withIndex()) {
            assertFalse(pureManager.isValidEntryName(name), "rejected name #$index was accepted")
        }
    }

    // ── listDirectory ─────────────────────────────────────────────────────────

    @Test
    fun `listDirectory passes paths with plus and percent to the SFTP client unchanged`() = runTest {
        val paths = listOf("/home/u/x+y", "/home/u/100%", "/home/u/%2e%2e", "/home/u/a%20b")
        val sftp = mockk<SFTPClient>()
        val received = mutableListOf<String>()
        every { sftp.ls(capture(received)) } returns emptyList()
        val manager = managerWith(sftp)

        for ((index, path) in paths.withIndex()) {
            assertTrue(manager.listDirectory(sessionId, path) is SshResult.Success, "listing #$index failed")
        }

        assertEquals(paths, received)
    }

    // ── delete / deleteRecursive ──────────────────────────────────────────────

    @Test
    fun `delete passes paths with plus and percent to the SFTP client unchanged`() = runTest {
        val paths = listOf(
            "/home/u/x+y", "/home/u/100%", "/home/u/%2e%2e", "/home/u/a%20b",
            "/home/u/x\\..\\..", "/home/u/..\\.bashrc",
        )
        val sftp = mockk<SFTPClient>()
        val removed = mutableListOf<String>()
        every { sftp.rm(capture(removed)) } just Runs
        val manager = managerWith(sftp)

        for ((index, path) in paths.withIndex()) {
            assertTrue(manager.delete(sessionId, path) is SshResult.Success, "delete #$index failed")
        }

        assertEquals(paths, removed)
    }

    @Test
    fun `rename passes old and new paths with plus and percent unchanged`() = runTest {
        val sftp = mockk<SFTPClient>()
        val from = mutableListOf<String>()
        val to = mutableListOf<String>()
        every { sftp.rename(capture(from), capture(to)) } just Runs
        val manager = managerWith(sftp)

        assertTrue(manager.rename(sessionId, "/home/u/x+y", "100%41") is SshResult.Success)

        assertEquals(listOf("/home/u/x+y"), from)
        assertEquals(listOf("/home/u/100%41"), to)
    }

    @Test
    fun `deleteRecursive on a folder with a plus sign only touches that folder`() = runTest {
        val basePath = "/home/u/x+y"
        val sftp = mockk<SFTPClient>()
        val fileInfo = mockk<RemoteResourceInfo>()
        val dirInfo = mockk<RemoteResourceInfo>()
        val attrs = mockk<FileAttributes>()
        val removedFiles = mutableListOf<String>()
        val removedDirs = mutableListOf<String>()

        every { fileInfo.name } returns "a.txt"
        every { fileInfo.path } returns "$basePath/a.txt"
        every { fileInfo.isDirectory } returns false
        every { fileInfo.attributes } returns attrs
        every { dirInfo.name } returns "sub+dir"
        every { dirInfo.path } returns "$basePath/sub+dir"
        every { dirInfo.isDirectory } returns true
        every { dirInfo.attributes } returns attrs
        every { attrs.type } returns FileMode.Type.REGULAR

        // Only the literal paths are stubbed: a decoded target such as
        // "/home/u/x y" would make the strict mock throw and fail the test.
        every { sftp.ls(basePath) } returns listOf(fileInfo, dirInfo)
        every { sftp.ls("$basePath/sub+dir") } returns emptyList()
        every { sftp.rm(capture(removedFiles)) } just Runs
        every { sftp.rmdir(capture(removedDirs)) } just Runs
        val manager = managerWith(sftp)

        val result = manager.deleteRecursive(sessionId, basePath)

        assertTrue(result is SshResult.Success)
        assertEquals(listOf("$basePath/a.txt"), removedFiles)
        assertEquals(listOf("$basePath/sub+dir", basePath), removedDirs)
    }

    // ── createDirectory ───────────────────────────────────────────────────────

    @Test
    fun `createDirectory refuses unsafe names without any SFTP call`() = runTest {
        val sftp = mockk<SFTPClient>()
        val manager = managerWith(sftp)
        val unsafe = listOf(
            "../x", "../../etc", "a/b", "a\\b", "..", ".", "", "   ", "/", "\\", "x/", "/x",
            "x${Char(0)}y", "x${Char(10)}y", "x${Char(0x7f)}y",
        )

        for ((index, name) in unsafe.withIndex()) {
            val result = manager.createDirectory(sessionId, "/home/u", name)
            assertTrue(result is SshResult.Error, "unsafe name #$index was not refused")
        }

        verify { sftp wasNot Called }
    }

    @Test
    fun `createDirectory refuses an invalid parent directory without any SFTP call`() = runTest {
        val sftp = mockk<SFTPClient>()
        val manager = managerWith(sftp)

        assertTrue(manager.createDirectory(sessionId, "/../x", "ok") is SshResult.Error)
        assertTrue(manager.createDirectory(sessionId, "", "ok") is SshResult.Error)
        assertTrue(manager.createDirectory(sessionId, "/home/u${Char(0)}", "ok") is SshResult.Error)

        verify { sftp wasNot Called }
    }

    @Test
    fun `createDirectory creates names with plus and percent literally`() = runTest {
        val sftp = mockk<SFTPClient>()
        val created = mutableListOf<String>()
        every { sftp.mkdir(capture(created)) } just Runs
        val manager = managerWith(sftp)
        val names = listOf("x+y", "100%", "%2e%2e", "a%20b", "..x", "x..", ".hidden", "a b")

        for ((index, name) in names.withIndex()) {
            val result = manager.createDirectory(sessionId, "/home/u", name)
            assertTrue(result is SshResult.Success, "creation #$index failed")
        }

        assertEquals(names.map { "/home/u/$it" }, created)
    }

    @Test
    fun `createDirectory joins the name to the parent without doubling or dropping a slash`() = runTest {
        val sftp = mockk<SFTPClient>()
        val created = mutableListOf<String>()
        every { sftp.mkdir(capture(created)) } just Runs
        val manager = managerWith(sftp)

        assertTrue(manager.createDirectory(sessionId, "/", "x+y") is SshResult.Success)
        assertTrue(manager.createDirectory(sessionId, "/home/u/", "100%") is SshResult.Success)
        assertTrue(manager.createDirectory(sessionId, "/home/u/a+b", "c") is SshResult.Success)

        assertEquals(listOf("/x+y", "/home/u/100%", "/home/u/a+b/c"), created)
    }
}
