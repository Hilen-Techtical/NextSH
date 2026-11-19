// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sessions

import android.content.ClipboardManager
import android.content.Context
import fr.techtical.nextsh.core.ssh.Fido2ChallengeRequest
import fr.techtical.nextsh.core.ssh.Fido2ChallengeResponse
import fr.techtical.nextsh.core.ssh.Fido2PromptCoordinator
import fr.techtical.nextsh.core.AppVisibility
import fr.techtical.nextsh.core.ssh.SessionHandoff
import fr.techtical.nextsh.core.ssh.SshSessionManager
import fr.techtical.nextsh.data.preferences.SettingsDataStore
import fr.techtical.nextsh.domain.repository.CustomTerminalThemeRepository
import fr.techtical.nextsh.domain.repository.HostRepository
import fr.techtical.nextsh.domain.repository.SnippetRepository
import fr.techtical.nextsh.domain.usecase.ConnectSessionUseCase
import fr.techtical.nextsh.service.TerminalServiceController
import fr.techtical.nextsh.shared.core.ssh.fido2.SkKeyType
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The FIDO2 callback has one owner, [Fido2PromptCoordinator], installed by `App.onCreate`.
 * `SessionViewModel` used to install its own in `init` and to point it at its own state: a
 * connection started from the host list (no ViewModel yet) failed with "FIDO2 callback not
 * configured", and a callback left by a destroyed ViewModel waited 60 s for a sheet nobody showed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionViewModelFido2Test {

    private val mainDispatcher = UnconfinedTestDispatcher()

    private lateinit var sshSessionManager: SshSessionManager
    private var storedCallback: (suspend (Fido2ChallengeRequest) -> Fido2ChallengeResponse?)? = null

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)

        // A real property behaviour on the mock: the last callback set is the one read back.
        sshSessionManager = mockk(relaxed = true)
        every { sshSessionManager.fido2ChallengeCallback = any() } answers { storedCallback = firstArg() }
        every { sshSessionManager.fido2ChallengeCallback } answers { storedCallback }
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel(): SessionViewModel {
        val context = mockk<Context>()
        every { context.getSystemService(Context.CLIPBOARD_SERVICE) } returns mockk<ClipboardManager>()
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
            terminalService = mockk<TerminalServiceController>(),
            appVisibility = AppVisibility { true },
        )
    }

    private fun SessionViewModel.clear() {
        // onCleared is protected; the ViewModel machinery that calls it is not reachable from a unit test.
        SessionViewModel::class.java.getDeclaredMethod("onCleared").apply { isAccessible = true }.invoke(this)
    }

    @Test
    fun `creating a SessionViewModel installs no interactive SSH callback`() {
        newViewModel()

        verify(exactly = 0) { sshSessionManager.fido2ChallengeCallback = any() }
        verify(exactly = 0) { sshSessionManager.hostKeyVerificationCallback = any() }
    }

    @Test
    fun `the callback installed at app start survives the creation of SessionViewModels`() {
        Fido2PromptCoordinator().install(sshSessionManager)
        val installedAtStart = storedCallback

        newViewModel()
        newViewModel()

        assertSame(installedAtStart, storedCallback)
    }

    @Test
    fun `clearing a SessionViewModel leaves the callback and the prompt on screen alone`() = runTest {
        val coordinator = Fido2PromptCoordinator()
        coordinator.install(sshSessionManager)
        val installedAtStart = storedCallback
        val viewModel = newViewModel()
        val request = Fido2ChallengeRequest(
            signingData = ByteArray(16) { 4 },
            rpId = "ssh:",
            keyType = SkKeyType.SK_ED25519,
        )
        // A connection started elsewhere (host list, tunnel) is waiting for the key.
        val waiting = launch(mainDispatcher) { coordinator.prompt(request) }

        viewModel.clear()

        assertSame(installedAtStart, storedCallback)
        assertSame(request, coordinator.state.value?.request, "the prompt belongs to the coordinator, not to the ViewModel")
        coordinator.cancel(request)
        waiting.join()
    }
}
