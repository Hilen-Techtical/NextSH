// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.domain.usecase

import fr.techtical.nextsh.core.AppVisibility
import fr.techtical.nextsh.core.network.NetworkEvent
import fr.techtical.nextsh.core.network.NetworkMonitor
import fr.techtical.nextsh.core.ssh.DeletedTunnelStopper
import fr.techtical.nextsh.core.ssh.LiveSessionCheck
import fr.techtical.nextsh.core.ssh.SshTunnelManager
import fr.techtical.nextsh.core.ssh.TUNNEL_NETWORK_LOST_MESSAGE
import fr.techtical.nextsh.core.ssh.TUNNEL_NO_ANSWER_MESSAGE
import fr.techtical.nextsh.core.ssh.TUNNEL_RECONNECT_INTERRUPTED_MESSAGE
import fr.techtical.nextsh.core.ssh.TUNNEL_SESSION_LOST_MESSAGE
import fr.techtical.nextsh.core.ssh.classifyUserAuthFailure
import fr.techtical.nextsh.core.ssh.userAuthFailureCode
import fr.techtical.nextsh.domain.model.SshErrorCode
import fr.techtical.nextsh.domain.model.TunnelConfig
import fr.techtical.nextsh.domain.model.TunnelStatus
import fr.techtical.nextsh.domain.model.TunnelType
import fr.techtical.nextsh.service.DeletedTunnelNotifier
import fr.techtical.nextsh.service.shouldStopForegroundService
import fr.techtical.nextsh.shared.domain.model.SshResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import com.hierynomus.sshj.common.KeyDecryptionFailedException
import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.userauth.UserAuthException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeoutException

/** How long a tunnel held before a cut that is a first incident, not one more of a series. */
private const val HEALTHY_UPTIME_MS = 2 * ReconnectTunnelsUseCase.STABLE_MS

