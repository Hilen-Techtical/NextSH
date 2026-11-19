// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sessions

import android.content.Context
import fr.techtical.nextsh.core.ssh.SshSessionManager
import fr.techtical.nextsh.core.ssh.SshTerminalSession
import fr.techtical.nextsh.core.vault.VaultWipeGate
import fr.techtical.nextsh.core.vault.VaultWipeState
import fr.techtical.nextsh.data.preferences.SettingsDataStore
import fr.techtical.nextsh.domain.model.AuthType
import fr.techtical.nextsh.domain.model.Host
import fr.techtical.nextsh.domain.model.SessionStatus
import fr.techtical.nextsh.domain.repository.HostRepository
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The terminal tabs during the "Wipe the vault" action: "close all" is the closing step, and a connection
 * that ends just after it must not leave a tab that outlives the erase.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TerminalTabStoreWipeTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val gate = VaultWipeGate()
    private val sessions = mockk<SshSessionManager>().also { every { it.release(any()) } just Runs }
    private lateinit var store: TerminalTabStore

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val settings = mockk<SettingsDataStore>()
        every { settings.settingsFlow } returns flowOf(SettingsDataStore.Settings())
        val hosts = mockk<HostRepository>()
        every { hosts.observeAll() } returns flowOf(emptyList())
        store = TerminalTabStore(mockk<Context>(), sessions, settings, hosts, gate).also {
            it.hostScope = CoroutineScope(SupervisorJob() + dispatcher)
            it.cleanupScope = CoroutineScope(SupervisorJob() + dispatcher)
            it.clipboardScope = CoroutineScope(SupervisorJob() + dispatcher)
        }
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun tab(sessionId: String): Pair<SessionTab, SshTerminalSession> {
        val terminal = mockk<SshTerminalSession>(relaxed = true)
        val host = Host(
            id = "h-$sessionId",
            label = "Host",
            hostname = "host.example.com",
            username = "user",
            authType = AuthType.PASSWORD,
            credentialId = "cred",
        )
        return SessionTab(sessionId, host, terminal, SessionStatus.CONNECTED) to terminal
    }

    @Test
    fun `closeAll closes the shell of every tab and gives back its reference, once`() {
        val (a, terminalA) = tab("s1")
        val (b, terminalB) = tab("s2")
        store.addTab(a)
        store.addTab(b)

        store.closeAll()

        assertTrue(store.state.value.tabs.isEmpty())
        verify(exactly = 1) { terminalA.disconnect() }
        verify(exactly = 1) { terminalB.disconnect() }
        verify(exactly = 1) { sessions.release("s1") }
        verify(exactly = 1) { sessions.release("s2") }
    }

    @Test
    fun `a tab added while the app is closed is closed as if it had been added and closed`() {
        val (late, terminal) = tab("late")

        for (state in listOf(VaultWipeState.RUNNING, VaultWipeState.FAILED, VaultWipeState.DONE)) {
            gate.set(state)
            store.addTab(late)

            assertTrue(store.state.value.tabs.isEmpty(), "no tab left behind in $state")
        }
        verify(atLeast = 1) { terminal.disconnect() }
        verify(atLeast = 1) { sessions.release("late") }
    }

    @Test
    fun `a tab added once the app is open is kept`() {
        gate.set(VaultWipeState.DONE)
        gate.acknowledgeDone()
        val (a, _) = tab("s1")

        store.addTab(a)

        assertEquals(listOf("s1"), store.state.value.tabs.map { it.sessionId })
    }
}
