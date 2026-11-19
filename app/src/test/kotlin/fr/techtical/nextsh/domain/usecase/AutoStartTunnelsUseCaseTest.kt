// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.domain.usecase

import fr.techtical.nextsh.core.ssh.SshTunnelManager
import fr.techtical.nextsh.core.ssh.TUNNEL_LOCAL_PORT_INVALID_MESSAGE
import fr.techtical.nextsh.core.ssh.TUNNEL_LOCAL_PORT_RESERVED_MESSAGE
import fr.techtical.nextsh.core.ssh.TUNNEL_START_CANCELLED_MESSAGE
import fr.techtical.nextsh.core.ssh.TUNNEL_START_STOPPED_MESSAGE
import fr.techtical.nextsh.domain.model.SshErrorCode
import fr.techtical.nextsh.domain.model.TunnelConfig
import fr.techtical.nextsh.domain.model.TunnelStatus
import fr.techtical.nextsh.domain.model.TunnelType
import fr.techtical.nextsh.domain.repository.TunnelRepository
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.shared.domain.vault.VaultAuthExpiredException
import fr.techtical.nextsh.testutil.RecordingTree
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import timber.log.Timber

@OptIn(ExperimentalCoroutinesApi::class)
class AutoStartTunnelsUseCaseTest {

    private lateinit var tunnelRepository: TunnelRepository
    private lateinit var startTunnel: StartTunnelUseCase
    private lateinit var liveTunnels: LiveTunnelLookup
    private lateinit var tunnelManager: SshTunnelManager
    private lateinit var useCase: AutoStartTunnelsUseCase

    @BeforeEach
    fun setUp() {
        tunnelRepository = mockk()
        startTunnel = mockk()
        liveTunnels = mockk()
        tunnelManager = SshTunnelManager(sessionManager = mockk(relaxed = true))
        useCase = AutoStartTunnelsUseCase(tunnelRepository, startTunnel, liveTunnels, tunnelManager)
    }

    private fun tunnel(id: String, localPort: Int = 8080, hostId: String = "h1") = TunnelConfig(
        id = id,
        label = "tunnel-$id",
        hostId = hostId,
        type = TunnelType.LOCAL_FORWARD,
        localPort = localPort,
        remoteHost = "127.0.0.1",
        remotePort = 80,
        autoStart = true,
    )

    @Test
    fun `no autostart tunnel, nothing is started`() = runTest {
        coEvery { tunnelRepository.getAutoStartTunnels() } returns emptyList()

        assertTrue(useCase().isEmpty())
        coVerify(exactly = 0) { startTunnel(any()) }
    }

    @Test
    fun `startTunnels starts the tunnels it is given and never asks the repository for the autostart ones`() = runTest {
        // The repository is a strict mock with nothing stubbed: asking it anything would throw.
        val a = tunnel("a", localPort = 8001)
        val b = tunnel("b", localPort = 8002)
        coEvery { liveTunnels.find("a") } returns a
        coEvery { liveTunnels.find("b") } returns b
        coEvery { startTunnel(a) } returns SshResult.Success(Unit)
        coEvery { startTunnel(b) } returns SshResult.Success(Unit)

        val results = useCase.startTunnels(listOf(a, b))

        assertEquals(listOf("a" to SshResult.Success(Unit), "b" to SshResult.Success(Unit)), results)
        assertTrue(useCase.startTunnels(emptyList()).isEmpty())
    }

    @Test
    fun `a tunnel whose row or host went away since the query is skipped without a result`() = runTest {
        val gone = tunnel("gone")
        val live = tunnel("live")
        coEvery { tunnelRepository.getAutoStartTunnels() } returns listOf(gone, live)
        coEvery { liveTunnels.find("gone") } returns null
        coEvery { liveTunnels.find("live") } returns live
        coEvery { startTunnel(live) } returns SshResult.Success(Unit)

        val results = useCase()

        assertEquals(listOf("live" to SshResult.Success(Unit)), results)
        coVerify(exactly = 0) { startTunnel(gone) }
    }

