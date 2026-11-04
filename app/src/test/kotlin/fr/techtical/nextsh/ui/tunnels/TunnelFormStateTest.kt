// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.tunnels

import fr.techtical.nextsh.domain.model.TunnelPortProblem
import fr.techtical.nextsh.domain.model.TunnelType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The port rules as the form applies them: when the save button is enabled, and which message
 * goes under which field. The rules themselves are covered by `TunnelPortRulesTest`.
 */
class TunnelFormStateTest {

    private fun form(type: TunnelType, localPort: String, remotePort: String = "80") = TunnelFormState(
        label = "Tunnel",
        hostId = "host-1",
        type = type,
        localPort = localPort,
        remoteHost = "127.0.0.1",
        remotePort = remotePort,
    )

    // ── isValid ──────────────────────────────────────────────────────────────

    @Test
    fun `local port 80 is refused for a local forward and for SOCKS5`() {
        assertFalse(form(TunnelType.LOCAL_FORWARD, "80").isValid)
        assertFalse(form(TunnelType.DYNAMIC_SOCKS5, "80").isValid)
    }

    @Test
    fun `local port 80 is accepted for a remote forward, the app only connects to it`() {
        assertTrue(form(TunnelType.REMOTE_FORWARD, "80").isValid)
        assertTrue(form(TunnelType.REMOTE_FORWARD, "1").isValid)
    }

    @Test
    fun `a local port out of range is refused for every type`() {
        for (type in TunnelType.entries) {
            for (port in listOf("0", "-1", "65536", "70000", "99999999999")) {
                assertFalse(form(type, port).isValid, "$type local port $port")
            }
        }
    }

    @Test
    fun `the bounds of the usable local ports are accepted for every type`() {
        for (type in TunnelType.entries) {
            for (port in listOf("1024", "8080", "65535")) {
                assertTrue(form(type, port).isValid, "$type local port $port")
            }
        }
    }

    @Test
    fun `a remote port out of range is refused for a local and a remote forward`() {
        for (type in listOf(TunnelType.LOCAL_FORWARD, TunnelType.REMOTE_FORWARD)) {
            for (port in listOf("0", "-1", "65536", "70000")) {
                assertFalse(form(type, "8080", remotePort = port).isValid, "$type remote port $port")
            }
        }
    }

    @Test
    fun `a remote port below 1024 is accepted, the server decides`() {
        for (type in listOf(TunnelType.LOCAL_FORWARD, TunnelType.REMOTE_FORWARD)) {
            for (port in listOf("1", "80", "1023", "1024", "65535")) {
                assertTrue(form(type, "8080", remotePort = port).isValid, "$type remote port $port")
            }
        }
    }

    @Test
    fun `the remote port is ignored for SOCKS5`() {
        for (port in listOf("", "abc", "70000", "-1")) {
            assertTrue(form(TunnelType.DYNAMIC_SOCKS5, "1080", remotePort = port).isValid, "remote port '$port'")
        }
    }

    @Test
    fun `an empty or non numeric port keeps the form invalid`() {
        for (type in TunnelType.entries) {
            for (port in listOf("", " ", "abc", "80a")) {
                assertFalse(form(type, port).isValid, "$type local port '$port'")
            }
        }
        for (type in listOf(TunnelType.LOCAL_FORWARD, TunnelType.REMOTE_FORWARD)) {
            for (port in listOf("", " ", "abc")) {
                assertFalse(form(type, "8080", remotePort = port).isValid, "$type remote port '$port'")
            }
        }
    }

    // ── Message under the field ──────────────────────────────────────────────

    @Test
    fun `the local port problem tells reserved from out of range`() {
        assertEquals(TunnelPortProblem.LOCAL_RESERVED, form(TunnelType.LOCAL_FORWARD, "80").localPortProblem)
        assertEquals(TunnelPortProblem.LOCAL_RESERVED, form(TunnelType.DYNAMIC_SOCKS5, "1023").localPortProblem)
        assertEquals(TunnelPortProblem.LOCAL_OUT_OF_RANGE, form(TunnelType.LOCAL_FORWARD, "70000").localPortProblem)
        assertEquals(TunnelPortProblem.LOCAL_OUT_OF_RANGE, form(TunnelType.REMOTE_FORWARD, "0").localPortProblem)
        assertEquals(TunnelPortProblem.LOCAL_OUT_OF_RANGE, form(TunnelType.DYNAMIC_SOCKS5, "-1").localPortProblem)
    }

    @Test
    fun `no local port problem for a usable port, nor for 80 on a remote forward`() {
        assertNull(form(TunnelType.LOCAL_FORWARD, "1024").localPortProblem)
        assertNull(form(TunnelType.DYNAMIC_SOCKS5, "65535").localPortProblem)
        assertNull(form(TunnelType.REMOTE_FORWARD, "80").localPortProblem)
    }

    @Test
    fun `text that is not a number shows the out of range message`() {
        assertEquals(TunnelPortProblem.LOCAL_OUT_OF_RANGE, form(TunnelType.LOCAL_FORWARD, "abc").localPortProblem)
        assertEquals(TunnelPortProblem.LOCAL_OUT_OF_RANGE, form(TunnelType.REMOTE_FORWARD, "80a").localPortProblem)
        assertEquals(
            TunnelPortProblem.REMOTE_OUT_OF_RANGE,
            form(TunnelType.LOCAL_FORWARD, "8080", remotePort = "abc").remotePortProblem,
        )
    }

