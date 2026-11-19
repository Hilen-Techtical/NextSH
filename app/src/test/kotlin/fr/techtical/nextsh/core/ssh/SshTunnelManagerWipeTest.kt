// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.core.vault.VaultWipeGate
import fr.techtical.nextsh.core.vault.VaultWipeState
import fr.techtical.nextsh.domain.model.TunnelConfig
import fr.techtical.nextsh.domain.model.TunnelStatus
import fr.techtical.nextsh.domain.model.TunnelType
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The tunnels during the "Wipe the vault" action. The closing stops every tunnel for good
 * ([SshTunnelManager.stopAllTunnels]), and what keeps them stopped is that a closed app accepts no start,
 * from whoever asks: the user, the automatic reconnection that was running before the wipe, the automatic
 * start. Otherwise a tunnel would come back, on the old vault, after the erase.
 */
class SshTunnelManagerWipeTest {

    private val gate = VaultWipeGate()
    private val sessions = mockk<SshSessionManager>(relaxed = true)
    private val manager = SshTunnelManager(sessionManager = sessions, wipeGate = gate)

    private fun config(id: String = "t1") = TunnelConfig(
        id = id,
        label = "tunnel-$id",
        hostId = "h1",
        type = TunnelType.LOCAL_FORWARD,
        localPort = 8080,
        remoteHost = "127.0.0.1",
        remotePort = 80,
    )

    private val closedStates = listOf(VaultWipeState.RUNNING, VaultWipeState.FAILED, VaultWipeState.DONE)

    @Test
    fun `a start announced while the app is closed is not announced at all`() {
        for (state in closedStates) {
            gate.set(state)

            manager.markStarting(config())

            assertNull(manager.getTunnelState("t1"), "no card in $state")
        }
    }

    @Test
    fun `a start is announced again once the app is open`() {
        gate.set(VaultWipeState.DONE)
        gate.acknowledgeDone()

        manager.markStarting(config())

        assertEquals(TunnelStatus.STARTING, manager.getTunnelState("t1")?.status)
    }

    @Test
    fun `the reconnection that was running before the wipe finds nothing to start`() {
        val cfg = config()
        manager.markStarting(cfg)
        manager.markError(cfg, "Connection lost")
        assertTrue(manager.isAwaitingReconnect(cfg.id), "a tunnel waiting for the reconnection, before the wipe")
        assertTrue(manager.reserveReconnectStart(cfg), "which the open app would start again")
        manager.markError(cfg, "Connection lost")

        gate.set(VaultWipeState.RUNNING)

        assertFalse(manager.reserveReconnectStart(cfg), "refused: the loop ends instead of reopening a connection")
    }

    @Test
    fun `the automatic start is refused while the app is closed`() {
        assertTrue(manager.reserveAutoStart(config("open")), "open app: reserved")

        for (state in closedStates) {
            gate.set(state)

            assertFalse(manager.reserveAutoStart(config("closed")), "refused in $state")
        }
    }

    @Test
    fun `stopping every tunnel keeps the flags of manual stop, and a start after it does not lift them`() {
        val cfg = config()
        manager.markStarting(cfg)
        manager.injectActiveForTest(cfg, sessionId = "s1")

        manager.stopAllTunnels()
        gate.set(VaultWipeState.RUNNING)
        // What an earlier loop would do: announce a start. The closed app does not lift the exclusion.
        manager.markStarting(cfg)

        assertNull(manager.getTunnelState(cfg.id), "no state: nothing to reconnect")
        assertFalse(manager.isAwaitingReconnect(cfg.id))
        assertFalse(manager.reserveReconnectStart(cfg))
    }

    @Test
    fun `a start that was in flight when every tunnel stopped is abandoned`() {
        val cfg = config()
        manager.markStarting(cfg)

        manager.stopAllTunnels()

        assertTrue(manager.isStartAbandoned(cfg), "it goes no further, it opens nothing and writes nothing")
    }
}
