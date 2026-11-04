// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.domain.model.SftpFile
import fr.techtical.nextsh.domain.model.SshErrorCode
import fr.techtical.nextsh.shared.domain.model.SshResult
import io.mockk.coEvery
import io.mockk.Runs
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.xfer.FilePermission
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.concurrent.ConcurrentHashMap

@ExtendWith(MockKExtension::class)
class SftpManagerTest {

    @MockK
    private lateinit var sessionManager: SshSessionManager

    private lateinit var sftpManager: SftpManager

    @BeforeEach
    fun setUp() {
        sftpManager = SftpManager(sessionManager)
    }

    // ── openSftp / closeSftp / isOpen ────────────────────────────────────────────

    @Test
    fun `openSftp succeeds when session client exists`() = runTest {
        val sessionId = "session-1"
        val mockSSHClient = mockk<SSHClient>()
        val mockSftpClient = mockk<SFTPClient>()

        every { mockSSHClient.newSFTPClient() } returns mockSftpClient
        coEvery { sessionManager.getClient(sessionId) } returns mockSSHClient

        val result = sftpManager.openSftp(sessionId)

        assertTrue(result is SshResult.Success)
        assertTrue(sftpManager.isOpen(sessionId))
    }

    @Test
    fun `openSftp returns success if already open (idempotent)`() = runTest {
        val sessionId = "session-1"
        val mockSSHClient = mockk<SSHClient>()
        val mockSftpClient = mockk<SFTPClient>()

        every { mockSSHClient.newSFTPClient() } returns mockSftpClient
        coEvery { sessionManager.getClient(sessionId) } returns mockSSHClient

        sftpManager.openSftp(sessionId)
        val result2 = sftpManager.openSftp(sessionId)

        assertTrue(result2 is SshResult.Success)
        assertTrue(sftpManager.isOpen(sessionId))
    }

    @Test
    fun `openSftp returns error when session does not exist`() = runTest {
        val sessionId = "nonexistent"
        coEvery { sessionManager.getClient(sessionId) } returns null

        val result = sftpManager.openSftp(sessionId)

        assertTrue(result is SshResult.Error)
        assertEquals(SshErrorCode.UNKNOWN, (result as SshResult.Error).code)
    }

    @Test
    fun `closeSftp removes client from map`() = runTest {
        val sessionId = "session-1"
        val mockSSHClient = mockk<SSHClient>()
        val mockSftpClient = mockk<SFTPClient>(relaxed = true)

        every { mockSSHClient.newSFTPClient() } returns mockSftpClient
        coEvery { sessionManager.getClient(sessionId) } returns mockSSHClient

        sftpManager.openSftp(sessionId)
        assertTrue(sftpManager.isOpen(sessionId))

        val closeResult = sftpManager.closeSftp(sessionId)

        assertTrue(closeResult is SshResult.Success)
        assertFalse(sftpManager.isOpen(sessionId))
    }

    @Test
    fun `closeSftp succeeds even if client was not open`() = runTest {
        val sessionId = "session-1"

        val result = sftpManager.closeSftp(sessionId)

        assertTrue(result is SshResult.Success)
    }

    // ── listDirectory path sanitization ──────────────────────────────────────────

    @Test
    fun `listDirectory rejects null-byte in path`() = runTest {
        val sessionId = "session-1"
        val mockSSHClient = mockk<SSHClient>()
        val mockSftpClient = mockk<SFTPClient>()

        every { mockSSHClient.newSFTPClient() } returns mockSftpClient
        coEvery { sessionManager.getClient(sessionId) } returns mockSSHClient

        sftpManager.openSftp(sessionId)

        val result = sftpManager.listDirectory(sessionId, "/home/user\u0000/file")

        assertTrue(result is SshResult.Error)
        assertEquals(SshErrorCode.UNKNOWN, (result as SshResult.Error).code)
    }

    @Test
    fun `listDirectory rejects path traversal above root`() = runTest {
        val sessionId = "session-1"
        val mockSSHClient = mockk<SSHClient>()
        val mockSftpClient = mockk<SFTPClient>()

        every { mockSSHClient.newSFTPClient() } returns mockSftpClient
        coEvery { sessionManager.getClient(sessionId) } returns mockSSHClient

        sftpManager.openSftp(sessionId)

        val result = sftpManager.listDirectory(sessionId, "/../../../etc/passwd")

        assertTrue(result is SshResult.Error)
        assertEquals(SshErrorCode.UNKNOWN, (result as SshResult.Error).code)
    }

    @Test
    fun `listDirectory succeeds with normal path`() = runTest {
        val sessionId = "session-1"
        val path = "/home/user"
        val mockSSHClient = mockk<SSHClient>()
        val mockSftpClient = mockk<SFTPClient>()
        val mockFileInfo = mockk<RemoteResourceInfo>()
        val mockAttrs = mockk<net.schmizz.sshj.sftp.FileAttributes>()

        every { mockSSHClient.newSFTPClient() } returns mockSftpClient
        coEvery { sessionManager.getClient(sessionId) } returns mockSSHClient
        every { mockFileInfo.name } returns "test.txt"
        every { mockFileInfo.path } returns "/home/user/test.txt"
        every { mockFileInfo.isDirectory } returns false
        every { mockFileInfo.attributes } returns mockAttrs
        every { mockAttrs.type } returns FileMode.Type.REGULAR
        every { mockAttrs.size } returns 1024L
        every { mockAttrs.mtime } returns 1000L
        every { mockAttrs.permissions } returns setOf<FilePermission>()
        every { mockSftpClient.ls(path) } returns listOf(mockFileInfo)

        sftpManager.openSftp(sessionId)
        val result = sftpManager.listDirectory(sessionId, path)

        assertTrue(result is SshResult.Success)
        assertEquals(1, (result as SshResult.Success).data.size)
        assertEquals("test.txt", result.data[0].name)
    }

