// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.scope

import fr.techtical.nextsh.shared.util.AppScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The contract of [ScreenViewModel]: [ScreenViewModel.screenScope] is cancelled by [ScreenViewModel.close] or
 * by the end of the app, whichever comes first, and the app scope is never touched by a close.
 *
 * Real dispatchers: the point is the cancellation tree, not the scheduling.
 */
class ScreenViewModelTest {

    private class TestAppScope(override val coroutineScope: CoroutineScope) : AppScope {
        override fun onDestroy() = coroutineScope.cancel()
    }

    /** A screen ViewModel with a database-like observer, as the real ones have. */
    private class Probe(appScope: AppScope, val source: MutableStateFlow<Int> = MutableStateFlow(0)) :
        ScreenViewModel(appScope) {
        val observed: StateFlow<Int> = source.stateIn(screenScope, SharingStarted.Eagerly, -1)

        fun launchOnScreen(block: suspend () -> Unit): Job = screenScope.launch { block() }
        fun launchOnApp(block: suspend () -> Unit): Job = appScope.coroutineScope.launch { block() }
    }

    private val app = TestAppScope(CoroutineScope(SupervisorJob() + Dispatchers.Default))

    @AfterTest
    fun tearDown() {
        app.onDestroy()
    }

    private suspend fun awaitUntil(message: String, condition: () -> Boolean) {
        val reached = withTimeoutOrNull(5_000L) {
            while (!condition()) delay(5L)
            true
        } ?: false
        assertTrue(reached, "timed out: $message")
    }

    @Test
    fun `close cancels what runs in the screen scope and the observers`() = runBlocking {
        val probe = Probe(app)
        awaitUntil("the observer subscribes") { probe.source.subscriptionCount.value == 1 }
        val onScreen = probe.launchOnScreen { delay(Long.MAX_VALUE) }

        probe.close()

        onScreen.join()
        assertTrue(onScreen.isCancelled)
        awaitUntil("the observer is released") { probe.source.subscriptionCount.value == 0 }
    }

    @Test
    fun `close leaves the app scope and what runs in it untouched`() = runBlocking {
        val probe = Probe(app)
        val gate = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        probe.launchOnApp { gate.await(); finished.complete(Unit) }

        probe.close()
        gate.complete(Unit)

        withTimeout(5_000L) { finished.await() }
        assertTrue(app.coroutineScope.isActive)
    }

    @Test
    fun `close is idempotent`() = runBlocking {
        val probe = Probe(app)

        probe.close()
        probe.close()

        assertTrue(app.coroutineScope.isActive)
    }

    @Test
    fun `ending the app scope cancels the screen scope`() = runBlocking {
        val probe = Probe(app)
        awaitUntil("the observer subscribes") { probe.source.subscriptionCount.value == 1 }
        val onScreen = probe.launchOnScreen { delay(Long.MAX_VALUE) }

        app.onDestroy()

        onScreen.join()
        assertTrue(onScreen.isCancelled)
        awaitUntil("the observer is released") { probe.source.subscriptionCount.value == 0 }
    }

    @Test
    fun `a screen opened after the app scope ended never starts anything`() = runBlocking {
        app.onDestroy()
        val ran = CompletableDeferred<Unit>()

        val probe = Probe(app)
        probe.launchOnScreen { ran.complete(Unit) }.join()

        assertFalse(ran.isCompleted)
        assertEquals(0, probe.source.subscriptionCount.value)
    }

    @Test
    fun `closing one screen leaves the other screens observing`() = runBlocking {
        val shared = MutableStateFlow(0)
        val first = Probe(app, shared)
        val second = Probe(app, shared)
        awaitUntil("both observe") { shared.subscriptionCount.value == 2 }
        // Subscribed is not yet "has seen its first value": wait for it, or the closed one could still be at
        // its initial value (-1) when its final value is asserted.
        awaitUntil("both have seen the first value") { first.observed.value == 0 && second.observed.value == 0 }

        first.close()

        awaitUntil("only the closed one is released") { shared.subscriptionCount.value == 1 }
        shared.value = 7
        awaitUntil("the open one still follows") { second.observed.value == 7 }
        assertEquals(0, first.observed.value, "the closed one stopped at its last value")
    }
}
