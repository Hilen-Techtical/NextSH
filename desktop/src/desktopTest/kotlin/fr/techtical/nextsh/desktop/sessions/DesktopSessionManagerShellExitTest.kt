// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sessions

// ──────────────────────────────────────────────────────────────────────────────
// A shell that ends by itself (`exit`): the pane becomes Ended (never "connection lost"), the terminal is
// released, and the connection is closed unless an SFTP pane still uses it. Also the SFTP leak next to it:
// closing the last SFTP pane of a connection whose terminal is gone must disconnect it.
//
// The shell end goes through the real chain: a fake connector given to getOrCreateComposeSession ends its
// stream, ComposeTerminalSession reports it, the manager handles it. Sessions are numbered `ssh-<n>` by call
// order; every pane has its own id.
// ──────────────────────────────────────────────────────────────────────────────

import com.jediterm.terminal.TtyConnector
import fr.techtical.nextsh.desktop.core.ssh.DesktopKnownHostsVerifier
import fr.techtical.nextsh.desktop.core.ssh.DesktopSftpManager
import fr.techtical.nextsh.desktop.core.ssh.DesktopSshSessionManager
import fr.techtical.nextsh.desktop.core.ssh.DesktopSshTerminalSession
import fr.techtical.nextsh.desktop.core.ssh.ShellEndInfo
import fr.techtical.nextsh.desktop.theme.TerminalThemePalette
import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.SessionStatus
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.shared.domain.model.SshSession
import fr.techtical.nextsh.shared.domain.model.TransferDirection
import fr.techtical.nextsh.shared.domain.model.TransferRequest
import fr.techtical.nextsh.shared.domain.model.TransferState
import fr.techtical.nextsh.shared.domain.repository.HostRepository
import fr.techtical.nextsh.shared.domain.vault.VaultManager
import fr.techtical.nextsh.shared.util.AppScope
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DesktopSessionManagerShellExitTest {

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

    private val left = listOf(PaneSlot.LEFT_OR_TOP)
    private val right = listOf(PaneSlot.RIGHT_OR_BOTTOM)

    private val palette = TerminalThemePalette(
        background = 0x000000,
        foreground = 0xFFFFFF,
        cursor = 0xFFFFFF,
        selectionBg = 0x333333,
        ansi = IntArray(16),
    )

    /** A shell whose stream the test ends, as a server does after `exit`. */
    private class FakeShell(private val code: Int?) : TtyConnector, ShellEndInfo {
        private val streamEnd = CountDownLatch(1)

        @Volatile private var connected = true

        override val closedLocally: Boolean get() = !connected

        fun exit() = streamEnd.countDown()

        override fun exitCode(): Int? = code
        override fun read(buf: CharArray, offset: Int, length: Int): Int {
            streamEnd.await()
            return -1
        }

        override fun write(bytes: ByteArray) = Unit
        override fun write(string: String) = Unit
        override fun isConnected(): Boolean = connected
        override fun waitFor(): Int = 0
        override fun ready(): Boolean = false
        override fun getName(): String = "fake"

        override fun close() {
            connected = false
            streamEnd.countDown()
        }
    }

    private class Rig(scope: CoroutineScope) {
        val connectedHosts = CopyOnWriteArrayList<Host>()
        val liveSessions = MutableStateFlow<List<SshSession>>(emptyList())
        private val observed = ObservedFlow(liveSessions)
        val transfers = MutableStateFlow<Map<String, TransferState>>(emptyMap())
        val ssh = mockk<DesktopSshSessionManager>(relaxed = true)
        val sftpManager = mockk<DesktopSftpManager>(relaxed = true)
        val manager: DesktopSessionManager

        init {
            every { ssh.sessions } returns observed
            every { ssh.getTerminalSession(any()) } returns mockk<DesktopSshTerminalSession>(relaxed = true)
            coEvery { ssh.connectWithPassword(any(), any()) } coAnswers {
                val host = firstArg<Host>()
                val n = connectedHosts.size
                connectedHosts.add(host)
                val session = SshSession(id = "ssh-${n + 1}", host = host, status = SessionStatus.CONNECTED)
                liveSessions.value = liveSessions.value + session
                SshResult.Success(session)
            }
            // The registry drops a session when it is disconnected, as the real manager does.
            coEvery { ssh.disconnect(any()) } coAnswers {
                val id = firstArg<String>()
                liveSessions.value = liveSessions.value.filter { it.id != id }
                Unit
            }
            val vault = mockk<VaultManager>(relaxed = true)
            coEvery { vault.getPassword(any()) } returns "pw".toCharArray()
            coEvery { sftpManager.openSftp(any()) } returns SshResult.Success(Unit)
            coEvery { sftpManager.getHomeDirectory(any()) } returns SshResult.Success("/root")
            manager = DesktopSessionManager(
                hostRepository = mockk<HostRepository>(relaxed = true),
                vaultManager = vault,
                sshSessionManager = ssh,
                sftpManager = sftpManager,
                knownHostsVerifier = mockk<DesktopKnownHostsVerifier>(relaxed = true),
                appScope = TestAppScope(scope),
                transfers = transfers,
            )
        }

        /** Waits until the manager has processed the latest registry value (a StateFlow conflates). */
        suspend fun awaitRegistryRead() {
            withTimeout(WAIT_MS) { while (observed.seen != liveSessions.value) delay(5) }
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

        suspend fun awaitEnded(tabId: String, path: List<PaneSlot>, code: Int?) =
            awaitPane(tabId, path, "ended pane") {
                ((it as? TabContent.Terminal)?.status as? TerminalTabStatus.Ended)?.exitCode == code &&
                    (it as TabContent.Terminal).status is TerminalTabStatus.Ended
            }

        suspend fun awaitSftpOpen(tabId: String, path: List<PaneSlot>) =
            awaitPane(tabId, path, "open SFTP pane") {
                (it as? TabContent.Sftp)?.state is SftpTabState.Open
            }

        fun paneAt(tabId: String, path: List<PaneSlot>): TabContent =
            path.fold(manager.tabs.value.first { it.tabId == tabId }.content) { acc, s -> (acc as TabContent.Split).pane(s) }
    }

    private fun withRig(block: suspend Rig.() -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            runBlocking {
                val rig = Rig(scope)
                withTimeout(WAIT_MS) { rig.liveSessions.subscriptionCount.first { it >= 1 } }
                rig.block()
            }
        } finally {
            scope.cancel()
        }
    }

    /** Mounts the terminal of [sshSessionId], as the screen does, and returns the shell to end. */
    private fun Rig.mountShell(sshSessionId: String, code: Int? = 0): FakeShell {
        val shell = FakeShell(code)
        manager.getOrCreateComposeSession(sshSessionId, shell, palette)
        return shell
    }

    @Test
    fun `a lone pane whose shell exits becomes Ended and its connection is closed`() = withRig {
        val tabId = manager.openSession(hostA)
        awaitConnected(tabId, emptyList())
        val shell = mountShell("ssh-1", code = 3)

        shell.exit()

        awaitEnded(tabId, emptyList(), 3)
        coVerify(timeout = WAIT_MS) { ssh.disconnect("ssh-1") }
        coVerify { sftpManager.closeSftp("ssh-1") }
    }

    @Test
    fun `an exit without a code is shown as Ended with no code`() = withRig {
        val tabId = manager.openSession(hostA)
        awaitConnected(tabId, emptyList())

        mountShell("ssh-1", code = null).exit()

        awaitEnded(tabId, emptyList(), null)
    }

    @Test
    fun `the exit of a terminal next to an SFTP pane keeps the connection for the SFTP`() = withRig {
        val tabId = manager.openSession(hostA)
        awaitConnected(tabId, emptyList())
        assertTrue(manager.splitPaneAsSftpAt(tabId, emptyList()))
        awaitSftpOpen(tabId, right)
        val shell = mountShell("ssh-1")

        shell.exit()
        awaitEnded(tabId, left, 0)
        // The exit handler has done its closing work once the registry would have been touched: give
        // the background launch the time to (wrongly) disconnect before checking it did not.
        delay(300)

        coVerify(exactly = 0) { ssh.disconnect(any()) }
        assertIs<SftpTabState.Open>((paneAt(tabId, right) as TabContent.Sftp).state)

        // The SFTP pane was the last user: closing it closes the connection, SFTP client first.
        manager.closePaneAt(tabId, right)
        coVerify(timeout = WAIT_MS) { ssh.disconnect("ssh-1") }
        coVerifyOrder {
            sftpManager.closeSftp("ssh-1")
            ssh.disconnect("ssh-1")
        }
    }

    @Test
    fun `the registry dropping the session after the end does not turn it into a loss`() = withRig {
        val tabId = manager.openSession(hostA)
        awaitConnected(tabId, emptyList())
        mountShell("ssh-1", code = 0).exit()
        awaitEnded(tabId, emptyList(), 0)
        coVerify(timeout = WAIT_MS) { ssh.disconnect("ssh-1") }

        awaitRegistryRead()
        delay(200)

        val status = (paneAt(tabId, emptyList()) as TabContent.Terminal).status
        assertIs<TerminalTabStatus.Ended>(status)
        assertEquals(0, status.exitCode)
    }

    @Test
    fun `an ended pane reconnects to its own host`() = withRig {
        val tabId = manager.openSession(hostA)
        awaitConnected(tabId, emptyList())
        mountShell("ssh-1").exit()
        awaitEnded(tabId, emptyList(), 0)
        val calls = connectedHosts.size

        manager.reconnectAt(tabId, emptyList())
        awaitConnected(tabId, emptyList())

        assertEquals(listOf("A"), connectedHosts.drop(calls).map { it.id })
    }

    @Test
    fun `an ended pane no longer counts as connected and stops the broadcast`() = withRig {
        val tabId = manager.openSession(hostA)
        awaitConnected(tabId, emptyList())
        manager.splitPaneAt(tabId, emptyList(), hostB)
        awaitConnected(tabId, right)
        manager.toggleBroadcastOnActiveTab()
        assertTrue(tabId in manager.broadcastTabIds.value)
        assertEquals(2, manager.connectedTerminalPaneCount())

        mountShell("ssh-2").exit()
        awaitEnded(tabId, right, 0)

        assertEquals(1, manager.connectedTerminalPaneCount())
        withTimeout(WAIT_MS) { manager.broadcastTabIds.first { tabId !in it } }
    }

    @Test
    fun `closing the terminal then the SFTP pane of a split disconnects the connection`() = withRig {
        val tabId = manager.openSession(hostA)
        awaitConnected(tabId, emptyList())
        assertTrue(manager.splitPaneAsSftpAt(tabId, emptyList()))
        awaitSftpOpen(tabId, right)

        manager.closePaneAt(tabId, left)
        delay(300)
        coVerify(exactly = 0) { ssh.disconnect(any()) }

        // The SFTP pane is now alone at the root: closing it closes the tab.
        manager.closePaneAt(tabId, emptyList())
        coVerify(timeout = WAIT_MS) { ssh.disconnect("ssh-1") }
    }

    @Test
    fun `a pane that lost its session releases its terminal`() = withRig {
        val tabId = manager.openSession(hostA)
        awaitConnected(tabId, emptyList())
        val shell = mountShell("ssh-1")

        // The registry loses the session (watchdog shutdown, server reboot).
        awaitRegistryRead()
        liveSessions.value = emptyList()
        awaitPane(tabId, emptyList(), "disconnected pane") {
            (it as? TabContent.Terminal)?.status is TerminalTabStatus.Disconnected
        }

        // Its compose session was closed: the connector it held is closed on our side.
        withTimeout(WAIT_MS) { while (shell.isConnected()) delay(5) }
        assertEquals(true, shell.closedLocally)
    }
    // ── Transfers keep their connection ───────────────────────────────────────

    private fun request(sshSessionId: String) = TransferRequest(
        sessionId = sshSessionId,
        remotePath = "/srv/big.iso",
        localUri = "big.iso",
        direction = TransferDirection.UPLOAD,
        fileSize = 1_000L,
        displayName = "big.iso",
    )

    private fun Rig.transferRunning(request: TransferRequest) {
        transfers.value = mapOf(request.id to TransferState.InProgress(request, 10L, 1_000L))
    }

    private fun Rig.transferDone(request: TransferRequest) {
        transfers.value = mapOf(request.id to TransferState.Completed(request))
    }

    @Test
    fun `closing the last SFTP pane during a transfer keeps the connection until it ends`() = withRig {
        val tabId = manager.openSession(hostA)
        awaitConnected(tabId, emptyList())
        assertTrue(manager.splitPaneAsSftpAt(tabId, emptyList()))
        awaitSftpOpen(tabId, right)
        manager.closePaneAt(tabId, left)
        val upload = request("ssh-1")
        transferRunning(upload)

        manager.closePaneAt(tabId, emptyList())
        delay(300)
        coVerify(exactly = 0) { sftpManager.closeSftp(any()) }
        coVerify(exactly = 0) { ssh.disconnect(any()) }

        transferDone(upload)
        coVerify(timeout = WAIT_MS) { ssh.disconnect("ssh-1") }
        coVerifyOrder {
            sftpManager.closeSftp("ssh-1")
            ssh.disconnect("ssh-1")
        }
    }

    @Test
    fun `an exit during a transfer ends the pane and keeps the connection until the transfer ends`() = withRig {
        val tabId = manager.openSession(hostA)
        awaitConnected(tabId, emptyList())
        val upload = request("ssh-1")
        transferRunning(upload)

        mountShell("ssh-1").exit()
        awaitEnded(tabId, emptyList(), 0)
        delay(300)
        coVerify(exactly = 0) { ssh.disconnect(any()) }

        transferDone(upload)
        coVerify(timeout = WAIT_MS) { ssh.disconnect("ssh-1") }
    }

    @Test
    fun `a transfer on another connection holds nothing`() = withRig {
        val tabId = manager.openSession(hostA)
        awaitConnected(tabId, emptyList())
        transferRunning(request("ssh-9"))

        mountShell("ssh-1").exit()
        awaitEnded(tabId, emptyList(), 0)

        coVerify(timeout = WAIT_MS) { ssh.disconnect("ssh-1") }
    }

    @Test
    fun `a held connection that a new tab uses again is not closed when the transfer ends`() = withRig {
        val tabId = manager.openSession(hostA)
        awaitConnected(tabId, emptyList())
        val upload = request("ssh-1")
        transferRunning(upload)
        mountShell("ssh-1").exit()
        awaitEnded(tabId, emptyList(), 0)
        delay(300)

        assertTrue(manager.openSftpTab(hostA, "ssh-1") != null)
        transferDone(upload)
        delay(500)

        coVerify(exactly = 0) { ssh.disconnect(any()) }
    }

    @Test
    fun `an ended pane keeps its terminal until it is reconnected`() = withRig {
        val tabId = manager.openSession(hostA)
        awaitConnected(tabId, emptyList())
        mountShell("ssh-1").exit()
        awaitEnded(tabId, emptyList(), 0)
        // The connection is gone (disconnect ran): the render session must still be there.
        coVerify(timeout = WAIT_MS) { ssh.disconnect("ssh-1") }
        awaitRegistryRead()
        delay(200)
        val kept = manager.composeSessionFor("ssh-1")
        assertTrue(kept != null, "the ended terminal was released too early")
        assertSame(kept, manager.getOrCreateComposeSession("ssh-1", liveShellConnector(), palette))

        manager.reconnectAt(tabId, emptyList())
        awaitConnected(tabId, emptyList())

        assertEquals(null, manager.composeSessionFor("ssh-1"))
    }

    @Test
    fun `closing an ended pane releases its terminal`() = withRig {
        val tabId = manager.openSession(hostA)
        awaitConnected(tabId, emptyList())
        assertTrue(manager.splitPaneAt(tabId, emptyList(), hostB))
        awaitConnected(tabId, right)
        mountShell("ssh-1").exit()
        awaitEnded(tabId, left, 0)
        assertTrue(manager.composeSessionFor("ssh-1") != null)

        manager.closePaneAt(tabId, left)

        assertEquals(null, manager.composeSessionFor("ssh-1"))
    }

    @Test
    fun `closing a tab during a transfer keeps the connection until the transfer ends`() = withRig {
        val tabId = manager.openSession(hostA)
        awaitConnected(tabId, emptyList())
        val upload = request("ssh-1")
        transferRunning(upload)

        manager.closeSession(tabId)
        delay(300)
        coVerify(exactly = 0) { ssh.disconnect(any()) }

        transferDone(upload)
        coVerify(timeout = WAIT_MS) { ssh.disconnect("ssh-1") }
    }

    @Test
    fun `closing a terminal and SFTP split tab during a transfer keeps the connection until the transfer ends`() = withRig {
        val tabId = manager.openSession(hostA)
        awaitConnected(tabId, emptyList())
        assertTrue(manager.splitPaneAsSftpAt(tabId, emptyList()))
        awaitSftpOpen(tabId, right)
        val upload = request("ssh-1")
        transferRunning(upload)

        manager.closeSession(tabId)
        delay(300)
        coVerify(exactly = 0) { ssh.disconnect(any()) }

        transferDone(upload)
        coVerify(timeout = WAIT_MS) { ssh.disconnect("ssh-1") }
    }
}
