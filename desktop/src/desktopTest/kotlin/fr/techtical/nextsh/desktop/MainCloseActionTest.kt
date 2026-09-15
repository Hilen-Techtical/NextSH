// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** One second expressed in `System.nanoTime()` units. */
private const val SECOND_NANOS = 1_000_000_000L

/**
 * Unit tests for [resolveCloseAction]: the pure decision behind the OS
 * close button (X): force-close for an installer, minimize to tray, ask for
 * confirmation, or close immediately, and for [isInstallerCloseArmed], the
 * TTL policy behind the installer latch. Both, plus [CloseAction], are
 * `internal`, reachable from this desktopTest source set without any
 * production-code changes (same pattern as
 * [fr.techtical.nextsh.desktop.components.CmdKPositionTest]).
 */
class MainCloseActionTest {

    @Test
    fun `hides to tray when the setting is on and the tray icon is registered`() {
        val action = resolveCloseAction(
            minimizeToTrayOnClose = true,
            trayRegistered = true,
            activeSessionCount = 0,
            activeTunnelCount = 0,
            closingForInstaller = false,
        )
        assertEquals(CloseAction.HIDE_TO_TRAY, action)
    }

    @Test
    fun `hides to tray even with active sessions and tunnels`() {
        // Minimizing never destroys anything: sessions/tunnels keep running
        // in the background, so there is nothing to confirm.
        val action = resolveCloseAction(
            minimizeToTrayOnClose = true,
            trayRegistered = true,
            activeSessionCount = 3,
            activeTunnelCount = 2,
            closingForInstaller = false,
        )
        assertEquals(CloseAction.HIDE_TO_TRAY, action)
    }

    @Test
    fun `falls back to the normal close path when the tray is not registered`() {
        // The setting is on, but SystemTray.isSupported() was false or the
        // icon failed to register: honoring the setting blindly would
        // strand the user with a hidden window and no way to reopen it.
        val action = resolveCloseAction(
            minimizeToTrayOnClose = true,
            trayRegistered = false,
            activeSessionCount = 0,
            activeTunnelCount = 0,
            closingForInstaller = false,
        )
        assertEquals(CloseAction.CLOSE_DIRECT, action)
    }

    /**
     * The dangerous corner of the matrix: the user asked for minimize-to-tray,
     * the tray is NOT available, and live shells are running. Falling back to
     * the "normal close path" must mean the CONFIRMATION path, not a silent
     * teardown: the user clicked X expecting the window to merely hide, so
     * destroying their sessions without asking would be the worst outcome of
     * the three branches.
     */
    @Test
    fun `asks for confirmation when the tray is unavailable and sessions are active`() {
        val action = resolveCloseAction(
            minimizeToTrayOnClose = true,
            trayRegistered = false,
            activeSessionCount = 2,
            activeTunnelCount = 0,
            closingForInstaller = false,
        )
        assertEquals(CloseAction.CONFIRM_QUIT, action)
    }

    /** Same fallback, driven by tunnels instead of shells. */
    @Test
    fun `asks for confirmation when the tray is unavailable and tunnels are active`() {
        val action = resolveCloseAction(
            minimizeToTrayOnClose = true,
            trayRegistered = false,
            activeSessionCount = 0,
            activeTunnelCount = 1,
            closingForInstaller = false,
        )
        assertEquals(CloseAction.CONFIRM_QUIT, action)
    }

    @Test
    fun `asks for confirmation when sessions are active and tray minimize is off`() {
        val action = resolveCloseAction(
            minimizeToTrayOnClose = false,
            trayRegistered = true,
            activeSessionCount = 1,
            activeTunnelCount = 0,
            closingForInstaller = false,
        )
        assertEquals(CloseAction.CONFIRM_QUIT, action)
    }

    @Test
    fun `asks for confirmation when tunnels are active and tray minimize is off`() {
        val action = resolveCloseAction(
            minimizeToTrayOnClose = false,
            trayRegistered = false,
            activeSessionCount = 0,
            activeTunnelCount = 2,
            closingForInstaller = false,
        )
        assertEquals(CloseAction.CONFIRM_QUIT, action)
    }

    @Test
    fun `closes directly when nothing is active and tray minimize is off`() {
        val action = resolveCloseAction(
            minimizeToTrayOnClose = false,
            trayRegistered = false,
            activeSessionCount = 0,
            activeTunnelCount = 0,
            closingForInstaller = false,
        )
        assertEquals(CloseAction.CLOSE_DIRECT, action)
    }

