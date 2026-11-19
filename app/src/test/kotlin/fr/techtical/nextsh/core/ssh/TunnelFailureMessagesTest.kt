// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.core.logging.blankComments
import fr.techtical.nextsh.domain.model.TunnelConfig
import fr.techtical.nextsh.domain.model.TunnelStatus
import fr.techtical.nextsh.domain.model.TunnelType
import fr.techtical.nextsh.domain.repository.HostRepository
import fr.techtical.nextsh.shared.domain.model.SshErrorCode
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.shared.domain.ssh.TunnelSessionProvider
import fr.techtical.nextsh.shared.domain.usecase.StartTunnelUseCase
import fr.techtical.nextsh.testutil.ProjectFiles
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.ConnectionException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.ServerSocket

/**
 * What a tunnel carries when its start or its stop fails: a fixed text that the card translates
 * ([tunnelMessageRes]), never the message of an exception (it comes from SSHJ or the system, in
 * English, and can name a host, an address or a port), nor a port number or a session identifier.
 *
 * The starts are the real ones of [SshTunnelManager], with a mocked SSH client that throws a message
 * carrying a host and a port; what is stored on the card and what the result says are both read.
 */
class TunnelFailureMessagesTest {

    private val heldSockets = mutableListOf<ServerSocket>()

    @AfterEach
    fun cleanup() {
        heldSockets.forEach { runCatching { it.close() } }
    }

    /** The text of an exception that must reach neither the card nor the result. */
    private val raw = "db.interne.example:5432 Connection refused (secret detail)"

