// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.domain.model.TunnelConfig
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.domain.model.TunnelStatus
import fr.techtical.nextsh.domain.model.TunnelType
import fr.techtical.nextsh.testutil.RecordingTree
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import timber.log.Timber
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Couvre la detection de panne des tunnels, prerequis de la reconnexion
 * automatique : sans transition vers [TunnelStatus.ERROR],
 * [SshTunnelManager.getTunnelsForReconnect] renvoie toujours une liste vide et
 * la reconnexion ne se declenche jamais.
 *
 * Les tunnels sont demarres via un vrai [ServerSocket] local plutot qu'un
 * client SSH complet : seule la mecanique d'etat est testee ici, pas SSHJ.
 */
class SshTunnelManagerFailureTest {

    private fun manager() = SshTunnelManager(sessionManager = mockk(relaxed = true))

    private fun config(id: String = "t1", port: Int = 0) = TunnelConfig(
        id         = id,
        label      = "tunnel-$id",
        hostId     = "h1",
        type       = TunnelType.LOCAL_FORWARD,
        localPort  = port,
        remoteHost = "127.0.0.1",
        remotePort = 8080,
    )

    /** Reserve un port libre puis le relache, pour eviter les collisions. */
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun `une panne bascule le tunnel en ERROR et le rend eligible a la reconnexion`() = runTest {
        val manager = manager()
        val cfg = config(port = freePort())

        manager.markStarting(cfg)
        manager.injectActiveForTest(cfg, sessionId = "s1")

        manager.simulateUnexpectedStopForTest(cfg.id, "Connexion SSH perdue")

        val state = manager.tunnelStates.value[cfg.id]
        assertEquals(TunnelStatus.ERROR, state?.status)
        assertEquals("Connexion SSH perdue", state?.errorMessage)
        assertNull(manager.activeTunnels.value[cfg.id], "le tunnel mort doit quitter les tunnels actifs")
        assertEquals(listOf(cfg.id), manager.getTunnelsForReconnect().map { it.id })
    }

    @Test
    fun `la fin de la session SSH fait tomber en ERROR les tunnels qu elle portait, pas les autres`() = runTest {
        val ends = slot<SessionEndListener>()
        val sessionManager = mockk<SshSessionManager>(relaxed = true)
        every { sessionManager.addSessionEndListener(capture(ends)) } just Runs
        val manager = SshTunnelManager(sessionManager)
        val onLostSession = config(id = "a", port = freePort())
        val alsoOnLostSession = config(id = "b", port = freePort())
        val elsewhere = config(id = "c", port = freePort())
        for ((cfg, session) in listOf(onLostSession to "s1", alsoOnLostSession to "s1", elsewhere to "s2")) {
            manager.markStarting(cfg)
            manager.injectActiveForTest(cfg, sessionId = session)
        }

        // The SSHJ listener is the session manager's now: the tunnels hear about it from there.
        ends.captured.onSessionEnded("s1", SessionEnd.Lost("CONNECTION_LOST"))

        for (cfg in listOf(onLostSession, alsoOnLostSession)) {
            assertEquals(TunnelStatus.ERROR, manager.tunnelStates.value[cfg.id]?.status)
            assertEquals(TUNNEL_SESSION_LOST_MESSAGE, manager.tunnelStates.value[cfg.id]?.errorMessage)
            assertNull(manager.activeTunnels.value[cfg.id])
        }
        assertEquals(TunnelStatus.ACTIVE, manager.tunnelStates.value[elsewhere.id]?.status)
        assertEquals(listOf("a", "b"), manager.getTunnelsForReconnect().map { it.id }.sorted())
    }

    @Test
    fun `une session fermee de force fait tomber ses tunnels comme avant, BY_APPLICATION`() = runTest {
        val ends = slot<SessionEndListener>()
        val sessionManager = mockk<SshSessionManager>(relaxed = true)
        every { sessionManager.addSessionEndListener(capture(ends)) } just Runs
        val manager = SshTunnelManager(sessionManager)
        val cfg = config(port = freePort())
        manager.markStarting(cfg)
        manager.injectActiveForTest(cfg, sessionId = "s1")

        ends.captured.onSessionEnded("s1", SessionEnd.Closed)

        assertEquals(TUNNEL_SESSION_LOST_MESSAGE, manager.tunnelStates.value[cfg.id]?.errorMessage)
    }

