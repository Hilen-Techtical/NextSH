// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import android.util.Log
import fr.techtical.nextsh.domain.model.TunnelConfig
import fr.techtical.nextsh.domain.model.TunnelStatus
import fr.techtical.nextsh.domain.model.TunnelType
import fr.techtical.nextsh.shared.domain.model.SshResult
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.LoggerFactory
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder
import net.schmizz.sshj.connection.channel.direct.Parameters
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import timber.log.Timber
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Verifie que l'arret d'un LOCAL_FORWARD termine reellement le thread qui
 * execute `LocalPortForwarder.listen()`.
 *
 * Dans SSHJ 0.38, fermer la ServerSocket ne suffit pas : la boucle d'ecoute
 * avale la SocketException d'`accept()` quand la socket est fermee et repart
 * aussitot, a 100 % d'un coeur, tant que son thread n'est pas interrompu.
 *
 * Les tunnels sont demarres par le vrai [SshTunnelManager.startLocalForward],
 * donc par le vrai cablage du thread dans [ActiveTunnel]. Seul le client SSH
 * est simule : son `newLocalPortForwarder` rend le vrai LocalPortForwarder de
 * SSHJ, sur la vraie ServerSocket du manager. Sa connexion est nulle :
 * `listen()` ne la touche qu'a l'arrivee d'un client, et aucun client ne se
 * connecte. Toutes les attentes sont bornees.
 */
class SshTunnelManagerListenerThreadTest {

    private val serverSockets = mutableListOf<ServerSocket>()
    private val listeners = mutableListOf<Thread>()

    /** Meme si un test echoue, aucun thread ne doit continuer a tourner dans la JVM de test. */
    @AfterEach
    fun cleanup() {
        listeners.forEach { it.interrupt() }
        serverSockets.forEach { runCatching { it.close() } }
    }

    private fun manager() = SshTunnelManager(sessionManager = mockk(relaxed = true))

    /** Reserve un port libre puis le relache, comme les autres tests de tunnels. */
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun config(id: String) = TunnelConfig(
        id         = id,
        label      = "tunnel-$id",
        hostId     = "h1",
        type       = TunnelType.LOCAL_FORWARD,
        localPort  = freePort(),
        remoteHost = "127.0.0.1",
        remotePort = 8080,
    )

    /** Client SSH simule dont le forwarder est le vrai LocalPortForwarder de SSHJ. */
    private fun clientWithRealForwarder(): SSHClient {
        val client = mockk<SSHClient>(relaxed = true)
        every { client.newLocalPortForwarder(any(), any()) } answers {
            LocalPortForwarder(null, firstArg<Parameters>(), secondArg<ServerSocket>(), LoggerFactory.DEFAULT)
        }
        return client
    }

