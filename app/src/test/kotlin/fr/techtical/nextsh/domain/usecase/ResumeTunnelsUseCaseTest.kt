// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.domain.usecase

import fr.techtical.nextsh.core.ssh.SshTunnelManager
import fr.techtical.nextsh.core.ssh.TUNNEL_AUTH_REQUIRED_MESSAGE
import fr.techtical.nextsh.domain.model.SshErrorCode
import fr.techtical.nextsh.domain.model.TunnelConfig
import fr.techtical.nextsh.domain.model.TunnelStatus
import fr.techtical.nextsh.domain.model.TunnelType
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.testutil.RecordingTree
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import timber.log.Timber

/**
 * The restart of the tunnels that waited for the user (see [ReconnectTunnelsUseCase]), asked for by
 * the notification once the app is on screen and the vault open. Against the real
 * [SshTunnelManager] state machine and the real [AutoStartTunnelsUseCase], with the database lookup
 * and the shared start mocked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResumeTunnelsUseCaseTest {

    private lateinit var startTunnel: StartTunnelUseCase
    private lateinit var liveTunnels: LiveTunnelLookup
    private lateinit var manager: SshTunnelManager
    private lateinit var useCase: ResumeTunnelsUseCase

    @BeforeEach
    fun setUp() {
        startTunnel = mockk()
        liveTunnels = mockk()
        manager = SshTunnelManager(sessionManager = mockk(relaxed = true))
        useCase = ResumeTunnelsUseCase(
            tunnelManager = manager,
            liveTunnels = liveTunnels,
            starter = AutoStartTunnelsUseCase(tunnelRepository = mockk(), startTunnel, liveTunnels, manager),
        )
    }

    private fun tunnel(id: String, localPort: Int = 8080, hostId: String = "h1") = TunnelConfig(
        id = id,
        label = "tunnel-$id",
        hostId = hostId,
        type = TunnelType.LOCAL_FORWARD,
        localPort = localPort,
        remoteHost = "127.0.0.1",
        remotePort = 80,
    )

    /** What the gate of the reconnection leaves behind: the tunnel in ERROR, held out of the reconnection. */
    private fun waitingForUser(t: TunnelConfig) {
        manager.markStarting(t)
        manager.injectActiveForTest(t, sessionId = "s-${t.id}")
        manager.simulateUnexpectedStopForTest(t.id, "Connexion SSH perdue")
        manager.holdReconnect(t.id, TUNNEL_AUTH_REQUIRED_MESSAGE)
    }

    /** What StartTunnelUseCase leaves behind on a failed connection: the tunnel in ERROR with the message. */
    private fun failedStart(t: TunnelConfig, code: SshErrorCode, message: String): SshResult.Error {
        manager.markStarting(t)
        manager.markError(t, message)
        return SshResult.Error(code, message)
    }

    private fun live(vararg tunnels: TunnelConfig) {
        tunnels.forEach { coEvery { liveTunnels.find(it.id) } returns it }
    }

    // ── What is restarted ─────────────────────────────────────────────────────

    @Test
    fun `the tunnels that waited for the user are started, held as they were`() = runTest {
        val t = tunnel("t1")
        waitingForUser(t)
        assertTrue(manager.getTunnelsForReconnect().isEmpty(), "held before the resume")
        live(t)
        coEvery { startTunnel(t) } returns SshResult.Success(Unit)

        val results = useCase(listOf("t1"))

        assertEquals(listOf("t1" to SshResult.Success(Unit)), results)
        coVerify(exactly = 1) { startTunnel(t) }
    }

    @Test
    fun `a start lifts the hold, the tunnel is an ordinary candidate again when it drops`() = runTest {
        val t = tunnel("t1")
        waitingForUser(t)
        live(t)
        // What the shared start does first: marks the tunnel STARTING, which lifts the hold.
        coEvery { startTunnel(t) } answers {
            manager.markStarting(t)
            manager.injectActiveForTest(t, sessionId = "s2")
            SshResult.Success(Unit)
        }

        useCase(listOf("t1"))
        manager.simulateUnexpectedStopForTest("t1", "Connexion SSH perdue")

        assertEquals(TunnelStatus.ERROR, manager.getTunnelState("t1")?.status)
        assertEquals(listOf(t), manager.getTunnelsForReconnect(), "no hold left over from the first drop")
    }

    @Test
    fun `the tunnels are started one after the other, in the order they were reported`() = runTest {
        val a = tunnel("a", localPort = 8001)
        val b = tunnel("b", localPort = 8002, hostId = "h2")
        val c = tunnel("c", localPort = 8003, hostId = "h3")
        listOf(a, b, c).forEach { waitingForUser(it) }
        live(a, b, c)
        var inFlight = 0
        var overlapped = false
        val order = mutableListOf<String>()
        listOf(a, b, c).forEach { t ->
            coEvery { startTunnel(t) } coAnswers {
                inFlight++
                if (inFlight > 1) overlapped = true
                order += t.id
                delay(50)
                inFlight--
                SshResult.Success(Unit)
            }
        }

        val results = useCase(listOf("a", "b", "c"))

        assertEquals(listOf("a", "b", "c"), order)
        assertFalse(overlapped, "two prompts must never be asked together")
        assertEquals(listOf("a", "b", "c"), results.map { it.first })
        coVerifyOrder { startTunnel(a); startTunnel(b); startTunnel(c) }
    }

    @Test
    fun `a tunnel of a host that opted out of the automatic reconnection is resumed all the same`() = runTest {
        // The option governs the automatic reconnection only: a start the user asks for, here
        // through the notification, is not a reconnection. Through the real lookup, over the
        // repositories, so that the host row with the option off is the one that is read.
        val t = tunnel("t1")
        waitingForUser(t)
        val tunnels = mockk<fr.techtical.nextsh.domain.repository.TunnelRepository>()
        val hosts = mockk<fr.techtical.nextsh.domain.repository.HostRepository>()
        coEvery { tunnels.getById("t1") } returns t
        coEvery { hosts.getById("h1") } returns fr.techtical.nextsh.domain.model.Host(
            id = "h1",
            label = "Prod",
            hostname = "prod.example.org",
            username = "root",
            authType = fr.techtical.nextsh.domain.model.AuthType.PASSWORD,
            credentialId = "c1",
            autoReconnect = false,
        )
        val realLookup = LiveTunnelLookup(tunnels, hosts)
        val resume = ResumeTunnelsUseCase(
            tunnelManager = manager,
            liveTunnels = realLookup,
            starter = AutoStartTunnelsUseCase(tunnelRepository = mockk(), startTunnel, realLookup, manager),
        )
        coEvery { startTunnel(t) } returns SshResult.Success(Unit)

        val results = resume(listOf("t1"))

        assertEquals(listOf("t1" to SshResult.Success(Unit)), results)
        coVerify(exactly = 1) { startTunnel(t) }
    }

    @Test
    fun `a tunnel the user restarted by hand meanwhile runs and is left alone`() = runTest {
        val running = tunnel("running", localPort = 8001)
        val waiting = tunnel("waiting", localPort = 8002)
        waitingForUser(running)
        waitingForUser(waiting)
        // The user restarts the first one before tapping the notification.
        manager.markStarting(running)
        manager.injectActiveForTest(running, sessionId = "s2")
        live(running, waiting)
        coEvery { startTunnel(waiting) } returns SshResult.Success(Unit)

        val results = useCase(listOf("running", "waiting"))

        assertEquals(listOf("waiting" to SshResult.Success(Unit)), results)
        coVerify(exactly = 0) { startTunnel(running) }
        assertEquals(TunnelStatus.ACTIVE, manager.getTunnelState("running")?.status, "left as it was")
    }

    @Test
    fun `a tunnel that starts or reconnects is not started a second time`() = runTest {
        val starting = tunnel("starting", localPort = 8001)
        val reconnecting = tunnel("reconnecting", localPort = 8002)
        manager.markStarting(starting)
        manager.markStarting(reconnecting)
        manager.updateTunnelStatus("reconnecting", TunnelStatus.RECONNECTING)

        val results = useCase(listOf("starting", "reconnecting"))

        assertTrue(results.isEmpty())
        coVerify(exactly = 0) { startTunnel(any()) }
    }

    @Test
    fun `a tunnel stopped by hand, or that the manager does not know, does not come back`() = runTest {
        // A stop removes the state: the notification may be tapped long after.
        val results = useCase(listOf("stopped", "never-known"))

        assertTrue(results.isEmpty())
        coVerify(exactly = 0) { startTunnel(any()) }
        coVerify(exactly = 0) { liveTunnels.find(any()) }
    }

    @Test
    fun `a tunnel deleted elsewhere meanwhile is skipped, with no result`() = runTest {
        val gone = tunnel("gone", localPort = 8001)
        val kept = tunnel("kept", localPort = 8002)
        waitingForUser(gone)
        waitingForUser(kept)
        coEvery { liveTunnels.find("gone") } returns null
        live(kept)
        coEvery { startTunnel(kept) } returns SshResult.Success(Unit)

        val results = useCase(listOf("gone", "kept"))

        assertEquals(listOf("kept" to SshResult.Success(Unit)), results)
        coVerify(exactly = 0) { startTunnel(gone) }
    }

    @Test
    fun `the stored row is started, not an older copy of it`() = runTest {
        val frozen = tunnel("t1", localPort = 8080)
        val edited = tunnel("t1", localPort = 9090)
        waitingForUser(frozen)
        coEvery { liveTunnels.find("t1") } returns edited
        coEvery { startTunnel(edited) } returns SshResult.Success(Unit)

        useCase(listOf("t1"))

        coVerify(exactly = 1) { startTunnel(edited) }
    }

    @Test
    fun `a tunnel named twice is started once`() = runTest {
        val t = tunnel("t1")
        waitingForUser(t)
        live(t)
        coEvery { startTunnel(t) } returns SshResult.Success(Unit)

        val results = useCase(listOf("t1", "t1"))

        assertEquals(1, results.size)
        coVerify(exactly = 1) { startTunnel(t) }
    }

    @Test
    fun `nothing to resume, nothing is asked`() = runTest {
        assertTrue(useCase(emptyList()).isEmpty())
        coVerify(exactly = 0) { startTunnel(any()) }
    }

    // ── One answer per host ───────────────────────────────────────────────────

    @Test
    fun `a key sheet cancelled for one tunnel is the answer of the other tunnels of its host`() = runTest {
        val first = tunnel("first", localPort = 8001, hostId = "h1")
        val second = tunnel("second", localPort = 8002, hostId = "h1")
        val other = tunnel("other", localPort = 8003, hostId = "h2")
        listOf(first, second, other).forEach { waitingForUser(it) }
        live(first, second, other)
        coEvery { startTunnel(first) } answers {
            failedStart(first, SshErrorCode.FIDO2_USER_CANCELLED, "Connexion annulée")
        }
        coEvery { startTunnel(other) } returns SshResult.Success(Unit)

        val results = useCase(listOf("first", "second", "other")).toMap()

        coVerify(exactly = 0) { startTunnel(second) }
        val cancelled = SshResult.Error(SshErrorCode.FIDO2_USER_CANCELLED, "Connexion annulée")
        assertEquals(cancelled, results["first"])
        assertEquals(cancelled, results["second"], "the same answer, no second prompt")
        assertEquals(SshResult.Success(Unit), results["other"], "another host is not concerned")
        assertEquals(TunnelStatus.ERROR, manager.getTunnelState("second")?.status)
        assertEquals("Connexion annulée", manager.getTunnelState("second")?.errorMessage)
        assertTrue(manager.getTunnelsForReconnect().isEmpty(), "held, no network event retries them")
    }

    @Test
    fun `a failure of the network is the tunnel's own, the next tunnel still tries`() = runTest {
        val first = tunnel("first", localPort = 8001)
        val second = tunnel("second", localPort = 8002)
        waitingForUser(first)
        waitingForUser(second)
        live(first, second)
        coEvery { startTunnel(first) } answers { failedStart(first, SshErrorCode.HOST_UNREACHABLE, "unreachable") }
        coEvery { startTunnel(second) } returns SshResult.Success(Unit)

        val results = useCase(listOf("first", "second")).toMap()

        coVerify(exactly = 1) { startTunnel(second) }
        assertEquals(SshResult.Success(Unit), results["second"])
    }

    // ── Not once per process ──────────────────────────────────────────────────

    @Test
    fun `every tap restarts the tunnels it reported, there is no once per process gate`() = runTest {
        val t = tunnel("t1")
        live(t)
        coEvery { startTunnel(t) } returns SshResult.Success(Unit)

        repeat(2) {
            waitingForUser(t)
            useCase(listOf("t1"))
        }

        coVerify(exactly = 2) { startTunnel(t) }
    }

    // ── Logs ──────────────────────────────────────────────────────────────────

    @Test
    fun `the resume logs counts only, never an id or a label`() = runTest {
        val t = tunnel("id-secret", localPort = 8080)
        waitingForUser(t)
        live(t)
        coEvery { startTunnel(t) } returns SshResult.Error(SshErrorCode.PORT_IN_USE, "Port 8080 déjà utilisé localement")
        val tree = RecordingTree()
        Timber.plant(tree)
        try {
            useCase(listOf("id-secret"))
        } finally {
            Timber.uproot(tree)
        }

        val logged = tree.entries.joinToString("\n") { it.message }
        for (secret in listOf("id-secret", "tunnel-id-secret", "8080", "localement")) {
            assertFalse(logged.contains(secret), "'$secret' ne doit pas etre dans le journal : $logged")
        }
    }
}
