// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.settings

import fr.techtical.nextsh.core.logging.blankComments
import fr.techtical.nextsh.core.vault.VaultWipeState
import fr.techtical.nextsh.testutil.ProjectFiles
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What the app shows of the wipe of the vault, from its state, and the navigation after it: the modal of the
 * progress, the failure dialog that nothing but its two buttons closes, and the restart of the app on the
 * creation of the vault once the wipe is done and the activity on screen.
 */
class VaultWipeDialogsTest {

    @Test
    fun `nothing is shown when no wipe is under way`() {
        assertEquals(WipeOverlay.NONE, wipeOverlayOf(VaultWipeState.IDLE))
    }

    @Test
    fun `the progress is shown while the wipe runs, and until the app restarts after it is done`() {
        assertEquals(WipeOverlay.PROGRESS, wipeOverlayOf(VaultWipeState.RUNNING))
        // The old screens hold data that is gone: they must not show again in the gap before the restart.
        assertEquals(WipeOverlay.PROGRESS, wipeOverlayOf(VaultWipeState.DONE))
    }

    @Test
    fun `the failure dialog is shown on a failed wipe`() {
        assertEquals(WipeOverlay.FAILED, wipeOverlayOf(VaultWipeState.FAILED))
    }

    @Test
    fun `the app restarts on the creation of the vault only when the wipe is done`() {
        assertTrue(shouldRestartAfterWipe(VaultWipeState.DONE))
        for (state in listOf(VaultWipeState.IDLE, VaultWipeState.RUNNING, VaultWipeState.FAILED)) {
            assertFalse(shouldRestartAfterWipe(state), "$state")
        }
    }

    // ── The dialogs ──────────────────────────────────────────────────────────

    private val source: String
        get() = blankComments(ProjectFiles.mainSource("ui/settings/VaultWipeDialogs.kt").readText())

    private fun squeeze(text: String) = text.replace(Regex("""\s+"""), " ")

    @Test
    fun `neither the progress nor the failure is closed by Back or by a tap outside`() {
        val code = squeeze(source)

        assertEquals(
            2,
            Regex("""DialogProperties\(dismissOnBackPress = false, dismissOnClickOutside = false\)""").findAll(code).count(),
            "the two dialogs",
        )
        assertEquals(2, Regex("""onDismissRequest = \{\}""").findAll(code).count(), "and their dismiss request does nothing")
    }

    @Test
    fun `the failure offers retry and close, and they are the wipe's own`() {
        val code = squeeze(source)

        assertTrue(code.contains("VaultWipeFailedDialog(onRetry = wipe::retry, onClose = wipe::dismissFailure, closeBlocked = closeBlocked)"))
        assertTrue(code.contains("R.string.action_retry") && code.contains("R.string.action_close"))
    }

    @Test
    fun `the texts of the dialogs come from resources`() {
        val code = source

        for (resource in listOf("settings_wipe_progress", "settings_wipe_failed_title", "settings_wipe_failed", "settings_wipe_close_blocked")) {
            assertTrue(code.contains("R.string.$resource"), resource)
        }
        assertFalse(Regex("""Text\(\s*"[^"]""").containsMatchIn(code), "no literal text")
    }
}
