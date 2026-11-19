// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import android.net.Uri
import fr.techtical.nextsh.core.vault.VaultWipeGate
import fr.techtical.nextsh.core.vault.VaultWipeState
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What the tracker does for the "Wipe the vault" action: the cancellation of everything, the list emptied
 * (it holds file names and remote paths), the late writes of a copy that ends after, and the refusal of a
 * transfer asked while the app is closed.
 */
class TransferTrackerWipeTest {

    private val gate = VaultWipeGate()
    private val tracker = TransferTracker(gate)

    private fun request(id: String) = TransferRequest(
        id = id,
        sessionId = "s1",
        remotePath = "/home/user/$id",
        localUri = mockk<Uri>(relaxed = true),
        direction = TransferDirection.UPLOAD,
        fileSize = 1024L,
        displayName = id,
    )

    private fun statusOf(id: String) = tracker.transfers.value[id]?.status

    @Test
    fun `cancelAll cancels the waiting and the running transfers and leaves the finished ones`() {
        for (id in listOf("queued", "running", "done", "failed")) tracker.enqueue(request(id))
        tracker.dequeue() // "queued" is the first: it is the running one now
        tracker.complete("done")
        tracker.fail("failed", "text")

        tracker.cancelAll()

        assertEquals(TransferStatus.CANCELLED, statusOf("queued"))
        assertEquals(TransferStatus.CANCELLED, statusOf("running"))
        assertEquals(TransferStatus.COMPLETED, statusOf("done"))
        assertEquals(TransferStatus.FAILED, statusOf("failed"))
    }

    @Test
    fun `a transfer cancelled by cancelAll is never dequeued`() {
        tracker.enqueue(request("a"))

        tracker.cancelAll()

        assertEquals(null, tracker.dequeue())
    }

    @Test
    fun `clear forgets every transfer`() {
        tracker.enqueue(request("a"))
        tracker.enqueue(request("b"))
        tracker.fail("b", "text")

        tracker.clear()

        assertTrue(tracker.transfers.value.isEmpty())
    }

    @Test
    fun `a late write of a copy that ends after the list was emptied changes nothing`() {
        tracker.enqueue(request("a"))
        tracker.clear()

        tracker.updateProgress("a", 10L, 1024L)
        tracker.complete("a")
        tracker.fail("a", "text")
        tracker.cancel("a")

        assertTrue(tracker.transfers.value.isEmpty(), "the forgotten transfer did not come back")
    }

    @Test
    fun `a late progress of a copy that ends after the cancellation does not bring the transfer back to life`() {
        tracker.enqueue(request("a"))
        tracker.dequeue()
        tracker.cancelAll()

        tracker.updateProgress("a", 512L, 1024L)

        assertEquals(TransferStatus.CANCELLED, statusOf("a"))
    }

    @Test
    fun `a transfer asked while the app is closed is refused, whatever the state of the wipe`() {
        for (state in listOf(VaultWipeState.RUNNING, VaultWipeState.FAILED, VaultWipeState.DONE)) {
            gate.set(state)

            tracker.enqueue(request("late"))

            assertTrue(tracker.transfers.value.isEmpty(), "refused in $state")
        }
    }

    @Test
    fun `a transfer asked once the app is open again is accepted`() {
        gate.set(VaultWipeState.DONE)
        gate.acknowledgeDone()

        tracker.enqueue(request("a"))

        assertEquals(TransferStatus.QUEUED, statusOf("a"))
    }

    // ── The copies that are really running ──────────────────────────────────

    @Test
    fun `the copies running are what the wait is on, not the status of the transfers`() {
        tracker.copyStarted("a")
        tracker.copyStarted("b")
        tracker.cancelAll()
        assertEquals(setOf("a", "b"), tracker.copying.value, "cancelled by status, still copying")

        tracker.copyEnded("a")
        tracker.copyEnded("a")
        tracker.copyEnded("b")

        assertTrue(tracker.copying.value.isEmpty())
    }
}