    @Test
    fun `listDirectory returns empty list when directory is empty`() = runTest {
        val sessionId = "session-1"
        val path = "/empty/dir"
        val mockSSHClient = mockk<SSHClient>()
        val mockSftpClient = mockk<SFTPClient>()

        every { mockSSHClient.newSFTPClient() } returns mockSftpClient
        coEvery { sessionManager.getClient(sessionId) } returns mockSSHClient
        every { mockSftpClient.ls(path) } returns emptyList()

        sftpManager.openSftp(sessionId)
        val result = sftpManager.listDirectory(sessionId, path)

        assertTrue(result is SshResult.Success)
        assertTrue((result as SshResult.Success).data.isEmpty())
    }

    @Test
    fun `listDirectory handles permission denied error`() = runTest {
        val sessionId = "session-1"
        val path = "/root"
        val mockSSHClient = mockk<SSHClient>()
        val mockSftpClient = mockk<SFTPClient>()

        every { mockSSHClient.newSFTPClient() } returns mockSftpClient
        coEvery { sessionManager.getClient(sessionId) } returns mockSSHClient
        every { mockSftpClient.ls(path) } throws SFTPException("Permission denied")

        sftpManager.openSftp(sessionId)
        val result = sftpManager.listDirectory(sessionId, path)

        assertTrue(result is SshResult.Error)
        assertEquals(SshErrorCode.AUTH_FAILED, (result as SshResult.Error).code)
    }

    @Test
    fun `listDirectory passes paths with plus and percent to the SFTP client unchanged`() = runTest {
        val sessionId = "session-1"
        val paths = listOf("/home/u/x+y", "/home/u/100%", "/home/u/%2e%2e", "/home/u/a%20b")
        val mockSSHClient = mockk<SSHClient>()
        val mockSftpClient = mockk<SFTPClient>()
        val received = mutableListOf<String>()

        every { mockSSHClient.newSFTPClient() } returns mockSftpClient
        coEvery { sessionManager.getClient(sessionId) } returns mockSSHClient
        every { mockSftpClient.ls(capture(received)) } returns emptyList()

        sftpManager.openSftp(sessionId)
        for (path in paths) {
            val result = sftpManager.listDirectory(sessionId, path)
            assertTrue(result is SshResult.Success, "listDirectory($path) doit réussir")
        }

        // Argument exact reçu par le client : ni "+" en espace, ni "%XX" décodé.
        assertEquals(paths, received)
    }

    // ── sanitizePath tests ───────────────────────────────────────────────────────

    @Test
    fun `sanitizePath normalizes simple path`() {
        val result = sftpManager.sanitizePath("/home/user")
        assertEquals("/home/user", result)
    }

    @Test
    fun `sanitizePath keeps backslashes as part of the name`() {
        // Sur un serveur POSIX « \ » est un caractère de nom : « x\..\.. » ne doit pas
        // remonter de deux dossiers.
        assertEquals("/home/u/x\\..\\..", sftpManager.sanitizePath("/home/u/x\\..\\.."))
        assertEquals("/home/u/..\\.bashrc", sftpManager.sanitizePath("/home/u/..\\.bashrc"))
    }

    @Test
    fun `sanitizePath resolves double dots`() {
        val result = sftpManager.sanitizePath("/home/user/../other")
        assertEquals("/home/other", result)
    }

    @Test
    fun `sanitizePath rejects traversal above root`() {
        val result = sftpManager.sanitizePath("/../../../etc/passwd")
        assertNull(result)
    }

    @Test
    fun `sanitizePath handles multiple consecutive slashes`() {
        val result = sftpManager.sanitizePath("/home//user///documents")
        assertEquals("/home/user/documents", result)
    }

    @Test
    fun `sanitizePath ignores dot segments`() {
        val result = sftpManager.sanitizePath("/home/./user")
        assertEquals("/home/user", result)
    }

    @Test
    fun `sanitizePath handles trailing slash`() {
        val result = sftpManager.sanitizePath("/home/user/")
        assertEquals("/home/user", result)
    }

    @Test
    fun `sanitizePath handles root directory`() {
        val result = sftpManager.sanitizePath("/")
        assertEquals("/", result)
    }

    @Test
    fun `sanitizePath normalizes relative path to absolute`() {
        val result = sftpManager.sanitizePath("relative/path")
        assertEquals("/relative/path", result)
    }

    @Test
    fun `sanitizePath rejects null-byte injection`() {
        val result = sftpManager.sanitizePath("/home/user\u0000/file")
        assertNull(result)
    }

