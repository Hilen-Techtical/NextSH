// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.domain.usecase

import fr.techtical.nextsh.core.ssh.SFTP_FOLDER_EXISTS_MESSAGE
import fr.techtical.nextsh.core.ssh.SFTP_FOLDER_NAME_INVALID_MESSAGE
import fr.techtical.nextsh.core.ssh.SFTP_NAME_EMPTY_MESSAGE
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

        coEvery { sftpManager.createDirectory(sessionId, parentPath, dirName) } returns SshResult.Success(Unit)

        val result = createDirectoryUseCase(sessionId, parentPath, dirName)

        assertTrue(result is SshResult.Success)
        coVerify(exactly = 1) { sftpManager.createDirectory(sessionId, parentPath, dirName) }
    }

    @Test
    fun `invoke hands the parent path to the manager untouched, the manager resolves it`() = runTest {
        val sessionId = "session-1"
        val parentPath = "/home/user/"  // Trailing slash
        val dirName = "newdir"

        coEvery { sftpManager.createDirectory(sessionId, parentPath, dirName) } returns SshResult.Success(Unit)

        val result = createDirectoryUseCase(sessionId, parentPath, dirName)

        assertTrue(result is SshResult.Success)
        coVerify(exactly = 1) { sftpManager.createDirectory(sessionId, parentPath, dirName) }
    }

    @Test
    fun `invoke hands the root as the parent path`() = runTest {
        coEvery { sftpManager.createDirectory("session-1", "/", "newdir") } returns SshResult.Success(Unit)

        val result = createDirectoryUseCase("session-1", "/", "newdir")

        assertTrue(result is SshResult.Success)
        coVerify(exactly = 1) { sftpManager.createDirectory("session-1", "/", "newdir") }
    }

    @Test
    fun `invoke removes the spaces around the name, and only those`() = runTest {
        coEvery { sftpManager.createDirectory("session-1", "/home/user", "newdir") } returns SshResult.Success(Unit)

        val result = createDirectoryUseCase("session-1", "/home/user", "  newdir  ")

        assertTrue(result is SshResult.Success)
        coVerify(exactly = 1) { sftpManager.createDirectory("session-1", "/home/user", "newdir") }
    }

    @Test
    fun `invoke gives the manager a name with a plus, a percent or an encoded slash exactly as typed`() = runTest {
        listOf("x+y", "100%", "a%2Fb", "..%2F..%2Ftmp%2Fx", "a b", "..x", "x..", "...", ".hidden").forEach { dirName ->
            coEvery { sftpManager.createDirectory("session-1", "/home/user", dirName) } returns SshResult.Success(Unit)

            val result = createDirectoryUseCase("session-1", "/home/user", dirName)

            assertTrue(result is SshResult.Success, dirName)
            coVerify(exactly = 1) { sftpManager.createDirectory("session-1", "/home/user", dirName) }
        }
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
        assertEquals(SFTP_NAME_EMPTY_MESSAGE, result.message)
        coVerify(exactly = 0) { sftpManager.createDirectory(any(), any(), any()) }
    }

    @Test
    fun `invoke returns error when directory name is blank`() = runTest {
        val sessionId = "session-1"
        val parentPath = "/home/user"
        val dirName = "   "  // Only whitespace

        val result = createDirectoryUseCase(sessionId, parentPath, dirName)

        assertTrue(result is SshResult.Error)
        assertEquals(SshErrorCode.UNKNOWN, (result as SshResult.Error).code)
        assertEquals(SFTP_NAME_EMPTY_MESSAGE, result.message)
        coVerify(exactly = 0) { sftpManager.createDirectory(any(), any(), any()) }
    }

    @Test
    fun `invoke refuses a backslash instead of creating the folder without it`() = runTest {
        // The name used to be cleaned: "new\dir" became "newdir", a folder nobody asked for.
        val result = createDirectoryUseCase("session-1", "/home/user", "new\\dir")

        assertTrue(result is SshResult.Error)
        assertEquals(SshErrorCode.UNKNOWN, (result as SshResult.Error).code)
        assertEquals(SFTP_FOLDER_NAME_INVALID_MESSAGE, result.message)
        coVerify(exactly = 0) { sftpManager.createDirectory(any(), any(), any()) }
    }

    @Test
    fun `invoke refuses every name that is not a bare name and asks the manager nothing`() = runTest {
        val refused = listOf(
            "a/b",
            "/x",
            "x/",
            "../x",
            ".",
            "..",
            " .. ",
            "/\\\u0000",
            "a\tb",
            "x\u0001y",
            "x\u007Fy",
            "x\u0085y",
            "a".repeat(256),
        )

        refused.forEach { dirName ->
            val result = createDirectoryUseCase("session-1", "/home/user", dirName)

            assertTrue(result is SshResult.Error, dirName)
            assertEquals(SFTP_FOLDER_NAME_INVALID_MESSAGE, (result as SshResult.Error).message, dirName)
        }
        coVerify(exactly = 0) { sftpManager.createDirectory(any(), any(), any()) }
    }

    @Test
    fun `invoke delegates sftpManager error`() = runTest {
        val sessionId = "session-1"
        val parentPath = "/home/user"
        val dirName = "existing"

        coEvery { sftpManager.createDirectory(sessionId, parentPath, dirName) } returns
            SshResult.Error(SshErrorCode.UNKNOWN, SFTP_FOLDER_EXISTS_MESSAGE)

        val result = createDirectoryUseCase(sessionId, parentPath, dirName)

        assertTrue(result is SshResult.Error)
        assertEquals(SFTP_FOLDER_EXISTS_MESSAGE, (result as SshResult.Error).message)
    }

    @Test
    fun `invoke handles permission denied error`() = runTest {
        val sessionId = "session-1"
        val parentPath = "/root"
        val dirName = "newdir"

        coEvery { sftpManager.createDirectory(sessionId, parentPath, dirName) } returns
            SshResult.Error(SshErrorCode.AUTH_FAILED, "Permission refusée")

        val result = createDirectoryUseCase(sessionId, parentPath, dirName)

        assertTrue(result is SshResult.Error)
        assertEquals(SshErrorCode.AUTH_FAILED, (result as SshResult.Error).code)
    }
}
