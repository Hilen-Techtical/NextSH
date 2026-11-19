// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Le message de l'application est `NextShMessage` : ni le Snackbar Material (qui n'est pas lu par
 * TalkBack comme celui de l'application, et ne suit pas la charte), ni le Toast Android (qui se
 * posait par-dessus la barre de progression et durait trop longtemps). Ce garde lit les sources de
 * `app/src/main`, commentaires retirés, pour qu'aucun des deux ne revienne.
 */
class NoMaterialSnackbarGuardTest {

    private val sources: Map<String, String> = SourceGuardSupport.mainSources("")

    @Test
    fun `no source of the app uses the Material Snackbar or the Android Toast`() {
        val forbidden = listOf(
            "SnackbarHost(",
            "SnackbarHostState",
            "SnackbarDuration",
            "showSnackbar(",
            "Toast.makeText",
            "import androidx.compose.material3.Snackbar",
            "import android.widget.Toast",
        )
        val offences = sources.flatMap { (path, code) ->
            forbidden.filter { code.contains(it) }.map { "$path: $it" }
        }

        assertEquals(emptyList<String>(), offences, "the message of the app is NextShMessage")
    }

    @Test
    fun `every screen that shows a message hands the dismissal back to its state`() {
        val screens = sources.filter { (path, code) ->
            path.startsWith("ui/") && !path.endsWith("NextShMessage.kt") && code.contains("NextShMessage(")
        }

        // The eight screens migrated from the Material Snackbar each show a NextShMessage. A count
        // would also pass when one of them lost its message, so the list is explicit.
        val migrated = listOf(
            "ui/vault/VaultScreen.kt",
            "ui/vault/KeyImportScreen.kt",
            "ui/hosts/HostListScreen.kt",
            "ui/hosts/HostImportScreen.kt",
            "ui/sessions/TerminalScreen.kt",
            "ui/snippets/SnippetListScreen.kt",
            "ui/tunnels/TunnelListScreen.kt",
            "ui/sync/EnrolledDevicesScreen.kt",
        )
        for (path in migrated) {
            assertTrue(screens.containsKey(path), "$path no longer shows a NextShMessage")
        }
        for ((path, code) in screens) {
            for (call in SourceGuardSupport.calls(code, "NextShMessage")) {
                assertTrue(call.contains("onDismiss"), "$path: a message that cannot be dismissed")
            }
        }
    }

    @Test
    fun `the migrated screens show their errors as errors`() {
        val errorScreens = listOf(
            "ui/hosts/HostListScreen.kt",
            "ui/tunnels/TunnelListScreen.kt",
            "ui/vault/KeyImportScreen.kt",
            "ui/sessions/TerminalScreen.kt",
        )
        for (path in errorScreens) {
            val call = SourceGuardSupport.calls(sources.getValue(path), "NextShMessage").single()
            assertTrue(call.contains("isError   = true") || call.contains("isError = true"), "$path: shown as an error")
        }
    }

    @Test
    fun `the terminal message is laid over the terminal and never inside its column`() {
        val code = sources.getValue("ui/sessions/TerminalScreen.kt")

        // Inside the Column, the message would resize the terminal: new rows sent to the server.
        // The keys bar is the last child of the Column: the message comes after it, in the Box.
        val message = SourceGuardSupport.calls(code, "NextShMessage").single()
        assertTrue(code.indexOf("NextShMessage(") > code.indexOf("ExtraKeysBar("), "after the end of the column")
        assertTrue(message.contains(".align(Alignment.BottomCenter)"), "laid over the bottom of the Box")
        assertTrue(code.contains("onSizeChanged"), "above the measured height of the keys bar")
    }

    @Test
    fun `the tunnel list never waits for a message to go away before the next event`() {
        val code = sources.getValue("ui/tunnels/TunnelListScreen.kt")

        assertTrue(!code.contains("showSnackbar"), "no suspending message call in the event collector")
        assertTrue(code.contains("message = serviceRefusedMessage"), "an assignment, not a wait")
        assertTrue(code.contains("message = tunnelUnavailableMessage"), "an assignment, not a wait")
    }
}