    @Test
    fun `sanitizePath handles complex nested dots`() {
        val result = sftpManager.sanitizePath("/home/user/./../../documents/../files")
        // /home/user + . -> /home/user, then .. -> /home, then .. -> /, documents -> /documents, then .. -> /, files -> /files
        assertEquals("/files", result)
    }

    @Test
    fun `sanitizePath single dot resolves to slash`() {
        val result = sftpManager.sanitizePath(".")
        assertEquals("/", result)
    }

    @Test
    fun `sanitizePath double dot at root returns null`() {
        val result = sftpManager.sanitizePath("..")
        assertNull(result)
    }

    @Test
    fun `sanitizePath complex traversal attack`() {
        val result = sftpManager.sanitizePath("/var/www/../../../../../../etc/passwd")
        assertNull(result)
    }

    @Test
    fun `sanitizePath rejects blank path`() {
        assertNull(sftpManager.sanitizePath(""))
        assertNull(sftpManager.sanitizePath("   "))
    }

    @Test
    fun `sanitizePath keeps percent-encoded dots as literal names`() {
        // Un chemin SFTP n'est pas une URL : "%2e%2e" est un nom de dossier
        // littéral, pas une remontée. Le serveur ne le décode pas non plus.
        val result = sftpManager.sanitizePath("/%2e%2e/%2e%2e/etc/passwd")
        assertEquals("/%2e%2e/%2e%2e/etc/passwd", result)
    }

    @Test
    fun `sanitizePath keeps percent-encoded null as literal text`() {
        // "%00" n'est que du texte : seul un vrai null-byte est rejeté (voir
        // le test d'injection null-byte plus haut).
        val result = sftpManager.sanitizePath("/home/user%00/file")
        assertEquals("/home/user%00/file", result)
    }

    @Test
    fun `sanitizePath keeps plus sign literal`() {
        // "+" ne doit jamais devenir une espace (décodage application/x-www-form-urlencoded)
        assertEquals("/home/u/x+y", sftpManager.sanitizePath("/home/u/x+y"))
        assertEquals("/home/u/a b+c", sftpManager.sanitizePath("/home/u/a b+c"))
    }

    @Test
    fun `sanitizePath keeps percent sequences literal`() {
        assertEquals("/home/u/100%", sftpManager.sanitizePath("/home/u/100%"))
        assertEquals("/home/u/50%zz", sftpManager.sanitizePath("/home/u/50%zz"))
        assertEquals("/home/u/a%20b", sftpManager.sanitizePath("/home/u/a%20b"))
        assertEquals("/home/u/%2e%2e", sftpManager.sanitizePath("/home/u/%2e%2e"))
    }

    // ── permissionsString tests ────────────────────────────────────────────────────

    @Test
    fun `SftpFile permissionsString displays 755 correctly`() {
        // 0o755 = 493 decimal = rwxr-xr-x
        val file = SftpFile(name = "test", path = "/test", size = 0, permissions = 493,
            isDirectory = true, modifiedAt = 0)
        assertEquals("rwxr-xr-x", file.permissionsString)
    }

    @Test
    fun `SftpFile permissionsString displays 644 correctly`() {
        // 0o644 = 420 decimal = rw-r--r--
        val file = SftpFile(name = "test", path = "/test", size = 0, permissions = 420,
            isDirectory = false, modifiedAt = 0)
        assertEquals("rw-r--r--", file.permissionsString)
    }

    @Test
    fun `SftpFile permissionsString displays 000 correctly`() {
        val file = SftpFile(name = "test", path = "/test", size = 0, permissions = 0,
            isDirectory = false, modifiedAt = 0)
        assertEquals("---------", file.permissionsString)
    }

    @Test
    fun `SftpFile permissionsString displays 777 correctly`() {
        // 0o777 = 511 decimal = rwxrwxrwx
        val file = SftpFile(name = "test", path = "/test", size = 0, permissions = 511,
            isDirectory = false, modifiedAt = 0)
        assertEquals("rwxrwxrwx", file.permissionsString)
    }

    // ── sanitizeFilename tests ───────────────────────────────────────────────────

    @Test
    fun `sanitizeFilename removes forward slashes`() {
        val result = sftpManager.sanitizeFilename("file/name.txt")
        assertEquals("filename.txt", result)
    }

    @Test
    fun `sanitizeFilename removes backslashes`() {
        val result = sftpManager.sanitizeFilename("file\\name.txt")
        assertEquals("filename.txt", result)
    }

    @Test
    fun `sanitizeFilename rejects null-byte`() {
        val result = sftpManager.sanitizeFilename("file\u0000.txt")
        assertEquals("file.txt", result)
    }

    @Test
    fun `sanitizeFilename truncates to 255 chars`() {
        val longName = "a".repeat(300) + ".txt"
        val result = sftpManager.sanitizeFilename(longName)
        assertEquals(255, result?.length)
    }

    @Test
    fun `sanitizeFilename returns null when empty after sanitization`() {
        val result = sftpManager.sanitizeFilename("\u0000/\\")
        assertNull(result)
    }

    @Test
    fun `sanitizeFilename preserves normal filename`() {
        val result = sftpManager.sanitizeFilename("document.pdf")
        assertEquals("document.pdf", result)
    }

