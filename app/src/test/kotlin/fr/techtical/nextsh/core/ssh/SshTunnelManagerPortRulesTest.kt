// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.domain.model.SshErrorCode
import fr.techtical.nextsh.domain.model.TunnelConfig
import fr.techtical.nextsh.domain.model.TunnelStatus
import fr.techtical.nextsh.domain.model.TunnelType
import fr.techtical.nextsh.shared.domain.model.SshResult
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.forwarded.RemotePortForwarder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A tunnel whose ports break [fr.techtical.nextsh.domain.model.TunnelPortRules] is refused at the
 * start, whatever way it got here (the form, the sync, an import): `ServerSocket` throws an
 * IllegalArgumentException for a port out of range, which nothing caught.
 *
 * The starts are the real `start*` of [SshTunnelManager], announced the way the shared start use
 * case announces them. The check runs before any socket, so the SSH client is a strict mock here:
 * any call on it would fail the test. The caller hands over a reference on the session, which a
 * refusal must give back. The refusal as such (text, hold, a start that a stop caught) is covered by
 * [SshTunnelManagerStopDuringStartTest]; this class covers the values of the ports, for each type.
 */
class SshTunnelManagerPortRulesTest {

    /** One manager, its session manager and a strict SSH client, fresh for each start. */
    private class Fixture(val client: SSHClient = mockk()) {
        val sessions = mockk<SshSessionManager>(relaxed = true).also {
            every { it.getClient("s1") } returns client
        }
        val manager = SshTunnelManager(sessionManager = sessions)
    }

    private fun config(type: TunnelType, localPort: Int, remotePort: Int = 8080) = TunnelConfig(
        id = "t1",
        label = "tunnel-t1",
        hostId = "h1",
        type = type,
        localPort = localPort,
        remoteHost = "127.0.0.1",
        remotePort = remotePort,
    )

    private suspend fun Fixture.start(config: TunnelConfig): SshResult<Unit> = when (config.type) {
        TunnelType.LOCAL_FORWARD -> manager.startLocalAnnounced(config, "s1")
        TunnelType.REMOTE_FORWARD -> manager.startRemoteAnnounced(config, "s1")
        TunnelType.DYNAMIC_SOCKS5 -> manager.startDynamicAnnounced(config, "s1")
    }

    /**
     * The start is refused: an error result, the tunnel in ERROR with the same message and held out
     * of the reconnection, nothing created, the SSH client never used, the reference given back.
     */
    private fun assertRefused(fixture: Fixture, result: SshResult<Unit>, expectedMessage: String, label: String) {
        assertTrue(result is SshResult.Error, "$label: $result")
        result as SshResult.Error
        assertEquals(SshErrorCode.UNKNOWN, result.code, label)
        assertEquals(expectedMessage, result.message, label)

        val state = fixture.manager.tunnelStates.value["t1"]
        assertEquals(TunnelStatus.ERROR, state?.status, label)
        assertEquals(expectedMessage, state?.errorMessage, label)
        assertTrue(fixture.manager.activeTunnels.value.isEmpty(), "$label: no active tunnel")
        assertTrue(fixture.manager.isReconnectHeld("t1"), "$label: a port does not mend by itself")
        assertTrue(
            fixture.manager.getTunnelsForReconnect().isEmpty(),
            "$label: no reconnection can succeed, it would only hide the message",
        )
        verify { fixture.client wasNot Called }
        verify(exactly = 1) { fixture.sessions.release("s1") }
    }

    @Test
    fun `a local port out of range is refused for each type without an exception`() = runTest {
        for (type in TunnelType.entries) {
            for (port in listOf(0, -1, 65536, 70000, Int.MAX_VALUE, Int.MIN_VALUE)) {
                val fixture = Fixture()
                val result = fixture.start(config(type, port, remotePort = if (type == TunnelType.DYNAMIC_SOCKS5) 0 else 8080))
                assertRefused(fixture, result, TUNNEL_LOCAL_PORT_INVALID_MESSAGE, "$type, local port $port")
            }
        }
    }

    @Test
    fun `a local port below 1024 is refused with the Android reason, not as a port in use`() = runTest {
        for (type in listOf(TunnelType.LOCAL_FORWARD, TunnelType.DYNAMIC_SOCKS5)) {
            for (port in listOf(1, 22, 80, 443, 1023)) {
                val fixture = Fixture()
                val result = fixture.start(config(type, port))
                assertRefused(fixture, result, TUNNEL_LOCAL_PORT_RESERVED_MESSAGE, "$type, local port $port")
                assertNotEquals(SshErrorCode.PORT_IN_USE, (result as SshResult.Error).code)
            }
        }
    }

    @Test
    fun `a remote port out of range is refused for a local and a remote forward`() = runTest {
        for (type in listOf(TunnelType.LOCAL_FORWARD, TunnelType.REMOTE_FORWARD)) {
            for (port in listOf(0, -1, 65536, 70000)) {
                val fixture = Fixture()
                val result = fixture.start(config(type, localPort = 8080, remotePort = port))
                assertRefused(fixture, result, TUNNEL_REMOTE_PORT_INVALID_MESSAGE, "$type, remote port $port")
            }
        }
    }

    @Test
    fun `the local port is reported before the remote port`() = runTest {
        val fixture = Fixture()
        val result = fixture.start(config(TunnelType.LOCAL_FORWARD, localPort = 80, remotePort = 70000))
        assertRefused(fixture, result, TUNNEL_LOCAL_PORT_RESERVED_MESSAGE, "both ports wrong")
    }

    @Test
    fun `a remote forward with local port 80 is not refused, the app only connects to it`() = runTest {
        val fixture = Fixture(client = clientThatAcceptsRemoteForwards())

        val result = fixture.start(config(TunnelType.REMOTE_FORWARD, localPort = 80, remotePort = 8080))

        assertTrue(result is SshResult.Success, "$result")
        assertEquals(TunnelStatus.ACTIVE, fixture.manager.tunnelStates.value["t1"]?.status)
        fixture.manager.stopAllTunnels()
    }

    @Test
    fun `a tunnel refused for its port starts once the same tunnel is corrected`() = runTest {
        val fixture = Fixture(client = clientThatAcceptsRemoteForwards())

        val refused = fixture.start(config(TunnelType.REMOTE_FORWARD, localPort = 70000))
        assertTrue(refused is SshResult.Error, "$refused")
        assertEquals(TunnelStatus.ERROR, fixture.manager.tunnelStates.value["t1"]?.status)
        assertTrue(fixture.manager.isReconnectHeld("t1"))

        // Same id, as after the user edits the ports of the tunnel and starts it again.
        val corrected = fixture.start(config(TunnelType.REMOTE_FORWARD, localPort = 3000))

        assertTrue(corrected is SshResult.Success, "$corrected")
        val state = fixture.manager.tunnelStates.value["t1"]
        assertEquals(TunnelStatus.ACTIVE, state?.status)
        assertNull(state?.errorMessage, "the refusal message must not outlive the corrected start")
        assertEquals(3000, state?.config?.localPort)
        assertFalse(fixture.manager.isReconnectHeld("t1"), "a manual start lifts the hold of the refusal")
        fixture.manager.stopAllTunnels()
    }

    private fun clientThatAcceptsRemoteForwards(): SSHClient = mockk(relaxed = true) {
        every { remotePortForwarder } returns mockk<RemotePortForwarder>(relaxed = true)
    }
}
