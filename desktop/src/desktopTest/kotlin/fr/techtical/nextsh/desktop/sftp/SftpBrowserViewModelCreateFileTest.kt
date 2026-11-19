// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sftp

import fr.techtical.nextsh.desktop.core.ssh.DesktopSftpManager
import fr.techtical.nextsh.desktop.core.ssh.DesktopTransferManager
import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.sftp_file_created
import fr.techtical.nextsh.desktop.sessions.DesktopSessionManager
import fr.techtical.nextsh.shared.domain.model.SftpFile
import fr.techtical.nextsh.shared.domain.model.SortOrder
import fr.techtical.nextsh.shared.domain.model.SshErrorCode
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.shared.util.AppScope
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.getString
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [SftpBrowserViewModel] "new file" flow over a mocked [DesktopSftpManager]: the dialog
 * lifecycle, the success path (reload plus confirmation) and the error path.
 *
 * Real time on purpose: the ViewModel launches in the app scope and the confirmation text
 * comes from `getString`, which does real I/O, so the tests wait on the state flow instead
 * of advancing a virtual clock. The messages are compared with the resources, never with a
 * literal: the text depends on the machine language.
 */
class SftpBrowserViewModelCreateFileTest {

    private class TestAppScope(override val coroutineScope: CoroutineScope) : AppScope {
        override fun onDestroy() = coroutineScope.cancel()
    }

    private val app = TestAppScope(CoroutineScope(SupervisorJob() + Dispatchers.Default))
    private val sftpManager = mockk<DesktopSftpManager>()
    private val sessionManager = mockk<DesktopSessionManager>(relaxed = true)
    private val transferManager = mockk<DesktopTransferManager>(relaxed = true)

    @AfterTest
    fun tearDown() {
        app.onDestroy()
    }

    private fun file(name: String, dir: String = CWD) =
        SftpFile(name = name, path = "$dir/$name", size = 0L, permissions = 0b110_100_100, isDirectory = false)

    private fun newViewModel(initialCwd: String = CWD): SftpBrowserViewModel {
        every { sftpManager.sanitizePath(any()) } answers { firstArg<String>() }
        every { sftpManager.knownUploadTempNames() } returns emptySet()
        return SftpBrowserViewModel(
            sessionId = SESSION,
            tabId = "tab-1",
            paneId = "pane-1",
            initialCwd = initialCwd,
            sftpManager = sftpManager,
            sessionManager = sessionManager,
            transferManager = transferManager,
            appScope = app,
            inbox = SftpTransferInbox(),
        )
    }

    /** The first [SftpBrowserUiState.Loaded] of the view model that satisfies [condition]. */
    private suspend fun SftpBrowserViewModel.awaitLoaded(
        what: String,
        condition: (SftpBrowserUiState.Loaded) -> Boolean = { true },
    ): SftpBrowserUiState.Loaded {
        val reached = withTimeoutOrNull(5_000L) {
            state.first { it is SftpBrowserUiState.Loaded && condition(it) }
        }
        return reached as? SftpBrowserUiState.Loaded ?: error("timed out waiting for: $what")
    }

    /** The first non-null notice of the view model: the message of a failed operation. */
    private suspend fun SftpBrowserViewModel.awaitNotice(what: String): String =
        withTimeoutOrNull(5_000L) { notice.first { it != null } }
            ?: error("timed out waiting for: $what")

    private fun stubListings(vararg listings: List<SftpFile>) {
        coEvery { sftpManager.listDirectory(SESSION, any()) } returnsMany
            listings.map { SshResult.Success(it) }
    }

    // ── Dialog ────────────────────────────────────────────────────────────────

    @Test
    fun `the new file dialog opens and closes without touching the server`() = runBlocking<Unit> {
        stubListings(emptyList())
        val vm = newViewModel()
        vm.awaitLoaded("initial listing")

        vm.openDialog(SftpDialog.NewFile)
        assertEquals(SftpDialog.NewFile, (vm.state.value as SftpBrowserUiState.Loaded).dialog)

        vm.dismissDialog()
        assertNull((vm.state.value as SftpBrowserUiState.Loaded).dialog)
        coVerify(exactly = 0) { sftpManager.createFile(any(), any(), any()) }
    }

    // ── Success ───────────────────────────────────────────────────────────────

