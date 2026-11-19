// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.domain.model.AuthType
import fr.techtical.nextsh.domain.model.Host
import fr.techtical.nextsh.domain.model.SshErrorCode
import fr.techtical.nextsh.domain.model.TunnelConfig
import fr.techtical.nextsh.domain.model.TunnelStatus
import fr.techtical.nextsh.domain.model.TunnelType
import fr.techtical.nextsh.domain.repository.HostRepository
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.shared.domain.ssh.TunnelSessionProvider
import fr.techtical.nextsh.shared.domain.usecase.StartTunnelUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder
import net.schmizz.sshj.connection.channel.forwarded.RemotePortForwarder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The contract between the shared `StartTunnelUseCase` (module `:shared`, not changed by the Android
 * fixes) and the manager of the app: the use case announces the start with `markStarting` before it
 * asks for a session, then hands the same row to the `start*`, and the manager honours only the
 * start that was announced last (see `SshTunnelManager.markStarting`).
 *
 * The real use case against the real manager; the host repository, the session provider and the SSH
 * client are mocks. The normal chain has to succeed for each type, and Stop has to win over the
 * start, however the connection ends.
 */
class SshTunnelManagerSharedStartContractTest {

    private val host = Host(
        id = "h1",
        label = "Host",
        hostname = "example.org",
        username = "root",
        authType = AuthType.PASSWORD,
        credentialId = "c1",
    )

    private val sessions = mockk<SshSessionManager>(relaxed = true)
    private val release = CountDownLatch(1)
    private val listenerThreads = CopyOnWriteArrayList<Thread>()
    private val sockets = CopyOnWriteArrayList<ServerSocket>()
    private val manager = SshTunnelManager(sessionManager = sessions)
    private val hostRepository = mockk<HostRepository>()

