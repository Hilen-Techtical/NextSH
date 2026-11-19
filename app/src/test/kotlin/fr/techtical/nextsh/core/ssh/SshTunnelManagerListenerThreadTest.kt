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
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.schmizz.concurrent.Promise
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.common.LoggerFactory
import net.schmizz.sshj.common.SSHPacket
import net.schmizz.sshj.connection.Connection
import net.schmizz.sshj.connection.ConnectionException
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder
import net.schmizz.sshj.transport.TransportException
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Verifie que l'arret d'un LOCAL_FORWARD termine reellement le thread qui
 * execute `LocalPortForwarder.listen()`.
 *
 * Dans SSHJ 0.38, fermer la ServerSocket ne suffit pas : la boucle d'ecoute
 * avale la SocketException d'`accept()` quand la socket est fermee et repart
 * aussitot, a 100 % d'un coeur, tant que son thread n'est pas interrompu.
 *
 * Les tunnels sont demarres par le vrai [SshTunnelManager.startLocalForward],
 * donc par le vrai cablage du thread dans [ActiveTunnel] et de la boucle
 * [runLocalForwardListener]. Seul le client SSH est simule, rendu par le
 * gestionnaire de sessions simule : son `newLocalPortForwarder` rend le vrai
 * LocalPortForwarder de SSHJ, sur la vraie ServerSocket du manager. Sa
 * connexion est nulle : `listen()` ne la touche qu'a l'arrivee d'un client, et
 * aucun client ne se connecte. Toutes les attentes sont bornees.
 *
 * Un arret voulu ne laisse aucun etat d'erreur et ne dit rien sur
 * [SshTunnelManager.unexpectedStops] (sinon la reconnexion ramenerait ce que
 * l'utilisateur vient de fermer) ; une vraie panne d'ecoute met le tunnel en
 * erreur et le dit.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SshTunnelManagerListenerThreadTest {

    private val serverSockets = mutableListOf<ServerSocket>()
    private val listeners = mutableListOf<Thread>()

    /** Meme si un test echoue, aucun thread ne doit continuer a tourner dans la JVM de test. */
    @AfterEach
    fun cleanup() {
        listeners.forEach { it.interrupt() }
        serverSockets.forEach { runCatching { it.close() } }
    }

    /** Un gestionnaire dont la session s1 rend [client], comme apres une connexion reussie. */
    private fun managerWith(client: SSHClient): SshTunnelManager {
        val sessions = mockk<SshSessionManager>(relaxed = true)
        every { sessions.getClient("s1") } returns client
        return SshTunnelManager(sessionManager = sessions)
    }

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

    /** Ce que la reconnexion entendrait : les tunnels arretes d'eux-memes. */
    private fun TestScope.listen(manager: SshTunnelManager): MutableList<UnexpectedStop> {
        val heard = CopyOnWriteArrayList<UnexpectedStop>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            manager.unexpectedStops.collect { heard += it }
        }
        return heard
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
        val result = startLocalAnnounced(cfg, sessionId = "s1")
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
    fun `un arret manuel termine le thread d ecoute et ne laisse ni etat ni annonce`() = runTest {
        val manager = managerWith(clientWithRealForwarder())
        val heard = listen(manager)
        val cfg = config("t1")
        val thread = manager.startRealTunnel(cfg)

        val result = manager.stopTunnel(cfg.id)

        assertTrue(result is SshResult.Success)
        assertListenerEnds(thread)
        assertNull(manager.tunnelStates.value[cfg.id], "un tunnel arrete manuellement disparait de l'etat")
        assertTrue(manager.getTunnelsForReconnect().isEmpty(), "pas de reconnexion apres un arret volontaire")
        assertTrue(heard.isEmpty(), "un arret voulu ne se dit pas : $heard")
    }

    @Test
    fun `stopAllTunnels termine tous les threads d ecoute et ne laisse ni etat ni annonce`() = runTest {
        val manager = managerWith(clientWithRealForwarder())
        val heard = listen(manager)
        val a = manager.startRealTunnel(config("a"))
        val b = manager.startRealTunnel(config("b"))

        manager.stopAllTunnels()

        assertListenerEnds(a)
        assertListenerEnds(b)
        assertTrue(manager.tunnelStates.value.isEmpty(), "aucun etat ne doit subsister")
        assertTrue(manager.getTunnelsForReconnect().isEmpty(), "pas de reconnexion apres un arret general")
        assertTrue(heard.isEmpty(), "un arret voulu ne se dit pas : $heard")
    }

    @Test
    fun `une panne detectee ailleurs termine aussi le thread d ecoute, et se dit une fois`() = runTest {
        val manager = managerWith(clientWithRealForwarder())
        val heard = listen(manager)
        val cfg = config("t1")
        val thread = manager.startRealTunnel(cfg)

        // Chemin de la coupure du transport SSH : markUnexpectedStop ferme les
        // ressources du tunnel, dont la boucle d'ecoute.
        manager.simulateUnexpectedStopForTest(cfg.id, TUNNEL_SESSION_LOST_MESSAGE)

        assertListenerEnds(thread)
        assertEquals(TunnelStatus.RECONNECTING, manager.tunnelStates.value[cfg.id]?.status)
        // Le thread d'ecoute reveille par l'arret ne doit pas ecraser le message.
        assertEquals(TUNNEL_SESSION_LOST_MESSAGE, manager.tunnelStates.value[cfg.id]?.errorMessage)
        assertEquals(listOf(cfg.id), manager.getTunnelsForReconnect().map { it.id })
        assertEquals(listOf(cfg.id), heard.map { it.tunnelId }, "la reconnexion est prevenue, une seule fois")
    }

    @Test
    fun `un thread reveille par l interruption ne touche pas aux etats`() = runTest {
        val manager = managerWith(clientWithRealForwarder())
        val heard = listen(manager)
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
        assertTrue(heard.isEmpty(), "$heard")
    }

    @Test
    fun `une vraie panne d ecoute declare toujours le tunnel en erreur et le dit`() = runTest {
        val gate = CountDownLatch(1)
        val forwarder = mockk<LocalPortForwarder>()
        every { forwarder.listen() } answers {
            // Attend que le tunnel soit enregistre : la panne arrive apres le demarrage.
            gate.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            throw IOException("db.interne.example:5432 boom")
        }
        val client = mockk<SSHClient>(relaxed = true)
        every { client.newLocalPortForwarder(any(), any()) } returns forwarder
        val manager = managerWith(client)
        val heard = listen(manager)
        val cfg = config("t1")

        val result = manager.startLocalAnnounced(cfg, sessionId = "s1")
        assertTrue(result is SshResult.Success, "le demarrage doit reussir : $result")
        val active = manager.activeTunnels.value.getValue(cfg.id)
        val thread = checkNotNull(active.listenerThread)
        serverSockets += checkNotNull(active.serverSocket)
        listeners += thread

        gate.countDown()

        assertListenerEnds(thread)
        assertEquals(TunnelStatus.RECONNECTING, manager.tunnelStates.value[cfg.id]?.status)
        // Un texte fixe : le message de l'exception vient de SSHJ et peut citer un hote et un port.
        assertEquals(TUNNEL_LISTENER_STOPPED_MESSAGE, manager.tunnelStates.value[cfg.id]?.errorMessage)
        assertEquals(listOf(cfg.id), manager.getTunnelsForReconnect().map { it.id })
        assertEquals(listOf(cfg.id), heard.map { it.tunnelId }, "la reconnexion est prevenue")
    }

    @Test
    fun `une sonde restee sans reponse declare la session entiere sans reponse et le tunnel avec ce motif`() = runTest {
        val gate = CountDownLatch(1)
        val forwarder = mockk<LocalPortForwarder>()
        every { forwarder.listen() } answers {
            gate.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            // Le delai d'ouverture d'un canal : SSHJ chaine une TimeoutException dans une ConnectionException.
            throw ConnectionException("open failed", TimeoutException("Timeout expired: 30000 MILLISECONDS"))
        }
        // La sonde ne recoit rien : le transport est tombe pendant l'attente (cause chainee par SSHJ).
        val silent = Promise<SSHPacket, ConnectionException>("probe", ConnectionException.chainer, LoggerFactory.DEFAULT)
        silent.deliverError(TransportException(DisconnectReason.CONNECTION_LOST, "Connection lost"))
        val connection = mockk<Connection>()
        every { connection.sendGlobalRequest(any(), any(), any()) } returns silent
        val client = mockk<SSHClient>(relaxed = true)
        every { client.newLocalPortForwarder(any(), any()) } returns forwarder
        every { client.isConnected } returns true
        every { client.connection } returns connection
        val sessions = mockk<SshSessionManager>(relaxed = true)
        every { sessions.getClient("s1") } returns client
        every { sessions.livenessCadenceSeconds("s1") } returns 30
        val manager = SshTunnelManager(sessionManager = sessions)
        val heard = listen(manager)
        val cfg = config("t1")

        val result = manager.startLocalAnnounced(cfg, sessionId = "s1")
        assertTrue(result is SshResult.Success, "le demarrage doit reussir : $result")
        val active = manager.activeTunnels.value.getValue(cfg.id)
        val thread = checkNotNull(active.listenerThread)
        serverSockets += checkNotNull(active.serverSocket)
        listeners += thread

        gate.countDown()

        assertListenerEnds(thread)
        // La session est declaree morte (avec le client de ce tunnel), pas seulement ce tunnel : un
        // terminal qui la partage ne resterait pas sur une session morte que la reconnexion reprendrait.
        verify(exactly = 1) { sessions.declareUnresponsive("s1", client) }
        assertEquals(TunnelStatus.RECONNECTING, manager.tunnelStates.value[cfg.id]?.status)
        assertEquals(TUNNEL_NO_ANSWER_MESSAGE, manager.tunnelStates.value[cfg.id]?.errorMessage)
        assertEquals(listOf(cfg.id), heard.map { it.tunnelId }, "la reconnexion est prevenue")
    }

    /** Lance un tunnel dont `listen` finit sur un delai d'ouverture et dont la sonde reste muette, puis rend les elements a verifier. */
    private suspend fun TestScope.silentLinkRun(
        cadence: Int?,
        socket: java.net.Socket? = null,
    ): Triple<SshSessionManager, SSHClient, SshTunnelManager> {
        val gate = CountDownLatch(1)
        val forwarder = mockk<LocalPortForwarder>()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        every { forwarder.listen() } answers {
            gate.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (calls.incrementAndGet() == 1) {
                throw ConnectionException("open failed", TimeoutException("Timeout expired: 30000 MILLISECONDS"))
            }
            // Listening again after a probe the traffic answered: blocks until the tunnel is stopped.
            try {
                Thread.sleep(WAIT_TIMEOUT_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        val silent = Promise<SSHPacket, ConnectionException>("probe", ConnectionException.chainer, LoggerFactory.DEFAULT)
        silent.deliverError(TransportException(DisconnectReason.CONNECTION_LOST, "Connection lost"))
        val connection = mockk<Connection>()
        every { connection.sendGlobalRequest(any(), any(), any()) } returns silent
        val client = mockk<SSHClient>(relaxed = true)
        every { client.newLocalPortForwarder(any(), any()) } returns forwarder
        every { client.isConnected } returns true
        every { client.connection } returns connection
        if (socket != null) every { client.socket } returns socket
        val sessions = mockk<SshSessionManager>(relaxed = true)
        every { sessions.getClient("s1") } returns client
        every { sessions.livenessCadenceSeconds("s1") } returns cadence
        val manager = SshTunnelManager(sessionManager = sessions)
        val cfg = config("t1")

        val result = manager.startLocalAnnounced(cfg, sessionId = "s1")
        assertTrue(result is SshResult.Success, "le demarrage doit reussir : $result")
        val active = manager.activeTunnels.value.getValue(cfg.id)
        val thread = checkNotNull(active.listenerThread)
        serverSockets += checkNotNull(active.serverSocket)
        listeners += thread
        gate.countDown()
        if (socket == null) assertListenerEnds(thread) else Thread.sleep(300)
        return Triple(sessions, client, manager)
    }

    @Test
    fun `une surveillance coupee ou inconnue ne fait pas declarer la session, seul le tunnel s arrete`() = runTest {
        for (cadence in listOf(null, 0, -1)) {
            val (sessions, _, manager) = silentLinkRun(cadence)

            verify(exactly = 0) { sessions.declareUnresponsive(any(), any()) }
            assertEquals(TunnelStatus.ERROR, manager.tunnelStates.value["t1"]?.status, "cadence=$cadence")
            assertEquals(TUNNEL_NO_ANSWER_MESSAGE, manager.tunnelStates.value["t1"]?.errorMessage, "cadence=$cadence")
        }
    }

    @Test
    fun `des octets recus pendant la sonde valent reponse, la session n est pas declaree morte`() = runTest {
        val socket = mockk<InboundActivitySocket>(relaxed = true)
        // Lu avant la sonde, puis apres : le serveur a envoye des donnees entre-temps.
        every { socket.lastInboundAt } returnsMany listOf(100L, 200L, 300L, 400L, 500L, 600L)

        val (sessions, _, manager) = silentLinkRun(cadence = 30, socket = socket)

        verify(exactly = 0) { sessions.declareUnresponsive(any(), any()) }
        assertEquals(TunnelStatus.ACTIVE, manager.tunnelStates.value["t1"]?.status, "le tunnel continue d ecouter")
        manager.stopAllTunnels()
    }

    @Test
    fun `une panne d ecoute ordinaire ne declare pas la session sans reponse`() = runTest {
        val gate = CountDownLatch(1)
        val forwarder = mockk<LocalPortForwarder>()
        every { forwarder.listen() } answers {
            gate.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            throw IOException("boom")
        }
        val client = mockk<SSHClient>(relaxed = true)
        every { client.newLocalPortForwarder(any(), any()) } returns forwarder
        val sessions = mockk<SshSessionManager>(relaxed = true)
        every { sessions.getClient("s1") } returns client
        val manager = SshTunnelManager(sessionManager = sessions)
        val cfg = config("t1")

        val result = manager.startLocalAnnounced(cfg, sessionId = "s1")
        assertTrue(result is SshResult.Success, "le demarrage doit reussir : $result")
        val active = manager.activeTunnels.value.getValue(cfg.id)
        val thread = checkNotNull(active.listenerThread)
        serverSockets += checkNotNull(active.serverSocket)
        listeners += thread

        gate.countDown()

        assertListenerEnds(thread)
        verify(exactly = 0) { sessions.declareUnresponsive(any(), any()) }
        assertEquals(TUNNEL_LISTENER_STOPPED_MESSAGE, manager.tunnelStates.value[cfg.id]?.errorMessage)
    }

    @Test
    fun `un demarrage qui echoue apres le thread libere la socket et le thread`() = runTest {
        val manager = managerWith(clientWithRealForwarder())
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
            manager.startLocalAnnounced(cfg, sessionId = "s1")
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