    @Test
    fun `the stored row is started, not the copy read by the query`() = runTest {
        val listed = tunnel("t1", localPort = 8080)
        val stored = tunnel("t1", localPort = 9090)
        coEvery { tunnelRepository.getAutoStartTunnels() } returns listOf(listed)
        coEvery { liveTunnels.find("t1") } returns stored
        coEvery { startTunnel(stored) } returns SshResult.Success(Unit)

        useCase()

        coVerify(exactly = 1) { startTunnel(stored) }
        coVerify(exactly = 0) { startTunnel(listed) }
    }

    @Test
    fun `a failed autostart logs the error code, never its message, the label or the id`() = runTest {
        val t = tunnel("id-secret", localPort = 8080)
        coEvery { tunnelRepository.getAutoStartTunnels() } returns listOf(t)
        coEvery { liveTunnels.find("id-secret") } returns t
        coEvery { startTunnel(t) } returns
            SshResult.Error(SshErrorCode.PORT_IN_USE, "Port 8080 déjà utilisé localement")
        val tree = RecordingTree()
        Timber.plant(tree)
        try {
            useCase()
        } finally {
            Timber.uproot(tree)
        }

        val logged = tree.entries.joinToString("\n") { it.message }
        assertTrue(logged.contains("PORT_IN_USE"), logged)
        for (secret in listOf("8080", "tunnel-id-secret", "id-secret", "localement")) {
            assertTrue(!logged.contains(secret), "'$secret' ne doit pas etre dans le journal : $logged")
        }
    }

    @Test
    fun `a start that throws before it announces the tunnel leaves no start on its way, the card can be cleared`() = runTest {
        // A tunnel in error, taken by the auto-start; the shared start fails reading the host.
        val t = tunnel("t1")
        tunnelManager.markStarting(t)
        tunnelManager.markError(t, "Connexion SSH perdue")
        val row = t.copy()
        coEvery { liveTunnels.find("t1") } returns row
        coEvery { startTunnel(row) } throws IllegalStateException("database is locked")

        val failure = runCatching { useCase.startTunnels(listOf(t)) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException, "$failure")
        assertEquals(TunnelStatus.ERROR, tunnelManager.getTunnelState("t1")?.status)
        assertTrue(tunnelManager.dismissError("t1"), "the card in error can be cleared")
        assertTrue(!tunnelManager.isStartAbandoned(row), "it had been given back, not invalidated by the dismissal")
    }

    // ── A cancelled or refused authentication is not retried by the network events ──

    /** What StartTunnelUseCase leaves behind on a failed connection: the tunnel in ERROR with the message. */
    private fun failedStart(t: TunnelConfig, code: SshErrorCode, message: String): SshResult.Error {
        tunnelManager.markStarting(t)
        tunnelManager.markError(t, message)
        return SshResult.Error(code, message)
    }

    @Test
    fun `an autostart cancelled at the security key prompt is not a reconnection candidate`() = runTest {
        val t = tunnel("t1")
        coEvery { tunnelRepository.getAutoStartTunnels() } returns listOf(t)
        coEvery { liveTunnels.find("t1") } returns t
        coEvery { startTunnel(t) } answers {
            failedStart(t, SshErrorCode.FIDO2_USER_CANCELLED, "Connexion annulée")
        }

        useCase()

        assertTrue(tunnelManager.getTunnelsForReconnect().isEmpty(), "no prompt at the next network event")
        assertEquals(TunnelStatus.ERROR, tunnelManager.getTunnelState("t1")?.status)
        assertEquals("Connexion annulée", tunnelManager.getTunnelState("t1")?.errorMessage)
    }

    @Test
    fun `an autostart refused on a host key that no longer matches is not a reconnection candidate`() = runTest {
        val t = tunnel("t1")
        val message = "Vérification de la clé hôte échouée : the key of the server changed"
        coEvery { tunnelRepository.getAutoStartTunnels() } returns listOf(t)
        coEvery { liveTunnels.find("t1") } returns t
        coEvery { startTunnel(t) } answers { failedStart(t, SshErrorCode.HOST_KEY_MISMATCH, message) }

        val results = useCase()

        assertEquals(SshErrorCode.HOST_KEY_MISMATCH, (results.single().second as SshResult.Error).code)
        assertTrue(tunnelManager.getTunnelsForReconnect().isEmpty(), "a security event is not replayed at the next network event")
        assertEquals(TunnelStatus.ERROR, tunnelManager.getTunnelState("t1")?.status)
        assertEquals(message, tunnelManager.getTunnelState("t1")?.errorMessage, "the existing message is kept")
    }