    @Test
    fun `sanitizeFilename removes control characters`() {
        val result = sftpManager.sanitizeFilename("file\u0001\u0002\u0003.txt")
        assertEquals("file.txt", result)
    }

    @Test
    fun `sanitizeFilename trims whitespace`() {
        val result = sftpManager.sanitizeFilename("  filename.txt  ")
        assertEquals("filename.txt", result)
    }

    @Test
    fun `sanitizeFilename handles exactly 255 char filename`() {
        val name = "a".repeat(255)
        val result = sftpManager.sanitizeFilename(name)
        assertEquals(255, result?.length)
    }

    // ── stat tests ───────────────────────────────────────────────────────────────

    @Test
    fun `stat returns file metadata for valid path`() = runTest {
        val sessionId = "session-1"
        val path = "/home/user/file.txt"
        val mockSSHClient = mockk<SSHClient>()
        val mockSftpClient = mockk<SFTPClient>()
        val mockAttrs = mockk<net.schmizz.sshj.sftp.FileAttributes>()

        every { mockSSHClient.newSFTPClient() } returns mockSftpClient
        coEvery { sessionManager.getClient(sessionId) } returns mockSSHClient
        every { mockAttrs.type } returns FileMode.Type.REGULAR
        every { mockAttrs.size } returns 2048L
        every { mockAttrs.mtime } returns 2000L
        every { mockAttrs.permissions } returns setOf<FilePermission>()
        every { mockSftpClient.stat(path) } returns mockAttrs

        sftpManager.openSftp(sessionId)
        val result = sftpManager.stat(sessionId, path)

        assertTrue(result is SshResult.Success)
        val file = (result as SshResult.Success).data
        assertEquals("file.txt", file.name)
        assertEquals(path, file.path)
        assertEquals(2048L, file.size)
        assertFalse(file.isDirectory)
    }

    @Test
    fun `stat returns directory flag correctly`() = runTest {
        val sessionId = "session-1"
        val path = "/home/user/documents"
        val mockSSHClient = mockk<SSHClient>()
        val mockSftpClient = mockk<SFTPClient>()
        val mockAttrs = mockk<net.schmizz.sshj.sftp.FileAttributes>()

        every { mockSSHClient.newSFTPClient() } returns mockSftpClient
        coEvery { sessionManager.getClient(sessionId) } returns mockSSHClient
        every { mockAttrs.type } returns FileMode.Type.DIRECTORY
        every { mockAttrs.size } returns 0L
        every { mockAttrs.mtime } returns 2000L
        every { mockAttrs.permissions } returns setOf<FilePermission>()
        every { mockSftpClient.stat(path) } returns mockAttrs

        sftpManager.openSftp(sessionId)
        val result = sftpManager.stat(sessionId, path)

        assertTrue(result is SshResult.Success)
        val file = (result as SshResult.Success).data
        assertTrue(file.isDirectory)
    }

    @Test
    fun `stat sanitizes path before calling SFTP`() = runTest {
        val sessionId = "session-1"
        val mockSSHClient = mockk<SSHClient>()
        val mockSftpClient = mockk<SFTPClient>()

        every { mockSSHClient.newSFTPClient() } returns mockSftpClient
        coEvery { sessionManager.getClient(sessionId) } returns mockSSHClient

        sftpManager.openSftp(sessionId)
        val result = sftpManager.stat(sessionId, "/home/user\u0000/file")

        assertTrue(result is SshResult.Error)
    }

    // ── getHomeDirectory ─────────────────────────────────────────────────────────

    @Test
    fun `getHomeDirectory returns canonicalized path`() = runTest {
        val sessionId = "session-1"
        val mockSSHClient = mockk<SSHClient>()
        val mockSftpClient = mockk<SFTPClient>()

        every { mockSSHClient.newSFTPClient() } returns mockSftpClient
        coEvery { sessionManager.getClient(sessionId) } returns mockSSHClient
        every { mockSftpClient.canonicalize(".") } returns "/home/testuser"

        sftpManager.openSftp(sessionId)
        val result = sftpManager.getHomeDirectory(sessionId)

        assertTrue(result is SshResult.Success)
        assertEquals("/home/testuser", (result as SshResult.Success).data)
    }

    @Test
    fun `getHomeDirectory returns root fallback on error`() = runTest {
        val sessionId = "session-1"
        val mockSSHClient = mockk<SSHClient>()
        val mockSftpClient = mockk<SFTPClient>()

        every { mockSSHClient.newSFTPClient() } returns mockSftpClient
        coEvery { sessionManager.getClient(sessionId) } returns mockSSHClient
        every { mockSftpClient.canonicalize(".") } throws Exception("Cannot canonicalize")

        sftpManager.openSftp(sessionId)
        val result = sftpManager.getHomeDirectory(sessionId)

        assertTrue(result is SshResult.Success)
        assertEquals("/", (result as SshResult.Success).data)
    }

    @Test
    fun `getHomeDirectory fails when SFTP not initialized`() = runTest {
        val sessionId = "session-1"

        val result = sftpManager.getHomeDirectory(sessionId)

        assertTrue(result is SshResult.Error)
        assertEquals(SshErrorCode.UNKNOWN, (result as SshResult.Error).code)
    }

    // ── createDirectory tests ────────────────────────────────────────────────────