    /** Attend une condition, au plus [WAIT_TIMEOUT_MS] ms. */
    private fun awaitUntil(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + WAIT_TIMEOUT_MS * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    /**
     * Demarre un vrai tunnel par `startLocalForward`, attend que `listen()`
     * soit entre dans sa boucle (donc bloque dans `accept()`), et rend le
     * thread d'ecoute enregistre dans le tunnel actif.
     */
    private suspend fun SshTunnelManager.startRealTunnel(cfg: TunnelConfig): Thread {
        val result = startLocalForward(clientWithRealForwarder(), cfg, sessionId = "s1")
        assertTrue(result is SshResult.Success, "le demarrage doit reussir : $result")

        val active = activeTunnels.value.getValue(cfg.id)
        val thread = checkNotNull(active.listenerThread) { "startLocalForward doit garder le thread d'ecoute" }
        val forwarder = checkNotNull(active.forwarder)
        serverSockets += checkNotNull(active.serverSocket)
        listeners += thread

        assertTrue(awaitUntil { forwarder.isRunning }, "listen() doit etre entre dans sa boucle d'ecoute")
        assertTrue(thread.isAlive, "le thread d'ecoute doit tourner avant l'arret")
        return thread
    }

    private fun assertListenerEnds(thread: Thread) {
        thread.join(WAIT_TIMEOUT_MS)
        assertFalse(thread.isAlive, "le thread d'ecoute doit se terminer, pas tourner a vide")
    }

    @Test
    fun `un arret manuel termine le thread d ecoute et ne laisse aucun etat`() = runTest {
        val manager = manager()
        val cfg = config("t1")
        val thread = manager.startRealTunnel(cfg)

        val result = manager.stopTunnel(cfg.id)

        assertTrue(result is SshResult.Success)
        assertListenerEnds(thread)
        assertNull(manager.tunnelStates.value[cfg.id], "un tunnel arrete manuellement disparait de l'etat")
        assertTrue(manager.getTunnelsForReconnect().isEmpty(), "pas de reconnexion apres un arret volontaire")
    }

    @Test
    fun `stopAllTunnels termine tous les threads d ecoute et ne laisse aucun etat`() = runTest {
        val manager = manager()
        val a = manager.startRealTunnel(config("a"))
        val b = manager.startRealTunnel(config("b"))

        manager.stopAllTunnels()

        assertListenerEnds(a)
        assertListenerEnds(b)
        assertTrue(manager.tunnelStates.value.isEmpty(), "aucun etat ne doit subsister")
        assertTrue(manager.getTunnelsForReconnect().isEmpty(), "pas de reconnexion apres un arret general")
    }

    @Test
    fun `une panne detectee ailleurs termine aussi le thread d ecoute`() = runTest {
        val manager = manager()
        val cfg = config("t1")
        val thread = manager.startRealTunnel(cfg)

        // Chemin de la coupure du transport SSH : markUnexpectedStop ferme les
        // ressources du tunnel, dont la boucle d'ecoute.
        manager.simulateUnexpectedStopForTest(cfg.id, "Connexion SSH perdue")

        assertListenerEnds(thread)
        assertEquals(TunnelStatus.ERROR, manager.tunnelStates.value[cfg.id]?.status)
        // Le thread d'ecoute reveille par l'arret ne doit pas ecraser le message.
        assertEquals("Connexion SSH perdue", manager.tunnelStates.value[cfg.id]?.errorMessage)
        assertEquals(listOf(cfg.id), manager.getTunnelsForReconnect().map { it.id })
    }

    @Test
    fun `un thread reveille par l interruption ne touche pas aux etats`() = runTest {
        val manager = manager()
        val cfg = config("t1")
        val thread = manager.startRealTunnel(cfg)
        val active = manager.activeTunnels.value.getValue(cfg.id)

        // Fait ce que fait stopLocalListener, sans rien marquer ni retirer :
        // le thread, lui, ne doit pas declarer une panne de son propre chef.
        thread.interrupt()
        checkNotNull(active.serverSocket).close()

        assertListenerEnds(thread)
        assertEquals(TunnelStatus.ACTIVE, manager.tunnelStates.value[cfg.id]?.status)
        assertTrue(manager.activeTunnels.value.containsKey(cfg.id), "le tunnel reste a ceux qui l'ont arrete")
    }

    @Test
    fun `une vraie panne d ecoute declare toujours le tunnel en erreur`() = runTest {
        val manager = manager()
        val cfg = config("t1")
        val gate = CountDownLatch(1)
        val forwarder = mockk<LocalPortForwarder>()
        every { forwarder.listen() } answers {
            // Attend que le tunnel soit enregistre : la panne arrive apres le demarrage.
            gate.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            throw IOException("boom")
        }
        val client = mockk<SSHClient>(relaxed = true)
        every { client.newLocalPortForwarder(any(), any()) } returns forwarder

        val result = manager.startLocalForward(client, cfg, sessionId = "s1")
        assertTrue(result is SshResult.Success, "le demarrage doit reussir : $result")
        val active = manager.activeTunnels.value.getValue(cfg.id)
        val thread = checkNotNull(active.listenerThread)
        serverSockets += checkNotNull(active.serverSocket)
        listeners += thread

        gate.countDown()

        assertListenerEnds(thread)
        assertEquals(TunnelStatus.ERROR, manager.tunnelStates.value[cfg.id]?.status)
        assertEquals("boom", manager.tunnelStates.value[cfg.id]?.errorMessage)
        assertEquals(listOf(cfg.id), manager.getTunnelsForReconnect().map { it.id })
    }

    @Test
    fun `un demarrage qui echoue apres le thread libere la socket et le thread`() = runTest {
        val manager = manager()
        val cfg = config("t1")

        // Fait echouer le demarrage apres l'enregistrement du tunnel : le journal
        // d'information de fin de startLocalForward est le dernier appel du try.
        val failingTree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (priority == Log.INFO) throw IllegalStateException("echec simule")
            }
        }
        Timber.plant(failingTree)
        val result = try {
            manager.startLocalForward(clientWithRealForwarder(), cfg, sessionId = "s1")
        } finally {
            Timber.uproot(failingTree)
        }

        assertTrue(result is SshResult.Error, "le demarrage doit echouer : $result")
        val active = manager.activeTunnels.value.getValue(cfg.id)
        val socket = checkNotNull(active.serverSocket)
        val thread = checkNotNull(active.listenerThread)
        serverSockets += socket
        listeners += thread

        assertListenerEnds(thread)
        assertTrue(socket.isClosed, "la socket ne doit plus etre liee au port")
    }

    private companion object {
        const val WAIT_TIMEOUT_MS = 5_000L
    }
}
