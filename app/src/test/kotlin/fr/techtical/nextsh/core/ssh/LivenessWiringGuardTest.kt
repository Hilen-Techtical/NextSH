// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.core.logging.blankComments
import fr.techtical.nextsh.testutil.ProjectFiles
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The production wiring of the liveness watchdog, which the unit tests of its pieces cannot see:
 * the instrumented socket is the one SSHJ connects, the watchdog reads its date, and the service
 * runs the watchdog. A cheap source check, like `SshSessionManagerWiringGuardTest`: a source that
 * cannot be found fails the test (see [ProjectFiles]).
 */
class LivenessWiringGuardTest {

    private fun code(pathUnderNextSh: String): String =
        blankComments(ProjectFiles.mainSource(pathUnderNextSh).readText())

    @Test
    fun `createSshClient sets the instrumented socket factory before it connects`() {
        val source = code("core/ssh/SshSessionManager.kt")
        val start = source.indexOf("private suspend fun createSshClient(")
        assertTrue(start >= 0, "createSshClient not found")
        val body = source.substring(start)
        val factory = body.indexOf("client.socketFactory = InboundActivitySocketFactory()")
        val connect = body.indexOf("client.connect(")
        assertTrue(factory >= 0, "createSshClient must set client.socketFactory = InboundActivitySocketFactory()")
        assertTrue(connect >= 0, "client.connect( not found")
        assertTrue(factory < connect, "the socket factory must be set before client.connect(")
    }

    @Test
    fun `the watchdog reads the date of the instrumented socket`() {
        val source = code("core/ssh/TunnelLivenessWatchdog.kt")
        assertTrue(
            Regex("""inboundAt\s*=\s*\{\s*\(it\.socket as\? InboundActivitySocket\)\?\.lastInboundAt\s*}""")
                .containsMatchIn(source),
            "the injected constructor must read InboundActivitySocket.lastInboundAt",
        )
    }

    @Test
    fun `the foreground service runs the watchdog once`() {
        val source = code("service/TunnelForegroundService.kt")
        assertTrue(source.contains("lateinit var livenessWatchdog: TunnelLivenessWatchdog"), "the watchdog must be injected")
        assertTrue(
            Regex("""if \(!livenessStarted\)\s*\{\s*livenessStarted = true\s*serviceScope\.launch \{ livenessWatchdog\.observe\(\) }""")
                .containsMatchIn(source),
            "onStartCommand must launch livenessWatchdog.observe() once, behind the livenessStarted guard",
        )
    }
}