    @Test
    fun `an autostart that failed on the network stays a reconnection candidate`() = runTest {
        val t = tunnel("t1")
        coEvery { tunnelRepository.getAutoStartTunnels() } returns listOf(t)
        coEvery { liveTunnels.find("t1") } returns t
        coEvery { startTunnel(t) } answers { failedStart(t, SshErrorCode.HOST_UNREACHABLE, "unreachable") }

        useCase()

        assertEquals(listOf("t1"), tunnelManager.getTunnelsForReconnect().map { it.id })
    }

    // ── A tunnel that already runs is left alone ──

    @Test
    fun `an active, starting or reconnecting tunnel is skipped, without a start and without a result`() = runTest {
        val active = tunnel("active", localPort = 8001)
        val starting = tunnel("starting", localPort = 8002)
        val reconnecting = tunnel("reconnecting", localPort = 8003)
        val idle = tunnel("idle", localPort = 8004)
        tunnelManager.markStarting(active)
        tunnelManager.updateTunnelStatus("active", TunnelStatus.ACTIVE)
        tunnelManager.markStarting(starting)
        tunnelManager.markStarting(reconnecting)
        tunnelManager.updateTunnelStatus("reconnecting", TunnelStatus.RECONNECTING)
        val all = listOf(active, starting, reconnecting, idle)
        coEvery { tunnelRepository.getAutoStartTunnels() } returns all
        all.forEach { coEvery { liveTunnels.find(it.id) } returns it }
        coEvery { startTunnel(idle) } returns SshResult.Success(Unit)

        val results = useCase()

        assertEquals(listOf("idle" to SshResult.Success(Unit)), results)
        coVerify(exactly = 1) { startTunnel(any()) }
        coVerify(exactly = 1) { startTunnel(idle) }
        assertEquals(TunnelStatus.ACTIVE, tunnelManager.getTunnelState("active")?.status, "left as it was")
        assertEquals(TunnelStatus.STARTING, tunnelManager.getTunnelState("starting")?.status)
        assertEquals(TunnelStatus.RECONNECTING, tunnelManager.getTunnelState("reconnecting")?.status)
    }

    @Test
    fun `a tunnel in error or stopped is started`() = runTest {
        val failed = tunnel("failed", localPort = 8001)
        val fresh = tunnel("fresh", localPort = 8002)
        tunnelManager.markStarting(failed)
        tunnelManager.markError(failed, "unreachable")
        coEvery { tunnelRepository.getAutoStartTunnels() } returns listOf(failed, fresh)
        coEvery { liveTunnels.find("failed") } returns failed
        coEvery { liveTunnels.find("fresh") } returns fresh
        coEvery { startTunnel(any()) } returns SshResult.Success(Unit)

        val results = useCase()

        assertEquals(listOf("failed", "fresh"), results.map { it.first })
        coVerify(exactly = 1) { startTunnel(failed) }
        coVerify(exactly = 1) { startTunnel(fresh) }
    }

    // ── A non-retryable failure is the host's: its other tunnels do not ask again ──

    @Test
    fun `after a cancelled key prompt the other tunnels of that host are not attempted and carry the same error`() = runTest {
        val a = tunnel("a", localPort = 8001, hostId = "h1")
        val b = tunnel("b", localPort = 8002, hostId = "h1")
        val c = tunnel("c", localPort = 8003, hostId = "h2")
        val d = tunnel("d", localPort = 8004, hostId = "h1")
        val all = listOf(a, b, c, d)
        coEvery { tunnelRepository.getAutoStartTunnels() } returns all
        all.forEach { coEvery { liveTunnels.find(it.id) } returns it }
        coEvery { startTunnel(a) } answers {
            failedStart(a, SshErrorCode.FIDO2_USER_CANCELLED, "Connexion annulée")
        }
        coEvery { startTunnel(c) } returns SshResult.Success(Unit)

        val results = useCase().toMap()

        val cancelled = SshResult.Error(SshErrorCode.FIDO2_USER_CANCELLED, "Connexion annulée")
        assertEquals(listOf("a", "b", "c", "d"), results.keys.toList())
        assertEquals(cancelled, results.getValue("a"))
        assertEquals(cancelled, results.getValue("b"), "same error result")
        assertEquals(SshResult.Success(Unit), results.getValue("c"), "another host still starts")
        assertEquals(cancelled, results.getValue("d"))
        coVerify(exactly = 1) { startTunnel(a) }
        coVerify(exactly = 1) { startTunnel(c) }
        coVerify(exactly = 0) { startTunnel(b) }
        coVerify(exactly = 0) { startTunnel(d) }
        assertTrue(tunnelManager.getTunnelsForReconnect().isEmpty(), "none of the three comes back at the next network event")
    }

