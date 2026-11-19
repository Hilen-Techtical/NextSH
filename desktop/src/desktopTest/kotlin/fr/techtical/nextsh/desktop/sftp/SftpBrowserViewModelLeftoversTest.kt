// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sftp

import fr.techtical.nextsh.desktop.core.ssh.DesktopSftpManager
import fr.techtical.nextsh.desktop.core.ssh.DesktopTransferManager
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
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The leftover temporary files of interrupted uploads (`.name.nextsh-part-xxxxxxxx`) in
 * [SftpBrowserViewModel]: which entries are reported, that they stay in the list when the hidden files are
 * off, that the temporary file of an upload of this app that is under way is not one (by its exact path,
 * whatever the session), and that their deletion is a plain `rm` of each exact name, never a recursive
 * delete. Also the reload asked during a listing, the retry after a failed load, the banner dismissed
 * across view models, and a disposed view model.
 *
 * Real time on purpose, as in [SftpBrowserViewModelCreateFileTest]: the view model launches in the app scope.
 */
class SftpBrowserViewModelLeftoversTest {

    private class TestAppScope(override val coroutineScope: CoroutineScope) : AppScope {
        override fun onDestroy() = coroutineScope.cancel()
    }

    private val app = TestAppScope(CoroutineScope(SupervisorJob() + Dispatchers.Default))
    private val sftpManager = mockk<DesktopSftpManager>()
    private val sessionManager = mockk<DesktopSessionManager>(relaxed = true)
    private val transferManager = mockk<DesktopTransferManager>(relaxed = true)
    private val inbox = SftpTransferInbox()

    @AfterTest
    fun tearDown() {
        app.onDestroy()
    }

    private fun file(name: String, dir: String = CWD, isDirectory: Boolean = false, isSymlink: Boolean = false) =
        SftpFile(
            name = name,
            path = "$dir/$name",
            size = 3L,
            permissions = 0b110_000_000,
            isDirectory = isDirectory,
            isSymlink = isSymlink,
        )

    private fun newViewModel(initialCwd: String = CWD): SftpBrowserViewModel {
        every { sftpManager.sanitizePath(any()) } answers { firstArg<String>() }
        return SftpBrowserViewModel(
            sessionId = SESSION,
            tabId = "tab-1",
            paneId = "pane-1",
            initialCwd = initialCwd,
            sftpManager = sftpManager,
            sessionManager = sessionManager,
            transferManager = transferManager,
            appScope = app,
            inbox = inbox,
        )
    }

    private suspend fun SftpBrowserViewModel.awaitLoaded(
        what: String,
        condition: (SftpBrowserUiState.Loaded) -> Boolean = { true },
    ): SftpBrowserUiState.Loaded {
        val reached = withTimeoutOrNull(5_000L) {
            state.first { it is SftpBrowserUiState.Loaded && condition(it) }
        }
        return reached as? SftpBrowserUiState.Loaded ?: error("timed out waiting for: $what")
    }

    private fun stubListings(vararg listings: List<SftpFile>) {
        coEvery { sftpManager.listDirectory(SESSION, any()) } returnsMany listings.map { SshResult.Success(it) }
    }

    /** The NAMES of the temporary files under way or known gone that the manager reports (none unless stated). */
    private fun stubLive(vararg names: String) {
        every { sftpManager.knownUploadTempNames() } returns names.toSet()
        every { sftpManager.isUploadTempUnderWay(any()) } returns false
    }

    private fun stubDeletes() {
        coEvery { sftpManager.delete(SESSION, any()) } returns SshResult.Success(Unit)
        coEvery { sftpManager.deleteRecursive(SESSION, any()) } returns SshResult.Success(Unit)
    }

    private val leftover = file(".report.pdf.nextsh-part-1a2b3c4d")
    private val otherLeftover = file(".notes.txt.nextsh-part-00ff00ff")
    private val hidden = file(".bashrc")
    private val ordinary = file("report.pdf")

    // ── Which entries are reported and shown ──────────────────────────────────

