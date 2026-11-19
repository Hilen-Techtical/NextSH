// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.lock

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the screens of NextSH are covered by the lock screen.
 *
 * This is a lock on the screen, not on the vault: the vault is opened once, at the unlock, and stays
 * open until the process ends (see `VaultManager`). What this stops is someone using the app after
 * the owner put the phone down. Sessions and tunnels keep running behind the lock.
 *
 * Locked from the start of the process, until someone unlocks it in this process. That also covers
 * a process that died and came back: the navigation restores the screens that were open without
 * going through the unlock screen, and the lock has to cover them.
 *
 * After that it locks again in two cases: [lock] (the user asks, or the vault refused an operation
 * because its authentication window had closed), and [onAppShown] when the app was out of the
 * foreground for at least the delay of the settings ([setAutoLockDelay], five minutes until the
 * settings are read).
 *
 * The time is the elapsed real time of the device, [SystemClock.elapsedRealtime]: unlike the
 * monotonic clock of the JVM it keeps counting in deep sleep, so a phone put down for half an hour
 * with its screen off has been away for half an hour. It is injectable for the tests.
 *
 * Logs: fixed texts only, nothing the user owns.
 */
@Singleton
class AppLock internal constructor(
    private val clock: () -> Long,
) {

    @Inject
    constructor() : this({ SystemClock.elapsedRealtime() })

    private val _locked = MutableStateFlow(true)

    /** True while the lock screen has to cover the app. */
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    /**
     * The delay of the settings, kept here because [onAppShown] runs inside the lifecycle callback of
     * the activity and cannot wait for the settings store.
     */
    @Volatile
    private var delayMs: Long = AutoLockDelay.DEFAULT.millis

    /** When the app left the foreground, on [clock]; [NOT_HIDDEN] while it is there. */
    @Volatile
    private var hiddenSinceMs: Long = NOT_HIDDEN

    fun isLocked(): Boolean = _locked.value

    /** Covers the app. Nothing changes under the lock screen. */
    fun lock() {
        if (_locked.compareAndSet(expect = false, update = true)) Timber.i("App locked")
    }

    /**
     * The user proved who they are. It also forgets the moment the app was last hidden: the screen
     * where the phone asks for its code stops the activity while it is up, and depending on the order
     * of that callback and of the answer of the prompt, the return would otherwise lock the app again
     * right after it was unlocked, with a delay of zero.
     */
    fun unlock() {
        hiddenSinceMs = NOT_HIDDEN
        if (_locked.compareAndSet(expect = true, update = false)) Timber.i("App unlocked")
    }

    fun setAutoLockDelay(delay: AutoLockDelay) {
        delayMs = delay.millis
    }

    /**
     * The app left the foreground for good: the last started instance of the activity stopped, see
     * `ActivityVisibilityTracker.onActivityStopped` (the stop of an old instance while a new one is up
     * is no absence). A rotation is not that either, and the activity must not tell us about it. The
     * first stop counts: a second one before the app came back does not postpone the lock.
     *
     * [ownerPromptOpen] says the system dialog that asks the owner for the vault is up (see
     * `BiometricPromptCoordinator.openDialog`). Its screen for the phone code stops the activity while
     * it is up, and the return would lock the app right after the owner proved who they are, with a
     * delay of zero: no absence is noted then. It is only the vault dialog: the dialog of a biometric
     * key does not go through the phone code, so leaving the app during it does start the clock. When
     * the vault dialog ends with the app still away (the owner left by Home), the absence is noted
     * then, see `OwnerPromptEndedAway`.
     */
    fun onAppHidden(ownerPromptOpen: Boolean = false) {
        if (ownerPromptOpen) return
        if (hiddenSinceMs == NOT_HIDDEN) hiddenSinceMs = clock()
    }

    /** The app is in the foreground again: it locks if it stayed away for at least the delay. */
    fun onAppShown() {
        val since = hiddenSinceMs
        hiddenSinceMs = NOT_HIDDEN
        if (since != NOT_HIDDEN && clock() - since >= delayMs) lock()
    }

    private companion object {
        const val NOT_HIDDEN = Long.MIN_VALUE
    }
}
