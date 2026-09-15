// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.domain.usecase

import fr.techtical.nextsh.core.ssh.SftpManager
import fr.techtical.nextsh.domain.model.SshErrorCode
import fr.techtical.nextsh.shared.domain.model.SshResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MockKExtension::class)
class ChangePermissionsUseCaseTest {

    @MockK
    private lateinit var sftpManager: SftpManager

    private lateinit var changePermissionsUseCase: ChangePermissionsUseCase

    @BeforeEach
    fun setUp() {
        changePermissionsUseCase = ChangePermissionsUseCase(sftpManager)
    }

    // ── Happy path ───────────────────────────────────────────────────────────────

    @Test
    fun `invoke succeeds with valid permissions 755`() = runTest {
        val sessionId = "session-1"
        val path = "/home/user/file.txt"
        val permissions = 493  // 0o755

        coEvery { sftpManager.chmod(sessionId, path, permissions) } returns SshResult.Success(Unit)

        val result = changePermissionsUseCase(sessionId, path, permissions)

        assertTrue(result is SshResult.Success)
        coVerify { sftpManager.chmod(sessionId, path, permissions) }
    }

    @Test
    fun `invoke succeeds with valid permissions 644`() = runTest {
        val sessionId = "session-1"
        val path = "/home/user/document.pdf"
        val permissions = 420  // 0o644

        coEvery { sftpManager.chmod(sessionId, path, permissions) } returns SshResult.Success(Unit)

        val result = changePermissionsUseCase(sessionId, path, permissions)

        assertTrue(result is SshResult.Success)
        coVerify { sftpManager.chmod(sessionId, path, permissions) }
    }

    @Test
    fun `invoke succeeds with boundary permission 0`() = runTest {
        val sessionId = "session-1"
        val path = "/home/user/secret.txt"
        val permissions = 0  // 0o000

        coEvery { sftpManager.chmod(sessionId, path, permissions) } returns SshResult.Success(Unit)

        val result = changePermissionsUseCase(sessionId, path, permissions)

        assertTrue(result is SshResult.Success)
        coVerify { sftpManager.chmod(sessionId, path, permissions) }
    }

    @Test
    fun `invoke succeeds with boundary permission 511`() = runTest {
        val sessionId = "session-1"
        val path = "/home/user/open.txt"
        val permissions = 511  // 0o777

        coEvery { sftpManager.chmod(sessionId, path, permissions) } returns SshResult.Success(Unit)

        val result = changePermissionsUseCase(sessionId, path, permissions)

        assertTrue(result is SshResult.Success)
        coVerify { sftpManager.chmod(sessionId, path, permissions) }
    }

    @Test
    fun `invoke succeeds with various valid permissions`() = runTest {
        val sessionId = "session-1"
        val path = "/home/user/file.txt"

        val validPermissions = listOf(0, 1, 100, 255, 384, 493, 511)

        for (perm in validPermissions) {
            coEvery { sftpManager.chmod(sessionId, path, perm) } returns SshResult.Success(Unit)
            val result = changePermissionsUseCase(sessionId, path, perm)
            assertTrue(result is SshResult.Success)
        }
    }

    @Test
    fun `invoke applies permissions to directories`() = runTest {
        val sessionId = "session-1"
        val path = "/home/user/folder"
        val permissions = 493  // 0o755 for directory

        coEvery { sftpManager.chmod(sessionId, path, permissions) } returns SshResult.Success(Unit)

        val result = changePermissionsUseCase(sessionId, path, permissions)

        assertTrue(result is SshResult.Success)
        coVerify { sftpManager.chmod(sessionId, path, permissions) }
    }

    // ── Error cases: Invalid permission values ───────────────────────────────────

    @Test
    fun `invoke returns error when permissions is negative`() = runTest {
        val sessionId = "session-1"
        val path = "/home/user/file.txt"
        val permissions = -1

        val result = changePermissionsUseCase(sessionId, path, permissions)

        assertTrue(result is SshResult.Error)
        assertEquals(SshErrorCode.UNKNOWN, (result as SshResult.Error).code)
        assertEquals("Valeur de permissions invalide (attendu 0–511)", result.message)
    }

    @Test
    fun `invoke returns error when permissions exceeds 511`() = runTest {
        val sessionId = "session-1"
        val path = "/home/user/file.txt"
        val permissions = 512  // Just over max

        val result = changePermissionsUseCase(sessionId, path, permissions)

        assertTrue(result is SshResult.Error)
        assertEquals(SshErrorCode.UNKNOWN, (result as SshResult.Error).code)
        assertEquals("Valeur de permissions invalide (attendu 0–511)", result.message)
    }

    @Test
    fun `invoke returns error when permissions far exceeds 511`() = runTest {
        val sessionId = "session-1"
        val path = "/home/user/file.txt"
        val permissions = 1000

        val result = changePermissionsUseCase(sessionId, path, permissions)

        assertTrue(result is SshResult.Error)
        assertEquals("Valeur de permissions invalide (attendu 0–511)", (result as SshResult.Error).message)
    }

    @Test
    fun `invoke returns error when permissions is large negative`() = runTest {
        val sessionId = "session-1"
        val path = "/home/user/file.txt"
        val permissions = -1000

        val result = changePermissionsUseCase(sessionId, path, permissions)

        assertTrue(result is SshResult.Error)
        assertEquals("Valeur de permissions invalide (attendu 0–511)", (result as SshResult.Error).message)
    }

