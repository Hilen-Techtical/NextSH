// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sessions

// ──────────────────────────────────────────────────────────────────────────────
// The current folder of an SFTP pane is kept under the pane's own id
// (TabContent.Sftp.paneId), never under its position in the tree: a position
// changes with the tree, and two panes of two different splits can share the
// same last slot.
//
// [withSftpPaneState] is exercised with hand-built trees (style of
// [SftpPaneIdentityTest]); the manager-level tests use real trees built by
// splitPaneAt / splitPaneAsSftpAt (style of [DesktopSessionManagerPaneCycleTest],
// whose private builder is duplicated here).
// ──────────────────────────────────────────────────────────────────────────────

import fr.techtical.nextsh.desktop.core.ssh.DesktopKnownHostsVerifier
import fr.techtical.nextsh.desktop.core.ssh.DesktopSftpManager
import fr.techtical.nextsh.desktop.core.ssh.DesktopSshSessionManager
import fr.techtical.nextsh.desktop.core.ssh.DesktopSshTerminalSession
import fr.techtical.nextsh.desktop.theme.ResolvedTheme
import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.SessionStatus
import fr.techtical.nextsh.shared.domain.model.SshErrorCode
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.shared.domain.model.SshSession
import fr.techtical.nextsh.shared.domain.repository.HostRepository
import fr.techtical.nextsh.shared.domain.vault.VaultManager
import fr.techtical.nextsh.shared.util.AppScope
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SftpPaneCwdTest {

    private class TestAppScope(scope: CoroutineScope) : AppScope {
        override val coroutineScope: CoroutineScope = scope
        override fun onDestroy() = Unit
    }

    private val theme = ResolvedTheme(name = "test", palette = mockk(relaxed = true))

    private fun terminal(sshSessionId: String = "ssh-1") = TabContent.Terminal(
        theme = theme,
        status = TerminalTabStatus.Connected(
            sshSessionId = sshSessionId,
            terminal = mockk<DesktopSshTerminalSession>(relaxed = true),
        ),
        host = makeHost("A"),
    )

    private fun sftp(paneId: String, cwd: String = "/home") = TabContent.Sftp(
        linkedSshSessionId = "ssh-1",
        state = SftpTabState.Open(cwd),
        host = makeHost("A"),
        paneId = paneId,
    )

    private fun split(first: TabContent, second: TabContent) = TabContent.Split(
        first = first,
        second = second,
        orientation = SplitOrientation.HORIZONTAL,
        focusedSlot = PaneSlot.RIGHT_OR_BOTTOM,
    )

    private fun makeHost(id: String) = Host(
        id = id,
        label = id,
        hostname = "example.com",
        port = 22,
        username = "root",
        authType = AuthType.PASSWORD,
        credentialId = "cred-$id",
    )

    /** Panes reach Connected (any host, one shared SSH session id), SFTP opens through [sftpManager]. */
    private fun buildManager(scope: CoroutineScope, sftpManager: DesktopSftpManager): DesktopSessionManager {
        val sshSessionManager = mockk<DesktopSshSessionManager>(relaxed = true)
        every { sshSessionManager.sessions } returns MutableStateFlow(emptyList<SshSession>())
        coEvery { sshSessionManager.connectWithPassword(any(), any()) } returns
            SshResult.Success(SshSession(id = "ssh-shared", host = makeHost("shared"), status = SessionStatus.CONNECTED))
        every { sshSessionManager.getTerminalSession(any()) } returns
            mockk<DesktopSshTerminalSession>(relaxed = true)

        val vaultManager = mockk<VaultManager>(relaxed = true)
        coEvery { vaultManager.getPassword(any()) } returns "pw".toCharArray()

        return DesktopSessionManager(
            hostRepository = mockk<HostRepository>(relaxed = true),
            vaultManager = vaultManager,
            sshSessionManager = sshSessionManager,
            sftpManager = sftpManager,
            knownHostsVerifier = mockk<DesktopKnownHostsVerifier>(relaxed = true),
            appScope = TestAppScope(scope),
        )
    }

    private fun openingSftpManager(): DesktopSftpManager {
        val sftpManager = mockk<DesktopSftpManager>(relaxed = true)
        coEvery { sftpManager.openSftp(any()) } returns SshResult.Success(Unit)
        coEvery { sftpManager.getHomeDirectory(any()) } returns SshResult.Success("/root")
        return sftpManager
    }

    private fun DesktopSessionManager.contentOf(tabId: String): TabContent =
        tabs.value.first { it.tabId == tabId }.content

    /** The single SFTP pane of the tree, failing when there is not exactly one. */
    private fun soleSftp(content: TabContent): TabContent.Sftp = when (content) {
        is TabContent.Terminal -> error("no SFTP pane here")
        is TabContent.Sftp -> content
        is TabContent.Split -> listOfNotNull(
            runCatching { soleSftp(content.first) }.getOrNull(),
            runCatching { soleSftp(content.second) }.getOrNull(),
        ).single()
    }

    // ── withSftpPaneState (pure) ──────────────────────────────────────────────

    @Test
    fun `the folder of a nested pane is kept under its own id`() {
        val tree = split(
            split(terminal(), sftp("pane-x")),
            split(terminal(), sftp("pane-y")),
        )

        val updated = withSftpPaneState(tree, "pane-x") { SftpTabState.Open("/var/log") }

        val root = updated as TabContent.Split
        val left = root.first as TabContent.Split
        val right = root.second as TabContent.Split
        assertEquals(SftpTabState.Open("/var/log"), (left.second as TabContent.Sftp).state)
        assertEquals(SftpTabState.Open("/home"), (right.second as TabContent.Sftp).state)
    }

    @Test
    fun `a nested pane never writes over the pane that shares its last slot`() {
        // Both SFTP panes are in the second slot of their parent: the old routing by the
        // last slot could not tell them apart.
        val sftp1 = sftp("pane-1", "/etc")
        val sftp2 = sftp("pane-2", "/home")
        val tree = split(split(terminal(), sftp2), sftp1)

        val updated = withSftpPaneState(tree, "pane-2") { SftpTabState.Open("/var/log") } as TabContent.Split

        assertEquals(sftp1, updated.second)
        assertEquals(
            SftpTabState.Open("/var/log"),
            ((updated.first as TabContent.Split).second as TabContent.Sftp).state,
        )
    }

    @Test
    fun `a pane three levels deep is reached`() {
        val deep = sftp("pane-deep")
        val tree = split(terminal(), split(terminal(), split(terminal(), deep)))

        val updated = withSftpPaneState(tree, "pane-deep") { SftpTabState.Open("/tmp") }

        val level1 = (updated as TabContent.Split).second as TabContent.Split
        val level2 = level1.second as TabContent.Split
        assertEquals(deep.copy(state = SftpTabState.Open("/tmp")), level2.second)
    }

    @Test
    fun `a lone SFTP tab content is reached by its own id`() {
        val updated = withSftpPaneState(sftp("p"), "p") { SftpTabState.Open("/var/log") }

        assertEquals(sftp("p", "/var/log"), updated)
        assertEquals(sftp("p"), withSftpPaneState(sftp("p"), "q") { SftpTabState.Open("/var/log") })
    }

    @Test
    fun `an unknown pane id leaves the tree as it is`() {
        val tree = split(split(terminal(), sftp("pane-1")), sftp("pane-2"))

        val updated = withSftpPaneState(tree, "pane-unknown") { SftpTabState.Open("/tmp") }

        assertEquals(tree, updated)
    }

    // ── updateSftpPaneCwd / opening, with a real manager ──────────────────────

    @Test
    fun `updateSftpPaneCwd replaces neither an opening pane nor a failed one`() =
        runTest(UnconfinedTestDispatcher()) {
            val gate = CompletableDeferred<Unit>()
            val sftpManager = mockk<DesktopSftpManager>(relaxed = true)
            coEvery { sftpManager.openSftp(any()) } returns SshResult.Success(Unit)
            coEvery { sftpManager.getHomeDirectory(any()) } coAnswers {
                gate.await()
                SshResult.Error(SshErrorCode.UNKNOWN, "no home")
            }
            val mgr = buildManager(backgroundScope, sftpManager)
            val tabId = mgr.openSession(makeHost("A"))
            advanceUntilIdle()
            assertTrue(mgr.splitPaneAsSftpAt(tabId, emptyList()))
            val paneId = soleSftp(mgr.contentOf(tabId)).paneId
            assertEquals(SftpTabState.Opening, soleSftp(mgr.contentOf(tabId)).state)

            mgr.updateSftpPaneCwd(tabId, paneId, "/var/log")
            assertEquals(SftpTabState.Opening, soleSftp(mgr.contentOf(tabId)).state)

            gate.complete(Unit)
            advanceUntilIdle()
            assertIs<SftpTabState.Error>(soleSftp(mgr.contentOf(tabId)).state)

            mgr.updateSftpPaneCwd(tabId, paneId, "/var/log")
            assertIs<SftpTabState.Error>(soleSftp(mgr.contentOf(tabId)).state)
        }

    @Test
    fun `the folder of a nested SFTP pane is kept and the sibling terminal is untouched`() =
        runTest(UnconfinedTestDispatcher()) {
            val mgr = buildManager(backgroundScope, openingSftpManager())
            val tabId = mgr.openSession(makeHost("A"))
            advanceUntilIdle()
            assertTrue(mgr.splitPaneAt(tabId, emptyList(), makeHost("B")))
            advanceUntilIdle()
            assertTrue(mgr.splitPaneAsSftpAt(tabId, listOf(PaneSlot.LEFT_OR_TOP)))
            advanceUntilIdle()
            val before = mgr.contentOf(tabId) as TabContent.Split
            val pane = soleSftp(before)
            assertEquals(SftpTabState.Open("/root"), pane.state)

            mgr.updateSftpPaneCwd(tabId, pane.paneId, "/var/log")

            val after = mgr.contentOf(tabId) as TabContent.Split
            assertEquals(pane.copy(state = SftpTabState.Open("/var/log")), (after.first as TabContent.Split).second)
            assertEquals(before.second, after.second)
        }

    @Test
    fun `the folder follows the pane when its sibling is closed`() =
        runTest(UnconfinedTestDispatcher()) {
            val mgr = buildManager(backgroundScope, openingSftpManager())
            val tabId = mgr.openSession(makeHost("A"))
            advanceUntilIdle()
            assertTrue(mgr.splitPaneAt(tabId, emptyList(), makeHost("B")))
            advanceUntilIdle()
            assertTrue(mgr.splitPaneAsSftpAt(tabId, listOf(PaneSlot.LEFT_OR_TOP)))
            advanceUntilIdle()
            val paneId = soleSftp(mgr.contentOf(tabId)).paneId
            mgr.updateSftpPaneCwd(tabId, paneId, "/var/log")

            // Split(Split(A, SFTP), B) -> Split(SFTP, B): the pane goes from [LEFT, RIGHT] to [LEFT].
            mgr.closePaneAt(tabId, listOf(PaneSlot.LEFT_OR_TOP, PaneSlot.LEFT_OR_TOP))
            advanceUntilIdle()

            val moved = (mgr.contentOf(tabId) as TabContent.Split).first as TabContent.Sftp
            assertEquals(paneId, moved.paneId)
            assertEquals(SftpTabState.Open("/var/log"), moved.state)

            mgr.updateSftpPaneCwd(tabId, paneId, "/etc")
            assertEquals(SftpTabState.Open("/etc"), soleSftp(mgr.contentOf(tabId)).state)
        }

    @Test
    fun `the folder of a root SFTP tab is kept under its pane id`() =
        runTest(UnconfinedTestDispatcher()) {
            val mgr = buildManager(backgroundScope, openingSftpManager())
            val terminalTab = mgr.openSession(makeHost("A"))
            advanceUntilIdle()
            val tabId = mgr.openSftpTab(makeHost("A"), "ssh-shared") ?: error("the SFTP tab must open")
            advanceUntilIdle()
            val tab = mgr.contentOf(tabId) as TabContent.Sftp
            assertEquals(SftpTabState.Open("/root"), tab.state)

            mgr.updateSftpPaneCwd(tabId, tab.paneId, "/var/log")

            assertEquals(SftpTabState.Open("/var/log"), (mgr.contentOf(tabId) as TabContent.Sftp).state)
            assertTrue(mgr.contentOf(terminalTab) is TabContent.Terminal)
        }

    @Test
    fun `a late result does not bring back to Open a root SFTP tab whose terminal was closed`() =
        runTest(UnconfinedTestDispatcher()) {
            val gate = CompletableDeferred<Unit>()
            val sftpManager = mockk<DesktopSftpManager>(relaxed = true)
            coEvery { sftpManager.openSftp(any()) } returns SshResult.Success(Unit)
            coEvery { sftpManager.getHomeDirectory(any()) } coAnswers {
                gate.await()
                SshResult.Success("/root")
            }
            val mgr = buildManager(backgroundScope, sftpManager)
            val terminalTab = mgr.openSession(makeHost("A"))
            advanceUntilIdle()
            val tabId = mgr.openSftpTab(makeHost("A"), "ssh-shared") ?: error("the SFTP tab must open")
            assertEquals(SftpTabState.Opening, (mgr.contentOf(tabId) as TabContent.Sftp).state)

            // The linked terminal closes while the tab opens: the tab goes to Error (asynchronously, the
            // message comes from the resources), then the late home directory arrives.
            mgr.closeSession(terminalTab)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    mgr.tabs.first { list ->
                        (list.firstOrNull { it.tabId == tabId }?.content as? TabContent.Sftp)?.state is SftpTabState.Error
                    }
                }
            }
            gate.complete(Unit)
            advanceUntilIdle()

            assertIs<SftpTabState.Error>((mgr.contentOf(tabId) as TabContent.Sftp).state)
        }

    @Test
    fun `an SFTP pane that opens while the tree changes lands on itself`() =
        runTest(UnconfinedTestDispatcher()) {
            val gate = CompletableDeferred<Unit>()
            val sftpManager = mockk<DesktopSftpManager>(relaxed = true)
            coEvery { sftpManager.openSftp(any()) } returns SshResult.Success(Unit)
            coEvery { sftpManager.getHomeDirectory(any()) } coAnswers {
                gate.await()
                SshResult.Success("/root")
            }
            val mgr = buildManager(backgroundScope, sftpManager)
            val tabId = mgr.openSession(makeHost("A"))
            advanceUntilIdle()
            assertTrue(mgr.splitPaneAt(tabId, emptyList(), makeHost("B")))
            advanceUntilIdle()
            assertTrue(mgr.splitPaneAsSftpAt(tabId, listOf(PaneSlot.LEFT_OR_TOP)))
            val paneId = soleSftp(mgr.contentOf(tabId)).paneId
            assertEquals(SftpTabState.Opening, soleSftp(mgr.contentOf(tabId)).state)

            // The sibling terminal closes while the pane is still opening: the pane moves up.
            mgr.closePaneAt(tabId, listOf(PaneSlot.LEFT_OR_TOP, PaneSlot.LEFT_OR_TOP))
            gate.complete(Unit)
            advanceUntilIdle()

            val moved = (mgr.contentOf(tabId) as TabContent.Split).first as TabContent.Sftp
            assertEquals(paneId, moved.paneId)
            assertEquals(SftpTabState.Open("/root"), moved.state)
        }
}
