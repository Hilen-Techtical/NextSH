// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.ui.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Tests of [CloseGate]: the close request that waits for the native browser to exist. */
class CloseGateTest {

    private val log = mutableListOf<String>()
    private var deferredRuns = 0

    private fun gate(closeNow: () -> Unit = { log += "close" }) =
        CloseGate(closeNow = closeNow, runDeferred = { deferredRuns++; it() })

    @Test
    fun `a close asked for after the creation runs at once and then the follow-up`() {
        val g = gate()
        g.markCreated()

        g.requestClose { log += "after" }

        assertEquals(listOf("close", "after"), log)
        assertEquals(0, deferredRuns, "nothing was deferred")
    }

    @Test
    fun `a close asked for before the creation waits for it`() {
        val g = gate()

        g.requestClose { log += "after" }
        assertTrue(log.isEmpty(), "the native browser does not exist yet: nothing may be closed or disposed")

        g.markCreated()

        assertEquals(listOf("close", "after"), log)
        assertEquals(1, deferredRuns)
    }

    @Test
    fun `the follow-up never runs before the close`() {
        val g = gate()
        g.requestClose { log += "after" }

        g.markCreated()

        assertTrue(log.indexOf("close") < log.indexOf("after"))
    }

    @Test
    fun `only the first request starts the close and every follow-up runs once`() {
        val g = gate()
        g.markCreated()

        g.requestClose { log += "after-1" }
        g.requestClose { log += "after-2" }

        assertEquals(listOf("close", "after-1", "after-2"), log)
    }

    @Test
    fun `follow-ups asked for before the creation all run, in order, after the close`() {
        val g = gate()

        g.requestClose { log += "after-1" }
        g.requestClose { log += "after-2" }
        assertTrue(log.isEmpty())
        g.markCreated()
        g.markCreated()

        assertEquals(listOf("close", "after-1", "after-2"), log)
        assertEquals(1, deferredRuns, "one deferred close")
    }

    @Test
    fun `a follow-up asked for after the close ran still runs once`() {
        val g = gate()
        g.markCreated()
        g.requestClose { log += "after-1" }

        g.requestClose { log += "after-2" }
        g.requestClose { log += "after-3" }

        assertEquals(listOf("close", "after-1", "after-2", "after-3"), log)
    }

    @Test
    fun `a throwing follow-up does not stop the others`() {
        val g = gate()
        g.markCreated()

        g.requestClose { throw IllegalStateException("dispose failed") }
        g.requestClose { log += "after-2" }
        g.requestClose { throw IllegalStateException("again") }
        g.requestClose { log += "after-4" }

        assertEquals(listOf("close", "after-2", "after-4"), log)
    }

    @Test
    fun `a follow-up that asks for another follow-up while running is picked up`() {
        val g = gate()
        g.markCreated()

        g.requestClose {
            log += "after-1"
            g.requestClose { log += "nested" }
        }

        assertEquals(listOf("close", "after-1", "nested"), log)
    }

    @Test
    fun `the session holder callback and the overlay release can both ask without losing the client release`() {
        // onDispose asks first with no follow-up, the overlay then asks with the client release.
        val g = gate()
        g.markCreated()

        g.requestClose()
        g.requestClose { log += "client-disposed" }

        assertEquals(listOf("close", "client-disposed"), log)
    }
    @Test
    fun `a creation without any request closes nothing`() {
        val g = gate()

        g.markCreated()

        assertTrue(log.isEmpty())
    }

    @Test
    fun `the follow-up runs even when the close throws`() {
        val g = gate(closeNow = { throw IllegalStateException("jni") })

        g.markCreated()
        assertFailsWith<IllegalStateException> { g.requestClose { log += "after" } }

        assertEquals(listOf("after"), log)
    }
}