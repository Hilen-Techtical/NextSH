// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.tunnels

import fr.techtical.nextsh.shared.domain.model.TunnelStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The tone of the line under the head of a tunnel card ([cardMessageTone]). */
class TunnelCardMessageTest {

    @Test
    fun `an ERROR with a text is shown as an error`() {
        assertEquals(CardMessageTone.ERROR, cardMessageTone(TunnelStatus.ERROR, "Authentification refusee."))
    }

    @Test
    fun `a RECONNECTING with a text is shown as pending`() {
        assertEquals(CardMessageTone.PENDING, cardMessageTone(TunnelStatus.RECONNECTING, "Le serveur ne repond plus."))
    }

    @Test
    fun `a STARTING with a text is shown as pending`() {
        assertEquals(CardMessageTone.PENDING, cardMessageTone(TunnelStatus.STARTING, "Le serveur ne repond plus."))
    }

    @Test
    fun `a RECONNECTING without a text or with a blank one shows nothing`() {
        assertNull(cardMessageTone(TunnelStatus.RECONNECTING, null))
        assertNull(cardMessageTone(TunnelStatus.RECONNECTING, ""))
        assertNull(cardMessageTone(TunnelStatus.RECONNECTING, "   "))
    }

    @Test
    fun `an ACTIVE or STOPPED tunnel never shows a text, even a left one`() {
        assertNull(cardMessageTone(TunnelStatus.ACTIVE, "left behind"))
        assertNull(cardMessageTone(TunnelStatus.STOPPED, "left behind"))
    }
}
