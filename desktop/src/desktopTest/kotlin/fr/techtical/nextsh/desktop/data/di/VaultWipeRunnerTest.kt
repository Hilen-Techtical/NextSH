// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.data.di

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [VaultWipeRunner]: one wipe at a time, the navigation only at the very end and only once everything is
 * erased, a failed step replayed once then offered for a retry, whatever the closing did. Real time: the
 * wipe runs in the app scope.
 */
class VaultWipeRunnerTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val events = CopyOnWriteArrayList<String>()
    private val state = MutableStateFlow(VaultWipeState.IDLE)

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private suspend fun waitFor(timeoutMs: Long = 5_000L, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            delay(10L)
        }
        return condition()
    }

    private val eraseCalls = CopyOnWriteArrayList<Set<EraseStep>>()

    /** [outcomes]: what each successive erase call reports as failed (the last one repeats). */
    private fun runner(
        stop: suspend () -> Boolean = { events += "stop"; true },
        outcomes: List<Set<EraseStep>> = listOf(emptySet()),
        vaultGone: Boolean = true,
    ): VaultWipeRunner {
        var call = 0
        return VaultWipeRunner(
            scope = scope,
            state = state,
            stop = stop,
            erase = { steps ->
                eraseCalls += steps
                events += "erase"
                outcomes[minOf(call++, outcomes.lastIndex)]
            },
            vaultGone = { vaultGone },
            afterWipe = { events += "after" },
            abandon = { still -> events += "abandon:$still" },
        )
    }

    @Test
    fun `the navigation happens only at the end, after the closing and the erase`() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val runner = runner(stop = { events += "stop"; gate.await(); true })

        runner.start { events += "navigate" }

        assertTrue(waitFor { events.contains("stop") })
        assertEquals(VaultWipeState.RUNNING, state.value)
        delay(200L)
        assertEquals(listOf("stop"), events.toList(), "nothing is erased and nothing navigates while closing")

        gate.complete(Unit)

        assertTrue(waitFor { events.contains("navigate") })
        assertEquals(listOf("stop", "erase", "after", "navigate"), events.toList())
        assertEquals(VaultWipeState.IDLE, state.value)
    }

    @Test
    fun `a closing step that reports a failure does not prevent the erase nor the navigation`() = runBlocking<Unit> {
        runner(stop = { events += "stop"; false }).start { events += "navigate" }

        assertTrue(waitFor { events.contains("navigate") })
        assertTrue(events.indexOf("erase") in 0 until events.indexOf("navigate"))
    }

    @Test
    fun `a closing step that throws does not prevent the erase either`() = runBlocking<Unit> {
        runner(stop = { throw IllegalStateException("boom") }).start { events += "navigate" }

        assertTrue(waitFor { events.contains("navigate") })
        assertTrue(events.contains("erase"))
    }

    @Test
    fun `a step that fails is replayed once, alone, and the navigation follows when it passes`() = runBlocking<Unit> {
        runner(outcomes = listOf(setOf(EraseStep.TABLES), emptySet())).start { events += "navigate" }

        assertTrue(waitFor { events.contains("navigate") })
        assertEquals(listOf(EraseStep.entries.toSet(), setOf(EraseStep.TABLES)), eraseCalls.toList())
    }

    @Test
    fun `a step that still fails leaves the failure dialog, no navigation, and a retry replays what is left`() = runBlocking<Unit> {
        val runner = runner(outcomes = listOf(setOf(EraseStep.KNOWN_HOSTS), setOf(EraseStep.KNOWN_HOSTS), emptySet()))

        runner.start { events += "navigate" }

        assertTrue(waitFor { state.value == VaultWipeState.FAILED })
        assertFalse(events.contains("navigate"), "an incomplete wipe never navigates")
        assertEquals(2, eraseCalls.size, "one attempt and one replay")

        runner.retry()

        assertTrue(waitFor { events.contains("navigate") })
        assertEquals(setOf(EraseStep.KNOWN_HOSTS), eraseCalls.last(), "the retry replays only what is left")
        assertEquals(VaultWipeState.IDLE, state.value)
    }

    @Test
    fun `a vault that is still there at the end is a failure even if no step reported one`() = runBlocking<Unit> {
        runner(vaultGone = false).start { events += "navigate" }

        assertTrue(waitFor { state.value == VaultWipeState.FAILED })
        assertFalse(events.contains("navigate"))
    }

    @Test
    fun `a failed wipe stays closed, the lockdown is not lifted until the user closes it`() = runBlocking<Unit> {
        val runner = runner(outcomes = listOf(setOf(EraseStep.VAULT)))
        runner.start { events += "navigate" }
        assertTrue(waitFor { state.value == VaultWipeState.FAILED })
        delay(100L)
        assertFalse(events.contains("after"), "still locked while the failure dialog is up")

        runner.dismissFailure()

        assertEquals(VaultWipeState.IDLE, state.value)
        assertTrue(waitFor { events.contains("abandon:false") })
        assertTrue(events.contains("after"))
    }

    @Test
    fun `closing the failure sends the user to unlock when the vault is still there`() = runBlocking<Unit> {
        val runner = runner(outcomes = listOf(setOf(EraseStep.VAULT)), vaultGone = false)
        runner.start { }
        assertTrue(waitFor { state.value == VaultWipeState.FAILED })

        runner.dismissFailure()

        assertTrue(waitFor { events.contains("abandon:true") })
    }

    @Test
    fun `closing the failure sends the user to the creation when the vault is gone`() = runBlocking<Unit> {
        val runner = runner(outcomes = listOf(setOf(EraseStep.TABLES)), vaultGone = true)
        runner.start { }
        assertTrue(waitFor { state.value == VaultWipeState.FAILED })

        runner.dismissFailure()

        assertTrue(waitFor { events.contains("abandon:false") })
    }

    @Test
    fun `a retry replays the closing before the erase`() = runBlocking<Unit> {
        val runner = runner(outcomes = listOf(setOf(EraseStep.VAULT), setOf(EraseStep.VAULT), emptySet()))
        runner.start { events += "navigate" }
        assertTrue(waitFor { state.value == VaultWipeState.FAILED })
        assertEquals(1, events.count { it == "stop" })

        runner.retry()

        assertTrue(waitFor { events.contains("navigate") })
        assertEquals(2, events.count { it == "stop" }, "a session opened since the failure is closed too")
        assertEquals(listOf("stop", "erase", "erase", "stop", "erase", "after", "navigate"), events.toList())
    }

    @Test
    fun `a cancellation during the closing lifts the lockdown and goes back to idle`() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val runner = runner(stop = { events += "stop"; gate.await(); true })
        runner.start { events += "navigate" }
        assertTrue(waitFor { events.contains("stop") })

        scope.coroutineContext[kotlinx.coroutines.Job]!!.children.forEach { it.cancel() }

        assertTrue(waitFor { state.value == VaultWipeState.IDLE })
        assertTrue(events.contains("after"))
        assertFalse(events.contains("navigate"))
    }

    @Test
    fun `closing the failure goes back to idle`() = runBlocking<Unit> {
        val runner = runner(outcomes = listOf(setOf(EraseStep.VAULT)))
        runner.start { }
        assertTrue(waitFor { state.value == VaultWipeState.FAILED })

        runner.dismissFailure()

        assertEquals(VaultWipeState.IDLE, state.value)
    }

    @Test
    fun `a second request while one is running is ignored`() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val runner = runner(stop = { events += "stop"; gate.await(); true })

        runner.start { events += "navigate" }
        assertTrue(waitFor { events.contains("stop") })
        runner.start { events += "navigate-again" }
        gate.complete(Unit)

        assertTrue(waitFor { events.contains("navigate") })
        delay(200L)
        assertEquals(1, events.count { it == "stop" })
        assertFalse(events.contains("navigate-again"))
    }

    @Test
    fun `a retry outside the failed state is ignored`() = runBlocking<Unit> {
        val runner = runner()

        runner.retry()
        delay(100L)

        assertTrue(eraseCalls.isEmpty())
        assertEquals(VaultWipeState.IDLE, state.value)
    }
}
