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
class CreateDirectoryUseCaseTest {

    @MockK
    private lateinit var sftpManager: SftpManager

    private lateinit var createDirectoryUseCase: CreateDirectoryUseCase

    @BeforeEach
    fun setUp() {
        createDirectoryUseCase = CreateDirectoryUseCase(sftpManager)
    }

    // ── Happy path ───────────────────────────────────────────────────────────────

    @Test
    fun `invoke succeeds when directory name is valid`() = runTest {
        val sessionId = "session-1"
        val parentPath = "/home/user"
        val dirName = "newdir"
        val expectedPath = "/home/user/newdir"

        coEvery { sftpManager.sanitizeFilename(dirName) } returns dirName
        coEvery { sftpManager.createDirectory(sessionId, expectedPath) } returns SshResult.Success(Unit)

        val result = createDirectoryUseCase(sessionId, parentPath, dirName)

        assertTrue(result is SshResult.Success)
        coVerify { sftpManager.createDirectory(sessionId, expectedPath) }
    }

    @Test
    fun `invoke trims trailing slash from parent path`() = runTest {
        val sessionId = "session-1"
        val parentPath = "/home/user/"  // Trailing slash
        val dirName = "newdir"
        val expectedPath = "/home/user/newdir"

        coEvery { sftpManager.sanitizeFilename(dirName) } returns dirName
        coEvery { sftpManager.createDirectory(sessionId, expectedPath) } returns SshResult.Success(Unit)

        val result = createDirectoryUseCase(sessionId, parentPath, dirName)

        assertTrue(result is SshResult.Success)
        coVerify { sftpManager.createDirectory(sessionId, expectedPath) }
    }

    @Test
    fun `invoke sanitizes filename before creating`() = runTest {
        val sessionId = "session-1"
        val parentPath = "/home/user"
        val dirName = "new\\dir"  // Backslash should be removed
        val sanitizedName = "newdir"
        val expectedPath = "/home/user/newdir"

        coEvery { sftpManager.sanitizeFilename(dirName) } returns sanitizedName
        coEvery { sftpManager.createDirectory(sessionId, expectedPath) } returns SshResult.Success(Unit)

        val result = createDirectoryUseCase(sessionId, parentPath, dirName)

        assertTrue(result is SshResult.Success)
        coVerify { sftpManager.sanitizeFilename(dirName) }
        coVerify { sftpManager.createDirectory(sessionId, expectedPath) }
    }

    // ── Error cases ──────────────────────────────────────────────────────────────

    @Test
    fun `invoke returns error when directory name is empty`() = runTest {
        val sessionId = "session-1"
        val parentPath = "/home/user"
        val dirName = ""

        val result = createDirectoryUseCase(sessionId, parentPath, dirName)

        assertTrue(result is SshResult.Error)
        assertEquals(SshErrorCode.UNKNOWN, (result as SshResult.Error).code)
        assertEquals("Le nom ne peut pas être vide", result.message)
    }

    @Test
    fun `invoke returns error when directory name is blank`() = runTest {
        val sessionId = "session-1"
        val parentPath = "/home/user"
        val dirName = "   "  // Only whitespace

        val result = createDirectoryUseCase(sessionId, parentPath, dirName)

        assertTrue(result is SshResult.Error)
        assertEquals(SshErrorCode.UNKNOWN, (result as SshResult.Error).code)
        assertEquals("Le nom ne peut pas être vide", result.message)
    }

    @Test
    fun `invoke returns error when sanitizeFilename returns null`() = runTest {
        val sessionId = "session-1"
        val parentPath = "/home/user"
        val dirName = "/\\\u0000"  // Unsafe characters only

        coEvery { sftpManager.sanitizeFilename(dirName) } returns null

        val result = createDirectoryUseCase(sessionId, parentPath, dirName)

        assertTrue(result is SshResult.Error)
        assertEquals(SshErrorCode.UNKNOWN, (result as SshResult.Error).code)
        assertEquals("Nom de dossier invalide", result.message)
    }

    @Test
    fun `invoke delegates sftpManager error`() = runTest {
        val sessionId = "session-1"
        val parentPath = "/home/user"
        val dirName = "existing"
        val expectedPath = "/home/user/existing"

        coEvery { sftpManager.sanitizeFilename(dirName) } returns dirName
        coEvery { sftpManager.createDirectory(sessionId, expectedPath) } returns
            SshResult.Error(SshErrorCode.UNKNOWN, "Un dossier portant ce nom existe déjà")

        val result = createDirectoryUseCase(sessionId, parentPath, dirName)

        assertTrue(result is SshResult.Error)
        assertEquals("Un dossier portant ce nom existe déjà", (result as SshResult.Error).message)
    }

    @Test
    fun `invoke handles root parent path`() = runTest {
        val sessionId = "session-1"
        val parentPath = "/"
        val dirName = "newdir"
        val expectedPath = "/newdir"

        coEvery { sftpManager.sanitizeFilename(dirName) } returns dirName
        coEvery { sftpManager.createDirectory(sessionId, expectedPath) } returns SshResult.Success(Unit)

        val result = createDirectoryUseCase(sessionId, parentPath, dirName)

        assertTrue(result is SshResult.Success)
        coVerify { sftpManager.createDirectory(sessionId, expectedPath) }
    }

    @Test
    fun `invoke handles permission denied error`() = runTest {
        val sessionId = "session-1"
        val parentPath = "/root"
        val dirName = "newdir"
        val expectedPath = "/root/newdir"

        coEvery { sftpManager.sanitizeFilename(dirName) } returns dirName
        coEvery { sftpManager.createDirectory(sessionId, expectedPath) } returns
            SshResult.Error(SshErrorCode.AUTH_FAILED, "Permission refusée")

        val result = createDirectoryUseCase(sessionId, parentPath, dirName)

        assertTrue(result is SshResult.Error)
        assertEquals(SshErrorCode.AUTH_FAILED, (result as SshResult.Error).code)
    }
}
