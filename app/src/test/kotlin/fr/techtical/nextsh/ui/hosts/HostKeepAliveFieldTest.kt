// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.hosts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The keepalive field of the host editor: 0, or 15 to 60 seconds, with nothing else accepted. */
class HostKeepAliveFieldTest {

    private fun form(keepAlive: String) = HostFormState(
        label = "Host",
        hostname = "host.example.com",
        username = "user",
        keepAliveSeconds = keepAlive,
    )

    @Test
    fun `0 and the values from 15 to 60 are accepted`() {
        listOf("0", "15", "16", "30", "59", "60").forEach {
            assertTrue(isKeepAliveValid(it), it)
            assertTrue(form(it).isValid, it)
        }
    }

    @Test
    fun `values out of the bounds are refused`() {
        listOf("1", "5", "14", "61", "120", "9999", "-1", "-30").forEach {
            assertFalse(isKeepAliveValid(it), it)
            assertFalse(form(it).isValid, it)
        }
    }

    @Test
    fun `text that is not a whole number is refused`() {
        listOf("", " ", "abc", "30.5", "3 0").forEach {
            assertFalse(isKeepAliveValid(it), "[$it]")
            assertFalse(form(it).isValid, "[$it]")
        }
    }

    @Test
    fun `a stored value out of the bounds shows as an error without being rewritten`() {
        // A synced or imported host at 120 s: the form shows the stored value as it is.
        val loaded = form(120.toString())

        assertEquals("120", loaded.keepAliveSeconds)
        assertFalse(loaded.isValid)
    }

    @Test
    fun `the default of a new host is accepted`() {
        assertTrue(isKeepAliveValid(HostFormState().keepAliveSeconds))
    }
}