    @Test
    fun `chaque fin de tunnel rend sa reference sur la session, une seule fois`() = runTest {
        val sessionManager = mockk<SshSessionManager>(relaxed = true)
        val manager = SshTunnelManager(sessionManager)
        val stopped = config(id = "a", port = freePort())
        val failed = config(id = "b", port = freePort())
        val everything = config(id = "c", port = freePort())
        manager.markStarting(stopped)
        manager.injectActiveForTest(stopped, sessionId = "s-stopped")
        manager.markStarting(failed)
        manager.injectActiveForTest(failed, sessionId = "s-failed")

        manager.stopTunnel(stopped.id)
        manager.simulateUnexpectedStopForTest(failed.id, "boucle d'ecoute finie")
        // Le meme arret signale une seconde fois (la boucle se reveille apres coup) ne rend rien de plus.
        manager.simulateUnexpectedStopForTest(failed.id, "boucle d'ecoute finie")
        manager.stopTunnel(stopped.id)

        verify(exactly = 1) { sessionManager.release("s-stopped") }
        verify(exactly = 1) { sessionManager.release("s-failed") }

        manager.markStarting(everything)
        manager.injectActiveForTest(everything, sessionId = "s-all")
        manager.stopAllTunnels()
        verify(exactly = 1) { sessionManager.release("s-all") }
    }

    // ── Des arrets qui se croisent rendent la reference une seule fois ────────

    /**
     * Une socket locale dont la PREMIERE fermeture reste suspendue : le temps de l'arret qui la
     * ferme, un autre arret du meme tunnel s'execute en entier. Rend la course deterministe.
     */
    private class SlowToCloseSocket : ServerSocket() {
        val closing = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        private val first = AtomicBoolean(true)

        override fun close() {
            if (first.compareAndSet(true, false)) {
                closing.countDown()
                check(proceed.await(5, TimeUnit.SECONDS)) { "the test never let the close go on" }
            }
            super.close()
        }
    }

    private fun <T> inThread(block: () -> T): Future<T> {
        val result = CompletableFuture<T>()
        Thread {
            try {
                result.complete(block())
            } catch (e: Throwable) {
                result.completeExceptionally(e)
            }
        }.start()
        return result
    }

    private fun newTracked(): Pair<SshTunnelManager, SshSessionManager> {
        val sessionManager = mockk<SshSessionManager>(relaxed = true)
        return SshTunnelManager(sessionManager) to sessionManager
    }

    @Test
    fun `deux arrets concurrents du meme tunnel ne rendent sa reference qu une fois`() {
        val (manager, sessionManager) = newTracked()
        val cfg = config()
        val socket = SlowToCloseSocket()
        manager.markStarting(cfg)
        manager.injectActiveForTest(cfg, sessionId = "s1", serverSocket = socket)

        val first = inThread { runBlocking { manager.stopTunnel(cfg.id) } }
        assertTrue(socket.closing.await(5, TimeUnit.SECONDS), "le premier arret ferme la socket")
        // Le second arret s'execute en entier pendant que le premier ferme encore.
        val second = runBlocking { manager.stopTunnel(cfg.id) }
        socket.proceed.countDown()
        first.get(5, TimeUnit.SECONDS)

        assertTrue(second is SshResult.Success)
        // Rendue deux fois, elle aurait pris celle d'un terminal qui tient la meme session.
        verify(exactly = 1) { sessionManager.release("s1") }
        assertTrue(manager.tunnelStates.value.isEmpty())
    }

    @Test
    fun `un arret et l arret de tous se croisent, la reference est rendue une fois`() {
        val (manager, sessionManager) = newTracked()
        val cfg = config()
        val socket = SlowToCloseSocket()
        manager.markStarting(cfg)
        manager.injectActiveForTest(cfg, sessionId = "s1", serverSocket = socket)

        val stopAll = inThread { manager.stopAllTunnels() }
        assertTrue(socket.closing.await(5, TimeUnit.SECONDS))
        runBlocking { manager.stopTunnel(cfg.id) }
        socket.proceed.countDown()
        stopAll.get(5, TimeUnit.SECONDS)

        verify(exactly = 1) { sessionManager.release("s1") }
        assertTrue(manager.tunnelStates.value.isEmpty())
        assertTrue(manager.activeTunnels.value.isEmpty())
    }

