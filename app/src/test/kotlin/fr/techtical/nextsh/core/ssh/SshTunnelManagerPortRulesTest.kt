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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A tunnel whose ports break [fr.techtical.nextsh.domain.model.TunnelPortRules] is refused at
 * the start, whatever way it got here (the form, the sync, an import): `ServerSocket` throws an
 * IllegalArgumentException for a port out of range, which nothing caught.
 *
 * The check runs before any socket, so the SSH client is a strict mock here: any call on it
 * would fail the test.
 */
class SshTunnelManagerPortRulesTest {

    private val outOfRangeLocal =
        "Port local invalide : choisissez un port entre 1 et 65535"
    private val reservedLocal =
        "Android interdit les ports locaux sous 1024. Choisissez un port plus élevé."
    private val outOfRangeRemote =
        "Port distant invalide : choisissez un port entre 1 et 65535"

    private fun manager(client: SSHClient? = null) = SshTunnelManager(
        sessionManager = mockk(relaxed = true) {
            if (client != null) every { getClient("s1") } returns client
        },
    )

    private fun config(type: TunnelType, localPort: Int, remotePort: Int = 8080) = TunnelConfig(
        id = "t1",
        label = "tunnel-t1",
        hostId = "h1",
        type = type,
        localPort = localPort,
        remoteHost = "127.0.0.1",
        remotePort = remotePort,
    )

    private suspend fun start(manager: SshTunnelManager, client: SSHClient, config: TunnelConfig) =
        when (config.type) {
            TunnelType.LOCAL_FORWARD -> manager.startLocalForward(client, config, "s1")
            TunnelType.REMOTE_FORWARD -> manager.startRemoteForward(client, config, "s1")
            TunnelType.DYNAMIC_SOCKS5 -> manager.startDynamicSocks5(client, config, "s1")
        }

    /** The start is refused: an error result, the tunnel in ERROR with the same message, nothing created. */
    private fun assertRefused(
        manager: SshTunnelManager,
        client: SSHClient,
        result: SshResult<Unit>,
        expectedMessage: String,
        label: String,
    ) {
        assertTrue(result is SshResult.Error, "$label: $result")
        result as SshResult.Error
        assertEquals(SshErrorCode.UNKNOWN, result.code, label)
        assertEquals(expectedMessage, result.message, label)

        val state = manager.tunnelStates.value["t1"]
        assertEquals(TunnelStatus.ERROR, state?.status, label)
        assertEquals(expectedMessage, state?.errorMessage, label)
        assertTrue(manager.activeTunnels.value.isEmpty(), "$label: no active tunnel")
        verify { client wasNot Called }
    }

    @Test
    fun `a local forward with a local port out of range is refused without an exception`() = runTest {
        for (port in listOf(0, -1, 65536, 70000, Int.MAX_VALUE, Int.MIN_VALUE)) {
            val client = mockk<SSHClient>()
            val manager = manager()
            val result = start(manager, client, config(TunnelType.LOCAL_FORWARD, port))
            assertRefused(manager, client, result, outOfRangeLocal, "local forward, local port $port")
        }
    }

    @Test
    fun `a SOCKS5 proxy with a local port out of range is refused without an exception`() = runTest {
        for (port in listOf(0, -1, 65536, 70000)) {
            val client = mockk<SSHClient>()
            val manager = manager()
            val result = start(manager, client, config(TunnelType.DYNAMIC_SOCKS5, port, remotePort = 0))
            assertRefused(manager, client, result, outOfRangeLocal, "SOCKS5, local port $port")
        }
    }

    @Test
    fun `a remote forward with a local port out of range is refused without an exception`() = runTest {
        for (port in listOf(0, -1, 65536, 70000)) {
            val client = mockk<SSHClient>()
            val manager = manager()
            val result = start(manager, client, config(TunnelType.REMOTE_FORWARD, port))
            assertRefused(manager, client, result, outOfRangeLocal, "remote forward, local port $port")
        }
    }

    @Test
    fun `a local port below 1024 is refused with the Android message, not port in use`() = runTest {
        for (type in listOf(TunnelType.LOCAL_FORWARD, TunnelType.DYNAMIC_SOCKS5)) {
            for (port in listOf(1, 22, 80, 443, 1023)) {
                val client = mockk<SSHClient>()
                val manager = manager()
                val result = start(manager, client, config(type, port))
                assertRefused(manager, client, result, reservedLocal, "$type, local port $port")
                assertNotEquals(SshErrorCode.PORT_IN_USE, (result as SshResult.Error).code)
            }
        }
    }

    @Test
    fun `a remote port out of range is refused for a local and a remote forward`() = runTest {
        for (type in listOf(TunnelType.LOCAL_FORWARD, TunnelType.REMOTE_FORWARD)) {
            for (port in listOf(0, -1, 65536, 70000)) {
                val client = mockk<SSHClient>()
                val manager = manager()
                val result = start(manager, client, config(type, localPort = 8080, remotePort = port))
                assertRefused(manager, client, result, outOfRangeRemote, "$type, remote port $port")
            }
        }
    }

