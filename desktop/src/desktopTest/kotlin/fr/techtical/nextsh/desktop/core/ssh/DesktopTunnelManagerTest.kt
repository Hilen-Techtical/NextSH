// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.ssh

import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.SshErrorCode
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.shared.domain.model.TunnelConfig
import fr.techtical.nextsh.shared.domain.model.TunnelStatus
import fr.techtical.nextsh.shared.domain.model.TunnelType
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder
import net.schmizz.sshj.connection.channel.direct.Parameters
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [DesktopTunnelManager].
 *
 * We mock the SSHJ [SSHClient] entirely: the real `newLocalPortForwarder`
 * returns a `LocalPortForwarder` that binds natively in tests and pollutes
 * ports; we stub it with a relaxed mock whose `listen()` blocks (we don't
 * actually forward any bytes) so the `scope.launch` inside the manager can
 * park without progressing.
 *
 * Scenarios covered :
 *  - LOCAL_FORWARD START success → STARTING → ACTIVE → STOPPED
 *  - START failure (factory returns Error) → STARTING → ERROR
 *  - PORT_IN_USE detected before factory is called (port already bound)
 *  - REMOTE_FORWARD happy path (using mocked remotePortForwarder)
 *  - DYNAMIC_SOCKS5 happy path (SOCKS5 server starts on local port)
 *  - stop on unknown id returns Success (idempotent)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopTunnelManagerTest {

    private val testHost = Host(
        id = "host-1",
        label = "Test Host",
        hostname = "test.example.com",
        port = 22,
        username = "user",
        authType = AuthType.PASSWORD,
        credentialId = "cred-1",
    )

    private val scope = CoroutineScope(UnconfinedTestDispatcher() + SupervisorJob())

    private val openedServerSockets = mutableListOf<ServerSocket>()

    @AfterTest
    fun tearDown() {
        openedServerSockets.forEach { try { it.close() } catch (_: Exception) {} }
        openedServerSockets.clear()
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    // ── LOCAL_FORWARD ────────────────────────────────────────────────────────

    @Test
    fun `startTunnel LOCAL_FORWARD emits STARTING then ACTIVE on success`() = runTest(UnconfinedTestDispatcher()) {
        val port = freePort()
        val config = TunnelConfig(
            id = "tun-1", label = "local-tun", hostId = testHost.id,
            type = TunnelType.LOCAL_FORWARD,
            localPort = port, remoteHost = "127.0.0.1", remotePort = 80,
        )

        val mockClient = mockk<SSHClient>(relaxed = true)
        // Return a relaxed LocalPortForwarder: `listen()` is called in a coroutine,
        // we don't need it to do anything.
        val paramsSlot = slot<Parameters>()
        val forwarderMock = mockk<LocalPortForwarder>(relaxed = true)
        every { mockClient.newLocalPortForwarder(capture(paramsSlot), any()) } returns forwarderMock

        val manager = DesktopTunnelManager(scope) { _, _ -> SshResult.Success(mockClient) }

        val result = manager.startTunnel(config) { _ -> testHost }

        assertTrue(result is SshResult.Success, "start must succeed, got $result")
        assertEquals(TunnelStatus.ACTIVE, manager.getTunnelState("tun-1")?.status)
        assertEquals(port, paramsSlot.captured.localPort)
        assertTrue(manager.activeIds().contains("tun-1"))

        // Stop
        val stopResult = manager.stopTunnel("tun-1")
        assertTrue(stopResult is SshResult.Success)
        assertEquals(TunnelStatus.STOPPED, manager.getTunnelState("tun-1")?.status)
        assertFalse(manager.activeIds().contains("tun-1"))
    }

    @Test
    fun `startTunnel fails with ERROR status when factory returns error`() = runTest(UnconfinedTestDispatcher()) {
        val port = freePort()
        val config = TunnelConfig(
            id = "tun-err", label = "fail", hostId = testHost.id,
            type = TunnelType.LOCAL_FORWARD,
            localPort = port, remoteHost = "127.0.0.1", remotePort = 80,
        )

        val manager = DesktopTunnelManager(scope) { _, _ ->
            SshResult.Error(SshErrorCode.AUTH_FAILED, "boom")
        }

        val result = manager.startTunnel(config) { _ -> testHost }
        assertTrue(result is SshResult.Error)
        assertEquals(SshErrorCode.AUTH_FAILED, result.code)
        assertEquals(TunnelStatus.ERROR, manager.getTunnelState("tun-err")?.status)
        assertEquals("boom", manager.getTunnelState("tun-err")?.errorMessage)
    }

    @Test
    fun `startTunnel detects PORT_IN_USE before opening SSH client`() = runTest(UnconfinedTestDispatcher()) {
        val squatter = ServerSocket(0)
        openedServerSockets += squatter
        val config = TunnelConfig(
            id = "tun-pbusy", label = "port-busy", hostId = testHost.id,
            type = TunnelType.LOCAL_FORWARD,
            localPort = squatter.localPort, remoteHost = "127.0.0.1", remotePort = 80,
        )

        var factoryCalled = false
        val manager = DesktopTunnelManager(scope) { _, _ ->
            factoryCalled = true
            SshResult.Success(mockk(relaxed = true))
        }

        val result = manager.startTunnel(config) { _ -> testHost }

        assertTrue(result is SshResult.Error)
        assertEquals(SshErrorCode.PORT_IN_USE, result.code)
        assertFalse(factoryCalled, "factory must NOT be called if the port is already bound")
        assertEquals(TunnelStatus.ERROR, manager.getTunnelState("tun-pbusy")?.status)
    }

    // ── REMOTE_FORWARD ───────────────────────────────────────────────────────

    @Test
    fun `startTunnel REMOTE_FORWARD binds remote forwarder`() = runTest(UnconfinedTestDispatcher()) {
        val config = TunnelConfig(
            id = "tun-remote", label = "remote", hostId = testHost.id,
            type = TunnelType.REMOTE_FORWARD,
            localPort = 22, remoteHost = "0.0.0.0", remotePort = 8080,
        )

        val mockClient = mockk<SSHClient>(relaxed = true)
        // `remotePortForwarder.bind` returns a `RemotePortForwarder.Forward` in SSHJ:
        // the relaxed mock auto-returns a default mock of that type, so we only
        // need to wire the getter. `justRun` would force Unit and trigger a
        // ClassCastException at the call site.
        val mockRemoteForwarder = mockk<net.schmizz.sshj.connection.channel.forwarded.RemotePortForwarder>(relaxed = true)
        every { mockClient.remotePortForwarder } returns mockRemoteForwarder

        val manager = DesktopTunnelManager(scope) { _, _ -> SshResult.Success(mockClient) }

        val result = manager.startTunnel(config) { _ -> testHost }
        assertTrue(result is SshResult.Success, "remote forward should succeed, got $result")
        assertEquals(TunnelStatus.ACTIVE, manager.getTunnelState("tun-remote")?.status)

        val stopResult = manager.stopTunnel("tun-remote")
        assertTrue(stopResult is SshResult.Success)
    }

    // ── SOCKS5 ───────────────────────────────────────────────────────────────

    @Test
    fun `startTunnel DYNAMIC_SOCKS5 starts server on local port`() = runTest(UnconfinedTestDispatcher()) {
        val port = freePort()
        val config = TunnelConfig(
            id = "tun-socks5", label = "socks5", hostId = testHost.id,
            type = TunnelType.DYNAMIC_SOCKS5,
            localPort = port, remoteHost = "0.0.0.0", remotePort = 0,
        )
        val mockClient = mockk<SSHClient>(relaxed = true)

        val manager = DesktopTunnelManager(scope) { _, _ -> SshResult.Success(mockClient) }

        val result = manager.startTunnel(config) { _ -> testHost }
        assertTrue(result is SshResult.Success, "socks5 start should succeed, got $result")
        assertEquals(TunnelStatus.ACTIVE, manager.getTunnelState("tun-socks5")?.status)

        // Port is now bound: a fresh bind should fail (proves the SOCKS5 server runs).
        var boundPortFree = false
        try { ServerSocket(port).close(); boundPortFree = true } catch (_: Exception) {}
        assertFalse(boundPortFree, "SOCKS5 server should have bound the port")

        val stopResult = manager.stopTunnel("tun-socks5")
        assertTrue(stopResult is SshResult.Success)
        // After stop the port is free again.
        try { ServerSocket(port).close(); boundPortFree = true } catch (_: Exception) {}
        assertTrue(boundPortFree, "SOCKS5 server should have released the port after stop")
    }

    // ── stop idempotency ─────────────────────────────────────────────────────

    @Test
    fun `stopTunnel on unknown id returns Success and clears any lingering state`() = runTest(UnconfinedTestDispatcher()) {
        val manager = DesktopTunnelManager(scope) { _, _ -> SshResult.Success(mockk(relaxed = true)) }
        val result = manager.stopTunnel("nope")
        assertTrue(result is SshResult.Success, "stop on unknown id must be idempotent Success, got $result")
        assertNull(manager.getTunnelState("nope"))
    }

    @Test
    fun `shared interface startLocalForward errors out, use service instead`() = runTest(UnconfinedTestDispatcher()) {
        val manager = DesktopTunnelManager(scope) { _, _ -> SshResult.Success(mockk(relaxed = true)) }
        val result = manager.startLocalForward(
            TunnelConfig(
                id = "x", label = "x", hostId = "h", type = TunnelType.LOCAL_FORWARD,
                localPort = 0, remoteHost = "", remotePort = 0,
            ),
            sessionId = "session-x",
        )
        assertTrue(result is SshResult.Error)
    }
}
