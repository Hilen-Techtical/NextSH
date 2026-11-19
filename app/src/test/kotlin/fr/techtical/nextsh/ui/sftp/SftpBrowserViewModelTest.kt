// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sftp

import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewModelScope
import fr.techtical.nextsh.R
import fr.techtical.nextsh.core.ssh.SFTP_CONNECTION_LOST_MESSAGE
import fr.techtical.nextsh.core.ssh.SFTP_CREATE_FILE_FAILED_MESSAGE
import fr.techtical.nextsh.core.ssh.SFTP_FOLDER_EXISTS_MESSAGE
import fr.techtical.nextsh.core.ssh.SFTP_NAME_EXISTS_MESSAGE
import fr.techtical.nextsh.core.ssh.SFTP_NO_SESSION_MESSAGE
import fr.techtical.nextsh.core.ssh.SFTP_OPEN_FAILED_MESSAGE
import fr.techtical.nextsh.core.ssh.SFTP_PERMISSION_DENIED_MESSAGE
import fr.techtical.nextsh.core.ssh.SFTP_PREVIEW_TOO_LARGE_MESSAGE
import fr.techtical.nextsh.core.ssh.SFTP_SESSION_NOT_FOUND_MESSAGE
import fr.techtical.nextsh.core.ssh.SFTP_UNKNOWN_MESSAGE
import fr.techtical.nextsh.core.ssh.SFTP_UPLOAD_TARGET_EXISTS_MESSAGE
import fr.techtical.nextsh.core.ssh.SessionHandoff
import fr.techtical.nextsh.core.ssh.SftpManager
import fr.techtical.nextsh.core.ssh.SftpReservation
import fr.techtical.nextsh.core.ssh.SshSessionManager
import fr.techtical.nextsh.core.ssh.TransferDirection
import fr.techtical.nextsh.core.ssh.TransferRequest
import fr.techtical.nextsh.core.ssh.TransferState
import fr.techtical.nextsh.core.ssh.TransferStatus
import fr.techtical.nextsh.core.ssh.TransferTracker
import fr.techtical.nextsh.core.ssh.UploadTarget
import fr.techtical.nextsh.domain.model.SftpFile
import fr.techtical.nextsh.domain.model.SortOrder
import fr.techtical.nextsh.domain.model.SshErrorCode
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.domain.usecase.ChangePermissionsUseCase
import fr.techtical.nextsh.domain.usecase.CreateDirectoryUseCase
import fr.techtical.nextsh.domain.usecase.CreateFileUseCase
import fr.techtical.nextsh.domain.usecase.DeleteFileUseCase
import fr.techtical.nextsh.domain.usecase.ListDirectoryUseCase
import fr.techtical.nextsh.domain.usecase.PreviewFileUseCase
import fr.techtical.nextsh.domain.usecase.RenameFileUseCase
import fr.techtical.nextsh.service.SftpPickerHoldController
import fr.techtical.nextsh.service.SftpTransferService
import fr.techtical.nextsh.testutil.awaitVerified
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkConstructor
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
import java.util.concurrent.atomic.AtomicInteger

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
    private lateinit var createFileUseCase: CreateFileUseCase

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

    @MockK
    private lateinit var resources: android.content.res.Resources

    @MockK
    private lateinit var sessionManager: SshSessionManager

    @MockK
    private lateinit var sessionHandoff: SessionHandoff

    @MockK
    private lateinit var pickerHold: SftpPickerHoldController

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
        // A session of a terminal tab by default: nothing on offer, the browser takes its own reference.
        io.mockk.every { sessionHandoff.claim(any()) } returns false
        io.mockk.every { sessionManager.acquire(any()) } returns true
        io.mockk.every { sessionManager.release(any()) } just Runs
        // The SSH session is alive and the SFTP client closes without a word, unless a test says otherwise.
        io.mockk.every { sessionManager.isConnected(any()) } returns true
        coEvery { sftpManager.closeSftp(any()) } returns SshResult.Success(Unit)
        // A failure the browser announces is acknowledged in the tracker.
        io.mockk.every { transferTracker.acknowledge(any()) } just Runs
        io.mockk.every { pickerHold.end(any()) } just Runs
        // No temporary file of this app is being written, unless a test says otherwise.
        io.mockk.every { sftpManager.knownUploadTempNames() } returns emptySet()
        viewModel = newViewModel(transferTracker)
    }

    private fun newViewModel(tracker: TransferTracker) = SftpBrowserViewModel(
        sftpManager,
        listDirectoryUseCase,
        createDirectoryUseCase,
        createFileUseCase,
        deleteFileUseCase,
        renameFileUseCase,
        changePermissionsUseCase,
        previewFileUseCase,
        tracker,
        context,
        sessionManager,
        sessionHandoff,
        pickerHold,
    )

    /** `onCleared` is protected: the ViewModel machinery that calls it is not reachable from a unit test. */
    private fun SftpBrowserViewModel.clear() {
        SftpBrowserViewModel::class.java.getDeclaredMethod("onCleared").apply { isAccessible = true }.invoke(this)
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

    // ── Reference on the SSH session ─────────────────────────────────────────────

    private fun sftpOpens(sessionId: String) {
        coEvery { sftpManager.openSftp(sessionId) } returns SshResult.Success(Unit)
        coEvery { sftpManager.getHomeDirectory(sessionId) } returns SshResult.Success("/")
        coEvery { sftpManager.closeSftp(sessionId) } returns SshResult.Success(Unit)
        coEvery { listDirectoryUseCase(any(), any(), any(), any()) } returns SshResult.Success(emptyList())
    }

    @Test
    fun `a browser on the session of a tab takes its own reference and gives it back`() = runTest {
        sftpOpens("tab-session")

        viewModel.init("tab-session", "Server")
        testDispatcher.scheduler.advanceUntilIdle()

        // The session is the tab's, not on offer: the browser does not own it, so it acquires.
        verify(exactly = 1) { sessionHandoff.claim("tab-session") }
        verify(exactly = 1) { sessionManager.acquire("tab-session") }
        verify(exactly = 0) { sessionManager.release(any()) }

        viewModel.clear()

        awaitVerified { verify(exactly = 1) { sessionManager.release("tab-session") } }
        coVerify(exactly = 1) { sftpManager.closeSftp("tab-session") }
    }

    @Test
    fun `a browser on the session of the host list takes over the reference of the list`() = runTest {
        sftpOpens("list-session")
        io.mockk.every { sessionHandoff.claim("list-session") } returns true

        viewModel.init("list-session", "Server")
        testDispatcher.scheduler.advanceUntilIdle()

        // Claimed: the reference of the list is the browser's, a second one would never be given back.
        verify(exactly = 0) { sessionManager.acquire(any()) }

        viewModel.clear()

        awaitVerified { verify(exactly = 1) { sessionManager.release("list-session") } }
    }

    @Test
    fun `the SFTP client closes before the session is released`() = runTest {
        sftpOpens("tab-session")
        val order = mutableListOf<String>()
        coEvery { sftpManager.closeSftp("tab-session") } coAnswers {
            order += "closeSftp"
            SshResult.Success(Unit)
        }
        io.mockk.every { sessionManager.release("tab-session") } answers { order += "release" }
        viewModel.init("tab-session", "Server")
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.clear()

        awaitVerified { assertEquals(listOf("closeSftp", "release"), order) }
    }

    @Test
    fun `a session that is gone is not released, and the browser reports the failure of the client`() = runTest {
        io.mockk.every { sessionManager.acquire("gone") } returns false
        coEvery { sftpManager.openSftp("gone") } returns SshResult.Error(SshErrorCode.UNKNOWN, SFTP_SESSION_NOT_FOUND_MESSAGE)
        // The manager says it with a fixed text, the browser says it in the language of the user.
        io.mockk.every { context.getString(R.string.sftp_error_session_not_found) } returns
            "La connexion SSH est fermée. Reconnectez-vous à l'hôte."

        viewModel.init("gone", "Server")
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.clear()

        assertEquals("La connexion SSH est fermée. Reconnectez-vous à l'hôte.", viewModel.uiState.value.error)
        // The SFTP client never opened, so the browser was never one of its users: it gives no use back.
        // (Giving one back would take it from another holder, a transfer for instance.)
        Thread.sleep(50)
        coVerify(exactly = 0) { sftpManager.closeSftp(any()) }
        // It never owned a reference either.
        verify(exactly = 0) { sessionManager.release(any()) }
    }

    @Test
    fun `a session that is gone at the start puts the browser in the session ended state`() = runTest {
        io.mockk.every { sessionManager.acquire("gone") } returns false
        coEvery { sftpManager.openSftp("gone") } returns SshResult.Error(SshErrorCode.UNKNOWN, SFTP_SESSION_NOT_FOUND_MESSAGE)
        io.mockk.every { context.getString(R.string.sftp_error_session_not_found) } returns "SSH connection closed"

        viewModel.init("gone", "Server")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        // Not a channel to reopen: the way back is offered, not a reconnection that cannot work.
        assertTrue(state.isDisconnected)
        assertTrue(state.sessionEnded)
        assertFalse(state.isLoading)
    }

    @Test
    fun `a channel that did not open on a living session is an error, not a session ended`() = runTest {
        coEvery { sftpManager.openSftp("alive") } returns SshResult.Error(SshErrorCode.UNKNOWN, SFTP_OPEN_FAILED_MESSAGE)
        io.mockk.every { context.getString(R.string.sftp_error_open_failed) } returns "Cannot open SFTP"

        viewModel.init("alive", "Server")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Cannot open SFTP", state.error)
        assertFalse(state.isDisconnected)
        assertFalse(state.sessionEnded)
    }

    @Test
    fun `a pane embedded in a split borrows the session of its tab, it neither claims, acquires nor releases`() = runTest {
        sftpOpens("tab-session")

        viewModel.init("tab-session", "Server", ownsSession = false)
        testDispatcher.scheduler.advanceUntilIdle()

        // The SFTP client opens as usual, only the session is not the pane's.
        coVerify(exactly = 1) { sftpManager.openSftp("tab-session") }
        verify(exactly = 0) { sessionHandoff.claim(any()) }
        verify(exactly = 0) { sessionManager.acquire(any()) }

        viewModel.clear()

        // The SFTP client is closed, the count of its users stays balanced, and the session is left
        // to the tab: the ViewModel of a pane outlives the pane, it must not hold the session.
        awaitVerified { coVerify(exactly = 1) { sftpManager.closeSftp("tab-session") } }
        verify(exactly = 0) { sessionManager.release(any()) }
    }

    @Test
    fun `a browser that was never initialized releases nothing`() = runTest {
        viewModel.clear()

        verify(exactly = 0) { sessionManager.release(any()) }
        coVerify(exactly = 0) { sftpManager.closeSftp(any()) }
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

    // ── Messages: the language of the user, and the colour of the message ────────

    private val aFile = SftpFile(
        name = "file1.txt", path = "/home/user/file1.txt", size = 100L,
        permissions = 420, isDirectory = false, modifiedAt = 1000L,
    )

    /** Puts an error on the screen, as a failed action does: the success that follows must replace it. */
    private fun showAnError() {
        coEvery { changePermissionsUseCase(any(), any(), any()) } returns
            SshResult.Error(SshErrorCode.UNKNOWN, SFTP_PERMISSION_DENIED_MESSAGE)
        io.mockk.every { context.getString(R.string.sftp_error_permission) } returns "Permission refusée"

        viewModel.changePermissions(aFile.path, 420)

        assertEquals("Permission refusée", viewModel.uiState.value.message)
        assertTrue(viewModel.uiState.value.messageIsError)
    }

    @Test
    fun `a success says the resource of the action and is not an error`() = runTest {
        setupInitialState("session-1", createTestFiles())
        io.mockk.every { context.getString(R.string.sftp_success_mkdir) } returns "Dossier créé"
        coEvery { createDirectoryUseCase(any(), any(), any()) } returns SshResult.Success(Unit)

        viewModel.createDirectory("new")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("Dossier créé", viewModel.uiState.value.message)
        assertFalse(viewModel.uiState.value.messageIsError)
    }

    @Test
    fun `a folder created after an error still on screen is not shown in red`() = runTest {
        setupInitialState("session-1", createTestFiles())
        showAnError()
        io.mockk.every { context.getString(R.string.sftp_success_mkdir) } returns "Dossier créé"
        coEvery { createDirectoryUseCase(any(), any(), any()) } returns SshResult.Success(Unit)

        viewModel.createDirectory("new")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("Dossier créé", viewModel.uiState.value.message)
        assertFalse(viewModel.uiState.value.messageIsError)
    }

    @Test
    fun `an item deleted after an error still on screen is not shown in red`() = runTest {
        setupInitialState("session-1", createTestFiles())
        showAnError()
        io.mockk.every { context.getString(R.string.sftp_success_delete) } returns "Élément supprimé"
        coEvery { deleteFileUseCase(any(), aFile.path, false) } returns SshResult.Success(Unit)

        viewModel.deleteFile(aFile)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("Élément supprimé", viewModel.uiState.value.message)
        assertFalse(viewModel.uiState.value.messageIsError)
    }

    @Test
    fun `an item renamed after an error still on screen is not shown in red`() = runTest {
        setupInitialState("session-1", createTestFiles())
        showAnError()
        // The same text serves a file and a folder: it says item.
        io.mockk.every { context.getString(R.string.sftp_success_rename) } returns "Élément renommé"
        coEvery { renameFileUseCase(any(), aFile.path, "renamed.txt") } returns SshResult.Success(Unit)

        viewModel.renameFile(aFile.path, "renamed.txt")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("Élément renommé", viewModel.uiState.value.message)
        assertFalse(viewModel.uiState.value.messageIsError)
    }

    @Test
    fun `permissions changed after an error still on screen are not shown in red`() = runTest {
        setupInitialState("session-1", createTestFiles())
        showAnError()
        io.mockk.every { context.getString(R.string.sftp_success_chmod) } returns "Permissions modifiées"
        coEvery { changePermissionsUseCase(any(), any(), any()) } returns SshResult.Success(Unit)

        viewModel.changePermissions(aFile.path, 420)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("Permissions modifiées", viewModel.uiState.value.message)
        assertFalse(viewModel.uiState.value.messageIsError)
    }

    @Test
    fun `a failure of an action says the fixed text of the manager in the language of the user`() = runTest {
        setupInitialState("session-1", createTestFiles())
        coEvery { renameFileUseCase(any(), any(), any()) } returns
            SshResult.Error(SshErrorCode.UNKNOWN, SFTP_PERMISSION_DENIED_MESSAGE)
        io.mockk.every { context.getString(R.string.sftp_error_permission) } returns "Permission denied (translated)"

        viewModel.renameFile(aFile.path, "renamed.txt")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("Permission denied (translated)", viewModel.uiState.value.message)
        assertTrue(viewModel.uiState.value.messageIsError)
    }

    @Test
    fun `a listing that fails says the fixed text of the manager in the language of the user`() = runTest {
        setupInitialState("session-1", createTestFiles())
        coEvery { listDirectoryUseCase(any(), any(), any(), any()) } returns
            SshResult.Error(SshErrorCode.AUTH_FAILED, SFTP_PERMISSION_DENIED_MESSAGE)
        io.mockk.every { context.getString(R.string.sftp_error_permission) } returns "Permission denied (translated)"

        viewModel.navigateTo("/root")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("Permission denied (translated)", viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.isDisconnected)
    }

    @Test
    fun `a connection that dropped offers the reconnection, read before the text is translated`() = runTest {
        setupInitialState("session-1", createTestFiles())
        coEvery { listDirectoryUseCase(any(), any(), any(), any()) } returns
            SshResult.Error(SshErrorCode.UNKNOWN, SFTP_CONNECTION_LOST_MESSAGE)
        // In English the translated text is not the fixed French one: the drop is read on the raw message.
        io.mockk.every { context.getString(R.string.sftp_error_connection) } returns "SFTP connection lost"

        viewModel.navigateTo("/home/user/documents")
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isDisconnected)
        assertEquals("SFTP connection lost", viewModel.uiState.value.error)
    }

    @Test
    fun `a message that is not a fixed text of the manager is shown as it is`() = runTest {
        setupInitialState("session-1", createTestFiles())
        coEvery { listDirectoryUseCase(any(), any(), any(), any()) } returns
            SshResult.Error(SshErrorCode.UNKNOWN, "Already in the language of the user")

        viewModel.navigateTo("/home/user/documents")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("Already in the language of the user", viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.isDisconnected)
    }

    @Test
    fun `the deletion of several items says how many went, in the plural of the language`() = runTest {
        val files = createTestFiles()
        setupInitialState("session-1", files)
        io.mockk.every { context.resources } returns resources
        io.mockk.every { resources.getQuantityString(R.plurals.sftp_multi_delete_done, 2, 2) } returns "2 éléments supprimés"
        coEvery { deleteFileUseCase(any(), any(), any()) } returns SshResult.Success(Unit)
        viewModel.enterSelectionMode(files[0])
        viewModel.toggleSelection(files[2])

        viewModel.confirmMultiDelete()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("2 éléments supprimés", viewModel.uiState.value.message)
        assertFalse(viewModel.uiState.value.messageIsError)
        assertFalse(viewModel.uiState.value.isSelectionMode)
    }

    @Test
    fun `the deletion of several items that partly failed says the ones that went, then the ones that did not`() = runTest {
        val files = createTestFiles()
        setupInitialState("session-1", files)
        io.mockk.every { context.resources } returns resources
        io.mockk.every { resources.getQuantityString(R.plurals.sftp_multi_delete_done, 1, 1) } returns "1 élément supprimé"
        io.mockk.every { resources.getQuantityString(R.plurals.sftp_multi_delete_failed, 1, 1) } returns
            "1 n'a pas pu être supprimé"
        io.mockk.every {
            context.getString(R.string.sftp_multi_delete_partial, "1 élément supprimé", "1 n'a pas pu être supprimé")
        } returns "1 élément supprimé, 1 n'a pas pu être supprimé"
        coEvery { deleteFileUseCase(any(), files[0].path, false) } returns SshResult.Success(Unit)
        coEvery { deleteFileUseCase(any(), files[2].path, false) } returns
            SshResult.Error(SshErrorCode.UNKNOWN, SFTP_PERMISSION_DENIED_MESSAGE)
        viewModel.enterSelectionMode(files[0])
        viewModel.toggleSelection(files[2])

        viewModel.confirmMultiDelete()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("1 élément supprimé, 1 n'a pas pu être supprimé", viewModel.uiState.value.message)
        assertTrue(viewModel.uiState.value.messageIsError)
    }

    @Test
    fun `the deletion of several items that all failed does not start with zero items deleted`() = runTest {
        val files = createTestFiles()
        setupInitialState("session-1", files)
        io.mockk.every { context.getString(R.string.sftp_multi_delete_none) } returns "Aucun élément n'a pu être supprimé"
        coEvery { deleteFileUseCase(any(), any(), any()) } returns
            SshResult.Error(SshErrorCode.UNKNOWN, SFTP_PERMISSION_DENIED_MESSAGE)
        viewModel.enterSelectionMode(files[0])
        viewModel.toggleSelection(files[2])

        viewModel.confirmMultiDelete()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("Aucun élément n'a pu être supprimé", viewModel.uiState.value.message)
        assertTrue(viewModel.uiState.value.messageIsError)
    }

    @Test
    fun `a file too big for the preview says so without a size, through the generic translation`() = runTest {
        setupInitialState("session-1", createTestFiles())
        // The size of the listing is not the one the manager measured (a symbolic link, a file that
        // grows): the sentence has no number, and the view model reads no size resource for it.
        val big = SftpFile(
            name = "big.log", path = "/home/user/big.log", size = 3L * 1024 * 1024,
            permissions = 420, isDirectory = false, modifiedAt = 0L,
        )
        coEvery { previewFileUseCase(any(), big.path, any()) } returns
            SshResult.Error(SshErrorCode.UNKNOWN, SFTP_PREVIEW_TOO_LARGE_MESSAGE)
        io.mockk.every { context.getString(R.string.sftp_preview_too_large) } returns "File too large for preview (translated)"

        viewModel.openPreview(big)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("File too large for preview (translated)", viewModel.uiState.value.previewState?.error)
        // The context is a strict mock: any other getString (a size unit, for instance) would have thrown.
        verify(exactly = 0) { context.getString(R.string.sftp_size_bytes) }
        verify(exactly = 0) { context.getString(R.string.sftp_size_megabytes) }
    }

    @Test
    fun `a preview that fails for another reason says the fixed text of the manager in the language of the user`() = runTest {
        setupInitialState("session-1", createTestFiles())
        coEvery { previewFileUseCase(any(), aFile.path, any()) } returns
            SshResult.Error(SshErrorCode.UNKNOWN, SFTP_PERMISSION_DENIED_MESSAGE)
        io.mockk.every { context.getString(R.string.sftp_error_permission) } returns "Permission denied (translated)"

        viewModel.openPreview(aFile)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("Permission denied (translated)", viewModel.uiState.value.previewState?.error)
    }

    // ── reconnect() ──────────────────────────────────────────────────────────────

    @Test
    fun `a reconnection that fails says the fixed text of the manager in the language of the user and stays disconnected`() = runTest {
        setupInitialState("session-1", createTestFiles())
        coEvery { sftpManager.openSftp("session-1") } returns
            SshResult.Error(SshErrorCode.UNKNOWN, SFTP_OPEN_FAILED_MESSAGE)
        io.mockk.every { context.getString(R.string.sftp_error_open_failed) } returns "Cannot open SFTP (translated)"

        viewModel.reconnect()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Cannot open SFTP (translated)", state.error)
        assertTrue(state.isDisconnected)
        assertFalse(state.isLoading)
    }

    @Test
    fun `a reconnection that works lists the folder again and is no longer disconnected`() = runTest {
        setupInitialState("session-1", createTestFiles())
        coEvery { listDirectoryUseCase(any(), any(), any(), any()) } returns
            SshResult.Error(SshErrorCode.UNKNOWN, SFTP_CONNECTION_LOST_MESSAGE)
        io.mockk.every { context.getString(R.string.sftp_error_connection) } returns "SFTP connection lost"
        viewModel.navigateTo("/home/user/documents")
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isDisconnected)

        coEvery { listDirectoryUseCase(any(), any(), any(), any()) } returns SshResult.Success(createTestFiles())
        viewModel.reconnect()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isDisconnected)
        assertFalse(state.sessionEnded)
        assertNull(state.error)
        assertEquals(3, state.files.size)
    }

    @Test
    fun `a reconnection that works clears the message of the failure that was on screen`() = runTest {
        setupInitialState("session-1", createTestFiles())
        showAnError()
        assertEquals("Permission refusée", viewModel.uiState.value.message)

        viewModel.reconnect()
        testDispatcher.scheduler.advanceUntilIdle()

        // The error was about the link that has just been replaced: it stayed on screen after the reconnection.
        assertNull(viewModel.uiState.value.message)
        assertFalse(viewModel.uiState.value.messageIsError)
    }

    @Test
    fun `a reconnection gives back the use of the old client before it asks for a new one`() = runTest {
        setupInitialState("session-1", createTestFiles())

        viewModel.reconnect()
        testDispatcher.scheduler.advanceUntilIdle()

        // The client of a channel that dropped is still registered: openSftp counts one more user for it.
        // The use that this browser already had goes back first, or the count drifts up for good.
        coVerifyOrder {
            sftpManager.openSftp("session-1")
            sftpManager.closeSftp("session-1")
            sftpManager.openSftp("session-1")
        }
    }

    @Test
    fun `a browser that reconnected is one user of the SFTP client, not two`() = runTest {
        // A counter in the shape of the manager: openSftp counts a user, closeSftp takes one away.
        val users = AtomicInteger(0)
        coEvery { sftpManager.openSftp("session-1") } coAnswers {
            users.incrementAndGet()
            SshResult.Success(Unit)
        }
        coEvery { sftpManager.closeSftp("session-1") } coAnswers {
            users.decrementAndGet()
            SshResult.Success(Unit)
        }
        coEvery { sftpManager.getHomeDirectory("session-1") } returns SshResult.Success("/home/user")
        coEvery { listDirectoryUseCase(any(), any(), any(), any()) } returns SshResult.Success(createTestFiles())
        viewModel.init("session-1", "Server")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, users.get())

        viewModel.reconnect()
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.reconnect()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, users.get(), "still one user after two reconnections")

        viewModel.clear()

        // One use given back when the browser goes: nothing is left for a client that nobody uses.
        awaitVerified { assertEquals(0, users.get()) }
    }

    @Test
    fun `a reconnection that fails leaves no use to give back, a second one is not taken away from a transfer`() = runTest {
        setupInitialState("session-1", createTestFiles())
        coEvery { sftpManager.openSftp("session-1") } returns SshResult.Error(SshErrorCode.UNKNOWN, SFTP_OPEN_FAILED_MESSAGE)
        io.mockk.every { context.getString(R.string.sftp_error_open_failed) } returns "Cannot open SFTP"

        viewModel.reconnect()
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.clear()

        // closeSftp ran once, in the reconnection (the use that the browser had). The browser has none
        // left when it goes: a second one would close the client under a transfer that holds a use.
        Thread.sleep(50)
        coVerify(exactly = 1) { sftpManager.closeSftp("session-1") }
    }

    @Test
    fun `a reconnection on a session that is gone opens nothing and offers the way back`() = runTest {
        setupInitialState("session-1", createTestFiles())
        io.mockk.every { sessionManager.isConnected("session-1") } returns false

        viewModel.reconnect()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.isDisconnected)
        assertTrue(state.sessionEnded)
        assertFalse(state.isLoading)
        // Only the opening of the browser: the reconnection did not ask for a channel on a dead session.
        coVerify(exactly = 1) { sftpManager.openSftp("session-1") }
        coVerify(exactly = 0) { sftpManager.closeSftp(any()) }
    }

    @Test
    fun `a reconnection that fails because the session died meanwhile says the session ended`() = runTest {
        setupInitialState("session-1", createTestFiles())
        var alive = true
        io.mockk.every { sessionManager.isConnected("session-1") } answers { alive }
        coEvery { sftpManager.openSftp("session-1") } coAnswers {
            alive = false
            SshResult.Error(SshErrorCode.UNKNOWN, SFTP_OPEN_FAILED_MESSAGE)
        }
        io.mockk.every { context.getString(R.string.sftp_error_open_failed) } returns "Cannot open SFTP"

        viewModel.reconnect()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.isDisconnected)
        assertTrue(state.sessionEnded, "the panel must stop offering a reconnection that cannot work")
    }

    @Test
    fun `a transfer that failed says its reason in the language of the user`() = runTest {
        val request = TransferRequest(
            id = "t1", sessionId = "session-1", remotePath = "/home/user/doc.txt",
            localUri = io.mockk.mockk<android.net.Uri>(), direction = TransferDirection.DOWNLOAD,
            fileSize = 10L, displayName = "doc.txt",
        )
        val failed = TransferState(request, TransferStatus.FAILED, errorMessage = SFTP_CONNECTION_LOST_MESSAGE)
        io.mockk.every { transferTracker.transfers } returns MutableStateFlow(mapOf("t1" to failed))
        io.mockk.every { context.getString(R.string.sftp_error_connection) } returns "SFTP connection lost"
        io.mockk.every {
            context.getString(R.string.sftp_transfer_failed_detail, "doc.txt", "SFTP connection lost")
        } returns "Transfer of doc.txt failed: SFTP connection lost"
        sftpOpens("session-1")
        viewModel.setVisible(true)

        viewModel.init("session-1", "Server")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("Transfer of doc.txt failed: SFTP connection lost", viewModel.uiState.value.message)
        assertTrue(viewModel.uiState.value.messageIsError)
        // Said, so acknowledged: the next browser of this session does not say it again.
        verify(exactly = 1) { transferTracker.acknowledge("t1") }
    }

    @Test
    fun `a transfer that failed with a text the service already resolved shows it as it is`() = runTest {
        val request = TransferRequest(
            id = "t2", sessionId = "session-1", remotePath = "/home/user/doc.txt",
            localUri = io.mockk.mockk<android.net.Uri>(), direction = TransferDirection.UPLOAD,
            fileSize = 10L, displayName = "doc.txt",
        )
        val failed = TransferState(request, TransferStatus.FAILED, errorMessage = "SSH connection closed.")
        io.mockk.every { transferTracker.transfers } returns MutableStateFlow(mapOf("t2" to failed))
        io.mockk.every {
            context.getString(R.string.sftp_transfer_failed_detail, "doc.txt", "SSH connection closed.")
        } returns "Transfer of doc.txt failed: SSH connection closed."
        sftpOpens("session-1")
        viewModel.setVisible(true)

        viewModel.init("session-1", "Server")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("Transfer of doc.txt failed: SSH connection closed.", viewModel.uiState.value.message)
        verify(exactly = 1) { transferTracker.acknowledge("t2") }
    }

    // ── The failures of the tracker: once, in the browser of their own session ──

    /** A transfer of [sessionId] that failed with [errorMessage], as the service leaves it in the tracker. */
    private fun TransferTracker.withFailure(
        id: String,
        sessionId: String,
        errorMessage: String = SFTP_CONNECTION_LOST_MESSAGE,
    ): TransferTracker {
        enqueue(
            TransferRequest(
                id = id, sessionId = sessionId, remotePath = "/home/user/doc.txt",
                localUri = io.mockk.mockk<android.net.Uri>(), direction = TransferDirection.DOWNLOAD,
                fileSize = 10L, displayName = "doc.txt",
            ),
        )
        fail(id, errorMessage)
        return this
    }

    private fun stubFailureTexts() {
        io.mockk.every { context.getString(R.string.sftp_error_connection) } returns "SFTP connection lost"
        io.mockk.every {
            context.getString(R.string.sftp_transfer_failed_detail, "doc.txt", "SFTP connection lost")
        } returns "Transfer of doc.txt failed: SFTP connection lost"
    }

    @Test
    fun `a failure is announced once, the next browser of the session does not replay it`() = runTest {
        // The tracker lives as long as the process: the failure was still in it for every browser opened later.
        val tracker = TransferTracker().withFailure("t1", "session-1")
        stubFailureTexts()
        sftpOpens("session-1")
        val first = newViewModel(tracker).apply { setVisible(true) }

        first.init("session-1", "Server")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("Transfer of doc.txt failed: SFTP connection lost", first.uiState.value.message)
        assertNull(tracker.transfers.value["t1"], "the browser acknowledged what it announced")

        val second = newViewModel(tracker).apply { setVisible(true) }
        second.init("session-1", "Server")
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(second.uiState.value.message)
        assertFalse(second.uiState.value.messageIsError)
    }

    @Test
    fun `a failure of another session is not shown, and stays for the browser of that session`() = runTest {
        val tracker = TransferTracker().withFailure("t9", "other-session")
        stubFailureTexts()
        sftpOpens("session-1")
        sftpOpens("other-session")
        val mine = newViewModel(tracker).apply { setVisible(true) }

        mine.init("session-1", "Server")
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(mine.uiState.value.message)
        assertEquals(TransferStatus.FAILED, tracker.transfers.value["t9"]?.status, "not mine to acknowledge")

        // The browser of the session it belongs to announces it, once.
        val theirs = newViewModel(tracker).apply { setVisible(true) }
        theirs.init("other-session", "Other")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("Transfer of doc.txt failed: SFTP connection lost", theirs.uiState.value.message)
        assertNull(tracker.transfers.value["t9"])
    }

    @Test
    fun `a transfer that fails while the browser is open is announced as it happens`() = runTest {
        val tracker = TransferTracker()
        stubFailureTexts()
        sftpOpens("session-1")
        val browser = newViewModel(tracker).apply { setVisible(true) }
        browser.init("session-1", "Server")
        testDispatcher.scheduler.advanceUntilIdle()
        assertNull(browser.uiState.value.message)

        tracker.withFailure("t3", "session-1")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("Transfer of doc.txt failed: SFTP connection lost", browser.uiState.value.message)
        assertNull(tracker.transfers.value["t3"])
    }

    // ── A failure is said by the browser that is on show, and by it alone ───────

    @Test
    fun `a browser that is not on show neither announces nor acknowledges a failure`() = runTest {
        val tracker = TransferTracker().withFailure("t1", "session-1")
        stubFailureTexts()
        sftpOpens("session-1")
        // Never told that its screen is on show: nobody sees a screen that is not there.
        val hidden = newViewModel(tracker)

        hidden.init("session-1", "Server")
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(hidden.uiState.value.message)
        assertFalse(hidden.uiState.value.messageIsError)
        assertEquals(TransferStatus.FAILED, tracker.transfers.value["t1"]?.status, "nobody saw it, it is not acknowledged")
    }

    @Test
    fun `of two browsers of the same session only the one on show announces the failure`() = runTest {
        val tracker = TransferTracker()
        stubFailureTexts()
        sftpOpens("session-1")
        // The pane of a split lives on in the navigation entry of the terminal and keeps collecting
        // while the full-screen browser of the same session is open above it. It collects first.
        val pane = newViewModel(tracker)
        pane.init("session-1", "Server", ownsSession = false)
        val fullScreen = newViewModel(tracker).apply { setVisible(true) }
        fullScreen.init("session-1", "Server")
        testDispatcher.scheduler.advanceUntilIdle()

        tracker.withFailure("t1", "session-1")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("Transfer of doc.txt failed: SFTP connection lost", fullScreen.uiState.value.message)
        assertTrue(fullScreen.uiState.value.messageIsError)
        assertNull(pane.uiState.value.message, "the pane said it to nobody")
        assertNull(tracker.transfers.value["t1"], "acknowledged by the browser that said it")
    }

    @Test
    fun `a browser that comes back on show announces the failure that came in while it was not`() = runTest {
        val tracker = TransferTracker()
        stubFailureTexts()
        sftpOpens("session-1")
        val browser = newViewModel(tracker).apply { setVisible(true) }
        browser.init("session-1", "Server")
        testDispatcher.scheduler.advanceUntilIdle()

        // The app goes to the background, a picker is open above it.
        browser.setVisible(false)
        tracker.withFailure("t1", "session-1")
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(browser.uiState.value.message)
        assertEquals(TransferStatus.FAILED, tracker.transfers.value["t1"]?.status, "it waits for the screen to come back")

        browser.setVisible(true)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("Transfer of doc.txt failed: SFTP connection lost", browser.uiState.value.message)
        assertTrue(browser.uiState.value.messageIsError)
        assertNull(tracker.transfers.value["t1"])
    }

    @Test
    fun `a failure that was announced is not announced again when the browser comes back on show`() = runTest {
        val tracker = TransferTracker()
        stubFailureTexts()
        sftpOpens("session-1")
        val browser = newViewModel(tracker).apply { setVisible(true) }
        browser.init("session-1", "Server")
        testDispatcher.scheduler.advanceUntilIdle()
        tracker.withFailure("t1", "session-1")
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(browser.uiState.value.messageIsError)
        browser.clearMessage()

        browser.setVisible(false)
        browser.setVisible(true)
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(browser.uiState.value.message)
        assertFalse(browser.uiState.value.messageIsError)
    }

    // ── A browser that goes while its SFTP channel opens ─────────────────────────

    @Test
    fun `a browser cleared while the channel opens gives its use back itself once it is open`() = runTest {
        val opening = CompletableDeferred<Unit>()
        coEvery { sftpManager.openSftp("session-1") } coAnswers {
            opening.await()
            SshResult.Success(Unit)
        }

        viewModel.init("session-1", "Server")
        viewModel.clear()

        // The channel is not open: the browser is no user of the client yet, onCleared has nothing to give back.
        Thread.sleep(50)
        coVerify(exactly = 0) { sftpManager.closeSftp(any()) }

        opening.complete(Unit)
        testDispatcher.scheduler.advanceUntilIdle()

        // The use that the opening has just counted goes back, or the client stays open for ever.
        awaitVerified { coVerify(exactly = 1) { sftpManager.closeSftp("session-1") } }
        // And nothing more is asked of a browser that is gone.
        coVerify(exactly = 0) { sftpManager.getHomeDirectory(any()) }
        coVerify(exactly = 0) { listDirectoryUseCase(any(), any(), any(), any()) }
    }

    @Test
    fun `a browser cleared while the channel opens and refused counts no use and gives none back`() = runTest {
        val opening = CompletableDeferred<Unit>()
        coEvery { sftpManager.openSftp("session-1") } coAnswers {
            opening.await()
            SshResult.Error(SshErrorCode.UNKNOWN, SFTP_OPEN_FAILED_MESSAGE)
        }
        io.mockk.every { context.getString(R.string.sftp_error_open_failed) } returns "Cannot open SFTP"

        viewModel.init("session-1", "Server")
        viewModel.clear()
        opening.complete(Unit)
        testDispatcher.scheduler.advanceUntilIdle()

        // A refusal counts for nothing: giving a use back would take one from a transfer.
        Thread.sleep(50)
        coVerify(exactly = 0) { sftpManager.closeSftp(any()) }
    }

    @Test
    fun `a browser moved to another session while a channel opens gives the use of the old one back, and keeps the new one`() = runTest {
        val opening = CompletableDeferred<Unit>()
        coEvery { sftpManager.openSftp("old") } coAnswers {
            opening.await()
            SshResult.Success(Unit)
        }
        sftpOpens("new")

        viewModel.init("old", "Old")
        viewModel.init("new", "New")
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 0) { sftpManager.closeSftp("old") }

        opening.complete(Unit)
        testDispatcher.scheduler.advanceUntilIdle()

        // The old channel opened for a browser that looks at another session now: its use goes back at once.
        awaitVerified { coVerify(exactly = 1) { sftpManager.closeSftp("old") } }
        // The use of the new session is the browser's, given back once when it goes, and not before.
        coVerify(exactly = 0) { sftpManager.closeSftp("new") }
        viewModel.clear()
        awaitVerified { coVerify(exactly = 1) { sftpManager.closeSftp("new") } }
    }

    @Test
    fun `a browser cleared while a reconnection opens the channel gives the new use back itself`() = runTest {
        setupInitialState("session-1", createTestFiles())
        val opening = CompletableDeferred<Unit>()
        coEvery { sftpManager.openSftp("session-1") } coAnswers {
            opening.await()
            SshResult.Success(Unit)
        }

        viewModel.reconnect()
        // The reconnection gave the old use back before it asked for a new one.
        coVerify(exactly = 1) { sftpManager.closeSftp("session-1") }
        viewModel.clear()
        Thread.sleep(50)
        coVerify(exactly = 1) { sftpManager.closeSftp("session-1") }

        opening.complete(Unit)
        testDispatcher.scheduler.advanceUntilIdle()

        awaitVerified { coVerify(exactly = 2) { sftpManager.closeSftp("session-1") } }
    }

    // ── Transfers on a session that is gone ──────────────────────────────────────

    @Test
    fun `a download on a session that is gone starts no service and puts the browser in the session ended state`() = runTest {
        setupInitialState("session-1", createTestFiles())
        io.mockk.every { sessionManager.isConnected("session-1") } returns false

        viewModel.downloadFile("session-1", "/home/user/file1.txt", "file1.txt", 100L, io.mockk.mockk<android.net.Uri>())

        val state = viewModel.uiState.value
        assertTrue(state.isDisconnected)
        assertTrue(state.sessionEnded)
        assertFalse(state.isLoading)
        // Not "download started": nothing started. The context is a strict mock, a service start or a
        // message would have thrown, and the state holds no message.
        assertNull(state.message)
        verify(exactly = 0) { context.startForegroundService(any()) }
    }

    @Test
    fun `an upload on a session that is gone starts no service and puts the browser in the session ended state`() = runTest {
        setupInitialState("session-1", createTestFiles())
        io.mockk.every { sessionManager.isConnected("session-1") } returns false

        viewModel.uploadFile("session-1", "/home/user", io.mockk.mockk<android.net.Uri>(), "notes.txt", 10L)

        val state = viewModel.uiState.value
        assertTrue(state.isDisconnected)
        assertTrue(state.sessionEnded)
        assertNull(state.message)
        verify(exactly = 0) { context.startForegroundService(any()) }
    }

    @Test
    fun `a transfer asked before the browser has a session says so, it is not a session that ended`() = runTest {
        io.mockk.every { context.getString(R.string.sftp_transfer_no_session) } returns "Session not ready"

        viewModel.uploadFile("", "/", io.mockk.mockk<android.net.Uri>(), "notes.txt", 10L)

        val state = viewModel.uiState.value
        assertEquals("Session not ready", state.message)
        assertTrue(state.messageIsError)
        assertFalse(state.isDisconnected)
        verify(exactly = 0) { context.startForegroundService(any()) }
    }

    // ── The link, read before a file picker opens ────────────────────────────────

    @Test
    fun `checkLink lets the picker open while the session is alive`() = runTest {
        setupInitialState("session-1", createTestFiles())

        assertTrue(viewModel.checkLink())

        assertFalse(viewModel.uiState.value.isDisconnected)
    }

    @Test
    fun `checkLink refuses the picker once the session is gone, and the browser says so`() = runTest {
        setupInitialState("session-1", createTestFiles())
        io.mockk.every { sessionManager.isConnected("session-1") } returns false

        assertFalse(viewModel.checkLink())

        assertTrue(viewModel.uiState.value.isDisconnected)
        assertTrue(viewModel.uiState.value.sessionEnded)
    }

    @Test
    fun `checkLink has nothing to say before the browser has a session`() = runTest {
        assertTrue(viewModel.checkLink())

        assertFalse(viewModel.uiState.value.isDisconnected)
        verify(exactly = 0) { sessionManager.isConnected(any()) }
    }

    // ── The hold that keeps the app up during the file picker ────────────────────

    @Test
    fun `the hold of the picker is taken for the session of the browser and given back by its id`() = runTest {
        setupInitialState("session-1", createTestFiles())
        io.mockk.every { pickerHold.begin("session-1") } returns "hold-1"

        val holdId = viewModel.beginPickerHold()
        viewModel.endPickerHold(holdId)

        assertEquals("hold-1", holdId)
        verify(exactly = 1) { pickerHold.begin("session-1") }
        verify(exactly = 1) { pickerHold.end("hold-1") }
    }

    @Test
    fun `no hold is taken before the browser has a session, and giving back nothing gives back nothing`() = runTest {
        assertNull(viewModel.beginPickerHold())

        viewModel.endPickerHold(null)

        verify(exactly = 0) { pickerHold.begin(any()) }
        verify(exactly = 0) { pickerHold.end(any()) }
    }

    @Test
    fun `a hold is given back by a browser that is not initialized yet, as after a recreation of the activity`() = runTest {
        // The callback of the picker runs before init on a recreated activity: the id is all it has.
        viewModel.endPickerHold("hold-from-before")

        verify(exactly = 1) { pickerHold.end("hold-from-before") }
    }

    @Test
    fun `a picker refused by the system opens without a hold`() = runTest {
        setupInitialState("session-1", createTestFiles())
        io.mockk.every { pickerHold.begin("session-1") } returns null

        val holdId = viewModel.beginPickerHold()
        viewModel.endPickerHold(holdId)

        assertNull(holdId)
        verify(exactly = 0) { pickerHold.end(any()) }
    }

    // ── A link that is lost: the channel, or the session ─────────────────────────

    @Test
    fun `a listing without SFTP client on a living session offers to reconnect the channel`() = runTest {
        setupInitialState("session-1", createTestFiles())
        coEvery { listDirectoryUseCase(any(), any(), any(), any()) } returns
            SshResult.Error(SshErrorCode.UNKNOWN, SFTP_NO_SESSION_MESSAGE)
        io.mockk.every { context.getString(R.string.sftp_transfer_no_session) } returns "SFTP session not ready"

        viewModel.refresh()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        // It used to read "not ready" with a Retry that looped: the client is gone, the link is lost.
        assertTrue(state.isDisconnected)
        assertFalse(state.sessionEnded)
    }

    @Test
    fun `a listing that loses the link on a dead session offers the way back, not a reconnection`() = runTest {
        setupInitialState("session-1", createTestFiles())
        coEvery { listDirectoryUseCase(any(), any(), any(), any()) } returns
            SshResult.Error(SshErrorCode.UNKNOWN, SFTP_CONNECTION_LOST_MESSAGE)
        io.mockk.every { context.getString(R.string.sftp_error_connection) } returns "SFTP connection lost"
        io.mockk.every { sessionManager.isConnected("session-1") } returns false

        viewModel.refresh()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.isDisconnected)
        assertTrue(state.sessionEnded)
    }

    @Test
    fun `a listing that fails for another reason says nothing about the link`() = runTest {
        setupInitialState("session-1", createTestFiles())
        coEvery { listDirectoryUseCase(any(), any(), any(), any()) } returns
            SshResult.Error(SshErrorCode.AUTH_FAILED, SFTP_PERMISSION_DENIED_MESSAGE)
        io.mockk.every { context.getString(R.string.sftp_error_permission) } returns "Permission denied"

        viewModel.refresh()
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isDisconnected)
        assertFalse(viewModel.uiState.value.sessionEnded)
        // The session is not even asked about: there is no doubt about it.
        verify(exactly = 0) { sessionManager.isConnected(any()) }
    }

    @Test
    fun `an action that loses the link puts the browser in the disconnected state`() = runTest {
        setupInitialState("session-1", createTestFiles())
        coEvery { renameFileUseCase(any(), any(), any()) } returns
            SshResult.Error(SshErrorCode.UNKNOWN, SFTP_CONNECTION_LOST_MESSAGE)
        io.mockk.every { context.getString(R.string.sftp_error_connection) } returns "SFTP connection lost"

        viewModel.renameFile(aFile.path, "renamed.txt")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("SFTP connection lost", viewModel.uiState.value.message)
        assertTrue(viewModel.uiState.value.isDisconnected)
        assertFalse(viewModel.uiState.value.sessionEnded)
    }

    @Test
    fun `a listing that works takes the browser out of the disconnected state`() = runTest {
        setupInitialState("session-1", createTestFiles())
        viewModel.checkLink()
        io.mockk.every { sessionManager.isConnected("session-1") } returns false
        viewModel.checkLink()
        assertTrue(viewModel.uiState.value.sessionEnded)

        io.mockk.every { sessionManager.isConnected("session-1") } returns true
        viewModel.refresh()
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isDisconnected)
        assertFalse(viewModel.uiState.value.sessionEnded)
    }

    // ── New file ─────────────────────────────────────────────────────────────────

    @Test
    fun `the new file dialog opens and closes on its own, the folder dialog is left alone`() = runTest {
        assertFalse(viewModel.uiState.value.newFileDialog)

        viewModel.showNewFileDialog()
        assertTrue(viewModel.uiState.value.newFileDialog)
        assertFalse(viewModel.uiState.value.mkdirDialog)

        viewModel.dismissNewFileDialog()
        assertFalse(viewModel.uiState.value.newFileDialog)
    }

    @Test
    fun `a file is created in the folder that is shown, then the dialog closes, the list reloads and the message is not red`() = runTest {
        setupInitialState("session-1", createTestFiles())
        io.mockk.every { context.getString(R.string.sftp_success_create_file) } returns "Fichier créé"
        coEvery { createFileUseCase(any(), any(), any()) } returns SshResult.Success(Unit)
        viewModel.showNewFileDialog()

        viewModel.createFile("notes.txt")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { createFileUseCase("session-1", "/home/user", "notes.txt") }
        assertFalse(viewModel.uiState.value.newFileDialog)
        assertEquals("Fichier créé", viewModel.uiState.value.message)
        assertFalse(viewModel.uiState.value.messageIsError)
        // The list of init, then the one of the refresh. No editor, no other call.
        coVerify(exactly = 2) { listDirectoryUseCase(any(), any(), any(), any()) }
    }

    @Test
    fun `a file is created in the folder the user moved to`() = runTest {
        setupInitialState("session-1", createTestFiles())
        io.mockk.every { context.getString(R.string.sftp_success_create_file) } returns "Fichier créé"
        coEvery { createFileUseCase(any(), any(), any()) } returns SshResult.Success(Unit)
        viewModel.navigateTo("/home/user/documents")
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.createFile("notes.txt")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { createFileUseCase("session-1", "/home/user/documents", "notes.txt") }
    }

    @Test
    fun `a file created after an error still on screen is not shown in red`() = runTest {
        setupInitialState("session-1", createTestFiles())
        showAnError()
        io.mockk.every { context.getString(R.string.sftp_success_create_file) } returns "Fichier créé"
        coEvery { createFileUseCase(any(), any(), any()) } returns SshResult.Success(Unit)

        viewModel.createFile("notes.txt")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("Fichier créé", viewModel.uiState.value.message)
        assertFalse(viewModel.uiState.value.messageIsError)
    }

    @Test
    fun `a name that is already taken closes the dialog and says so in red, without a reload`() = runTest {
        setupInitialState("session-1", createTestFiles())
        coEvery { createFileUseCase(any(), any(), any()) } returns
            SshResult.Error(SshErrorCode.UNKNOWN, SFTP_NAME_EXISTS_MESSAGE)
        io.mockk.every { context.getString(R.string.sftp_error_name_exists) } returns "An item with this name already exists"
        viewModel.showNewFileDialog()

        viewModel.createFile("file1.txt")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.newFileDialog)
        assertEquals("An item with this name already exists", state.message)
        assertTrue(state.messageIsError)
        // Nothing was created: the list of init is the only one, and the link is not in question.
        coVerify(exactly = 1) { listDirectoryUseCase(any(), any(), any(), any()) }
        assertFalse(state.isDisconnected)
    }

    @Test
    fun `a refusal of the server closes the dialog and says it in the language of the user`() = runTest {
        setupInitialState("session-1", createTestFiles())
        coEvery { createFileUseCase(any(), any(), any()) } returns
            SshResult.Error(SshErrorCode.UNKNOWN, SFTP_CREATE_FILE_FAILED_MESSAGE)
        io.mockk.every { context.getString(R.string.sftp_error_create_file_failed) } returns "Cannot create the file"
        viewModel.showNewFileDialog()

        viewModel.createFile("notes.txt")
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.uiState.value.newFileDialog)
        assertEquals("Cannot create the file", viewModel.uiState.value.message)
        assertTrue(viewModel.uiState.value.messageIsError)
    }

    @Test
    fun `a creation that loses the link puts the browser in the disconnected state`() = runTest {
        setupInitialState("session-1", createTestFiles())
        coEvery { createFileUseCase(any(), any(), any()) } returns
            SshResult.Error(SshErrorCode.UNKNOWN, SFTP_CONNECTION_LOST_MESSAGE)
        io.mockk.every { context.getString(R.string.sftp_error_connection) } returns "SFTP connection lost"
        viewModel.showNewFileDialog()

        viewModel.createFile("notes.txt")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.newFileDialog)
        assertEquals("SFTP connection lost", state.message)
        assertTrue(state.isDisconnected)
        // The SSH session is alive: only the channel is lost, the panel offers to reconnect.
        assertFalse(state.sessionEnded)
    }

    @Test
    fun `a creation that loses the link of a dead session offers the way back`() = runTest {
        setupInitialState("session-1", createTestFiles())
        coEvery { createFileUseCase(any(), any(), any()) } returns
            SshResult.Error(SshErrorCode.UNKNOWN, SFTP_CONNECTION_LOST_MESSAGE)
        io.mockk.every { context.getString(R.string.sftp_error_connection) } returns "SFTP connection lost"
        io.mockk.every { sessionManager.isConnected("session-1") } returns false

        viewModel.createFile("notes.txt")
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isDisconnected)
        assertTrue(viewModel.uiState.value.sessionEnded)
    }

    @Test
    fun `a creation without an SFTP client puts the browser in the disconnected state`() = runTest {
        setupInitialState("session-1", createTestFiles())
        coEvery { createFileUseCase(any(), any(), any()) } returns
            SshResult.Error(SshErrorCode.UNKNOWN, SFTP_NO_SESSION_MESSAGE)
        io.mockk.every { context.getString(R.string.sftp_transfer_no_session) } returns "SFTP session not ready"

        viewModel.createFile("notes.txt")
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isDisconnected)
    }

    @Test
    fun `a creation refused for another reason says nothing about the link`() = runTest {
        setupInitialState("session-1", createTestFiles())
        coEvery { createFileUseCase(any(), any(), any()) } returns
            SshResult.Error(SshErrorCode.UNKNOWN, SFTP_PERMISSION_DENIED_MESSAGE)
        io.mockk.every { context.getString(R.string.sftp_error_permission) } returns "Permission denied"

        viewModel.createFile("notes.txt")
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isDisconnected)
        verify(exactly = 0) { sessionManager.isConnected(any()) }
    }

    // ── The dialog of a creation closes when the creation starts ─────────────────

    @Test
    fun `the new file dialog closes when the creation starts, before the server has answered`() = runTest {
        setupInitialState("session-1", createTestFiles())
        io.mockk.every { context.getString(R.string.sftp_success_create_file) } returns "Fichier créé"
        val answer = CompletableDeferred<Unit>()
        coEvery { createFileUseCase(any(), any(), any()) } coAnswers {
            answer.await()
            SshResult.Success(Unit)
        }
        viewModel.showNewFileDialog()

        viewModel.createFile("notes.txt")

        // Still waiting for the server: a second touch on "Create" has no button left to land on.
        assertFalse(viewModel.uiState.value.newFileDialog)
        assertNull(viewModel.uiState.value.message)

        answer.complete(Unit)
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.uiState.value.newFileDialog)
        assertEquals("Fichier créé", viewModel.uiState.value.message)
        assertFalse(viewModel.uiState.value.messageIsError)
        coVerify(exactly = 1) { createFileUseCase(any(), any(), any()) }
    }

    @Test
    fun `the new file dialog stays closed when the creation is refused, the refusal goes to the message`() = runTest {
        setupInitialState("session-1", createTestFiles())
        val answer = CompletableDeferred<Unit>()
        coEvery { createFileUseCase(any(), any(), any()) } coAnswers {
            answer.await()
            SshResult.Error(SshErrorCode.UNKNOWN, SFTP_NAME_EXISTS_MESSAGE)
        }
        io.mockk.every { context.getString(R.string.sftp_error_name_exists) } returns "An item with this name already exists"
        viewModel.showNewFileDialog()

        viewModel.createFile("file1.txt")
        assertFalse(viewModel.uiState.value.newFileDialog)

        answer.complete(Unit)
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.uiState.value.newFileDialog)
        assertEquals("An item with this name already exists", viewModel.uiState.value.message)
        assertTrue(viewModel.uiState.value.messageIsError)
    }

    @Test
    fun `a new file dialog opened again while a creation runs is not closed by the result of that creation`() = runTest {
        setupInitialState("session-1", createTestFiles())
        io.mockk.every { context.getString(R.string.sftp_success_create_file) } returns "Fichier créé"
        val answer = CompletableDeferred<Unit>()
        coEvery { createFileUseCase(any(), any(), any()) } coAnswers {
            answer.await()
            SshResult.Success(Unit)
        }
        viewModel.showNewFileDialog()
        viewModel.createFile("notes.txt")

        viewModel.showNewFileDialog()
        answer.complete(Unit)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.newFileDialog)
    }

    @Test
    fun `the folder dialog closes when the creation starts, before the server has answered`() = runTest {
        setupInitialState("session-1", createTestFiles())
        io.mockk.every { context.getString(R.string.sftp_success_mkdir) } returns "Dossier créé"
        val answer = CompletableDeferred<Unit>()
        coEvery { createDirectoryUseCase(any(), any(), any()) } coAnswers {
            answer.await()
            SshResult.Success(Unit)
        }
        viewModel.showMkdirDialog()

        viewModel.createDirectory("new")

        assertFalse(viewModel.uiState.value.mkdirDialog)
        assertNull(viewModel.uiState.value.message)

        answer.complete(Unit)
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.uiState.value.mkdirDialog)
        assertEquals("Dossier créé", viewModel.uiState.value.message)
        assertFalse(viewModel.uiState.value.messageIsError)
        coVerify(exactly = 1) { createDirectoryUseCase("session-1", "/home/user", "new") }
    }

    @Test
    fun `the folder dialog stays closed when the creation is refused, the refusal goes to the message`() = runTest {
        setupInitialState("session-1", createTestFiles())
        val answer = CompletableDeferred<Unit>()
        coEvery { createDirectoryUseCase(any(), any(), any()) } coAnswers {
            answer.await()
            SshResult.Error(SshErrorCode.UNKNOWN, SFTP_FOLDER_EXISTS_MESSAGE)
        }
        io.mockk.every { context.getString(R.string.sftp_error_folder_exists) } returns "A folder with this name already exists"
        viewModel.showMkdirDialog()

        viewModel.createDirectory("documents")
        assertFalse(viewModel.uiState.value.mkdirDialog)

        answer.complete(Unit)
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.uiState.value.mkdirDialog)
        assertEquals("A folder with this name already exists", viewModel.uiState.value.message)
        assertTrue(viewModel.uiState.value.messageIsError)
    }

    @Test
    fun `a folder dialog opened again while a creation runs is not closed by the result of that creation`() = runTest {
        setupInitialState("session-1", createTestFiles())
        io.mockk.every { context.getString(R.string.sftp_success_mkdir) } returns "Dossier créé"
        val answer = CompletableDeferred<Unit>()
        coEvery { createDirectoryUseCase(any(), any(), any()) } coAnswers {
            answer.await()
            SshResult.Success(Unit)
        }
        viewModel.showMkdirDialog()
        viewModel.createDirectory("new")

        viewModel.showMkdirDialog()
        answer.complete(Unit)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.mkdirDialog)
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
        // Argument exact : le message doit recevoir le nom assaini. Avec le nom brut, le
        // mock strict n'aurait pas de réponse et le test échouerait.
        io.mockk.every {
            context.getString(R.string.sftp_transfer_upload_started, ".._.ssh_authorized_keys")
        } returns "Envoi de .._.ssh_authorized_keys démarré"
        // The check of the name runs on the sanitized path, never on the raw name of the provider.
        stubFreeUploadTarget("/home/user/.._.ssh_authorized_keys")

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
        io.mockk.every {
            context.getString(R.string.sftp_transfer_upload_started, "rapport.pdf")
        } returns "Envoi de rapport.pdf démarré"
        stubFreeUploadTarget("/home/user/rapport.pdf")

        viewModel.uploadFile(
            sessionId   = "session-1",
            remoteDir   = "/home/user/",
            sourceUri   = mockk<Uri>(),
            displayName = "rapport.pdf",
            fileSize    = 10L,
        )

        assertEquals("/home/user/rapport.pdf", extras[SftpTransferService.EXTRA_REMOTE_PATH])
        assertEquals("rapport.pdf", extras[SftpTransferService.EXTRA_DISPLAY_NAME])
        // A name that is free is sent without replacement: the server refuses it if a file appears meanwhile.
        assertEquals(false, booleanExtras[SftpTransferService.EXTRA_REPLACE_EXISTING])
        io.mockk.verify(exactly = 1) { ContextCompat.startForegroundService(any(), any()) }
        assertEquals("Envoi de rapport.pdf démarré", viewModel.uiState.value.message)
    }

    // ── uploadFile() : document virtuel ───────────────────────────────────────────

    @Test
    fun `uploadFile refuses a virtual document, says to export it and starts no service`() = runTest {
        val virtualMessage = "Ce document n'existe qu'en ligne, exportez-le d'abord"
        io.mockk.every { context.getString(R.string.sftp_error_source_virtual) } returns virtualMessage

        // Le contexte est un mock strict : créer l'Intent du service, ou dire « démarré », lèverait.
        viewModel.uploadFile(
            sessionId   = "session-1",
            remoteDir   = "/home/user",
            sourceUri   = mockk<Uri>(),
            displayName = "Rapport trimestriel",
            fileSize    = 0L,
            isVirtual   = true,
        )

        val state = viewModel.uiState.value
        assertEquals(virtualMessage, state.message)
        assertTrue(state.messageIsError)
        assertFalse(state.isDisconnected)
        io.mockk.verify(exactly = 0) { context.startForegroundService(any()) }
        io.mockk.verify(exactly = 0) { context.startService(any()) }
    }

    @Test
    fun `uploadFile says the document is virtual before it judges the name`() = runTest {
        val virtualMessage = "Ce document n'existe qu'en ligne, exportez-le d'abord"
        io.mockk.every { context.getString(R.string.sftp_error_source_virtual) } returns virtualMessage

        // Le nom est inexploitable aussi, mais c'est le document qu'on ne peut pas lire : le message
        // du nom (non stubbé ici, donc qui lèverait) n'est pas celui qu'il faut montrer.
        viewModel.uploadFile(
            sessionId   = "session-1",
            remoteDir   = "/home/user",
            sourceUri   = mockk<Uri>(),
            displayName = "..",
            isVirtual   = true,
        )

        assertEquals(virtualMessage, viewModel.uiState.value.message)
        assertTrue(viewModel.uiState.value.messageIsError)
        io.mockk.verify(exactly = 0) { context.startForegroundService(any()) }
    }

    @Test
    fun `the short uploadFile passes the virtual document on and starts no service`() = runTest {
        setupInitialState("session-1", createTestFiles())
        val virtualMessage = "Ce document n'existe qu'en ligne, exportez-le d'abord"
        io.mockk.every { context.getString(R.string.sftp_error_source_virtual) } returns virtualMessage

        viewModel.uploadFile(mockk<Uri>(), "Rapport trimestriel", 0L, true)

        assertEquals(virtualMessage, viewModel.uiState.value.message)
        assertTrue(viewModel.uiState.value.messageIsError)
        io.mockk.verify(exactly = 0) { context.startForegroundService(any()) }
    }

    @Test
    fun `uploadFile on a virtual document of a session that is gone says the session ended, not the document`() = runTest {
        setupInitialState("session-1", createTestFiles())
        io.mockk.every { sessionManager.isConnected("session-1") } returns false

        viewModel.uploadFile("session-1", "/home/user", mockk<Uri>(), "Rapport", 0L, isVirtual = true)

        val state = viewModel.uiState.value
        assertTrue(state.isDisconnected)
        assertTrue(state.sessionEnded)
        assertNull(state.message)
    }

    @Test
    fun `uploadFile with a document that is not virtual starts the service as before`() = runTest {
        val extras = captureTransferIntentExtras()
        io.mockk.every {
            context.getString(R.string.sftp_transfer_upload_started, "rapport.pdf")
        } returns "Envoi de rapport.pdf démarré"
        stubFreeUploadTarget("/home/user/rapport.pdf")

        viewModel.uploadFile(
            sessionId   = "session-1",
            remoteDir   = "/home/user",
            sourceUri   = mockk<Uri>(),
            displayName = "rapport.pdf",
            fileSize    = 10L,
            isVirtual   = false,
        )

        assertEquals("/home/user/rapport.pdf", extras[SftpTransferService.EXTRA_REMOTE_PATH])
        io.mockk.verify(exactly = 1) { ContextCompat.startForegroundService(any(), any()) }
        val state = viewModel.uiState.value
        assertFalse(state.messageIsError)
        assertEquals("Envoi de rapport.pdf démarré", state.message)
    }

    // ── uploadFile() : un fichier porte déjà le nom ───────────────────────────────
    //
    // An upload never replaces a file on its own. Before anything is sent, the browser looks at what
    // has the name of the file (the "check"): a name that is free starts the upload, a file asks the
    // user, a folder or an entry that cannot be replaced is refused with a message, and a check that
    // cannot tell sends anyway, without replacement (the server refuses a taken name when it opens the
    // file). Only "Replace" starts an upload that may overwrite. The view model is new and not
    // initialised (no `init`), as it is when the picker returns after the activity was recreated: the
    // session comes from the arguments. The context is a strict mock: a message that a test did not
    // stub fails it.

    private val uploadSessionId = "session-1"
    private val uploadDir = "/home/user"
    private val uploadPath = "/home/user/rapport.pdf"
    private val uploadSource = mockk<Uri>()

    /** The reservation the check takes is a real one over the mocks, so that giving it back is seen. */
    private fun uploadReservationTaken() {
        coEvery { sftpManager.reserve(uploadSessionId) } returns
            SshResult.Success(SftpReservation(sftpManager, sessionManager, uploadSessionId))
    }

    private fun uploadCheckFinds(target: UploadTarget) {
        uploadReservationTaken()
        coEvery { sftpManager.checkUploadTarget(uploadSessionId, uploadPath) } returns SshResult.Success(target)
    }

    private fun uploadCheckFails(message: String) {
        uploadReservationTaken()
        coEvery { sftpManager.checkUploadTarget(uploadSessionId, uploadPath) } returns
            SshResult.Error(SshErrorCode.UNKNOWN, message)
    }

    private fun stubUploadStarted() {
        io.mockk.every {
            context.getString(R.string.sftp_transfer_upload_started, "rapport.pdf")
        } returns "Envoi de rapport.pdf démarré"
    }

    /** The user picks "rapport.pdf": what the screen does when the picker returns. */
    private fun pickRapport() {
        viewModel.uploadFile(
            sessionId = uploadSessionId,
            remoteDir = uploadDir,
            sourceUri = uploadSource,
            displayName = "rapport.pdf",
            fileSize = 10L,
        )
    }

    private fun verifyUploadServiceNeverStarted() {
        verify(exactly = 0) { ContextCompat.startForegroundService(any(), any()) }
        verify(exactly = 0) { context.startForegroundService(any()) }
        verify(exactly = 0) { context.startService(any()) }
    }

    /** The reservation of the check was given back, once: the channel and the reference on the session. */
    private fun verifyUploadReservationGivenBackOnce() {
        coVerify(exactly = 1) { sftpManager.closeSftp(uploadSessionId) }
        verify(exactly = 1) { sessionManager.release(uploadSessionId) }
    }

    // A name that is free

    @Test
    fun `a name that is free starts the service without replacement`() = runTest {
        val extras = captureTransferIntentExtras()
        stubUploadStarted()
        uploadCheckFinds(UploadTarget.FREE)

        pickRapport()

        assertEquals(uploadPath, extras[SftpTransferService.EXTRA_REMOTE_PATH])
        assertEquals("rapport.pdf", extras[SftpTransferService.EXTRA_DISPLAY_NAME])
        assertEquals(uploadSessionId, extras[SftpTransferService.EXTRA_SESSION_ID])
        assertEquals(false, booleanExtras[SftpTransferService.EXTRA_REPLACE_EXISTING])
        verify(exactly = 1) { ContextCompat.startForegroundService(any(), any()) }
        val state = viewModel.uiState.value
        assertNull(state.uploadConflict)
        assertEquals("Envoi de rapport.pdf démarré", state.message)
        assertFalse(state.messageIsError)
    }

    @Test
    fun `the reservation of the check is given back after the service started, not before`() = runTest {
        captureTransferIntentExtras()
        stubUploadStarted()
        uploadCheckFinds(UploadTarget.FREE)

        pickRapport()

        verifyUploadReservationGivenBackOnce()
        // The service takes its own reservation: the session is held all along, never left alone.
        io.mockk.verifyOrder {
            ContextCompat.startForegroundService(any(), any())
            sessionManager.release(uploadSessionId)
        }
    }

    // A file that has the name

    @Test
    fun `a file that has the name holds the upload and asks, it starts nothing and says nothing`() = runTest {
        captureTransferIntentExtras()
        uploadCheckFinds(UploadTarget.FILE)

        pickRapport()

        val state = viewModel.uiState.value
        assertEquals(UploadConflictState(uploadSessionId, uploadPath, uploadSource, "rapport.pdf", 10L), state.uploadConflict)
        // The dialog names the folder of the pick, as it is: the parent of the path.
        assertEquals("/home/user", state.uploadConflict!!.folder)
        assertNull(state.message)
        assertFalse(state.isDisconnected)
        verifyUploadServiceNeverStarted()
        verifyUploadReservationGivenBackOnce()
    }

    @Test
    fun `the conflict names the folder of the pick, not the one the browser shows meanwhile`() = runTest {
        // The browser is in /home/user when the user picks a file for /srv/data, then it goes on showing
        // /home/user while the name is checked: the dialog and the upload follow the pick.
        val extras = captureTransferIntentExtras()
        stubUploadStarted()
        setupInitialState(uploadSessionId, createTestFiles())
        assertEquals("/home/user", viewModel.uiState.value.currentPath)
        val path = "/srv/data/rapport.pdf"
        uploadReservationTaken()
        coEvery { sftpManager.checkUploadTarget(uploadSessionId, path) } returns SshResult.Success(UploadTarget.FILE)

        viewModel.uploadFile(uploadSessionId, "/srv/data", uploadSource, "rapport.pdf", 10L)

        val conflict = viewModel.uiState.value.uploadConflict
        assertEquals("/srv/data", conflict!!.folder)
        assertEquals("/home/user", viewModel.uiState.value.currentPath)

        viewModel.confirmUploadReplace()

        assertEquals(path, extras[SftpTransferService.EXTRA_REMOTE_PATH])
    }

    @Test
    fun `the folder of a conflict is the root for a file at the root, and has no slash at its end`() {
        val atRoot = UploadConflictState(uploadSessionId, "/rapport.pdf", uploadSource, "rapport.pdf", 10L)
        val nested = UploadConflictState(uploadSessionId, "/home/user/docs/rapport.pdf", uploadSource, "rapport.pdf", 10L)

        assertEquals("/", atRoot.folder)
        assertEquals("/home/user/docs", nested.folder)
    }

    @Test
    fun `a destination given with a slash at its end gives the same folder in the dialog`() = runTest {
        captureTransferIntentExtras()
        uploadReservationTaken()
        coEvery { sftpManager.checkUploadTarget(uploadSessionId, uploadPath) } returns SshResult.Success(UploadTarget.FILE)

        viewModel.uploadFile(uploadSessionId, "/home/user/", uploadSource, "rapport.pdf", 10L)

        assertEquals("/home/user", viewModel.uiState.value.uploadConflict!!.folder)
    }

    @Test
    fun `replace starts the upload with the replacement, closes the dialog and says the upload started`() = runTest {
        val extras = captureTransferIntentExtras()
        stubUploadStarted()
        uploadCheckFinds(UploadTarget.FILE)
        pickRapport()

        viewModel.confirmUploadReplace()

        assertEquals(true, booleanExtras[SftpTransferService.EXTRA_REPLACE_EXISTING])
        assertEquals(uploadPath, extras[SftpTransferService.EXTRA_REMOTE_PATH])
        assertEquals("rapport.pdf", extras[SftpTransferService.EXTRA_DISPLAY_NAME])
        assertEquals(uploadSessionId, extras[SftpTransferService.EXTRA_SESSION_ID])
        verify(exactly = 1) { ContextCompat.startForegroundService(any(), any()) }
        val state = viewModel.uiState.value
        assertNull(state.uploadConflict)
        assertEquals("Envoi de rapport.pdf démarré", state.message)
        assertFalse(state.messageIsError)
    }

    @Test
    fun `a second touch on replace starts nothing more`() = runTest {
        captureTransferIntentExtras()
        stubUploadStarted()
        uploadCheckFinds(UploadTarget.FILE)
        pickRapport()

        viewModel.confirmUploadReplace()
        viewModel.confirmUploadReplace()

        verify(exactly = 1) { ContextCompat.startForegroundService(any(), any()) }
    }

    @Test
    fun `cancel closes the dialog and starts nothing, says nothing`() = runTest {
        captureTransferIntentExtras()
        uploadCheckFinds(UploadTarget.FILE)
        pickRapport()

        viewModel.dismissUploadConflict()

        val state = viewModel.uiState.value
        assertNull(state.uploadConflict)
        assertNull(state.message)
        verifyUploadServiceNeverStarted()
    }

    @Test
    fun `replace after cancel does nothing`() = runTest {
        captureTransferIntentExtras()
        uploadCheckFinds(UploadTarget.FILE)
        pickRapport()
        viewModel.dismissUploadConflict()

        viewModel.confirmUploadReplace()

        assertNull(viewModel.uiState.value.message)
        verifyUploadServiceNeverStarted()
    }

    @Test
    fun `replace without a conflict has no effect`() = runTest {
        // No Intent is intercepted and no message is stubbed: the context is strict, anything the call
        // did would have thrown.
        viewModel.confirmUploadReplace()

        val state = viewModel.uiState.value
        assertNull(state.uploadConflict)
        assertNull(state.message)
        assertFalse(state.isDisconnected)
        verify(exactly = 0) { context.startForegroundService(any()) }
    }

    @Test
    fun `replace on a session that is gone meanwhile starts nothing and puts the browser in the session ended state`() = runTest {
        captureTransferIntentExtras()
        uploadCheckFinds(UploadTarget.FILE)
        pickRapport()
        io.mockk.every { sessionManager.isConnected(uploadSessionId) } returns false

        viewModel.confirmUploadReplace()

        val state = viewModel.uiState.value
        assertNull(state.uploadConflict)
        assertTrue(state.isDisconnected)
        assertTrue(state.sessionEnded)
        assertNull(state.message)
        verifyUploadServiceNeverStarted()
    }

    // A folder, or an entry that cannot be replaced

    @Test
    fun `a folder that has the name is refused with a message that names the file, and starts nothing`() = runTest {
        captureTransferIntentExtras()
        io.mockk.every {
            context.getString(R.string.sftp_upload_target_is_folder, "rapport.pdf")
        } returns "Un dossier porte déjà le nom rapport.pdf ici"
        uploadCheckFinds(UploadTarget.DIRECTORY)

        pickRapport()

        val state = viewModel.uiState.value
        assertEquals("Un dossier porte déjà le nom rapport.pdf ici", state.message)
        assertTrue(state.messageIsError)
        assertNull(state.uploadConflict)
        verifyUploadServiceNeverStarted()
        verifyUploadReservationGivenBackOnce()
    }

    @Test
    fun `an entry that cannot be replaced is refused with a message that names the file, and starts nothing`() = runTest {
        captureTransferIntentExtras()
        io.mockk.every {
            context.getString(R.string.sftp_upload_target_not_replaceable, "rapport.pdf")
        } returns "Un élément nommé rapport.pdf existe déjà et ne peut pas être remplacé"
        uploadCheckFinds(UploadTarget.OTHER)

        pickRapport()

        val state = viewModel.uiState.value
        assertEquals("Un élément nommé rapport.pdf existe déjà et ne peut pas être remplacé", state.message)
        assertTrue(state.messageIsError)
        assertNull(state.uploadConflict)
        verifyUploadServiceNeverStarted()
        verifyUploadReservationGivenBackOnce()
    }

    // The reservation of the check

    @Test
    fun `a session that is not found at the reservation puts the browser in the session ended state, without a message`() = runTest {
        captureTransferIntentExtras()
        coEvery { sftpManager.reserve(uploadSessionId) } returns
            SshResult.Error(SshErrorCode.UNKNOWN, SFTP_SESSION_NOT_FOUND_MESSAGE)

        pickRapport()

        val state = viewModel.uiState.value
        assertTrue(state.isDisconnected)
        assertTrue(state.sessionEnded)
        assertNull(state.message)
        assertFalse(state.uploadCheckInProgress)
        verifyUploadServiceNeverStarted()
        // Nothing was reserved, so nothing is given back, and nothing was checked.
        coVerify(exactly = 0) { sftpManager.checkUploadTarget(any(), any()) }
        coVerify(exactly = 0) { sftpManager.closeSftp(any()) }
        verify(exactly = 0) { sessionManager.release(any()) }
    }

    @Test
    fun `a channel that does not open at the reservation says it and starts nothing`() = runTest {
        captureTransferIntentExtras()
        coEvery { sftpManager.reserve(uploadSessionId) } returns
            SshResult.Error(SshErrorCode.UNKNOWN, SFTP_OPEN_FAILED_MESSAGE)
        io.mockk.every { context.getString(R.string.sftp_error_open_failed) } returns "Cannot open SFTP"

        pickRapport()

        val state = viewModel.uiState.value
        assertEquals("Cannot open SFTP", state.message)
        assertTrue(state.messageIsError)
        assertFalse(state.isDisconnected)
        assertFalse(state.uploadCheckInProgress)
        verifyUploadServiceNeverStarted()
        coVerify(exactly = 0) { sftpManager.checkUploadTarget(any(), any()) }
        coVerify(exactly = 0) { sftpManager.closeSftp(any()) }
    }

    @Test
    fun `the reservation is taken for the session of the arguments, never for an empty one`() = runTest {
        // A view model that has no session yet refuses before anything is reserved.
        io.mockk.every { context.getString(R.string.sftp_transfer_no_session) } returns "Session not ready"

        viewModel.uploadFile("", "/", uploadSource, "notes.txt", 10L)

        assertEquals("Session not ready", viewModel.uiState.value.message)
        assertFalse(viewModel.uiState.value.uploadCheckInProgress)
        coVerify(exactly = 0) { sftpManager.reserve(any()) }
    }

    @Test
    fun `a check that is really cancelled still gives the reservation back, and starts nothing`() = runTest {
        captureTransferIntentExtras()
        uploadReservationTaken()
        // The check never answers: only the cancellation of the scope ends it.
        coEvery { sftpManager.checkUploadTarget(uploadSessionId, uploadPath) } coAnswers { awaitCancellation() }
        var channelClosed = 0
        coEvery { sftpManager.closeSftp(uploadSessionId) } coAnswers {
            // In a scope that is cancelled, a closing that is not protected throws here, and the
            // channel stays counted for ever: ensureActive is what the protection is seen by.
            currentCoroutineContext().ensureActive()
            channelClosed++
            SshResult.Success(Unit)
        }

        pickRapport()
        assertTrue(viewModel.uiState.value.uploadCheckInProgress, "the check is under way")
        viewModel.viewModelScope.cancel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, channelClosed, "the channel of the reservation was closed, once")
        verify(exactly = 1) { sessionManager.release(uploadSessionId) }
        verifyUploadServiceNeverStarted()
        assertNull(viewModel.uiState.value.uploadConflict)
        assertFalse(viewModel.uiState.value.uploadCheckInProgress, "the button is given back")
    }

    @Test
    fun `a scope cancelled while the reservation is being taken still gives that reservation back, and checks nothing`() = runTest {
        captureTransferIntentExtras()
        val reserving = CompletableDeferred<Unit>()
        // SftpManager.reserve counts a user of the SFTP client inside the call: it must run to its end.
        coEvery { sftpManager.reserve(uploadSessionId) } coAnswers {
            reserving.await()
            SshResult.Success(SftpReservation(sftpManager, sessionManager, uploadSessionId))
        }
        var channelClosed = 0
        coEvery { sftpManager.closeSftp(uploadSessionId) } coAnswers {
            currentCoroutineContext().ensureActive()
            channelClosed++
            SshResult.Success(Unit)
        }

        pickRapport()
        viewModel.viewModelScope.cancel()
        reserving.complete(Unit)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, channelClosed, "the reservation that came after the cancellation was given back")
        verify(exactly = 1) { sessionManager.release(uploadSessionId) }
        coVerify(exactly = 0) { sftpManager.checkUploadTarget(any(), any()) }
        verifyUploadServiceNeverStarted()
        assertFalse(viewModel.uiState.value.uploadCheckInProgress)
    }

    // The button is held back while the name is checked

    @Test
    fun `the upload is flagged as being checked from the pick to the decision, then the flag goes down`() = runTest {
        captureTransferIntentExtras()
        stubUploadStarted()
        val reserving = CompletableDeferred<Unit>()
        coEvery { sftpManager.reserve(uploadSessionId) } coAnswers {
            reserving.await()
            SshResult.Success(SftpReservation(sftpManager, sessionManager, uploadSessionId))
        }
        coEvery { sftpManager.checkUploadTarget(uploadSessionId, uploadPath) } returns SshResult.Success(UploadTarget.FREE)
        assertFalse(viewModel.uiState.value.uploadCheckInProgress)

        pickRapport()
        assertTrue(viewModel.uiState.value.uploadCheckInProgress, "nothing is decided yet")
        assertNull(viewModel.uiState.value.message)

        reserving.complete(Unit)
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.uiState.value.uploadCheckInProgress)
        assertEquals("Envoi de rapport.pdf démarré", viewModel.uiState.value.message)
    }

    @Test
    fun `the flag goes down when the dialog asks, the answer is the user's`() = runTest {
        captureTransferIntentExtras()
        uploadCheckFinds(UploadTarget.FILE)

        pickRapport()

        val state = viewModel.uiState.value
        assertNotNull(state.uploadConflict)
        assertFalse(state.uploadCheckInProgress)
    }

    @Test
    fun `the flag goes down when the check ends on a lost link too`() = runTest {
        captureTransferIntentExtras()
        io.mockk.every { context.getString(R.string.sftp_error_connection) } returns "SFTP connection lost"
        uploadCheckFails(SFTP_CONNECTION_LOST_MESSAGE)

        pickRapport()

        assertFalse(viewModel.uiState.value.uploadCheckInProgress)
    }

    // A check that fails

    @Test
    fun `a link lost during the check says it, puts the browser in the disconnected state and starts nothing`() = runTest {
        captureTransferIntentExtras()
        io.mockk.every { context.getString(R.string.sftp_error_connection) } returns "SFTP connection lost"
        uploadCheckFails(SFTP_CONNECTION_LOST_MESSAGE)

        pickRapport()

        val state = viewModel.uiState.value
        assertEquals("SFTP connection lost", state.message)
        assertTrue(state.messageIsError)
        assertTrue(state.isDisconnected)
        // The SSH session is still there: only the channel is lost, the browser offers to reconnect.
        assertFalse(state.sessionEnded)
        assertNull(state.uploadConflict)
        verifyUploadServiceNeverStarted()
        verifyUploadReservationGivenBackOnce()
    }

    @Test
    fun `a link lost during the check on a session that died meanwhile says the session ended`() = runTest {
        captureTransferIntentExtras()
        io.mockk.every { context.getString(R.string.sftp_error_connection) } returns "SFTP connection lost"
        var alive = true
        io.mockk.every { sessionManager.isConnected(uploadSessionId) } answers { alive }
        uploadReservationTaken()
        coEvery { sftpManager.checkUploadTarget(uploadSessionId, uploadPath) } coAnswers {
            alive = false
            SshResult.Error(SshErrorCode.UNKNOWN, SFTP_CONNECTION_LOST_MESSAGE)
        }

        pickRapport()

        val state = viewModel.uiState.value
        assertTrue(state.isDisconnected)
        assertTrue(state.sessionEnded)
        verifyUploadServiceNeverStarted()
    }

    @Test
    fun `a check that the server refuses for lack of rights sends anyway, without replacement`() = runTest {
        captureTransferIntentExtras()
        stubUploadStarted()
        uploadCheckFails(SFTP_PERMISSION_DENIED_MESSAGE)

        pickRapport()

        // The check is a courtesy: the open of the file is exclusive, the server refuses a name that is taken.
        assertEquals(false, booleanExtras[SftpTransferService.EXTRA_REPLACE_EXISTING])
        verify(exactly = 1) { ContextCompat.startForegroundService(any(), any()) }
        val state = viewModel.uiState.value
        assertEquals("Envoi de rapport.pdf démarré", state.message)
        assertFalse(state.isDisconnected)
        assertNull(state.uploadConflict)
        verifyUploadReservationGivenBackOnce()
    }

    @Test
    fun `a check that fails for no reason it can name sends anyway, without replacement`() = runTest {
        captureTransferIntentExtras()
        stubUploadStarted()
        uploadCheckFails(SFTP_UNKNOWN_MESSAGE)

        pickRapport()

        assertEquals(false, booleanExtras[SftpTransferService.EXTRA_REPLACE_EXISTING])
        verify(exactly = 1) { ContextCompat.startForegroundService(any(), any()) }
        assertFalse(viewModel.uiState.value.isDisconnected)
        verifyUploadReservationGivenBackOnce()
    }

    // The failure that the service reports

    @Test
    fun `an upload that the server refused for a name that is taken says why, in the language of the user`() = runTest {
        val request = TransferRequest(
            id = "t3", sessionId = uploadSessionId, remotePath = uploadPath,
            localUri = mockk<Uri>(), direction = TransferDirection.UPLOAD,
            fileSize = 10L, displayName = "rapport.pdf",
        )
        val failed = TransferState(request, TransferStatus.FAILED, errorMessage = SFTP_UPLOAD_TARGET_EXISTS_MESSAGE)
        io.mockk.every { transferTracker.transfers } returns MutableStateFlow(mapOf("t3" to failed))
        io.mockk.every { context.getString(R.string.sftp_transfer_target_exists) } returns "A file already has this name"
        io.mockk.every {
            context.getString(R.string.sftp_transfer_failed_detail, "rapport.pdf", "A file already has this name")
        } returns "Transfer of rapport.pdf failed: A file already has this name"
        sftpOpens(uploadSessionId)
        viewModel.setVisible(true)

        viewModel.init(uploadSessionId, "Server")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Transfer of rapport.pdf failed: A file already has this name", state.message)
        assertTrue(state.messageIsError)
        verify(exactly = 1) { transferTracker.acknowledge("t3") }
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
        // The upload puts whether it replaces (SftpTransferService.EXTRA_REPLACE_EXISTING): without
        // this stub the mock of the constructor has no answer for it ("Method not mocked").
        booleanExtras.clear()
        io.mockk.every { anyConstructed<Intent>().putExtra(any<String>(), any<Boolean>()) } answers {
            booleanExtras[firstArg()] = secondArg()
            self as Intent
        }
        io.mockk.justRun { ContextCompat.startForegroundService(any(), any()) }
        return extras
    }

    /** The boolean extras the last [captureTransferIntentExtras] saw put in the intent of the service. */
    private val booleanExtras = mutableMapOf<String, Boolean>()

    /**
     * The check of the name finds nothing at [remotePath]: the upload starts. The reservation that the
     * check takes is a real one over the mocks of the test, so that giving it back is seen.
     */
    private fun stubFreeUploadTarget(remotePath: String, sessionId: String = "session-1") {
        coEvery { sftpManager.reserve(sessionId) } returns
            SshResult.Success(SftpReservation(sftpManager, sessionManager, sessionId))
        coEvery { sftpManager.checkUploadTarget(sessionId, remotePath) } returns SshResult.Success(UploadTarget.FREE)
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
        // Refused before any check: the flag that holds the upload button back was never raised.
        assertFalse(state.uploadCheckInProgress)
        // ContextCompat.startForegroundService appelle l'une ou l'autre selon l'API.
        io.mockk.verify(exactly = 0) { context.startForegroundService(any()) }
        io.mockk.verify(exactly = 0) { context.startService(any()) }
    }

    // ── Restes d'envois interrompus ──────────────────────────────────────────────

    private fun leftoverFile(
        name: String = ".report.pdf.nextsh-part-1a2b3c4d",
        isDirectory: Boolean = false,
        isSymlink: Boolean = false,
    ) = SftpFile(
        name = name,
        path = "/home/user/$name",
        size = 10L,
        permissions = 384,
        isDirectory = isDirectory,
        isSymlink = isSymlink,
        modifiedAt = 700L,
    )

    private fun uploadOf(
        remotePath: String,
        status: TransferStatus,
        sessionId: String = "session-1",
        id: String = "t1",
    ) = TransferState(
        TransferRequest(
            id = id, sessionId = sessionId, remotePath = remotePath, localUri = mockk<Uri>(),
            direction = TransferDirection.UPLOAD, fileSize = 10L, displayName = "report.pdf", replaceExisting = true,
        ),
        status,
    )

    private fun trackerHolds(vararg transfers: TransferState) {
        io.mockk.every { transferTracker.transfers } returns MutableStateFlow(transfers.associateBy { it.request.id })
    }

    @Test
    fun `a listing with the temporary file of an interrupted upload reports it`() = runTest {
        val leftover = leftoverFile()
        setupInitialState("session-1", createTestFiles() + leftover)

        val state = viewModel.uiState.value
        assertEquals(listOf(leftover), state.uploadLeftovers)
        // It is also an entry of the list, the row carries the badge.
        assertTrue(leftover in state.files)
        assertFalse(state.leftoverBannerDismissed)
    }

    @Test
    fun `a listing without any leftover reports none`() = runTest {
        setupInitialState("session-1", createTestFiles())

        assertTrue(viewModel.uiState.value.uploadLeftovers.isEmpty())
    }

    @Test
    fun `a folder or a link with the name of a leftover is not reported`() = runTest {
        setupInitialState(
            "session-1",
            createTestFiles() + leftoverFile(isDirectory = true) +
                leftoverFile(".other.txt.nextsh-part-00000000", isSymlink = true),
        )

        assertTrue(viewModel.uiState.value.uploadLeftovers.isEmpty())
    }

    @Test
    fun `the temporary file of an upload of this session under way is not reported, and hidden with the hidden files`() = runTest {
        val own = leftoverFile()
        io.mockk.every { sftpManager.knownUploadTempNames() } returns setOf(own.name)
        setupInitialState("session-1", createTestFiles() + own)

        val state = viewModel.uiState.value
        assertTrue(state.uploadLeftovers.isEmpty())
        assertFalse(own in state.files)
    }

    @Test
    fun `a temporary file written for a link that was followed is not reported, the exact path counts`() = runTest {
        // The upload was asked for /home/user/link.pdf, a link: the manager writes the temporary file next
        // to the real target, under the name of that target. Only the exact path tells it is being written.
        val own = leftoverFile()
        trackerHolds(uploadOf("/home/user/link.pdf", TransferStatus.IN_PROGRESS))
        io.mockk.every { sftpManager.knownUploadTempNames() } returns setOf(own.name)
        setupInitialState("session-1", createTestFiles() + own)

        assertTrue(viewModel.uiState.value.uploadLeftovers.isEmpty())
        assertFalse(own in viewModel.uiState.value.files)
    }

    @Test
    fun `a temporary file written by another session of the app is not reported either`() = runTest {
        // The manager is shared by every session: the set holds the temporary files of all of them.
        val own = leftoverFile()
        trackerHolds(uploadOf("/home/user/report.pdf", TransferStatus.IN_PROGRESS, sessionId = "session-2"))
        io.mockk.every { sftpManager.knownUploadTempNames() } returns setOf(own.name)
        setupInitialState("session-1", createTestFiles() + own)

        assertTrue(viewModel.uiState.value.uploadLeftovers.isEmpty())
    }

    @Test
    fun `a temporary file that was finished while the folder was being listed is not reported`() = runTest {
        val own = leftoverFile()
        // Before the listing it is being written, after it the upload is over.
        io.mockk.every { sftpManager.knownUploadTempNames() } returnsMany listOf(setOf(own.name), emptySet())
        setupInitialState("session-1", createTestFiles() + own)

        assertTrue(viewModel.uiState.value.uploadLeftovers.isEmpty())
    }

    @Test
    fun `a temporary file that was created while the folder was being listed is not reported`() = runTest {
        val own = leftoverFile()
        io.mockk.every { sftpManager.knownUploadTempNames() } returnsMany listOf(emptySet(), setOf(own.name))
        setupInitialState("session-1", createTestFiles() + own)

        assertTrue(viewModel.uiState.value.uploadLeftovers.isEmpty())
    }

    @Test
    fun `a listing that is overtaken by a more recent navigation is dropped`() = runTest {
        val gate = CompletableDeferred<Unit>()
        setupInitialState("session-1", createTestFiles())
        val slow = SftpFile(name = "slow.txt", path = "/slow/slow.txt", size = 1L, permissions = 420, isDirectory = false)
        val quick = SftpFile(name = "quick.txt", path = "/quick/quick.txt", size = 1L, permissions = 420, isDirectory = false)
        coEvery { listDirectoryUseCase("session-1", "/slow", any(), any()) } coAnswers {
            gate.await()
            SshResult.Success(listOf(slow))
        }
        coEvery { listDirectoryUseCase("session-1", "/quick", any(), any()) } returns SshResult.Success(listOf(quick))

        viewModel.navigateTo("/slow")
        viewModel.navigateTo("/quick")
        gate.complete(Unit)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("/quick", state.currentPath)
        assertEquals(listOf(quick), state.files)
        assertFalse(state.isLoading)
    }

    @Test
    fun `the temporary file of an upload under way is an ordinary hidden file when the hidden files are on`() = runTest {
        val own = leftoverFile()
        io.mockk.every { sftpManager.knownUploadTempNames() } returns setOf(own.name)
        setupInitialState("session-1", createTestFiles() + own)

        viewModel.toggleHiddenFiles()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.showHiddenFiles)
        assertTrue(own in state.files)
        assertTrue(state.uploadLeftovers.isEmpty())
    }

    @Test
    fun `a temporary file the manager does not write is reported, whatever the transfers say`() = runTest {
        val leftover = leftoverFile()
        // Transfers of the same name that do not write this file: finished, failed, cancelled, a download.
        val download = TransferState(
            TransferRequest(
                id = "t5", sessionId = "session-1", remotePath = "/home/user/report.pdf", localUri = mockk<Uri>(),
                direction = TransferDirection.DOWNLOAD, fileSize = 10L, displayName = "report.pdf",
            ),
            TransferStatus.IN_PROGRESS,
        )
        trackerHolds(
            uploadOf("/home/user/report.pdf", TransferStatus.COMPLETED, id = "t1"),
            uploadOf("/home/user/report.pdf", TransferStatus.FAILED, id = "t3"),
            uploadOf("/home/user/report.pdf", TransferStatus.CANCELLED, id = "t4"),
            download,
        )
        io.mockk.every { sftpManager.knownUploadTempNames() } returns setOf(".other.txt.nextsh-part-00000000")
        setupInitialState("session-1", createTestFiles() + leftover)

        assertEquals(listOf(leftover), viewModel.uiState.value.uploadLeftovers)
        assertTrue(leftover in viewModel.uiState.value.files)
    }

    @Test
    fun `a link lost during the deletion of leftovers puts the browser in its disconnected state`() = runTest {
        val first = leftoverFile()
        setupInitialState("session-1", createTestFiles() + first)
        io.mockk.every { context.resources } returns resources
        io.mockk.every { context.getString(R.string.sftp_multi_delete_none) } returns "Aucun élément n'a pu être supprimé"
        io.mockk.every { context.getString(R.string.sftp_error_connection) } returns "SFTP connection lost"
        coEvery { deleteFileUseCase(any(), any(), any()) } returns
            SshResult.Error(SshErrorCode.UNKNOWN, SFTP_CONNECTION_LOST_MESSAGE)
        coEvery { listDirectoryUseCase(any(), any(), any(), any()) } returns
            SshResult.Error(SshErrorCode.UNKNOWN, SFTP_CONNECTION_LOST_MESSAGE)

        viewModel.showLeftoverDeleteConfirmation(listOf(first))
        viewModel.confirmLeftoverDelete()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isDisconnected)
        assertTrue(viewModel.uiState.value.messageIsError)
    }

    @Test
    fun `a temporary file seen under another path is not reported, the name counts and not the path`() = runTest {
        // The folder is reached through a link of folder: the listing shows the same file under a path that
        // is not the one the manager used when it created it.
        val seenThroughLink = leftoverFile().copy(path = "/via-link/.report.pdf.nextsh-part-1a2b3c4d")
        io.mockk.every { sftpManager.knownUploadTempNames() } returns setOf(seenThroughLink.name)
        setupInitialState("session-1", createTestFiles() + seenThroughLink)

        assertTrue(viewModel.uiState.value.uploadLeftovers.isEmpty())
        assertFalse(seenThroughLink in viewModel.uiState.value.files)
    }

    @Test
    fun `a temporary file left behind, unknown to the manager, stays reported`() = runTest {
        val left = leftoverFile()
        io.mockk.every { sftpManager.knownUploadTempNames() } returns setOf(".other.txt.nextsh-part-00000000")
        setupInitialState("session-1", createTestFiles() + left)

        assertEquals(listOf(left), viewModel.uiState.value.uploadLeftovers)
    }

    @Test
    fun `a refresh while the user navigates reloads the target of the navigation and not the old folder`() = runTest {
        setupInitialState("session-1", createTestFiles())
        val gate = CompletableDeferred<Unit>()
        val inA = SftpFile(name = "a.txt", path = "/a/a.txt", size = 1L, permissions = 420, isDirectory = false)
        coEvery { listDirectoryUseCase("session-1", "/a", any(), any()) } coAnswers {
            gate.await()
            SshResult.Success(listOf(inA))
        }

        viewModel.navigateTo("/a")
        // The end of an upload, a sort or the hidden files: they all go through refresh().
        viewModel.refresh()
        viewModel.toggleHiddenFiles()
        gate.complete(Unit)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("/a", state.currentPath)
        assertEquals(listOf(inA), state.files)
        coVerify(exactly = 0) { listDirectoryUseCase("session-1", "/home/user", any(), true) }
    }

    @Test
    fun `once a navigation ended a refresh reloads the folder on screen`() = runTest {
        setupInitialState("session-1", createTestFiles())
        val inA = SftpFile(name = "a.txt", path = "/a/a.txt", size = 1L, permissions = 420, isDirectory = false)
        coEvery { listDirectoryUseCase("session-1", "/a", any(), any()) } returns SshResult.Success(listOf(inA))

        viewModel.navigateTo("/a")
        viewModel.refresh()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("/a", viewModel.uiState.value.currentPath)
        coVerify(atLeast = 2) { listDirectoryUseCase("session-1", "/a", any(), any()) }
    }

    @Test
    fun `a leftover whose name became a temporary file being written is skipped by the deletion`() = runTest {
        val first = leftoverFile()
        val second = leftoverFile(".notes.txt.nextsh-part-00ff00ff")
        setupInitialState("session-1", createTestFiles() + first + second)
        io.mockk.every { context.resources } returns resources
        io.mockk.every { resources.getQuantityString(R.plurals.sftp_multi_delete_done, 1, 1) } returns "1 élément supprimé"
        coEvery { deleteFileUseCase(any(), any(), any()) } returns SshResult.Success(Unit)

        viewModel.showLeftoverDeleteConfirmation(listOf(first, second))
        // An upload of this app starts writing a temporary file with this very name after the dialog opened.
        io.mockk.every { sftpManager.knownUploadTempNames() } returns setOf(first.name)
        viewModel.confirmLeftoverDelete()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { deleteFileUseCase("session-1", first.path, any()) }
        coVerify(exactly = 1) { deleteFileUseCase("session-1", second.path, false) }
        assertEquals("1 élément supprimé", viewModel.uiState.value.message)
    }

    @Test
    fun `the deletion of leftovers deletes each exact file once and never recursively`() = runTest {
        val first = leftoverFile()
        val second = leftoverFile(".notes.txt.nextsh-part-00ff00ff")
        setupInitialState("session-1", createTestFiles() + first + second)
        io.mockk.every { context.resources } returns resources
        io.mockk.every { resources.getQuantityString(R.plurals.sftp_multi_delete_done, 2, 2) } returns "2 éléments supprimés"
        coEvery { deleteFileUseCase(any(), any(), any()) } returns SshResult.Success(Unit)

        viewModel.showLeftoverDeleteConfirmation(viewModel.uiState.value.uploadLeftovers)
        assertEquals(listOf(first, second), viewModel.uiState.value.leftoverDeleteConfirmation)
        viewModel.confirmLeftoverDelete()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { deleteFileUseCase("session-1", first.path, false) }
        coVerify(exactly = 1) { deleteFileUseCase("session-1", second.path, false) }
        coVerify(exactly = 0) { deleteFileUseCase(any(), any(), true) }
        val state = viewModel.uiState.value
        assertNull(state.leftoverDeleteConfirmation)
        assertEquals("2 éléments supprimés", state.message)
        assertFalse(state.messageIsError)
        // The folder is listed again: the initial listing, then the refresh after the deletion.
        coVerify(atLeast = 2) { listDirectoryUseCase(any(), any(), any(), any()) }
    }

    @Test
    fun `the deletion of leftovers that partly failed says so as an error`() = runTest {
        val first = leftoverFile()
        val second = leftoverFile(".notes.txt.nextsh-part-00ff00ff")
        setupInitialState("session-1", createTestFiles() + first + second)
        io.mockk.every { context.resources } returns resources
        io.mockk.every { resources.getQuantityString(R.plurals.sftp_multi_delete_done, 1, 1) } returns "1 élément supprimé"
        io.mockk.every { resources.getQuantityString(R.plurals.sftp_multi_delete_failed, 1, 1) } returns
            "1 n'a pas pu être supprimé"
        io.mockk.every {
            context.getString(R.string.sftp_multi_delete_partial, "1 élément supprimé", "1 n'a pas pu être supprimé")
        } returns "1 élément supprimé, 1 n'a pas pu être supprimé"
        coEvery { deleteFileUseCase(any(), first.path, false) } returns SshResult.Success(Unit)
        coEvery { deleteFileUseCase(any(), second.path, false) } returns
            SshResult.Error(SshErrorCode.UNKNOWN, SFTP_PERMISSION_DENIED_MESSAGE)

        viewModel.showLeftoverDeleteConfirmation(listOf(first, second))
        viewModel.confirmLeftoverDelete()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("1 élément supprimé, 1 n'a pas pu être supprimé", viewModel.uiState.value.message)
        assertTrue(viewModel.uiState.value.messageIsError)
    }

    @Test
    fun `the targets of the deletion dialog are fixed when it opens`() = runTest {
        val first = leftoverFile()
        setupInitialState("session-1", createTestFiles() + first)
        io.mockk.every { context.resources } returns resources
        io.mockk.every { resources.getQuantityString(R.plurals.sftp_multi_delete_done, 1, 1) } returns "1 élément supprimé"
        coEvery { deleteFileUseCase(any(), any(), any()) } returns SshResult.Success(Unit)

        viewModel.showLeftoverDeleteConfirmation(viewModel.uiState.value.uploadLeftovers)
        // A leftover appears while the dialog is open, then the folder is refreshed.
        val late = leftoverFile(".late.txt.nextsh-part-abcdef01")
        coEvery { listDirectoryUseCase(any(), any(), any(), any()) } returns
            SshResult.Success(createTestFiles() + first + late)
        viewModel.refresh()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.uploadLeftovers.size)
        assertEquals(listOf(first), viewModel.uiState.value.leftoverDeleteConfirmation)

        viewModel.confirmLeftoverDelete()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { deleteFileUseCase("session-1", first.path, false) }
        coVerify(exactly = 0) { deleteFileUseCase("session-1", late.path, any()) }
    }

    @Test
    fun `the deletion dialog only takes leftovers, not a folder or an ordinary file`() = runTest {
        setupInitialState("session-1", createTestFiles())

        viewModel.showLeftoverDeleteConfirmation(
            listOf(leftoverFile(isDirectory = true), leftoverFile(isSymlink = true), createTestFiles()[0]),
        )

        assertNull(viewModel.uiState.value.leftoverDeleteConfirmation)
    }

    @Test
    fun `a dismissed banner stays dismissed after a refresh and comes back in another folder`() = runTest {
        setupInitialState("session-1", createTestFiles() + leftoverFile())
        assertFalse(viewModel.uiState.value.leftoverBannerDismissed)

        viewModel.dismissLeftoverBanner()
        assertTrue(viewModel.uiState.value.leftoverBannerDismissed)
        viewModel.refresh()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value.leftoverBannerDismissed)
        // The leftovers are still reported: the rows keep their badge.
        assertEquals(1, viewModel.uiState.value.uploadLeftovers.size)

        viewModel.navigateTo("/home/other")
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(viewModel.uiState.value.leftoverBannerDismissed)

        viewModel.navigateTo("/home/user")
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value.leftoverBannerDismissed)
    }

    private suspend fun setupInitialState(sessionId: String, testFiles: List<SftpFile>) {
        coEvery { sftpManager.openSftp(sessionId) } returns SshResult.Success(Unit)
        coEvery { sftpManager.getHomeDirectory(sessionId) } returns SshResult.Success("/home/user")
        coEvery { listDirectoryUseCase(any(), any(), any(), any()) } returns SshResult.Success(testFiles)

        viewModel.init(sessionId)
        testDispatcher.scheduler.advanceUntilIdle()
    }
}

