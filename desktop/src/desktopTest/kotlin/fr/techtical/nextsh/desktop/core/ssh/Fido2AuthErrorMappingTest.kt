// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.ssh

import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.shared.domain.model.SshErrorCode
import fr.techtical.nextsh.desktop.generated.resources.auth_error_fido2_failed
import fr.techtical.nextsh.desktop.generated.resources.auth_error_fido2_cancelled
import fr.techtical.nextsh.desktop.generated.resources.auth_error_fido2_no_key
import fr.techtical.nextsh.desktop.generated.resources.auth_error_fido2_touch_timeout
import fr.techtical.nextsh.desktop.generated.resources.auth_error_fido2_transport
import fr.techtical.nextsh.desktop.generated.resources.fido2_enroll_pcsc_windows_bug
import fr.techtical.nextsh.desktop.generated.resources.fido2_enroll_windows_hid_locked
import kotlinx.coroutines.test.runTest
import net.schmizz.sshj.userauth.UserAuthException
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The FIDO2 failures of a connection reach the user as translated texts: the typed tokens of
 * the session manager are mapped to resources, and the technical message of the exception is
 * never part of the text.
 */
class Fido2AuthErrorMappingTest {

    /** The way SSHJ delivers it: the exception of the method is the cause of "Exhausted available authentication methods". */
    private fun wrapped(token: String) = UserAuthException(
        "Exhausted available authentication methods",
        UserAuthException("$token: PC/SC access to the YubiKey failed", RuntimeException("inner")),
    )

    private suspend fun message(token: String): String = fido2AuthErrorResult(wrapped(token)).message

    @Test
    fun `the PC SC bug shows the translated enrollment text`() = runTest {
        assertEquals(getString(Res.string.fido2_enroll_pcsc_windows_bug), message("FIDO2_PCSC_BUG"))
    }

    @Test
    fun `a locked HID interface shows its translated text`() = runTest {
        assertEquals(getString(Res.string.fido2_enroll_windows_hid_locked), message("FIDO2_HID_LOCKED"))
    }

    @Test
    fun `a transport error shows its translated text`() = runTest {
        assertEquals(getString(Res.string.auth_error_fido2_transport), message("FIDO2_TRANSPORT"))
    }

    @Test
    fun `any other FIDO2 error shows a generic translated text without the technical message`() = runTest {
        val text = message("FIDO2_ERROR")
        assertEquals(getString(Res.string.auth_error_fido2_failed), text)
        assertFalse(text.contains("FIDO2_ERROR") || text.contains("PC/SC access"), text)
    }

    @Test
    fun `the codes and texts of a missing key, a cancel and a timeout survive the wrapping`() = runTest {
        val noKey = fido2AuthErrorResult(wrapped("FIDO2_NO_KEY"))
        assertEquals(SshErrorCode.FIDO2_NO_KEY, noKey.code)
        assertEquals(getString(Res.string.auth_error_fido2_no_key), noKey.message)
        val cancelled = fido2AuthErrorResult(wrapped("FIDO2_USER_CANCELLED"))
        assertEquals(SshErrorCode.FIDO2_USER_CANCELLED, cancelled.code)
        assertEquals(getString(Res.string.auth_error_fido2_cancelled), cancelled.message)
        val timeout = fido2AuthErrorResult(wrapped("FIDO2_TIMEOUT"))
        assertEquals(SshErrorCode.FIDO2_TIMEOUT, timeout.code)
        assertEquals(getString(Res.string.auth_error_fido2_touch_timeout), timeout.message)
    }

    @Test
    fun `an unwrapped token is still read`() = runTest {
        assertEquals(
            getString(Res.string.auth_error_fido2_transport),
            fido2AuthErrorResult(UserAuthException("FIDO2_TRANSPORT")).message,
        )
    }

    @Test
    fun `a refusal without token shows the generic text and never the SSHJ message`() = runTest {
        val text = fido2AuthErrorResult(UserAuthException("Exhausted available authentication methods")).message
        assertEquals(getString(Res.string.auth_error_fido2_failed), text)
        assertFalse(text.contains("Exhausted"), text)
    }
}
