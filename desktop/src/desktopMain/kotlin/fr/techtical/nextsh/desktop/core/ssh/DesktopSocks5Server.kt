// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.ssh

import fr.techtical.nextsh.shared.util.Logger
import net.schmizz.sshj.SSHClient
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

private const val TAG = "DesktopSocks5Server"

/**
 * Serveur SOCKS5 léger (RFC 1928) : commande CONNECT uniquement.
 *
 * Portage Desktop 1:1 de `app/core/ssh/Socks5Server.kt`. Pas de dépendance
 * Android SDK : uniquement `net.schmizz.sshj.*` + JDK standard. La seule
 * différence avec Android est le logger (`fr.techtical.nextsh.shared.util.Logger`
 * au lieu de Timber) : la logique du protocole reste strictement identique.
 *
 * Écoute sur `127.0.0.1:localPort` et ouvre un canal SSH direct-tcpip pour
 * chaque connexion entrante, relayant les données dans les deux sens.
 *
 * Protocole supporté :
 *  - Version 5
 *  - Auth : aucune (0x00)
 *  - Commandes : CONNECT (0x01)
 *  - Types d'adresse : IPv4 (0x01), domain name (0x03)
 */
class DesktopSocks5Server(
    private val client: SSHClient,
    private val localPort: Int,
) {

    private var serverSocket: ServerSocket? = null

    @Volatile
    private var running = false

    fun start() {
        running = true
        serverSocket = ServerSocket(localPort, 50, InetAddress.getByName("127.0.0.1"))
        thread(name = "Socks5-Accept-$localPort", isDaemon = true) {
            while (running) {
                try {
                    val socket = serverSocket?.accept() ?: break
                    thread(name = "Socks5-Handler-$localPort", isDaemon = true) {
                        try {
                            handleConnection(socket)
                        } catch (e: Exception) {
                            Logger.w(TAG, "SOCKS5 handler error: ${e.message}")
                        } finally {
                            try { socket.close() } catch (_: Exception) {}
                        }
                    }
                } catch (e: IOException) {
                    if (running) Logger.w(TAG, "SOCKS5 accept error: ${e.message}")
                    break
                }
            }
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }

    // ── SOCKS5 protocol ──────────────────────────────────────────────────────────

    private fun handleConnection(socket: Socket) {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()

        // Étape 1 : salutation client
        val version = input.read()
        if (version != 0x05) {
            Logger.w(TAG, "SOCKS5: version inconnue $version")
            return
        }
        val nMethods = input.read()
        val methods = ByteArray(nMethods)
        input.readFully(methods)

        if (!methods.contains(0x00.toByte())) {
            output.write(byteArrayOf(0x05, 0xFF.toByte()))
            output.flush()
            Logger.w(TAG, "SOCKS5: client ne propose pas NO AUTH")
            return
        }

        output.write(byteArrayOf(0x05, 0x00))
        output.flush()

        // Étape 2 : demande de connexion
        val reqVersion = input.read()
        if (reqVersion != 0x05) return
        val cmd = input.read()
        /* reserved */ input.read()
        val addrType = input.read()

        if (cmd != 0x01) {
            sendSocksError(output, 0x07)
            return
        }

        val targetHost: String = when (addrType) {
            0x01 -> {
                val addr = ByteArray(4)
                input.readFully(addr)
                addr.joinToString(".") { (it.toInt() and 0xFF).toString() }
            }
            0x03 -> {
                val len = input.read()
                val domain = ByteArray(len)
                input.readFully(domain)
                String(domain, Charsets.UTF_8)
            }
            0x04 -> {
                // IPv6 non supporté : même limitation que l'Android
                sendSocksError(output, 0x08)
                return
            }
            else -> {
                sendSocksError(output, 0x08)
                return
            }
        }

        val portHi = input.read()
        val portLo = input.read()
        val targetPort = (portHi shl 8) or portLo

        // Étape 3 : ouverture canal SSH direct-tcpip
        val directConnection = try {
            client.newDirectConnection(targetHost, targetPort)
        } catch (e: Exception) {
            Logger.w(TAG, "SOCKS5: impossible d'ouvrir canal SSH vers $targetHost:$targetPort : ${e.message}")
            sendSocksError(output, 0x05)
            return
        }

        output.write(byteArrayOf(
            0x05, 0x00, 0x00, 0x01,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00,
        ))
        output.flush()

        // Étape 4 : relay bidirectionnel
        val sshIn = directConnection.inputStream
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

    private fun sendSocksError(output: OutputStream, rep: Int) {
        try {
            output.write(byteArrayOf(
                0x05, rep.toByte(), 0x00, 0x01,
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x00,
            ))
            output.flush()
        } catch (_: Exception) {}
    }

    private fun InputStream.readFully(buf: ByteArray) {
        var offset = 0
        while (offset < buf.size) {
            val read = this.read(buf, offset, buf.size - offset)
            if (read == -1) throw IOException("Flux fermé prématurément")
            offset += read
        }
    }
}
