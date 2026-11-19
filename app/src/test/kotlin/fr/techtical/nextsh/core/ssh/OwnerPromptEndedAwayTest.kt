// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import androidx.biometric.BiometricPrompt
import fr.techtical.nextsh.core.ActivityVisibilityTracker
import fr.techtical.nextsh.core.lock.AppLock
import fr.techtical.nextsh.core.lock.AutoLockDelay
import fr.techtical.nextsh.core.lock.OwnerPromptEndedAway
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.Signature

/**
 * The owner left by Home in the middle of a confirmation of a save: with the vault dialog up, the stop of
 * the activity is not noted (the screen of the phone code stops it too), so the clock of the lock starts
 * when the dialog ends, if the app is still away. However it ends, and only then.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OwnerPromptEndedAwayTest {

    private class Rig(
        val appLock: AppLock,
        val tracker: ActivityVisibilityTracker,
        val coordinator: BiometricPromptCoordinator,
    ) {
        /** What the activity does: the last stop tells the lock, unless the vault dialog is up. */
        fun activityStops(rotation: Boolean = false) {
            val left = tracker.onActivityStopped(changingConfigurations = rotation)
            if (left && !rotation) {
                appLock.onAppHidden(ownerPromptOpen = coordinator.openDialog() is VaultAuthRequest)
            }
        }

        fun activityStarts() {
            if (tracker.onActivityStarted()) appLock.onAppShown()
        }
    }

    /** The app on screen, unlocked, one minute of delay, the watcher of the ends of vault dialogs at work. */
    private fun TestScope.rig(delay: AutoLockDelay = AutoLockDelay.ONE_MINUTE): Rig {
        val clock = { testScheduler.currentTime }
        val appLock = AppLock(clock)
        val tracker = ActivityVisibilityTracker(clock)
        val coordinator = BiometricPromptCoordinator(InteractivePromptTurn(), clock, { true })
        appLock.setAutoLockDelay(delay)
        appLock.unlock()
        OwnerPromptEndedAway(appLock, coordinator, tracker).start(backgroundScope)
        runCurrent()
        return Rig(appLock, tracker, coordinator).also { it.tracker.onActivityStarted() }
    }

    /** A confirmation of a save: the dialog is up, and the owner leaves by Home. Returns the request. */
    private fun TestScope.leaveByHomeDuringTheDialog(rig: Rig): kotlinx.coroutines.Deferred<BiometricPromptResponse?> {
        val response = async { rig.coordinator.authenticateVault(VaultAuthPurpose.CONFIRM_SAVE) }
        runCurrent()
        assertTrue(rig.coordinator.markLaunched(rig.coordinator.state.value!!.request), "the dialog is up")
        rig.activityStops()
        return response
    }

    // ── The dialog ends while the app is away ─────────────────────────────────

    @Test
    fun `the owner leaves by Home during the dialog, it ends in the background, the lock comes when the delay has passed`() = runTest {
        val rig = rig()
        val response = leaveByHomeDuringTheDialog(rig)
        advanceTimeBy(30_000) // away, the dialog still up: nothing noted yet
        runCurrent()

        val request = rig.coordinator.state.value!!.request
        rig.coordinator.cancel(request) // the system took the dialog away
        response.await()
        runCurrent()
        rig.coordinator.markClosed(request) // and the host, back on screen, confirms it is closed
        advanceTimeBy(59_999)
        rig.activityStarts()
        assertFalse(rig.appLock.isLocked(), "one millisecond short of the delay since the end of the dialog")

        rig.activityStops()
        advanceTimeBy(60_000)
        rig.activityStarts()
        assertTrue(rig.appLock.isLocked())
    }

    @Test
    fun `the clock starts at the end of the dialog, not at the stop, which was not noted`() = runTest {
        val rig = rig()
        val response = leaveByHomeDuringTheDialog(rig)
        advanceTimeBy(2 * 60_000L) // longer than the delay away, the dialog still up (it lives three minutes)

        rig.coordinator.cancel(rig.coordinator.state.value!!.request)
        response.await()
        runCurrent()
        // The end is the moment the absence is noted: back at once, the delay has not passed.
        rig.activityStarts()

        assertFalse(rig.appLock.isLocked())
    }

    @Test
    fun `however the dialog ends the absence is noted, answered, cancelled, closed by the user, timed out or given up`() = runTest {
        val ends = listOf<(Rig, kotlinx.coroutines.Deferred<BiometricPromptResponse?>) -> Unit>(
            { rig, _ -> rig.coordinator.authorize(rig.coordinator.state.value!!.request) },
            { rig, _ -> rig.coordinator.cancel(rig.coordinator.state.value!!.request) },
            { rig, _ -> rig.coordinator.reportError(rig.coordinator.state.value!!.request, BiometricPrompt.ERROR_USER_CANCELED) },
            { rig, _ -> rig.coordinator.fail(rig.coordinator.state.value!!.request, BiometricFailure.LOCKOUT) },
            { _, response -> response.cancel() },
        )
        for ((index, end) in ends.withIndex()) {
            val rig = rig()
            val response = leaveByHomeDuringTheDialog(rig)

            end(rig, response)
            runCurrent()
            advanceTimeBy(60_000)
            rig.activityStarts()

            assertTrue(rig.appLock.isLocked(), "end #$index")
        }
    }

    @Test
    fun `a dialog that nobody answered ends at its deadline, in the background, and the absence is noted then`() = runTest {
        val rig = rig()
        val response = leaveByHomeDuringTheDialog(rig)

        advanceTimeBy(BiometricPromptCoordinator.VAULT_DIALOG_TIMEOUT_MS + 1)
        runCurrent()
        assertEquals(null, response.await())
        advanceTimeBy(60_000)
        rig.activityStarts()

        assertTrue(rig.appLock.isLocked())
    }

    // ── What changes nothing ──────────────────────────────────────────────────

    @Test
    fun `a dialog that is still up changes nothing, the screen of the phone code is in front`() = runTest {
        val rig = rig(AutoLockDelay.IMMEDIATELY)
        val response = leaveByHomeDuringTheDialog(rig) // the activity stops for the screen of the phone code
        advanceTimeBy(10_000)
        runCurrent()

        rig.activityStarts() // back from the phone code screen, the dialog is still up

        assertFalse(rig.appLock.isLocked(), "locked right after a confirmation of the owner, with the delay at zero")
        response.cancel()
    }

    @Test
    fun `a dialog that ends with the app on screen does not start the clock`() = runTest {
        val rig = rig(AutoLockDelay.IMMEDIATELY)
        val response = async { rig.coordinator.authenticateVault(VaultAuthPurpose.CONFIRM_SAVE) }
        runCurrent()

        rig.coordinator.authorize(rig.coordinator.state.value!!.request)
        response.await()
        runCurrent()
        advanceTimeBy(60 * 60_000L)
        rig.appLock.onAppShown() // nothing was noted: the app never left

        assertFalse(rig.appLock.isLocked())
    }

    @Test
    fun `a dialog that ends in the middle of a rotation does not start the clock, the recreation is no absence`() = runTest {
        val rig = rig(AutoLockDelay.IMMEDIATELY)
        val response = async { rig.coordinator.authenticateVault(VaultAuthPurpose.CONFIRM_SAVE) }
        runCurrent()
        rig.activityStops(rotation = true) // the old instance stops, the new one is not up yet

        rig.coordinator.authorize(rig.coordinator.state.value!!.request)
        response.await()
        runCurrent()
        rig.activityStarts()

        assertFalse(rig.appLock.isLocked())
    }

    @Test
    fun `a signature prompt that ends in the background starts no clock, only the vault dialog does`() = runTest {
        val rig = rig(AutoLockDelay.IMMEDIATELY)
        val finger = BiometricSignRequest(Signature.getInstance("SHA256withECDSA"), "Host", "Key")
        val response = async { rig.coordinator.prompt(finger) }
        runCurrent()
        rig.tracker.onActivityStopped() // the app is away, no lock told: the stop was not noted by this rig

        rig.coordinator.cancel(finger)
        response.await()
        runCurrent()
        rig.tracker.onActivityStarted()
        rig.appLock.onAppShown()

        assertFalse(rig.appLock.isLocked(), "nothing was noted")
    }

    // ── The event of the coordinator ──────────────────────────────────────────

    @Test
    fun `the coordinator says each end of a vault request once, and says nothing for a signature request`() = runTest {
        val coordinator = BiometricPromptCoordinator(InteractivePromptTurn(), { testScheduler.currentTime }, { true })
        var ends = 0
        backgroundScope.launch { coordinator.vaultRequestEnded.collect { ends++ } }
        runCurrent()

        val vault = async { coordinator.authenticateVault(VaultAuthPurpose.UNLOCK) }
        runCurrent()
        coordinator.authorize(coordinator.state.value!!.request)
        vault.await()
        runCurrent()
        assertEquals(1, ends)

        val finger = BiometricSignRequest(Signature.getInstance("SHA256withECDSA"), "Host", "Key")
        val signature = async { coordinator.prompt(finger) }
        runCurrent()
        coordinator.authorize(finger)
        signature.await()
        runCurrent()
        assertEquals(1, ends, "a signature request ends without saying it")

        val second = launch { coordinator.authenticateVault(VaultAuthPurpose.CONFIRM_SAVE) }
        runCurrent()
        second.cancel()
        runCurrent()
        assertEquals(2, ends)
    }
}