    @Test
    fun `createDirectory succeeds when directory created`() = runTest {
        val sessionId = "session-1"
        val path = "/home/user/newdir"
        val mockSSHClient = mockk<SSHClient>()
        val mockSftpClient = mockk<SFTPClient>(relaxed = true)

        every { mockSSHClient.newSFTPClient() } returns mockSftpClient
        coEvery { sessionManager.getClient(sessionId) } returns mockSSHClient

        sftpManager.openSftp(sessionId)
        val result = sftpManager.createDirectory(sessionId, path)

        assertTrue(result is SshResult.Success)
        coVerify { mockSftpClient.mkdir(path) }
    }

    @Test
    fun `createDirectory returns error when permission denied`() = runTest {
        val sessionId = "session-1"
        val path = "/root/newdir"
        val mockSSHClient = mockk<SSHClient>()
        val mockSftpClient = mockk<SFTPClient>()

        every { mockSSHClient.newSFTPClient() } returns mockSftpClient
        coEvery { sessionManager.getClient(sessionId) } returns mockSSHClient
        every { mockSftpClient.mkdir(path) } throws SFTPException("Permission denied")

        sftpManager.openSftp(sessionId)
        val result = sftpManager.createDirectory(sessionId, path)

        assertTrue(result is SshResult.Error)
        assertEquals("Permission refusée", (result as SshResult.Error).message)
    }

    @Test
    fun `createDirectory returns error when directory already exists`() = runTest {
        val sessionId = "session-1"
        val path = "/home/user/existing"
        val mockSSHClient = mockk<SSHClient>()
        val mockSftpClient = mockk<SFTPClient>()

        every { mockSSHClient.newSFTPClient() } returns mockSftpClient
        coEvery { sessionManager.getClient(sessionId) } returns mockSSHClient
        every { mockSftpClient.mkdir(path) } throws SFTPException("File already exists")

        sftpManager.openSftp(sessionId)
        val result = sftpManager.createDirectory(sessionId, path)

        assertTrue(result is SshResult.Error)
        assertEquals("Un dossier portant ce nom existe déjà", (result as SshResult.Error).message)
    }

    @Test
    fun `createDirectory returns error when invalid name`() = runTest {
        val sessionId = "session-1"
        val path = "/home/user/"  // Empty name
        val mockSSHClient = mockk<SSHClient>()
        val mockSftpClient = mockk<SFTPClient>()

        every { mockSSHClient.newSFTPClient() } returns mockSftpClient
        coEvery { sessionManager.getClient(sessionId) } returns mockSSHClient

        sftpManager.openSftp(sessionId)
        val result = sftpManager.createDirectory(sessionId, path)

        assertTrue(result is SshResult.Error)
        assertEquals("Nom de dossier invalide", (result as SshResult.Error).message)
    }

    @Test
    fun `createDirectory returns error when name contains null-byte`() = runTest {
        val sessionId = "session-1"
        val path = "/home/user/bad\u0000dir"
        val mockSSHClient = mockk<SSHClient>()
        val mockSftpClient = mockk<SFTPClient>()

        every { mockSSHClient.newSFTPClient() } returns mockSftpClient
        coEvery { sessionManager.getClient(sessionId) } returns mockSSHClient

        sftpManager.openSftp(sessionId)
        val result = sftpManager.createDirectory(sessionId, path)

        assertTrue(result is SshResult.Error)
    }

    @Test
    fun `createDirectory keeps plus and percent in the path literal`() = runTest {
        val sessionId = "session-1"
        val paths = listOf("/home/u/x+y", "/home/u/100%")
        val mockSSHClient = mockk<SSHClient>()
        val mockSftpClient = mockk<SFTPClient>()
        val created = mutableListOf<String>()

        every { mockSSHClient.newSFTPClient() } returns mockSftpClient
        coEvery { sessionManager.getClient(sessionId) } returns mockSSHClient
        every { mockSftpClient.mkdir(capture(created)) } just Runs

        sftpManager.openSftp(sessionId)
        for (path in paths) {
            val result = sftpManager.createDirectory(sessionId, path)
            assertTrue(result is SshResult.Success, "createDirectory($path) doit réussir")
        }

        assertEquals(paths, created)
    }

    // ── delete tests ─────────────────────────────────────────────────────────────

    @Test
    fun `delete succeeds when file deleted`() = runTest {
        val sessionId = "session-1"
        val path = "/home/user/file.txt"
        val mockSSHClient = mockk<SSHClient>()
        val mockSftpClient = mockk<SFTPClient>(relaxed = true)

        every { mockSSHClient.newSFTPClient() } returns mockSftpClient
        coEvery { sessionManager.getClient(sessionId) } returns mockSSHClient

        sftpManager.openSftp(sessionId)
        val result = sftpManager.delete(sessionId, path)

        assertTrue(result is SshResult.Success)
        coVerify { mockSftpClient.rm(path) }
    }