    @Test
    fun `l arret de tous et un arret qui le precede, l ordre inverse rend aussi une fois`() {
        val (manager, sessionManager) = newTracked()
        val cfg = config()
        val socket = SlowToCloseSocket()
        manager.markStarting(cfg)
        manager.injectActiveForTest(cfg, sessionId = "s1", serverSocket = socket)

        val stop = inThread { runBlocking { manager.stopTunnel(cfg.id) } }
        assertTrue(socket.closing.await(5, TimeUnit.SECONDS))
        manager.stopAllTunnels()
        socket.proceed.countDown()
        stop.get(5, TimeUnit.SECONDS)

        verify(exactly = 1) { sessionManager.release("s1") }
    }

    @Test
    fun `une panne qui croise un arret manuel ne rend pas la reference une seconde fois`() {
        val (manager, sessionManager) = newTracked()
        val cfg = config()
        val socket = SlowToCloseSocket()
        manager.markStarting(cfg)
        manager.injectActiveForTest(cfg, sessionId = "s1", serverSocket = socket)

        val failure = inThread { manager.simulateUnexpectedStopForTest(cfg.id, "boucle d'ecoute finie") }
        assertTrue(socket.closing.await(5, TimeUnit.SECONDS))
        runBlocking { manager.stopTunnel(cfg.id) }
        socket.proceed.countDown()
        failure.get(5, TimeUnit.SECONDS)

        verify(exactly = 1) { sessionManager.release("s1") }
        // L'arret manuel a solde la carte : la panne tardive ne la ressuscite pas en erreur.
        assertTrue(manager.tunnelStates.value.isEmpty())
        assertTrue(manager.getTunnelsForReconnect().isEmpty())
    }

    @Test
    fun `l arret de tous n efface pas un tunnel enregistre apres son instantane`() {
        val (manager, sessionManager) = newTracked()
        val stopped = config(id = "a")
        val late = config(id = "b")
        val socket = SlowToCloseSocket()
        manager.markStarting(stopped)
        manager.injectActiveForTest(stopped, sessionId = "s-stopped", serverSocket = socket)

        val stopAll = inThread { manager.stopAllTunnels() }
        assertTrue(socket.closing.await(5, TimeUnit.SECONDS))
        // Un demarrage se termine entre l'instantane et le vidage de l'ancien code.
        manager.markStarting(late)
        manager.injectActiveForTest(late, sessionId = "s-late")
        socket.proceed.countDown()
        stopAll.get(5, TimeUnit.SECONDS)

        assertEquals(listOf("b"), manager.activeTunnels.value.keys.toList(), "le tunnel tardif reste actif")
        assertEquals(TunnelStatus.ACTIVE, manager.tunnelStates.value["b"]?.status)
        assertNull(manager.tunnelStates.value["a"])
        verify(exactly = 1) { sessionManager.release("s-stopped") }
        verify(exactly = 0) { sessionManager.release("s-late") }
    }

    @Test
    fun `un arret qui croise un redemarrage du meme tunnel ne touche pas la nouvelle instance`() {
        val (manager, sessionManager) = newTracked()
        val cfg = config()
        val socket = SlowToCloseSocket()
        manager.markStarting(cfg)
        manager.injectActiveForTest(cfg, sessionId = "s-old", serverSocket = socket)

        val stop = inThread { runBlocking { manager.stopTunnel(cfg.id) } }
        assertTrue(socket.closing.await(5, TimeUnit.SECONDS))
        // L'ancienne instance est remplacee par un demarrage : le nouveau enregistre son tunnel.
        manager.markStarting(cfg)
        manager.injectActiveForTest(cfg, sessionId = "s-new")
        socket.proceed.countDown()
        stop.get(5, TimeUnit.SECONDS)

        assertEquals(setOf(cfg.id), manager.activeTunnels.value.keys)
        assertEquals(TunnelStatus.ACTIVE, manager.tunnelStates.value[cfg.id]?.status)
        verify(exactly = 0) { sessionManager.release("s-new") }
    }

