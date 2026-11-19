// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.settings

import fr.techtical.nextsh.core.logging.blankComments
import fr.techtical.nextsh.testutil.ProjectFiles
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Smoke alarm on the one thing a unit test cannot run: the screen of the settings. The wipe of the vault is
 * asked in two steps, like on Desktop (a dialog that says what is lost, then a second one where the word
 * `CONFIRMER` is typed), and nothing but the second one starts it. `SettingsViewModelTest` proves what the
 * view model does; this checks that the composable only reaches it from the right place.
 *
 * Same compromise and same rule as `BiometricPromptWiringGuardTest`: a cheap source check. A source
 * that cannot be found FAILS the test (see [ProjectFiles]).
 */
class SettingsScreenWipeGuardTest {

    /** The code of the settings screen, comments blanked. */
    private val screen: String
        get() = blankComments(ProjectFiles.mainSource("ui/settings/SettingsScreen.kt").readText())

    private fun count(code: String, needle: String) = Regex(Regex.escape(needle)).findAll(code).count()

    @Test
    fun `the vault no longer has to be open to wipe, so the screen has no refusal to collect`() {
        val code = screen

        assertFalse(code.contains("authRequiredEvent"), "the refusal of an expired window is gone")
        assertFalse(code.contains("settings_wipe_vault_auth_expired"), "and so is its sentence")
    }

    @Test
    fun `the word to type is the one of the Desktop`() {
        assertEquals("CONFIRMER", WIPE_CONFIRMATION_TOKEN)
    }

    @Test
    fun `the texts of the screen come from resources, not from the code`() {
        val code = screen

        for (literal in listOf("Zone dangereuse", "Action irréversible", "Connexion, terminal")) {
            assertFalse(code.contains(literal), "$literal is a resource")
        }
        for (resource in listOf("settings_subtitle", "section_danger_zone", "settings_danger_zone_hint")) {
            assertTrue(code.contains("R.string.$resource"), resource)
        }
        for (resource in listOf("settings_wipe_token_title", "settings_wipe_token_body", "settings_wipe_token_field_label")) {
            assertTrue(code.contains("R.string.$resource"), resource)
        }
    }

    @Test
    fun `the wipe is only asked from the second dialog, when the word is exact`() {
        val code = screen

        assertEquals(1, count(code, "viewModel.wipeVault()"), "one call of wipeVault")
        val first = code.substring(code.indexOf("if (showWipeDialog)"), code.indexOf("if (showWipeTokenDialog)"))
        assertFalse(first.contains("wipeVault"), "the first dialog only opens the second one")
        assertTrue(first.contains("showWipeTokenDialog = true"), "and it does")

        val second = code.substring(code.indexOf("if (showWipeTokenDialog)"))
        val call = second.indexOf("viewModel.wipeVault()")
        assertTrue(call > 0, "the call is inside the second dialog")
        assertTrue(call < second.indexOf("dismissButton"), "the call is the one of the confirm button")
        assertTrue(
            second.substring(0, call).substringAfterLast("onClick").contains("wipeToken == WIPE_CONFIRMATION_TOKEN"),
            "armed only by the exact word, in the handler too and not only in the look of the button",
        )
        assertTrue(second.contains("enabled = wipeToken == WIPE_CONFIRMATION_TOKEN"), "the button is disabled until then")
        // The row of the danger zone only opens the first dialog.
        assertTrue(code.contains("onClick = { showWipeDialog = true }"), "the row opens the dialog")
    }

    @Test
    fun `the typed word is not kept when the second dialog goes away`() {
        val second = screen.let { it.substring(it.indexOf("if (showWipeTokenDialog)")) }

        assertTrue(count(second, "wipeToken = \"\"") >= 2, "cleared on confirm and on cancel")
    }
}