    @Test
    fun `closes directly when the setting is simply off, tray or not`() {
        val action = resolveCloseAction(
            minimizeToTrayOnClose = false,
            trayRegistered = true,
            activeSessionCount = 0,
            activeTunnelCount = 0,
            closingForInstaller = false,
        )
        assertEquals(CloseAction.CLOSE_DIRECT, action)
    }

    // ── Installer close (MSI upgrade / uninstall) ─────────────────────────────

    /**
     * The worst-case combination for an in-place upgrade: the user has
     * minimize-to-tray ON, the tray IS available, and live shells are
     * running. Honoring the preference here would hide the window while the
     * process keeps every installed file locked: the exact "already
     * running" upgrade failure `util:CloseApplication` exists to avoid, and
     * an installer cannot click the confirm dialog either. The installer
     * branch must therefore beat both.
     */
    @Test
    fun `force closes for the installer even with the tray on and sessions active`() {
        val action = resolveCloseAction(
            minimizeToTrayOnClose = true,
            trayRegistered = true,
            activeSessionCount = 3,
            activeTunnelCount = 2,
            closingForInstaller = true,
        )
        assertEquals(CloseAction.FORCE_CLOSE, action)
    }

    @Test
    fun `force closes for the installer when nothing else would have stopped us`() {
        val action = resolveCloseAction(
            minimizeToTrayOnClose = false,
            trayRegistered = false,
            activeSessionCount = 0,
            activeTunnelCount = 0,
            closingForInstaller = true,
        )
        assertEquals(CloseAction.FORCE_CLOSE, action)
    }

    // ── Full truth table ──────────────────────────────────────────────────────

    private data class Case(
        val minimizeToTrayOnClose: Boolean,
        val trayRegistered: Boolean,
        val activeSessionCount: Int,
        val activeTunnelCount: Int,
        val closingForInstaller: Boolean,
        val expected: CloseAction,
    )

    /**
     * Every one of the 2 x 2 x 2 x 2 x 2 combinations of the five inputs, with
     * the expected outcome spelled out row by row rather than recomputed from
     * the `when` under test. Guards the whole decision surface against a
     * reordering of the branches (which would silently change several rows at
     * once) and makes any future input added to [resolveCloseAction] fail
     * loudly here: which is also why that function declares no default value
     * for [Case.closingForInstaller]: a default would let a new input slip in
     * without any row here ever exercising it.
     */
    @Test
    fun `covers the full input matrix`() {
        val cases = listOf(
            // ── No installer involved (closingForInstaller = false) ──────────
            // Setting OFF → tray registration is irrelevant, only activity matters.
            Case(false, false, 0, 0, false, CloseAction.CLOSE_DIRECT),
            Case(false, false, 0, 1, false, CloseAction.CONFIRM_QUIT),
            Case(false, false, 1, 0, false, CloseAction.CONFIRM_QUIT),
            Case(false, false, 1, 1, false, CloseAction.CONFIRM_QUIT),
            Case(false, true, 0, 0, false, CloseAction.CLOSE_DIRECT),
            Case(false, true, 0, 1, false, CloseAction.CONFIRM_QUIT),
            Case(false, true, 1, 0, false, CloseAction.CONFIRM_QUIT),
            Case(false, true, 1, 1, false, CloseAction.CONFIRM_QUIT),
            // Setting ON but tray unavailable → same rows as "setting OFF":
            // the preference cannot be honored, so it must not shortcut the
            // confirmation either.
            Case(true, false, 0, 0, false, CloseAction.CLOSE_DIRECT),
            Case(true, false, 0, 1, false, CloseAction.CONFIRM_QUIT),
            Case(true, false, 1, 0, false, CloseAction.CONFIRM_QUIT),
            Case(true, false, 1, 1, false, CloseAction.CONFIRM_QUIT),
            // Setting ON and tray registered → hide, unconditionally: nothing is
            // destroyed, so there is nothing to confirm.
            Case(true, true, 0, 0, false, CloseAction.HIDE_TO_TRAY),
            Case(true, true, 0, 1, false, CloseAction.HIDE_TO_TRAY),
            Case(true, true, 1, 0, false, CloseAction.HIDE_TO_TRAY),
            Case(true, true, 1, 1, false, CloseAction.HIDE_TO_TRAY),

            // ── Installer close (closingForInstaller = true) ─────────────────
            // Every single row collapses to FORCE_CLOSE: an MSI upgrade or
            // uninstall cannot answer a confirmation dialog, and a window
            // merely hidden to the tray leaves the process holding its
            // installed files open. The branch must therefore dominate the
            // tray preference, tray availability, and any live activity.
            Case(false, false, 0, 0, true, CloseAction.FORCE_CLOSE),
            Case(false, false, 0, 1, true, CloseAction.FORCE_CLOSE),
            Case(false, false, 1, 0, true, CloseAction.FORCE_CLOSE),
            Case(false, false, 1, 1, true, CloseAction.FORCE_CLOSE),
            Case(false, true, 0, 0, true, CloseAction.FORCE_CLOSE),
            Case(false, true, 0, 1, true, CloseAction.FORCE_CLOSE),
            Case(false, true, 1, 0, true, CloseAction.FORCE_CLOSE),
            Case(false, true, 1, 1, true, CloseAction.FORCE_CLOSE),
            Case(true, false, 0, 0, true, CloseAction.FORCE_CLOSE),
            Case(true, false, 0, 1, true, CloseAction.FORCE_CLOSE),
            Case(true, false, 1, 0, true, CloseAction.FORCE_CLOSE),
            Case(true, false, 1, 1, true, CloseAction.FORCE_CLOSE),
            Case(true, true, 0, 0, true, CloseAction.FORCE_CLOSE),
            Case(true, true, 0, 1, true, CloseAction.FORCE_CLOSE),
            Case(true, true, 1, 0, true, CloseAction.FORCE_CLOSE),
            Case(true, true, 1, 1, true, CloseAction.FORCE_CLOSE),
        )

        for (case in cases) {
            val action = resolveCloseAction(
                minimizeToTrayOnClose = case.minimizeToTrayOnClose,
                trayRegistered = case.trayRegistered,
                activeSessionCount = case.activeSessionCount,
                activeTunnelCount = case.activeTunnelCount,
                closingForInstaller = case.closingForInstaller,
            )
            assertEquals(case.expected, action, "unexpected action for $case")
        }
    }

