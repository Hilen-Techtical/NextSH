// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sessions

// ──────────────────────────────────────────────────────────────────────────────
// A terminal pane keeps the host it was opened for (TabContent.Terminal.host). Reconnect, "Retry" and the
// password prompt of a split pane must target THAT host, never the host of the tab (= the first pane).
// The hosts below are really distinct (hostname, credential id): a rig that gives every host the same
// hostname would not see the bug.
//
// Connection results of the second pane are awaited on the real manager (Dispatchers.Default scope),
// because the loss of a session goes through compose-resources strings (getString).
// ──────────────────────────────────────────────────────────────────────────────

import fr.techtical.nextsh.desktop.core.ssh.DesktopKnownHostsVerifier
import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.reconnect_default_message_lost
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
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.getString
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

internal const val WAIT_MS = 30_000L

internal class ObservedFlow(private val inner: MutableStateFlow<List<SshSession>>) : StateFlow<List<SshSession>> by inner {
    @Volatile var seen: List<SshSession>? = null

    override suspend fun collect(collector: FlowCollector<List<SshSession>>): Nothing {
        inner.collect {
            collector.emit(it)
            seen = it
        }
    }
}

class DesktopSessionManagerPaneHostTest {

    private class TestAppScope(scope: CoroutineScope) : AppScope {
        override val coroutineScope: CoroutineScope = scope
        override fun onDestroy() = Unit
    }

    private fun host(id: String) = Host(
        id = id,
        label = id,
        hostname = "${id.lowercase()}.example.org",
        port = 22,
        username = "user-$id",
        authType = AuthType.PASSWORD,
        credentialId = "cred-$id",
    )

    private val hostA = host("A")
    private val hostB = host("B")
    private val hostC = host("C")

    private val left = listOf(PaneSlot.LEFT_OR_TOP)
    private val right = listOf(PaneSlot.RIGHT_OR_BOTTOM)
    private val rightLeft = listOf(PaneSlot.RIGHT_OR_BOTTOM, PaneSlot.LEFT_OR_TOP)
    private val rightRight = listOf(PaneSlot.RIGHT_OR_BOTTOM, PaneSlot.RIGHT_OR_BOTTOM)

    private fun paneOf(content: TabContent, path: List<PaneSlot>): TabContent =
        path.fold(content) { acc, slot -> (acc as TabContent.Split).pane(slot) }

    private class Rig(scope: CoroutineScope, storedPasswordFor: Set<String>) {
        /** Hosts received by every connectWithPassword call, in call order. */
        val connectedHosts = CopyOnWriteArrayList<Host>()
        val liveSessions = MutableStateFlow<List<SshSession>>(emptyList())

        /** What the manager handed to [liveSessions] collectors: lets [lose] wait until it has processed a value. */
        private val observed = ObservedFlow(liveSessions)

        /** Call index (0-based) to the error that call answers. */
        val scriptedErrors = HashMap<Int, SshResult.Error>()

        /** Call index (0-based) to a gate the call waits for before answering. */
        val gates = HashMap<Int, CompletableDeferred<Unit>>()

        val hostRepository = mockk<HostRepository>(relaxed = true)
        val sftpManager = mockk<DesktopSftpManager>(relaxed = true)
        val manager: DesktopSessionManager

