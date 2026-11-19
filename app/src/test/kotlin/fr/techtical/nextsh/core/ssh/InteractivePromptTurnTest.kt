// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The turn that the FIDO2 sheet and the fingerprint dialog share. */
@OptIn(ExperimentalCoroutinesApi::class)
class InteractivePromptTurnTest {

    private val turn = InteractivePromptTurn()

    @Test
    fun `only one block runs at a time, in the order they asked`() = runTest {
        val events = mutableListOf<String>()
        val release = CompletableDeferred<Unit>()

        val first = launch {
            turn.withTurn {
                events += "first in"
                release.await()
                events += "first out"
            }
        }
        runCurrent()
        val second = launch { turn.withTurn { events += "second in" } }
        runCurrent()

        assertEquals(listOf("first in"), events, "the second waits")
        release.complete(Unit)
        runCurrent()

        assertEquals(listOf("first in", "first out", "second in"), events)
        assertTrue(first.isCompleted && second.isCompleted)
    }

    @Test
    fun `the value of the block is returned`() = runTest {
        assertEquals(42, turn.withTurn { 42 })
    }

    @Test
    fun `a waiter that is cancelled gives up its place without blocking the others`() = runTest {
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val holder = launch { turn.withTurn { release.await() } }
        runCurrent()
        val cancelled = launch { turn.withTurn { events += "cancelled ran" } }
        runCurrent()
        val last = async { turn.withTurn { events += "last ran"; "done" } }
        runCurrent()

        cancelled.cancel()
        runCurrent()
        release.complete(Unit)
        runCurrent()

        assertEquals(listOf("last ran"), events)
        assertEquals("done", last.await())
        assertTrue(holder.isCompleted)
    }

    @Test
    fun `a block that fails gives the turn back`() = runTest {
        val failure = runCatching { turn.withTurn { throw IllegalStateException("boom") } }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)

        var ran = false
        turn.withTurn { ran = true }

        assertTrue(ran, "the turn was given back")
    }
}