    @Test
    fun `a tunnel not attempted for its host is in error with the host message and held out of the reconnection`() = runTest {
        val a = tunnel("a", localPort = 8001)
        val b = tunnel("b", localPort = 8002)
        coEvery { tunnelRepository.getAutoStartTunnels() } returns listOf(a, b)
        coEvery { liveTunnels.find("a") } returns a
        coEvery { liveTunnels.find("b") } returns b
        coEvery { startTunnel(a) } answers {
            failedStart(a, SshErrorCode.FIDO2_TIMEOUT, "Délai dépassé pour la clé de sécurité")
        }

        useCase()

        coVerify(exactly = 0) { startTunnel(b) }
        assertEquals(TunnelStatus.ERROR, tunnelManager.getTunnelState("b")?.status)
        assertEquals("Délai dépassé pour la clé de sécurité", tunnelManager.getTunnelState("b")?.errorMessage)
        assertTrue(tunnelManager.getTunnelsForReconnect().isEmpty(), "neither tunnel comes back at the next network event")
    }

    @Test
    fun `a host key mismatch is the host's too`() = runTest {
        val a = tunnel("a", localPort = 8001)
        val b = tunnel("b", localPort = 8002)
        coEvery { tunnelRepository.getAutoStartTunnels() } returns listOf(a, b)
        coEvery { liveTunnels.find("a") } returns a
        coEvery { liveTunnels.find("b") } returns b
        coEvery { startTunnel(a) } answers { failedStart(a, SshErrorCode.HOST_KEY_MISMATCH, "key changed") }

        val results = useCase().toMap()

        coVerify(exactly = 0) { startTunnel(b) }
        assertEquals(SshErrorCode.HOST_KEY_MISMATCH, (results.getValue("b") as SshResult.Error).code)
        assertTrue(tunnelManager.getTunnelsForReconnect().isEmpty())
    }

    @Test
    fun `a running tunnel of the refused host is left alone, not marked in error`() = runTest {
        val a = tunnel("a", localPort = 8001)
        val running = tunnel("running", localPort = 8002)
        tunnelManager.markStarting(running)
        tunnelManager.updateTunnelStatus("running", TunnelStatus.ACTIVE)
        coEvery { tunnelRepository.getAutoStartTunnels() } returns listOf(a, running)
        coEvery { liveTunnels.find("a") } returns a
        coEvery { liveTunnels.find("running") } returns running
        coEvery { startTunnel(a) } answers { failedStart(a, SshErrorCode.AUTH_FAILED, "refused") }

        val results = useCase()

        assertEquals(listOf("a"), results.map { it.first })
        assertEquals(TunnelStatus.ACTIVE, tunnelManager.getTunnelState("running")?.status)
    }

    @Test
    fun `a tunnel not attempted for its host is logged by the code only`() = runTest {
        val a = tunnel("id-a", localPort = 8001)
        val b = tunnel("id-b", localPort = 8002)
        coEvery { tunnelRepository.getAutoStartTunnels() } returns listOf(a, b)
        coEvery { liveTunnels.find("id-a") } returns a
        coEvery { liveTunnels.find("id-b") } returns b
        coEvery { startTunnel(a) } answers { failedStart(a, SshErrorCode.AUTH_FAILED, "refused for admin@secret-host") }
        val tree = RecordingTree()
        Timber.plant(tree)
        try {
            useCase()
        } finally {
            Timber.uproot(tree)
        }

        val logged = tree.entries.joinToString("\n") { it.message }
        assertTrue(logged.contains("AUTH_FAILED"), logged)
        for (secret in listOf("secret-host", "admin", "tunnel-id-b", "id-b", "8002")) {
            assertTrue(!logged.contains(secret), "'$secret' ne doit pas etre dans le journal : $logged")
        }
    }

