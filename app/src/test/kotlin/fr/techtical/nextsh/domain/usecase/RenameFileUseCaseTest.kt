// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.domain.usecase

import fr.techtical.nextsh.core.ssh.SFTP_ENTRY_NAME_INVALID_MESSAGE
import fr.techtical.nextsh.core.ssh.SFTP_NAME_EMPTY_MESSAGE
import fr.techtical.nextsh.core.ssh.SFTP_NAME_EXISTS_MESSAGE
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

        coEvery { sftpManager.rename(sessionId, oldPath, newName) } returns SshResult.Success(Unit)

        val result = renameFileUseCase(sessionId, oldPath, newName)

        assertTrue(result is SshResult.Success)
        coVerify(exactly = 1) { sftpManager.rename(sessionId, oldPath, newName) }
    }

    @Test
    fun `invoke removes the spaces around the new name, and only those`() = runTest {
        val sessionId = "session-1"
        val oldPath = "/home/user/oldfile.txt"

        coEvery { sftpManager.rename(sessionId, oldPath, "newfile.txt") } returns SshResult.Success(Unit)

        val result = renameFileUseCase(sessionId, oldPath, "  newfile.txt  ")

        assertTrue(result is SshResult.Success)
        coVerify(exactly = 1) { sftpManager.rename(sessionId, oldPath, "newfile.txt") }
    }

    @Test
    fun `invoke renames file at root directory`() = runTest {
        val sessionId = "session-1"
        val oldPath = "/oldfile.txt"
        val newName = "newfile.txt"

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

        coEvery { sftpManager.rename(sessionId, oldPath, newNameWithSpecial) } returns SshResult.Success(Unit)

        val result = renameFileUseCase(sessionId, oldPath, newNameWithSpecial)

        assertTrue(result is SshResult.Success)
    }

    @Test
    fun `invoke gives the manager a name with a plus, a percent or an encoded slash exactly as typed`() = runTest {
        listOf("x+y", "100%", "a%2Fb", "..%2F..%2Ftmp%2Fx", "..x", "x..", "...", ".hidden").forEach { newName ->
            coEvery { sftpManager.rename("session-1", "/home/user/old", newName) } returns SshResult.Success(Unit)

            val result = renameFileUseCase("session-1", "/home/user/old", newName)

            assertTrue(result is SshResult.Success, newName)
            coVerify(exactly = 1) { sftpManager.rename("session-1", "/home/user/old", newName) }
        }
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
        assertEquals(SFTP_NAME_EMPTY_MESSAGE, result.message)
        coVerify(exactly = 0) { sftpManager.rename(any(), any(), any()) }
    }

    @Test
    fun `invoke returns error when new name is blank`() = runTest {
        val sessionId = "session-1"
        val oldPath = "/home/user/oldfile.txt"
        val newName = "   "  // Only whitespace

        val result = renameFileUseCase(sessionId, oldPath, newName)

        assertTrue(result is SshResult.Error)
        assertEquals(SshErrorCode.UNKNOWN, (result as SshResult.Error).code)
        assertEquals(SFTP_NAME_EMPTY_MESSAGE, result.message)
        coVerify(exactly = 0) { sftpManager.rename(any(), any(), any()) }
    }

    @Test
    fun `invoke refuses a backslash instead of renaming without it`() = runTest {
        // The name used to be cleaned: "new\file.txt" became "newfile.txt", a name nobody asked for.
        val result = renameFileUseCase("session-1", "/home/user/oldfile.txt", "new\\file.txt")

        assertTrue(result is SshResult.Error)
        assertEquals(SshErrorCode.UNKNOWN, (result as SshResult.Error).code)
        assertEquals(SFTP_ENTRY_NAME_INVALID_MESSAGE, result.message)
        coVerify(exactly = 0) { sftpManager.rename(any(), any(), any()) }
    }

    @Test
    fun `invoke returns error when new name contains forward slashes`() = runTest {
        val sessionId = "session-1"
        val oldPath = "/home/user/oldfile.txt"
        val badName = "path/to/newfile.txt"

        val result = renameFileUseCase(sessionId, oldPath, badName)

        assertTrue(result is SshResult.Error)
        assertEquals(SshErrorCode.UNKNOWN, (result as SshResult.Error).code)
        assertEquals(SFTP_ENTRY_NAME_INVALID_MESSAGE, result.message)
        coVerify(exactly = 0) { sftpManager.rename(any(), any(), any()) }
    }

    @Test
    fun `invoke returns error when new name contains only path separators`() = runTest {
        val sessionId = "session-1"
        val oldPath = "/home/user/oldfile.txt"
        val badName = "/\\\u0000"

        val result = renameFileUseCase(sessionId, oldPath, badName)

        assertTrue(result is SshResult.Error)
        assertEquals(SFTP_ENTRY_NAME_INVALID_MESSAGE, (result as SshResult.Error).message)
        coVerify(exactly = 0) { sftpManager.rename(any(), any(), any()) }
    }

    @Test
    fun `invoke returns error when new name is only control characters`() = runTest {
        val sessionId = "session-1"
        val oldPath = "/home/user/oldfile.txt"
        val newName = "\u0000\u0001"  // Only control characters

        val result = renameFileUseCase(sessionId, oldPath, newName)

        assertTrue(result is SshResult.Error)
        assertEquals(SFTP_ENTRY_NAME_INVALID_MESSAGE, (result as SshResult.Error).message)
        coVerify(exactly = 0) { sftpManager.rename(any(), any(), any()) }
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
            "a\tb",
            "x\u007Fy",
            "x\u0085y",
            "a".repeat(256),
        )

        refused.forEach { newName ->
            val result = renameFileUseCase("session-1", "/home/user/oldfile.txt", newName)

            assertTrue(result is SshResult.Error, newName)
            assertEquals(SFTP_ENTRY_NAME_INVALID_MESSAGE, (result as SshResult.Error).message, newName)
        }
        coVerify(exactly = 0) { sftpManager.rename(any(), any(), any()) }
    }

    @Test
    fun `invoke delegates sftpManager error when file already exists`() = runTest {
        val sessionId = "session-1"
        val oldPath = "/home/user/oldfile.txt"
        val newName = "existing.txt"

        coEvery { sftpManager.rename(sessionId, oldPath, newName) } returns
            SshResult.Error(SshErrorCode.UNKNOWN, SFTP_NAME_EXISTS_MESSAGE)

        val result = renameFileUseCase(sessionId, oldPath, newName)

        assertTrue(result is SshResult.Error)
        assertEquals(SFTP_NAME_EXISTS_MESSAGE, (result as SshResult.Error).message)
    }

    @Test
    fun `invoke delegates sftpManager error when permission denied`() = runTest {
        val sessionId = "session-1"
        val oldPath = "/root/oldfile.txt"
        val newName = "newfile.txt"

        coEvery { sftpManager.rename(sessionId, oldPath, newName) } returns
            SshResult.Error(SshErrorCode.UNKNOWN, "Permission refusée")

        val result = renameFileUseCase(sessionId, oldPath, newName)

        assertTrue(result is SshResult.Error)
        assertEquals("Permission refusée", (result as SshResult.Error).message)
    }

    @Test
    fun `invoke preserves path with multiple segments`() = runTest {
        val sessionId = "session-1"
        val oldPath = "/home/user/deep/nested/file.txt"
        val newName = "renamed.txt"

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

        coEvery { sftpManager.rename(sessionId, oldPath, newName) } returns
            SshResult.Error(SshErrorCode.UNKNOWN, "Connexion SFTP perdue")

        val result = renameFileUseCase(sessionId, oldPath, newName)

        assertTrue(result is SshResult.Error)
        assertEquals("Connexion SFTP perdue", (result as SshResult.Error).message)
    }
}
