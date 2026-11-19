// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.hosts

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The host name and port fields of the host editor: what can be typed can be restored from a backup
 * (same rule as the shared validator), so a host that a restore would refuse cannot be saved.
 */
class HostRestorableFieldsTest {

    private fun form(hostname: String = "host.example.com", port: String = "22") = HostFormState(
        label = "Host",
        hostname = hostname,
        port = port,
        username = "user",
    )

    private fun invisible(codePoint: Int): String = String(Character.toChars(codePoint))

    @Test
    fun `a host name, an IPv4 address and an IPv6 address are accepted`() {
        listOf("host.example.com", "192.168.1.10", "2001:db8::1", "fe80::1%eth0", "a").forEach {
            assertTrue(isHostnameValid(it), it)
            assertTrue(form(hostname = it).isValid, it)
        }
    }

    @Test
    fun `a host name with an inner space is refused`() {
        assertFalse(isHostnameValid("my host.example.com"))
        assertFalse(form(hostname = "my host.example.com").isValid)
    }

    @Test
    fun `a host name with spaces around it is accepted`() {
        assertTrue(isHostnameValid("  host.example.com  "))
        assertTrue(form(hostname = "  host.example.com  ").isValid)
    }

    @Test
    fun `a host name with an invisible or a control character is refused`() {
        listOf(0x200B, 0x202E, 0xFEFF, 0x0000, 0x000A).forEach { codePoint ->
            val name = "host" + invisible(codePoint) + ".example.com"
            assertFalse(isHostnameValid(name), "U+" + codePoint.toString(16))
            assertFalse(form(hostname = name).isValid, "U+" + codePoint.toString(16))
        }
    }

    @Test
    fun `an empty or blank host name is refused`() {
        listOf("", " ", "   ").forEach {
            assertFalse(isHostnameValid(it), "[$it]")
            assertFalse(form(hostname = it).isValid, "[$it]")
        }
    }

    @Test
    fun `a host name of 255 characters is accepted and 256 is refused`() {
        assertTrue(isHostnameValid("a".repeat(255)))
        assertFalse(isHostnameValid("a".repeat(256)))
    }

    @Test
    fun `ports from 1 to 65535 are accepted`() {
        listOf("1", "22", "2222", "65535", " 22 ").forEach {
            assertTrue(isPortValid(it), "[$it]")
            assertTrue(form(port = it).isValid, "[$it]")
        }
    }

    @Test
    fun `port 0, 65536, a negative port and text are refused`() {
        listOf("0", "65536", "70000", "-1", "", " ", "abc", "22.5", "2 2").forEach {
            assertFalse(isPortValid(it), "[$it]")
            assertFalse(form(port = it).isValid, "[$it]")
        }
    }

    @Test
    fun `the defaults of a new host keep the port valid`() {
        assertTrue(isPortValid(HostFormState().port))
    }
}