    @Test
    fun `a leftover stays in the list while the hidden files are off, an ordinary hidden file does not`() = runBlocking<Unit> {
        stubLive()
        stubListings(listOf(ordinary, hidden, leftover))
        val vm = newViewModel()

        val loaded = vm.awaitLoaded("listing")

        assertEquals(setOf(leftover.path), loaded.leftoverPaths)
        assertEquals(listOf(leftover.path), loaded.leftovers.map { it.path })
        assertEquals(setOf(ordinary.path, leftover.path), loaded.visibleFiles.map { it.path }.toSet())
    }

    @Test
    fun `a leftover is listed once when the hidden files are shown`() = runBlocking<Unit> {
        stubLive()
        stubListings(listOf(ordinary, hidden, leftover))
        val vm = newViewModel()
        vm.awaitLoaded("listing")

        vm.toggleHidden()
        val loaded = vm.awaitLoaded("hidden shown") { it.hiddenVisible }

        assertEquals(3, loaded.visibleFiles.size)
        assertEquals(1, loaded.visibleFiles.count { it.path == leftover.path })
    }

    @Test
    fun `a folder or a link with the name of a leftover is neither reported nor visible`() = runBlocking<Unit> {
        stubLive()
        val folder = file(".a.nextsh-part-11111111", isDirectory = true)
        val link = file(".b.nextsh-part-22222222", isSymlink = true)
        stubListings(listOf(ordinary, folder, link))
        val vm = newViewModel()

        val loaded = vm.awaitLoaded("listing")

        assertTrue(loaded.leftoverPaths.isEmpty())
        assertEquals(listOf(ordinary.path), loaded.visibleFiles.map { it.path })
    }

    // ── The temporary files of the uploads under way ──────────────────────────

    @Test
    fun `the temporary file of an upload under way is not a leftover and stays hidden`() = runBlocking<Unit> {
        stubLive(leftover.name)
        stubListings(listOf(ordinary, leftover, otherLeftover))
        val vm = newViewModel()

        val loaded = vm.awaitLoaded("listing")

        assertEquals(setOf(otherLeftover.path), loaded.leftoverPaths)
        assertFalse(loaded.visibleFiles.any { it.path == leftover.path })
        vm.toggleHidden()
        assertTrue(vm.awaitLoaded("hidden shown") { it.hiddenVisible }.visibleFiles.any { it.path == leftover.path })
    }

    @Test
    fun `a temporary file is set aside by its name, whatever the path the listing shows it under`() = runBlocking<Unit> {
        // The replacement of a file reached through a link writes its temporary file next to the REAL
        // target (/srv/conf). The folder on screen is a link to it (/home/t/confdir): the listing shows
        // the same file under another path. Only the name is common.
        val name = ".real.conf.nextsh-part-5e5e5e5e"
        stubLive(name)
        stubListings(listOf(file(name, dir = "/home/t/confdir")))
        val vm = newViewModel(initialCwd = "/home/t/confdir")

        assertTrue(vm.awaitLoaded("listing").leftoverPaths.isEmpty())
    }

    @Test
    fun `a temporary file left by a lost channel or a refused rm is still reported`() = runBlocking<Unit> {
        // The manager does not know it (neither under way nor gone): it is a real leftover.
        stubLive()
        stubListings(listOf(leftover))
        val vm = newViewModel()

        assertEquals(setOf(leftover.path), vm.awaitLoaded("listing").leftoverPaths)
    }
    @Test
    fun `the name of an unrelated temporary file does not hide the leftover of the folder on screen`() = runBlocking<Unit> {
        stubLive(".other.txt.nextsh-part-9d9d9d9d")
        stubListings(listOf(leftover))
        val vm = newViewModel()

        assertEquals(setOf(leftover.path), vm.awaitLoaded("listing").leftoverPaths)
    }

