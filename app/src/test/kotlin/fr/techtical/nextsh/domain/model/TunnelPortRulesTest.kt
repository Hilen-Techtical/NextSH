// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.domain.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TunnelPortRulesTest {

    private fun tunnel(type: TunnelType, localPort: Int, remotePort: Int) = TunnelConfig(
        id = "t1",
        label = "Tunnel",
        hostId = "h1",
        type = type,
        localPort = localPort,
        remoteHost = "127.0.0.1",
        remotePort = remotePort,
    )

    @Test
    fun `the app listens on the local port of a local forward and a SOCKS5 proxy only`() {
        assertTrue(TunnelPortRules.listensLocally(TunnelType.LOCAL_FORWARD))
        assertTrue(TunnelPortRules.listensLocally(TunnelType.DYNAMIC_SOCKS5))
        assertFalse(TunnelPortRules.listensLocally(TunnelType.REMOTE_FORWARD))
    }

    @Test
    fun `a local port out of range is refused for every type`() {
        for (type in TunnelType.values()) {
            for (port in listOf(Int.MIN_VALUE, -1, 0, 65536, 70000, Int.MAX_VALUE)) {
                assertEquals(
                    TunnelPortProblem.LOCAL_OUT_OF_RANGE,
                    TunnelPortRules.localPortProblem(type, port),
                    "$type local port $port",
                )
            }
        }
    }

    @Test
    fun `a local port below 1024 is reserved where the app listens`() {
        for (type in listOf(TunnelType.LOCAL_FORWARD, TunnelType.DYNAMIC_SOCKS5)) {
            for (port in listOf(1, 22, 80, 443, 1023)) {
                assertEquals(
                    TunnelPortProblem.LOCAL_RESERVED,
                    TunnelPortRules.localPortProblem(type, port),
                    "$type local port $port",
                )
            }
        }
    }

    @Test
    fun `a local port below 1024 is fine for a remote forward, the app only connects to it`() {
        for (port in listOf(1, 80, 1023)) {
            assertNull(TunnelPortRules.localPortProblem(TunnelType.REMOTE_FORWARD, port), "port $port")
        }
    }

    @Test
    fun `the bounds of the usable local ports are accepted`() {
        for (type in TunnelType.values()) {
            assertNull(TunnelPortRules.localPortProblem(type, 1024), "$type 1024")
            assertNull(TunnelPortRules.localPortProblem(type, 8080), "$type 8080")
            assertNull(TunnelPortRules.localPortProblem(type, 65535), "$type 65535")
        }
    }

    @Test
    fun `a remote port out of range is refused, below 1024 is left to the server`() {
        for (type in listOf(TunnelType.LOCAL_FORWARD, TunnelType.REMOTE_FORWARD)) {
            for (port in listOf(-1, 0, 65536, 70000)) {
                assertEquals(
                    TunnelPortProblem.REMOTE_OUT_OF_RANGE,
                    TunnelPortRules.remotePortProblem(type, port),
                    "$type remote port $port",
                )
            }
            for (port in listOf(1, 22, 80, 1023, 1024, 65535)) {
                assertNull(TunnelPortRules.remotePortProblem(type, port), "$type remote port $port")
            }
        }
    }

    @Test
    fun `a SOCKS5 proxy has no remote port to check`() {
        for (port in listOf(-1, 0, 70000)) {
            assertNull(TunnelPortRules.remotePortProblem(TunnelType.DYNAMIC_SOCKS5, port), "port $port")
        }
    }

    @Test
    fun `the local port is reported before the remote port`() {
        assertEquals(
            TunnelPortProblem.LOCAL_RESERVED,
            TunnelPortRules.problemOf(tunnel(TunnelType.LOCAL_FORWARD, localPort = 80, remotePort = 70000)),
        )
        assertEquals(
            TunnelPortProblem.REMOTE_OUT_OF_RANGE,
            TunnelPortRules.problemOf(tunnel(TunnelType.LOCAL_FORWARD, localPort = 8080, remotePort = 70000)),
        )
        assertNull(TunnelPortRules.problemOf(tunnel(TunnelType.LOCAL_FORWARD, localPort = 8080, remotePort = 80)))
        assertNull(TunnelPortRules.problemOf(tunnel(TunnelType.DYNAMIC_SOCKS5, localPort = 1080, remotePort = 0)))
    }
}
