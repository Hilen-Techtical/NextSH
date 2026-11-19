// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.navigation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The start that a launcher shortcut asks of the tunnel list is one-shot per entry: the route keeps
 * its argument, so an entry that the bottom bar saved and restores later (or that an activity
 * recreation brings back) must not start the tunnel again, whereas a new shortcut navigation, which
 * makes a new entry, must.
 */
class NavGraphTunnelStartTest {

    @Test
    fun `a fresh entry with a requested tunnel starts it`() {
        assertEquals("t1", tunnelToStartOnOpen(requestedId = "t1", alreadyHandled = false))
    }

    @Test
    fun `an entry that handled the request does not start the tunnel again`() {
        assertNull(tunnelToStartOnOpen(requestedId = "t1", alreadyHandled = true))
    }

    @Test
    fun `no tunnel requested, nothing to start, handled or not`() {
        assertNull(tunnelToStartOnOpen(requestedId = null, alreadyHandled = false))
        assertNull(tunnelToStartOnOpen(requestedId = null, alreadyHandled = true))
    }

    /** What an entry does over its life: it decides when it comes into composition, then marks itself. */
    private class Entry(val requestedId: String?) {
        var handled = false

        fun open(): String? {
            val toStart = tunnelToStartOnOpen(requestedId, handled)
            if (toStart != null) handled = true
            return toStart
        }
    }

    @Test
    fun `an entry saved by the bottom bar and restored does not restart the tunnel the user stopped`() {
        val entry = Entry(requestedId = "t1")

        assertEquals("t1", entry.open(), "the shortcut starts it")
        // The user stops the tunnel, takes another tab (the entry is saved with its flag), comes back
        // (the same entry is restored, its argument still t1): it comes into composition again.
        assertNull(entry.open(), "restored, it starts nothing")
        assertNull(entry.open(), "and again on every later return")
    }

    @Test
    fun `an activity that is recreated does not restart the tunnel`() {
        val entry = Entry(requestedId = "t1")
        entry.open()

        // Same entry, restored with the saved state of the activity: composed anew.
        assertNull(entry.open())
    }

    @Test
    fun `a new shortcut navigation makes a new entry and starts the tunnel again, the same one included`() {
        val first = Entry(requestedId = "t1")
        first.open()

        // popUpTo(TunnelList, inclusive) then a new entry: its own flag.
        val second = Entry(requestedId = "t1")

        assertEquals("t1", second.open(), "tapping the same shortcut after a stop starts it again")
        assertEquals("t2", Entry(requestedId = "t2").open())
    }

    @Test
    fun `the list opened without an id, as the resume of tunnels does, is not touched`() {
        val entry = Entry(requestedId = null)

        assertNull(entry.open())
        assertNull(entry.open())
    }
}
