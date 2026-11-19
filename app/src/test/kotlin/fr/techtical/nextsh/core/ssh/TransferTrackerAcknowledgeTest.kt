// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import android.net.Uri
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The tracker is a singleton that outlives every browser: a failure that one browser told the user
 * about must not be told again by the next one. A browser acknowledges what it announced, and the
 * tracker forgets it.
 */
class TransferTrackerAcknowledgeTest {

    private fun request(id: String, sessionId: String = "s1") = TransferRequest(
        id          = id,
        sessionId   = sessionId,
        remotePath  = "/home/user/$id",
        localUri    = mockk<Uri>(relaxed = true),
        direction   = TransferDirection.DOWNLOAD,
        fileSize    = 1024L,
        displayName = id,
    )

    @Test
    fun `an acknowledged failure is forgotten`() {
        val tracker = TransferTracker()
        tracker.enqueue(request("a"))
        tracker.dequeue()
        tracker.fail("a", "boom")

        tracker.acknowledge("a")

        assertNull(tracker.transfers.value["a"])
    }

    @Test
    fun `a completed or cancelled transfer can be acknowledged too`() {
        val tracker = TransferTracker()
        tracker.enqueue(request("done"))
        tracker.enqueue(request("stopped"))
        tracker.dequeue()
        tracker.dequeue()
        tracker.complete("done")
        tracker.cancel("stopped")

        tracker.acknowledge("done")
        tracker.acknowledge("stopped")

        assertTrue(tracker.transfers.value.isEmpty())
    }

    @Test
    fun `a transfer that is not over is never forgotten, its queue would be lost`() {
        val tracker = TransferTracker()
        tracker.enqueue(request("running"))
        tracker.enqueue(request("waiting"))
        // dequeue reserves the first queued one: "running" runs, "waiting" stays in the queue.
        tracker.dequeue()
        tracker.updateProgress("running", 10L, 1024L)

        tracker.acknowledge("running")
        tracker.acknowledge("waiting")

        assertEquals(TransferStatus.IN_PROGRESS, tracker.transfers.value["running"]?.status)
        assertEquals(TransferStatus.QUEUED, tracker.transfers.value["waiting"]?.status)
        assertEquals(listOf("waiting"), tracker.getQueued().map { it.id })
    }

    @Test
    fun `acknowledging an unknown transfer changes nothing`() {
        val tracker = TransferTracker()
        tracker.enqueue(request("a"))

        tracker.acknowledge("unknown")

        assertNotNull(tracker.transfers.value["a"])
        assertEquals(1, tracker.transfers.value.size)
    }

    @Test
    fun `acknowledging one failure leaves the failures of the other sessions for their own browser`() {
        val tracker = TransferTracker()
        tracker.enqueue(request("a", sessionId = "s1"))
        tracker.enqueue(request("b", sessionId = "s2"))
        tracker.dequeue()
        tracker.dequeue()
        tracker.fail("a", "boom")
        tracker.fail("b", "boom")

        tracker.acknowledge("a")

        assertNull(tracker.transfers.value["a"])
        assertEquals(TransferStatus.FAILED, tracker.transfers.value["b"]?.status)
    }
}