    private fun managerWith(client: SSHClient? = null): SshTunnelManager {
        val sessions = mockk<SshSessionManager>(relaxed = true)
        every { sessions.getClient("s1") } returns client
        return SshTunnelManager(sessionManager = sessions)
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun config(
        id: String = "t1",
        type: TunnelType = TunnelType.LOCAL_FORWARD,
        localPort: Int = freePort(),
    ) = TunnelConfig(
        id = id,
        label = "tunnel-$id",
        hostId = "h1",
        type = type,
        localPort = localPort,
        remoteHost = "127.0.0.1",
        remotePort = 8080,
    )

    /** The error of a start, on both the result and the card: the same fixed text, and it has a translation. */
    private fun assertFailedWith(expected: String, result: SshResult<Unit>, manager: SshTunnelManager, id: String = "t1") {
        assertTrue(result is SshResult.Error, "$result")
        val message = (result as SshResult.Error).message
        assertEquals(expected, message)
        val state = manager.getTunnelState(id)
        assertEquals(TunnelStatus.ERROR, state?.status)
        assertEquals(expected, state?.errorMessage)
        assertNotNull(tunnelMessageRes(expected), "$expected has no string resource")
        assertFalse(message.contains("db.interne") || message.contains("5432") || message.contains("secret"), message)
    }

    // ── A start that throws ───────────────────────────────────────────────────

    @Test
    fun `a local start that throws stores a fixed text, not the message of the exception`() = runTest {
        val client = mockk<SSHClient>(relaxed = true)
        every { client.newLocalPortForwarder(any(), any()) } throws IOException(raw)
        val manager = managerWith(client)

        val result = manager.startLocalAnnounced(config(), sessionId = "s1")

        assertFailedWith(TUNNEL_START_FAILED_MESSAGE, result, manager)
        assertEquals(SshErrorCode.UNKNOWN, (result as SshResult.Error).code)
    }

    @Test
    fun `a remote start that throws stores a fixed text, not the message of the exception`() = runTest {
        val client = mockk<SSHClient>(relaxed = true)
        every { client.remotePortForwarder } throws ConnectionException(raw)
        val manager = managerWith(client)

        val result = manager.startRemoteAnnounced(config(type = TunnelType.REMOTE_FORWARD), sessionId = "s1")

        assertFailedWith(TUNNEL_START_FAILED_MESSAGE, result, manager)
    }

    @Test
    fun `a start called for a tunnel of another type says the start failed, not the name of the type`() = runTest {
        val manager = managerWith(mockk<SSHClient>(relaxed = true))

        val local = manager.startLocalAnnounced(config(id = "a", type = TunnelType.REMOTE_FORWARD), sessionId = "s1")
        val remote = manager.startRemoteAnnounced(config(id = "b", type = TunnelType.LOCAL_FORWARD), sessionId = "s1")
        val socks = manager.startDynamicAnnounced(config(id = "c", type = TunnelType.LOCAL_FORWARD), sessionId = "s1")

        assertFailedWith(TUNNEL_START_FAILED_MESSAGE, local, manager, "a")
        assertFailedWith(TUNNEL_START_FAILED_MESSAGE, remote, manager, "b")
        assertFailedWith(TUNNEL_START_FAILED_MESSAGE, socks, manager, "c")
        listOf(local, remote, socks).forEach {
            val message = (it as SshResult.Error).message
            assertFalse(message.contains("FORWARD") || message.contains("SOCKS"), message)
        }
    }

    // ── A port already used, a session or a tunnel that is gone ───────────────

    @Test
    fun `a local port already used is a fixed text without the number of the port`() = runTest {
        val held = ServerSocket(0).also { heldSockets += it }
        val manager = managerWith(mockk<SSHClient>(relaxed = true))

        val local = manager.startLocalAnnounced(config(id = "a", localPort = held.localPort), sessionId = "s1")
        val socks = manager.startDynamicAnnounced(
            config(id = "b", type = TunnelType.DYNAMIC_SOCKS5, localPort = held.localPort),
            sessionId = "s1",
        )

        assertFailedWith(TUNNEL_PORT_IN_USE_MESSAGE, local, manager, "a")
        assertFailedWith(TUNNEL_PORT_IN_USE_MESSAGE, socks, manager, "b")
        assertEquals(SshErrorCode.PORT_IN_USE, (local as SshResult.Error).code)
        assertEquals(SshErrorCode.PORT_IN_USE, (socks as SshResult.Error).code)
        assertFalse((local as SshResult.Error).message.contains(held.localPort.toString()))
    }

    @Test
    fun `a start on a session that is gone does not name the session`() = runTest {
        val manager = managerWith(client = null)

        val result = manager.startLocalAnnounced(config(), sessionId = "s1")

        assertTrue(result is SshResult.Error, "$result")
        assertEquals(TUNNEL_SESSION_NOT_FOUND_MESSAGE, (result as SshResult.Error).message)
        assertNotNull(tunnelMessageRes(result.message))
        assertFalse(result.message.contains("s1"), result.message)
        // The card says it too: it used to stay in STARTING for ever.
        val state = manager.getTunnelState("t1")
        assertEquals(TunnelStatus.ERROR, state?.status)
        assertEquals(TUNNEL_SESSION_NOT_FOUND_MESSAGE, state?.errorMessage)
    }

    @Test
    fun `stopping a tunnel that is not known does not name it`() = runTest {
        val manager = managerWith()

        val result = manager.stopTunnel("tunnel-id-that-must-not-leak")

        assertTrue(result is SshResult.Error, "$result")
        assertEquals(TUNNEL_NOT_FOUND_MESSAGE, (result as SshResult.Error).message)
        assertNotNull(tunnelMessageRes(result.message))
        assertFalse(result.message.contains("must-not-leak"), result.message)
    }

    @Test
    fun `a stop that throws says it failed, not what the exception said`() = runTest {
        val manager = managerWith()
        val cfg = config()
        val failing = object : ServerSocket() {
            override fun close() {
                throw IOException(raw)
            }
        }
        manager.markStarting(cfg)
        manager.injectActiveForTest(cfg, sessionId = "s1", serverSocket = failing)

        val result = manager.stopTunnel(cfg.id)

        assertTrue(result is SshResult.Error, "$result")
        assertEquals(TUNNEL_STOP_FAILED_MESSAGE, (result as SshResult.Error).message)
        assertNotNull(tunnelMessageRes(result.message))
        assertFalse(result.message.contains("db.interne"), result.message)
    }

    // ── The shared start, whose text the app cannot change ────────────────────

    @Test
    fun `a tunnel whose host is gone carries the text the app translates`() = runTest {
        // The text is a constant of the shared module (StartTunnelUseCase), which this branch does not
        // change: the constant of the app must be that very text, or the card shows it in French.
        val manager = managerWith()
        val hostRepository = mockk<HostRepository>()
        coEvery { hostRepository.getById(any()) } returns null
        val startTunnel = StartTunnelUseCase(manager, hostRepository, mockk<TunnelSessionProvider>())
        val cfg = config()

        val result = startTunnel(cfg)

        assertTrue(result is SshResult.Error, "$result")
        assertEquals(TUNNEL_HOST_NOT_FOUND_MESSAGE, (result as SshResult.Error).message)
        assertEquals(TUNNEL_HOST_NOT_FOUND_MESSAGE, manager.getTunnelState(cfg.id)?.errorMessage)
        assertNotNull(tunnelMessageRes(result.message))
    }

    // ── The sources: no exception text is ever stored ─────────────────────────

    /** The code of [fileName] of `core/ssh`, comments blanked; a source that cannot be found FAILS the test. */
    private fun sourceOf(fileName: String): String = blankComments(ProjectFiles.mainSource("core/ssh/$fileName").readText())

    @Test
    fun `the code that makes the messages of a tunnel never reads the message of an exception`() {
        listOf("SshTunnelManager.kt", "Socks5Server.kt", "AndroidTunnelSessionProvider.kt").forEach { file ->
            val code = sourceOf(file)
            assertTrue(code.length > 1_000, "$file: the guard reads nothing")
            val offenders = Regex("""\b(?:e|ex|t|cause|closeError)\s*\??\.\s*(?:message|localizedMessage)\b""")
                .findAll(code).map { it.value }.toList()
            assertTrue(offenders.isEmpty(), "$file stores or shows the message of an exception: $offenders")
            assertFalse(code.contains("Erreur inconnue"), "$file still has the French fallback of a raw message")
        }
    }
}