/**
 * The reconnection loop against the real [SshTunnelManager] state machine (no SSH, see
 * `injectActiveForTest`), with the database lookup and the start use case mocked. Time is
 * virtual: the waits of 1, 2, 4, 8 and 16 seconds cost nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReconnectTunnelsUseCaseTest {

    private lateinit var manager: SshTunnelManager
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var startTunnel: StartTunnelUseCase
    private lateinit var liveTunnels: LiveTunnelLookup
    private lateinit var notifier: DeletedTunnelNotifier
    private lateinit var useCase: ReconnectTunnelsUseCase

    @BeforeEach
    fun setUp() {
        manager = SshTunnelManager(sessionManager = mockk(relaxed = true), clock = { managerClockMs })
        networkMonitor = mockk()
        every { networkMonitor.networkEvents } returns networkEvents
        every { networkMonitor.hasUsableNetwork() } answers { networkUsable }
        startTunnel = mockk()
        liveTunnels = mockk()
        notifier = mockk(relaxed = true)
        useCase = ReconnectTunnelsUseCase(
            networkMonitor = networkMonitor,
            tunnelManager = manager,
            startTunnel = startTunnel,
            liveTunnels = liveTunnels,
            deletedTunnelStopper = DeletedTunnelStopper(manager, notifier),
            // The app is on screen: this class tests the loop itself, the gate for a host that needs
            // the user is in ReconnectTunnelsUseCaseAuthGateTest.
            appVisibility = AppVisibility { true },
            authRequiredNotifier = mockk(relaxed = true),
            liveSessions = LiveSessionCheck { false },
            settings = reconnectTestSettings { autoReconnectSetting },
            context = reconnectTestContext(),
        )
    }

    /** The "Reconnexion automatique" option of the Settings, as the use case reads it each time. */
    private var autoReconnectSetting = true

    /** What `NetworkMonitor.hasUsableNetwork()` answers: online unless a test says otherwise. */
    private var networkUsable = true

    /** The clock of the tunnel manager, which dates the tunnels: a test moves it to give a tunnel an uptime. */
    private var managerClockMs = 0L

    /** The network events of the tests that observe; a test that wants none just never emits. */
    private val networkEvents = MutableSharedFlow<NetworkEvent>(replay = 1)

    private fun config(id: String = "t1", localPort: Int = 8080, hostId: String = "h1") = TunnelConfig(
        id = id,
        label = "tunnel-$id",
        hostId = hostId,
        type = TunnelType.LOCAL_FORWARD,
        localPort = localPort,
        remoteHost = "127.0.0.1",
        remotePort = 80,
    )

    /** A tunnel that was running and whose SSH transport dropped: ERROR, eligible to reconnect. */
    private fun inError(cfg: TunnelConfig) {
        manager.markStarting(cfg)
        manager.injectActiveForTest(cfg, sessionId = "s1")
        manager.simulateUnexpectedStopForTest(cfg.id, "Connexion SSH perdue")
    }

    // ── Bug 1: a tunnel stopped while it waits is never restarted ───────────────────

    @Test
    fun `a tunnel stopped by hand while it waits to reconnect is not restarted`() = runTest {
        val cfg = config()
        inError(cfg)
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        coEvery { startTunnel(any()) } returns SshResult.Success(Unit)

        val job = launch { useCase.reconnectErrorTunnels() }
        runCurrent()
        assertEquals(TunnelStatus.RECONNECTING, manager.getTunnelState(cfg.id)?.status, "waiting before the attempt")

        // The user closes the card during the wait. stopTunnel switches to Dispatchers.IO, a real
        // thread: called as a suspend call from runTest, the test body suspends while the stop
        // runs there, runTest sees nothing left to do and advances virtual time, so the 1 s wait
        // of the loop can elapse (and startTunnel be called) before the stop has finished. That
        // made this test flaky. runBlocking keeps the test thread, and so the virtual clock,
        // still until the stop is done.
        runBlocking { manager.stopTunnel(cfg.id) }
        job.join()

        coVerify(exactly = 0) { startTunnel(any()) }
        assertNull(manager.getTunnelState(cfg.id), "the stop must stick")
        // A stop by hand is not a deletion elsewhere: nobody is notified.
        verify(exactly = 0) { notifier.notifyStopped(any()) }
    }

    @Test
    fun `a tunnel restarted by hand while it waits is left alone`() = runTest {
        val cfg = config()
        inError(cfg)
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        coEvery { startTunnel(any()) } returns SshResult.Success(Unit)

        val job = launch { useCase.reconnectErrorTunnels() }
        runCurrent()

        manager.markStarting(cfg)
        manager.injectActiveForTest(cfg, sessionId = "s2")
        job.join()

        coVerify(exactly = 0) { startTunnel(any()) }
        assertEquals(TunnelStatus.ACTIVE, manager.getTunnelState(cfg.id)?.status, "the new run is untouched")
    }

    @Test
    fun `the final failure does not flag a tunnel the user restarted during the last attempt`() = runTest {
        val cfg = config()
        inError(cfg)
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        var attempts = 0
        coEvery { startTunnel(any()) } coAnswers {
            // The user starts the tunnel again while the 5th attempt is still running.
            if (++attempts == 5) {
                manager.markStarting(cfg)
                manager.injectActiveForTest(cfg, sessionId = "s2")
            }
            SshResult.Error(SshErrorCode.UNKNOWN, "refused")
        }

        useCase.reconnectErrorTunnels()

        assertEquals(5, attempts)
        assertEquals(TunnelStatus.ACTIVE, manager.getTunnelState(cfg.id)?.status, "must not be flagged ERROR")
    }

    // ── Bug 2 family: a tunnel or host deleted elsewhere is stopped, not retried ────

    @Test
    fun `a tunnel deleted while it waits is stopped for good and not retried`() = runTest {
        val cfg = config()
        inError(cfg)
        // Live when the step starts, gone after the wait.
        coEvery { liveTunnels.findLive(cfg.id) } returnsMany listOf(liveOf(cfg), null)

        useCase.reconnectErrorTunnels()

        coVerify(exactly = 0) { startTunnel(any()) }
        assertNull(manager.getTunnelState(cfg.id), "the state must go, or the foreground service never ends")
        assertTrue(manager.getTunnelsForReconnect().isEmpty())
        // The user is told, with the label of the tunnel that was running.
        verify(exactly = 1) { notifier.notifyStopped(listOf("tunnel-t1")) }
    }

    @Test
    fun `a tunnel already deleted is stopped before it is marked as reconnecting`() = runTest {
        val cfg = config()
        inError(cfg)
        coEvery { liveTunnels.findLive(cfg.id) } returns null

        useCase.reconnectErrorTunnels()

        coVerify(exactly = 1) { liveTunnels.findLive(cfg.id) }
        coVerify(exactly = 0) { startTunnel(any()) }
        assertNull(manager.getTunnelState(cfg.id))
        assertEquals(0L, testScheduler.currentTime, "no wait for a tunnel that is gone")
        // Still in ERROR when stopped (never marked as reconnecting): stopped silently.
        verify(exactly = 0) { notifier.notifyStopped(any()) }
    }

    // ── The attempt starts from the stored row ──────────────────────────────────────

    @Test
    fun `the attempt starts from the stored row, an edit made while in error applies`() = runTest {
        val frozen = config(localPort = 8080)
        val edited = frozen.copy(localPort = 9090)
        inError(frozen)
        coEvery { liveTunnels.findLive(frozen.id) } returns liveOf(edited)
        coEvery { startTunnel(edited) } returns SshResult.Success(Unit)

        useCase.reconnectErrorTunnels()

        coVerify(exactly = 1) { startTunnel(edited) }
        coVerify(exactly = 0) { startTunnel(frozen) }
    }

    // ── Existing behaviour kept ─────────────────────────────────────────────────────

    @Test
    fun `retries back off 1 2 4 8 16 seconds then leave the tunnel in error`() = runTest {
        val cfg = config()
        inError(cfg)
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        coEvery { startTunnel(any()) } returns SshResult.Error(SshErrorCode.UNKNOWN, "refused")

        useCase.reconnectErrorTunnels()

        coVerify(exactly = 5) { startTunnel(any()) }
        assertEquals(31_000L, testScheduler.currentTime)
        val state = manager.getTunnelState(cfg.id)
        assertEquals(TunnelStatus.ERROR, state?.status)
        assertEquals("Reconnexion échouée après 5 tentatives : refused", state?.errorMessage)
    }

    // ── The final message keeps the reason of the last attempt ──────────────────────

    @Test
    fun `the final message gives the reason of the last attempt, not of the first`() = runTest {
        val cfg = config()
        inError(cfg)
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        val reasons = listOf("un", "deux", "trois", "quatre", "cinq").iterator()
        coEvery { startTunnel(any()) } coAnswers {
            SshResult.Error(SshErrorCode.HOST_UNREACHABLE, reasons.next())
        }

        useCase.reconnectErrorTunnels()

        assertEquals(
            "Reconnexion échouée après 5 tentatives : cinq",
            manager.getTunnelState(cfg.id)?.errorMessage,
        )
    }

    @Test
    fun `the reason is the one the failed start put on the tunnel, as the shared start writes it`() = runTest {
        val cfg = config()
        inError(cfg)
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        coEvery { startTunnel(cfg) } coAnswers {
            failedStart(cfg, SshErrorCode.HOST_UNREACHABLE, "Délai de connexion dépassé (10 s)")
        }

        useCase.reconnectErrorTunnels()

        assertEquals(
            "Reconnexion échouée après 5 tentatives : Délai de connexion dépassé (10 s)",
            manager.getTunnelState(cfg.id)?.errorMessage,
        )
    }

    @Test
    fun `a fixed text of the tunnel manager is quoted in the language of the user`() = runTest {
        val cfg = config()
        inError(cfg)
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        // The session of the host died between the look and the reference: the provider's own text.
        coEvery { startTunnel(any()) } returns SshResult.Error(SshErrorCode.HOST_UNREACHABLE, TUNNEL_SESSION_LOST_MESSAGE)

        useCase.reconnectErrorTunnels()

        assertEquals(
            "Reconnexion échouée après 5 tentatives : SSH connection lost (translated)",
            manager.getTunnelState(cfg.id)?.errorMessage,
        )
    }

    @Test
    fun `a failure without a message still says how many attempts were made`() = runTest {
        val cfg = config()
        inError(cfg)
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        coEvery { startTunnel(any()) } returns SshResult.Error(SshErrorCode.UNKNOWN, "  ")

        useCase.reconnectErrorTunnels()

        assertEquals("Reconnexion échouée après 5 tentatives", manager.getTunnelState(cfg.id)?.errorMessage)
    }

    @Test
    fun `an internal error ends the tunnel in error with a plain message, not a made up reason`() = runTest {
        val cfg = config()
        inError(cfg)
        coEvery { liveTunnels.findLive(cfg.id) } throws IllegalStateException("database closed")

        useCase.reconnectErrorTunnels()

        assertEquals(TunnelStatus.ERROR, manager.getTunnelState(cfg.id)?.status)
        assertEquals("Reconnexion échouée", manager.getTunnelState(cfg.id)?.errorMessage)
    }

    @Test
    fun `the reason of a failure that stops the loop at once is the one of that failure, untouched`() = runTest {
        val cfg = config()
        inError(cfg)
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        coEvery { startTunnel(any()) } returns SshResult.Error(SshErrorCode.AUTH_FAILED, "Authentification échouée")

        useCase.reconnectErrorTunnels()

        // Not "failed after 5 attempts": there was one.
        assertEquals("Authentification échouée", manager.getTunnelState(cfg.id)?.errorMessage)
    }

    @Test
    fun `a success ends the loop`() = runTest {
        val cfg = config()
        inError(cfg)
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        coEvery { startTunnel(any()) } returnsMany listOf(
            SshResult.Error(SshErrorCode.UNKNOWN, "refused"),
            SshResult.Success(Unit),
        )

        useCase.reconnectErrorTunnels()

        coVerify(exactly = 2) { startTunnel(any()) }
        assertEquals(3_000L, testScheduler.currentTime)
    }

    // ── An authentication failure is not retried: no key sheet popping up again ─────

    private val authFailures = listOf(
        SshErrorCode.AUTH_FAILED,
        SshErrorCode.FIDO2_NO_KEY,
        SshErrorCode.FIDO2_USER_CANCELLED,
        SshErrorCode.FIDO2_TIMEOUT,
    )

    @Test
    fun `an authentication failure ends the loop at once and leaves the tunnel in error with its message`() = runTest {
        for (code in authFailures) {
            val cfg = config(id = "t-$code")
            inError(cfg)
            coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
            coEvery { startTunnel(cfg) } returns SshResult.Error(code, "reason-$code")

            useCase.reconnectErrorTunnels()

            coVerify(exactly = 1) { startTunnel(cfg) }
            val state = manager.getTunnelState(cfg.id)
            assertEquals(TunnelStatus.ERROR, state?.status, "$code")
            assertEquals("reason-$code", state?.errorMessage, "$code")
        }
    }

    @Test
    fun `an authentication failure costs one wait, not the whole back-off`() = runTest {
        val cfg = config()
        inError(cfg)
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        coEvery { startTunnel(any()) } returns SshResult.Error(SshErrorCode.FIDO2_USER_CANCELLED, "Connexion annulée")

        useCase.reconnectErrorTunnels()

        assertEquals(1_000L, testScheduler.currentTime)
    }

    @Test
    fun `a cancelled key prompt is not asked again at the next network event`() = runTest {
        val cfg = config()
        inError(cfg)
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        coEvery { startTunnel(any()) } returns SshResult.Error(SshErrorCode.FIDO2_USER_CANCELLED, "Connexion annulée")

        useCase.reconnectErrorTunnels()
        // Two more connectivity events.
        useCase.reconnectErrorTunnels()
        useCase.reconnectErrorTunnels()

        coVerify(exactly = 1) { startTunnel(any()) }
        assertTrue(manager.getTunnelsForReconnect().isEmpty())
        assertEquals(TunnelStatus.ERROR, manager.getTunnelState(cfg.id)?.status, "still shown, with its message")
    }

    @Test
    fun `starting the tunnel again by hand makes it a reconnection candidate again`() = runTest {
        val cfg = config()
        inError(cfg)
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        coEvery { startTunnel(any()) } returns SshResult.Error(SshErrorCode.AUTH_FAILED, "refused")
        useCase.reconnectErrorTunnels()
        assertTrue(manager.getTunnelsForReconnect().isEmpty())

        // The user starts it (StartTunnelUseCase marks it STARTING), it runs, then the transport drops.
        manager.markStarting(cfg)
        manager.injectActiveForTest(cfg, sessionId = "s2")
        manager.simulateUnexpectedStopForTest(cfg.id, "Connexion SSH perdue")

        assertEquals(listOf(cfg.id), manager.getTunnelsForReconnect().map { it.id })
    }

    @Test
    fun `a network failure then an authentication failure stops at the authentication failure`() = runTest {
        val cfg = config()
        inError(cfg)
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        coEvery { startTunnel(any()) } returnsMany listOf(
            SshResult.Error(SshErrorCode.HOST_UNREACHABLE, "unreachable"),
            SshResult.Error(SshErrorCode.FIDO2_TIMEOUT, "Clé de sécurité non touchée à temps"),
        )

        useCase.reconnectErrorTunnels()

        coVerify(exactly = 2) { startTunnel(any()) }
        assertEquals(3_000L, testScheduler.currentTime)
        assertEquals("Clé de sécurité non touchée à temps", manager.getTunnelState(cfg.id)?.errorMessage)
    }

    @Test
    fun `network failures keep being retried`() = runTest {
        // The other side of the rule: unreachable, timeout, port in use are what the loop is for.
        for (code in listOf(SshErrorCode.HOST_UNREACHABLE, SshErrorCode.TIMEOUT, SshErrorCode.PORT_IN_USE)) {
            val cfg = config(id = "t-$code")
            inError(cfg)
            coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
            coEvery { startTunnel(cfg) } returns SshResult.Error(code, "x")

            useCase.reconnectErrorTunnels()

            coVerify(exactly = 5) { startTunnel(cfg) }
        }
    }

    // ── An expired authentication window: only the user renews it ───────────────────

    /**
     * A start that fails the way the shared StartTunnelUseCase does when the connection fails: it
     * marks the tunnel in error with the reason (`markError`), then returns the same error.
     */
    private fun failedStart(cfg: TunnelConfig, code: SshErrorCode, message: String): SshResult.Error {
        manager.markError(cfg, message)
        return SshResult.Error(code, message)
    }

    @Test
    fun `an expired authentication window ends the loop at the first attempt and keeps its message`() = runTest {
        val cfg = config()
        inError(cfg)
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        coEvery { startTunnel(cfg) } coAnswers {
            failedStart(cfg, SshErrorCode.AUTH_EXPIRED, "Authentification biométrique expirée : réessayez")
        }

        useCase.reconnectErrorTunnels()

        coVerify(exactly = 1) { startTunnel(cfg) }
        assertEquals(1_000L, testScheduler.currentTime, "one wait, not the whole back-off")
        val state = manager.getTunnelState(cfg.id)
        assertEquals(TunnelStatus.ERROR, state?.status)
        assertEquals("Authentification biométrique expirée : réessayez", state?.errorMessage)
    }

    @Test
    fun `an expired authentication window is not held, the next network event looks at the tunnel again`() = runTest {
        val cfg = config()
        inError(cfg)
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        coEvery { startTunnel(cfg) } coAnswers { failedStart(cfg, SshErrorCode.AUTH_EXPIRED, "expired") }

        useCase.reconnectErrorTunnels()
        assertEquals(listOf(cfg.id), manager.getTunnelsForReconnect().map { it.id }, "nothing is wrong with the credential")

        useCase.reconnectErrorTunnels()

        coVerify(exactly = 2) { startTunnel(cfg) }
    }

    @Test
    fun `a network failure then an expired window stops at the expired window`() = runTest {
        val cfg = config()
        inError(cfg)
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        val answers = listOf(
            SshErrorCode.HOST_UNREACHABLE to "unreachable",
            SshErrorCode.AUTH_EXPIRED to "expired",
        ).iterator()
        coEvery { startTunnel(any()) } coAnswers {
            val (code, message) = answers.next()
            failedStart(cfg, code, message)
        }

        useCase.reconnectErrorTunnels()

        coVerify(exactly = 2) { startTunnel(any()) }
        assertEquals(3_000L, testScheduler.currentTime)
        assertEquals("expired", manager.getTunnelState(cfg.id)?.errorMessage)
    }

    @Test
    fun `the expired window of a biometric key, as the connection reports it, ends the loop`() = runTest {
        val cfg = config()
        inError(cfg)
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        val windowOver = io.mockk.mockk<android.security.keystore.UserNotAuthenticatedException>(relaxed = true)
            .also { every { it.cause } returns null }
        val expired = UserAuthException("signing", net.schmizz.sshj.common.SSHRuntimeException(windowOver))
        val reported = failureOf(expired, "Authentification biométrique expirée : réessayez")
        coEvery { startTunnel(any()) } coAnswers { failedStart(cfg, reported.code, reported.message) }

        useCase.reconnectErrorTunnels()

        coVerify(exactly = 1) { startTunnel(any()) }
        assertEquals(SshErrorCode.AUTH_EXPIRED, reported.code)
        assertEquals(TunnelStatus.ERROR, manager.getTunnelState(cfg.id)?.status)
    }

    @Test
    fun `the vault window that ran out ends the loop the same way`() = runTest {
        // ConnectSessionUseCase turns the VaultAuthExpiredException into AUTH_EXPIRED, which the
        // shared start passes on after marking the tunnel in error.
        val cfg = config()
        inError(cfg)
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        coEvery { startTunnel(cfg) } coAnswers {
            failedStart(cfg, SshErrorCode.AUTH_EXPIRED, "Authentification vault expirée : veuillez vous ré-authentifier")
        }

        useCase.reconnectErrorTunnels()

        coVerify(exactly = 1) { startTunnel(cfg) }
        assertEquals(1_000L, testScheduler.currentTime)
        assertEquals(
            "Authentification vault expirée : veuillez vous ré-authentifier",
            manager.getTunnelState(cfg.id)?.errorMessage,
        )
    }

    @Test
    fun `an expired window writes nothing itself, the state is the one the shared start left`() = runTest {
        // The start marks the error before it returns: the loop must not write it a second time.
        // Two different texts tell the writers apart.
        val cfg = config()
        inError(cfg)
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        coEvery { startTunnel(cfg) } coAnswers {
            manager.markError(cfg, "written by the start")
            SshResult.Error(SshErrorCode.AUTH_EXPIRED, "returned by the start")
        }

        useCase.reconnectErrorTunnels()

        assertEquals("written by the start", manager.getTunnelState(cfg.id)?.errorMessage)
    }

    @Test
    fun `an expired window does not flag a tunnel the user restarted during the attempt`() = runTest {
        val cfg = config()
        inError(cfg)
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        coEvery { startTunnel(any()) } coAnswers {
            manager.markStarting(cfg)
            manager.injectActiveForTest(cfg, sessionId = "s2")
            SshResult.Error(SshErrorCode.AUTH_EXPIRED, "expired")
        }

        useCase.reconnectErrorTunnels()

        assertEquals(TunnelStatus.ACTIVE, manager.getTunnelState(cfg.id)?.status, "the new run is untouched")
        assertNull(manager.getTunnelState(cfg.id)?.errorMessage)
    }

    // The failure fed to the loop is the one SshSessionManager builds from what SSHJ threw.

    private fun failureOf(e: UserAuthException, message: String): SshResult.Error =
        SshResult.Error(userAuthFailureCode(classifyUserAuthFailure(e)), message)

    @Test
    fun `a connection lost during the authentication is retried like any network failure`() = runTest {
        val cfg = config()
        inError(cfg)
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        val lost = UserAuthException(TransportException(DisconnectReason.CONNECTION_LOST, "Connection lost"))
        coEvery { startTunnel(any()) } returns failureOf(lost, "Connexion impossible")

        useCase.reconnectErrorTunnels()

        coVerify(exactly = 5) { startTunnel(any()) }
        assertEquals(TunnelStatus.ERROR, manager.getTunnelState(cfg.id)?.status)
        assertEquals(31_000L, testScheduler.currentTime)
        // Given up after the five tries, not held: the next network event tries again.
        assertEquals(listOf(cfg.id), manager.getTunnelsForReconnect().map { it.id })
    }

    @Test
    fun `an authentication wait that ran out is retried like any network failure`() = runTest {
        val cfg = config()
        inError(cfg)
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        val late = UserAuthException(TimeoutException("Timeout expired: 30000 MILLISECONDS"))
        coEvery { startTunnel(any()) } returns failureOf(late, "Délai de connexion dépassé (30 s)")

        useCase.reconnectErrorTunnels()

        coVerify(exactly = 5) { startTunnel(any()) }
        assertEquals(listOf(cfg.id), manager.getTunnelsForReconnect().map { it.id })
    }

    @Test
    fun `a key that cannot be read is given up at once and held, not retried like a network failure`() = runTest {
        val cfg = config()
        inError(cfg)
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        val wrongPassphrase = UserAuthException(
            "Problem getting private key from FileKeyProvider",
            KeyDecryptionFailedException(),
        )
        coEvery { startTunnel(any()) } returns failureOf(wrongPassphrase, "La clé SSH n'a pas pu être lue (passphrase ?)")

        useCase.reconnectErrorTunnels()

        coVerify(exactly = 1) { startTunnel(any()) }
        assertTrue(manager.getTunnelsForReconnect().isEmpty())
    }

    @Test
    fun `OpenSSH too many authentication failures is given up at once and held`() = runTest {
        val cfg = config()
        inError(cfg)
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        val tooMany = UserAuthException(TransportException(DisconnectReason.PROTOCOL_ERROR, "Too many authentication failures"))
        coEvery { startTunnel(any()) } returns failureOf(tooMany, "Authentification échouée")

        useCase.reconnectErrorTunnels()

        // Five more attempts, and at every network event after that, would be five more failed logins.
        coVerify(exactly = 1) { startTunnel(any()) }
        assertTrue(manager.getTunnelsForReconnect().isEmpty())
    }

    @Test
    fun `a host key that no longer matches is never retried and keeps its message`() = runTest {
        val cfg = config()
        inError(cfg)
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        val message = "Vérification de la clé hôte échouée : the key of the server changed"
        coEvery { startTunnel(any()) } returns SshResult.Error(SshErrorCode.HOST_KEY_MISMATCH, message)

        useCase.reconnectErrorTunnels()
        // Nor at the next network events.
        useCase.reconnectErrorTunnels()
        useCase.reconnectErrorTunnels()

        coVerify(exactly = 1) { startTunnel(any()) }
        assertEquals(TunnelStatus.ERROR, manager.getTunnelState(cfg.id)?.status)
        assertEquals(message, manager.getTunnelState(cfg.id)?.errorMessage)
        assertTrue(manager.getTunnelsForReconnect().isEmpty())
        assertEquals(1_000L, testScheduler.currentTime, "one wait, then given up")
    }

    @Test
    fun `a host key refused during the authentication is never retried either`() = runTest {
        val cfg = config()
        inError(cfg)
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        // A re-key inside the authentication whose host key the verifier refuses.
        val rekey = UserAuthException(TransportException(DisconnectReason.HOST_KEY_NOT_VERIFIABLE, "Could not verify host key"))
        coEvery { startTunnel(any()) } returns failureOf(rekey, "Vérification de la clé de l'hôte échouée")

        useCase.reconnectErrorTunnels()

        coVerify(exactly = 1) { startTunnel(any()) }
        assertTrue(manager.getTunnelsForReconnect().isEmpty())
    }

    @Test
    fun `a genuine refusal is given up at once and held`() = runTest {
        val cfg = config()
        inError(cfg)
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        val refused = UserAuthException("Exhausted available authentication methods")
        coEvery { startTunnel(any()) } returns failureOf(refused, "Authentification échouée")

        useCase.reconnectErrorTunnels()

        coVerify(exactly = 1) { startTunnel(any()) }
        assertEquals("Authentification échouée", manager.getTunnelState(cfg.id)?.errorMessage)
        assertTrue(manager.getTunnelsForReconnect().isEmpty())
    }

    @Test
    fun `an authentication failure does not flag a tunnel the user restarted during the attempt`() = runTest {
        val cfg = config()
        inError(cfg)
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        coEvery { startTunnel(any()) } coAnswers {
            manager.markStarting(cfg)
            manager.injectActiveForTest(cfg, sessionId = "s2")
            SshResult.Error(SshErrorCode.FIDO2_USER_CANCELLED, "Connexion annulée")
        }

        useCase.reconnectErrorTunnels()

        assertEquals(TunnelStatus.ACTIVE, manager.getTunnelState(cfg.id)?.status, "the new run is untouched")
        assertNull(manager.getTunnelState(cfg.id)?.errorMessage)
    }

    // ── A reconnection cancelled on the way does not leave the card reconnecting for ever ──

    @Test
    fun `a reconnection cancelled during the wait goes back to error and stays a candidate`() = runTest {
        val cfg = config()
        inError(cfg)
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)

        val job = launch { useCase.reconnectErrorTunnels() }
        runCurrent()
        assertEquals(TunnelStatus.RECONNECTING, manager.getTunnelState(cfg.id)?.status)
        job.cancel()
        job.join()

        assertTrue(job.isCancelled, "the cancellation goes on")
        assertEquals(TunnelStatus.ERROR, manager.getTunnelState(cfg.id)?.status)
        assertEquals(TUNNEL_RECONNECT_INTERRUPTED_MESSAGE, manager.getTunnelState(cfg.id)?.errorMessage)
        assertEquals(listOf(cfg.id), manager.getTunnelsForReconnect().map { it.id }, "retried at the next network event")
        coVerify(exactly = 0) { startTunnel(any()) }
    }

    @Test
    fun `a reconnection cancelled during the attempt goes back to error and stays a candidate`() = runTest {
        val cfg = config()
        inError(cfg)
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        // The shared use case marks the tunnel STARTING, then the connection is under way.
        coEvery { startTunnel(any()) } coAnswers {
            manager.markStarting(cfg)
            awaitCancellation()
        }

        val job = launch { useCase.reconnectErrorTunnels() }
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(TunnelStatus.STARTING, manager.getTunnelState(cfg.id)?.status)
        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(TunnelStatus.ERROR, manager.getTunnelState(cfg.id)?.status)
        assertEquals(listOf(cfg.id), manager.getTunnelsForReconnect().map { it.id })
    }

    @Test
    fun `a reconnection cancelled during the attempt leaves alone a start the user made meanwhile`() = runTest {
        val cfg = config()
        inError(cfg)
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        // The attempt is on when the user stops the card and starts the tunnel again: the state is
        // a STARTING that carries the row of the user's start, not the one of the attempt.
        coEvery { startTunnel(any()) } coAnswers {
            manager.markStarting(cfg.copy())
            awaitCancellation()
        }

        val job = launch { useCase.reconnectErrorTunnels() }
        advanceTimeBy(1_001)
        runCurrent()
        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(TunnelStatus.STARTING, manager.getTunnelState(cfg.id)?.status, "the user's start goes on")
        assertNull(manager.getTunnelState(cfg.id)?.errorMessage)
    }

    @Test
    fun `a reconnection cancelled after the user stopped the tunnel does not bring it back`() = runTest {
        val cfg = config()
        inError(cfg)
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)

        val job = launch { useCase.reconnectErrorTunnels() }
        runCurrent()
        runBlocking { manager.stopTunnel(cfg.id) }
        job.cancel()
        job.join()

        assertNull(manager.getTunnelState(cfg.id), "the stop sticks")
    }

    @Test
    fun `a failure on one tunnel does not stop the others`() = runTest {
        val broken = config(id = "broken")
        val healthy = config(id = "healthy")
        inError(broken)
        inError(healthy)
        coEvery { liveTunnels.findLive("broken") } throws IllegalStateException("database closed")
        coEvery { liveTunnels.findLive("healthy") } returns liveOf(healthy)
        coEvery { startTunnel(healthy) } returns SshResult.Success(Unit)

        useCase.reconnectErrorTunnels()

        coVerify(exactly = 1) { startTunnel(healthy) }
        assertEquals(
            TunnelStatus.ERROR,
            manager.getTunnelState("broken")?.status,
            "the failed one is flagged, not left as reconnecting",
        )
    }

    @Test
    fun `only a network connection triggers a reconnection`() = runTest {
        val cfg = config()
        inError(cfg)
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        coEvery { startTunnel(any()) } returns SshResult.Success(Unit)
        val events = MutableSharedFlow<NetworkEvent>(replay = 1)
        every { networkMonitor.networkEvents } returns events
        // A foreground job, not backgroundScope: advanceUntilIdle skips background work, and the
        // reconnection waits on virtual time. The collector never ends, so it is cancelled.
        val collector = launch { useCase.observe() }

        events.tryEmit(NetworkEvent.Lost)
        advanceUntilIdle()
        coVerify(exactly = 0) { startTunnel(any()) }

        events.tryEmit(NetworkEvent.Connected)
        advanceUntilIdle()
        coVerify(exactly = 1) { startTunnel(any()) }
        collector.cancel()
    }

    // ── The "Reconnexion automatique" options: a tunnel reconnects only if both are on ──

    private fun assertLeftInErrorWithItsReason(tunnelId: String) {
        val state = manager.getTunnelState(tunnelId)
        assertEquals(TunnelStatus.ERROR, state?.status)
        assertEquals("Connexion SSH perdue", state?.errorMessage, "the reason of the cut is still there")
    }

    @Test
    fun `with the option off in the Settings nothing is attempted and the tunnel keeps its reason`() = runTest {
        autoReconnectSetting = false
        val cfg = config()
        inError(cfg)

        useCase.reconnectErrorTunnels()

        coVerify(exactly = 0) { startTunnel(any()) }
        coVerify(exactly = 0) { liveTunnels.findLive(any()) }
        assertLeftInErrorWithItsReason(cfg.id)
        assertEquals(0L, testScheduler.currentTime, "no wait")
        assertEquals(listOf(cfg.id), manager.getTunnelsForReconnect().map { it.id }, "not held: turning it on again is enough")
    }

    @Test
    fun `with the option off on the host of the tunnel nothing is attempted and the tunnel keeps its reason`() = runTest {
        val cfg = config()
        inError(cfg)
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg, autoReconnect = false)

        useCase.reconnectErrorTunnels()

        coVerify(exactly = 0) { startTunnel(any()) }
        assertLeftInErrorWithItsReason(cfg.id)
        assertEquals(0L, testScheduler.currentTime, "no wait")
        assertEquals(listOf(cfg.id), manager.getTunnelsForReconnect().map { it.id }, "not held: turning it on again is enough")
    }

    @Test
    fun `the option of a host is that host's own, the tunnels of another host are reconnected`() = runTest {
        val optedOut = config(id = "a", localPort = 8001, hostId = "h-off")
        val others = config(id = "b", localPort = 8002, hostId = "h-on")
        inError(optedOut)
        inError(others)
        coEvery { liveTunnels.findLive("a") } returns liveOf(optedOut, autoReconnect = false)
        coEvery { liveTunnels.findLive("b") } returns liveOf(others, autoReconnect = true)
        coEvery { startTunnel(others) } returns SshResult.Success(Unit)

        useCase.reconnectErrorTunnels()

        coVerify(exactly = 1) { startTunnel(others) }
        coVerify(exactly = 0) { startTunnel(optedOut) }
        assertLeftInErrorWithItsReason("a")
    }

    @Test
    fun `with both options on the tunnel is reconnected`() = runTest {
        val cfg = config()
        inError(cfg)
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg, autoReconnect = true)
        coEvery { startTunnel(cfg) } returns SshResult.Success(Unit)

        useCase.reconnectErrorTunnels()

        coVerify(exactly = 1) { startTunnel(cfg) }
    }

    @Test
    fun `turning the Settings option back on makes the tunnel a candidate again at the next event`() = runTest {
        val cfg = config()
        inError(cfg)
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        coEvery { startTunnel(cfg) } returns SshResult.Success(Unit)
        autoReconnectSetting = false
        useCase.reconnectErrorTunnels()
        coVerify(exactly = 0) { startTunnel(any()) }

        autoReconnectSetting = true
        useCase.reconnectErrorTunnels()

        coVerify(exactly = 1) { startTunnel(cfg) }
    }

    @Test
    fun `turning the option of the host back on makes the tunnel a candidate again at the next event`() = runTest {
        val cfg = config()
        inError(cfg)
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg, autoReconnect = false)
        coEvery { startTunnel(cfg) } returns SshResult.Success(Unit)
        useCase.reconnectErrorTunnels()
        coVerify(exactly = 0) { startTunnel(any()) }

        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg, autoReconnect = true)
        useCase.reconnectErrorTunnels()

        coVerify(exactly = 1) { startTunnel(cfg) }
    }

    @Test
    fun `turning the option off during the back-off stops the reconnection at the next step, with the reason back`() = runTest {
        val cfg = config()
        inError(cfg)
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        coEvery { startTunnel(any()) } returns SshResult.Success(Unit)

        val job = launch { useCase.reconnectErrorTunnels() }
        runCurrent()
        assertEquals(TunnelStatus.RECONNECTING, manager.getTunnelState(cfg.id)?.status, "waiting before the attempt")
        autoReconnectSetting = false
        job.join()

        coVerify(exactly = 0) { startTunnel(any()) }
        assertLeftInErrorWithItsReason(cfg.id)
    }

    @Test
    fun `turning the option of the host off after a failed attempt leaves the reason of that attempt`() = runTest {
        val cfg = config()
        inError(cfg)
        var hostOption = true
        coEvery { liveTunnels.findLive(cfg.id) } coAnswers { liveOf(cfg, autoReconnect = hostOption) }
        coEvery { startTunnel(cfg) } coAnswers {
            hostOption = false
            failedStart(cfg, SshErrorCode.HOST_UNREACHABLE, "Délai de connexion dépassé (10 s)")
        }

        useCase.reconnectErrorTunnels()

        coVerify(exactly = 1) { startTunnel(any()) }
        assertEquals(TunnelStatus.ERROR, manager.getTunnelState(cfg.id)?.status)
        assertEquals("Délai de connexion dépassé (10 s)", manager.getTunnelState(cfg.id)?.errorMessage)
    }

    @Test
    fun `a network event does nothing while the option is off in the Settings`() = runTest {
        autoReconnectSetting = false
        val cfg = config()
        inError(cfg)
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        val events = MutableSharedFlow<NetworkEvent>(replay = 1)
        every { networkMonitor.networkEvents } returns events
        val collector = launch { useCase.observe() }

        events.tryEmit(NetworkEvent.Connected)
        advanceUntilIdle()

        coVerify(exactly = 0) { startTunnel(any()) }
        assertLeftInErrorWithItsReason(cfg.id)
        collector.cancel()
    }

    // ── A tunnel that is cut is reconnected at once, without waiting for a network event ──

    /** Runs [block] with [ReconnectTunnelsUseCase.observe] listening, and ends it afterwards. */
    private suspend fun TestScope.whileObserving(block: suspend () -> Unit) {
        val observer = launch { useCase.observe() }
        runCurrent()
        try {
            block()
        } finally {
            observer.cancel()
        }
    }

    /** A tunnel that was running and whose transport drops now: the stop the reconnection listens to. */
    private fun cut(cfg: TunnelConfig, sessionId: String = "s1", uptimeMs: Long = HEALTHY_UPTIME_MS) {
        manager.markStarting(cfg)
        manager.injectActiveForTest(cfg, sessionId)
        managerClockMs += uptimeMs
        manager.simulateUnexpectedStopForTest(cfg.id, "Connexion SSH perdue")
    }

    /** The tunnel the shared start brings up: ACTIVE on a new instance, as `startTunnel` leaves it. */
    private fun comesBackUp(cfg: TunnelConfig): SshResult<Unit> {
        manager.markStarting(cfg)
        manager.injectActiveForTest(cfg, sessionId = "s-back")
        return SshResult.Success(Unit)
    }

    @Test
    fun `a tunnel that is cut is reconnected at once, no network event needed`() = runTest {
        val cfg = config()
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        coEvery { startTunnel(cfg) } returns SshResult.Success(Unit)

        whileObserving {
            cut(cfg)
            runCurrent()
            assertEquals(TunnelStatus.RECONNECTING, manager.getTunnelState(cfg.id)?.status, "under way, first wait")

            advanceUntilIdle()

            coVerify(exactly = 1) { startTunnel(cfg) }
            assertEquals(1_000L, testScheduler.currentTime, "the first wait of the back-off, nothing more")
        }
    }

    @Test
    fun `a cut tunnel gets the same five attempts and the same message with the reason`() = runTest {
        val cfg = config()
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        coEvery { startTunnel(cfg) } coAnswers { failedStart(cfg, SshErrorCode.HOST_UNREACHABLE, "Le serveur n'a pas répondu") }

        whileObserving {
            cut(cfg)
            advanceUntilIdle()

            coVerify(exactly = 5) { startTunnel(cfg) }
            assertEquals(31_000L, testScheduler.currentTime, "1, 2, 4, 8 then 16 seconds")
            val state = manager.getTunnelState(cfg.id)
            assertEquals(TunnelStatus.ERROR, state?.status)
            assertEquals("Reconnexion échouée après 5 tentatives : Le serveur n'a pas répondu", state?.errorMessage)
        }
    }

    @Test
    fun `after the five attempts the tunnel waits for the next network event, which tries again`() = runTest {
        val cfg = config()
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        coEvery { startTunnel(cfg) } coAnswers { failedStart(cfg, SshErrorCode.HOST_UNREACHABLE, "refused") }

        whileObserving {
            cut(cfg)
            advanceUntilIdle()
            coVerify(exactly = 5) { startTunnel(cfg) }
            advanceTimeBy(10 * 60_000L)
            runCurrent()
            coVerify(exactly = 5) { startTunnel(cfg) }

            networkEvents.tryEmit(NetworkEvent.Connected)
            advanceUntilIdle()

            coVerify(exactly = 10) { startTunnel(cfg) }
        }
    }

    @Test
    fun `a network event during the back-off does not start a second reconnection`() = runTest {
        val cfg = config()
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        // Each failed start leaves the tunnel in ERROR for an instant, then the loop says RECONNECTING
        // with the reason until the next attempt: a network event does not list it as a candidate, and
        // the reconnecting guard would stop a second back-off anyway.
        coEvery { startTunnel(cfg) } coAnswers { failedStart(cfg, SshErrorCode.HOST_UNREACHABLE, "refused") }

        whileObserving {
            cut(cfg)
            advanceTimeBy(1_500)
            runCurrent()
            coVerify(exactly = 1) { startTunnel(cfg) }
            assertEquals(TunnelStatus.RECONNECTING, manager.getTunnelState(cfg.id)?.status, "between two attempts")
            assertEquals("refused", manager.getTunnelState(cfg.id)?.errorMessage, "with the reason of the failed attempt")

            networkEvents.tryEmit(NetworkEvent.Connected)
            advanceUntilIdle()

            coVerify(exactly = 5) { startTunnel(cfg) }
            assertEquals(31_000L, testScheduler.currentTime, "one back-off, not two")
        }
    }

    @Test
    fun `a network event and a cut at the same moment give one reconnection`() = runTest {
        val cfg = config()
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        coEvery { startTunnel(cfg) } coAnswers { failedStart(cfg, SshErrorCode.HOST_UNREACHABLE, "refused") }

        whileObserving {
            cut(cfg)
            networkEvents.tryEmit(NetworkEvent.Connected)
            advanceUntilIdle()

            coVerify(exactly = 5) { startTunnel(cfg) }
        }
    }

    @Test
    fun `a network event first, then the cut, gives one reconnection`() = runTest {
        val cfg = config()
        manager.markStarting(cfg)
        manager.injectActiveForTest(cfg, sessionId = "s1")
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        coEvery { startTunnel(cfg) } returns SshResult.Success(Unit)

        whileObserving {
            // The network changes while the transport still looks alive: nothing to reconnect yet.
            networkEvents.tryEmit(NetworkEvent.Connected)
            runCurrent()
            coVerify(exactly = 0) { startTunnel(any()) }

            manager.simulateUnexpectedStopForTest(cfg.id, "Connexion SSH perdue")
            advanceUntilIdle()

            coVerify(exactly = 1) { startTunnel(cfg) }
        }
    }

    @Test
    fun `two tunnels cut together are reconnected side by side, not one after the other`() = runTest {
        val a = config(id = "a", localPort = 8001)
        val b = config(id = "b", localPort = 8002)
        coEvery { liveTunnels.findLive("a") } returns liveOf(a)
        coEvery { liveTunnels.findLive("b") } returns liveOf(b)
        coEvery { startTunnel(any()) } coAnswers {
            val cfg = firstArg<TunnelConfig>()
            failedStart(cfg, SshErrorCode.HOST_UNREACHABLE, "refused")
        }

        whileObserving {
            cut(a, "s1")
            cut(b, "s1")
            advanceUntilIdle()

            coVerify(exactly = 5) { startTunnel(a) }
            coVerify(exactly = 5) { startTunnel(b) }
            assertEquals(31_000L, testScheduler.currentTime, "their back-offs run together")
        }
    }

    @Test
    fun `a non-retryable failure of a cut tunnel stops it at the first attempt and holds it`() = runTest {
        val cfg = config()
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        coEvery { startTunnel(cfg) } returns SshResult.Error(SshErrorCode.AUTH_FAILED, "Authentification échouée")

        whileObserving {
            cut(cfg)
            advanceUntilIdle()

            coVerify(exactly = 1) { startTunnel(cfg) }
            assertEquals(1_000L, testScheduler.currentTime)
            assertEquals("Authentification échouée", manager.getTunnelState(cfg.id)?.errorMessage)
            assertTrue(manager.isReconnectHeld(cfg.id))
            networkEvents.tryEmit(NetworkEvent.Connected)
            advanceUntilIdle()
            coVerify(exactly = 1) { startTunnel(cfg) }
        }
    }

    @Test
    fun `a tunnel stopped by hand is not reconnected, nor when its listener wakes up late`() = runTest {
        val cfg = config()
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        manager.markStarting(cfg)
        manager.injectActiveForTest(cfg, sessionId = "s1")

        whileObserving {
            manager.stopTunnel(cfg.id)
            manager.simulateUnexpectedStopForTest(cfg.id, "socket closed")
            advanceUntilIdle()

            coVerify(exactly = 0) { startTunnel(any()) }
            assertNull(manager.getTunnelState(cfg.id))
        }
    }

    @Test
    fun `stopping a tunnel by hand during its reconnection ends it`() = runTest {
        val cfg = config()
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        coEvery { startTunnel(cfg) } returns SshResult.Success(Unit)

        whileObserving {
            cut(cfg)
            runCurrent()
            runBlocking { manager.stopTunnel(cfg.id) }
            advanceUntilIdle()

            coVerify(exactly = 0) { startTunnel(any()) }
            assertNull(manager.getTunnelState(cfg.id), "the stop sticks")
        }
    }

    @Test
    fun `a tunnel that fails to start is not a stop, nothing reconnects it by itself`() = runTest {
        val cfg = config()
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)

        whileObserving {
            // What the shared start leaves behind when the connection fails: STARTING, then ERROR.
            manager.markStarting(cfg)
            manager.markError(cfg, "Délai de connexion dépassé (10 s)")
            advanceUntilIdle()

            coVerify(exactly = 0) { startTunnel(any()) }
            assertEquals("Délai de connexion dépassé (10 s)", manager.getTunnelState(cfg.id)?.errorMessage)
        }
    }

    @Test
    fun `a cut tunnel whose option is off is left in error with its reason`() = runTest {
        val cfg = config()
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg, autoReconnect = false)

        whileObserving {
            cut(cfg)
            advanceUntilIdle()

            coVerify(exactly = 0) { startTunnel(any()) }
            assertLeftInErrorWithItsReason(cfg.id)
        }
    }

    @Test
    fun `a cut tunnel is not reconnected while the option is off in the Settings`() = runTest {
        autoReconnectSetting = false
        val cfg = config()
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)

        whileObserving {
            cut(cfg)
            advanceUntilIdle()

            coVerify(exactly = 0) { startTunnel(any()) }
            assertLeftInErrorWithItsReason(cfg.id)
        }
    }

    @Test
    fun `a cut tunnel deleted elsewhere is stopped for good, as at a network event`() = runTest {
        val cfg = config()
        coEvery { liveTunnels.findLive(cfg.id) } returns null

        whileObserving {
            cut(cfg)
            advanceUntilIdle()

            coVerify(exactly = 0) { startTunnel(any()) }
            assertNull(manager.getTunnelState(cfg.id))
        }
    }

    @Test
    fun `one cut tunnel failing inside does not end the observation of the others`() = runTest {
        val broken = config(id = "broken", localPort = 8001)
        val healthy = config(id = "healthy", localPort = 8002)
        coEvery { liveTunnels.findLive("broken") } throws IllegalStateException("database closed")
        coEvery { liveTunnels.findLive("healthy") } returns liveOf(healthy)
        coEvery { startTunnel(healthy) } returns SshResult.Success(Unit)

        whileObserving {
            cut(broken)
            cut(healthy)
            advanceUntilIdle()

            coVerify(exactly = 1) { startTunnel(healthy) }
            assertEquals(TunnelStatus.ERROR, manager.getTunnelState("broken")?.status, "flagged, not left reconnecting")
            // Still listening: a later cut is reconnected.
            val later = config(id = "later", localPort = 8003)
            coEvery { liveTunnels.findLive("later") } returns liveOf(later)
            coEvery { startTunnel(later) } returns SshResult.Success(Unit)
            cut(later)
            advanceUntilIdle()
            coVerify(exactly = 1) { startTunnel(later) }
        }
    }

    @Test
    fun `cancelling the observation puts a reconnection under way back to error, a candidate for the next event`() = runTest {
        val cfg = config()
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        val observer = launch { useCase.observe() }
        runCurrent()
        cut(cfg)
        runCurrent()
        assertEquals(TunnelStatus.RECONNECTING, manager.getTunnelState(cfg.id)?.status)

        // The service stops: the scope that listens ends.
        observer.cancel()
        observer.join()

        assertEquals(TunnelStatus.ERROR, manager.getTunnelState(cfg.id)?.status)
        assertEquals(TUNNEL_RECONNECT_INTERRUPTED_MESSAGE, manager.getTunnelState(cfg.id)?.errorMessage)
        assertEquals(listOf(cfg.id), manager.getTunnelsForReconnect().map { it.id })
    }

    // ── The foreground service lives as long as there is a card to reconnect ──

    @Test
    fun `the service stays up through the whole reconnection, pauses and attempts included`() = runTest {
        val cfg = config()
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        coEvery { startTunnel(cfg) } coAnswers { failedStart(cfg, SshErrorCode.HOST_UNREACHABLE, "refused") }
        fun serviceStops() = shouldStopForegroundService(manager.tunnelStates.value, hadTunnels = true)

        whileObserving {
            cut(cfg)
            assertFalse(serviceStops(), "in error just after the cut")
            runCurrent()
            assertFalse(serviceStops(), "reconnecting, first wait")
            for (stepMs in listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L)) {
                advanceTimeBy(stepMs)
                runCurrent()
                assertFalse(serviceStops(), "after a wait of $stepMs ms, between two attempts")
            }
            advanceUntilIdle()
            assertEquals(TunnelStatus.ERROR, manager.getTunnelState(cfg.id)?.status)
            assertFalse(serviceStops(), "gave up: the card is there, the next network event can use the service")

            manager.stopTunnel(cfg.id)
            assertTrue(serviceStops(), "once the user removes the card, the service can end")
        }
    }

    // ── A tunnel that drops again as soon as it is restored is not restored for ever ──

    /** Cuts the tunnel that the reconnection just restored, [uptimeMs] after it came back. */
    private fun cutRestored(cfg: TunnelConfig, uptimeMs: Long) {
        managerClockMs += uptimeMs
        manager.simulateUnexpectedStopForTest(cfg.id, "Connexion SSH perdue")
    }

    @Test
    fun `a tunnel cut again soon after each restoration is answered with 1, 2 and 4 seconds, then not any more`() = runTest {
        val cfg = config()
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        val cutAt = mutableListOf<Long>()
        val startedAt = mutableListOf<Long>()
        coEvery { startTunnel(cfg) } coAnswers {
            startedAt += testScheduler.currentTime
            comesBackUp(cfg)
        }

        whileObserving {
            cutAt += testScheduler.currentTime
            cut(cfg) // after a long healthy run: a first incident, not a repeat
            advanceUntilIdle()
            repeat(3) {
                cutAt += testScheduler.currentTime
                cutRestored(cfg, uptimeMs = 2_000)
                advanceUntilIdle()
            }

            assertEquals(3, startedAt.size, "the fourth cut is not answered: startTunnel is never called for it")
            assertEquals(listOf(1_000L, 2_000L, 4_000L), startedAt.zip(cutAt) { start, cut -> start - cut }, "1, 2 then 4 s")
            val state = manager.getTunnelState(cfg.id)
            assertEquals(TunnelStatus.ERROR, state?.status)
            assertEquals("Le tunnel se coupe dès qu'il est rétabli. Vérifiez le serveur, puis relancez-le.", state?.errorMessage)
            assertEquals(listOf(cfg.id), manager.getTunnelsForReconnect().map { it.id }, "not held: a network connection can re-arm it")
        }
    }

    @Test
    fun `a network connection gives a tunnel that was given up on its chances back`() = runTest {
        val cfg = config()
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        coEvery { startTunnel(cfg) } coAnswers { comesBackUp(cfg) }

        whileObserving {
            cut(cfg)
            advanceUntilIdle()
            repeat(3) {
                cutRestored(cfg, uptimeMs = 2_000)
                advanceUntilIdle()
            }
            coVerify(exactly = 3) { startTunnel(cfg) }

            networkEvents.tryEmit(NetworkEvent.Connected)
            advanceUntilIdle()

            coVerify(exactly = 4) { startTunnel(cfg) }
            assertEquals(TunnelStatus.ACTIVE, manager.getTunnelState(cfg.id)?.status)
            // And the count starts again: the next quick cut is answered, after 2 s (it is the first).
            val before = testScheduler.currentTime
            cutRestored(cfg, uptimeMs = 2_000)
            advanceUntilIdle()
            coVerify(exactly = 5) { startTunnel(cfg) }
            assertEquals(2_000L, testScheduler.currentTime - before)
        }
    }

    @Test
    fun `a cut after a stable run starts the count again`() = runTest {
        val cfg = config()
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        val delays = mutableListOf<Long>()
        var cutTime = 0L
        coEvery { startTunnel(cfg) } coAnswers {
            delays += testScheduler.currentTime - cutTime
            comesBackUp(cfg)
        }

        whileObserving {
            cut(cfg)
            advanceUntilIdle()
            // Two quick cuts, then a restoration that holds for two minutes, then a cut: a new incident.
            for (uptime in listOf(2_000L, 2_000L, 120_000L, 2_000L)) {
                cutTime = testScheduler.currentTime
                cutRestored(cfg, uptimeMs = uptime)
                advanceUntilIdle()
            }

            assertEquals(listOf(1_000L, 2_000L, 4_000L, 1_000L, 2_000L), delays)
        }
    }

    @Test
    fun `the uptime that counts is the one before the cut, a cut at the limit is a stable one`() = runTest {
        val cfg = config()
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        coEvery { startTunnel(cfg) } coAnswers { comesBackUp(cfg) }

        whileObserving {
            cut(cfg)
            advanceUntilIdle()
            repeat(4) {
                cutRestored(cfg, uptimeMs = ReconnectTunnelsUseCase.STABLE_MS)
                advanceUntilIdle()
            }

            coVerify(exactly = 5) { startTunnel(cfg) }
        }
    }

    @Test
    fun `a start by hand gives a tunnel that was given up on its chances back`() = runTest {
        val cfg = config()
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        coEvery { startTunnel(cfg) } coAnswers { comesBackUp(cfg) }

        whileObserving {
            cut(cfg)
            advanceUntilIdle()
            repeat(3) {
                cutRestored(cfg, uptimeMs = 2_000)
                advanceUntilIdle()
            }
            coVerify(exactly = 3) { startTunnel(cfg) }

            // The user starts it again, from the card.
            manager.markStarting(cfg)
            manager.injectActiveForTest(cfg, sessionId = "s-hand")
            val before = testScheduler.currentTime
            managerClockMs += 2_000
            manager.simulateUnexpectedStopForTest(cfg.id, "Connexion SSH perdue")
            advanceUntilIdle()

            coVerify(exactly = 4) { startTunnel(cfg) }
            assertEquals(2_000L, testScheduler.currentTime - before, "the first quick cut of the new run: 2 s")
        }
    }

    @Test
    fun `the cuts of one tunnel do not count for another`() = runTest {
        val flapping = config(id = "flapping", localPort = 8001)
        val steady = config(id = "steady", localPort = 8002)
        coEvery { liveTunnels.findLive("flapping") } returns liveOf(flapping)
        coEvery { liveTunnels.findLive("steady") } returns liveOf(steady)
        coEvery { startTunnel(any()) } coAnswers { comesBackUp(firstArg()) }

        whileObserving {
            cut(flapping, "s1")
            cut(steady, "s2")
            advanceUntilIdle()
            repeat(3) {
                cutRestored(flapping, uptimeMs = 2_000)
                advanceUntilIdle()
            }

            coVerify(exactly = 3) { startTunnel(flapping) }
            coVerify(exactly = 1) { startTunnel(steady) }
            cutRestored(steady, uptimeMs = 2_000)
            advanceUntilIdle()
            coVerify(exactly = 2) { startTunnel(steady) }
        }
    }

    @Test
    fun `a failed attempt does not count as a cut, the back-off of a restoration is the ordinary one`() = runTest {
        val cfg = config()
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        coEvery { startTunnel(cfg) } coAnswers { failedStart(cfg, SshErrorCode.HOST_UNREACHABLE, "refused") }

        whileObserving {
            cut(cfg)
            advanceUntilIdle()

            coVerify(exactly = 5) { startTunnel(cfg) }
            assertEquals(31_000L, testScheduler.currentTime)
        }
    }

    // ── A cut that comes while an attempt is still under way is not lost ──────

    @Test
    fun `a tunnel cut before its attempt is over is reconnected when the attempt ends`() = runTest {
        val cfg = config()
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        val startedAt = mutableListOf<Long>()
        coEvery { startTunnel(cfg) } coAnswers {
            startedAt += testScheduler.currentTime
            if (startedAt.size == 1) {
                // The tunnel is up, and cut again before the attempt returns.
                comesBackUp(cfg)
                cutRestored(cfg, uptimeMs = 2_000)
                delay(500)
                SshResult.Success(Unit)
            } else {
                comesBackUp(cfg)
            }
        }

        whileObserving {
            cut(cfg)
            advanceUntilIdle()

            assertEquals(2, startedAt.size, "the cut was not dropped")
            assertEquals(TunnelStatus.ACTIVE, manager.getTunnelState(cfg.id)?.status)
            // It is a quick cut like another: 2 s before the new attempt, not 1.
            assertEquals(1_000L + 500L + 2_000L, startedAt[1])
        }
    }

    @Test
    fun `the cuts that come during every attempt are answered once each, and the series of quick cuts stops them`() = runTest {
        val cfg = config()
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        coEvery { startTunnel(cfg) } coAnswers {
            comesBackUp(cfg)
            cutRestored(cfg, uptimeMs = 2_000)
            delay(100)
            SshResult.Success(Unit)
        }

        whileObserving {
            cut(cfg)
            advanceUntilIdle()

            coVerify(exactly = 3) { startTunnel(cfg) }
            val state = manager.getTunnelState(cfg.id)
            assertEquals(TunnelStatus.ERROR, state?.status)
            assertEquals("Le tunnel se coupe dès qu'il est rétabli. Vérifiez le serveur, puis relancez-le.", state?.errorMessage)
        }
    }

    @Test
    fun `several cuts during one attempt are answered once, and both count in the series`() = runTest {
        val cfg = config()
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        val startedAt = mutableListOf<Long>()
        coEvery { startTunnel(cfg) } coAnswers {
            startedAt += testScheduler.currentTime
            comesBackUp(cfg)
            if (startedAt.size == 1) {
                cutRestored(cfg, uptimeMs = 2_000)
                delay(100)
                // Up again by another way, and cut again, before the attempt returns.
                comesBackUp(cfg)
                cutRestored(cfg, uptimeMs = 2_000)
                delay(100)
            }
            SshResult.Success(Unit)
        }

        whileObserving {
            cut(cfg)
            advanceUntilIdle()

            assertEquals(2, startedAt.size, "one run for the two cuts")
            // Two quick cuts in the series: 1 s shifted twice, 4 s.
            assertEquals(1_000L + 200L + 4_000L, startedAt[1])
        }
    }

    @Test
    fun `a tunnel cut before its start even returns still counts in the series of quick cuts`() = runTest {
        val cfg = config()
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        coEvery { startTunnel(cfg) } coAnswers {
            // Up and cut again before the start hands the result back, with nothing to wait for in
            // between: the tunnel is no longer active when the reconnection looks for its run, and the
            // reconnection that restored it is over when the cut is counted.
            comesBackUp(cfg)
            cutRestored(cfg, uptimeMs = 2_000)
            SshResult.Success(Unit)
        }

        whileObserving {
            cut(cfg)
            // Bounded: a series that never stops would restore the tunnel every few seconds for ever.
            advanceTimeBy(120_000)
            runCurrent()

            coVerify(exactly = 3) { startTunnel(cfg) }
            val state = manager.getTunnelState(cfg.id)
            assertEquals(TunnelStatus.ERROR, state?.status)
            assertEquals("Le tunnel se coupe dès qu'il est rétabli. Vérifiez le serveur, puis relancez-le.", state?.errorMessage)
        }
    }

    /** What the use case remembers per tunnel id (private state, read for the one test that checks it is let go). */
    private fun remembered(field: String): Set<*> {
        val map = ReconnectTunnelsUseCase::class.java.getDeclaredField(field).apply { isAccessible = true }.get(useCase)
        return when (map) {
            is Map<*, *> -> map.keys
            is Set<*> -> map
            else -> error("$field is neither a map nor a set")
        }
    }

    @Test
    fun `a tunnel deleted elsewhere is forgotten, not remembered for as long as the service lives`() = runTest {
        val cfg = config()
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        coEvery { startTunnel(cfg) } coAnswers { comesBackUp(cfg) }

        whileObserving {
            cut(cfg)
            advanceUntilIdle()
            cutRestored(cfg, uptimeMs = 2_000)
            advanceUntilIdle()
            assertTrue(cfg.id in remembered("reconnectedInstances"), "the run it restored is noted")
            assertTrue(cfg.id in remembered("quickCuts"), "and its quick cut counted")

            // A sync deletes the tunnel; it is cut once more and the reconnection finds it gone.
            coEvery { liveTunnels.findLive(cfg.id) } returns null
            cutRestored(cfg, uptimeMs = 2_000)
            advanceUntilIdle()

            assertFalse(cfg.id in remembered("reconnectedInstances"), "the run noted")
            assertFalse(cfg.id in remembered("quickCuts"), "the series")
            assertFalse(cfg.id in remembered("rerun"), "the cut waiting to be answered")
        }
    }

    @Test
    fun `cuts told while the option is off leave no series behind for when it is turned on`() = runTest {
        val cfg = config()
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        coEvery { startTunnel(cfg) } coAnswers { comesBackUp(cfg) }

        whileObserving {
            autoReconnectSetting = false
            repeat(ReconnectTunnelsUseCase.MAX_QUICK_CUTS) {
                cut(cfg, uptimeMs = 2_000)
                advanceUntilIdle()
            }
            coVerify(exactly = 0) { startTunnel(cfg) }

            autoReconnectSetting = true
            val before = testScheduler.currentTime
            cut(cfg, uptimeMs = 2_000)
            advanceUntilIdle()

            // Nothing was restored while it was off: this is the first quick cut of a series, 2 s.
            coVerify(exactly = 1) { startTunnel(cfg) }
            assertEquals(2_000L, testScheduler.currentTime - before)
        }
    }

    @Test
    fun `a cut told during the wait is answered by the attempt that follows, not by a second one`() = runTest {
        val cfg = config()
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        coEvery { startTunnel(cfg) } coAnswers { comesBackUp(cfg) }

        whileObserving {
            cut(cfg)
            runCurrent()
            assertEquals(TunnelStatus.RECONNECTING, manager.getTunnelState(cfg.id)?.status, "in its first wait")
            advanceTimeBy(500)
            // The user starts it again from the card, and it is cut again, all during the wait.
            manager.markStarting(cfg)
            manager.injectActiveForTest(cfg, sessionId = "s-hand")
            managerClockMs += 2_000
            manager.simulateUnexpectedStopForTest(cfg.id, "Connexion SSH perdue")
            advanceUntilIdle()

            coVerify(exactly = 1) { startTunnel(cfg) }
            assertEquals(TunnelStatus.ACTIVE, manager.getTunnelState(cfg.id)?.status)
        }
    }

    @Test
    fun `a network event during an attempt asks for no second run`() = runTest {
        val cfg = config()
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        coEvery { startTunnel(cfg) } coAnswers {
            // In error from the start of the attempt, which then goes on for a moment: the state in
            // which a network event lists the tunnel as a candidate while the attempt holds it.
            val failure = failedStart(cfg, SshErrorCode.HOST_UNREACHABLE, "refused")
            delay(100)
            failure
        }

        whileObserving {
            cut(cfg)
            advanceTimeBy(1_050)
            runCurrent()
            assertEquals(TunnelStatus.ERROR, manager.getTunnelState(cfg.id)?.status, "mid-attempt")
            networkEvents.tryEmit(NetworkEvent.Connected)
            advanceUntilIdle()

            coVerify(exactly = 5) { startTunnel(cfg) }
        }
    }

    @Test
    fun `an attempt cancelled with its service does not run the cut again`() = runTest {
        val cfg = config()
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        coEvery { startTunnel(cfg) } coAnswers {
            comesBackUp(cfg)
            cutRestored(cfg, uptimeMs = 2_000)
            awaitCancellation()
        }
        val observer = launch { useCase.observe() }
        runCurrent()
        cut(cfg)
        advanceTimeBy(1_100)
        runCurrent()

        observer.cancel()
        observer.join()
        advanceUntilIdle()

        coVerify(exactly = 1) { startTunnel(cfg) }
        assertEquals(TunnelStatus.ERROR, manager.getTunnelState(cfg.id)?.status)
    }

    @Test
    fun `an unreadable setting counts as on, the default of the option`() = runTest {
        val cfg = config()
        inError(cfg)
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        coEvery { startTunnel(cfg) } returns SshResult.Success(Unit)
        val unreadable = mockk<fr.techtical.nextsh.data.preferences.SettingsDataStore>()
        every { unreadable.settingsFlow } returns kotlinx.coroutines.flow.flow { throw java.io.IOException("corrupted") }
        val reader = ReconnectTunnelsUseCase(
            networkMonitor = networkMonitor,
            tunnelManager = manager,
            startTunnel = startTunnel,
            liveTunnels = liveTunnels,
            deletedTunnelStopper = DeletedTunnelStopper(manager, notifier),
            appVisibility = AppVisibility { true },
            authRequiredNotifier = mockk(relaxed = true),
            liveSessions = LiveSessionCheck { false },
            settings = unreadable,
            context = reconnectTestContext(),
        )

        reader.reconnectErrorTunnels()

        coVerify(exactly = 1) { startTunnel(cfg) }
    }

    // ── The reason of the cut is on the card while the tunnel reconnects ──

    @Test
    fun `the card says why the tunnel is reconnecting, from the cut to the attempt`() = runTest {
        val cfg = config()
        manager.markStarting(cfg)
        manager.injectActiveForTest(cfg, sessionId = "s1")
        manager.simulateUnexpectedStopForTest(cfg.id, TUNNEL_NO_ANSWER_MESSAGE)
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        var reasonDuringAttempt: String? = "unset"
        var statusDuringAttempt: TunnelStatus? = null
        coEvery { startTunnel(any()) } coAnswers {
            val attempt = firstArg<TunnelConfig>()
            manager.markStarting(attempt)
            reasonDuringAttempt = manager.getTunnelState(cfg.id)?.errorMessage
            statusDuringAttempt = manager.getTunnelState(cfg.id)?.status
            manager.injectActiveForTest(attempt, sessionId = "s-back")
            SshResult.Success(Unit)
        }

        val job = launch { useCase.reconnectErrorTunnels() }
        runCurrent()
        assertEquals(TunnelStatus.RECONNECTING, manager.getTunnelState(cfg.id)?.status)
        assertEquals(TUNNEL_NO_ANSWER_MESSAGE, manager.getTunnelState(cfg.id)?.errorMessage, "RECONNECTING keeps the reason")

        advanceUntilIdle()
        job.join()

        assertEquals(TunnelStatus.STARTING, statusDuringAttempt)
        assertEquals(TUNNEL_NO_ANSWER_MESSAGE, reasonDuringAttempt, "the attempt keeps it too")
        assertEquals(TunnelStatus.ACTIVE, manager.getTunnelState(cfg.id)?.status)
        assertNull(manager.getTunnelState(cfg.id)?.errorMessage, "and the active card has none")
    }

    // ── No network: no attempt consumed, the card says so ──

    @Test
    fun `without a usable network no attempt is made, the card says the network is unavailable`() = runTest {
        networkUsable = false
        val cfg = config()
        inError(cfg)
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        coEvery { startTunnel(any()) } returns SshResult.Success(Unit)

        val job = launch { useCase.reconnectErrorTunnels() }
        advanceTimeBy(10 * 60_000L)
        runCurrent()

        coVerify(exactly = 0) { startTunnel(any()) }
        assertEquals(TunnelStatus.RECONNECTING, manager.getTunnelState(cfg.id)?.status, "still waiting, not given up")
        assertEquals(TUNNEL_NETWORK_LOST_MESSAGE, manager.getTunnelState(cfg.id)?.errorMessage)
        assertFalse(job.isCompleted)
        job.cancel()
    }

    @Test
    fun `the network coming back restarts the attempts from the initial wait, none having been used`() = runTest {
        networkUsable = false
        val cfg = config()
        inError(cfg)
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        val attemptTimes = mutableListOf<Long>()
        coEvery { startTunnel(any()) } coAnswers {
            attemptTimes += testScheduler.currentTime
            failedStart(firstArg(), SshErrorCode.HOST_UNREACHABLE, "refused")
        }

        val job = launch { useCase.reconnectErrorTunnels() }
        advanceTimeBy(5_000)
        runCurrent()
        coVerify(exactly = 0) { startTunnel(any()) }

        networkUsable = true
        val backAt = testScheduler.currentTime
        networkEvents.tryEmit(NetworkEvent.Connected)
        advanceUntilIdle()
        job.join()

        assertEquals(5, attemptTimes.size, "the five attempts are all still there")
        assertEquals(backAt + 1_000L, attemptTimes.first(), "the first one after the initial wait of 1 s")
    }

    @Test
    fun `waiting for the network gives the five attempts back, the event that ends it being this loop's to use`() = runTest {
        val cfg = config()
        inError(cfg)
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        var calls = 0
        coEvery { startTunnel(any()) } coAnswers {
            calls++
            // The network is lost during the fourth attempt, which fails.
            if (calls == 4) networkUsable = false
            failedStart(firstArg(), SshErrorCode.HOST_UNREACHABLE, "refused")
        }

        val job = launch { useCase.reconnectErrorTunnels() }
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(4, calls)
        assertEquals(TUNNEL_NETWORK_LOST_MESSAGE, manager.getTunnelState(cfg.id)?.errorMessage)

        networkUsable = true
        networkEvents.tryEmit(NetworkEvent.Connected)
        advanceUntilIdle()
        job.join()

        assertEquals(9, calls, "four before the loss, then five again, not one")
        assertEquals(TunnelStatus.ERROR, manager.getTunnelState(cfg.id)?.status)
    }

    @Test
    fun `the final verdict of an earlier loop is not shown as the reason of the next one`() = runTest {
        val cfg = config()
        inError(cfg)
        manager.updateTunnelStatus(cfg.id, TunnelStatus.ERROR, "Reconnexion échouée après 5 tentatives : refused")
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        var reasonDuringAttempt: String? = "unset"
        coEvery { startTunnel(any()) } coAnswers {
            val attempt = firstArg<TunnelConfig>()
            manager.markStarting(attempt)
            reasonDuringAttempt = manager.getTunnelState(cfg.id)?.errorMessage
            manager.injectActiveForTest(attempt, sessionId = "s-back")
            SshResult.Success(Unit)
        }

        val job = launch { useCase.reconnectErrorTunnels() }
        runCurrent()
        assertEquals(TunnelStatus.RECONNECTING, manager.getTunnelState(cfg.id)?.status)
        assertNull(manager.getTunnelState(cfg.id)?.errorMessage, "no contradictory verdict in amber")
        advanceUntilIdle()
        job.join()

        assertNull(reasonDuringAttempt)
    }

    @Test
    fun `the reason of an attempt of this loop is shown while it waits for the next one`() = runTest {
        val cfg = config()
        inError(cfg)
        manager.updateTunnelStatus(cfg.id, TunnelStatus.ERROR, TUNNEL_RECONNECT_INTERRUPTED_MESSAGE)
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        coEvery { startTunnel(any()) } coAnswers { failedStart(firstArg(), SshErrorCode.HOST_UNREACHABLE, "refused") }

        val job = launch { useCase.reconnectErrorTunnels() }
        advanceTimeBy(1_500)
        runCurrent()

        assertEquals("refused", manager.getTunnelState(cfg.id)?.errorMessage)
        job.cancel()
    }

    // ── A cut goes straight to amber, and every amber card has a loop or goes red ──

    @Test
    fun `a cut shows amber at once and starts the loop on a card that is already reconnecting`() = runTest {
        val cfg = config()
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        coEvery { startTunnel(cfg) } returns SshResult.Success(Unit)

        whileObserving {
            cut(cfg)
            // Before anything of the reconnection has run: no red flash.
            assertEquals(TunnelStatus.RECONNECTING, manager.getTunnelState(cfg.id)?.status)
            assertEquals("Connexion SSH perdue", manager.getTunnelState(cfg.id)?.errorMessage)

            advanceUntilIdle()

            coVerify(exactly = 1) { startTunnel(cfg) }
        }
    }

    @Test
    fun `with the option off the cut ends in red with its reason, not in an amber card without a loop`() = runTest {
        autoReconnectSetting = false
        val cfg = config()
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)

        whileObserving {
            cut(cfg)
            runCurrent()
            advanceUntilIdle()

            assertEquals(TunnelStatus.ERROR, manager.getTunnelState(cfg.id)?.status)
            assertEquals("Connexion SSH perdue", manager.getTunnelState(cfg.id)?.errorMessage)
            coVerify(exactly = 0) { startTunnel(any()) }
        }
    }

    @Test
    fun `with the option off for the host the cut ends in red with its reason`() = runTest {
        val cfg = config()
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg, autoReconnect = false)

        whileObserving {
            cut(cfg)
            advanceUntilIdle()

            assertEquals(TunnelStatus.ERROR, manager.getTunnelState(cfg.id)?.status)
            assertEquals("Connexion SSH perdue", manager.getTunnelState(cfg.id)?.errorMessage)
            coVerify(exactly = 0) { startTunnel(any()) }
        }
    }

    @Test
    fun `a network event takes up an orphan reconnecting card, whose announcement was lost`() = runTest {
        val cfg = config()
        inError(cfg)
        manager.updateTunnelStatus(cfg.id, TunnelStatus.RECONNECTING, TUNNEL_SESSION_LOST_MESSAGE)
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        coEvery { startTunnel(cfg) } returns SshResult.Success(Unit)

        useCase.reconnectErrorTunnels()

        coVerify(exactly = 1) { startTunnel(cfg) }
    }

    @Test
    fun `cancelling the observation with a cut not yet answered leaves the card red, not amber`() = runTest {
        val cfg = config()
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        val observer = launch { useCase.observe() }
        runCurrent()

        cut(cfg) // amber at once, the stop waits in the buffer of the collector
        observer.cancel()
        observer.join()

        assertEquals(TunnelStatus.ERROR, manager.getTunnelState(cfg.id)?.status)
        assertEquals("Connexion SSH perdue", manager.getTunnelState(cfg.id)?.errorMessage)
        assertEquals(listOf(cfg.id), manager.getTunnelsForReconnect().map { it.id }, "still a candidate")
    }

    @Test
    fun `the option turned off while an attempt connects ends the card in red when the restored tunnel is cut again`() = runTest {
        val cfg = config()
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        coEvery { startTunnel(any()) } coAnswers {
            val attempt = firstArg<TunnelConfig>()
            manager.markStarting(attempt)
            manager.injectActiveForTest(attempt, sessionId = "s-back")
            autoReconnectSetting = false
            // Cut again before the start returns.
            manager.simulateUnexpectedStopForTest(cfg.id, TUNNEL_SESSION_LOST_MESSAGE)
            SshResult.Success(Unit)
        }

        whileObserving {
            cut(cfg)
            advanceUntilIdle()

            assertEquals(TunnelStatus.ERROR, manager.getTunnelState(cfg.id)?.status)
            assertEquals("Connexion SSH perdue", manager.getTunnelState(cfg.id)?.errorMessage)
            coVerify(exactly = 1) { startTunnel(any()) }
        }
    }

    @Test
    fun `a card that already has a loop never gets a second one from a network event`() = runTest {
        val cfg = config()
        inError(cfg)
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        coEvery { startTunnel(cfg) } returns SshResult.Success(Unit)

        val first = launch { useCase.reconnectErrorTunnels() }
        runCurrent() // its loop waits: the card is RECONNECTING
        val second = launch { useCase.reconnectErrorTunnels() }
        advanceUntilIdle()
        first.join()
        second.join()

        coVerify(exactly = 1) { startTunnel(cfg) }
    }

    @Test
    fun `a second network event during a long reconnection is not lost`() = runTest {
        val a = config(id = "a", localPort = 8001)
        val b = config(id = "b", localPort = 8002)
        inError(a)
        coEvery { liveTunnels.findLive("a") } returns liveOf(a)
        coEvery { liveTunnels.findLive("b") } returns liveOf(b)
        coEvery { startTunnel(a) } coAnswers { failedStart(a, SshErrorCode.HOST_UNREACHABLE, "refused") }
        coEvery { startTunnel(b) } returns SshResult.Success(Unit)

        whileObserving {
            networkEvents.tryEmit(NetworkEvent.Connected) // the pass on `a` lasts 31 s
            runCurrent()
            advanceTimeBy(5_000)
            // `b` fails to start by hand meanwhile: in error, not announced, only the next event finds it.
            manager.markStarting(b)
            manager.markError(b, "refused")
            networkEvents.tryEmit(NetworkEvent.Connected)
            // While `a` is still in its back-off (its loop lasts until 31 s): the second pass does not
            // wait for the first. Checked here, before everything is idle.
            advanceTimeBy(2_000)
            assertEquals(7_000L, testScheduler.currentTime)
            coVerify(exactly = 1) { startTunnel(b) }
            assertEquals(TunnelStatus.RECONNECTING, manager.getTunnelState("a")?.status, "the first pass is still running")
            advanceUntilIdle()

            coVerify(exactly = 1) { startTunnel(b) }
        }
    }

    @Test
    fun `a host on this device is reconnected without a network`() = runTest {
        networkUsable = false
        val cfg = config()
        inError(cfg)
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg, hostname = "127.0.0.1")
        coEvery { startTunnel(any()) } returns SshResult.Success(Unit)

        useCase.reconnectErrorTunnels()

        coVerify(exactly = 1) { startTunnel(any()) }
    }

    @Test
    fun `a tunnel stopped by hand while the loop waits for the network is not brought back`() = runTest {
        networkUsable = false
        val cfg = config()
        inError(cfg)
        coEvery { liveTunnels.findLive(cfg.id) } returns liveOf(cfg)
        coEvery { startTunnel(any()) } returns SshResult.Success(Unit)

        val job = launch { useCase.reconnectErrorTunnels() }
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(TUNNEL_NETWORK_LOST_MESSAGE, manager.getTunnelState(cfg.id)?.errorMessage)
        runBlocking { manager.stopTunnel(cfg.id) }

        networkUsable = true
        networkEvents.tryEmit(NetworkEvent.Connected)
        advanceUntilIdle()
        job.join()

        coVerify(exactly = 0) { startTunnel(any()) }
        assertNull(manager.getTunnelState(cfg.id), "the stop sticks, the network reason does not resurrect the card")
    }
}
