// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sftp

import fr.techtical.nextsh.desktop.core.ssh.DesktopSftpManager
import fr.techtical.nextsh.shared.domain.model.SshErrorCode
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.shared.util.AppScope
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `createFolder` must hand the parent directory and the BARE name to the
 * manager as two separate arguments: the manager validates the name before
 * joining, so the view model must never build `cwd/name` itself (a name such
 * as `../x` would then escape the displayed folder before any check ran).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SftpBrowserViewModelCreateFolderTest {

    private class TestAppScope(scope: CoroutineScope) : AppScope {
        override val coroutineScope: CoroutineScope = scope
        override fun onDestroy() = Unit
    }

    private val sshSessionId = "ssh-1"

    private fun stubbedManager(): DesktopSftpManager {
        val manager = mockk<DesktopSftpManager>()
        coEvery { manager.listDirectory(any(), any()) } returns SshResult.Success(emptyList())
        every { manager.sanitizePath(any()) } answers { firstArg() }
        return manager
    }

    private fun buildViewModel(
        scope: CoroutineScope,
        manager: DesktopSftpManager,
        cwd: String,
    ) = SftpBrowserViewModel(
        sessionId = sshSessionId,
        tabId = "tab-1",
        initialCwd = cwd,
        paneSlot = null,
        sftpManager = manager,
        sessionManager = mockk(relaxed = true),
        transferManager = mockk(relaxed = true),
        appScope = TestAppScope(scope),
    )

    @Test
    fun `createFolder passes the parent directory and the bare name separately`() =
        runTest(UnconfinedTestDispatcher()) {
            val manager = stubbedManager()
            coEvery { manager.createDirectory(any(), any(), any()) } returns SshResult.Success(Unit)
            val viewModel = buildViewModel(backgroundScope, manager, cwd = "/home/u")

            viewModel.createFolder("../x")
            viewModel.createFolder("x+y")
            viewModel.createFolder("100%")

            coVerify(exactly = 1) { manager.createDirectory(sshSessionId, "/home/u", "../x") }
            coVerify(exactly = 1) { manager.createDirectory(sshSessionId, "/home/u", "x+y") }
            coVerify(exactly = 1) { manager.createDirectory(sshSessionId, "/home/u", "100%") }
        }

    @Test
    fun `createFolder surfaces a refusal from the manager as an error message`() =
        runTest(UnconfinedTestDispatcher()) {
            val manager = stubbedManager()
            coEvery { manager.createDirectory(any(), any(), any()) } returns
                SshResult.Error(SshErrorCode.UNKNOWN, "refused by the manager")
            val viewModel = buildViewModel(backgroundScope, manager, cwd = "/home/u")

            viewModel.createFolder("a/b")

            val loaded = viewModel.state.value as? SftpBrowserUiState.Loaded
                ?: error("the browser must stay on the listing after a refusal")
            assertEquals("refused by the manager", loaded.lastError)
        }
}
