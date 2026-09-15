// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import net.schmizz.sshj.SSHClient
import timber.log.Timber
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Serveur SOCKS5 léger (RFC 1928), commande CONNECT uniquement.
 *
 * Écoute sur localhost:localPort et ouvre un canal SSH direct-tcpip
 * pour chaque connexion entrante, relayant les données dans les deux sens.
 *
 * Protocole supporté :
 *  - Version 5
 *  - Auth : aucune (0x00)
 *  - Commandes : CONNECT (0x01)
 *  - Types d'adresse : IPv4 (0x01), domain name (0x03), IPv6 (0x04)
 */
class Socks5Server(
    private val client: SSHClient,
    private val localPort: Int,
    /**
     * Appelé quand la boucle d'acceptation s'arrête sans que [stop] ait été
     * invoqué, c'est-à-dire sur panne (socket local fermé par le système,
     * erreur d'E/S). Permet au [SshTunnelManager] de basculer le tunnel en
     * ERROR et de le rendre éligible à la reconnexion automatique.
     * Jamais appelé lors d'un arrêt manuel.
     */
    private val onUnexpectedStop: ((String) -> Unit)? = null,
) {

    private var serverSocket: ServerSocket? = null

    @Volatile
    private var running = false

    fun start() {
        running = true
        serverSocket = ServerSocket(localPort, 50, InetAddress.getByName("127.0.0.1"))
        thread(name = "Socks5-Accept-$localPort", isDaemon = true) {
            var failure: String? = null
            while (running) {
                try {
                    val socket = serverSocket?.accept() ?: break
                    thread(name = "Socks5-Handler-$localPort", isDaemon = true) {
                        try {
                            handleConnection(socket)
                        } catch (e: Exception) {
                            Timber.w("SOCKS5 handler error: ${e.message}")
                        } finally {
                            try { socket.close() } catch (_: Exception) {}
                        }
                    }
                } catch (e: IOException) {
                    if (running) {
                        Timber.w("SOCKS5 accept error: ${e.message}")
                        failure = e.message ?: "Erreur d'écoute SOCKS5"
                    }
                    break
                }
            }
            // `running` encore vrai : la boucle est sortie sur panne, pas sur stop().
            if (running) onUnexpectedStop?.invoke(failure ?: "Proxy SOCKS5 arrêté")
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }

    // ── SOCKS5 protocol ──────────────────────────────────────────────────────────

    internal fun handleConnection(socket: Socket) {
        val input  = socket.getInputStream()
        val output = socket.getOutputStream()

        // ── Étape 1 : salutation client ──────────────────────────────────────────
        // Client → [0x05, nMethods, method1, method2, ...]
        val version = input.read()
        if (version != 0x05) {
            Timber.w("SOCKS5: version inconnue $version")
            return
        }
        val nMethods = input.read()
        val methods  = ByteArray(nMethods)
        input.readFully(methods)

        // Vérifie que NO AUTH (0x00) est proposé
        if (!methods.contains(0x00.toByte())) {
            // On refuse : 0xFF = no acceptable method
            output.write(byteArrayOf(0x05, 0xFF.toByte()))
            output.flush()
            Timber.w("SOCKS5: client ne propose pas NO AUTH")
            return
        }

        // Serveur → [0x05, 0x00], aucune authentification requise
        output.write(byteArrayOf(0x05, 0x00))
        output.flush()

        // ── Étape 2 : demande de connexion ───────────────────────────────────────
        // Client → [0x05, cmd, 0x00, addrType, addr..., portHi, portLo]
        val reqVersion = input.read()
        if (reqVersion != 0x05) {
            // Protocole invalide : on ne peut pas envoyer une réponse SOCKS5 valide
            // si la version est inconnue, on ferme simplement la connexion.
            return
        }
        val cmd        = input.read()
        /* reserved */ input.read()
        val addrType   = input.read()

        if (cmd != 0x01) {
            // On ne supporte que CONNECT
            sendSocksError(output, 0x07)
            return
        }

        val targetHost: String = when (addrType) {
            0x01 -> { // IPv4, 4 octets
                val addr = ByteArray(4)
                input.readFully(addr)
                addr.joinToString(".") { (it.toInt() and 0xFF).toString() }
            }
            0x03 -> { // Domain name, 1 octet longueur + N octets
                val len = input.read()
                val domain = ByteArray(len)
                input.readFully(domain)
                String(domain, Charsets.UTF_8)
            }
            0x04 -> { // IPv6, 16 bytes (RFC 1928 §5)
                val addr = ByteArray(16)
                input.readFully(addr)
                InetAddress.getByAddress(addr).hostAddress as String
            }
            else -> {
                sendSocksError(output, 0x08)
                return
            }
        }

        val portHi   = input.read()
        val portLo   = input.read()
        val targetPort = (portHi shl 8) or portLo

        // ── Étape 3 : ouverture canal SSH direct-tcpip ───────────────────────────
        val directConnection = try {
            client.newDirectConnection(targetHost, targetPort)
        } catch (e: Exception) {
            Timber.w("SOCKS5: impossible d'ouvrir canal SSH vers $targetHost:$targetPort, ${e.message}")
            sendSocksError(output, 0x05) // Connection refused
            return
        }

        // Succès → [0x05, 0x00, 0x00, 0x01, 0,0,0,0, 0,0]
        output.write(byteArrayOf(
            0x05, 0x00, 0x00, 0x01,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00
        ))
        output.flush()

        // ── Étape 4 : relay bidirectionnel ───────────────────────────────────────
        val sshIn  = directConnection.inputStream
        val sshOut = directConnection.outputStream

        val clientToSsh = thread(name = "Socks5-Relay-C2S", isDaemon = true) {
            try { relay(input, sshOut) } catch (_: Exception) {}
            finally {
                try { directConnection.close() } catch (_: Exception) {}
                try { socket.close() } catch (_: Exception) {}
            }
        }

        val sshToClient = thread(name = "Socks5-Relay-S2C", isDaemon = true) {
            try { relay(sshIn, output) } catch (_: Exception) {}
            finally {
                try { directConnection.close() } catch (_: Exception) {}
                try { socket.close() } catch (_: Exception) {}
            }
        }

        clientToSsh.join()
        sshToClient.join()
    }

    private fun relay(from: InputStream, to: OutputStream) {
        val buf = ByteArray(8192)
        var n: Int
        while (from.read(buf).also { n = it } != -1) {
            to.write(buf, 0, n)
            to.flush()
        }
    }

    /** Envoie une réponse d'erreur SOCKS5 et ferme. */
    private fun sendSocksError(output: OutputStream, rep: Int) {
        try {
            output.write(byteArrayOf(
                0x05, rep.toByte(), 0x00, 0x01,
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x00
            ))
            output.flush()
        } catch (_: Exception) {}
    }

    // ── Utilitaire ───────────────────────────────────────────────────────────────

    private fun InputStream.readFully(buf: ByteArray) {
        var offset = 0
        while (offset < buf.size) {
            val read = this.read(buf, offset, buf.size - offset)
            if (read == -1) throw IOException("Flux fermé prématurément")
            offset += read
        }
    }
}
