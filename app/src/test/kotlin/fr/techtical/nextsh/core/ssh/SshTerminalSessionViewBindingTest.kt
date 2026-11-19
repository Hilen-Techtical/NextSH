// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * A terminal session outlives its view, and its two callbacks capture that view, so the Activity.
 * The view that bound them removes them when it is released, and only if it still owns them: when
 * the screen goes from full width to a split, the new view is bound before the old one is released.
 *
 * The real session needs an Android Handler, absent from a unit test: the binding logic runs on a
 * mock whose binding methods call the original code.
 */
class SshTerminalSessionViewBindingTest {

    private fun session(): SshTerminalSession {
        val session = mockk<SshTerminalSession>()
        every { session.bindView(any(), any(), any()) } answers { callOriginal() }
        every { session.unbindView(any()) } answers { callOriginal() }
        every { session.onScreenUpdate } answers { callOriginal() }
        every { session.onPaletteOverridden } answers { callOriginal() }
        return session
    }

    @Test
    fun `a session has no callback until a view binds it`() {
        val session = session()

        assertNull(session.onScreenUpdate)
        assertNull(session.onPaletteOverridden)
    }

    @Test
    fun `a bound view receives the screen updates and the palette resets`() {
        val session = session()
        var screen = 0
        var palette = 0

        session.bindView(Any(), { screen++ }, { palette++ })
        session.onScreenUpdate!!.invoke()
        session.onPaletteOverridden!!.invoke()

        assertEquals(1, screen)
        assertEquals(1, palette)
    }

    @Test
    fun `releasing the view that owns the callbacks removes both`() {
        val session = session()
        val view = Any()
        session.bindView(view, {}, {})

        session.unbindView(view)

        assertNull(session.onScreenUpdate)
        assertNull(session.onPaletteOverridden)
    }

    @Test
    fun `releasing an old view does not undo the binding of the newer one`() {
        val session = session()
        val oldView = Any()
        val newView = Any()
        var newScreen = 0
        session.bindView(oldView, {}, {})
        // Full width to split: the new view is created and bound before the old one is released.
        session.bindView(newView, { newScreen++ }, {})

        session.unbindView(oldView)

        assertNotNull(session.onScreenUpdate)
        assertNotNull(session.onPaletteOverridden)
        session.onScreenUpdate!!.invoke()
        assertEquals(1, newScreen)
    }

    @Test
    fun `a view that never bound the session cannot unbind it`() {
        val session = session()
        session.bindView(Any(), {}, {})

        session.unbindView(Any())

        assertNotNull(session.onScreenUpdate)
    }

    @Test
    fun `releasing twice is harmless and a later view can bind again`() {
        val session = session()
        val first = Any()
        session.bindView(first, {}, {})
        session.unbindView(first)
        session.unbindView(first)

        val second = Any()
        session.bindView(second, {}, {})

        assertNotNull(session.onScreenUpdate)
        session.unbindView(second)
        assertNull(session.onScreenUpdate)
    }
}