    @Test
    fun `un arret manuel n est pas confondu avec une panne`() = runTest {
        val manager = manager()
        val cfg = config(port = freePort())

        manager.markStarting(cfg)
        manager.injectActiveForTest(cfg, sessionId = "s1")
        manager.stopTunnel(cfg.id)

        // La boucle d'ecoute se reveille APRES l'arret et signale la fermeture.
        manager.simulateUnexpectedStopForTest(cfg.id, "socket closed")

        assertNull(manager.tunnelStates.value[cfg.id], "un tunnel arrete manuellement disparait de l'etat")
        assertTrue(manager.getTunnelsForReconnect().isEmpty(), "pas de reconnexion apres un arret volontaire")
    }

    @Test
    fun `stopAllTunnels marque avant de fermer, aucune reconnexion parasite`() = runTest {
        val manager = manager()
        val a = config(id = "a", port = freePort())
        val b = config(id = "b", port = freePort())

        manager.markStarting(a)
        manager.injectActiveForTest(a, sessionId = "s1")
        manager.markStarting(b)
        manager.injectActiveForTest(b, sessionId = "s1")

        manager.stopAllTunnels()

        // Les deux boucles d'ecoute se reveillent apres coup.
        manager.simulateUnexpectedStopForTest(a.id, "socket closed")
        manager.simulateUnexpectedStopForTest(b.id, "socket closed")

        assertTrue(manager.tunnelStates.value.isEmpty())
        assertTrue(manager.getTunnelsForReconnect().isEmpty())
    }

    @Test
    fun `un redemarrage apres arret manuel redevient eligible a la reconnexion`() = runTest {
        val manager = manager()
        val cfg = config(port = freePort())

        manager.markStarting(cfg)
        manager.injectActiveForTest(cfg, sessionId = "s1")
        manager.stopTunnel(cfg.id)

        // Redemarrage : markStarting retire l'id des tunnels arretes manuellement.
        manager.markStarting(cfg)
        manager.injectActiveForTest(cfg, sessionId = "s1")
        manager.simulateUnexpectedStopForTest(cfg.id, "Connexion SSH perdue")

        assertEquals(TunnelStatus.ERROR, manager.tunnelStates.value[cfg.id]?.status)
        assertEquals(listOf(cfg.id), manager.getTunnelsForReconnect().map { it.id })
    }

    @Test
    fun `une panne sur un tunnel inconnu est ignoree`() = runTest {
        val manager = manager()
        manager.simulateUnexpectedStopForTest("inexistant", "peu importe")
        assertTrue(manager.tunnelStates.value.isEmpty())
    }

    @Test
    fun `un tunnel en erreur peut etre retire, sinon il bloque le service au premier plan`() = runTest {
        val manager = manager()
        val cfg = config(port = freePort())

        manager.markStarting(cfg)
        manager.injectActiveForTest(cfg, sessionId = "s1")
        manager.simulateUnexpectedStopForTest(cfg.id, "Connexion SSH perdue")

        // Le tunnel n'est plus actif mais son etat ERREUR subsiste : sans prise
        // en charge de ce cas, stopTunnel echouait et l'etat restait a jamais,
        // maintenant le service au premier plan avec zero tunnel vivant.
        val result = manager.stopTunnel(cfg.id)

        assertTrue(result is SshResult.Success, "arreter un tunnel en erreur doit reussir")
        assertTrue(manager.tunnelStates.value.isEmpty(), "l'etat doit disparaitre")
        assertTrue(manager.getTunnelsForReconnect().isEmpty())
    }

    @Test
    fun `le thread d une instance remplacee ne tue pas le tunnel redemarre`() = runTest {
        val manager = manager()
        val cfg = config(port = freePort())

        manager.markStarting(cfg)
        val ancienne = manager.injectActiveForTest(cfg, sessionId = "s1")
        manager.stopTunnel(cfg.id)

        // Redemarrage immediat, nouvelle instance du meme tunnel.
        manager.markStarting(cfg)
        manager.injectActiveForTest(cfg, sessionId = "s1")

        // La boucle d'ecoute de l'ancienne instance se reveille seulement
        // maintenant et signale sa propre fin.
        manager.simulateUnexpectedStopForTest(cfg.id, "socket closed", instanceId = ancienne)

        assertEquals(
            TunnelStatus.ACTIVE,
            manager.tunnelStates.value[cfg.id]?.status,
            "le tunnel fraichement redemarre doit rester actif",
        )
    }