    @Test
    fun `createFile creates in the current folder, closes the dialog, reloads and confirms`() = runBlocking<Unit> {
        val created = file("notes.txt")
        stubListings(emptyList(), listOf(created))
        coEvery { sftpManager.createFile(SESSION, CWD, "notes.txt") } returns SshResult.Success(Unit)
        val vm = newViewModel()
        vm.awaitLoaded("initial listing")
        vm.openDialog(SftpDialog.NewFile)

        vm.createFile("notes.txt")
        val loaded = vm.awaitLoaded("reload after creation") { it.lastInfo != null }

        assertEquals(getString(Res.string.sftp_file_created), loaded.lastInfo)
        assertNull(vm.notice.value)
        assertNull(loaded.dialog)
        assertEquals(CWD, loaded.cwd)
        assertEquals(listOf(created), loaded.rawFiles)
        coVerify(exactly = 1) { sftpManager.createFile(SESSION, CWD, "notes.txt") }
        coVerify(exactly = 2) { sftpManager.listDirectory(SESSION, CWD) }
    }

    @Test
    fun `the dialog closes as soon as the creation starts, before the server answers`() = runBlocking<Unit> {
        stubListings(emptyList(), listOf(file("notes.txt")))
        val gate = CompletableDeferred<SshResult<Unit>>()
        coEvery { sftpManager.createFile(any(), any(), any()) } coAnswers { gate.await() }
        val vm = newViewModel()
        vm.awaitLoaded("initial listing")
        vm.openDialog(SftpDialog.NewFile)

        vm.createFile("notes.txt")

        assertNull((vm.state.value as SftpBrowserUiState.Loaded).dialog)
        gate.complete(SshResult.Success(Unit))
        vm.awaitLoaded("reload after creation") { it.lastInfo != null }
    }

    @Test
    fun `a file created at the root is requested for the root folder`() = runBlocking<Unit> {
        stubListings(emptyList(), listOf(file("notes.txt", dir = "")))
        coEvery { sftpManager.createFile(SESSION, "/", "notes.txt") } returns SshResult.Success(Unit)
        val vm = newViewModel(initialCwd = "/")
        vm.awaitLoaded("initial listing")

        vm.createFile("notes.txt")
        vm.awaitLoaded("reload after creation") { it.lastInfo != null }

        coVerify(exactly = 1) { sftpManager.createFile(SESSION, "/", "notes.txt") }
    }

    @Test
    fun `the confirmation is dismissed on demand and does not survive a navigation`() = runBlocking<Unit> {
        stubListings(emptyList(), listOf(file("notes.txt")), listOf(file("notes.txt")))
        coEvery { sftpManager.createFile(any(), any(), any()) } returns SshResult.Success(Unit)
        val vm = newViewModel()
        vm.awaitLoaded("initial listing")

        vm.createFile("notes.txt")
        vm.awaitLoaded("confirmation shown") { it.lastInfo != null }
        vm.dismissInfo()
        assertNull((vm.state.value as SftpBrowserUiState.Loaded).lastInfo)

        vm.createFile("again.txt")
        vm.awaitLoaded("confirmation shown again") { it.lastInfo != null }
        vm.navigateTo(CWD)
        val afterNavigation = vm.awaitLoaded("listing after navigation") { it.lastInfo == null }
        assertNull(afterNavigation.lastInfo)
    }

    // ── View options survive the reload ───────────────────────────────────────

    @Test
    fun `the reload after a creation keeps the hidden files toggle, so a new dotfile stays visible`() = runBlocking<Unit> {
        val created = file(".env")
        stubListings(listOf(file("a.txt")), listOf(file("a.txt"), created))
        coEvery { sftpManager.createFile(SESSION, CWD, ".env") } returns SshResult.Success(Unit)
        val vm = newViewModel()
        vm.awaitLoaded("initial listing")
        vm.toggleHidden()
        vm.setSortOrder(SortOrder.NAME_DESC)

        vm.createFile(".env")
        val loaded = vm.awaitLoaded("reload after creation") { it.lastInfo != null }

        assertTrue(loaded.hiddenVisible, "the toggle must survive the reload")
        assertEquals(SortOrder.NAME_DESC, loaded.sortOrder)
        assertTrue(created in loaded.visibleFiles, "the file just created must be listed")
    }

    @Test
    fun `refresh keeps the view options of the folder on screen`() = runBlocking<Unit> {
        stubListings(emptyList(), listOf(file(".env")))
        val vm = newViewModel()
        vm.awaitLoaded("initial listing")
        vm.toggleHidden()
        vm.setSortOrder(SortOrder.SIZE_DESC)

        vm.refresh()
        val loaded = vm.awaitLoaded("reload") { it.rawFiles.isNotEmpty() }

        assertTrue(loaded.hiddenVisible)
        assertEquals(SortOrder.SIZE_DESC, loaded.sortOrder)
    }