        init {
            val ssh = mockk<DesktopSshSessionManager>(relaxed = true)
            every { ssh.sessions } returns observed
            every { ssh.getTerminalSession(any()) } returns mockk<DesktopSshTerminalSession>(relaxed = true)
            coEvery { ssh.connectWithPassword(any(), any()) } coAnswers {
                val host = firstArg<Host>()
                val n = connectedHosts.size
                connectedHosts.add(host)
                gates[n]?.await()
                val result: SshResult<SshSession> = scriptedErrors[n] ?: run {
                    val session = SshSession(id = "ssh-${n + 1}", host = host, status = SessionStatus.CONNECTED)
                    liveSessions.value = liveSessions.value + session
                    SshResult.Success(session)
                }
                result
            }
            val vault = mockk<VaultManager>(relaxed = true)
            coEvery { vault.getPassword(any()) } coAnswers {
                if (firstArg<String>() in storedPasswordFor) "pw".toCharArray() else null
            }
            coEvery { sftpManager.openSftp(any()) } returns SshResult.Success(Unit)
            coEvery { sftpManager.getHomeDirectory(any()) } returns SshResult.Success("/root")
            manager = DesktopSessionManager(
                hostRepository = hostRepository,
                vaultManager = vault,
                sshSessionManager = ssh,
                sftpManager = sftpManager,
                knownHostsVerifier = mockk<DesktopKnownHostsVerifier>(relaxed = true),
                appScope = TestAppScope(scope),
            )
        }

        /** The sessions of [hostIds] vanish from the registry, as when the server reboots. */
        suspend fun lose(vararg hostIds: String) {
            // A StateFlow conflates: removing a session the manager has not seen yet would hide the loss.
            withTimeout(WAIT_MS) { while (observed.seen != liveSessions.value) delay(5) }
            liveSessions.value = liveSessions.value.filter { it.host.id !in hostIds }
        }

        suspend fun awaitPane(tabId: String, path: List<PaneSlot>, what: String, condition: (TabContent) -> Boolean) {
            val reached = withTimeoutOrNull(WAIT_MS) {
                manager.tabs.first { tabs ->
                    val tab = tabs.firstOrNull { it.tabId == tabId } ?: return@first false
                    runCatching { condition(path.fold(tab.content) { acc, s -> (acc as TabContent.Split).pane(s) }) }
                        .getOrDefault(false)
                }
            }
            if (reached == null) error("timed out waiting for: $what")
        }

        suspend fun awaitConnected(tabId: String, path: List<PaneSlot>) =
            awaitPane(tabId, path, "connected pane") {
                (it as? TabContent.Terminal)?.status is TerminalTabStatus.Connected
            }

        suspend fun awaitDisconnected(tabId: String, path: List<PaneSlot>) =
            awaitPane(tabId, path, "disconnected pane") {
                (it as? TabContent.Terminal)?.status is TerminalTabStatus.Disconnected
            }

        suspend fun awaitError(tabId: String, path: List<PaneSlot>) =
            awaitPane(tabId, path, "pane in error") {
                (it as? TabContent.Terminal)?.status is TerminalTabStatus.Error
            }
    }