    @Test
    fun `after a network failure the next tunnel of the same host is still attempted`() = runTest {
        val a = tunnel("a", localPort = 8001)
        val b = tunnel("b", localPort = 8002)
        coEvery { tunnelRepository.getAutoStartTunnels() } returns listOf(a, b)
        coEvery { liveTunnels.find("a") } returns a
        coEvery { liveTunnels.find("b") } returns b
        coEvery { startTunnel(a) } answers { failedStart(a, SshErrorCode.HOST_UNREACHABLE, "unreachable") }
        coEvery { startTunnel(b) } returns SshResult.Success(Unit)

        val results = useCase().toMap()

        coVerify(exactly = 1) { startTunnel(b) }
        assertEquals(SshErrorCode.HOST_UNREACHABLE, (results.getValue("a") as SshResult.Error).code)
        assertEquals(SshResult.Success(Unit), results.getValue("b"))
        assertEquals(listOf("a"), tunnelManager.getTunnelsForReconnect().map { it.id }, "the failed one stays a candidate")
    }

    @Test
    fun `after an expired authentication window the next tunnel of the same host is still attempted`() = runTest {
        val a = tunnel("a", localPort = 8001)
        val b = tunnel("b", localPort = 8002)
        coEvery { tunnelRepository.getAutoStartTunnels() } returns listOf(a, b)
        coEvery { liveTunnels.find("a") } returns a
        coEvery { liveTunnels.find("b") } returns b
        coEvery { startTunnel(a) } returns SshResult.Error(SshErrorCode.AUTH_EXPIRED, "expired")
        coEvery { startTunnel(b) } returns SshResult.Success(Unit)

        val results = useCase().toMap()

        coVerify(exactly = 1) { startTunnel(b) }
        assertEquals(SshResult.Success(Unit), results.getValue("b"))
    }

    @Test
    fun `after a timeout the next tunnel of the same host is still attempted`() = runTest {
        val a = tunnel("a", localPort = 8001)
        val b = tunnel("b", localPort = 8002)
        coEvery { tunnelRepository.getAutoStartTunnels() } returns listOf(a, b)
        coEvery { liveTunnels.find("a") } returns a
        coEvery { liveTunnels.find("b") } returns b
        coEvery { startTunnel(a) } answers { failedStart(a, SshErrorCode.TIMEOUT, "timeout") }
        coEvery { startTunnel(b) } returns SshResult.Success(Unit)

        useCase()

        coVerify(exactly = 1) { startTunnel(b) }
    }

    // ── A start cancelled on the way does not leave the card starting for ever ──

    @Test
    fun `a start cancelled on the way leaves the tunnel in error, held, and the cancellation goes on`() = runTest {
        val t = tunnel("t1")
        coEvery { tunnelRepository.getAutoStartTunnels() } returns listOf(t)
        coEvery { liveTunnels.find("t1") } returns t
        // The shared use case marks the tunnel STARTING, then the connection is under way.
        coEvery { startTunnel(t) } coAnswers {
            tunnelManager.markStarting(t)
            awaitCancellation()
        }

        val job = launch { useCase() }
        runCurrent()
        assertEquals(TunnelStatus.STARTING, tunnelManager.getTunnelState("t1")?.status)
        job.cancel()
        job.join()

        assertTrue(job.isCancelled, "the cancellation is not swallowed")
        assertEquals(TunnelStatus.ERROR, tunnelManager.getTunnelState("t1")?.status)
        assertEquals(TUNNEL_START_CANCELLED_MESSAGE, tunnelManager.getTunnelState("t1")?.errorMessage)
        assertTrue(tunnelManager.getTunnelsForReconnect().isEmpty(), "an abandoned start is not retried by itself")
    }