    @Test
    fun `an upload that ends during the listing is not reported, the snapshot of before is used`() = runBlocking<Unit> {
        // First read (before the listing): the upload is under way. Second read (after): it just ended and
        // its temporary file was renamed away or removed after the listing saw it.
        every { sftpManager.knownUploadTempNames() } returnsMany listOf(setOf(leftover.name), emptySet())
        every { sftpManager.isUploadTempUnderWay(any()) } returns false
        stubListings(listOf(leftover))
        val vm = newViewModel()

        assertTrue(vm.awaitLoaded("listing").leftoverPaths.isEmpty())
    }

    // ── Reload during a listing, retry after a failure ────────────────────────

    @Test
    fun `an upload created and finished during the listing is not reported, the read of after is used`() = runBlocking<Unit> {
        // Nothing under way before the listing; created and finished while it ran, so known gone after.
        every { sftpManager.knownUploadTempNames() } returnsMany listOf(emptySet(), setOf(leftover.name))
        every { sftpManager.isUploadTempUnderWay(any()) } returns false
        stubListings(listOf(leftover))
        val vm = newViewModel()

        assertTrue(vm.awaitLoaded("listing").leftoverPaths.isEmpty())
    }

    @Test
    fun `a refresh asked while the folder is loading runs when the listing ends`() = runBlocking<Unit> {
        stubLive()
        val gate = CompletableDeferred<SshResult<List<SftpFile>>>()
        coEvery { sftpManager.listDirectory(SESSION, any()) } coAnswers { gate.await() } andThen
            SshResult.Success(listOf(ordinary))
        val vm = newViewModel()
        assertTrue(vm.state.value is SftpBrowserUiState.Loading)

        vm.refresh()
        gate.complete(SshResult.Success(emptyList()))

        val loaded = vm.awaitLoaded("the replayed listing") { it.rawFiles.isNotEmpty() }
        assertEquals(listOf(ordinary.path), loaded.rawFiles.map { it.path })
        coVerify(exactly = 2) { sftpManager.listDirectory(SESSION, CWD) }
    }

    @Test
    fun `several refreshes asked during a listing give one reload`() = runBlocking<Unit> {
        stubLive()
        val gate = CompletableDeferred<SshResult<List<SftpFile>>>()
        coEvery { sftpManager.listDirectory(SESSION, any()) } coAnswers { gate.await() } andThen
            SshResult.Success(listOf(ordinary))
        val vm = newViewModel()

        vm.refresh()
        vm.refresh()
        vm.refresh()
        gate.complete(SshResult.Success(emptyList()))
        vm.awaitLoaded("the replayed listing") { it.rawFiles.isNotEmpty() }

        coVerify(exactly = 2) { sftpManager.listDirectory(SESSION, CWD) }
    }

    @Test
    fun `a refresh after a failed load asks for the folder again`() = runBlocking<Unit> {
        stubLive()
        coEvery { sftpManager.listDirectory(SESSION, any()) } returnsMany listOf(
            SshResult.Error(SshErrorCode.UNKNOWN, "connection lost"),
            SshResult.Success(listOf(ordinary)),
        )
        val vm = newViewModel()
        withTimeoutOrNull(5_000L) { vm.state.first { it is SftpBrowserUiState.Error } } ?: error("no error state")

        vm.refresh()

        assertEquals(listOf(ordinary.path), vm.awaitLoaded("the retry").rawFiles.map { it.path })
    }

    // ── The banner ────────────────────────────────────────────────────────────

    @Test
    fun `a dismissed banner stays dismissed after a refresh of the same folder and after a return to it`() = runBlocking<Unit> {
        stubLive()
        stubListings(listOf(leftover), listOf(leftover), emptyList(), listOf(leftover))
        val vm = newViewModel()
        assertFalse(vm.awaitLoaded("listing").leftoverBannerDismissed)

        vm.dismissLeftoverBanner()
        assertTrue((vm.state.value as SftpBrowserUiState.Loaded).leftoverBannerDismissed)

        vm.refresh()
        val refreshed = vm.awaitLoaded("refresh") { it.rawFiles.isNotEmpty() && it.leftoverBannerDismissed }
        assertEquals(setOf(leftover.path), refreshed.leftoverPaths)

        vm.navigateTo("/other")
        vm.awaitLoaded("other folder") { it.cwd == "/other" }
        vm.navigateTo(CWD)
        assertTrue(vm.awaitLoaded("back") { it.cwd == CWD && it.rawFiles.isNotEmpty() }.leftoverBannerDismissed)
    }

