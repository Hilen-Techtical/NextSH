// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.domain.usecase

import fr.techtical.nextsh.core.ssh.SftpManager
import fr.techtical.nextsh.domain.model.SftpFile
import fr.techtical.nextsh.domain.model.SortOrder
import fr.techtical.nextsh.domain.model.SshErrorCode
import fr.techtical.nextsh.shared.domain.model.SshResult
import io.mockk.coEvery
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MockKExtension::class)
class ListDirectoryUseCaseTest {

    @MockK
    private lateinit var sftpManager: SftpManager

    private lateinit var useCase: ListDirectoryUseCase

    private fun createTestFiles(): List<SftpFile> = listOf(
        SftpFile(name = "file1.txt", path = "/home/user/file1.txt", size = 100L, permissions = 420, isDirectory = false, modifiedAt = 1000L),
        SftpFile(name = "file2.txt", path = "/home/user/file2.txt", size = 200L, permissions = 420, isDirectory = false, modifiedAt = 2000L),
        SftpFile(name = "documents", path = "/home/user/documents", size = 0L, permissions = 493, isDirectory = true, modifiedAt = 1500L),
        SftpFile(name = ".hidden", path = "/home/user/.hidden", size = 50L, permissions = 420, isDirectory = false, modifiedAt = 500L),
        SftpFile(name = "subdir", path = "/home/user/subdir", size = 0L, permissions = 493, isDirectory = true, modifiedAt = 3000L)
    )

    @BeforeEach
    fun setUp() {
        useCase = ListDirectoryUseCase(sftpManager)
    }

    @Test
    fun `invoke filters hidden files when showHidden is false`() = runTest {
        val sessionId = "session-1"
        val path = "/home/user"
        val testFiles = createTestFiles()

        coEvery { sftpManager.listDirectory(any(), any()) } returns SshResult.Success(testFiles)

        val result = useCase.invoke(sessionId, path, SortOrder.NAME_ASC, showHidden = false)

        assertTrue(result is SshResult.Success)
        val files = (result as SshResult.Success).data
        assertEquals(4, files.size)
        assertFalse(files.any { it.name == ".hidden" })
    }

    @Test
    fun `invoke includes hidden files when showHidden is true`() = runTest {
        val sessionId = "session-1"
        val path = "/home/user"
        val testFiles = createTestFiles()

        coEvery { sftpManager.listDirectory(any(), any()) } returns SshResult.Success(testFiles)

        val result = useCase.invoke(sessionId, path, SortOrder.NAME_ASC, showHidden = true)

        assertTrue(result is SshResult.Success)
        val files = (result as SshResult.Success).data
        assertEquals(5, files.size)
        assertTrue(files.any { it.name == ".hidden" })
    }

    @Test
    fun `invoke sorts directories before files`() = runTest {
        val sessionId = "session-1"
        val path = "/home/user"
        val testFiles = createTestFiles()

        coEvery { sftpManager.listDirectory(any(), any()) } returns SshResult.Success(testFiles)

        val result = useCase.invoke(sessionId, path, SortOrder.NAME_ASC, showHidden = false)

        assertTrue(result is SshResult.Success)
        val files = (result as SshResult.Success).data
        assertEquals(4, files.size)
        val dirCount = files.takeWhile { it.isDirectory }.size
        assertEquals(2, dirCount)
        assertTrue(files[dirCount].name in setOf("file1.txt", "file2.txt"))
    }

    @Test
    fun `invoke sorts by name ascending`() = runTest {
        val sessionId = "session-1"
        val path = "/home/user"
        val testFiles = createTestFiles()

        coEvery { sftpManager.listDirectory(any(), any()) } returns SshResult.Success(testFiles)

        val result = useCase.invoke(sessionId, path, SortOrder.NAME_ASC, showHidden = false)

        assertTrue(result is SshResult.Success)
        val files = (result as SshResult.Success).data
        assertEquals("documents", files[0].name)
        assertEquals("subdir", files[1].name)
        assertEquals("file1.txt", files[2].name)
        assertEquals("file2.txt", files[3].name)
    }