    @Test
    fun `a cancelled start does not touch a start the user made on the same tunnel meanwhile`() = runTest {
        val t = tunnel("t1")
        coEvery { tunnelRepository.getAutoStartTunnels() } returns listOf(t)
        coEvery { liveTunnels.find("t1") } returns t
        // The cancelled start is still on when the user stops the card and starts it again, which
        // marks the tunnel STARTING with a row of its own.
        coEvery { startTunnel(t) } coAnswers {
            tunnelManager.markStarting(t.copy())
            awaitCancellation()
        }

        val job = launch { useCase() }
        runCurrent()
        job.cancel()
        job.join()

        assertEquals(TunnelStatus.STARTING, tunnelManager.getTunnelState("t1")?.status, "the user's start goes on")
        assertNull(tunnelManager.getTunnelState("t1")?.errorMessage)
    }

    @Test
    fun `an expired vault authentication is reported as an error for that tunnel only`() = runTest {
        val a = tunnel("a")
        val b = tunnel("b")
        coEvery { tunnelRepository.getAutoStartTunnels() } returns listOf(a, b)
        coEvery { liveTunnels.find("a") } returns a
        coEvery { liveTunnels.find("b") } returns b
        // The shared start marks the tunnel STARTING before it asks for a session.
        coEvery { startTunnel(a) } answers {
            tunnelManager.markStarting(a)
            throw VaultAuthExpiredException()
        }
        coEvery { startTunnel(b) } returns SshResult.Success(Unit)

        val results = useCase().toMap()

        assertEquals(SshErrorCode.AUTH_EXPIRED, (results.getValue("a") as SshResult.Error).code)
        assertEquals(SshResult.Success(Unit), results.getValue("b"))
        // The card of the tunnel does not stay in STARTING.
        assertEquals(TunnelStatus.ERROR, tunnelManager.getTunnelState("a")?.status)
        assertEquals("Authentification vault expirée", tunnelManager.getTunnelState("a")?.errorMessage)
    }

    // ── A stop by hand during a start, and a port that cannot be used ──

    @Test
    fun `a tunnel stopped while its auto-start connects has no result, no error and no hold on its host`() = runTest {
        val a = tunnel("a", localPort = 8001)
        val b = tunnel("b", localPort = 8002)
        coEvery { liveTunnels.find("a") } returns a
        coEvery { liveTunnels.find("b") } returns b
        coEvery { startTunnel(a) } coAnswers {
            tunnelManager.markStarting(a)
            // The user presses Stop on the DEMARRAGE card; the connection, which goes on, then fails
            // on a non-retryable answer.
            runBlocking { tunnelManager.stopTunnel("a") }
            tunnelManager.markError(a, "Connexion annulée")
            SshResult.Error(SshErrorCode.FIDO2_USER_CANCELLED, "Connexion annulée")
        }
        coEvery { startTunnel(b) } returns SshResult.Success(Unit)

        val results = useCase.startTunnels(listOf(a, b))

        // Nothing to tell for a: the result is the one of b alone, and a failure that the user caused
        // by closing the card is not the host's, so b was tried.
        assertEquals(listOf("b" to SshResult.Success(Unit)), results)
        coVerify(exactly = 1) { startTunnel(b) }
        assertNull(tunnelManager.getTunnelState("a"), "the card stays closed")
        assertTrue(tunnelManager.getTunnelsForReconnect().isEmpty())
    }

    @Test
    fun `a stop by hand that lands while the shared start reads the host leaves no card and no start result`() = runTest {
        val t = tunnel("t1")
        coEvery { liveTunnels.find("t1") } returns t
        coEvery { startTunnel(t) } coAnswers {
            // Between the reservation of this start and the markStarting of the shared use case.
            runBlocking { tunnelManager.stopTunnel("t1") }
            tunnelManager.markStarting(t)
            SshResult.Error(SshErrorCode.UNKNOWN, TUNNEL_START_STOPPED_MESSAGE)
        }

        val results = useCase.startTunnels(listOf(t))

        assertTrue(results.isEmpty(), "$results")
        assertNull(tunnelManager.getTunnelState("t1"), "no resurrection")
        assertTrue(tunnelManager.getTunnelsForReconnect().isEmpty())
    }