    @Test
    fun `delete returns error when permission denied`() = runTest {
        val sessionId = "session-1"
        val path = "/root/protected.txt"
        val mockSSHClient = mockk<SSHClient>()
        val mockSftpClient = mockk<SFTPClient>()

        every { mockSSHClient.newSFTPClient() } returns mockSftpClient
        coEvery { sessionManager.getClient(sessionId) } returns mockSSHClient
        every { mockSftpClient.rm(path) } throws SFTPException("Permission denied")

        sftpManager.openSftp(sessionId)
        val result = sftpManager.delete(sessionId, path)

        assertTrue(result is SshResult.Error)
        assertEquals("Permission refusée", (result as SshResult.Error).message)
    }

    @Test
    fun `delete returns error when trying to delete directory`() = runTest {
        val sessionId = "session-1"
        val path = "/home/user/folder"
        val mockSSHClient = mockk<SSHClient>()
        val mockSftpClient = mockk<SFTPClient>()

        every { mockSSHClient.newSFTPClient() } returns mockSftpClient
        coEvery { sessionManager.getClient(sessionId) } returns mockSSHClient
        every { mockSftpClient.rm(path) } throws SFTPException("Cannot remove directory")

        sftpManager.openSftp(sessionId)
        val result = sftpManager.delete(sessionId, path)

        assertTrue(result is SshResult.Error)
        assertEquals("Impossible de supprimer un dossier avec cette opération", (result as SshResult.Error).message)
    }

    @Test
    fun `delete passes paths with plus and percent to the SFTP client unchanged`() = runTest {
        val sessionId = "session-1"
        val paths = listOf(
            "/home/u/x+y", "/home/u/100%", "/home/u/%2e%2e", "/home/u/a%20b",
            "/home/u/x\\..\\..", "/home/u/..\\.bashrc",
        )
        val mockSSHClient = mockk<SSHClient>()
        val mockSftpClient = mockk<SFTPClient>()
        val removed = mutableListOf<String>()

        every { mockSSHClient.newSFTPClient() } returns mockSftpClient
        coEvery { sessionManager.getClient(sessionId) } returns mockSSHClient
        every { mockSftpClient.rm(capture(removed)) } just Runs

        sftpManager.openSftp(sessionId)
        for (path in paths) {
            val result = sftpManager.delete(sessionId, path)
            assertTrue(result is SshResult.Success, "delete($path) doit réussir")
        }

        assertEquals(paths, removed)
    }

    // ── deleteRecursive tests ────────────────────────────────────────────────────

    @Test
    fun `deleteRecursive succeeds with nested files and directories`() = runTest {
        val sessionId = "session-1"
        val basePath = "/home/user/folder"
        val mockSSHClient = mockk<SSHClient>()
        val mockSftpClient = mockk<SFTPClient>(relaxed = true)

        every { mockSSHClient.newSFTPClient() } returns mockSftpClient
        coEvery { sessionManager.getClient(sessionId) } returns mockSSHClient

        // Mock nested structure: ls returns files and subdirs
        val fileInfo = mockk<RemoteResourceInfo>()
        val dirInfo = mockk<RemoteResourceInfo>()
        val subFileInfo = mockk<RemoteResourceInfo>()
        val mockAttrs = mockk<net.schmizz.sshj.sftp.FileAttributes>()

        every { fileInfo.name } returns "file.txt"
        every { fileInfo.path } returns "$basePath/file.txt"
        every { fileInfo.isDirectory } returns false
        every { fileInfo.attributes } returns mockAttrs
        every { dirInfo.name } returns "subdir"
        every { dirInfo.path } returns "$basePath/subdir"
        every { dirInfo.isDirectory } returns true
        every { dirInfo.attributes } returns mockAttrs
        every { subFileInfo.name } returns "subfile.txt"
        every { subFileInfo.path } returns "$basePath/subdir/subfile.txt"
        every { subFileInfo.isDirectory } returns false
        every { subFileInfo.attributes } returns mockAttrs
        every { mockAttrs.type } returns FileMode.Type.REGULAR

        every { mockSftpClient.ls(basePath) } returns listOf(fileInfo, dirInfo)
        every { mockSftpClient.ls("$basePath/subdir") } returns listOf(subFileInfo)

        sftpManager.openSftp(sessionId)
        val result = sftpManager.deleteRecursive(sessionId, basePath)

        assertTrue(result is SshResult.Success)
        coVerify { mockSftpClient.rm("$basePath/file.txt") }
        coVerify { mockSftpClient.rm("$basePath/subdir/subfile.txt") }
        coVerify { mockSftpClient.rmdir("$basePath/subdir") }
        coVerify { mockSftpClient.rmdir(basePath) }
    }

    @Test
    fun `deleteRecursive returns error when permission denied`() = runTest {
        val sessionId = "session-1"
        val path = "/root/protected"
        val mockSSHClient = mockk<SSHClient>()
        val mockSftpClient = mockk<SFTPClient>()

        every { mockSSHClient.newSFTPClient() } returns mockSftpClient
        coEvery { sessionManager.getClient(sessionId) } returns mockSSHClient
        every { mockSftpClient.ls(path) } throws SFTPException("Permission denied")

        sftpManager.openSftp(sessionId)
        val result = sftpManager.deleteRecursive(sessionId, path)

        assertTrue(result is SshResult.Error)
        assertEquals("Permission refusée", (result as SshResult.Error).message)
    }

