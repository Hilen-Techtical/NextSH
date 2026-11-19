// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.service

import fr.techtical.nextsh.domain.model.TunnelConfig
import fr.techtical.nextsh.domain.model.TunnelState
import fr.techtical.nextsh.domain.model.TunnelStatus
import fr.techtical.nextsh.domain.model.TunnelType
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * When the foreground service ends. It hosts the automatic reconnection, which has to outlive the
 * moments when no tunnel is running: a tunnel in error waits for its next attempt, or for the next
 * network event, and a service that stopped then would take the reconnection with it.
 */
class TunnelForegroundServiceTest {

    private fun state(id: String, status: TunnelStatus) = TunnelState(
        config = TunnelConfig(
            id = id,
            label = "tunnel-$id",
            hostId = "h1",
            type = TunnelType.LOCAL_FORWARD,
            localPort = 8080,
            remoteHost = "127.0.0.1",
            remotePort = 80,
        ),
        status = status,
    )

    private fun statesOf(vararg statuses: TunnelStatus): Map<String, TunnelState> =
        statuses.withIndex().associate { (i, status) -> "t$i" to state("t$i", status) }

    @Test
    fun `a tunnel in any state, an error or a reconnection included, keeps the service up`() {
        for (status in TunnelStatus.entries) {
            assertFalse(
                shouldStopForegroundService(statesOf(status), hadTunnels = true),
                "a tunnel in $status is a card the reconnection still serves",
            )
        }
    }

    @Test
    fun `tunnels that all failed keep the service up, the next network event needs it`() {
        assertFalse(shouldStopForegroundService(statesOf(TunnelStatus.ERROR, TunnelStatus.ERROR), hadTunnels = true))
    }

    @Test
    fun `the service ends once the last card is gone`() {
        assertTrue(shouldStopForegroundService(emptyMap(), hadTunnels = true))
    }

    @Test
    fun `a service that has not seen a tunnel yet does not end on the empty table before the first one`() {
        assertFalse(shouldStopForegroundService(emptyMap(), hadTunnels = false))
    }
}