    @Test
    fun `no message while a port field is still empty, though the form stays invalid`() {
        val local = form(TunnelType.LOCAL_FORWARD, "", remotePort = "")
        assertNull(local.localPortProblem)
        assertNull(local.remotePortProblem)
        assertFalse(local.isValid)

        assertNull(form(TunnelType.LOCAL_FORWARD, "   ").localPortProblem)
    }

    @Test
    fun `the remote port problem is reported for a local and a remote forward, never for SOCKS5`() {
        assertEquals(
            TunnelPortProblem.REMOTE_OUT_OF_RANGE,
            form(TunnelType.LOCAL_FORWARD, "8080", remotePort = "70000").remotePortProblem,
        )
        assertEquals(
            TunnelPortProblem.REMOTE_OUT_OF_RANGE,
            form(TunnelType.REMOTE_FORWARD, "8080", remotePort = "0").remotePortProblem,
        )
        assertNull(form(TunnelType.LOCAL_FORWARD, "8080", remotePort = "80").remotePortProblem)
        assertNull(form(TunnelType.DYNAMIC_SOCKS5, "1080", remotePort = "70000").remotePortProblem)
    }

    @Test
    fun `switching the type recomputes the local port problem`() {
        val typed = form(TunnelType.LOCAL_FORWARD, "80")
        assertEquals(TunnelPortProblem.LOCAL_RESERVED, typed.localPortProblem)
        assertNull(typed.copy(type = TunnelType.REMOTE_FORWARD).localPortProblem)
    }

    // ── When the message shows ───────────────────────────────────────────────

    @Test
    fun `typing 8080 shows no message at any step`() {
        for (type in listOf(TunnelType.LOCAL_FORWARD, TunnelType.DYNAMIC_SOCKS5)) {
            for (typed in listOf("8", "80", "808", "8080")) {
                assertNull(form(type, typed).localPortProblemToShow(hasFocus = true), "$type, '$typed'")
            }
        }
    }

    @Test
    fun `while the field has the focus a reserved port and 0 wait for the end of the typing`() {
        for (type in listOf(TunnelType.LOCAL_FORWARD, TunnelType.DYNAMIC_SOCKS5)) {
            for (typed in listOf("0", "00", "1", "22", "80", "1000", "1023")) {
                val state = form(type, typed)
                assertNotNull(state.localPortProblem, "$type, '$typed' is a problem")
                assertNull(state.localPortProblemToShow(hasFocus = true), "$type, '$typed' while typing")
            }
        }
        assertNull(form(TunnelType.REMOTE_FORWARD, "8080", remotePort = "0").remotePortProblemToShow(hasFocus = true))
    }

    @Test
    fun `a port the typing cannot fix shows its message at once, focus or not`() {
        for (typed in listOf("65536", "70000", "99999999999", "abc", "80a", "-", "-1")) {
            for (hasFocus in listOf(true, false)) {
                for (type in TunnelType.entries) {
                    assertEquals(
                        TunnelPortProblem.LOCAL_OUT_OF_RANGE,
                        form(type, typed).localPortProblemToShow(hasFocus),
                        "$type, local '$typed', focus $hasFocus",
                    )
                }
                for (type in listOf(TunnelType.LOCAL_FORWARD, TunnelType.REMOTE_FORWARD)) {
                    assertEquals(
                        TunnelPortProblem.REMOTE_OUT_OF_RANGE,
                        form(type, "8080", remotePort = typed).remotePortProblemToShow(hasFocus),
                        "$type, remote '$typed', focus $hasFocus",
                    )
                }
            }
        }
    }

    @Test
    fun `once the field is left every problem shows`() {
        assertEquals(TunnelPortProblem.LOCAL_RESERVED, form(TunnelType.LOCAL_FORWARD, "80").localPortProblemToShow(false))
        assertEquals(TunnelPortProblem.LOCAL_RESERVED, form(TunnelType.DYNAMIC_SOCKS5, "1023").localPortProblemToShow(false))
        assertEquals(TunnelPortProblem.LOCAL_OUT_OF_RANGE, form(TunnelType.LOCAL_FORWARD, "0").localPortProblemToShow(false))
        assertEquals(
            TunnelPortProblem.REMOTE_OUT_OF_RANGE,
            form(TunnelType.LOCAL_FORWARD, "8080", remotePort = "0").remotePortProblemToShow(false),
        )
        assertNull(form(TunnelType.LOCAL_FORWARD, "8080").localPortProblemToShow(false))
        assertNull(form(TunnelType.REMOTE_FORWARD, "80").localPortProblemToShow(false))
    }

    @Test
    fun `a field that is empty shows nothing, focused or not`() {
        val empty = form(TunnelType.LOCAL_FORWARD, "", remotePort = "")
        for (hasFocus in listOf(true, false)) {
            assertNull(empty.localPortProblemToShow(hasFocus))
            assertNull(empty.remotePortProblemToShow(hasFocus))
        }
    }

    @Test
    fun `a SOCKS5 proxy never shows a remote port message`() {
        for (hasFocus in listOf(true, false)) {
            assertNull(form(TunnelType.DYNAMIC_SOCKS5, "1080", remotePort = "70000").remotePortProblemToShow(hasFocus))
        }
    }

    @Test
    fun `hiding the message while typing does not make the form valid`() {
        // Hidden while typing, the problem still keeps the save button off.
        val typing = form(TunnelType.LOCAL_FORWARD, "80")
        assertNull(typing.localPortProblemToShow(hasFocus = true))
        assertFalse(typing.isValid)

        assertTrue(form(TunnelType.LOCAL_FORWARD, "8080").isValid)
    }
}