    // ── Installer-close latch TTL ────────────────────────────────────────────
    //
    // The latch cannot be disarmed by WM_ENDSESSION: WiX 3.11.2 sends it with
    // wParam = TRUE / lParam = ENDSESSION_CLOSEAPP and only after a non-zero
    // WM_QUERYENDSESSION answer, so it confirms that the close is going ahead
    // instead of signalling "the installer gave up", and it is not sent at
    // all when the installer actually does give up. The latch is bounded in
    // time instead: an upgrade the user cancels at the prompt must not leave
    // the app force-closing every later click on X.

    @Test
    fun `installer latch is not armed before any end-session message`() {
        assertFalse(isInstallerCloseArmed(armedAtNanos = null, nowNanos = 123_456_789L))
    }

    @Test
    fun `installer latch is armed immediately after the message`() {
        val armedAt = 5_000L * SECOND_NANOS
        assertTrue(isInstallerCloseArmed(armedAtNanos = armedAt, nowNanos = armedAt))
    }

    @Test
    fun `installer latch is still armed well inside the TTL`() {
        // 59 s: the end-session CloseApplication row alone allows 30 s, and
        // the WM_CLOSE row plus the Abort/Retry/Ignore prompt come after it,
        // so the whole installer window must stay covered.
        val armedAt = 5_000L * SECOND_NANOS
        assertTrue(
            isInstallerCloseArmed(
                armedAtNanos = armedAt,
                nowNanos = armedAt + 59L * SECOND_NANOS,
            ),
        )
    }

    @Test
    fun `installer latch expires past the TTL`() {
        val armedAt = 5_000L * SECOND_NANOS
        assertFalse(
            isInstallerCloseArmed(
                armedAtNanos = armedAt,
                nowNanos = armedAt + 61L * SECOND_NANOS,
            ),
        )
    }

    @Test
    fun `installer latch expires exactly at the TTL boundary`() {
        val armedAt = 5_000L * SECOND_NANOS
        assertFalse(
            isInstallerCloseArmed(
                armedAtNanos = armedAt,
                nowNanos = armedAt + INSTALLER_CLOSE_ARM_TTL_NANOS,
            ),
        )
    }

    @Test
    fun `installer latch survives the nanoTime counter wrapping around`() {
        // System.nanoTime() is documented as an arbitrary origin that may go
        // negative and wrap; the elapsed-by-subtraction idiom under test must
        // stay correct across the boundary (a naive `now > armedAt + TTL`
        // would report "expired" here).
        val armedAt = Long.MAX_VALUE - SECOND_NANOS
        assertTrue(
            isInstallerCloseArmed(
                armedAtNanos = armedAt,
                nowNanos = armedAt + 2L * SECOND_NANOS,
            ),
        )
    }
}
