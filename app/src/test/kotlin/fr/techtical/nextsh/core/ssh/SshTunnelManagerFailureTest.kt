// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.domain.model.TunnelConfig
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.domain.model.TunnelStatus
import fr.techtical.nextsh.domain.model.TunnelType
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.ServerSocket

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
}
