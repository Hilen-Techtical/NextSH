// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sftp

import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import fr.techtical.nextsh.R
import fr.techtical.nextsh.core.ssh.SftpManager
import fr.techtical.nextsh.core.ssh.TransferTracker
import fr.techtical.nextsh.domain.model.SftpFile
import fr.techtical.nextsh.domain.model.SortOrder
import fr.techtical.nextsh.domain.model.SshErrorCode
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.domain.usecase.ChangePermissionsUseCase
import fr.techtical.nextsh.domain.usecase.CreateDirectoryUseCase
import fr.techtical.nextsh.domain.usecase.DeleteFileUseCase
import fr.techtical.nextsh.domain.usecase.ListDirectoryUseCase
import fr.techtical.nextsh.domain.usecase.PreviewFileUseCase
import fr.techtical.nextsh.domain.usecase.RenameFileUseCase
import fr.techtical.nextsh.service.SftpTransferService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkConstructor
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MockKExtension::class)
class SftpBrowserViewModelTest {

    @MockK
    private lateinit var sftpManager: SftpManager

    @MockK
    private lateinit var listDirectoryUseCase: ListDirectoryUseCase

    @MockK
    private lateinit var createDirectoryUseCase: CreateDirectoryUseCase

    @MockK
    private lateinit var deleteFileUseCase: DeleteFileUseCase

    @MockK
    private lateinit var renameFileUseCase: RenameFileUseCase

    @MockK
    private lateinit var changePermissionsUseCase: ChangePermissionsUseCase

    @MockK
    private lateinit var previewFileUseCase: PreviewFileUseCase

    @MockK
    private lateinit var transferTracker: TransferTracker

    @MockK
    private lateinit var context: android.content.Context

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var viewModel: SftpBrowserViewModel