    @Test
    fun `a dismissed banner stays dismissed in the view model that replaces the first one`() = runBlocking<Unit> {
        stubLive()
        stubListings(listOf(leftover), listOf(leftover))
        val first = newViewModel()
        first.awaitLoaded("first listing")
        first.dismissLeftoverBanner()
        first.dispose()

        val second = newViewModel()
        val loaded = second.awaitLoaded("second listing")

        assertTrue(loaded.leftoverBannerDismissed)
        assertEquals(setOf(leftover.path), loaded.leftoverPaths)
    }

    @Test
    fun `a banner dismissed in a pane that is closed is shown again when a pane of that id comes back`() = runBlocking<Unit> {
        stubLive()
        stubListings(listOf(leftover), listOf(leftover))
        val first = newViewModel()
        first.awaitLoaded("first listing")
        first.dismissLeftoverBanner()

        inbox.retainPanes(emptySet())
        val second = newViewModel()

        assertFalse(second.awaitLoaded("second listing").leftoverBannerDismissed)
    }

    @Test
    fun `dismissing the banner of a folder does not dismiss the banner of another`() = runBlocking<Unit> {
        stubLive()
        stubListings(listOf(leftover), listOf(file(".x.nextsh-part-33333333", dir = "/other")))
        val vm = newViewModel()
        vm.awaitLoaded("listing")
        vm.dismissLeftoverBanner()

        vm.navigateTo("/other")
        val other = vm.awaitLoaded("other folder") { it.cwd == "/other" }

        assertFalse(other.leftoverBannerDismissed)
        assertEquals(1, other.leftoverPaths.size)
    }

    // ── The view options across view models ───────────────────────────────────

    @Test
    fun `the hidden files option and the sort order are taken up again by the view model that replaces the first one`() = runBlocking<Unit> {
        stubLive()
        stubListings(listOf(ordinary, hidden), listOf(ordinary, hidden))
        val first = newViewModel()
        first.awaitLoaded("first listing")
        first.toggleHidden()
        first.setSortOrder(SortOrder.NAME_DESC)
        first.dispose()

        val second = newViewModel()
        val loaded = second.awaitLoaded("second listing")

        assertTrue(loaded.hiddenVisible)
        assertEquals(SortOrder.NAME_DESC, loaded.sortOrder)
    }

    @Test
    fun `the options of a pane are not applied to another folder`() = runBlocking<Unit> {
        stubLive()
        stubListings(listOf(ordinary), listOf(ordinary))
        val first = newViewModel()
        first.awaitLoaded("first listing")
        first.toggleHidden()
        first.dispose()

        val second = newViewModel(initialCwd = "/other")
        val loaded = second.awaitLoaded("listing of another folder") { it.cwd == "/other" }

        assertFalse(loaded.hiddenVisible)
        assertEquals(SortOrder.NAME_ASC, loaded.sortOrder)
    }

    @Test
    fun `the options of a pane are forgotten when the pane is closed`() = runBlocking<Unit> {
        stubLive()
        stubListings(listOf(ordinary), listOf(ordinary))
        val first = newViewModel()
        first.awaitLoaded("first listing")
        first.toggleHidden()
        first.setSortOrder(SortOrder.SIZE_DESC)
        first.dispose()

        inbox.retainPanes(emptySet())
        val loaded = newViewModel().awaitLoaded("second listing")

        assertFalse(loaded.hiddenVisible)
        assertEquals(SortOrder.NAME_ASC, loaded.sortOrder)
    }

    // ── A disposed view model ─────────────────────────────────────────────────

