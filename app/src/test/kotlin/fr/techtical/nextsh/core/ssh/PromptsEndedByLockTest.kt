// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.core.AppVisibility
import fr.techtical.nextsh.core.lock.AppLock
import fr.techtical.nextsh.core.lock.PromptsEndedByLock
import fr.techtical.nextsh.shared.core.ssh.fido2.SkKeyType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.Signature

/**
 * A prompt that was published when the app locked must not outlive the lock: it would hold the turn
 * shared with the others, and the SSH connection behind it, for up to a minute, in front of a lock
 * screen whose unlock button would wait for a dialog that cannot show. It ends as if the app had been
 * left, like the prompts asked while locked do: with the answer of "nobody on screen". The vault
 * request, the unlock itself, is not one of them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PromptsEndedByLockTest {

    private val appLock = AppLock { 0L }

    /** What the prompts see: the app is there while it is unlocked, as `UnlockedAppVisibility` says. */
    private val visibility = AppVisibility { !appLock.isLocked() }

    private class Prompts(
        val hostKeys: HostKeyPromptCoordinator,
        val fido2: Fido2PromptCoordinator,
        val fingerprints: BiometricPromptCoordinator,
    )

    /** The three coordinators on one turn and one virtual clock, as the app wires them, and the lock watching them. */
    private fun TestScope.prompts(): Prompts {
        val turn = InteractivePromptTurn()
        val clock = { testScheduler.currentTime }
        val prompts = Prompts(
            HostKeyPromptCoordinator(turn, clock, visibility, { StoredKeyState.NONE }, confirmArmMs = 0L),
            Fido2PromptCoordinator(turn, clock, visibility),
            BiometricPromptCoordinator(turn, clock, visibility),
        )
        PromptsEndedByLock(appLock, prompts.hostKeys, prompts.fido2, prompts.fingerprints).start(backgroundScope)
        runCurrent()
        return prompts
    }

    private fun unknownKey() =
        HostKeyVerifyResult.Unknown(hostname = "new.example.org:22", algorithm = "ssh-ed25519", fingerprint = "SHA256:new")

    private fun fido2Request() = Fido2ChallengeRequest(
        signingData = ByteArray(32) { 9 },
        rpId = "ssh:",
        keyType = SkKeyType.SK_ED25519,
    )

    private fun signRequest() = BiometricSignRequest(
        signature = Signature.getInstance("SHA256withECDSA"),
        hostLabel = "Host",
        keyLabel = "Key",
    )

    // ── Each coordinator ──────────────────────────────────────────────────────

    @Test
    fun `the key of an unknown server that is up when the app locks ends as nobody on screen, and nothing is trusted`() = runTest {
        appLock.unlock()
        val prompts = prompts()
        val response = async { prompts.hostKeys.promptUnknown(unknownKey()) }
        runCurrent()
        assertTrue(prompts.hostKeys.state.value != null, "the dialog is up")

        appLock.lock()
        runCurrent()

        assertEquals(HostKeyPromptResponse.NoScreen, response.await())
        assertNull(prompts.hostKeys.state.value, "the dialog goes away")
    }

    @Test
    fun `the security key sheet that is up when the app locks ends as nobody on screen, and the data to sign is zeroed`() = runTest {
        appLock.unlock()
        val prompts = prompts()
        val request = fido2Request()
        val response = async { prompts.fido2.prompt(request) }
        runCurrent()
        assertSame(request, prompts.fido2.state.value?.request)

        appLock.lock()
        runCurrent()

        assertEquals(Fido2ChallengeResponse.NoScreen, response.await())
        assertNull(prompts.fido2.state.value)
        assertTrue(request.signingData.all { it == 0.toByte() }, "zeroed like when a prompt ends")
    }

    @Test
    fun `the fingerprint of a biometric key that is up when the app locks ends as nobody on screen`() = runTest {
        appLock.unlock()
        val prompts = prompts()
        val request = signRequest()
        val response = async { prompts.fingerprints.prompt(request) }
        runCurrent()
        assertSame(request, prompts.fingerprints.state.value?.request)

        appLock.lock()
        runCurrent()

        assertEquals(BiometricPromptResponse.NoScreen, response.await())
        assertNull(prompts.fingerprints.state.value)
    }

    // ── The turn ──────────────────────────────────────────────────────────────

    @Test
    fun `the turn is released, the unlock prompt of the lock screen gets it at once`() = runTest {
        appLock.unlock()
        val prompts = prompts()
        val sheet = async { prompts.fido2.prompt(fido2Request()) }
        runCurrent()
        val unlock = async { prompts.fingerprints.authenticateVault(VaultAuthPurpose.UNLOCK) }
        runCurrent()
        assertNull(prompts.fingerprints.state.value, "behind the sheet")

        appLock.lock()
        runCurrent()

        assertEquals(Fido2ChallengeResponse.NoScreen, sheet.await())
        assertTrue(prompts.fingerprints.state.value?.request is VaultAuthRequest, "the dialog of the unlock can show")
        prompts.fingerprints.authorize(prompts.fingerprints.state.value!!.request)
        assertEquals(BiometricPromptResponse.Authorized, unlock.await())
    }

    @Test
    fun `a prompt that waited for its turn behind the one that ended finds the app locked, and ends as nobody on screen too`() = runTest {
        appLock.unlock()
        val prompts = prompts()
        val sheet = async { prompts.fido2.prompt(fido2Request()) }
        runCurrent()
        val hostKey = async { prompts.hostKeys.promptUnknown(unknownKey()) }
        val finger = async { prompts.fingerprints.prompt(signRequest()) }
        runCurrent()

        appLock.lock()
        runCurrent()

        assertEquals(Fido2ChallengeResponse.NoScreen, sheet.await())
        assertEquals(HostKeyPromptResponse.NoScreen, hostKey.await())
        assertEquals(BiometricPromptResponse.NoScreen, finger.await())
        assertNull(prompts.hostKeys.state.value)
        assertNull(prompts.fingerprints.state.value)
    }

    // ── What it leaves alone ──────────────────────────────────────────────────

    @Test
    fun `the vault request is not ended by the lock, it is the unlock itself`() = runTest {
        appLock.unlock()
        val prompts = prompts()
        val vault = async { prompts.fingerprints.authenticateVault(VaultAuthPurpose.CONFIRM_SAVE) }
        runCurrent()
        val request = prompts.fingerprints.state.value!!.request

        appLock.lock()
        runCurrent()

        assertFalse(vault.isCompleted)
        assertSame(request, prompts.fingerprints.state.value?.request)
        prompts.fingerprints.authorize(request)
        assertEquals(BiometricPromptResponse.Authorized, vault.await())
    }

    @Test
    fun `an unlock ends nothing`() = runTest {
        val prompts = prompts() // locked from the start
        appLock.unlock()
        val request = fido2Request()
        val sheet = async { prompts.fido2.prompt(request) }
        runCurrent()

        appLock.unlock() // already unlocked: no change, no event
        runCurrent()

        assertFalse(sheet.isCompleted)
        assertSame(request, prompts.fido2.state.value?.request)
        prompts.fido2.cancel(request)
        sheet.await()
    }

    @Test
    fun `the lock ends the prompts each time it comes, not only the first`() = runTest {
        appLock.unlock()
        val prompts = prompts()
        repeat(2) {
            val response = async { prompts.hostKeys.promptUnknown(unknownKey()) }
            // Two dialogs never follow each other without a break: the second waits the gap out.
            advanceTimeBy(HostKeyPromptCoordinator.PROMPT_GAP_MS)
            runCurrent()
            assertTrue(prompts.hostKeys.state.value != null)

            appLock.lock()
            runCurrent()
            assertEquals(HostKeyPromptResponse.NoScreen, response.await())

            appLock.unlock()
            runCurrent()
        }
    }

    @Test
    fun `nothing up when the app locks, nothing happens`() = runTest {
        appLock.unlock()
        val prompts = prompts()

        appLock.lock()
        runCurrent()

        assertNull(prompts.hostKeys.state.value)
        assertNull(prompts.fido2.state.value)
        assertNull(prompts.fingerprints.state.value)
    }
}
