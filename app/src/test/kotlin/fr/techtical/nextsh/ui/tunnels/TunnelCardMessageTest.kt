// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.tunnels

import fr.techtical.nextsh.domain.model.TunnelConfig
import fr.techtical.nextsh.domain.model.TunnelState
import fr.techtical.nextsh.domain.model.TunnelStatus
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TunnelCardMessageTest {

    private val config = mockk<TunnelConfig>(relaxed = true)

    private fun state(status: TunnelStatus, message: String?) = TunnelState(config, status, message)

    @Test
    fun `no state shows the diagram`() {
        assertEquals(TunnelCardMessage.None, tunnelCardMessage(null))
    }

    @Test
    fun `reconnecting with a reason is amber`() {
        assertEquals(
            TunnelCardMessage.Reconnecting("Reseau indisponible"),
            tunnelCardMessage(state(TunnelStatus.RECONNECTING, "Reseau indisponible")),
        )
    }

    @Test
    fun `starting with a reason is amber`() {
        assertEquals(
            TunnelCardMessage.Reconnecting("raison"),
            tunnelCardMessage(state(TunnelStatus.STARTING, "raison")),
        )
    }

    @Test
    fun `starting launched by the user keeps the diagram`() {
        assertEquals(TunnelCardMessage.None, tunnelCardMessage(state(TunnelStatus.STARTING, null)))
    }

    @Test
    fun `reconnecting without a usable reason keeps the diagram`() {
        assertEquals(TunnelCardMessage.None, tunnelCardMessage(state(TunnelStatus.RECONNECTING, null)))
        assertEquals(TunnelCardMessage.None, tunnelCardMessage(state(TunnelStatus.RECONNECTING, "  ")))
    }

    @Test
    fun `error is red`() {
        assertEquals(
            TunnelCardMessage.Failed("echec"),
            tunnelCardMessage(state(TunnelStatus.ERROR, "echec")),
        )
    }

    @Test
    fun `error without a message keeps the diagram`() {
        assertEquals(TunnelCardMessage.None, tunnelCardMessage(state(TunnelStatus.ERROR, null)))
    }

    @Test
    fun `active and stopped never show a banner even with a stale message`() {
        assertEquals(TunnelCardMessage.None, tunnelCardMessage(state(TunnelStatus.ACTIVE, "reste")))
        assertEquals(TunnelCardMessage.None, tunnelCardMessage(state(TunnelStatus.STOPPED, "reste")))
    }
}
