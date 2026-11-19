// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.domain.usecase

import fr.techtical.nextsh.core.ssh.SFTP_CREATE_FILE_FAILED_MESSAGE
import fr.techtical.nextsh.core.ssh.SFTP_NAME_EMPTY_MESSAGE
import fr.techtical.nextsh.core.ssh.SFTP_NAME_EXISTS_MESSAGE
import fr.techtical.nextsh.core.ssh.SFTP_NAME_INVALID_MESSAGE
import fr.techtical.nextsh.core.ssh.SFTP_PERMISSION_DENIED_MESSAGE
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
class CreateFileUseCaseTest {

    @MockK
    private lateinit var sftpManager: SftpManager

    private lateinit var createFileUseCase: CreateFileUseCase

    @BeforeEach
    fun setUp() {
        createFileUseCase = CreateFileUseCase(sftpManager)
    }

    // ── Happy path ───────────────────────────────────────────────────────────────

    @Test
    fun `invoke succeeds when file name is valid`() = runTest {
        val sessionId = "session-1"
        val parentPath = "/home/user"
        val fileName = "notes.txt"

        coEvery { sftpManager.createFile(sessionId, parentPath, fileName) } returns SshResult.Success(Unit)

        val result = createFileUseCase(sessionId, parentPath, fileName)

        assertTrue(result is SshResult.Success)
        coVerify(exactly = 1) { sftpManager.createFile(sessionId, parentPath, fileName) }
    }

    @Test
    fun `invoke hands the parent path to the manager untouched, the manager resolves it`() = runTest {
        val sessionId = "session-1"
        val parentPath = "/home/user/"  // Trailing slash
        val fileName = "notes.txt"

        coEvery { sftpManager.createFile(sessionId, parentPath, fileName) } returns SshResult.Success(Unit)

        val result = createFileUseCase(sessionId, parentPath, fileName)

        assertTrue(result is SshResult.Success)
        coVerify(exactly = 1) { sftpManager.createFile(sessionId, parentPath, fileName) }
    }

    @Test
    fun `invoke hands the root as the parent path`() = runTest {
        val sessionId = "session-1"

        coEvery { sftpManager.createFile(sessionId, "/", "notes.txt") } returns SshResult.Success(Unit)

        val result = createFileUseCase(sessionId, "/", "notes.txt")

        assertTrue(result is SshResult.Success)
        coVerify(exactly = 1) { sftpManager.createFile(sessionId, "/", "notes.txt") }
    }

    @Test
    fun `invoke removes the spaces around the name, and only those`() = runTest {
        coEvery { sftpManager.createFile("session-1", "/home/user", "notes.txt") } returns SshResult.Success(Unit)

        val result = createFileUseCase("session-1", "/home/user", "  notes.txt  ")

        assertTrue(result is SshResult.Success)
        coVerify(exactly = 1) { sftpManager.createFile("session-1", "/home/user", "notes.txt") }
    }

    @Test
    fun `invoke gives the manager a name with a plus, a percent or an encoded slash exactly as typed`() = runTest {
        listOf("a+b.txt", "50%.txt", "a%2Fb", "..%2F..%2Ftmp%2Fx").forEach { fileName ->
            coEvery { sftpManager.createFile("session-1", "/home/user", fileName) } returns SshResult.Success(Unit)

            val result = createFileUseCase("session-1", "/home/user", fileName)

            assertTrue(result is SshResult.Success, fileName)
            coVerify(exactly = 1) { sftpManager.createFile("session-1", "/home/user", fileName) }
        }
    }

    // ── Error cases ──────────────────────────────────────────────────────────────

    @Test
    fun `invoke returns the empty name text when the name is empty and asks the manager nothing`() = runTest {
        val result = createFileUseCase("session-1", "/home/user", "")

        assertTrue(result is SshResult.Error)
        assertEquals(SshErrorCode.UNKNOWN, (result as SshResult.Error).code)
        assertEquals(SFTP_NAME_EMPTY_MESSAGE, result.message)
        coVerify(exactly = 0) { sftpManager.createFile(any(), any(), any()) }
    }

