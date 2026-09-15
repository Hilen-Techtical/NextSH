// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.DirectConnection
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import io.mockk.junit5.MockKExtension
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

@ExtendWith(MockKExtension::class)
class Socks5ServerTest {

    // ── helpers ──────────────────────────────────────────────────────────────────

    /** Construit un Socket mocké dont les streams sont les buffers fournis. */
    private fun socketWith(input: InputStream, output: OutputStream): java.net.Socket {
        val socket = mockk<java.net.Socket>(relaxed = true)
        every { socket.getInputStream() } returns input
        every { socket.getOutputStream() } returns output
        return socket
    }

    /**
     * Bytes d'un handshake SOCKS5 complet vers une adresse IPv6 :
     *  - Greeting : version=5, 1 méthode NO AUTH
     *  - Request  : CONNECT, IPv6, <16 bytes adresse>, port Hi/Lo
     */
    private fun buildHandshakeBytes(ipv6Bytes: ByteArray, portHi: Byte, portLo: Byte): ByteArray {
        require(ipv6Bytes.size == 16)
        return byteArrayOf(
            // greeting
            0x05, 0x01, 0x00,
            // request
            0x05, 0x01, 0x00, 0x04,
        ) + ipv6Bytes + byteArrayOf(portHi, portLo)
    }

    // ── tests ────────────────────────────────────────────────────────────────────

    @Test
    fun `IPv6 CONNECT dispatches newDirectConnection with canonical host address`() {
        // ::1, adresse de loopback IPv6, 15 zéros + 0x01
        val ipv6Loopback = ByteArray(15) { 0 } + byteArrayOf(0x01)
        // Java canonical form de ::1 est "0:0:0:0:0:0:0:1"
        val expectedHost = "0:0:0:0:0:0:0:1"
        val expectedPort = 443

        val mockConnection = mockk<DirectConnection>(relaxed = true)
        every { mockConnection.inputStream } returns ByteArrayInputStream(ByteArray(0))
        every { mockConnection.outputStream } returns ByteArrayOutputStream()

        val mockClient = mockk<SSHClient>()
        every { mockClient.newDirectConnection(expectedHost, expectedPort) } returns mockConnection

        val input = ByteArrayInputStream(buildHandshakeBytes(ipv6Loopback, 0x01, 0xBB.toByte()))
        val output = ByteArrayOutputStream()

        val server = Socks5Server(mockClient, 0)
        server.handleConnection(socketWith(input, output))

        verify(exactly = 1) { mockClient.newDirectConnection(expectedHost, expectedPort) }
        val response = output.toByteArray()
        assertTrue(response.size >= 2, "Réponse trop courte : ${response.size} octets")
        assertEquals(0x05.toByte(), response[0], "Version inattendue dans la réponse")
        assertEquals(0x00.toByte(), response[1], "Code d'état inattendu")
    }

    @Test
    fun `IPv6 tronqué, EOF avant 16 bytes, newDirectConnection jamais appelé`() {
        // Seulement 8 des 16 bytes IPv6 + aucun port → EOF prématuré
        val truncated = ByteArrayInputStream(byteArrayOf(
            // greeting
            0x05, 0x01, 0x00,
            // request avec seulement 8 bytes IPv6 (incomplet)
            0x05, 0x01, 0x00, 0x04,
            0x20, 0x01, 0x0d, 0xb8.toByte(), 0x00, 0x00, 0x00, 0x00,
            // stream s'arrête ici, 8 bytes manquants
        ))
        val output = ByteArrayOutputStream()

        val mockClient = mockk<SSHClient>(relaxed = true)

        val server = Socks5Server(mockClient, 0)
        // readFully lève IOException sur EOF prématuré. Le thread handler le catch normalement
        try { server.handleConnection(socketWith(truncated, output)) } catch (_: java.io.IOException) {}

        verify(exactly = 0) { mockClient.newDirectConnection(any(), any()) }
    }

    @Test
    fun `IPv6 CONNECT, newDirectConnection lève une exception, réponse SOCKS5 connection refused`() {
        val ipv6Loopback = ByteArray(15) { 0 } + byteArrayOf(0x01)

        val mockClient = mockk<SSHClient>()
        every { mockClient.newDirectConnection(any(), any()) } throws java.io.IOException("refused")

        val input = ByteArrayInputStream(buildHandshakeBytes(ipv6Loopback, 0x01, 0xBB.toByte()))
        val output = ByteArrayOutputStream()

        val server = Socks5Server(mockClient, 0)
        server.handleConnection(socketWith(input, output))

        // Réponse complète attendue : greeting [0x05, 0x00] + erreur 0x05 (connection refused)
        val expected = byteArrayOf(
            0x05, 0x00,
            0x05, 0x05, 0x00, 0x01,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00,
        )
        assertArrayEquals(expected, output.toByteArray())
    }
}