    @Test
    fun `a disposed view model does not reload`() = runBlocking<Unit> {
        stubLive()
        stubListings(listOf(ordinary), listOf(ordinary))
        val vm = newViewModel()
        vm.awaitLoaded("listing")

        vm.dispose()
        vm.refresh()

        kotlinx.coroutines.delay(300)
        coVerify(exactly = 1) { sftpManager.listDirectory(SESSION, CWD) }
    }

    @Test
    fun `a view model disposed during its listing does not write the folder of the pane`() = runBlocking<Unit> {
        stubLive()
        val gate = CompletableDeferred<SshResult<List<SftpFile>>>()
        coEvery { sftpManager.listDirectory(SESSION, any()) } coAnswers { gate.await() }
        val vm = newViewModel()

        vm.dispose()
        gate.complete(SshResult.Success(listOf(ordinary)))
        vm.awaitLoaded("listing")

        verify(exactly = 0) { sessionManager.updateSftpPaneCwd(any(), any(), any()) }
    }

    @Test
    fun `a view model disposed during its listing does not replay a pending reload`() = runBlocking<Unit> {
        stubLive()
        val gate = CompletableDeferred<SshResult<List<SftpFile>>>()
        coEvery { sftpManager.listDirectory(SESSION, any()) } coAnswers { gate.await() }
        val vm = newViewModel()
        vm.refresh()

        vm.dispose()
        gate.complete(SshResult.Success(listOf(ordinary)))
        vm.awaitLoaded("listing")

        kotlinx.coroutines.delay(300)
        coVerify(exactly = 1) { sftpManager.listDirectory(SESSION, CWD) }
    }

    // ── Deletion ──────────────────────────────────────────────────────────────

    @Test
    fun `the targets of the deletion dialog are the ones it was opened with, a later listing does not change them`() = runBlocking<Unit> {
        stubLive()
        stubListings(listOf(leftover), listOf(leftover, otherLeftover), emptyList())
        stubDeletes()
        val vm = newViewModel()
        val first = vm.awaitLoaded("listing")
        vm.openDialog(SftpDialog.DeleteLeftovers(first.leftovers))
        val dialog = (vm.state.value as SftpBrowserUiState.Loaded).dialog as SftpDialog.DeleteLeftovers

        // The folder is listed again while the dialog is open: a second leftover has appeared.
        vm.refresh()
        val second = vm.awaitLoaded("reload with a second leftover") { it.rawFiles.size == 2 }
        assertEquals(2, second.leftovers.size)
        assertEquals(listOf(leftover.path), dialog.targets.map { it.path })

        vm.deleteLeftovers(dialog.targets)
        vm.awaitLoaded("reload after deletion") { it.rawFiles.isEmpty() }

        coVerify(exactly = 1) { sftpManager.delete(SESSION, leftover.path) }
        coVerify(exactly = 0) { sftpManager.delete(SESSION, otherLeftover.path) }
    }

    @Test
    fun `deleting removes each exact name with a plain rm and never a recursive delete`() = runBlocking<Unit> {
        stubLive()
        stubListings(listOf(leftover, otherLeftover), emptyList())
        stubDeletes()
        val vm = newViewModel()
        val loaded = vm.awaitLoaded("listing")

        vm.deleteLeftovers(loaded.leftovers)
        vm.awaitLoaded("reload after deletion") { it.rawFiles.isEmpty() }

        coVerify(exactly = 1) { sftpManager.delete(SESSION, leftover.path) }
        coVerify(exactly = 1) { sftpManager.delete(SESSION, otherLeftover.path) }
        coVerify(exactly = 0) { sftpManager.deleteRecursive(any(), any()) }
        coVerify(exactly = 2) { sftpManager.listDirectory(SESSION, CWD) }
        assertNull(vm.notice.value)
    }