    @Test
    fun `invoke returns error when permissions at min boundary`() = runTest {
        val sessionId = "session-1"
        val path = "/home/user/file.txt"
        val permissions = Int.MIN_VALUE

        val result = changePermissionsUseCase(sessionId, path, permissions)

        assertTrue(result is SshResult.Error)
    }

    @Test
    fun `invoke returns error when permissions at max boundary`() = runTest {
        val sessionId = "session-1"
        val path = "/home/user/file.txt"
        val permissions = Int.MAX_VALUE

        val result = changePermissionsUseCase(sessionId, path, permissions)

        assertTrue(result is SshResult.Error)
    }

    // ── SFTP operation errors ────────────────────────────────────────────────────

    @Test
    fun `invoke delegates sftpManager error when permission denied`() = runTest {
        val sessionId = "session-1"
        val path = "/root/file.txt"
        val permissions = 493

        coEvery { sftpManager.chmod(sessionId, path, permissions) } returns
            SshResult.Error(SshErrorCode.UNKNOWN, "Permission refusée")

        val result = changePermissionsUseCase(sessionId, path, permissions)

        assertTrue(result is SshResult.Error)
        assertEquals("Permission refusée", (result as SshResult.Error).message)
    }

    @Test
    fun `invoke delegates sftpManager error when file not found`() = runTest {
        val sessionId = "session-1"
        val path = "/home/user/nonexistent.txt"
        val permissions = 493

        coEvery { sftpManager.chmod(sessionId, path, permissions) } returns
            SshResult.Error(SshErrorCode.UNKNOWN, "Fichier introuvable")

        val result = changePermissionsUseCase(sessionId, path, permissions)

        assertTrue(result is SshResult.Error)
        assertEquals("Fichier introuvable", (result as SshResult.Error).message)
    }

    @Test
    fun `invoke handles connection lost error`() = runTest {
        val sessionId = "session-1"
        val path = "/home/user/file.txt"
        val permissions = 493

        coEvery { sftpManager.chmod(sessionId, path, permissions) } returns
            SshResult.Error(SshErrorCode.UNKNOWN, "Connexion SFTP perdue")

        val result = changePermissionsUseCase(sessionId, path, permissions)

        assertTrue(result is SshResult.Error)
        assertEquals("Connexion SFTP perdue", (result as SshResult.Error).message)
    }

    // ── Path handling ────────────────────────────────────────────────────────────

    @Test
    fun `invoke applies permissions to root path`() = runTest {
        val sessionId = "session-1"
        val path = "/"
        val permissions = 511

        coEvery { sftpManager.chmod(sessionId, path, permissions) } returns SshResult.Success(Unit)

        val result = changePermissionsUseCase(sessionId, path, permissions)

        assertTrue(result is SshResult.Success)
    }

    @Test
    fun `invoke applies permissions to deeply nested path`() = runTest {
        val sessionId = "session-1"
        val path = "/home/user/deep/nested/folder/file.txt"
        val permissions = 420

        coEvery { sftpManager.chmod(sessionId, path, permissions) } returns SshResult.Success(Unit)

        val result = changePermissionsUseCase(sessionId, path, permissions)

        assertTrue(result is SshResult.Success)
        coVerify { sftpManager.chmod(sessionId, path, permissions) }
    }

    @Test
    fun `invoke handles path with special characters`() = runTest {
        val sessionId = "session-1"
        val path = "/home/user/file (copy).txt"
        val permissions = 420

        coEvery { sftpManager.chmod(sessionId, path, permissions) } returns SshResult.Success(Unit)

        val result = changePermissionsUseCase(sessionId, path, permissions)

        assertTrue(result is SshResult.Success)
    }

    // ── Boundary and edge cases ──────────────────────────────────────────────────

    @Test
    fun `invoke validates permission before making SFTP call`() = runTest {
        val sessionId = "session-1"
        val path = "/home/user/file.txt"
        val invalidPermissions = 512

        val result = changePermissionsUseCase(sessionId, path, invalidPermissions)

        // Verify chmod was never called because validation failed
        coVerify(exactly = 0) { sftpManager.chmod(any(), any(), any()) }
        assertTrue(result is SshResult.Error)
    }

    @Test
    fun `invoke makes SFTP call for valid permission at lower boundary`() = runTest {
        val sessionId = "session-1"
        val path = "/home/user/file.txt"
        val validPermissions = 0

        coEvery { sftpManager.chmod(sessionId, path, validPermissions) } returns SshResult.Success(Unit)

        val result = changePermissionsUseCase(sessionId, path, validPermissions)

        assertTrue(result is SshResult.Success)
        coVerify { sftpManager.chmod(sessionId, path, validPermissions) }
    }

    @Test
    fun `invoke makes SFTP call for valid permission at upper boundary`() = runTest {
        val sessionId = "session-1"
        val path = "/home/user/file.txt"
        val validPermissions = 511

        coEvery { sftpManager.chmod(sessionId, path, validPermissions) } returns SshResult.Success(Unit)

        val result = changePermissionsUseCase(sessionId, path, validPermissions)

        assertTrue(result is SshResult.Success)
        coVerify { sftpManager.chmod(sessionId, path, validPermissions) }
    }
}
