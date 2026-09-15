// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import android.net.Uri
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Couvre la reservation atomique de la file et l'annulation.
 *
 * Avant la migration API 36, la file etait lue avec un simple filtre sur les
 * requetes en attente : deux vidages concurrents pouvaient retenir la meme
 * requete et la transferer deux fois.
 */
class TransferTrackerQueueTest {

    private fun request(id: String, name: String = "fichier-$id") = TransferRequest(
        id          = id,
        sessionId   = "s1",
        remotePath  = "/home/user/$name",
        localUri    = mockk<Uri>(relaxed = true),
        direction   = TransferDirection.DOWNLOAD,
        fileSize    = 1024L,
        displayName = name,
    )

    @Test
    fun `dequeue reserve la requete et la retire de la file`() {
        val tracker = TransferTracker()
        tracker.enqueue(request("a"))

        val claimed = tracker.dequeue()

        assertEquals("a", claimed?.id)
        assertEquals(TransferStatus.IN_PROGRESS, tracker.transfers.value["a"]?.status)
        assertTrue(tracker.getQueued().isEmpty(), "la requete reservee ne doit plus etre en attente")
        assertNull(tracker.dequeue(), "une requete deja reservee ne doit pas etre servie deux fois")
    }

    @Test
    fun `deux vidages concurrents ne se partagent jamais la meme requete`() {
        val tracker = TransferTracker()
        tracker.enqueue(request("a"))
        tracker.enqueue(request("b"))

        val claimed = listOfNotNull(tracker.dequeue(), tracker.dequeue(), tracker.dequeue())

        assertEquals(2, claimed.size)
        assertEquals(setOf("a", "b"), claimed.map { it.id }.toSet())
    }

    @Test
    fun `un transfert annule ne peut pas etre marque termine`() {
        val tracker = TransferTracker()
        tracker.enqueue(request("a"))
        tracker.dequeue()

        tracker.cancel("a")
        // La boucle de copie se termine juste apres l'annulation.
        tracker.complete("a")

        assertEquals(
            TransferStatus.CANCELLED,
            tracker.transfers.value["a"]?.status,
            "annoncer un fichier complet alors qu'il est tronque induirait l'utilisateur en erreur",
        )
    }

    @Test
    fun `isCancelled reflete la demande d annulation`() {
        val tracker = TransferTracker()
        tracker.enqueue(request("a"))

        assertFalse(tracker.isCancelled("a"))
        tracker.cancel("a")
        assertTrue(tracker.isCancelled("a"))
    }

    @Test
    fun `failAllUnfinished ne touche ni les transferts termines ni les annules`() {
        val tracker = TransferTracker()
        listOf("done", "cancelled", "running", "queued").forEach { tracker.enqueue(request(it)) }
        tracker.dequeue() // done
        tracker.complete("done")
        tracker.cancel("cancelled")
        tracker.updateProgress("running", 10L, 1024L)

        tracker.failAllUnfinished("quota atteint")

        val states = tracker.transfers.value
        assertEquals(TransferStatus.COMPLETED, states["done"]?.status)
        assertEquals(TransferStatus.CANCELLED, states["cancelled"]?.status)
        assertEquals(TransferStatus.FAILED, states["running"]?.status)
        assertEquals(TransferStatus.FAILED, states["queued"]?.status)
        assertEquals("quota atteint", states["queued"]?.errorMessage)
    }
}