    @Test
    fun `invoke sorts by name descending`() = runTest {
        val sessionId = "session-1"
        val path = "/home/user"
        val testFiles = createTestFiles()

        coEvery { sftpManager.listDirectory(any(), any()) } returns SshResult.Success(testFiles)

        val result = useCase.invoke(sessionId, path, SortOrder.NAME_DESC, showHidden = false)

        assertTrue(result is SshResult.Success)
        val files = (result as SshResult.Success).data
        assertEquals("subdir", files[0].name)
        assertEquals("documents", files[1].name)
        assertEquals("file2.txt", files[2].name)
        assertEquals("file1.txt", files[3].name)
    }

    @Test
    fun `invoke sorts by size ascending`() = runTest {
        val sessionId = "session-1"
        val path = "/home/user"
        val testFiles = createTestFiles()

        coEvery { sftpManager.listDirectory(any(), any()) } returns SshResult.Success(testFiles)

        val result = useCase.invoke(sessionId, path, SortOrder.SIZE_ASC, showHidden = false)

        assertTrue(result is SshResult.Success)
        val files = (result as SshResult.Success).data
        assertEquals("documents", files[0].name)
        assertEquals("subdir", files[1].name)
        assertEquals(100L, files[2].size)
        assertEquals(200L, files[3].size)
    }

    @Test
    fun `invoke sorts by size descending`() = runTest {
        val sessionId = "session-1"
        val path = "/home/user"
        val testFiles = createTestFiles()

        coEvery { sftpManager.listDirectory(any(), any()) } returns SshResult.Success(testFiles)

        val result = useCase.invoke(sessionId, path, SortOrder.SIZE_DESC, showHidden = false)

        assertTrue(result is SshResult.Success)
        val files = (result as SshResult.Success).data
        // Directories first (both size 0), then files by descending size
        assertTrue(files[0].isDirectory)
        assertTrue(files[1].isDirectory)
        assertEquals(200L, files[2].size)
        assertEquals(100L, files[3].size)
    }

    @Test
    fun `invoke sorts by date ascending`() = runTest {
        val sessionId = "session-1"
        val path = "/home/user"
        val testFiles = createTestFiles()

        coEvery { sftpManager.listDirectory(any(), any()) } returns SshResult.Success(testFiles)

        val result = useCase.invoke(sessionId, path, SortOrder.DATE_ASC, showHidden = false)

        assertTrue(result is SshResult.Success)
        val files = (result as SshResult.Success).data
        assertEquals("documents", files[0].name)
        assertEquals("subdir", files[1].name)
        assertEquals(1000L, files[2].modifiedAt)
        assertEquals(2000L, files[3].modifiedAt)
    }

    @Test
    fun `invoke sorts by date descending`() = runTest {
        val sessionId = "session-1"
        val path = "/home/user"
        val testFiles = createTestFiles()

        coEvery { sftpManager.listDirectory(any(), any()) } returns SshResult.Success(testFiles)

        val result = useCase.invoke(sessionId, path, SortOrder.DATE_DESC, showHidden = false)

        assertTrue(result is SshResult.Success)
        val files = (result as SshResult.Success).data
        assertEquals("subdir", files[0].name)
        assertEquals("documents", files[1].name)
        assertEquals(2000L, files[2].modifiedAt)
        assertEquals(1000L, files[3].modifiedAt)
    }

    @Test
    fun `invoke returns empty list for empty directory`() = runTest {
        val sessionId = "session-1"
        val path = "/empty"

        coEvery { sftpManager.listDirectory(any(), any()) } returns SshResult.Success(emptyList())

        val result = useCase.invoke(sessionId, path, SortOrder.NAME_ASC, showHidden = false)

        assertTrue(result is SshResult.Success)
        assertTrue((result as SshResult.Success).data.isEmpty())
    }

