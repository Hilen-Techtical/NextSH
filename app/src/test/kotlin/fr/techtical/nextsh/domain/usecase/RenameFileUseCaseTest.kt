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
class RenameFileUseCaseTest {

    @MockK
    private lateinit var sftpManager: SftpManager

    private lateinit var renameFileUseCase: RenameFileUseCase

    @BeforeEach
    fun setUp() {
        renameFileUseCase = RenameFileUseCase(sftpManager)
    }

    // ── Happy path ───────────────────────────────────────────────────────────────

    @Test
    fun `invoke succeeds when new name is valid`() = runTest {
        val sessionId = "session-1"
        val oldPath = "/home/user/oldfile.txt"
        val newName = "newfile.txt"

        coEvery { sftpManager.sanitizeFilename(newName) } returns newName
        coEvery { sftpManager.rename(sessionId, oldPath, newName) } returns SshResult.Success(Unit)

        val result = renameFileUseCase(sessionId, oldPath, newName)

        assertTrue(result is SshResult.Success)
        coVerify { sftpManager.rename(sessionId, oldPath, newName) }
    }

    @Test
    fun `invoke sanitizes new name before renaming`() = runTest {
        val sessionId = "session-1"
        val oldPath = "/home/user/oldfile.txt"
        val newNameUnsafe = "new\\file.txt"  // Backslash should be removed
        val newNameSanitized = "newfile.txt"

        coEvery { sftpManager.sanitizeFilename(newNameUnsafe) } returns newNameSanitized
        coEvery { sftpManager.rename(sessionId, oldPath, newNameSanitized) } returns SshResult.Success(Unit)

        val result = renameFileUseCase(sessionId, oldPath, newNameUnsafe)

        assertTrue(result is SshResult.Success)
        coVerify { sftpManager.sanitizeFilename(newNameUnsafe) }
        coVerify { sftpManager.rename(sessionId, oldPath, newNameSanitized) }
    }

    @Test
    fun `invoke renames file at root directory`() = runTest {
        val sessionId = "session-1"
        val oldPath = "/oldfile.txt"
        val newName = "newfile.txt"

        coEvery { sftpManager.sanitizeFilename(newName) } returns newName
        coEvery { sftpManager.rename(sessionId, oldPath, newName) } returns SshResult.Success(Unit)

        val result = renameFileUseCase(sessionId, oldPath, newName)

        assertTrue(result is SshResult.Success)
        coVerify { sftpManager.rename(sessionId, oldPath, newName) }
    }

    @Test
    fun `invoke handles special characters in new name`() = runTest {
        val sessionId = "session-1"
        val oldPath = "/home/user/file.txt"
        val newNameWithSpecial = "file (1).txt"

        coEvery { sftpManager.sanitizeFilename(newNameWithSpecial) } returns newNameWithSpecial
        coEvery { sftpManager.rename(sessionId, oldPath, newNameWithSpecial) } returns SshResult.Success(Unit)

        val result = renameFileUseCase(sessionId, oldPath, newNameWithSpecial)

        assertTrue(result is SshResult.Success)
    }

    // ── Error cases ──────────────────────────────────────────────────────────────

    @Test
    fun `invoke returns error when new name is empty`() = runTest {
        val sessionId = "session-1"
        val oldPath = "/home/user/oldfile.txt"
        val newName = ""

        val result = renameFileUseCase(sessionId, oldPath, newName)

        assertTrue(result is SshResult.Error)
        assertEquals(SshErrorCode.UNKNOWN, (result as SshResult.Error).code)
        assertEquals("Le nom ne peut pas être vide", result.message)
    }

    @Test
    fun `invoke returns error when new name is blank`() = runTest {
        val sessionId = "session-1"
        val oldPath = "/home/user/oldfile.txt"
        val newName = "   "  // Only whitespace

        val result = renameFileUseCase(sessionId, oldPath, newName)

        assertTrue(result is SshResult.Error)
        assertEquals(SshErrorCode.UNKNOWN, (result as SshResult.Error).code)
        assertEquals("Le nom ne peut pas être vide", result.message)
    }