    // ── Journaux : la regle vaut a tous les niveaux, debug compris (RecordingTree les voit tous) ──

    @Test
    fun `une panne, un arret et un retrait ne citent ni libelle ni id ni message dans le journal`() = runTest {
        val manager = manager()
        val failing = config(id = "id-secret-1", port = freePort())
        val stopped = config(id = "id-secret-2", port = freePort())
        val tree = RecordingTree()
        Timber.plant(tree)
        try {
            manager.markStarting(failing)
            manager.injectActiveForTest(failing, sessionId = "session-secret")
            manager.simulateUnexpectedStopForTest(failing.id, "Connexion SSH perdue vers db.interne.example")
            // Retrait d'un tunnel en erreur, puis arret propre d'un tunnel actif, puis inconnu.
            manager.stopTunnel(failing.id)
            manager.markStarting(stopped)
            manager.injectActiveForTest(stopped, sessionId = "session-secret")
            manager.stopTunnel(stopped.id)
            manager.stopTunnel("id-secret-inconnu")
        } finally {
            Timber.uproot(tree)
        }

        val logged = tree.entries.joinToString("\n") { it.message }
        assertTrue(logged.isNotBlank(), "les arrets sont journalises")
        for (secret in listOf("tunnel-id-secret", "id-secret", "session-secret", "db.interne", "Connexion SSH perdue")) {
            assertFalse(logged.contains(secret), "'$secret' ne doit pas etre dans le journal : $logged")
        }
    }

    // ── isAwaitingReconnect : la boucle de reconnexion s'efface devant l'utilisateur ──

    @Test
    fun `un tunnel en erreur attend une reconnexion`() = runTest {
        val manager = manager()
        val cfg = config(port = freePort())
        manager.markStarting(cfg)
        manager.injectActiveForTest(cfg, sessionId = "s1")
        manager.simulateUnexpectedStopForTest(cfg.id, "Connexion SSH perdue")

        assertTrue(manager.isAwaitingReconnect(cfg.id))
    }

    @Test
    fun `un tunnel en cours de reconnexion attend encore`() = runTest {
        val manager = manager()
        val cfg = config(port = freePort())
        manager.markStarting(cfg)
        manager.injectActiveForTest(cfg, sessionId = "s1")
        manager.simulateUnexpectedStopForTest(cfg.id, "Connexion SSH perdue")
        manager.updateTunnelStatus(cfg.id, TunnelStatus.RECONNECTING)

        assertTrue(manager.isAwaitingReconnect(cfg.id))
    }

    @Test
    fun `un tunnel arrete a la main pendant la reconnexion n attend plus`() = runTest {
        val manager = manager()
        val cfg = config(port = freePort())
        manager.markStarting(cfg)
        manager.injectActiveForTest(cfg, sessionId = "s1")
        manager.simulateUnexpectedStopForTest(cfg.id, "Connexion SSH perdue")
        manager.updateTunnelStatus(cfg.id, TunnelStatus.RECONNECTING)

        manager.stopTunnel(cfg.id)

        assertFalse(manager.isAwaitingReconnect(cfg.id))
    }

    @Test
    fun `un tunnel redemarre a la main n attend plus de reconnexion`() = runTest {
        val manager = manager()
        val cfg = config(port = freePort())
        manager.markStarting(cfg)
        manager.injectActiveForTest(cfg, sessionId = "s1")
        manager.simulateUnexpectedStopForTest(cfg.id, "Connexion SSH perdue")

        manager.markStarting(cfg)
        assertFalse(manager.isAwaitingReconnect(cfg.id), "demarrage en cours")

        manager.injectActiveForTest(cfg, sessionId = "s1")
        assertFalse(manager.isAwaitingReconnect(cfg.id), "tunnel actif")
    }

    @Test
    fun `un tunnel inconnu n attend pas de reconnexion`() = runTest {
        assertFalse(manager().isAwaitingReconnect("inexistant"))
    }
}