    @Test
    fun `invoke propagates SFTP error correctly`() = runTest {
        val sessionId = "session-1"
        val path = "/nonexistent"
        val error = SshResult.Error(SshErrorCode.UNKNOWN, "No such directory")

        coEvery { sftpManager.listDirectory(any(), any()) } returns error

        val result = useCase.invoke(sessionId, path, SortOrder.NAME_ASC, showHidden = false)

        assertTrue(result is SshResult.Error)
        val errorResult = result as SshResult.Error
        assertEquals(SshErrorCode.UNKNOWN, errorResult.code)
        assertEquals("No such directory", errorResult.message)
    }

    @Test
    fun `invoke propagates permission denied error`() = runTest {
        val sessionId = "session-1"
        val path = "/root"
        val error = SshResult.Error(SshErrorCode.AUTH_FAILED, "Permission denied")

        coEvery { sftpManager.listDirectory(any(), any()) } returns error

        val result = useCase.invoke(sessionId, path, SortOrder.NAME_ASC, showHidden = false)

        assertTrue(result is SshResult.Error)
        val errorResult = result as SshResult.Error
        assertEquals(SshErrorCode.AUTH_FAILED, errorResult.code)
    }

    @Test
    fun `invoke sorts names case-insensitively`() = runTest {
        val sessionId = "session-1"
        val path = "/home/user"
        val mixedCaseFiles = listOf(
            SftpFile(name = "Zebra.txt", path = "/home/user/Zebra.txt", size = 100L, permissions = 420, isDirectory = false, modifiedAt = 1000L),
            SftpFile(name = "apple.txt", path = "/home/user/apple.txt", size = 100L, permissions = 420, isDirectory = false, modifiedAt = 1000L),
            SftpFile(name = "BANANA.txt", path = "/home/user/BANANA.txt", size = 100L, permissions = 420, isDirectory = false, modifiedAt = 1000L)
        )

        coEvery { sftpManager.listDirectory(any(), any()) } returns SshResult.Success(mixedCaseFiles)

        val result = useCase.invoke(sessionId, path, SortOrder.NAME_ASC, showHidden = false)

        assertTrue(result is SshResult.Success)
        val files = (result as SshResult.Success).data
        assertEquals("apple.txt", files[0].name)
        assertEquals("BANANA.txt", files[1].name)
        assertEquals("Zebra.txt", files[2].name)
    }

    @Test
    fun `invoke filters hidden and sorts correctly`() = runTest {
        val sessionId = "session-1"
        val path = "/home/user"
        val testFiles = createTestFiles()
        val filesWithHidden = testFiles + listOf(
            SftpFile(name = ".config", path = "/home/user/.config", size = 0L, permissions = 493, isDirectory = true, modifiedAt = 500L),
            SftpFile(name = ".bashrc", path = "/home/user/.bashrc", size = 25L, permissions = 420, isDirectory = false, modifiedAt = 600L)
        )

        coEvery { sftpManager.listDirectory(any(), any()) } returns SshResult.Success(filesWithHidden)

        val result = useCase.invoke(sessionId, path, SortOrder.NAME_ASC, showHidden = false)

        assertTrue(result is SshResult.Success)
        val files = (result as SshResult.Success).data
        assertEquals(4, files.size)
        assertTrue(files.none { it.name.startsWith('.') })
    }

    @Test
    fun `invoke uses default parameters correctly`() = runTest {
        val sessionId = "session-1"
        val path = "/home/user"
        val testFiles = createTestFiles()

        coEvery { sftpManager.listDirectory(any(), any()) } returns SshResult.Success(testFiles)

        val result = useCase.invoke(sessionId, path)

        assertTrue(result is SshResult.Success)
        val files = (result as SshResult.Success).data
        assertEquals(4, files.size)
        assertFalse(files.any { it.name.startsWith('.') })
    }
}
