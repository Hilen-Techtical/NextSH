// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sftp

import fr.techtical.nextsh.desktop.core.ssh.DesktopSftpManager
import fr.techtical.nextsh.desktop.core.ssh.DesktopTransferManager
import fr.techtical.nextsh.desktop.sessions.DesktopSessionManager
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.shared.util.AppScope
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * The folder a browser lists is reported to the session manager under the id of its pane
 * (`TabContent.Sftp.paneId`), not under a position in the tree.
 */
class SftpBrowserViewModelCwdTest {

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

    private fun newViewModel(initialCwd: String): SftpBrowserViewModel {
        every { sftpManager.sanitizePath(any()) } answers { firstArg<String>() }
        every { sftpManager.knownUploadTempNames() } returns emptySet()
        coEvery { sftpManager.listDirectory(SESSION, any()) } returns SshResult.Success(emptyList())
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

    @Test
    fun `the listing reports its folder under the pane id`() {
        val vm = newViewModel(CWD)

        verify(timeout = 5_000L) { sessionManager.updateSftpPaneCwd("tab-1", "pane-1", CWD) }

        vm.navigateTo(OTHER)

        verify(timeout = 5_000L) { sessionManager.updateSftpPaneCwd("tab-1", "pane-1", OTHER) }
    }

    private companion object {
        const val SESSION = "ssh-1"
        const val CWD = "/home/t"
        const val OTHER = "/var/log"
    }
}