    @Test
    fun `deleteRecursive on a folder with a plus sign only touches that folder`() = runTest {
        val sessionId = "session-1"
        val basePath = "/home/u/x+y"
        val mockSSHClient = mockk<SSHClient>()
        val mockSftpClient = mockk<SFTPClient>()
        val fileInfo = mockk<RemoteResourceInfo>()
        val dirInfo = mockk<RemoteResourceInfo>()
        val mockAttrs = mockk<net.schmizz.sshj.sftp.FileAttributes>()
        val removedFiles = mutableListOf<String>()
        val removedDirs = mutableListOf<String>()

        every { mockSSHClient.newSFTPClient() } returns mockSftpClient
        coEvery { sessionManager.getClient(sessionId) } returns mockSSHClient
        every { fileInfo.name } returns "a.txt"
        every { fileInfo.path } returns "$basePath/a.txt"
        every { fileInfo.isDirectory } returns false
        every { fileInfo.attributes } returns mockAttrs
        every { dirInfo.name } returns "sub+dir"
        every { dirInfo.path } returns "$basePath/sub+dir"
        every { dirInfo.isDirectory } returns true
        every { dirInfo.attributes } returns mockAttrs
        every { mockAttrs.type } returns FileMode.Type.REGULAR

        // Seuls les chemins littéraux sont stubbés : un décodage (cible "/home/u/x y")
        // ferait lever le mock strict, donc échouer le test.
        every { mockSftpClient.ls(basePath) } returns listOf(fileInfo, dirInfo)
        every { mockSftpClient.ls("$basePath/sub+dir") } returns emptyList()
        every { mockSftpClient.rm(capture(removedFiles)) } just Runs
        every { mockSftpClient.rmdir(capture(removedDirs)) } just Runs

        sftpManager.openSftp(sessionId)
        val result = sftpManager.deleteRecursive(sessionId, basePath)

        assertTrue(result is SshResult.Success)
        assertEquals(listOf("$basePath/a.txt"), removedFiles)
        assertEquals(listOf("$basePath/sub+dir", basePath), removedDirs)
    }

    // ── rename tests ─────────────────────────────────────────────────────────────

    @Test
    fun `rename succeeds when file renamed`() = runTest {
        val sessionId = "session-1"
        val oldPath = "/home/user/oldname.txt"
        val newName = "newname.txt"
        val newPath = "/home/user/newname.txt"
        val mockSSHClient = mockk<SSHClient>()
        val mockSftpClient = mockk<SFTPClient>(relaxed = true)

        every { mockSSHClient.newSFTPClient() } returns mockSftpClient
        coEvery { sessionManager.getClient(sessionId) } returns mockSSHClient

        sftpManager.openSftp(sessionId)
        val result = sftpManager.rename(sessionId, oldPath, newName)

        assertTrue(result is SshResult.Success)
        coVerify { mockSftpClient.rename(oldPath, newPath) }
    }

    @Test
    fun `rename removes slashes from new name`() = runTest {
        val sessionId = "session-1"
        val oldPath = "/home/user/file.txt"
        val nameWithSlashes = "path/to/file.txt"  // Slashes will be removed to "pathtofile.txt"
        val expectedNewPath = "/home/user/pathtofile.txt"  // Expected sanitized result
        val mockSSHClient = mockk<SSHClient>()
        val mockSftpClient = mockk<SFTPClient>(relaxed = true)

        every { mockSSHClient.newSFTPClient() } returns mockSftpClient
        coEvery { sessionManager.getClient(sessionId) } returns mockSSHClient

        sftpManager.openSftp(sessionId)
        val result = sftpManager.rename(sessionId, oldPath, nameWithSlashes)

        assertTrue(result is SshResult.Success)
        coVerify { mockSftpClient.rename(oldPath, expectedNewPath) }
    }

    @Test
    fun `rename sanitizes filename correctly`() = runTest {
        val sessionId = "session-1"
        val oldPath = "/home/user/file.txt"
        val nameWithSlash = "new\\file.txt"  // Backslash should be removed
        val mockSSHClient = mockk<SSHClient>()
        val mockSftpClient = mockk<SFTPClient>(relaxed = true)

        every { mockSSHClient.newSFTPClient() } returns mockSftpClient
        coEvery { sessionManager.getClient(sessionId) } returns mockSSHClient

        sftpManager.openSftp(sessionId)
        val result = sftpManager.rename(sessionId, oldPath, nameWithSlash)

        assertTrue(result is SshResult.Success)
        // Backslash removed, resulting in "newfile.txt"
        coVerify { mockSftpClient.rename(oldPath, "/home/user/newfile.txt") }
    }

    @Test
    fun `rename returns error when file already exists`() = runTest {
        val sessionId = "session-1"
        val oldPath = "/home/user/oldname.txt"
        val newName = "existing.txt"
        val newPath = "/home/user/existing.txt"
        val mockSSHClient = mockk<SSHClient>()
        val mockSftpClient = mockk<SFTPClient>()

        every { mockSSHClient.newSFTPClient() } returns mockSftpClient
        coEvery { sessionManager.getClient(sessionId) } returns mockSSHClient
        every { mockSftpClient.rename(oldPath, newPath) } throws SFTPException("File already exists")

        sftpManager.openSftp(sessionId)
        val result = sftpManager.rename(sessionId, oldPath, newName)

        assertTrue(result is SshResult.Error)
        assertEquals("Un fichier portant ce nom existe déjà", (result as SshResult.Error).message)
    }

