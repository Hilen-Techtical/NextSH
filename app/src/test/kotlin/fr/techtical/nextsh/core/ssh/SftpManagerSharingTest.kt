// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.SFTPClient
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Le client SFTP est partage entre l'explorateur et le service de transfert.
 *
 * Sans comptage de references, quitter l'ecran de l'explorateur fermait le
 * client sous les pieds d'un telechargement en cours : le transfert mourait des
 * que l'utilisateur naviguait ailleurs, ce qui vide de son sens le service au
 * premier plan.
 */
class SftpManagerSharingTest {

    private val sftpClient: SFTPClient = mockk(relaxed = true)

    private fun manager(): SftpManager {
        val sshClient: SSHClient = mockk(relaxed = true)
        every { sshClient.newSFTPClient() } returns sftpClient
        val sessionManager: SshSessionManager = mockk(relaxed = true)
        every { sessionManager.getClient("s1") } returns sshClient
        return SftpManager(sessionManager)
    }

    @Test
    fun `le client survit au depart d un seul utilisateur`() = runTest {
        val manager = manager()

        manager.openSftp("s1")   // explorateur
        manager.openSftp("s1")   // service de transfert

        manager.closeSftp("s1")  // l'utilisateur quitte l'ecran

        assertTrue(manager.isOpen("s1"), "le transfert en cours doit garder le client ouvert")
        verify(exactly = 0) { sftpClient.close() }
    }

    @Test
    fun `le client se ferme au dernier partant`() = runTest {
        val manager = manager()

        manager.openSftp("s1")
        manager.openSftp("s1")
        manager.closeSftp("s1")
        manager.closeSftp("s1")

        assertFalse(manager.isOpen("s1"))
        verify(exactly = 1) { sftpClient.close() }
    }

    @Test
    fun `un seul utilisateur ferme immediatement`() = runTest {
        val manager = manager()

        manager.openSftp("s1")
        manager.closeSftp("s1")

        assertFalse(manager.isOpen("s1"))
        verify(exactly = 1) { sftpClient.close() }
    }

    @Test
    fun `fermer une session jamais ouverte ne casse rien`() = runTest {
        val manager = manager()

        manager.closeSftp("inconnue")

        assertFalse(manager.isOpen("inconnue"))
    }

    @Test
    fun `rouvrir apres fermeture repart d un compteur propre`() = runTest {
        val manager = manager()

        manager.openSftp("s1")
        manager.closeSftp("s1")
        manager.openSftp("s1")

        assertTrue(manager.isOpen("s1"))
        // Une seule fermeture doit suffire : le compteur ne doit pas avoir
        // garde de trace du cycle precedent.
        manager.closeSftp("s1")
        assertFalse(manager.isOpen("s1"))
    }
}