    @Test
    fun `invoke returns the empty name text when the name is blank`() = runTest {
        val result = createFileUseCase("session-1", "/home/user", "   ")

        assertTrue(result is SshResult.Error)
        assertEquals(SFTP_NAME_EMPTY_MESSAGE, (result as SshResult.Error).message)
        coVerify(exactly = 0) { sftpManager.createFile(any(), any(), any()) }
    }

    @Test
    fun `invoke returns the invalid name text when the name holds only unsafe characters`() = runTest {
        val fileName = "/\\\u0000"  // Unsafe characters only

        val result = createFileUseCase("session-1", "/home/user", fileName)

        assertTrue(result is SshResult.Error)
        assertEquals(SshErrorCode.UNKNOWN, (result as SshResult.Error).code)
        // The text of a file name, not the one of a folder name.
        assertEquals(SFTP_NAME_INVALID_MESSAGE, result.message)
        coVerify(exactly = 0) { sftpManager.createFile(any(), any(), any()) }
    }

    @Test
    fun `invoke refuses a name that holds a forbidden character instead of creating it without that character`() = runTest {
        // The name used to be cleaned: "a/b" became "ab", "../x" became "..x". Creating "ab" when "a/b"
        // was typed would be a file the user did not ask for.
        listOf("a/b", "../x", "a\\b", "a\tb", ".", "..", " .. ", "x\u0085y", "a".repeat(256)).forEach { fileName ->
            val result = createFileUseCase("session-1", "/home/user", fileName)

            assertTrue(result is SshResult.Error, fileName)
            assertEquals(SshErrorCode.UNKNOWN, (result as SshResult.Error).code, fileName)
            assertEquals(SFTP_NAME_INVALID_MESSAGE, result.message, fileName)
        }
        coVerify(exactly = 0) { sftpManager.createFile(any(), any(), any()) }
    }

    @Test
    fun `invoke refuses a forbidden character next to a space at the border just as well`() = runTest {
        // " a/ " trimmed is "a/", which is no bare name: refused, not turned into "a".
        val result = createFileUseCase("session-1", "/home/user", " a/ ")

        assertTrue(result is SshResult.Error)
        assertEquals(SFTP_NAME_INVALID_MESSAGE, (result as SshResult.Error).message)
        coVerify(exactly = 0) { sftpManager.createFile(any(), any(), any()) }
    }

    @Test
    fun `invoke delegates the error of a name that is already taken`() = runTest {
        coEvery { sftpManager.createFile("session-1", "/home/user", "existing.txt") } returns
            SshResult.Error(SshErrorCode.UNKNOWN, SFTP_NAME_EXISTS_MESSAGE)

        val result = createFileUseCase("session-1", "/home/user", "existing.txt")

        assertTrue(result is SshResult.Error)
        assertEquals(SFTP_NAME_EXISTS_MESSAGE, (result as SshResult.Error).message)
    }

    @Test
    fun `invoke delegates the permission error`() = runTest {
        coEvery { sftpManager.createFile("session-1", "/root", "notes.txt") } returns
            SshResult.Error(SshErrorCode.UNKNOWN, SFTP_PERMISSION_DENIED_MESSAGE)

        val result = createFileUseCase("session-1", "/root", "notes.txt")

        assertTrue(result is SshResult.Error)
        assertEquals(SFTP_PERMISSION_DENIED_MESSAGE, (result as SshResult.Error).message)
    }

    @Test
    fun `invoke delegates the failure text of the manager`() = runTest {
        coEvery { sftpManager.createFile("session-1", "/home/user", "notes.txt") } returns
            SshResult.Error(SshErrorCode.UNKNOWN, SFTP_CREATE_FILE_FAILED_MESSAGE)

        val result = createFileUseCase("session-1", "/home/user", "notes.txt")

        assertTrue(result is SshResult.Error)
        assertEquals(SFTP_CREATE_FILE_FAILED_MESSAGE, (result as SshResult.Error).message)
    }
}
