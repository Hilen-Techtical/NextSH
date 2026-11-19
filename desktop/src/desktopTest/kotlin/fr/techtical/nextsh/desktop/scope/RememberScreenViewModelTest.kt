// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.scope

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import fr.techtical.nextsh.shared.util.AppScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * [rememberScreenViewModel] on the real Compose runtime, without a UI: a composition over a no-op applier is
 * enough to see when the ViewModel is built and when it is closed.
 */
class RememberScreenViewModelTest {

    private class TestAppScope(override val coroutineScope: CoroutineScope) : AppScope {
        override fun onDestroy() = coroutineScope.cancel()
    }

    private class Probe(appScope: AppScope, source: MutableStateFlow<Int>) : ScreenViewModel(appScope) {
        val observed: StateFlow<Int> = source.stateIn(screenScope, SharingStarted.Eagerly, 0)
    }

    private object NoOpApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
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

    /**
     * Sends frames until [condition] holds. A frame sent while the recomposer is not yet waiting for one is
     * lost, so a single `sendFrame` proves nothing: keep sending until the recomposition really happened.
     */
    private suspend fun recomposeUntil(clock: BroadcastFrameClock, message: String, condition: () -> Boolean) {
        var frame = 0L
        val reached = withTimeoutOrNull(5_000L) {
            while (!condition()) {
                clock.sendFrame(frame++)
                delay(10L)
            }
            true
        } ?: false
        assertTrue(reached, "timed out: $message")
    }

    @Test
    fun `the ViewModel is built once and closed when the screen leaves the composition`() = runBlocking {
        val source = MutableStateFlow(0)
        val built = AtomicInteger(0)
        val recomposer = Recomposer(coroutineContext)
        val composition = Composition(NoOpApplier, recomposer)

        composition.setContent {
            rememberScreenViewModel { built.incrementAndGet(); Probe(app, source) }
        }

        awaitUntil("the ViewModel observes while the screen is shown") { source.subscriptionCount.value == 1 }
        assertEquals(1, built.get())

        composition.dispose()

        awaitUntil("the ViewModel is closed when the screen is gone") { source.subscriptionCount.value == 0 }
        recomposer.cancel()
    }

    @Test
    fun `a composition abandoned before it was applied still closes the ViewModel`() = runBlocking {
        val source = MutableStateFlow(0)
        val recomposer = Recomposer(coroutineContext)
        val composition = Composition(NoOpApplier, recomposer)

        // The ViewModel is built and remembered, then the composition fails: it is thrown away without ever
        // being applied, so no effect ran and nothing would close the ViewModel but the RememberObserver.
        assertFails {
            composition.setContent {
                rememberScreenViewModel { Probe(app, source) }
                // The observer runs on another thread: wait until it really subscribed, or "no subscription
                // left" below could be true only because it had not started yet.
                val deadline = System.currentTimeMillis() + 5_000L
                while (source.subscriptionCount.value == 0 && System.currentTimeMillis() < deadline) {
                    Thread.sleep(5L)
                }
                error("the composition fails after the ViewModel was built")
            }
        }

        awaitUntil("the abandoned ViewModel is closed") { source.subscriptionCount.value == 0 }
        recomposer.cancel()
    }

    @Test
    fun `a new key closes the old ViewModel and builds a new one, the same key keeps it`() = runBlocking {
        val source = MutableStateFlow(0)
        val built = AtomicInteger(0)
        val contentRuns = AtomicInteger(0)
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val recomposeLoop = launch(clock, start = CoroutineStart.UNDISPATCHED) {
            recomposer.runRecomposeAndApplyChanges()
        }
        val composition = Composition(NoOpApplier, recomposer)
        var key by mutableStateOf("a")
        var unrelated by mutableStateOf(0)

        composition.setContent {
            contentRuns.incrementAndGet()
            @Suppress("UNUSED_EXPRESSION") unrelated // read, so that a change recomposes this content
            rememberScreenViewModel(key) { built.incrementAndGet(); Probe(app, source) }
        }
        awaitUntil("the first ViewModel observes") { source.subscriptionCount.value == 1 }

        // Same key: the content recomposes (counted, so this is not vacuous) and keeps the ViewModel.
        val runsBefore = contentRuns.get()
        Snapshot.withMutableSnapshot { unrelated = 1 }
        recomposeUntil(clock, "the content recomposes for the unrelated change") { contentRuns.get() > runsBefore }
        assertEquals(1, built.get(), "the same key must not rebuild the ViewModel")
        assertEquals(1, source.subscriptionCount.value)

        // New key: the old one is closed, the new one observes.
        Snapshot.withMutableSnapshot { key = "b" }
        recomposeUntil(clock, "the new key builds a second ViewModel") { built.get() == 2 }
        awaitUntil("only the new ViewModel still observes") { source.subscriptionCount.value == 1 }

        composition.dispose()
        awaitUntil("everything is released") { source.subscriptionCount.value == 0 }
        recomposeLoop.cancel()
        recomposer.cancel()
    }
}