    @AfterEach
    fun cleanup() {
        release.countDown()
        listenerThreads.forEach { it.interrupt() }
        sockets.forEach { runCatching { it.close() } }
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun config(type: TunnelType) = TunnelConfig(
        id = "t1",
        label = "tunnel",
        hostId = host.id,
        type = type,
        localPort = freePort(),
        remoteHost = "127.0.0.1",
        remotePort = 8080,
    )

    /** A client for the three types: a forwarder that blocks as the real one, a remote forwarder that accepts. */
    private fun client(): SSHClient {
        val client = mockk<SSHClient>(relaxed = true)
        val forwarder = mockk<LocalPortForwarder>()
        every { forwarder.listen() } answers {
            listenerThreads += Thread.currentThread()
            try {
                release.await(WAIT_MS, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        every { client.newLocalPortForwarder(any(), any()) } answers {
            sockets += secondArg<ServerSocket>()
            forwarder
        }
        every { client.remotePortForwarder } returns mockk<RemotePortForwarder>(relaxed = true)
        every { sessions.getClient("s1") } returns client
        return client
    }

    private fun useCase(provider: TunnelSessionProvider): StartTunnelUseCase {
        coEvery { hostRepository.getById(host.id) } returns host
        return StartTunnelUseCase(manager, hostRepository, provider)
    }

    @Test
    fun `the normal chain of the shared start succeeds for each type of tunnel`() = runTest {
        for ((index, type) in TunnelType.values().withIndex()) {
            client()
            val startTunnel = useCase { SshResult.Success("s1") }
            val cfg = config(type)

            val result = startTunnel(cfg)

            assertEquals(SshResult.Success(Unit), result, "$type")
            assertEquals(TunnelStatus.ACTIVE, manager.getTunnelState(cfg.id)?.status, "$type")
            assertSame(cfg, manager.activeTunnels.value.getValue(cfg.id).config, "$type: the tunnel carries the row that was started")
            assertFalse(manager.isStartAbandoned(cfg))
            // The running tunnel keeps its reference: only the stops of the types before it released theirs.
            verify(exactly = index) { sessions.release("s1") }

            manager.stopTunnel(cfg.id)
            assertNull(manager.getTunnelState(cfg.id))
        }
    }

    @Test
    fun `a stop during the connection makes the start fade out, the card stays closed and the reference comes back`() = runTest {
        client()
        val connecting = CompletableDeferred<Unit>()
        val startTunnel = useCase {
            connecting.await()
            SshResult.Success("s1")
        }
        val cfg = config(TunnelType.LOCAL_FORWARD)

        val start = async { startTunnel(cfg) }
        runCurrent()
        // The use case announced the start before it asked for a session: the card says STARTING.
        assertEquals(TunnelStatus.STARTING, manager.getTunnelState(cfg.id)?.status)
        assertTrue(manager.stopTunnel(cfg.id) is SshResult.Success)
        assertNull(manager.getTunnelState(cfg.id))
        connecting.complete(Unit) // the connection ends, and the start goes on
        val result = start.await()

        assertTrue(result is SshResult.Error, "$result")
        assertEquals(TUNNEL_START_STOPPED_MESSAGE, (result as SshResult.Error).message)
        assertTrue(manager.isStartWithdrawn(cfg, result))
        assertNull(manager.getTunnelState(cfg.id), "neither ACTIVE nor ERROR")
        assertTrue(manager.activeTunnels.value.isEmpty())
        verify(exactly = 1) { sessions.release("s1") }
        ServerSocket(cfg.localPort).close() // nothing was bound
    }

    @Test
    fun `a connection that fails after the stop leaves no card and says nothing`() = runTest {
        client()
        val connecting = CompletableDeferred<Unit>()
        val startTunnel = useCase {
            connecting.await()
            SshResult.Error(SshErrorCode.AUTH_FAILED, "Authentication failed")
        }
        val cfg = config(TunnelType.LOCAL_FORWARD)

        val start = async { startTunnel(cfg) }
        runCurrent()
        manager.stopTunnel(cfg.id)
        connecting.complete(Unit)
        val result = start.await()

        assertTrue(result is SshResult.Error, "$result")
        assertTrue(manager.isStartWithdrawn(cfg, result as SshResult.Error), "the callers keep quiet")
        assertNull(manager.getTunnelState(cfg.id), "the use case marks the error, the manager does not write it")
    }

    @Test
    fun `a connection that fails without any stop is written as an error`() = runTest {
        client()
        val startTunnel = useCase { SshResult.Error(SshErrorCode.HOST_UNREACHABLE, "Hôte injoignable") }
        val cfg = config(TunnelType.LOCAL_FORWARD)

        val result = startTunnel(cfg)

        assertTrue(result is SshResult.Error, "$result")
        assertFalse(manager.isStartWithdrawn(cfg, result as SshResult.Error))
        assertEquals(TunnelStatus.ERROR, manager.getTunnelState(cfg.id)?.status)
        assertEquals("Hôte injoignable", manager.getTunnelState(cfg.id)?.errorMessage)
    }

    @Test
    fun `the row of a start that ended can be played again, it makes a new start`() = runTest {
        client()
        val startTunnel = useCase { SshResult.Success("s1") }
        val cfg = config(TunnelType.LOCAL_FORWARD)
        assertEquals(SshResult.Success(Unit), startTunnel(cfg))
        manager.stopTunnel(cfg.id)

        val again = startTunnel(cfg)

        assertEquals(SshResult.Success(Unit), again)
        assertEquals(TunnelStatus.ACTIVE, manager.getTunnelState(cfg.id)?.status)
        manager.stopTunnel(cfg.id)
    }

    @Test
    fun `a host that is gone is written as an error for a start nobody announced`() = runTest {
        coEvery { hostRepository.getById(host.id) } returns null
        val startTunnel = StartTunnelUseCase(manager, hostRepository) { SshResult.Success("s1") }
        val cfg = config(TunnelType.LOCAL_FORWARD)

        val result = startTunnel(cfg)

        assertEquals(TUNNEL_HOST_NOT_FOUND_MESSAGE, (result as SshResult.Error).message)
        assertEquals(TunnelStatus.ERROR, manager.getTunnelState(cfg.id)?.status)
        assertEquals(TUNNEL_HOST_NOT_FOUND_MESSAGE, manager.getTunnelState(cfg.id)?.errorMessage)
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
