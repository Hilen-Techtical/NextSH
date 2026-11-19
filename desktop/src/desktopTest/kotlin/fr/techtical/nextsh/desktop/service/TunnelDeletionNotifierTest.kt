// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.service

import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.tray_stopped_deleted_title_many
import fr.techtical.nextsh.desktop.generated.resources.tray_stopped_deleted_title_one
import fr.techtical.nextsh.desktop.generated.resources.tunnels_stopped_deleted_many
import fr.techtical.nextsh.desktop.generated.resources.tunnels_stopped_deleted_one
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.resources.getString
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [TunnelDeletionNotifier] behind a fake tray: the grouping of the tunnels of one event into one message, and
 * the cases where there is nothing to show. The assertions compare with the resources, never with a literal:
 * the text depends on the machine language (FR or EN).
 */
class TunnelDeletionNotifierTest {

    private class FakeMessenger(@Volatile var available: Boolean = true) : TrayMessenger {
        val shown = CopyOnWriteArrayList<Pair<String, String>>()

        /** The next displayInfo throws, like a tray that refuses the message. */
        @Volatile
        var failNext = false

        override val isAvailable: Boolean get() = available

        override fun displayInfo(title: String, message: String) {
            if (failNext) {
                failNext = false
                throw IllegalStateException("tray refused the message")
            }
            shown += title to message
        }
    }

    private fun stop(vararg labels: String) =
        TunnelDeletionStop(labels.mapIndexed { index, label -> StoppedTunnel(id = "id-$index", label = label) })

    @Test
    fun `one stopped tunnel is named in the message`() = runTest {
        val messenger = FakeMessenger()

        TunnelDeletionNotifier(messenger).notify(stop("Grafana"))

        val (title, message) = messenger.shown.single()
        assertEquals(getString(Res.string.tray_stopped_deleted_title_one), title)
        assertEquals(getString(Res.string.tunnels_stopped_deleted_one, "Grafana"), message)
        assertTrue(message.contains("Grafana"))
        assertFalse(message.contains("%1"), "no format token may be left behind")
    }

    @Test
    fun `several tunnels of one event give ONE message with the count`() = runTest {
        val messenger = FakeMessenger()

        TunnelDeletionNotifier(messenger).notify(stop("Grafana", "Prometheus", "Loki"))

        val (title, message) = messenger.shown.single()
        assertEquals(getString(Res.string.tray_stopped_deleted_title_many), title)
        assertEquals(getString(Res.string.tunnels_stopped_deleted_many, 3), message)
        assertTrue(message.contains("3"))
        assertFalse(message.contains("%1"), "no format token may be left behind")
        assertFalse(message.contains("Grafana"), "the count form does not list the labels")
    }

    @Test
    fun `nothing is shown when the tray is not available, and nothing is queued for later`() = runTest {
        val messenger = FakeMessenger(available = false)
        val notifier = TunnelDeletionNotifier(messenger)

        notifier.notify(stop("Grafana"))
        assertTrue(messenger.shown.isEmpty())

        // The tray registering afterwards does not replay the old event.
        messenger.available = true
        assertTrue(messenger.shown.isEmpty())
        notifier.notify(stop("Prometheus"))
        assertEquals(1, messenger.shown.size)
    }

    @Test
    fun `an event without tunnels shows nothing`() = runTest {
        val messenger = FakeMessenger()

        TunnelDeletionNotifier(messenger).notify(TunnelDeletionStop(emptyList()))

        assertTrue(messenger.shown.isEmpty())
    }

    @Test
    fun `a label with percent and dollar signs comes through verbatim`() = runTest {
        val messenger = FakeMessenger()
        val label = "100% sure \$HOME"

        TunnelDeletionNotifier(messenger).notify(stop(label))

        assertTrue(messenger.shown.single().second.contains(label), messenger.shown.single().second)
    }

    @Test
    fun `a very long label is cut so the balloon stays readable`() = runTest {
        val messenger = FakeMessenger()
        val label = "x".repeat(300)

        TunnelDeletionNotifier(messenger).notify(stop(label))

        val message = messenger.shown.single().second
        assertFalse(message.contains(label), "the full label must not be in the message")
        assertTrue(message.contains("x".repeat(79) + "…"), message)
        assertTrue(message.length < 200, "length=${message.length}")
    }

    @Test
    fun `a long label with emoji is cut between characters, never inside a surrogate pair`() = runTest {
        val messenger = FakeMessenger()
        val emoji = "😀" // one code point, two chars
        val label = emoji.repeat(300)

        TunnelDeletionNotifier(messenger).notify(stop(label))

        val message = messenger.shown.single().second
        val index = message.indexOf(emoji)
        assertTrue(index >= 0, message)
        message.forEachIndexed { i, c ->
            if (Character.isHighSurrogate(c)) {
                assertTrue(i + 1 < message.length && Character.isLowSurrogate(message[i + 1]), "lone high surrogate at $i")
            }
            if (Character.isLowSurrogate(c)) {
                assertTrue(i > 0 && Character.isHighSurrogate(message[i - 1]), "lone low surrogate at $i")
            }
        }
        assertTrue(message.contains(emoji.repeat(79) + "…"), "79 whole emoji, then the ellipsis")
        assertFalse(message.contains(emoji.repeat(80)), "and no more")
    }

    // -- observe ------------------------------------------------------------------

    @Test
    fun `observe notifies every event in order, survives a tray failure and stops when cancelled`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val messenger = FakeMessenger()
        val stops = MutableSharedFlow<TunnelDeletionStop>(extraBufferCapacity = 16)
        val job = TunnelDeletionNotifier(messenger).observe(scope, stops)
        // Wait for the collector to be subscribed: a SharedFlow without replay drops what comes before.
        waitUntil { stops.subscriptionCount.value > 0 }

        stops.emit(stop("first"))
        waitUntil { messenger.shown.size == 1 }
        messenger.failNext = true
        stops.emit(stop("lost to the failing tray"))
        delay(150L)
        stops.emit(stop("second"))
        waitUntil { messenger.shown.size == 2 }

        assertEquals(
            listOf(
                getString(Res.string.tunnels_stopped_deleted_one, "first"),
                getString(Res.string.tunnels_stopped_deleted_one, "second"),
            ),
            messenger.shown.map { it.second },
        )

        job.cancel()
        waitUntil { stops.subscriptionCount.value == 0 }
        stops.emit(stop("after the cancel"))
        delay(150L)
        assertEquals(2, messenger.shown.size, "a cancelled observation shows nothing more")

        scope.cancel()
    }

    private suspend fun waitUntil(timeoutMs: Long = 3_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !condition()) delay(20L)
        assertTrue(condition(), "condition not reached within ${timeoutMs}ms")
    }
}