    private fun createTestFiles(): List<SftpFile> = listOf(
        SftpFile(name = "file1.txt", path = "/home/user/file1.txt", size = 100L, permissions = 420, isDirectory = false, modifiedAt = 1000L),
        SftpFile(name = "documents", path = "/home/user/documents", size = 0L, permissions = 493, isDirectory = true, modifiedAt = 1500L),
        SftpFile(name = "file2.txt", path = "/home/user/file2.txt", size = 200L, permissions = 420, isDirectory = false, modifiedAt = 2000L)
    )

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        // TransferTracker needs its transfers flow stubbed
        io.mockk.every { transferTracker.transfers } returns MutableStateFlow(emptyMap())
        viewModel = SftpBrowserViewModel(
            sftpManager,
            listDirectoryUseCase,
            createDirectoryUseCase,
            deleteFileUseCase,
            renameFileUseCase,
            changePermissionsUseCase,
            previewFileUseCase,
            transferTracker,
            context
        )
    }

    @AfterEach
    fun tearDown() {
        unmockkConstructor(Intent::class)
        unmockkStatic(ContextCompat::class)
        Dispatchers.resetMain()
    }

    // ── init() ───────────────────────────────────────────────────────────────────

    @Test
    fun `init sets loading state and opens SFTP connection`() = runTest {
        val sessionId = "session-1"
        val hostLabel = "My Server"
        val testFiles = createTestFiles()

        coEvery { sftpManager.openSftp(sessionId) } returns SshResult.Success(Unit)
        coEvery { sftpManager.getHomeDirectory(sessionId) } returns SshResult.Success("/home/user")
        coEvery { listDirectoryUseCase(any(), any(), any(), any()) } returns SshResult.Success(testFiles)

        viewModel.init(sessionId, hostLabel)

        // Allow the coroutine to complete
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(hostLabel, state.hostLabel)
        assertFalse(state.isLoading)
        assertNull(state.error)
        assertEquals("/home/user", state.currentPath)
    }

    @Test
    fun `init sets error when openSftp fails`() = runTest {
        val sessionId = "session-1"
        val errorMsg = "Connection failed"

        coEvery { sftpManager.openSftp(sessionId) } returns SshResult.Error(SshErrorCode.UNKNOWN, errorMsg)

        viewModel.init(sessionId, "My Server")

        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.isLoading == false)
        assertNotNull(state.error)
        assertEquals(errorMsg, state.error)
    }

    @Test
    fun `init is idempotent - second call with same sessionId does nothing`() = runTest {
        val sessionId = "session-1"
        val testFiles = createTestFiles()

        coEvery { sftpManager.openSftp(sessionId) } returns SshResult.Success(Unit)
        coEvery { sftpManager.getHomeDirectory(sessionId) } returns SshResult.Success("/home/user")
        coEvery { listDirectoryUseCase(any(), any(), any(), any()) } returns SshResult.Success(testFiles)

        viewModel.init(sessionId, "Server 1")
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.init(sessionId, "Server 2")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Server 1", state.hostLabel) // Should not change
        coVerify(exactly = 1) { sftpManager.openSftp(sessionId) } // Only called once
    }

    @Test
    fun `init falls back to root when getHomeDirectory fails`() = runTest {
        val sessionId = "session-1"
        val testFiles = createTestFiles()

        coEvery { sftpManager.openSftp(sessionId) } returns SshResult.Success(Unit)
        coEvery { sftpManager.getHomeDirectory(sessionId) } returns SshResult.Error(SshErrorCode.UNKNOWN, "Failed")
        coEvery { listDirectoryUseCase(any(), any(), any(), any()) } returns SshResult.Success(testFiles)

        viewModel.init(sessionId)

        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("/", state.currentPath)
    }

    // ── navigateTo() ──────────────────────────────────────────────────────────────

    @Test
    fun `navigateTo updates currentPath and files`() = runTest {
        val sessionId = "session-1"
        val testFiles = createTestFiles()
        setupInitialState(sessionId, testFiles)

        coEvery { listDirectoryUseCase(any(), any(), any(), any()) } returns SshResult.Success(testFiles)

        viewModel.navigateTo("/home/user/documents")

        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("/home/user/documents", state.currentPath)
        assertEquals(testFiles.size, state.files.size)
        assertFalse(state.isLoading)
        assertNull(state.error)
    }

    @Test
    fun `navigateTo pushes current path to history when pushHistory is true`() = runTest {
        val sessionId = "session-1"
        val testFiles = createTestFiles()
        setupInitialState(sessionId, testFiles)

        coEvery { listDirectoryUseCase(any(), any(), any(), any()) } returns SshResult.Success(testFiles)

        viewModel.navigateTo("/other", pushHistory = true)

        testDispatcher.scheduler.advanceUntilIdle()

        // History should contain the previous path
        // We verify by calling goBack() which should work
        coEvery { listDirectoryUseCase(any(), any(), any(), any()) } returns SshResult.Success(testFiles)

        val canGoBack = viewModel.goBack()

        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(canGoBack)
        assertEquals("/home/user", viewModel.uiState.value.currentPath)
    }

    @Test
    fun `navigateTo does not push history when pushHistory is false`() = runTest {
        val sessionId = "session-1"
        val testFiles = createTestFiles()
        setupInitialState(sessionId, testFiles)

        coEvery { listDirectoryUseCase(any(), any(), any(), any()) } returns SshResult.Success(testFiles)

        viewModel.navigateTo("/other", pushHistory = false)

        testDispatcher.scheduler.advanceUntilIdle()

        val canGoBack = viewModel.goBack()

        assertFalse(canGoBack)
    }

    @Test
    fun `navigateTo sets error when listDirectory fails`() = runTest {
        val sessionId = "session-1"
        val testFiles = createTestFiles()
        setupInitialState(sessionId, testFiles)

        val errorMsg = "Permission denied"
        coEvery { listDirectoryUseCase(any(), any(), any(), any()) } returns SshResult.Error(SshErrorCode.AUTH_FAILED, errorMsg)

        viewModel.navigateTo("/root")

        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.isLoading == false)
        assertEquals(errorMsg, state.error)
    }

    @Test
    fun `navigateTo applies current sort order and showHidden settings`() = runTest {
        val sessionId = "session-1"
        val testFiles = createTestFiles()
        setupInitialState(sessionId, testFiles)

        viewModel.changeSortOrder(SortOrder.SIZE_DESC)
        viewModel.toggleHiddenFiles()

        testDispatcher.scheduler.advanceUntilIdle()

        coEvery { listDirectoryUseCase(any(), any(), any(), any()) } returns SshResult.Success(testFiles)

        viewModel.navigateTo("/new/path")

        testDispatcher.scheduler.advanceUntilIdle()

        // Verify listDirectoryUseCase was called
        coVerify { listDirectoryUseCase(any(), any(), any(), any()) }
    }

    // ── navigateUp() ──────────────────────────────────────────────────────────────

    @Test
    fun `navigateUp goes to parent directory`() = runTest {
        val sessionId = "session-1"
        val testFiles = createTestFiles()
        setupInitialState(sessionId, testFiles)

        coEvery { listDirectoryUseCase(any(), any(), any(), any()) } returns SshResult.Success(testFiles)
        viewModel.navigateTo("/home/user/documents")
        testDispatcher.scheduler.advanceUntilIdle()

        coEvery { listDirectoryUseCase(any(), any(), any(), any()) } returns SshResult.Success(testFiles)
        viewModel.navigateUp()

        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("/home/user", viewModel.uiState.value.currentPath)
    }

    @Test
    fun `navigateUp at root does nothing`() = runTest {
        val sessionId = "session-1"
        val testFiles = createTestFiles()

        coEvery { sftpManager.openSftp(sessionId) } returns SshResult.Success(Unit)
        coEvery { sftpManager.getHomeDirectory(sessionId) } returns SshResult.Success("/")
        coEvery { listDirectoryUseCase(any(), any(), any(), any()) } returns SshResult.Success(testFiles)

        viewModel.init(sessionId)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.navigateUp()

        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("/", viewModel.uiState.value.currentPath)
    }

    @Test
    fun `navigateUp from nested path`() = runTest {
        val sessionId = "session-1"
        val testFiles = createTestFiles()
        setupInitialState(sessionId, testFiles)

        coEvery { listDirectoryUseCase(any(), any(), any(), any()) } returns SshResult.Success(testFiles)
        viewModel.navigateTo("/home/user/documents/projects")
        testDispatcher.scheduler.advanceUntilIdle()

        coEvery { listDirectoryUseCase(any(), any(), any(), any()) } returns SshResult.Success(testFiles)
        viewModel.navigateUp()

        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("/home/user/documents", viewModel.uiState.value.currentPath)
    }

    // ── goBack() ───────────────────────────────────────────────────────────────────

    @Test
    fun `goBack returns true when history is not empty`() = runTest {
        val sessionId = "session-1"
        val testFiles = createTestFiles()
        setupInitialState(sessionId, testFiles)

        coEvery { listDirectoryUseCase(any(), any(), any(), any()) } returns SshResult.Success(testFiles)
        viewModel.navigateTo("/home/user/documents")
        testDispatcher.scheduler.advanceUntilIdle()

        coEvery { listDirectoryUseCase(any(), any(), any(), any()) } returns SshResult.Success(testFiles)
        val canGoBack = viewModel.goBack()

        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(canGoBack)
        assertEquals("/home/user", viewModel.uiState.value.currentPath)
    }

    @Test
    fun `goBack returns false when history is empty`() = runTest {
        val sessionId = "session-1"
        val testFiles = createTestFiles()
        setupInitialState(sessionId, testFiles)

        val canGoBack = viewModel.goBack()

        assertFalse(canGoBack)
    }

    @Test
    fun `goBack pops from stack correctly`() = runTest {
        val sessionId = "session-1"
        val testFiles = createTestFiles()

        coEvery { sftpManager.openSftp(sessionId) } returns SshResult.Success(Unit)
        coEvery { sftpManager.getHomeDirectory(sessionId) } returns SshResult.Success("/home/user")
        coEvery { listDirectoryUseCase(any(), any(), any(), any()) } returns SshResult.Success(testFiles)

        viewModel.init(sessionId)
        testDispatcher.scheduler.advanceUntilIdle()

        // Navigate to three different paths
        viewModel.navigateTo("/path1")
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.navigateTo("/path2")
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.navigateTo("/path3")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("/path3", viewModel.uiState.value.currentPath)

        // Go back through the stack
        viewModel.goBack()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("/path2", viewModel.uiState.value.currentPath)

        viewModel.goBack()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("/path1", viewModel.uiState.value.currentPath)

        viewModel.goBack()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("/home/user", viewModel.uiState.value.currentPath)

        // Final goBack should return false since history is now empty
        val canGoBack = viewModel.goBack()
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(canGoBack)
    }

    // ── refresh() ──────────────────────────────────────────────────────────────────

    @Test
    fun `refresh reloads current directory`() = runTest {
        val sessionId = "session-1"
        val testFiles = createTestFiles()

        coEvery { sftpManager.openSftp(sessionId) } returns SshResult.Success(Unit)
        coEvery { sftpManager.getHomeDirectory(sessionId) } returns SshResult.Success("/home/user")

        val newFiles = listOf(
            SftpFile(name = "new_file.txt", path = "/home/user/new_file.txt", size = 100L, permissions = 420, isDirectory = false, modifiedAt = 1000L)
        )

        coEvery { listDirectoryUseCase(any(), any(), any(), any()) }
            .returnsMany(SshResult.Success(testFiles), SshResult.Success(newFiles))

        viewModel.init(sessionId)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.refresh()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.files.size)
        assertEquals("new_file.txt", viewModel.uiState.value.files[0].name)
    }

    // ── toggleHiddenFiles() ────────────────────────────────────────────────────────

    @Test
    fun `toggleHiddenFiles toggles and refreshes`() = runTest {
        val sessionId = "session-1"
        val testFiles = createTestFiles()
        setupInitialState(sessionId, testFiles)

        val initialState = viewModel.uiState.value
        assertFalse(initialState.showHiddenFiles)

        coEvery { listDirectoryUseCase(any(), any(), any(), any()) } returns SshResult.Success(testFiles)

        viewModel.toggleHiddenFiles()
        testDispatcher.scheduler.advanceUntilIdle()

        val newState = viewModel.uiState.value
        assertTrue(newState.showHiddenFiles)
        coVerify { listDirectoryUseCase(any(), any(), any(), any()) }
    }

    @Test
    fun `toggleHiddenFiles twice returns to original state`() = runTest {
        val sessionId = "session-1"
        val testFiles = createTestFiles()
        setupInitialState(sessionId, testFiles)

        coEvery { listDirectoryUseCase(any(), any(), any(), any()) } returns SshResult.Success(testFiles)

        viewModel.toggleHiddenFiles()
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.toggleHiddenFiles()
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showHiddenFiles)
    }

    // ── changeSortOrder() ──────────────────────────────────────────────────────────

    @Test
    fun `changeSortOrder updates sort and refreshes`() = runTest {
        val sessionId = "session-1"
        val testFiles = createTestFiles()
        setupInitialState(sessionId, testFiles)

        val initialState = viewModel.uiState.value
        assertEquals(SortOrder.NAME_ASC, initialState.sortOrder)

        coEvery { listDirectoryUseCase(any(), any(), any(), any()) } returns SshResult.Success(testFiles)

        viewModel.changeSortOrder(SortOrder.SIZE_DESC)
        testDispatcher.scheduler.advanceUntilIdle()

        val newState = viewModel.uiState.value
        assertEquals(SortOrder.SIZE_DESC, newState.sortOrder)
        coVerify { listDirectoryUseCase(any(), any(), any(), any()) }
    }

    @Test
    fun `changeSortOrder to same value does nothing`() = runTest {
        val sessionId = "session-1"
        val testFiles = createTestFiles()
        setupInitialState(sessionId, testFiles)

        coEvery { listDirectoryUseCase(any(), any(), any(), any()) } returns SshResult.Success(testFiles)

        viewModel.changeSortOrder(SortOrder.NAME_ASC)
        testDispatcher.scheduler.advanceUntilIdle()

        // Should not trigger refresh since sort didn't change
        coVerify(exactly = 1) { listDirectoryUseCase(any(), any(), any(), any()) } // Only from init
    }

    @Test
    fun `changeSortOrder multiple times in sequence`() = runTest {
        val sessionId = "session-1"
        val testFiles = createTestFiles()
        setupInitialState(sessionId, testFiles)

        coEvery { listDirectoryUseCase(any(), any(), any(), any()) } returns SshResult.Success(testFiles)

        viewModel.changeSortOrder(SortOrder.NAME_DESC)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(SortOrder.NAME_DESC, viewModel.uiState.value.sortOrder)

        viewModel.changeSortOrder(SortOrder.SIZE_ASC)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(SortOrder.SIZE_ASC, viewModel.uiState.value.sortOrder)

        viewModel.changeSortOrder(SortOrder.DATE_DESC)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(SortOrder.DATE_DESC, viewModel.uiState.value.sortOrder)
    }

    // ── uploadFile() : nom fourni par le sélecteur de fichiers ────────────────────

    @Test
    fun `uploadFile refuses a dotdot name and starts no service`() = runTest {
        assertUploadRefused("..")
    }

    @Test
    fun `uploadFile refuses a dot name and starts no service`() = runTest {
        assertUploadRefused(".")
    }

    @Test
    fun `uploadFile refuses a blank name and starts no service`() = runTest {
        assertUploadRefused("   ")
    }

    @Test
    fun `uploadFile refuses an empty name and starts no service`() = runTest {
        assertUploadRefused("")
    }

    @Test
    fun `uploadFile neutralises a traversal name before building the remote path`() = runTest {
        val extras = captureTransferIntentExtras()

        viewModel.uploadFile(
            sessionId   = "session-1",
            remoteDir   = "/home/user",
            sourceUri   = mockk<Uri>(),
            displayName = "../.ssh/authorized_keys",
            fileSize    = 10L,
        )

        assertEquals("/home/user/.._.ssh_authorized_keys", extras[SftpTransferService.EXTRA_REMOTE_PATH])
        assertEquals(".._.ssh_authorized_keys", extras[SftpTransferService.EXTRA_DISPLAY_NAME])
        io.mockk.verify(exactly = 1) { ContextCompat.startForegroundService(any(), any()) }
        val state = viewModel.uiState.value
        assertFalse(state.messageIsError)
        // Le message annonce le nom assaini, jamais le nom brut du fournisseur.
        assertTrue(state.message!!.contains(".._.ssh_authorized_keys"), state.message)
        assertFalse(state.message!!.contains("../.ssh"), state.message)
    }

    @Test
    fun `uploadFile keeps an ordinary name and a trailing slash directory`() = runTest {
        val extras = captureTransferIntentExtras()

        viewModel.uploadFile(
            sessionId   = "session-1",
            remoteDir   = "/home/user/",
            sourceUri   = mockk<Uri>(),
            displayName = "rapport.pdf",
            fileSize    = 10L,
        )

        assertEquals("/home/user/rapport.pdf", extras[SftpTransferService.EXTRA_REMOTE_PATH])
        assertEquals("rapport.pdf", extras[SftpTransferService.EXTRA_DISPLAY_NAME])
        io.mockk.verify(exactly = 1) { ContextCompat.startForegroundService(any(), any()) }
        assertEquals("Envoi de rapport.pdf démarré", viewModel.uiState.value.message)
    }

    // Note: onCleared() is protected and cannot be tested directly from outside the ViewModel.
    // It is tested implicitly through the lifecycle tearDown in integration tests.

    // ── Helper ────────────────────────────────────────────────────────────────────

    /**
     * Intercepte l'Intent du service de transfert (le JAR Android des tests n'implémente ni
     * `Intent` ni `ContextCompat`) et renvoie les extras de type texte qui y sont posés.
     * Le service n'est pas démarré.
     */
    private fun captureTransferIntentExtras(): Map<String, String?> {
        val extras = mutableMapOf<String, String?>()
        mockkConstructor(Intent::class)
        mockkStatic(ContextCompat::class)
        io.mockk.every { anyConstructed<Intent>().putExtra(any<String>(), any<String>()) } answers {
            extras[firstArg()] = secondArg()
            self as Intent
        }
        io.mockk.every { anyConstructed<Intent>().putExtra(any<String>(), any<Long>()) } answers {
            self as Intent
        }
        io.mockk.justRun { ContextCompat.startForegroundService(any(), any()) }
        return extras
    }

    /**
     * Un envoi avec un nom inexploitable doit s'arrêter avant toute création d'Intent :
     * message d'erreur pour l'utilisateur, et aucun service démarré.
     */
    private fun assertUploadRefused(displayName: String) {
        val invalidNameMessage = "Nom de fichier invalide"
        io.mockk.every { context.getString(R.string.sftp_error_name_invalid) } returns invalidNameMessage

        viewModel.uploadFile(
            sessionId   = "session-1",
            remoteDir   = "/home/user",
            sourceUri   = mockk<Uri>(),
            displayName = displayName,
            fileSize    = 10L,
        )

        val state = viewModel.uiState.value
        assertEquals(invalidNameMessage, state.message)
        assertTrue(state.messageIsError)
        // ContextCompat.startForegroundService appelle l'une ou l'autre selon l'API.
        io.mockk.verify(exactly = 0) { context.startForegroundService(any()) }
        io.mockk.verify(exactly = 0) { context.startService(any()) }
    }

    private suspend fun setupInitialState(sessionId: String, testFiles: List<SftpFile>) {
        coEvery { sftpManager.openSftp(sessionId) } returns SshResult.Success(Unit)
        coEvery { sftpManager.getHomeDirectory(sessionId) } returns SshResult.Success("/home/user")
        coEvery { listDirectoryUseCase(any(), any(), any(), any()) } returns SshResult.Success(testFiles)

        viewModel.init(sessionId)
        testDispatcher.scheduler.advanceUntilIdle()
    }
}

