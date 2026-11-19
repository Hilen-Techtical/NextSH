// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.vault

import fr.techtical.nextsh.core.logging.blankComments
import fr.techtical.nextsh.testutil.ProjectFiles
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Source guard of the notice about a corrected or unchecked public key on the Vault screen. When it is
 * produced the key is already corrected in the database: the notice must survive the death of the activity
 * during the file picker (saved state, like the rest of that flow) and the app locking meanwhile (shown after
 * the unlock, never dropped). A Compose screen cannot be run in a unit test: this reads the source, comments
 * blanked. A source that cannot be found FAILS (see [ProjectFiles]).
 */
class VaultScreenKeyNoticeGuardTest {

    private val code = blankComments(ProjectFiles.mainSource("ui/vault/VaultScreen.kt").readText())

    @Test
    fun `the notice that waits for the file picker and the notice that waits for the unlock are saved`() {
        for (name in listOf("pubKeyNotice", "pendingKeyNotice")) {
            val declaration = Regex("""var\s+$name\s+by\s+(\w+)""").find(code)
            assertTrue(declaration != null, "$name is gone, update the guard")
            assertEquals("rememberSaveable", declaration!!.groupValues[1], "$name must survive the activity")
        }
    }

    @Test
    fun `a notice that waits for the unlock is shown once the app is unlocked`() {
        val effect = Regex("""LaunchedEffect\(appLocked,\s*pendingKeyNotice\)\s*\{([\s\S]*?)\n    \}""").find(code)
        assertTrue(effect != null, "the effect that shows the pending notice is gone")
        val body = effect!!.groupValues[1]
        assertTrue(body.contains("!appLocked"), "shown only when unlocked")
        assertTrue(body.contains("localMessage = notice") && body.contains("pendingKeyNotice = null"), "shown, then cleared")
    }

    @Test
    fun `the lock never clears a notice without showing it`() {
        // The branch taken when the app is locked after the derivation keeps the notice for the unlock.
        val locked = Regex("""else if \(appLocked\) \{([\s\S]*?)\n\s*\} else \{""").find(code)
        assertTrue(locked != null, "the locked branch of the download is gone, update the guard")
        assertTrue(locked!!.groupValues[1].contains("pendingKeyNotice = notice"), "the notice waits for the unlock")
    }

    @Test
    fun `no key notice goes straight to the local message when it follows a corrected key`() {
        // After the file picker, the notice goes through the pending notice (unlock rule), never to localMessage.
        val launcher = Regex("""val pubKeyExportLauncher[\s\S]*?pubKeyPendingExport = null\r?\n    \}""").find(code)
        assertTrue(launcher != null, "the file picker result is gone, update the guard")
        assertTrue(!launcher!!.value.contains("localMessage = notice"), "the notice must wait for the unlock")
        assertEquals(2, Regex("""pendingKeyNotice = notice""").findAll(launcher.value).count(), "saved and cancelled")
    }
}
