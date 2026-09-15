// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sessions

// ──────────────────────────────────────────────────────────────────────────────
// Unit tests for [DesktopSessionManager.selectNextTab],
// [DesktopSessionManager.selectPreviousTab], and the positive-modulo
// wrap-around logic backing the Desktop tab-cycling shortcuts (Ctrl+Tab /
// Ctrl+Shift+Tab).
//
// Construction strategy: [DesktopSshSessionManager] and friends are mocked
// via MockK (relaxed) with `sessions` stubbed to return an empty StateFlow so
// the init collector starts cleanly. [openSession] is synchronous for the tab
// insertion; the async [prepareConnection] coroutine is not awaited and
// irrelevant to cycling. [VaultManager.getPassword] returns `null` so the pane
// stays in [TerminalTabStatus.AwaitingPassword], a valid non-empty tab state.
//
// Cycling logic never touches SSH/vault, so this stays a pure in-memory unit
// test with UnconfinedTestDispatcher.
// ──────────────────────────────────────────────────────────────────────────────

import fr.techtical.nextsh.desktop.core.ssh.DesktopKnownHostsVerifier
import fr.techtical.nextsh.desktop.core.ssh.DesktopSftpManager
import fr.techtical.nextsh.desktop.core.ssh.DesktopSshSessionManager
import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.SshSession
import fr.techtical.nextsh.shared.domain.repository.HostRepository
import fr.techtical.nextsh.shared.domain.vault.VaultManager
import fr.techtical.nextsh.shared.util.AppScope
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopSessionManagerTabCycleTest {

    // ── Test AppScope ─────────────────────────────────────────────────────────

    private class TestAppScope(scope: CoroutineScope) : AppScope {
        override val coroutineScope: CoroutineScope = scope
        override fun onDestroy() = Unit
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeHost(id: String, label: String = id) = Host(
        id           = id,
        label        = label,
        hostname     = "example.com",
        port         = 22,
        username     = "root",
        authType     = AuthType.PASSWORD,
        credentialId = "cred-$id",
    )

    /**
     * Build a [DesktopSessionManager] wired to a test scope. SSH and vault
     * collaborators are relaxed mocks:
     * - `sshSessionManager.sessions` → empty StateFlow (init collector needs this)
     * - `vaultManager.getPassword`   → null (keeps tab in AwaitingPassword, not Connected)
     */
    private fun buildManager(scope: CoroutineScope): DesktopSessionManager {
        val sshSessionManager = mockk<DesktopSshSessionManager>(relaxed = true)
        every { sshSessionManager.sessions } returns MutableStateFlow(emptyList<SshSession>())

        val vaultManager = mockk<VaultManager>(relaxed = true)
        coEvery { vaultManager.getPassword(any()) } returns null

        val hostRepository = mockk<HostRepository>(relaxed = true)
        val sftpManager    = mockk<DesktopSftpManager>(relaxed = true)
        val knownHosts     = mockk<DesktopKnownHostsVerifier>(relaxed = true)

        return DesktopSessionManager(
            hostRepository    = hostRepository,
            vaultManager      = vaultManager,
            sshSessionManager = sshSessionManager,
            sftpManager       = sftpManager,
            knownHostsVerifier = knownHosts,
            appScope          = TestAppScope(scope),
        )
    }

    // ── Empty list ────────────────────────────────────────────────────────────

    @Test
    fun `selectNextTab on empty list is a no-op`() = runTest(UnconfinedTestDispatcher()) {
        val mgr = buildManager(backgroundScope)
        mgr.selectNextTab()
        assertNull(mgr.activeTabId.value, "activeTabId must remain null on empty list")
    }

    @Test
    fun `selectPreviousTab on empty list is a no-op`() = runTest(UnconfinedTestDispatcher()) {
        val mgr = buildManager(backgroundScope)
        mgr.selectPreviousTab()
        assertNull(mgr.activeTabId.value, "activeTabId must remain null on empty list")
    }

    // ── Single tab ────────────────────────────────────────────────────────────

    @Test
    fun `selectNextTab on single tab stays on that tab`() = runTest(UnconfinedTestDispatcher()) {
        val mgr = buildManager(backgroundScope)
        val tabId = mgr.openSession(makeHost("A"))
        advanceUntilIdle()
        mgr.selectNextTab()
        assertEquals(tabId, mgr.activeTabId.value, "single-tab next must self-select")
    }

    @Test
    fun `selectPreviousTab on single tab stays on that tab`() = runTest(UnconfinedTestDispatcher()) {
        val mgr = buildManager(backgroundScope)
        val tabId = mgr.openSession(makeHost("A"))
        advanceUntilIdle()
        mgr.selectPreviousTab()
        assertEquals(tabId, mgr.activeTabId.value, "single-tab previous must self-select")
    }

    // ── Three-tab cycling ─────────────────────────────────────────────────────

    /**
     * Seed 3 tabs (A→B→C, active = C because openSession sets active to the
     * last one). Assert forward cycling wraps A→B→C→A.
     */
    @Test
    fun `selectNextTab cycles forward and wraps around`() = runTest(UnconfinedTestDispatcher()) {
        val mgr  = buildManager(backgroundScope)
        val tabA = mgr.openSession(makeHost("A"))
        val tabB = mgr.openSession(makeHost("B"))
        val tabC = mgr.openSession(makeHost("C"))
        advanceUntilIdle()

        // Active is C (last opened).
        assertEquals(tabC, mgr.activeTabId.value)

        // C → A  (wrap-around from last to first)
        mgr.selectNextTab()
        assertEquals(tabA, mgr.activeTabId.value, "C→next should land on A (wrap)")

        // A → B
        mgr.selectNextTab()
        assertEquals(tabB, mgr.activeTabId.value, "A→next should land on B")

        // B → C
        mgr.selectNextTab()
        assertEquals(tabC, mgr.activeTabId.value, "B→next should land on C")
    }

    /**
     * Assert backward cycling wraps C→B→A→C.
     */
    @Test
    fun `selectPreviousTab cycles backward and wraps around`() = runTest(UnconfinedTestDispatcher()) {
        val mgr  = buildManager(backgroundScope)
        val tabA = mgr.openSession(makeHost("A"))
        val tabB = mgr.openSession(makeHost("B"))
        val tabC = mgr.openSession(makeHost("C"))
        advanceUntilIdle()

        // Active is C.
        assertEquals(tabC, mgr.activeTabId.value)

        // C → B
        mgr.selectPreviousTab()
        assertEquals(tabB, mgr.activeTabId.value, "C→prev should land on B")

        // B → A
        mgr.selectPreviousTab()
        assertEquals(tabA, mgr.activeTabId.value, "B→prev should land on A")

        // A → C  (wrap-around from first to last)
        mgr.selectPreviousTab()
        assertEquals(tabC, mgr.activeTabId.value, "A→prev should land on C (wrap)")
    }
}