    @Test
    fun `invoke returns error when new name contains forward slashes`() = runTest {
        val sessionId = "session-1"
        val oldPath = "/home/user/oldfile.txt"
        val badName = "path/to/newfile.txt"

        coEvery { sftpManager.sanitizeFilename(badName) } returns null

        val result = renameFileUseCase(sessionId, oldPath, badName)

        assertTrue(result is SshResult.Error)
        assertEquals(SshErrorCode.UNKNOWN, (result as SshResult.Error).code)
        assertEquals("Nom de fichier invalide", result.message)
    }

    @Test
    fun `invoke returns error when new name contains only path separators`() = runTest {
        val sessionId = "session-1"
        val oldPath = "/home/user/oldfile.txt"
        val badName = "/\\\u0000"

        coEvery { sftpManager.sanitizeFilename(badName) } returns null

        val result = renameFileUseCase(sessionId, oldPath, badName)

        assertTrue(result is SshResult.Error)
        assertEquals("Nom de fichier invalide", (result as SshResult.Error).message)
    }

    @Test
    fun `invoke delegates sftpManager error when file already exists`() = runTest {
        val sessionId = "session-1"
        val oldPath = "/home/user/oldfile.txt"
        val newName = "existing.txt"

        coEvery { sftpManager.sanitizeFilename(newName) } returns newName
        coEvery { sftpManager.rename(sessionId, oldPath, newName) } returns
            SshResult.Error(SshErrorCode.UNKNOWN, "Un fichier portant ce nom existe déjà")

        val result = renameFileUseCase(sessionId, oldPath, newName)

        assertTrue(result is SshResult.Error)
        assertEquals("Un fichier portant ce nom existe déjà", (result as SshResult.Error).message)
    }

    @Test
    fun `invoke delegates sftpManager error when permission denied`() = runTest {
        val sessionId = "session-1"
        val oldPath = "/root/oldfile.txt"
        val newName = "newfile.txt"

        coEvery { sftpManager.sanitizeFilename(newName) } returns newName
        coEvery { sftpManager.rename(sessionId, oldPath, newName) } returns
            SshResult.Error(SshErrorCode.UNKNOWN, "Permission refusée")

        val result = renameFileUseCase(sessionId, oldPath, newName)

        assertTrue(result is SshResult.Error)
        assertEquals("Permission refusée", (result as SshResult.Error).message)
    }

    @Test
    fun `invoke handles sanitizeFilename returning empty string after trimming`() = runTest {
        val sessionId = "session-1"
        val oldPath = "/home/user/oldfile.txt"
        val newName = "\u0000\u0001"  // Only control characters

        coEvery { sftpManager.sanitizeFilename(newName) } returns null

        val result = renameFileUseCase(sessionId, oldPath, newName)

        assertTrue(result is SshResult.Error)
        assertEquals("Nom de fichier invalide", (result as SshResult.Error).message)
    }

    @Test
    fun `invoke preserves path with multiple segments`() = runTest {
        val sessionId = "session-1"
        val oldPath = "/home/user/deep/nested/file.txt"
        val newName = "renamed.txt"

        coEvery { sftpManager.sanitizeFilename(newName) } returns newName
        coEvery { sftpManager.rename(sessionId, oldPath, newName) } returns SshResult.Success(Unit)

        val result = renameFileUseCase(sessionId, oldPath, newName)

        assertTrue(result is SshResult.Success)
        coVerify { sftpManager.rename(sessionId, oldPath, newName) }
    }

    @Test
    fun `invoke handles connection lost error`() = runTest {
        val sessionId = "session-1"
        val oldPath = "/home/user/oldfile.txt"
        val newName = "newfile.txt"

        coEvery { sftpManager.sanitizeFilename(newName) } returns newName
        coEvery { sftpManager.rename(sessionId, oldPath, newName) } returns
            SshResult.Error(SshErrorCode.UNKNOWN, "Connexion SFTP perdue")

        val result = renameFileUseCase(sessionId, oldPath, newName)

        assertTrue(result is SshResult.Error)
        assertEquals("Connexion SFTP perdue", (result as SshResult.Error).message)
    }
}
