// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import androidx.biometric.BiometricPrompt
import fr.techtical.nextsh.R
import fr.techtical.nextsh.core.AppVisibility
import fr.techtical.nextsh.shared.core.ssh.fido2.SkKeyType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.Signature

/**
 * The proof of the owner that the vault asks for (the unlock, the confirmation of a save) goes through
 * the same coordinator as the fingerprint of a biometric key, which is what ends the collision of the
 * two on the single callback slot of the activity: one turn, shared with the FIDO2 sheet, the same
 * deadline, the same answers. And unlike a signature, it does not ask whether the app is on screen:
 * the person who asks has just touched it, and the lock screen makes the app count as absent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BiometricPromptCoordinatorVaultTest {

    private var onScreen = true

    /** The visibility the coordinator sees, which the test flips (a lock screen up, the app in the background). */
    private val appVisibility = AppVisibility { onScreen }

    private fun TestScope.newCoordinator(turn: InteractivePromptTurn = InteractivePromptTurn()) =
        BiometricPromptCoordinator(turn, { testScheduler.currentTime }, appVisibility)

    private fun signRequest(label: String = "Host") = BiometricSignRequest(
        signature = Signature.getInstance("SHA256withECDSA"),
        hostLabel = label,
        keyLabel = "Key",
    )

    private fun fido2Request() = Fido2ChallengeRequest(
        signingData = ByteArray(32) { 9 },
        rpId = "ssh:",
        keyType = SkKeyType.SK_ED25519,
    )

    // ── The request ───────────────────────────────────────────────────────────

    @Test
    fun `the vault prompt is published as a vault request with its purpose`() = runTest {
        val coordinator = newCoordinator()

        val job = launch { coordinator.authenticateVault(VaultAuthPurpose.UNLOCK) }
        runCurrent()

        val request = coordinator.state.value?.request
        assertTrue(request is VaultAuthRequest, "$request")
        assertEquals(VaultAuthPurpose.UNLOCK, (request as VaultAuthRequest).purpose)
        job.cancel()
    }

    @Test
    fun `each purpose has its own title and subtitle, the unlock and the confirmation of a save`() {
        assertEquals(R.string.vault_auth_unlock_title, VaultAuthPurpose.UNLOCK.titleRes)
        assertEquals(R.string.vault_auth_unlock_subtitle, VaultAuthPurpose.UNLOCK.subtitleRes)
        assertEquals(R.string.host_save_confirm_title, VaultAuthPurpose.CONFIRM_SAVE.titleRes)
        assertEquals(R.string.host_save_confirm_subtitle, VaultAuthPurpose.CONFIRM_SAVE.subtitleRes)
    }

    @Test
    fun `a request is known by identity, two for the same purpose are two requests`() = runTest {
        val coordinator = newCoordinator()
        val first = async { coordinator.authenticateVault(VaultAuthPurpose.UNLOCK) }
        runCurrent()
        val shown = coordinator.state.value!!.request

        val second = async { coordinator.authenticateVault(VaultAuthPurpose.UNLOCK) }
        runCurrent()

        assertSame(shown, coordinator.state.value!!.request, "the second one waits for its turn")
        coordinator.authorize(shown)
        first.await()
        runCurrent()
        assertNotSame(shown, coordinator.state.value!!.request)
        second.cancel()
    }

    @Test
    fun `a vault request is authorized by a success with no signature only, a signature request by its own signature only`() {
        val vault = VaultAuthRequest(VaultAuthPurpose.UNLOCK)
        val sign = signRequest()
        val other = Signature.getInstance("SHA256withECDSA")

        assertTrue(vault.isAuthorizedBy(null))
        assertFalse(vault.isAuthorizedBy(other), "a CryptoObject on a dialog that had none is not the callback of this dialog")
        assertFalse(vault.isAuthorizedBy(sign.signature))

        assertTrue(sign.isAuthorizedBy(sign.signature))
        assertFalse(sign.isAuthorizedBy(null))
        assertFalse(sign.isAuthorizedBy(other))
    }

    @Test
    fun `a vault request tells nothing but its purpose`() {
        assertEquals("VaultAuthRequest(UNLOCK)", VaultAuthRequest(VaultAuthPurpose.UNLOCK).toString())
        assertEquals("BiometricSignRequest", signRequest("prod-db-secret-host").toString())
    }

    // ── Its answers ───────────────────────────────────────────────────────────

    @Test
    fun `a recognized owner authorizes the request`() = runTest {
        val coordinator = newCoordinator()
        val response = async { coordinator.authenticateVault(VaultAuthPurpose.UNLOCK) }
        runCurrent()

        coordinator.authorize(coordinator.state.value!!.request)

        assertEquals(BiometricPromptResponse.Authorized, response.await())
        assertNull(coordinator.state.value)
    }

    @Test
    fun `a dialog the user closes, or the system when the app was left, is a cancellation`() = runTest {
        val coordinator = newCoordinator()
        for (code in listOf(
            BiometricPrompt.ERROR_USER_CANCELED,
            BiometricPrompt.ERROR_NEGATIVE_BUTTON,
            BiometricPrompt.ERROR_CANCELED,
        )) {
            val response = async { coordinator.authenticateVault(VaultAuthPurpose.UNLOCK) }
            runCurrent()

            coordinator.reportError(coordinator.state.value!!.request, code)

            assertEquals(BiometricPromptResponse.Cancelled, response.await(), "code $code")
        }
    }

    @Test
    fun `a sensor locked or missing is a failure, the system timeout is an answer nobody gave`() = runTest {
        val coordinator = newCoordinator()
        val cases = mapOf(
            BiometricPrompt.ERROR_LOCKOUT to BiometricPromptResponse.Failed(BiometricFailure.LOCKOUT),
            BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL to BiometricPromptResponse.Failed(BiometricFailure.OTHER),
            BiometricPrompt.ERROR_TIMEOUT to null,
        )
        for ((code, expected) in cases) {
            val response = async { coordinator.authenticateVault(VaultAuthPurpose.CONFIRM_SAVE) }
            runCurrent()

            coordinator.reportError(coordinator.state.value!!.request, code)

            assertEquals(expected, response.await(), "code $code")
        }
    }

    @Test
    fun `an unanswered vault prompt ends at its deadline like any other`() = runTest {
        val coordinator = newCoordinator()
        val response = async { coordinator.authenticateVault(VaultAuthPurpose.UNLOCK) }
        runCurrent()

        advanceTimeBy(BiometricPromptCoordinator.PROMPT_TIMEOUT_MS + 1)
        runCurrent()

        assertNull(response.await())
        assertNull(coordinator.state.value)
    }

    @Test
    fun `a vault dialog is launched once and gets three minutes from the launch, for a code typed slowly`() = runTest {
        val coordinator = newCoordinator()
        val job = launch { coordinator.authenticateVault(VaultAuthPurpose.UNLOCK) }
        runCurrent()
        val request = coordinator.state.value!!.request
        advanceTimeBy(40_000)

        assertTrue(coordinator.markLaunched(request))
        assertFalse(coordinator.markLaunched(request), "a host recreated by a rotation does not start it again")

        assertEquals(3 * 60_000L, BiometricPromptCoordinator.VAULT_DIALOG_TIMEOUT_MS)
        assertEquals(40_000L + BiometricPromptCoordinator.VAULT_DIALOG_TIMEOUT_MS, coordinator.state.value!!.deadlineMs)
        assertSame(request, coordinator.openDialog())
        coordinator.markClosed(request)
        assertNull(coordinator.openDialog())
        job.cancel()
    }

    @Test
    fun `a vault dialog is not closed under the fingers after a minute, a signature dialog still is`() = runTest {
        val coordinator = newCoordinator()
        val vault = async { coordinator.authenticateVault(VaultAuthPurpose.UNLOCK) }
        runCurrent()
        val vaultRequest = coordinator.state.value!!.request
        assertTrue(coordinator.markLaunched(vaultRequest))

        advanceTimeBy(BiometricPromptCoordinator.PROMPT_TIMEOUT_MS + 5_000) // a code typed slowly
        runCurrent()
        assertFalse(vault.isCompleted, "still up after a minute and five seconds")
        assertNotNull(coordinator.state.value)

        advanceTimeBy(BiometricPromptCoordinator.VAULT_DIALOG_TIMEOUT_MS) // nobody ever answered
        runCurrent()
        assertNull(vault.await())
        assertNull(coordinator.state.value)
        coordinator.markClosed(vaultRequest) // the host confirms the dialog is gone

        val finger = signRequest()
        val fingerResponse = async { coordinator.prompt(finger) }
        runCurrent()
        assertTrue(coordinator.markLaunched(finger))
        advanceTimeBy(BiometricPromptCoordinator.PROMPT_TIMEOUT_MS + 1)
        runCurrent()
        assertNull(fingerResponse.await(), "a signature dialog keeps its minute: an SSH transport waits behind it")
    }

    @Test
    fun `a vault request that is not launched still ends after a minute, the turn it holds is protected`() = runTest {
        val coordinator = newCoordinator()
        val vault = async { coordinator.authenticateVault(VaultAuthPurpose.UNLOCK) }
        runCurrent()

        advanceTimeBy(BiometricPromptCoordinator.PROMPT_TIMEOUT_MS + 1) // no host launched its dialog
        runCurrent()

        assertNull(vault.await())
        assertNull(coordinator.state.value)
    }

    @Test
    fun `the three minutes of a vault dialog are counted from its launch within the maximum of its life`() = runTest {
        val coordinator = newCoordinator()
        val job = launch { coordinator.authenticateVault(VaultAuthPurpose.UNLOCK) }
        runCurrent()
        advanceTimeBy(BiometricPromptCoordinator.PROMPT_TIMEOUT_MS - 1) // the last moment a launch can happen

        assertTrue(coordinator.markLaunched(coordinator.state.value!!.request))

        assertEquals(
            BiometricPromptCoordinator.PROMPT_TIMEOUT_MS + BiometricPromptCoordinator.VAULT_DIALOG_TIMEOUT_MS,
            BiometricPromptCoordinator.MAX_VAULT_PROMPT_TOTAL_MS,
        )
        assertTrue(coordinator.state.value!!.deadlineMs <= BiometricPromptCoordinator.MAX_VAULT_PROMPT_TOTAL_MS)
        job.cancel()
    }

    // ── The lock ends the signature prompts, never the vault one ──────────────

    @Test
    fun `a signature prompt that is up when the app locks ends as nobody on screen, and the turn is released`() = runTest {
        val coordinator = newCoordinator()
        val finger = signRequest()
        val fingerResponse = async { coordinator.prompt(finger) }
        runCurrent()
        assertSame(finger, coordinator.state.value?.request)
        val next = async { coordinator.authenticateVault(VaultAuthPurpose.UNLOCK) }
        runCurrent()
        assertSame(finger, coordinator.state.value?.request, "the unlock button waits behind it")

        coordinator.endSignaturesForAbsence()
        runCurrent()

        assertEquals(BiometricPromptResponse.NoScreen, fingerResponse.await())
        assertTrue(coordinator.state.value?.request is VaultAuthRequest, "the turn went to the unlock prompt at once")
        next.cancel()
    }

    @Test
    fun `a vault request that is up is not ended by the lock`() = runTest {
        val coordinator = newCoordinator()
        val vault = async { coordinator.authenticateVault(VaultAuthPurpose.CONFIRM_SAVE) }
        runCurrent()
        val request = coordinator.state.value!!.request

        coordinator.endSignaturesForAbsence()
        runCurrent()

        assertFalse(vault.isCompleted)
        assertSame(request, coordinator.state.value?.request)
        coordinator.authorize(request)
        assertEquals(BiometricPromptResponse.Authorized, vault.await())
    }

    @Test
    fun `ending the signature prompts with nothing up does nothing`() = runTest {
        val coordinator = newCoordinator()

        coordinator.endSignaturesForAbsence()

        assertNull(coordinator.state.value)
    }

    @Test
    fun `a caller that gives up takes its vault prompt away`() = runTest {
        val coordinator = newCoordinator()
        val job = launch { coordinator.authenticateVault(VaultAuthPurpose.UNLOCK) }
        runCurrent()
        assertNotNull(coordinator.state.value)

        job.cancel()
        runCurrent()

        assertNull(coordinator.state.value)
    }

    // ── It does not ask whether the app is on screen ──────────────────────────

    @Test
    fun `a vault prompt is shown although the app counts as absent, a signature prompt is not`() = runTest {
        val coordinator = newCoordinator()
        onScreen = false // the lock screen is up: the app counts as absent for the prompts that need a person

        val signature = coordinator.prompt(signRequest())
        val vault = launch { coordinator.authenticateVault(VaultAuthPurpose.UNLOCK) }
        runCurrent()

        assertEquals(BiometricPromptResponse.NoScreen, signature)
        assertTrue(coordinator.state.value?.request is VaultAuthRequest, "the unlock prompt of the lock screen is shown")
        vault.cancel()
    }

    @Test
    fun `a vault prompt that waited for its turn is shown although the app counts as absent by then`() = runTest {
        val turn = InteractivePromptTurn()
        val coordinator = newCoordinator(turn)
        val fido2 = Fido2PromptCoordinator(turn, { testScheduler.currentTime }, appVisibility)
        val sheet = fido2Request()
        val sheetResponse = async { fido2.prompt(sheet) }
        runCurrent()
        val vault = launch { coordinator.authenticateVault(VaultAuthPurpose.UNLOCK) }
        runCurrent()
        assertNull(coordinator.state.value, "waiting behind the sheet")

        onScreen = false // the user left in the wait
        fido2.cancel(sheet)
        sheetResponse.await()
        runCurrent()

        assertTrue(coordinator.state.value?.request is VaultAuthRequest)
        vault.cancel()
    }

    @Test
    fun `a signature request is still asked whether the app is on screen, at the call and at its turn`() = runTest {
        val turn = InteractivePromptTurn()
        val coordinator = newCoordinator(turn)
        val vault = async { coordinator.authenticateVault(VaultAuthPurpose.UNLOCK) }
        runCurrent()
        val signature = async { coordinator.prompt(signRequest()) }
        runCurrent()
        assertTrue(coordinator.state.value?.request is VaultAuthRequest)

        onScreen = false // the user left while the signature waited behind the unlock prompt
        coordinator.cancel(coordinator.state.value!!.request)
        vault.await()
        runCurrent()

        assertEquals(BiometricPromptResponse.NoScreen, signature.await())
        assertNull(coordinator.state.value)
    }

    // ── One turn with the other prompts ───────────────────────────────────────

    @Test
    fun `a vault prompt waits for the fingerprint prompt that is on screen, then gets its turn`() = runTest {
        val coordinator = newCoordinator()
        val finger = signRequest()
        val fingerResponse = async { coordinator.prompt(finger) }
        runCurrent()

        val vault = async { coordinator.authenticateVault(VaultAuthPurpose.UNLOCK) }
        runCurrent()
        assertSame(finger, coordinator.state.value?.request, "one dialog at a time")

        coordinator.authorize(finger)
        fingerResponse.await()
        runCurrent()
        assertTrue(coordinator.state.value?.request is VaultAuthRequest, "its turn")

        coordinator.authorize(coordinator.state.value!!.request)
        assertEquals(BiometricPromptResponse.Authorized, vault.await())
    }

    @Test
    fun `a fingerprint prompt waits for the vault prompt that is on screen`() = runTest {
        val coordinator = newCoordinator()
        val vault = async { coordinator.authenticateVault(VaultAuthPurpose.UNLOCK) }
        runCurrent()
        val vaultRequest = coordinator.state.value!!.request

        val finger = signRequest()
        val fingerResponse = async { coordinator.prompt(finger) }
        runCurrent()
        assertSame(vaultRequest, coordinator.state.value?.request)

        coordinator.cancel(vaultRequest)
        assertEquals(BiometricPromptResponse.Cancelled, vault.await())
        runCurrent()
        assertSame(finger, coordinator.state.value?.request)
        coordinator.authorize(finger)
        assertEquals(BiometricPromptResponse.Authorized, fingerResponse.await())
    }

    @Test
    fun `a vault prompt waits for the FIDO2 sheet that is on screen, and the sheet for the vault prompt`() = runTest {
        val turn = InteractivePromptTurn()
        val coordinator = newCoordinator(turn)
        val fido2 = Fido2PromptCoordinator(turn, { testScheduler.currentTime }, appVisibility)

        val sheet = fido2Request()
        val sheetResponse = async { fido2.prompt(sheet) }
        runCurrent()
        val vault = async { coordinator.authenticateVault(VaultAuthPurpose.CONFIRM_SAVE) }
        runCurrent()
        assertSame(sheet, fido2.state.value?.request)
        assertNull(coordinator.state.value, "the dialog must not show over the sheet")

        fido2.cancel(sheet)
        sheetResponse.await()
        runCurrent()
        val vaultRequest = coordinator.state.value!!.request
        assertTrue(vaultRequest is VaultAuthRequest)

        val secondSheet = fido2Request()
        val secondSheetResponse = async { fido2.prompt(secondSheet) }
        runCurrent()
        assertNull(fido2.state.value, "and the sheet must not show over the dialog")

        coordinator.authorize(vaultRequest)
        assertEquals(BiometricPromptResponse.Authorized, vault.await())
        runCurrent()
        assertSame(secondSheet, fido2.state.value?.request)
        fido2.cancel(secondSheet)
        secondSheetResponse.await()
    }

    @Test
    fun `the deadline of a vault prompt that waited for another counts from when it appears`() = runTest {
        val coordinator = newCoordinator()
        val finger = signRequest()
        val fingerResponse = async { coordinator.prompt(finger) }
        runCurrent()
        val vault = launch { coordinator.authenticateVault(VaultAuthPurpose.UNLOCK) }
        runCurrent()
        advanceTimeBy(45_000)

        coordinator.cancel(finger)
        fingerResponse.await()
        runCurrent()

        assertEquals(45_000L + BiometricPromptCoordinator.PROMPT_TIMEOUT_MS, coordinator.state.value!!.deadlineMs)
        vault.cancel()
    }

    @Test
    fun `an answer for a request that is not on screen does not touch the vault prompt that is`() = runTest {
        val coordinator = newCoordinator()
        val vault = async { coordinator.authenticateVault(VaultAuthPurpose.UNLOCK) }
        runCurrent()

        coordinator.authorize(signRequest())
        coordinator.cancel(VaultAuthRequest(VaultAuthPurpose.UNLOCK))
        coordinator.reportError(VaultAuthRequest(VaultAuthPurpose.UNLOCK), BiometricPrompt.ERROR_LOCKOUT)

        assertFalse(vault.isCompleted, "a late answer of a closed dialog is dropped")
        coordinator.authorize(coordinator.state.value!!.request)
        assertEquals(BiometricPromptResponse.Authorized, vault.await())
    }
}
