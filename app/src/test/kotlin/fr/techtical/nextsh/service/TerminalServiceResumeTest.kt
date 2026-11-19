// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.service

import fr.techtical.nextsh.core.logging.blankComments
import fr.techtical.nextsh.core.ssh.SshTerminalSession
import fr.techtical.nextsh.domain.model.AuthType
import fr.techtical.nextsh.domain.model.Host
import fr.techtical.nextsh.domain.model.SessionStatus
import fr.techtical.nextsh.testutil.ProjectFiles
import fr.techtical.nextsh.ui.sessions.SessionTab
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The foreground service is asked for once when a tab opens, after suspensions (connection, key
 * touch, host key). If the user left the app meanwhile the system refuses the start, and nothing
 * asked again: the session died at the next trip to the background. `MainActivity` now asks again
 * each time it comes on screen, through [resumeTerminalService].
 */
class TerminalServiceResumeTest {

    private fun tab(id: String, status: SessionStatus = SessionStatus.CONNECTED) = SessionTab(
        sessionId = "session-$id",
        host = Host(
            id = "host-$id",
            label = id,
            hostname = "$id.example.com",
            username = "user",
            authType = AuthType.PASSWORD,
            credentialId = "cred-$id",
        ),
        terminalSession = mockk<SshTerminalSession>(),
        status = status,
    )

    private fun controller(accepts: Boolean = true) = mockk<TerminalServiceController>().also {
        every { it.ensureRunning() } returns accepts
    }

    @Test
    fun `a connected tab makes the service run again when the app comes on screen`() {
        val controller = controller()

        resumeTerminalService(listOf(tab("a")), controller)

        verify(exactly = 1) { controller.ensureRunning() }
    }

    @Test
    fun `one connected tab among dead ones is enough`() {
        val controller = controller()

        resumeTerminalService(listOf(tab("a", SessionStatus.DISCONNECTED), tab("b"), tab("c", SessionStatus.ERROR)), controller)

        verify(exactly = 1) { controller.ensureRunning() }
    }

    @Test
    fun `without a tab the service is not asked for`() {
        val controller = controller()

        resumeTerminalService(emptyList(), controller)

        verify(exactly = 0) { controller.ensureRunning() }
    }

    @Test
    fun `tabs that are all disconnected do not start a service that would stop at once`() {
        val controller = controller()

        resumeTerminalService(
            listOf(tab("a", SessionStatus.DISCONNECTED), tab("b", SessionStatus.CONNECTING), tab("c", SessionStatus.ERROR)),
            controller,
        )

        verify(exactly = 0) { controller.ensureRunning() }
    }

    @Test
    fun `a refusal of the system is not an error for the caller, it asks again at the next start`() {
        val controller = controller(accepts = false)

        resumeTerminalService(listOf(tab("a")), controller)
        resumeTerminalService(listOf(tab("a")), controller)

        verify(exactly = 2) { controller.ensureRunning() }
    }

    // ── MainActivity: the one thing the unit tests cannot run ───────────────

    /**
     * Smoke alarm, same compromise and same rule as `ShortcutWiringGuardTest`: a cheap source check,
     * and a source that cannot be found FAILS the test (see [ProjectFiles]). An Activity and its
     * lifecycle observer are not something a unit test can start.
     */
    @Test
    fun `MainActivity asks again for the service at each start, from the tabs of the store`() {
        val main = blankComments(ProjectFiles.mainSource("MainActivity.kt").readText())

        val start = main.indexOf("override fun onStart(")
        assertTrue(start >= 0, "onStart of the lifecycle observer not found")
        val end = main.indexOf("override fun onStop(", start)
        assertTrue(end > start, "onStop of the lifecycle observer not found")
        val onStart = main.substring(start, end).replace(Regex("""\s+"""), " ")

        assertTrue(
            onStart.contains("resumeTerminalService(terminalTabStore.state.value.tabs, terminalService)"),
            "the service is asked for again from the tabs the store holds now",
        )
        assertTrue(main.contains("@Inject lateinit var terminalTabStore: TerminalTabStore"))
        assertTrue(main.contains("@Inject lateinit var terminalService: TerminalServiceController"))
    }
}
