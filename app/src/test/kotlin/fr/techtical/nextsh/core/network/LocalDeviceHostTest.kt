// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.network

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalDeviceHostTest {

    @Test
    fun `localhost with or without a final dot, in any case`() {
        for (host in listOf("localhost", "LOCALHOST", "localhost.", " localhost ")) {
            assertTrue(isLocalDeviceHost(host), host)
        }
    }

    @Test
    fun `the whole of 127 slash 8 is local`() {
        for (host in listOf("127.0.0.1", "127.0.0.2", "127.255.255.254", "127.1.2.3")) {
            assertTrue(isLocalDeviceHost(host), host)
        }
    }

    @Test
    fun `IPv6 loopback, bracketed or not, and the IPv4 form mapped in IPv6`() {
        for (host in listOf("::1", "[::1]", "0:0:0:0:0:0:0:1", "::ffff:127.0.0.1", "[::ffff:127.0.0.5]", "0:0:0:0:0:ffff:127.0.0.1")) {
            assertTrue(isLocalDeviceHost(host), host)
        }
    }

    @Test
    fun `anything else is not local, and no name is resolved`() {
        val others = listOf(
            null, "", " ", ".", "example.com", "localhost.evil.com", "127.0.0.1.evil.com", "evil127.0.0.1",
            "128.0.0.1", "10.0.0.1", "192.168.1.10", "126.255.255.255", "127.0.0", "127.0.0.1.1", "127.0.0.256",
            "127.0.0.x", "1270.0.0.1", "::2", "::ffff:10.0.0.1", "::ffff:128.0.0.1", "[::1", "::1]",
        )
        for (host in others) {
            assertFalse(isLocalDeviceHost(host), "$host")
        }
    }
}
