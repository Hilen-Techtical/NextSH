// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.domain.usecase

import fr.techtical.nextsh.core.AppVisibility
import fr.techtical.nextsh.core.network.NetworkMonitor
import fr.techtical.nextsh.core.ssh.DeletedTunnelStopper
import fr.techtical.nextsh.core.ssh.LiveSessionCheck
import fr.techtical.nextsh.core.ssh.SshTunnelManager
import fr.techtical.nextsh.core.ssh.TUNNEL_AUTH_REQUIRED_MESSAGE
import fr.techtical.nextsh.domain.model.AuthType
import fr.techtical.nextsh.domain.model.Fido2Mode
import fr.techtical.nextsh.domain.model.Host
import fr.techtical.nextsh.domain.model.SshErrorCode
import fr.techtical.nextsh.domain.model.TunnelConfig
import fr.techtical.nextsh.domain.model.TunnelStatus
import fr.techtical.nextsh.domain.model.TunnelType
import fr.techtical.nextsh.service.AuthRequiredNotifier
import fr.techtical.nextsh.shared.domain.model.SshResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * A tunnel whose host needs the user in front of the phone (a security key to touch, a fingerprint
 * to present) is not reconnected while the app is not on screen: its prompt would open for nobody
 * and end as a timeout 60 s later. It is put in error with a clear message, held out of the
 * reconnection, and the user is told through a notification. Hosts that need nobody are never held,
 * and with the app on screen the attempt is made as always, prompt included.
 *
 * Against the real [SshTunnelManager] state machine, with the database lookup and the start use
 * case mocked. Time is virtual.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReconnectTunnelsUseCaseAuthGateTest {

    private lateinit var manager: SshTunnelManager
    private lateinit var startTunnel: StartTunnelUseCase
    private lateinit var liveTunnels: LiveTunnelLookup
    private lateinit var authNotifier: AuthRequiredNotifier
    private lateinit var useCase: ReconnectTunnelsUseCase

    /** Whether the app is on screen; the use case reads it through [AppVisibility]. */
    private var visible = false

    /** The hosts that already have a live session, as the session provider would tell. */
    private var hostsWithLiveSession = emptySet<String>()

    /** What the session lookup does, besides answering; a test hooks a suspension of the gate here. */
    private var onSessionLookup: () -> Unit = {}

    @BeforeEach
    fun setUp() {
        visible = false
        hostsWithLiveSession = emptySet()
        onSessionLookup = {}
        managerClockMs = 0L
        manager = SshTunnelManager(sessionManager = mockk(relaxed = true), clock = { managerClockMs })
        startTunnel = mockk()
        liveTunnels = mockk()
        authNotifier = mockk(relaxed = true)
        useCase = ReconnectTunnelsUseCase(
            networkMonitor = mockk<NetworkMonitor>().also {
                every { it.networkEvents } returns MutableSharedFlow()
                every { it.hasUsableNetwork() } returns true
            },
            tunnelManager = manager,
            startTunnel = startTunnel,
            liveTunnels = liveTunnels,
            deletedTunnelStopper = DeletedTunnelStopper(manager, mockk(relaxed = true)),
            appVisibility = AppVisibility { visible },
            authRequiredNotifier = authNotifier,
            liveSessions = LiveSessionCheck { host ->
                onSessionLookup()
                host.id in hostsWithLiveSession
            },
            settings = reconnectTestSettings { autoReconnectSetting },
            context = reconnectTestContext(),
        )
    }

    /** The "Reconnexion automatique" option of the Settings, as the use case reads it each time. */
    private var autoReconnectSetting = true

    /** The clock of the tunnel manager, which dates the tunnels. */
    private var managerClockMs = 0L

    private fun config(id: String = "t1", hostId: String = "h1", localPort: Int = 8080) = TunnelConfig(
        id = id,
        label = "tunnel-$id",
        hostId = hostId,
        type = TunnelType.LOCAL_FORWARD,
        localPort = localPort,
        remoteHost = "127.0.0.1",
        remotePort = 80,
    )

    private fun host(authType: AuthType, fido2Mode: Fido2Mode? = null, id: String = "h1") = Host(
        id = id,
        label = "host-$id",
        hostname = "example.org",
        username = "root",
        authType = authType,
        credentialId = "c1",
        fido2Mode = fido2Mode,
    )

    /** A tunnel that was running and whose SSH transport dropped: ERROR, eligible to reconnect. */
    private fun inError(cfg: TunnelConfig) {
        manager.markStarting(cfg)
        manager.injectActiveForTest(cfg, sessionId = "s-${cfg.id}")
        // After a long healthy run: a first incident, not one more cut of a series.
        managerClockMs += 2 * ReconnectTunnelsUseCase.STABLE_MS
        manager.simulateUnexpectedStopForTest(cfg.id, "Connexion SSH perdue")
    }

    private fun stored(cfg: TunnelConfig, host: Host) {
        coEvery { liveTunnels.findLive(cfg.id) } returns LiveTunnel(cfg, host)
        coEvery { liveTunnels.findHost(cfg.hostId) } returns host
    }

    // ── A host that needs the user, app hidden ────────────────────────────────

    private fun assertHeldForTheUser(cfg: TunnelConfig) {
        coVerify(exactly = 0) { startTunnel(any()) }
        val state = manager.getTunnelState(cfg.id)
        assertEquals(TunnelStatus.ERROR, state?.status)
        assertEquals(TUNNEL_AUTH_REQUIRED_MESSAGE, state?.errorMessage)
        assertTrue(manager.getTunnelsForReconnect().isEmpty(), "held: no network event retries it")
        verify(exactly = 1) { authNotifier.notifyAuthRequired(cfg.id, cfg.label) }
    }

    @Test
    fun `a security key host is not attempted while the app is hidden, it is held and reported`() = runTest {
        val cfg = config()
        inError(cfg)
        stored(cfg, host(AuthType.FIDO2, Fido2Mode.HARDWARE_KEY))

        useCase.reconnectErrorTunnels()

        assertHeldForTheUser(cfg)
    }

    @Test
    fun `a FIDO2 host without a mode is a security key host too`() = runTest {
        val cfg = config()
        inError(cfg)
        stored(cfg, host(AuthType.FIDO2, fido2Mode = null))

        useCase.reconnectErrorTunnels()

        assertHeldForTheUser(cfg)
    }

    @Test
    fun `a biometric key host is not attempted while the app is hidden, it is held and reported`() = runTest {
        val cfg = config()
        inError(cfg)
        stored(cfg, host(AuthType.BIOMETRIC_KEY))

        useCase.reconnectErrorTunnels()

        assertHeldForTheUser(cfg)
    }

    @Test
    fun `the gate costs one wait and no more, there is no point in a back-off`() = runTest {
        val cfg = config()
        inError(cfg)
        stored(cfg, host(AuthType.BIOMETRIC_KEY))

        useCase.reconnectErrorTunnels()

        assertEquals(1_000L, testScheduler.currentTime, "the first wait only")
    }

    @Test
    fun `a held tunnel is not retried at the next network event`() = runTest {
        val cfg = config()
        inError(cfg)
        stored(cfg, host(AuthType.FIDO2, Fido2Mode.HARDWARE_KEY))
        useCase.reconnectErrorTunnels()

        useCase.reconnectErrorTunnels()

        coVerify(exactly = 0) { startTunnel(any()) }
        verify(exactly = 1) { authNotifier.notifyAuthRequired(cfg.id, cfg.label) }
    }

    @Test
    fun `every tunnel of the host is held and reported, none is attempted`() = runTest {
        val first = config("t1", localPort = 8080)
        val second = config("t2", localPort = 8081)
        inError(first)
        inError(second)
        val h = host(AuthType.FIDO2, Fido2Mode.HARDWARE_KEY)
        stored(first, h)
        stored(second, h)

        useCase.reconnectErrorTunnels()

        coVerify(exactly = 0) { startTunnel(any()) }
        verify(exactly = 1) { authNotifier.notifyAuthRequired("t1", "tunnel-t1") }
        verify(exactly = 1) { authNotifier.notifyAuthRequired("t2", "tunnel-t2") }
        assertTrue(manager.getTunnelsForReconnect().isEmpty())
    }

    @Test
    fun `starting a held tunnel again makes it an ordinary candidate`() = runTest {
        val cfg = config()
        inError(cfg)
        stored(cfg, host(AuthType.FIDO2, Fido2Mode.HARDWARE_KEY))
        useCase.reconnectErrorTunnels()
        assertTrue(manager.getTunnelsForReconnect().isEmpty())

        // The user opens the app and restarts it, then it drops again.
        manager.markStarting(cfg)
        manager.injectActiveForTest(cfg, sessionId = "s2")
        manager.simulateUnexpectedStopForTest(cfg.id, "Connexion SSH perdue")

        assertEquals(listOf(cfg), manager.getTunnelsForReconnect())
    }

    // ── The card held for the user can be cleared ─────────────────────────────

    @Test
    fun `the card held for the user can be cleared, the reservation of the reconnection does not stand in the way`() = runTest {
        val cfg = config()
        inError(cfg)
        stored(cfg, host(AuthType.FIDO2, Fido2Mode.HARDWARE_KEY))
        useCase.reconnectErrorTunnels()
        assertHeldForTheUser(cfg)

        assertTrue(manager.dismissError(cfg.id), "the cross works on the authentication required card")

        assertNull(manager.getTunnelState(cfg.id), "back to stopped")
        // The reconnection gave its reservation back when it left (it is not the dismissal that invalidated it).
        assertFalse(manager.isStartAbandoned(cfg))
        assertTrue(manager.getTunnelsForReconnect().isEmpty())
        // And the reconnection does not come back for it at the next network event.
        useCase.reconnectErrorTunnels()
        coVerify(exactly = 0) { startTunnel(any()) }
    }

    @Test
    fun `a lookup that throws after the reservation leaves no start on its way, the card can be cleared`() = runTest {
        val cfg = config()
        inError(cfg)
        coEvery { liveTunnels.findLive(cfg.id) } returns LiveTunnel(cfg, host(AuthType.FIDO2, Fido2Mode.HARDWARE_KEY))
        coEvery { liveTunnels.findHost(cfg.hostId) } throws IllegalStateException("database is locked")

        useCase.reconnectErrorTunnels()

        coVerify(exactly = 0) { startTunnel(any()) }
        assertEquals(TunnelStatus.ERROR, manager.getTunnelState(cfg.id)?.status)
        assertTrue(manager.dismissError(cfg.id))
        assertNull(manager.getTunnelState(cfg.id))
        assertFalse(manager.isStartAbandoned(cfg), "the reservation had been given back")
    }

    @Test
    fun `a reconnection cancelled while it decides to hold leaves no start on its way`() = runTest {
        val cfg = config()
        inError(cfg)
        val h = host(AuthType.FIDO2, Fido2Mode.HARDWARE_KEY)
        stored(cfg, h)
        // The lookup of the gate is a suspension: the scope that observes the network ends there.
        coEvery { liveTunnels.findHost(cfg.hostId) } coAnswers { awaitCancellation() }
        val reconnection = launch { useCase.reconnectErrorTunnels() }
        runCurrent()
        advanceTimeBy(1_001)
        runCurrent()

        reconnection.cancelAndJoin()

        assertEquals(TunnelStatus.ERROR, manager.getTunnelState(cfg.id)?.status)
        assertTrue(manager.getTunnelsForReconnect().isNotEmpty(), "a cut-short reconnection stays a candidate")
        assertTrue(manager.dismissError(cfg.id))
        assertFalse(manager.isStartAbandoned(cfg), "the reservation had been given back")
    }

    // ── The options turned off: no attempt, and not even a hold or a notification ──

    private fun assertLeftAsItWas(cfg: TunnelConfig) {
        coVerify(exactly = 0) { startTunnel(any()) }
        verify(exactly = 0) { authNotifier.notifyAuthRequired(any(), any()) }
        val state = manager.getTunnelState(cfg.id)
        assertEquals(TunnelStatus.ERROR, state?.status)
        assertEquals("Connexion SSH perdue", state?.errorMessage, "not the authentication required message")
        assertEquals(listOf(cfg.id), manager.getTunnelsForReconnect().map { it.id }, "not held either")
    }

    @Test
    fun `a security key host that opted out is neither held nor notified while the app is hidden`() = runTest {
        val cfg = config()
        inError(cfg)
        stored(cfg, host(AuthType.FIDO2, Fido2Mode.HARDWARE_KEY).copy(autoReconnect = false))

        useCase.reconnectErrorTunnels()

        assertLeftAsItWas(cfg)
    }

    @Test
    fun `a biometric key host is neither held nor notified when the option is off in the Settings`() = runTest {
        autoReconnectSetting = false
        val cfg = config()
        inError(cfg)
        stored(cfg, host(AuthType.BIOMETRIC_KEY))

        useCase.reconnectErrorTunnels()

        assertLeftAsItWas(cfg)
    }

    // ── A tunnel that is cut passes the same gate as one the network brings back ──

    @Test
    fun `a cut tunnel of a security key host is held and reported at once while the app is hidden`() = runTest {
        val cfg = config()
        stored(cfg, host(AuthType.FIDO2, Fido2Mode.HARDWARE_KEY))
        val observer = launch { useCase.observe() }
        runCurrent()

        inError(cfg)
        advanceUntilIdle()
        observer.cancel()

        assertHeldForTheUser(cfg)
    }

    @Test
    fun `a cut tunnel of a security key host is attempted with the app on screen, the prompt shows`() = runTest {
        visible = true
        val cfg = config()
        stored(cfg, host(AuthType.FIDO2, Fido2Mode.HARDWARE_KEY))
        coEvery { startTunnel(any()) } returns SshResult.Success(Unit)
        val observer = launch { useCase.observe() }
        runCurrent()

        inError(cfg)
        advanceUntilIdle()
        observer.cancel()

        coVerify(exactly = 1) { startTunnel(cfg) }
        verify(exactly = 0) { authNotifier.notifyAuthRequired(any(), any()) }
    }

    @Test
    fun `a cut tunnel of a host that opted out is neither held nor notified`() = runTest {
        val cfg = config()
        stored(cfg, host(AuthType.FIDO2, Fido2Mode.HARDWARE_KEY).copy(autoReconnect = false))
        val observer = launch { useCase.observe() }
        runCurrent()

        inError(cfg)
        advanceUntilIdle()
        observer.cancel()

        assertLeftAsItWas(cfg)
    }

    // ── Hosts that need nobody ────────────────────────────────────────────────

    @Test
    fun `a passkey, password, key or certificate host is attempted while the app is hidden`() = runTest {
        val hosts = listOf(
            host(AuthType.FIDO2, Fido2Mode.PASSKEY),
            host(AuthType.PASSWORD),
            host(AuthType.SSH_KEY),
            host(AuthType.CERTIFICATE),
        )
        for (h in hosts) {
            setUp()
            val cfg = config()
            inError(cfg)
            stored(cfg, h)
            coEvery { startTunnel(any()) } returns SshResult.Success(Unit)

            useCase.reconnectErrorTunnels()

            coVerify(exactly = 1) { startTunnel(cfg) }
            verify(exactly = 0) { authNotifier.notifyAuthRequired(any(), any()) }
        }
    }

    // ── App on screen ─────────────────────────────────────────────────────────

    @Test
    fun `with the app on screen a security key host is attempted as always, the prompt shows`() = runTest {
        visible = true
        val cfg = config()
        inError(cfg)
        stored(cfg, host(AuthType.FIDO2, Fido2Mode.HARDWARE_KEY))
        coEvery { startTunnel(any()) } returns SshResult.Success(Unit)

        useCase.reconnectErrorTunnels()

        coVerify(exactly = 1) { startTunnel(cfg) }
        verify(exactly = 0) { authNotifier.notifyAuthRequired(any(), any()) }
    }

    @Test
    fun `with the app on screen the host is not even looked up`() = runTest {
        visible = true
        val cfg = config()
        inError(cfg)
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        coEvery { startTunnel(any()) } returns SshResult.Success(Unit)

        useCase.reconnectErrorTunnels()

        coVerify(exactly = 0) { liveTunnels.findHost(any()) }
    }

    @Test
    fun `the visibility is read after the wait, a user who opened the app meanwhile is prompted`() = runTest {
        val cfg = config()
        inError(cfg)
        stored(cfg, host(AuthType.FIDO2, Fido2Mode.HARDWARE_KEY))
        coEvery { startTunnel(any()) } returns SshResult.Success(Unit)

        val job = launch { useCase.reconnectErrorTunnels() }
        runCurrent()
        assertEquals(TunnelStatus.RECONNECTING, manager.getTunnelState(cfg.id)?.status, "waiting before the attempt")
        visible = true
        job.join()

        coVerify(exactly = 1) { startTunnel(cfg) }
        verify(exactly = 0) { authNotifier.notifyAuthRequired(any(), any()) }
    }

    @Test
    fun `an app left during the wait is a hidden app at the attempt`() = runTest {
        visible = true
        val cfg = config()
        inError(cfg)
        stored(cfg, host(AuthType.FIDO2, Fido2Mode.HARDWARE_KEY))

        val job = launch { useCase.reconnectErrorTunnels() }
        runCurrent()
        visible = false
        job.join()

        assertHeldForTheUser(cfg)
    }

    // ── Races and odd cases ───────────────────────────────────────────────────

    @Test
    fun `a tunnel restarted by hand while the host is looked up is left alone and nobody is told`() = runTest {
        val cfg = config()
        inError(cfg)
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        coEvery { liveTunnels.findHost(cfg.hostId) } coAnswers {
            manager.markStarting(cfg)
            manager.injectActiveForTest(cfg, sessionId = "s2")
            host(AuthType.FIDO2, Fido2Mode.HARDWARE_KEY)
        }

        useCase.reconnectErrorTunnels()

        coVerify(exactly = 0) { startTunnel(any()) }
        verify(exactly = 0) { authNotifier.notifyAuthRequired(any(), any()) }
        assertEquals(TunnelStatus.ACTIVE, manager.getTunnelState(cfg.id)?.status, "the new run is untouched")
    }

    @Test
    fun `a host that is gone is left to the attempt, which says so`() = runTest {
        val cfg = config()
        inError(cfg)
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        coEvery { liveTunnels.findHost(cfg.hostId) } returns null
        coEvery { startTunnel(any()) } returns SshResult.Success(Unit)

        useCase.reconnectErrorTunnels()

        coVerify(exactly = 1) { startTunnel(cfg) }
        verify(exactly = 0) { authNotifier.notifyAuthRequired(any(), any()) }
    }

    // ── A host that already has a live session needs no prompt ────────────────

    @Test
    fun `a host with a live session is attempted while the app is hidden, the start reuses the session`() = runTest {
        for (h in listOf(host(AuthType.FIDO2, Fido2Mode.HARDWARE_KEY), host(AuthType.BIOMETRIC_KEY))) {
            setUp()
            val cfg = config()
            inError(cfg)
            stored(cfg, h)
            hostsWithLiveSession = setOf(h.id)
            coEvery { startTunnel(any()) } returns SshResult.Success(Unit)

            useCase.reconnectErrorTunnels()

            coVerify(exactly = 1) { startTunnel(cfg) }
            verify(exactly = 0) { authNotifier.notifyAuthRequired(any(), any()) }
        }
    }

    @Test
    fun `a live session of another host changes nothing`() = runTest {
        val cfg = config()
        inError(cfg)
        stored(cfg, host(AuthType.FIDO2, Fido2Mode.HARDWARE_KEY))
        hostsWithLiveSession = setOf("another-host")

        useCase.reconnectErrorTunnels()

        assertHeldForTheUser(cfg)
    }

    // ── The app can come back while the gate looks things up ──────────────────

    @Test
    fun `an app that became visible during the host lookup gets the normal attempt`() = runTest {
        val cfg = config()
        inError(cfg)
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        coEvery { liveTunnels.findHost(cfg.hostId) } coAnswers {
            visible = true
            host(AuthType.FIDO2, Fido2Mode.HARDWARE_KEY)
        }
        coEvery { startTunnel(any()) } returns SshResult.Success(Unit)

        useCase.reconnectErrorTunnels()

        coVerify(exactly = 1) { startTunnel(cfg) }
        verify(exactly = 0) { authNotifier.notifyAuthRequired(any(), any()) }
    }

    @Test
    fun `an app that became visible during the session lookup gets the normal attempt`() = runTest {
        val cfg = config()
        inError(cfg)
        stored(cfg, host(AuthType.BIOMETRIC_KEY))
        onSessionLookup = { visible = true }
        coEvery { startTunnel(any()) } returns SshResult.Success(Unit)

        useCase.reconnectErrorTunnels()

        coVerify(exactly = 1) { startTunnel(cfg) }
        verify(exactly = 0) { authNotifier.notifyAuthRequired(any(), any()) }
        assertEquals(TunnelStatus.RECONNECTING, manager.getTunnelState(cfg.id)?.status, "not held")
    }

    // ── The user leaves after the gate: the silent hold is reported too ───────

    /** An attempt that ends the way a prompt nobody answered ends, with the app left meanwhile. */
    private fun leavesDuringAttempt(code: SshErrorCode, leaves: Boolean = true) {
        coEvery { startTunnel(any()) } coAnswers {
            if (leaves) visible = false
            SshResult.Error(code, "message")
        }
    }

    @Test
    fun `a prompt that ended at once because the user left during the handshake is reported`() = runTest {
        visible = true
        val cfg = config()
        inError(cfg)
        stored(cfg, host(AuthType.FIDO2, Fido2Mode.HARDWARE_KEY))
        leavesDuringAttempt(SshErrorCode.FIDO2_TIMEOUT)

        useCase.reconnectErrorTunnels()

        assertTrue(manager.getTunnelsForReconnect().isEmpty(), "held, as before")
        assertTrue(manager.isReconnectHeld(cfg.id))
        verify(exactly = 1) { authNotifier.notifyAuthRequired(cfg.id, cfg.label) }
    }

    @Test
    fun `a fingerprint dialog dismissed by the system when the user left is reported`() = runTest {
        visible = true
        val cfg = config()
        inError(cfg)
        stored(cfg, host(AuthType.BIOMETRIC_KEY))
        // The system cancels the dialog when the app goes to the background.
        leavesDuringAttempt(SshErrorCode.FIDO2_USER_CANCELLED)

        useCase.reconnectErrorTunnels()

        verify(exactly = 1) { authNotifier.notifyAuthRequired(cfg.id, cfg.label) }
    }

    @Test
    fun `a prompt that timed out with the app still on screen is the user's own business, nobody is notified`() = runTest {
        visible = true
        val cfg = config()
        inError(cfg)
        stored(cfg, host(AuthType.FIDO2, Fido2Mode.HARDWARE_KEY))
        leavesDuringAttempt(SshErrorCode.FIDO2_TIMEOUT, leaves = false)

        useCase.reconnectErrorTunnels()

        assertTrue(manager.isReconnectHeld(cfg.id), "held as always")
        verify(exactly = 0) { authNotifier.notifyAuthRequired(any(), any()) }
    }

    @Test
    fun `a failure that is not a missing user is not reported, even from a hidden app`() = runTest {
        visible = true
        val cfg = config()
        inError(cfg)
        stored(cfg, host(AuthType.FIDO2, Fido2Mode.HARDWARE_KEY))
        leavesDuringAttempt(SshErrorCode.AUTH_FAILED)

        useCase.reconnectErrorTunnels()

        assertTrue(manager.isReconnectHeld(cfg.id))
        verify(exactly = 0) { authNotifier.notifyAuthRequired(any(), any()) }
    }

    // ── The host key of an unknown server: a prompt for any host, whatever its authentication ──

    @Test
    fun `a host key nobody could confirm, for a host that needs no key or finger, is held and reported`() = runTest {
        // Password host, known hosts cleared, app hidden: the attempt is made (no prompt was
        // certain), the host-key prompt ends at once for lack of a screen, with the timeout code.
        val cfg = config()
        inError(cfg)
        stored(cfg, host(AuthType.PASSWORD))
        coEvery { startTunnel(any()) } returns
            SshResult.Error(SshErrorCode.FIDO2_TIMEOUT, "Vérification du serveur requise : ouvrez NextSH pour confirmer son identité")

        useCase.reconnectErrorTunnels()

        coVerify(exactly = 1) { startTunnel(cfg) }
        assertTrue(manager.isReconnectHeld(cfg.id), "held: another attempt would ask the same into the same empty screen")
        assertEquals(
            "Vérification du serveur requise : ouvrez NextSH pour confirmer son identité",
            manager.getTunnelState(cfg.id)?.errorMessage,
        )
        verify(exactly = 1) { authNotifier.notifyAuthRequired(cfg.id, cfg.label) }
        assertEquals(1_000L, testScheduler.currentTime, "one wait, then given up, no back-off")
    }

    @Test
    fun `every kind of host that needs no key or finger is reported for its host key`() = runTest {
        val hosts = listOf(
            host(AuthType.PASSWORD),
            host(AuthType.SSH_KEY),
            host(AuthType.CERTIFICATE),
            host(AuthType.FIDO2, Fido2Mode.PASSKEY),
        )
        for (h in hosts) {
            setUp()
            val cfg = config()
            inError(cfg)
            stored(cfg, h)
            coEvery { startTunnel(any()) } returns SshResult.Error(SshErrorCode.FIDO2_TIMEOUT, "message")

            useCase.reconnectErrorTunnels()

            verify(exactly = 1) { authNotifier.notifyAuthRequired(cfg.id, cfg.label) }
        }
    }

    @Test
    fun `a host key that was not confirmed in time, the user having left meanwhile, is reported`() = runTest {
        visible = true
        val cfg = config()
        inError(cfg)
        stored(cfg, host(AuthType.PASSWORD))
        leavesDuringAttempt(SshErrorCode.FIDO2_TIMEOUT)

        useCase.reconnectErrorTunnels()

        assertTrue(manager.isReconnectHeld(cfg.id))
        verify(exactly = 1) { authNotifier.notifyAuthRequired(cfg.id, cfg.label) }
    }

    @Test
    fun `a host key not confirmed in time with the app still on screen is the user's own business`() = runTest {
        visible = true
        val cfg = config()
        inError(cfg)
        stored(cfg, host(AuthType.PASSWORD))
        leavesDuringAttempt(SshErrorCode.FIDO2_TIMEOUT, leaves = false)

        useCase.reconnectErrorTunnels()

        assertTrue(manager.isReconnectHeld(cfg.id), "held as always: no prompt comes back by itself")
        verify(exactly = 0) { authNotifier.notifyAuthRequired(any(), any()) }
    }

    @Test
    fun `a cancellation for a host that needs no key or finger is not reported`() = runTest {
        // The cancellation of a host key prompt is a connection that was cancelled: its caller is gone.
        visible = true
        val cfg = config()
        inError(cfg)
        stored(cfg, host(AuthType.PASSWORD))
        leavesDuringAttempt(SshErrorCode.FIDO2_USER_CANCELLED)

        useCase.reconnectErrorTunnels()

        verify(exactly = 0) { authNotifier.notifyAuthRequired(any(), any()) }
    }

    @Test
    fun `a host key prompt of a host that opted out of the reconnection is never reached`() = runTest {
        val cfg = config()
        inError(cfg)
        stored(cfg, host(AuthType.PASSWORD).copy(autoReconnect = false))

        useCase.reconnectErrorTunnels()

        assertLeftAsItWas(cfg)
    }

    @Test
    fun `a tunnel the user restarted during the attempt is not held and nobody is told`() = runTest {
        visible = true
        val cfg = config()
        inError(cfg)
        stored(cfg, host(AuthType.FIDO2, Fido2Mode.HARDWARE_KEY))
        coEvery { startTunnel(any()) } coAnswers {
            visible = false
            manager.markStarting(cfg)
            manager.injectActiveForTest(cfg, sessionId = "s2")
            SshResult.Error(SshErrorCode.FIDO2_TIMEOUT, "message")
        }

        useCase.reconnectErrorTunnels()

        assertEquals(TunnelStatus.ACTIVE, manager.getTunnelState(cfg.id)?.status, "the new run is untouched")
        verify(exactly = 0) { authNotifier.notifyAuthRequired(any(), any()) }
    }

    @Test
    fun `a user who came back during the host lookup of the hold is not notified`() = runTest {
        visible = true
        val cfg = config()
        inError(cfg)
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        coEvery { liveTunnels.findHost(cfg.hostId) } coAnswers {
            visible = true
            host(AuthType.FIDO2, Fido2Mode.HARDWARE_KEY)
        }
        coEvery { startTunnel(any()) } coAnswers {
            visible = false
            SshResult.Error(SshErrorCode.FIDO2_TIMEOUT, "message")
        }

        useCase.reconnectErrorTunnels()

        assertTrue(manager.isReconnectHeld(cfg.id), "held as always, the card shows the error")
        verify(exactly = 0) { authNotifier.notifyAuthRequired(any(), any()) }
    }

    @Test
    fun `a tunnel started again during the host lookup of the hold is not notified`() = runTest {
        visible = true
        val cfg = config()
        inError(cfg)
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        coEvery { liveTunnels.findHost(cfg.hostId) } coAnswers {
            manager.markStarting(cfg)
            manager.injectActiveForTest(cfg, sessionId = "s2")
            host(AuthType.BIOMETRIC_KEY)
        }
        coEvery { startTunnel(any()) } coAnswers {
            visible = false
            SshResult.Error(SshErrorCode.FIDO2_TIMEOUT, "message")
        }

        useCase.reconnectErrorTunnels()

        verify(exactly = 0) { authNotifier.notifyAuthRequired(any(), any()) }
        assertEquals(TunnelStatus.ACTIVE, manager.getTunnelState(cfg.id)?.status)
    }
}