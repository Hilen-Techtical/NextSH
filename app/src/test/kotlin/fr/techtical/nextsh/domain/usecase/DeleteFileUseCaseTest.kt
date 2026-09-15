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
class DeleteFileUseCaseTest {

    @MockK
    private lateinit var sftpManager: SftpManager

    private lateinit var deleteFileUseCase: DeleteFileUseCase

    @BeforeEach
    fun setUp() {
        deleteFileUseCase = DeleteFileUseCase(sftpManager)
    }

    // ── File deletion (non-recursive) ────────────────────────────────────────────

    @Test
    fun `invoke calls delete when isDirectory is false`() = runTest {
        val sessionId = "session-1"
        val path = "/home/user/file.txt"

        coEvery { sftpManager.delete(sessionId, path) } returns SshResult.Success(Unit)

        val result = deleteFileUseCase(sessionId, path, isDirectory = false)

        assertTrue(result is SshResult.Success)
        coVerify { sftpManager.delete(sessionId, path) }
        coVerify(exactly = 0) { sftpManager.deleteRecursive(any(), any()) }
    }

    @Test
    fun `invoke delegates delete error for file`() = runTest {
        val sessionId = "session-1"
        val path = "/home/user/protected.txt"

        coEvery { sftpManager.delete(sessionId, path) } returns
            SshResult.Error(SshErrorCode.UNKNOWN, "Permission refusée")

        val result = deleteFileUseCase(sessionId, path, isDirectory = false)

        assertTrue(result is SshResult.Error)
        assertEquals("Permission refusée", (result as SshResult.Error).message)
    }

    @Test
    fun `invoke handles file not found error`() = runTest {
        val sessionId = "session-1"
        val path = "/home/user/nonexistent.txt"

        coEvery { sftpManager.delete(sessionId, path) } returns
            SshResult.Error(SshErrorCode.UNKNOWN, "Fichier introuvable")

        val result = deleteFileUseCase(sessionId, path, isDirectory = false)

        assertTrue(result is SshResult.Error)
        assertEquals("Fichier introuvable", (result as SshResult.Error).message)
    }

    // ── Directory deletion (recursive) ───────────────────────────────────────────

    @Test
    fun `invoke calls deleteRecursive when isDirectory is true`() = runTest {
        val sessionId = "session-1"
        val path = "/home/user/folder"

        coEvery { sftpManager.deleteRecursive(sessionId, path) } returns SshResult.Success(Unit)

        val result = deleteFileUseCase(sessionId, path, isDirectory = true)

        assertTrue(result is SshResult.Success)
        coVerify { sftpManager.deleteRecursive(sessionId, path) }
        coVerify(exactly = 0) { sftpManager.delete(any(), any()) }
    }

    @Test
    fun `invoke delegates deleteRecursive error for directory`() = runTest {
        val sessionId = "session-1"
        val path = "/root/protected_folder"

        coEvery { sftpManager.deleteRecursive(sessionId, path) } returns
            SshResult.Error(SshErrorCode.UNKNOWN, "Permission refusée")

        val result = deleteFileUseCase(sessionId, path, isDirectory = true)

        assertTrue(result is SshResult.Error)
        assertEquals("Permission refusée", (result as SshResult.Error).message)
    }

    @Test
    fun `invoke handles directory not found error`() = runTest {
        val sessionId = "session-1"
        val path = "/home/user/nonexistent_folder"

        coEvery { sftpManager.deleteRecursive(sessionId, path) } returns
            SshResult.Error(SshErrorCode.UNKNOWN, "Dossier introuvable")

        val result = deleteFileUseCase(sessionId, path, isDirectory = true)

        assertTrue(result is SshResult.Error)
        assertEquals("Dossier introuvable", (result as SshResult.Error).message)
    }

    // ── Boundary conditions ──────────────────────────────────────────────────────

    @Test
    fun `invoke handles root path deletion`() = runTest {
        val sessionId = "session-1"
        val path = "/"

        coEvery { sftpManager.deleteRecursive(sessionId, path) } returns
            SshResult.Error(SshErrorCode.UNKNOWN, "Cannot delete root")

        val result = deleteFileUseCase(sessionId, path, isDirectory = true)

        assertTrue(result is SshResult.Error)
    }

    @Test
    fun `invoke handles empty path for file deletion`() = runTest {
        val sessionId = "session-1"
        val path = ""

        coEvery { sftpManager.delete(sessionId, path) } returns
            SshResult.Error(SshErrorCode.UNKNOWN, "Chemin invalide")

        val result = deleteFileUseCase(sessionId, path, isDirectory = false)

        assertTrue(result is SshResult.Error)
    }

    @Test
    fun `invoke correctly distinguishes between file and directory with same name at different flags`() = runTest {
        val sessionId = "session-1"
        val path = "/home/user/item"

        coEvery { sftpManager.delete(sessionId, path) } returns SshResult.Success(Unit)
        coEvery { sftpManager.deleteRecursive(sessionId, path) } returns SshResult.Success(Unit)

        // Same path, deleted as file
        val resultFile = deleteFileUseCase(sessionId, path, isDirectory = false)
        // Same path, deleted as directory
        val resultDir = deleteFileUseCase(sessionId, path, isDirectory = true)

        assertTrue(resultFile is SshResult.Success)
        assertTrue(resultDir is SshResult.Success)
        coVerify(exactly = 1) { sftpManager.delete(sessionId, path) }
        coVerify(exactly = 1) { sftpManager.deleteRecursive(sessionId, path) }
    }

    @Test
    fun `invoke propagates connection lost error`() = runTest {
        val sessionId = "session-1"
        val path = "/home/user/file.txt"

        coEvery { sftpManager.delete(sessionId, path) } returns
            SshResult.Error(SshErrorCode.UNKNOWN, "Connexion SFTP perdue")

        val result = deleteFileUseCase(sessionId, path, isDirectory = false)

        assertTrue(result is SshResult.Error)
        assertEquals("Connexion SFTP perdue", (result as SshResult.Error).message)
    }
}