    @Test
    fun `a target that became a folder is skipped, nothing recursive is asked`() = runBlocking<Unit> {
        stubLive()
        stubListings(listOf(leftover), emptyList())
        stubDeletes()
        val vm = newViewModel()
        vm.awaitLoaded("listing")
        val nowAFolder = file(".swap.nextsh-part-44444444", isDirectory = true)

        vm.deleteLeftovers(listOf(nowAFolder, leftover))
        vm.awaitLoaded("reload after deletion") { it.rawFiles.isEmpty() }

        coVerify(exactly = 0) { sftpManager.delete(SESSION, nowAFolder.path) }
        coVerify(exactly = 0) { sftpManager.deleteRecursive(any(), any()) }
        coVerify(exactly = 1) { sftpManager.delete(SESSION, leftover.path) }
    }

    @Test
    fun `a refused deletion shows the name and the message in the notice and the folder is reloaded`() = runBlocking<Unit> {
        stubLive()
        stubListings(listOf(leftover), listOf(leftover))
        coEvery { sftpManager.delete(SESSION, leftover.path) } returns SshResult.Error(SshErrorCode.UNKNOWN, "Permission denied")
        val vm = newViewModel()
        vm.awaitLoaded("listing")

        vm.deleteLeftovers(listOf(leftover))
        val notice = withTimeoutOrNull(5_000L) { vm.notice.first { it != null } } ?: error("timed out waiting for the notice")

        assertEquals("${leftover.name}: Permission denied", notice)
        coVerify(exactly = 0) { sftpManager.deleteRecursive(any(), any()) }
        vm.awaitLoaded("reload after the failure")
        coVerify(timeout = 5_000L, exactly = 2) { sftpManager.listDirectory(SESSION, CWD) }
    }

    @Test
    fun `the dialog and the selection are closed as soon as the deletion starts, not when it ends`() = runBlocking<Unit> {
        stubLive()
        stubListings(listOf(leftover), emptyList())
        val gate = CompletableDeferred<SshResult<Unit>>()
        coEvery { sftpManager.delete(SESSION, leftover.path) } coAnswers { gate.await() }
        val vm = newViewModel()
        vm.awaitLoaded("listing")
        vm.selectOnly(leftover.path)
        vm.openDialog(SftpDialog.DeleteLeftovers(listOf(leftover)))
        val before = vm.state.value as SftpBrowserUiState.Loaded
        assertTrue(before.dialog != null && before.selection.isNotEmpty(), "the test does not start from an open dialog")

        vm.deleteLeftovers(listOf(leftover))

        // The deletion is held: nothing has ended, yet the dialog and the selection are already gone.
        coVerify(timeout = 5_000L, exactly = 1) { sftpManager.delete(SESSION, leftover.path) }
        val during = vm.state.value as SftpBrowserUiState.Loaded
        assertNull(during.dialog)
        assertTrue(during.selection.isEmpty())
        gate.complete(SshResult.Success(Unit))
        vm.awaitLoaded("reload after the deletion") { it.rawFiles.isEmpty() }
    }

    @Test
    fun `a leftover that became the temporary file of an upload under way is skipped by the deletion`() = runBlocking<Unit> {
        stubLive()
        every { sftpManager.isUploadTempUnderWay(leftover.name) } returns true
        stubListings(listOf(leftover, otherLeftover), emptyList())
        stubDeletes()
        val vm = newViewModel()
        val loaded = vm.awaitLoaded("listing")

        vm.deleteLeftovers(loaded.leftovers)
        vm.awaitLoaded("reload after deletion") { it.rawFiles.isEmpty() }

        coVerify(exactly = 0) { sftpManager.delete(SESSION, leftover.path) }
        coVerify(exactly = 1) { sftpManager.delete(SESSION, otherLeftover.path) }
    }

    // ── The end of an upload asks the pane for a reload ───────────────────────

    @Test
    fun `a reload requested through the inbox reloads the folder`() = runBlocking<Unit> {
        stubLive()
        stubListings(listOf(ordinary), listOf(ordinary, leftover))
        val vm = newViewModel()
        vm.awaitLoaded("listing")

        inbox.requestReload(SftpPaneKey("pane-1"))

        assertEquals(2, vm.awaitLoaded("the reload") { it.rawFiles.size == 2 }.rawFiles.size)
    }