    @Test
    fun `navigating to another folder starts again from the default view options`() = runBlocking<Unit> {
        val other = "/home/other"
        stubListings(emptyList(), listOf(file("b.txt", dir = other)))
        val vm = newViewModel()
        vm.awaitLoaded("initial listing")
        vm.toggleHidden()
        vm.setSortOrder(SortOrder.DATE_ASC)

        vm.navigateTo(other)
        val loaded = vm.awaitLoaded("other folder") { it.cwd == other }

        assertFalse(loaded.hiddenVisible)
        assertEquals(SortOrder.NAME_ASC, loaded.sortOrder)
    }

    // ── The user moved on before the server answered ──────────────────────────

    @Test
    fun `the confirmation is not shown in another folder when the user navigated away meanwhile`() = runBlocking<Unit> {
        val other = "/home/other"
        stubListings(emptyList(), listOf(file("b.txt", dir = other)))
        val gate = CompletableDeferred<SshResult<Unit>>()
        coEvery { sftpManager.createFile(any(), any(), any()) } coAnswers { gate.await() }
        val vm = newViewModel()
        vm.awaitLoaded("initial listing")

        vm.createFile("notes.txt")
        vm.navigateTo(other)
        vm.awaitLoaded("other folder") { it.cwd == other }
        gate.complete(SshResult.Success(Unit))
        // Nothing observable to wait on: the expected outcome is that nothing happens.
        delay(300L)

        val loaded = vm.state.value as SftpBrowserUiState.Loaded
        assertEquals(other, loaded.cwd)
        assertNull(loaded.lastInfo)
        assertEquals(listOf(file("b.txt", dir = other)), loaded.rawFiles)
        coVerify(exactly = 2) { sftpManager.listDirectory(any(), any()) }
    }

    // ── Names reach the manager bare ──────────────────────────────────────────

    @Test
    fun `the name is passed bare to the manager, never joined to the folder`() = runBlocking<Unit> {
        stubListings(emptyList())
        coEvery { sftpManager.createFile(any(), any(), any()) } returns
            SshResult.Error(SshErrorCode.UNKNOWN, "invalid name")
        val vm = newViewModel()
        vm.awaitLoaded("initial listing")
        val names = listOf("a/b", "../x", "a\\b")

        for (name in names) {
            vm.createFile(name)
            assertEquals("invalid name", vm.awaitNotice("error shown for $name"))
            vm.dismissNotice()
        }

        for (name in names) {
            coVerify(exactly = 1) { sftpManager.createFile(SESSION, CWD, name) }
        }
        // No other combination: the folder is always the displayed one.
        coVerify(exactly = names.size) { sftpManager.createFile(any(), any(), any()) }
        coVerify(exactly = 1) { sftpManager.listDirectory(any(), any()) }
    }

    // ── Error ─────────────────────────────────────────────────────────────────

    @Test
    fun `a refused creation shows the manager message and neither reloads nor confirms`() = runBlocking<Unit> {
        stubListings(emptyList())
        coEvery { sftpManager.createFile(any(), any(), any()) } returns
            SshResult.Error(SshErrorCode.UNKNOWN, "refused by the server")
        val vm = newViewModel()
        vm.awaitLoaded("initial listing")
        vm.openDialog(SftpDialog.NewFile)

        vm.createFile("notes.txt")
        val message = vm.awaitNotice("error shown")
        val loaded = vm.awaitLoaded("listing still there")

        assertEquals("refused by the server", message)
        assertNull(loaded.lastInfo)
        assertNull(loaded.dialog)
        coVerify(exactly = 1) { sftpManager.listDirectory(any(), any()) }
    }

    @Test
    fun `dismissing the error clears it`() = runBlocking<Unit> {
        stubListings(emptyList())
        coEvery { sftpManager.createFile(any(), any(), any()) } returns SshResult.Error(SshErrorCode.UNKNOWN, "refused")
        val vm = newViewModel()
        vm.awaitLoaded("initial listing")
        vm.createFile("notes.txt")
        vm.awaitNotice("error shown")

        vm.dismissNotice()

        assertNull(vm.notice.value)
    }

    // ── Not loaded ────────────────────────────────────────────────────────────

    @Test
    fun `createFile is ignored while the folder is still loading`() = runBlocking<Unit> {
        val gate = CompletableDeferred<SshResult<List<SftpFile>>>()
        coEvery { sftpManager.listDirectory(SESSION, any()) } coAnswers { gate.await() }
        val vm = newViewModel()

        vm.createFile("notes.txt")

        assertTrue(vm.state.value is SftpBrowserUiState.Loading)
        coVerify(exactly = 0) { sftpManager.createFile(any(), any(), any()) }
        gate.complete(SshResult.Success(emptyList()))
        vm.awaitLoaded("initial listing")
    }

    private companion object {
        const val SESSION = "ssh-1"
        const val CWD = "/home/t"
    }
}