    @Test
    fun `the local port is reported before the remote port`() = runTest {
        val client = mockk<SSHClient>()
        val manager = manager()
        val result = start(manager, client, config(TunnelType.LOCAL_FORWARD, localPort = 80, remotePort = 70000))
        assertRefused(manager, client, result, reservedLocal, "both ports wrong")
    }

    @Test
    fun `the refusal messages carry neither a port nor an identifier`() = runTest {
        val cases = listOf(
            config(TunnelType.LOCAL_FORWARD, localPort = 70000),
            config(TunnelType.LOCAL_FORWARD, localPort = 80),
            config(TunnelType.LOCAL_FORWARD, localPort = 8080, remotePort = 70000),
        )
        for (cfg in cases) {
            val manager = manager()
            val result = start(manager, mockk(), cfg) as SshResult.Error
            val forbidden = listOf(cfg.localPort.toString(), cfg.remotePort.toString(), cfg.label, cfg.remoteHost)
            for (value in forbidden) {
                assertFalse(result.message.contains(value), "'$value' in '${result.message}'")
            }
        }
    }

    @Test
    fun `the refusal messages fit the two lines of the tunnel card`() = runTest {
        // TunnelListScreen cuts the error banner of a card after two lines at 11 sp.
        val cases = listOf(
            config(TunnelType.LOCAL_FORWARD, localPort = 70000),
            config(TunnelType.LOCAL_FORWARD, localPort = 80),
            config(TunnelType.LOCAL_FORWARD, localPort = 8080, remotePort = 70000),
        )
        for (cfg in cases) {
            val result = start(manager(), mockk(), cfg) as SshResult.Error
            assertTrue(result.message.length <= 80, "${result.message.length} characters: '${result.message}'")
        }
    }

    @Test
    fun `the start through the session id is refused too`() = runTest {
        val client = mockk<SSHClient>()

        for (type in TunnelType.entries) {
            val manager = manager(client)
            val cfg = config(type, localPort = 70000)
            val result = when (type) {
                TunnelType.LOCAL_FORWARD -> manager.startLocalForward(cfg, "s1")
                TunnelType.REMOTE_FORWARD -> manager.startRemoteForward(cfg, "s1")
                TunnelType.DYNAMIC_SOCKS5 -> manager.startDynamicForward(cfg, "s1")
            }
            assertRefused(manager, client, result, outOfRangeLocal, "$type through the session id")
        }
    }

    @Test
    fun `a remote forward with local port 80 is not refused, the app only connects to it`() = runTest {
        val client = mockk<SSHClient>(relaxed = true)
        val manager = manager()

        val result = start(manager, client, config(TunnelType.REMOTE_FORWARD, localPort = 80, remotePort = 8080))

        assertTrue(result is SshResult.Success, "$result")
        assertEquals(TunnelStatus.ACTIVE, manager.tunnelStates.value["t1"]?.status)
        manager.stopAllTunnels()
    }

    @Test
    fun `a tunnel refused for its port starts once the same tunnel is corrected`() = runTest {
        val client = mockk<SSHClient>(relaxed = true)
        val manager = manager()

        val refused = start(manager, client, config(TunnelType.REMOTE_FORWARD, localPort = 70000))
        assertTrue(refused is SshResult.Error, "$refused")
        assertEquals(TunnelStatus.ERROR, manager.tunnelStates.value["t1"]?.status)

        // Same id, as after the user edits the ports of the tunnel and starts it again.
        val corrected = start(manager, client, config(TunnelType.REMOTE_FORWARD, localPort = 3000))

        assertTrue(corrected is SshResult.Success, "$corrected")
        val state = manager.tunnelStates.value["t1"]
        assertEquals(TunnelStatus.ACTIVE, state?.status)
        assertNull(state?.errorMessage, "the refusal message must not outlive the corrected start")
        assertEquals(3000, state?.config?.localPort)
        manager.stopAllTunnels()
    }

    // ── Reconnection ─────────────────────────────────────────────────────────

    @Test
    fun `a tunnel refused for its ports is not offered to the reconnection`() = runTest {
        val manager = manager()
        val bad = config(TunnelType.LOCAL_FORWARD, localPort = 80)

        start(manager, mockk(), bad)

        assertEquals(TunnelStatus.ERROR, manager.tunnelStates.value["t1"]?.status)
        assertTrue(manager.getTunnelsForReconnect().isEmpty(), "no reconnection can succeed, it would only hide the message")
    }

    @Test
    fun `a tunnel in error with usable ports stays offered to the reconnection`() = runTest {
        val manager = manager()
        val good = config(TunnelType.LOCAL_FORWARD, localPort = 8080)

        manager.markError(good, "Connexion SSH perdue")

        assertEquals(listOf("t1"), manager.getTunnelsForReconnect().map { it.id })
        assertNull(manager.activeTunnels.value["t1"])
    }
}