    @Test
    fun `rename keeps plus and percent literal in the old and new paths`() = runTest {
        val sessionId = "session-1"
        val mockSSHClient = mockk<SSHClient>()
        val mockSftpClient = mockk<SFTPClient>()
        val oldPaths = mutableListOf<String>()
        val newPaths = mutableListOf<String>()

        every { mockSSHClient.newSFTPClient() } returns mockSftpClient
        coEvery { sessionManager.getClient(sessionId) } returns mockSSHClient
        every { mockSftpClient.rename(capture(oldPaths), capture(newPaths)) } just Runs

        sftpManager.openSftp(sessionId)
        val result = sftpManager.rename(sessionId, "/home/u/x+y", "100%41")

        assertTrue(result is SshResult.Success)
        assertEquals(listOf("/home/u/x+y"), oldPaths)
        assertEquals(listOf("/home/u/100%41"), newPaths)
    }

    // ── chmod tests ──────────────────────────────────────────────────────────────

    @Test
    fun `chmod succeeds with valid permissions`() = runTest {
        val sessionId = "session-1"
        val path = "/home/user/file.txt"
        val permissions = 493  // 0o755
        val mockSSHClient = mockk<SSHClient>()
        val mockSftpClient = mockk<SFTPClient>(relaxed = true)

        every { mockSSHClient.newSFTPClient() } returns mockSftpClient
        coEvery { sessionManager.getClient(sessionId) } returns mockSSHClient

        sftpManager.openSftp(sessionId)
        val result = sftpManager.chmod(sessionId, path, permissions)

        assertTrue(result is SshResult.Success)
        coVerify { mockSftpClient.chmod(path, permissions) }
    }

    @Test
    fun `chmod returns error when permissions negative`() = runTest {
        val sessionId = "session-1"
        val path = "/home/user/file.txt"
        val mockSSHClient = mockk<SSHClient>()
        val mockSftpClient = mockk<SFTPClient>()

        every { mockSSHClient.newSFTPClient() } returns mockSftpClient
        coEvery { sessionManager.getClient(sessionId) } returns mockSSHClient

        sftpManager.openSftp(sessionId)
        val result = sftpManager.chmod(sessionId, path, -1)

        assertTrue(result is SshResult.Error)
        assertEquals("Valeur de permissions invalide (attendu 0–511)", (result as SshResult.Error).message)
    }

    @Test
    fun `chmod returns error when permissions exceed 511`() = runTest {
        val sessionId = "session-1"
        val path = "/home/user/file.txt"
        val mockSSHClient = mockk<SSHClient>()
        val mockSftpClient = mockk<SFTPClient>()

        every { mockSSHClient.newSFTPClient() } returns mockSftpClient
        coEvery { sessionManager.getClient(sessionId) } returns mockSSHClient

        sftpManager.openSftp(sessionId)
        val result = sftpManager.chmod(sessionId, path, 512)

        assertTrue(result is SshResult.Error)
        assertEquals("Valeur de permissions invalide (attendu 0–511)", (result as SshResult.Error).message)
    }

    @Test
    fun `chmod succeeds with boundary permission value 0`() = runTest {
        val sessionId = "session-1"
        val path = "/home/user/file.txt"
        val mockSSHClient = mockk<SSHClient>()
        val mockSftpClient = mockk<SFTPClient>(relaxed = true)

        every { mockSSHClient.newSFTPClient() } returns mockSftpClient
        coEvery { sessionManager.getClient(sessionId) } returns mockSSHClient

        sftpManager.openSftp(sessionId)
        val result = sftpManager.chmod(sessionId, path, 0)

        assertTrue(result is SshResult.Success)
        coVerify { mockSftpClient.chmod(path, 0) }
    }

    @Test
    fun `chmod succeeds with boundary permission value 511`() = runTest {
        val sessionId = "session-1"
        val path = "/home/user/file.txt"
        val mockSSHClient = mockk<SSHClient>()
        val mockSftpClient = mockk<SFTPClient>(relaxed = true)

        every { mockSSHClient.newSFTPClient() } returns mockSftpClient
        coEvery { sessionManager.getClient(sessionId) } returns mockSSHClient

        sftpManager.openSftp(sessionId)
        val result = sftpManager.chmod(sessionId, path, 511)

        assertTrue(result is SshResult.Success)
        coVerify { mockSftpClient.chmod(path, 511) }
    }

    @Test
    fun `chmod returns error when permission denied`() = runTest {
        val sessionId = "session-1"
        val path = "/root/file.txt"
        val mockSSHClient = mockk<SSHClient>()
        val mockSftpClient = mockk<SFTPClient>()

        every { mockSSHClient.newSFTPClient() } returns mockSftpClient
        coEvery { sessionManager.getClient(sessionId) } returns mockSSHClient
        every { mockSftpClient.chmod(path, 493) } throws SFTPException("Permission denied")

        sftpManager.openSftp(sessionId)
        val result = sftpManager.chmod(sessionId, path, 493)

        assertTrue(result is SshResult.Error)
        assertEquals("Permission refusée", (result as SshResult.Error).message)
    }
}