    @Test
    fun `a tunnel with a port that cannot be used is refused before any connection, and held`() = runTest {
        val bad = tunnel("bad", localPort = 80)
        val outOfRange = tunnel("range", localPort = 70_000)
        val good = tunnel("good", localPort = 8002)
        for (t in listOf(bad, outOfRange, good)) coEvery { liveTunnels.find(t.id) } returns t
        coEvery { startTunnel(good) } returns SshResult.Success(Unit)

        val results = useCase.startTunnels(listOf(bad, outOfRange, good)).toMap()

        coVerify(exactly = 0) { startTunnel(bad) }
        coVerify(exactly = 0) { startTunnel(outOfRange) }
        coVerify(exactly = 1) { startTunnel(good) }
        assertEquals(TUNNEL_LOCAL_PORT_RESERVED_MESSAGE, (results.getValue("bad") as SshResult.Error).message)
        assertEquals(TUNNEL_LOCAL_PORT_INVALID_MESSAGE, (results.getValue("range") as SshResult.Error).message)
        assertEquals(SshResult.Success(Unit), results.getValue("good"), "the host is not refused: the next tunnel is tried")
        assertEquals(TunnelStatus.ERROR, tunnelManager.getTunnelState("bad")?.status)
        assertEquals(TUNNEL_LOCAL_PORT_RESERVED_MESSAGE, tunnelManager.getTunnelState("bad")?.errorMessage)
        assertTrue(tunnelManager.isReconnectHeld("bad"))
        assertTrue(tunnelManager.isReconnectHeld("range"))
        assertTrue(tunnelManager.getTunnelsForReconnect().isEmpty(), "a port does not mend by itself")
    }

    @Test
    fun `a port that cannot be used does not count as a failure of the host`() = runTest {
        val bad = tunnel("bad", localPort = 80, hostId = "h1")
        val sibling = tunnel("sibling", localPort = 8002, hostId = "h1")
        coEvery { liveTunnels.find("bad") } returns bad
        coEvery { liveTunnels.find("sibling") } returns sibling
        coEvery { startTunnel(sibling) } answers {
            failedStart(sibling, SshErrorCode.HOST_UNREACHABLE, "unreachable")
        }

        val results = useCase.startTunnels(listOf(bad, sibling)).toMap()

        coVerify(exactly = 1) { startTunnel(sibling) }
        assertEquals(SshErrorCode.HOST_UNREACHABLE, (results.getValue("sibling") as SshResult.Error).code)
    }

    @Test
    fun `starting a refused tunnel again lifts its hold`() = runTest {
        val bad = tunnel("bad", localPort = 80)
        coEvery { liveTunnels.find("bad") } returns bad
        useCase.startTunnels(listOf(bad))
        assertTrue(tunnelManager.isReconnectHeld("bad"))

        // The user edits the port and starts the tunnel: the shared start marks it STARTING.
        tunnelManager.markStarting(bad.copy(localPort = 8080))

        assertTrue(!tunnelManager.isReconnectHeld("bad"))
    }

    @Test
    fun `the result of a start that faded out has no result, even when its replacement has already finished`() = runTest {
        val a = tunnel("a", localPort = 8001)
        val b = tunnel("b", localPort = 8002)
        coEvery { liveTunnels.find("a") } returns a
        coEvery { liveTunnels.find("b") } returns b
        val byUser = a.copy()
        coEvery { startTunnel(a) } coAnswers {
            tunnelManager.markStarting(a)
            tunnelManager.markStarting(byUser)
            tunnelManager.injectActiveForTest(byUser, sessionId = "s2") // the more recent start has finished
            SshResult.Error(SshErrorCode.UNKNOWN, TUNNEL_START_STOPPED_MESSAGE)
        }
        coEvery { startTunnel(b) } returns SshResult.Success(Unit)

        val results = useCase.startTunnels(listOf(a, b))

        assertEquals(listOf("b" to SshResult.Success(Unit)), results)
        assertTrue(!tunnelManager.isStartAbandoned(a), "the premise: the manager does not know")
        assertEquals(TunnelStatus.ACTIVE, tunnelManager.getTunnelState("a")?.status)
    }
}