    @Test
    fun `a reload requested before the view model existed is not replayed, the one asked after is`() = runBlocking<Unit> {
        stubLive()
        inbox.requestReload(SftpPaneKey("pane-1"))
        stubListings(listOf(ordinary), listOf(ordinary, leftover))
        val vm = newViewModel()
        vm.awaitLoaded("listing")
        kotlinx.coroutines.delay(300)
        coVerify(exactly = 1) { sftpManager.listDirectory(SESSION, CWD) }

        inbox.requestReload(SftpPaneKey("pane-1"))

        vm.awaitLoaded("the reload") { it.rawFiles.size == 2 }
    }

    @Test
    fun `the end of an upload reloads the view model that replaced the one that started it`() = runBlocking<Unit> {
        stubLive()
        stubListings(listOf(ordinary), listOf(ordinary), listOf(ordinary, leftover))
        val first = newViewModel()
        first.awaitLoaded("first listing")
        first.dispose()
        val second = newViewModel()
        second.awaitLoaded("second listing")

        // The upload started by the first one ends now: only the key of the pane is involved.
        inbox.requestReload(SftpPaneKey("pane-1"))

        second.awaitLoaded("the reload of the successor") { it.rawFiles.size == 2 }
        coVerify(exactly = 3) { sftpManager.listDirectory(SESSION, CWD) }
    }

    @Test
    fun `a disposed view model no longer reacts to the requests of the pane`() = runBlocking<Unit> {
        stubLive()
        stubListings(listOf(ordinary), listOf(ordinary, leftover))
        val vm = newViewModel()
        vm.awaitLoaded("listing")
        vm.dispose()

        inbox.requestReload(SftpPaneKey("pane-1"))

        kotlinx.coroutines.delay(300)
        coVerify(exactly = 1) { sftpManager.listDirectory(SESSION, CWD) }
    }

    // ── A file created while another listing runs ─────────────────────────────

    @Test
    fun `a file created while a listing runs is not missing from the result`() = runBlocking<Unit> {
        stubLive()
        val created = CompletableDeferred<SshResult<Unit>>()
        val other = CompletableDeferred<SshResult<List<SftpFile>>>()
        coEvery { sftpManager.createFile(SESSION, CWD, "n.txt") } coAnswers { created.await() }
        coEvery { sftpManager.listDirectory(SESSION, CWD) } returns SshResult.Success(listOf(ordinary))
        coEvery { sftpManager.listDirectory(SESSION, "/other") } coAnswers { other.await() } andThen
            SshResult.Success(listOf(file("n.txt", dir = "/other")))
        val vm = newViewModel()
        vm.awaitLoaded("listing")

        val creation = launchedInApp { vm.createFile("n.txt") }
        coVerify(timeout = 5_000L, exactly = 1) { sftpManager.createFile(SESSION, CWD, "n.txt") }
        vm.navigateTo("/other")
        // The outcome of the creation is handled while the listing of the other folder is still under way:
        // the case under test. Once that listing has ended, the view model rightly leaves the other folder
        // alone (SftpBrowserViewModelCreateFileTest, the user navigated away meanwhile), so the end of the
        // creation and the end of the listing must not be left to race on the default dispatcher.
        created.complete(SshResult.Success(Unit))
        withTimeoutOrNull(5_000L) { creation.join() } ?: error("timed out waiting for: the end of the creation")
        other.complete(SshResult.Success(emptyList()))

        vm.awaitLoaded("the replayed listing") { it.cwd == "/other" && it.rawFiles.isNotEmpty() }
        coVerify(exactly = 2) { sftpManager.listDirectory(SESSION, "/other") }
    }

    /**
     * Runs [action] and returns the one coroutine it launched in the app scope, so that a test can wait for
     * the view model to have handled an outcome that leaves nothing else to observe.
     */
    private fun launchedInApp(action: () -> Unit): Job {
        val scope = app.coroutineScope.coroutineContext.job
        val before = scope.children.toSet()
        action()
        return (scope.children.toSet() - before).single()
    }

    private companion object {
        const val SESSION = "ssh-1"
        const val CWD = "/home/t"
    }
}
