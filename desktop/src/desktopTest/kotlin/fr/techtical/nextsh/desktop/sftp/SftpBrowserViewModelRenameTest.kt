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
 * `rename` must hand the path of the entry and the BARE new name to the manager as two separate arguments:
 * the manager refuses a name that is not a bare name, and joins a valid one to the folder of the entry, so
 * the view model must never build the new path itself. What the manager answers is shown as it is.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SftpBrowserViewModelRenameTest {

    private class TestAppScope(scope: CoroutineScope) : AppScope {
        override val coroutineScope: CoroutineScope = scope
        override fun onDestroy() = Unit
    }

    private val sshSessionId = "ssh-1"

    private fun stubbedManager(): DesktopSftpManager {
        val manager = mockk<DesktopSftpManager>()
        coEvery { manager.listDirectory(any(), any()) } returns SshResult.Success(emptyList())
        every { manager.sanitizePath(any()) } answers { firstArg() }
        every { manager.knownUploadTempNames() } returns emptySet()
        return manager
    }

    private fun buildViewModel(
        scope: CoroutineScope,
        manager: DesktopSftpManager,
        cwd: String,
    ) = SftpBrowserViewModel(
        sessionId = sshSessionId,
        tabId = "tab-1",
        paneId = "pane-1",
        initialCwd = cwd,
        sftpManager = manager,
        sessionManager = mockk(relaxed = true),
        transferManager = mockk(relaxed = true),
        appScope = TestAppScope(scope),
        inbox = SftpTransferInbox(),
    )

    @Test
    fun `rename passes the path of the entry and the bare new name separately`() =
        runTest(UnconfinedTestDispatcher()) {
            val manager = stubbedManager()
            coEvery { manager.rename(any(), any(), any()) } returns SshResult.Success(Unit)
            val viewModel = buildViewModel(backgroundScope, manager, cwd = "/home/u")

            viewModel.rename("/home/u/old.txt", "..")
            viewModel.rename("/home/u/old.txt", "x+y")
            viewModel.rename("/home/u/old.txt", "100%")

            coVerify(exactly = 1) { manager.rename(sshSessionId, "/home/u/old.txt", "..") }
            coVerify(exactly = 1) { manager.rename(sshSessionId, "/home/u/old.txt", "x+y") }
            coVerify(exactly = 1) { manager.rename(sshSessionId, "/home/u/old.txt", "100%") }
        }

    @Test
    fun `rename surfaces a refusal from the manager as an error message`() =
        runTest(UnconfinedTestDispatcher()) {
            val manager = stubbedManager()
            coEvery { manager.rename(any(), any(), any()) } returns
                SshResult.Error(SshErrorCode.UNKNOWN, "refused by the manager")
            val viewModel = buildViewModel(backgroundScope, manager, cwd = "/home/u")

            viewModel.rename("/home/u/old.txt", "a/b")

            assertEquals("refused by the manager", viewModel.notice.value)
        }
}
