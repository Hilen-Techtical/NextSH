// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sftp

import fr.techtical.nextsh.desktop.core.ssh.DesktopSftpManager
import fr.techtical.nextsh.desktop.core.ssh.DesktopTransferManager
import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.sftp_error_generic
import fr.techtical.nextsh.desktop.sessions.DesktopSessionManager
import fr.techtical.nextsh.shared.domain.model.SftpFile
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.getString
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The errors of the operations (create, rename, permissions, delete) are shown through the
 * notice of the pane in the [SftpTransferInbox]: they must survive the reload that follows an
 * operation, a navigation, a folder that fails to load and the re-creation of the view model,
 * which the state of the list ([SftpBrowserUiState]) does not.
 *
 * Real time on purpose, as in [SftpBrowserViewModelCreateFileTest]. One inbox is shared by the
 * view models of a test, as the container's one is by the screens.
 */
class SftpBrowserViewModelErrorTest {

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

    private fun file(name: String, dir: String = CWD) =
        SftpFile(name = name, path = "$dir/$name", size = 0L, permissions = 0b110_100_100, isDirectory = false)

    private fun newViewModel(paneId: String = PANE): SftpBrowserViewModel {
        every { sftpManager.sanitizePath(any()) } answers { firstArg<String>() }
        every { sftpManager.knownUploadTempNames() } returns emptySet()
        return SftpBrowserViewModel(
            sessionId = SESSION,
            tabId = "tab-1",
            paneId = paneId,
            initialCwd = CWD,
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

    private suspend fun SftpBrowserViewModel.awaitState(
        what: String,
        condition: (SftpBrowserUiState) -> Boolean,
    ): SftpBrowserUiState =
        withTimeoutOrNull(5_000L) { state.first(condition) } ?: error("timed out waiting for: $what")

    private suspend fun SftpBrowserViewModel.awaitNotice(what: String): String =
        withTimeoutOrNull(5_000L) { notice.first { it != null } } ?: error("timed out waiting for: $what")

    @Test
    fun `a failed delete is still shown after the reload that follows it`() = runBlocking<Unit> {
        val target = file("a.txt")
        coEvery { sftpManager.listDirectory(SESSION, any()) } returns SshResult.Success(listOf(target))
        coEvery { sftpManager.delete(SESSION, target.path) } returns SshResult.Error(SshErrorCode.UNKNOWN, "refused")
        val vm = newViewModel()
        vm.awaitLoaded("initial listing")

        vm.deleteTargets(listOf(target))

        coVerify(exactly = 2, timeout = 5_000L) { sftpManager.listDirectory(SESSION, CWD) }
        vm.awaitLoaded("listing after the delete")
        assertEquals("a.txt: refused", vm.notice.value)
    }

    @Test
    fun `an error that comes back while the folder is loading is shown`() = runBlocking<Unit> {
        val reload = CompletableDeferred<SshResult<List<SftpFile>>>()
        coEvery { sftpManager.listDirectory(SESSION, CWD) } returns SshResult.Success(emptyList())
        coEvery { sftpManager.listDirectory(SESSION, OTHER) } coAnswers { reload.await() }
        val operation = CompletableDeferred<SshResult<Unit>>()
        coEvery { sftpManager.createDirectory(SESSION, CWD, "d") } coAnswers { operation.await() }
        val vm = newViewModel()
        vm.awaitLoaded("initial listing")

        vm.createFolder("d")
        vm.navigateTo(OTHER)
        vm.awaitState("loading the other folder") { it is SftpBrowserUiState.Loading }
        operation.complete(SshResult.Error(SshErrorCode.UNKNOWN, "no space left"))

        assertEquals("no space left", vm.awaitNotice("error while loading"))
        reload.complete(SshResult.Success(emptyList()))
        vm.awaitLoaded("other folder") { it.cwd == OTHER }
        assertEquals("no space left", vm.notice.value)
    }

    @Test
    fun `an error that comes back while the folder failed to load is shown`() = runBlocking<Unit> {
        coEvery { sftpManager.listDirectory(SESSION, CWD) } returns SshResult.Success(emptyList())
        coEvery { sftpManager.listDirectory(SESSION, OTHER) } returns SshResult.Error(SshErrorCode.UNKNOWN, "unreadable")
        val operation = CompletableDeferred<SshResult<Unit>>()
        coEvery { sftpManager.rename(SESSION, "$CWD/a", "b") } coAnswers { operation.await() }
        val vm = newViewModel()
        vm.awaitLoaded("initial listing")

        vm.rename("$CWD/a", "b")
        vm.navigateTo(OTHER)
        vm.awaitState("folder failed to load") { it is SftpBrowserUiState.Error }
        operation.complete(SshResult.Error(SshErrorCode.UNKNOWN, "name taken"))

        assertEquals("name taken", vm.awaitNotice("error after a failed load"))
    }

    @Test
    fun `an error is still there when the screen and its view model are created again`() = runBlocking<Unit> {
        coEvery { sftpManager.listDirectory(SESSION, any()) } returns SshResult.Success(emptyList())
        val operation = CompletableDeferred<SshResult<Unit>>()
        coEvery { sftpManager.chmod(SESSION, "$CWD/a", 0b111_000_000) } coAnswers { operation.await() }
        val first = newViewModel()
        first.awaitLoaded("initial listing")
        first.chmod("$CWD/a", 0b111_000_000)

        val second = newViewModel()
        second.awaitLoaded("listing of the second view model")
        operation.complete(SshResult.Error(SshErrorCode.UNKNOWN, "not permitted"))

        assertEquals("not permitted", second.awaitNotice("error on the new view model"))
    }

    @Test
    fun `an error of one pane is not shown in another pane`() = runBlocking<Unit> {
        coEvery { sftpManager.listDirectory(SESSION, any()) } returns SshResult.Success(emptyList())
        coEvery { sftpManager.createDirectory(any(), any(), any()) } returns SshResult.Error(SshErrorCode.UNKNOWN, "refused")
        val paneX = newViewModel(paneId = PANE)
        val paneY = newViewModel(paneId = "pane-2")
        paneX.awaitLoaded("listing of pane X")
        paneY.awaitLoaded("listing of pane Y")

        paneX.createFolder("d")

        assertEquals("refused", paneX.awaitNotice("error of pane X"))
        assertNull(paneY.notice.value)
    }

    @Test
    fun `a blank error falls back to the generic SFTP message`() = runBlocking<Unit> {
        coEvery { sftpManager.listDirectory(SESSION, any()) } returns SshResult.Success(emptyList())
        coEvery { sftpManager.createDirectory(any(), any(), any()) } returns SshResult.Error(SshErrorCode.UNKNOWN, "")
        val vm = newViewModel()
        vm.awaitLoaded("initial listing")

        vm.createFolder("d")

        assertEquals(getString(Res.string.sftp_error_generic), vm.awaitNotice("generic error"))
    }

    @Test
    fun `an error survives a navigation and goes away when dismissed`() = runBlocking<Unit> {
        coEvery { sftpManager.listDirectory(SESSION, any()) } returns SshResult.Success(emptyList())
        coEvery { sftpManager.createDirectory(any(), any(), any()) } returns SshResult.Error(SshErrorCode.UNKNOWN, "refused")
        val vm = newViewModel()
        vm.awaitLoaded("initial listing")
        vm.createFolder("d")
        vm.awaitNotice("error shown")

        vm.navigateTo(OTHER)
        vm.awaitLoaded("other folder") { it.cwd == OTHER }
        assertEquals("refused", vm.notice.value)

        vm.dismissNotice()
        assertTrue(vm.notice.value == null)
    }

    private companion object {
        const val SESSION = "ssh-1"
        const val PANE = "pane-1"
        const val CWD = "/home/t"
        const val OTHER = "/var/log"
    }
}
