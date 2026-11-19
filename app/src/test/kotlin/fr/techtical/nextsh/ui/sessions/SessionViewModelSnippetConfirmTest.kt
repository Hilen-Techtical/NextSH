// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sessions

import android.content.ClipboardManager
import android.content.Context
import fr.techtical.nextsh.core.AppVisibility
import fr.techtical.nextsh.core.ssh.SessionHandoff
import fr.techtical.nextsh.core.ssh.SshSessionManager
import fr.techtical.nextsh.data.preferences.SettingsDataStore
import fr.techtical.nextsh.domain.model.Snippet
import fr.techtical.nextsh.domain.repository.CustomTerminalThemeRepository
import fr.techtical.nextsh.domain.repository.HostRepository
import fr.techtical.nextsh.domain.repository.SnippetRepository
import fr.techtical.nextsh.domain.usecase.ConnectSessionUseCase
import fr.techtical.nextsh.service.TerminalServiceController
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The confirmation of a snippet lives in the ViewModel, not in the screen: it survives a rotation
 * (the ViewModel does) but not the death of the process (the tabs die with it), and a [Snippet]
 * never enters a Bundle.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionViewModelSnippetConfirmTest {

    private val mainDispatcher = UnconfinedTestDispatcher()
    private val snippet = Snippet(label = "Disk", command = "df -h")

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel(): SessionViewModel {
        val context = mockk<Context>()
        every { context.getSystemService(Context.CLIPBOARD_SERVICE) } returns mockk<ClipboardManager>()
        val sshSessionManager = mockk<SshSessionManager>(relaxed = true)
        val hostRepository = mockk<HostRepository>()
        every { hostRepository.observeAll() } returns flowOf(emptyList())
        val customThemes = mockk<CustomTerminalThemeRepository>()
        every { customThemes.observeAll() } returns flowOf(emptyList())
        val settings = mockk<SettingsDataStore>()
        every { settings.settingsFlow } returns flowOf(SettingsDataStore.Settings())
        every { settings.terminalFontSizeFlow } returns flowOf(24)

        return SessionViewModel(
            context = context,
            sshSessionManager = sshSessionManager,
            hostRepository = hostRepository,
            snippetRepository = mockk<SnippetRepository>(),
            customThemeRepository = customThemes,
            connectSession = mockk<ConnectSessionUseCase>(),
            settingsDataStore = settings,
            sessionHandoff = mockk<SessionHandoff>(),
            tabStore = TerminalTabStore(context, sshSessionManager, settings, hostRepository),
            terminalService = mockk<TerminalServiceController>(relaxed = true),
            appVisibility = AppVisibility { true },
        )
    }

    @Test
    fun `no confirmation is pending at the start`() {
        assertNull(newViewModel().pendingSnippet.value)
    }

    @Test
    fun `a request makes the snippet pending`() {
        val viewModel = newViewModel()

        viewModel.requestSnippet(snippet)

        assertSame(snippet, viewModel.pendingSnippet.value)
    }

    @Test
    fun `cancelling empties the request`() {
        val viewModel = newViewModel()
        viewModel.requestSnippet(snippet)

        viewModel.cancelSnippet()

        assertNull(viewModel.pendingSnippet.value)
    }

    @Test
    fun `confirming empties the request even without a session to write to`() {
        val viewModel = newViewModel()
        viewModel.requestSnippet(snippet)

        viewModel.confirmSnippet()

        assertNull(viewModel.pendingSnippet.value)
    }

    @Test
    fun `confirming with nothing pending does nothing`() {
        val viewModel = newViewModel()

        viewModel.confirmSnippet()

        assertNull(viewModel.pendingSnippet.value)
    }

    @Test
    fun `closing a tab empties the request`() {
        val viewModel = newViewModel()
        viewModel.requestSnippet(snippet)

        viewModel.closeTab(0)

        assertNull(viewModel.pendingSnippet.value)
    }
}