    private fun withRig(storedPasswordFor: Set<String> = setOf("cred-A", "cred-B", "cred-C"), block: suspend Rig.() -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            runBlocking {
                // Cold start of the Compose resources (read by handleSessionsGone) can be slow: load them first.
                getString(Res.string.reconnect_default_message_lost)
                val rig = Rig(scope, storedPasswordFor)
                // The manager reads the registry before it subscribes: losing a session before that would go unseen.
                withTimeout(WAIT_MS) { rig.liveSessions.subscriptionCount.first { it >= 1 } }
                rig.block()
            }
        } finally {
            scope.cancel()
        }
    }

    // ── Reconnect ─────────────────────────────────────────────────────────────

    @Test
    fun `after both sessions of a split are lost each pane reconnects to its own host`() = withRig {
        val tabId = manager.openSession(hostA)
        awaitConnected(tabId, emptyList())
        assertEquals(true, manager.splitPaneAt(tabId, emptyList(), hostB))
        awaitConnected(tabId, right)
        val calls = connectedHosts.size

        lose("A", "B")
        awaitDisconnected(tabId, left)
        awaitDisconnected(tabId, right)

        manager.reconnectAt(tabId, left)
        awaitConnected(tabId, left)
        manager.reconnectAt(tabId, right)
        awaitConnected(tabId, right)

        assertEquals(listOf("A", "B"), connectedHosts.drop(calls).map { it.id })
        val rightPane = paneOf(manager.tabs.value.single().content, right) as TabContent.Terminal
        val newId = (rightPane.status as TerminalTabStatus.Connected).sshSessionId
        assertEquals("B", manager.hostForSshSession(newId)?.id)
        coVerify(atLeast = 2, timeout = WAIT_MS) { hostRepository.updateLastConnected("B") }
    }

    @Test
    fun `retrying a split pane whose first connection failed targets that pane's host`() = withRig {
        scriptedErrors[1] = SshResult.Error(SshErrorCode.UNKNOWN, "unreachable")
        val tabId = manager.openSession(hostA)
        awaitConnected(tabId, emptyList())
        manager.splitPaneAt(tabId, emptyList(), hostB)
        awaitError(tabId, right)

        manager.reconnectAt(tabId, right)
        awaitConnected(tabId, right)

        assertEquals(listOf("A", "B", "B"), connectedHosts.map { it.id })
    }

    @Test
    fun `a password typed in a split pane is sent to that pane's host only`() = withRig(setOf("cred-A")) {
        val tabId = manager.openSession(hostA)
        awaitConnected(tabId, emptyList())
        manager.splitPaneAt(tabId, emptyList(), hostB)
        awaitPane(tabId, right, "password prompt of the second pane") {
            (it as? TabContent.Terminal)?.status is TerminalTabStatus.AwaitingPassword
        }
        assertEquals(listOf("A"), connectedHosts.map { it.id })

        manager.submitPasswordAt(tabId, right, "typed-for-b".toCharArray())
        awaitConnected(tabId, right)

        assertEquals(listOf("A", "B"), connectedHosts.map { it.id })
    }

    @Test
    fun `three panes reconnect each to its own host`() = withRig {
        val tabId = manager.openSession(hostA)
        awaitConnected(tabId, emptyList())
        manager.splitPaneAt(tabId, emptyList(), hostB)
        awaitConnected(tabId, right)
        manager.splitPaneAt(tabId, right, hostC)
        awaitConnected(tabId, rightRight)
        val calls = connectedHosts.size

        lose("A", "B", "C")
        awaitDisconnected(tabId, left)
        awaitDisconnected(tabId, rightLeft)
        awaitDisconnected(tabId, rightRight)

        for (path in listOf(left, rightLeft, rightRight)) {
            manager.reconnectAt(tabId, path)
            awaitConnected(tabId, path)
        }

        assertEquals(listOf("A", "B", "C"), connectedHosts.drop(calls).map { it.id })
    }

    @Test
    fun `the pane left alone after closing the first one reconnects to its own host`() = withRig {
        val tabId = manager.openSession(hostA)
        awaitConnected(tabId, emptyList())
        manager.splitPaneAt(tabId, emptyList(), hostB)
        awaitConnected(tabId, right)

        manager.closePaneAt(tabId, left)
        assertEquals("B", manager.tabs.value.single().host.id, "the tab follows the surviving pane")
        val calls = connectedHosts.size
        lose("B")
        awaitDisconnected(tabId, emptyList())

        manager.reconnectAt(tabId, emptyList())
        awaitConnected(tabId, emptyList())

        assertEquals(listOf("B"), connectedHosts.drop(calls).map { it.id })
    }

    @Test
    fun `closing the first pane of a three pane tab moves the tab to a host still on screen`() = withRig {
        val tabId = manager.openSession(hostA)
        awaitConnected(tabId, emptyList())
        manager.splitPaneAt(tabId, emptyList(), hostB)
        awaitConnected(tabId, right)
        manager.splitPaneAt(tabId, right, hostC)
        awaitConnected(tabId, rightRight)

        manager.closePaneAt(tabId, left)

        val tab = manager.tabs.value.single()
        assertIs<TabContent.Split>(tab.content)
        assertEquals("C", tab.host.id, "no pane targets A any more: the tab follows the focused pane")

        manager.closePaneAt(tabId, left)
        assertEquals("C", manager.tabs.value.single().host.id)
    }

    @Test
    fun `the host of a pane survives every status change`() = withRig {
        val tabId = manager.openSession(hostA)
        awaitConnected(tabId, emptyList())
        manager.splitPaneAt(tabId, emptyList(), hostB)
        awaitConnected(tabId, right)
        fun hostOfRight() = (paneOf(manager.tabs.value.single().content, right) as TabContent.Terminal).host.id
        assertEquals("B", hostOfRight())

        lose("B")
        awaitDisconnected(tabId, right)
        assertEquals("B", hostOfRight())

        val gate = CompletableDeferred<Unit>()
        gates[connectedHosts.size] = gate
        manager.reconnectAt(tabId, right)
        awaitPane(tabId, right, "connecting pane") { (it as? TabContent.Terminal)?.status is TerminalTabStatus.Connecting }
        assertEquals("B", hostOfRight())

        gate.complete(Unit)
        awaitConnected(tabId, right)
        assertEquals("B", hostOfRight())
    }

    @Test
    fun `a second Reconnect while connecting starts no second connection`() = withRig {
        val tabId = manager.openSession(hostA)
        awaitConnected(tabId, emptyList())
        manager.splitPaneAt(tabId, emptyList(), hostB)
        awaitConnected(tabId, right)
        lose("B")
        awaitDisconnected(tabId, right)
        val calls = connectedHosts.size
        val gate = CompletableDeferred<Unit>()
        gates[calls] = gate

        manager.reconnectAt(tabId, right)
        manager.reconnectAt(tabId, right)
        gate.complete(Unit)
        awaitConnected(tabId, right)

        assertEquals(calls + 1, connectedHosts.size)
    }

    // ── SFTP panes and the focused host ───────────────────────────────────────

    @Test
    fun `an SFTP pane split from the second pane carries that pane's host`() = withRig {
        val tabId = manager.openSession(hostA)
        awaitConnected(tabId, emptyList())
        manager.splitPaneAt(tabId, emptyList(), hostB)
        awaitConnected(tabId, right)

        assertEquals(true, manager.splitPaneAsSftpAt(tabId, right))

        val sftp = paneOf(manager.tabs.value.single().content, rightRight)
        assertIs<TabContent.Sftp>(sftp)
        assertEquals("B", sftp.host.id)
    }

    @Test
    fun `the snippet filter follows the host of the active pane`() = withRig {
        val tabId = manager.openSession(hostA)
        awaitConnected(tabId, emptyList())
        assertEquals("A", manager.activeTabHost()?.id)

        manager.splitPaneAt(tabId, emptyList(), hostB)

        assertEquals("B", manager.activeTabHost()?.id)
    }

    @Test
    fun `focusedPaneHost and hostOfSessionIn read the pane rather than the tab`() {
        val theme = ResolvedTheme(name = "test", palette = mockk(relaxed = true))
        fun terminal(host: Host, sessionId: String) = TabContent.Terminal(
            theme = theme,
            status = TerminalTabStatus.Connected(sessionId, mockk<DesktopSshTerminalSession>(relaxed = true)),
            host = host,
        )
        val tree = TabContent.Split(
            first = terminal(hostA, "ssh-a"),
            second = TabContent.Split(
                first = terminal(hostB, "ssh-b"),
                second = TabContent.Sftp("ssh-c", SftpTabState.Opening, hostC),
                orientation = SplitOrientation.VERTICAL,
                focusedSlot = PaneSlot.RIGHT_OR_BOTTOM,
            ),
            orientation = SplitOrientation.HORIZONTAL,
            focusedSlot = PaneSlot.RIGHT_OR_BOTTOM,
        )

        assertEquals("C", focusedPaneHost(tree).id)
        assertEquals("A", focusedPaneHost(tree.copy(focusedSlot = PaneSlot.LEFT_OR_TOP)).id)
        assertEquals("A", hostOfSessionIn(tree, "ssh-a")?.id)
        assertEquals("B", hostOfSessionIn(tree, "ssh-b")?.id)
        assertEquals("C", hostOfSessionIn(tree, "ssh-c")?.id)
        assertEquals(null, hostOfSessionIn(tree, "ssh-unknown"))
    }
}
