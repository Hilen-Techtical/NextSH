// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sftp

import fr.techtical.nextsh.desktop.core.ssh.DesktopSftpManager
import fr.techtical.nextsh.desktop.core.ssh.DesktopTransferManager
import fr.techtical.nextsh.desktop.sessions.DesktopSessionManager
import fr.techtical.nextsh.shared.domain.model.SftpFile
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.shared.util.AppScope
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** A file over the preview limit gets its typed state at once, so a quick click on another file cannot be overwritten. */
class SftpBrowserViewModelPreviewTest {

    private class TestAppScope(override val coroutineScope: CoroutineScope) : AppScope {
        override fun onDestroy() = coroutineScope.cancel()
    }

    private val app = TestAppScope(CoroutineScope(SupervisorJob() + Dispatchers.Default))
    private val sftpManager = mockk<DesktopSftpManager>()

    @AfterTest
    fun tearDown() {
        app.onDestroy()
    }

    private fun file(name: String, size: Long) =
        SftpFile(name = name, path = "/home/t/$name", size = size, permissions = 420, isDirectory = false)

    private fun newViewModel(): SftpBrowserViewModel {
        every { sftpManager.sanitizePath(any()) } answers { firstArg<String>() }
        every { sftpManager.knownUploadTempNames() } returns emptySet()
        coEvery { sftpManager.listDirectory("ssh-1", any()) } returns SshResult.Success(emptyList())
        return SftpBrowserViewModel(
            sessionId = "ssh-1",
            tabId = "tab-1",
            paneId = "pane-1",
            initialCwd = "/home/t",
            sftpManager = sftpManager,
            sessionManager = mockk<DesktopSessionManager>(relaxed = true),
            transferManager = mockk<DesktopTransferManager>(relaxed = true),
            appScope = app,
            inbox = SftpTransferInbox(),
        )
    }

    @Test
    fun `a text file over the limit is marked too large synchronously with the cap in KB`() = runBlocking {
        val vm = newViewModel()
        withTimeout(5_000L) { vm.state.first { it is SftpBrowserUiState.Loaded } }
        val big = file("big.txt", SftpBrowserViewModel.TEXT_PREVIEW_LIMIT + 1)

        vm.openPreview(big)

        val loaded = assertIs<SftpBrowserUiState.Loaded>(vm.state.value)
        val preview = assertIs<PreviewState.TooLarge>(loaded.preview)
        assertEquals(big, preview.file)
        assertEquals((SftpBrowserViewModel.TEXT_PREVIEW_LIMIT / 1024).toInt(), preview.maxKb)
    }
}
