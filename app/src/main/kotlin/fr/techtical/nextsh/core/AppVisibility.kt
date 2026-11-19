// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.techtical.nextsh.core.lock.AppLock
import fr.techtical.nextsh.core.vault.VaultWipeGate
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether NextSH is on screen, that is, whether someone can answer a prompt that needs a person in
 * front of the phone (a security key sheet, a fingerprint dialog, the confirmation of the key of a
 * server never seen).
 *
 * Why this exists: the tunnels are reconnected in the background by a service. A reconnection that
 * reaches a host whose authentication needs the user, or whose key is not trusted yet, opens a prompt
 * on a screen nobody looks at, waits for its 60 s deadline and fails with a timeout, silently. Those
 * callers ask this first.
 *
 * A function interface so that a test gives it as a lambda.
 */
fun interface AppVisibility {

    /**
     * True while the main screen is at least STARTED, that is, visible. A dialog-themed activity on
     * top of it (the system USB permission dialog, for instance) only pauses it, so it still counts.
     * A screen that is being recreated for a configuration change (a rotation) still counts for a
     * moment, see [ActivityVisibilityTracker]. The app the way the rest of the code gets it
     * ([UnlockedAppVisibility]) is not visible while the lock screen covers it either.
     */
    fun isVisible(): Boolean
}

/**
 * The [AppVisibility] of the app, fed by `MainActivity` from its lifecycle.
 *
 * It counts started instances rather than keeping a flag, so that a second instance that starts
 * before the first one stopped (a relaunch) does not read as "hidden" while a screen is up.
 *
 * A configuration change is the other way round: the framework stops the old instance *before* it
 * starts the new one, so the count passes through zero on every rotation of the terminal, SFTP and
 * tunnel browser screens. A prompt asked for at that moment would end at once in front of the user.
 * When the instance that stops is being recreated ([onActivityStopped] told so), the app therefore
 * stays visible for [CONFIG_CHANGE_GRACE_MS] more, or until the new instance starts, whichever comes
 * first: the start of an instance ends the grace, so that leaving the app right after a rotation is
 * seen at once. A recreation takes a fraction of a second; two seconds is a margin, not a wait.
 *
 * Read from any thread (the SSH transport, the service), written from the main thread. The clock is
 * the monotonic one of the JVM, injectable for tests.
 */
@Singleton
class ActivityVisibilityTracker internal constructor(
    private val clock: () -> Long,
) : AppVisibility {

    @Inject
    constructor() : this({ System.nanoTime() / NANOS_PER_MILLI })

    private val started = AtomicInteger(0)

    /** When the grace of a recreation ends, on [clock]; [NO_GRACE] when there is none. */
    private val graceEndsAtMs = AtomicLong(NO_GRACE)

    override fun isVisible(): Boolean = started.get() > 0 || clock() < graceEndsAtMs.get()

    /**
     * An instance of the main screen reached STARTED. It ends the grace of a recreation: the screen is back.
     *
     * Returns true when it is the first one, that is when the app comes to the foreground (the count
     * goes from 0 to 1): that is the moment [AppLock] is told the app is back. A second instance that
     * starts before the first one stopped (a relaunch) is no return, and says false.
     */
    fun onActivityStarted(): Boolean {
        val before = started.getAndIncrement()
        graceEndsAtMs.set(NO_GRACE)
        return before == 0
    }

    /**
     * An instance of the main screen left STARTED. Never goes below zero: a stray stop cannot hide a
     * later start. With [changingConfigurations] (the instance is being recreated, see
     * `Activity.isChangingConfigurations`) and no other instance left, the app stays visible for
     * [CONFIG_CHANGE_GRACE_MS], until the new instance starts.
     *
     * Returns true when it was the last one, that is when the app leaves the foreground (the count goes
     * from 1 to 0): that is the moment [AppLock] is told the app is away, so that the stop of an old
     * instance while a new one is up does not start the clock of the lock. A stray stop (nothing was
     * started) says false. Whether the stop is a rotation is for the caller to exclude.
     */
    fun onActivityStopped(changingConfigurations: Boolean = false): Boolean {
        val before = started.getAndUpdate { (it - 1).coerceAtLeast(0) }
        // Only the stop of a screen that was up can be followed by its successor.
        if (before == 1 && changingConfigurations) {
            graceEndsAtMs.set(clock() + CONFIG_CHANGE_GRACE_MS)
        }
        return before == 1
    }

    companion object {
        /** How long a recreation for a configuration change is given to bring the new instance up. */
        const val CONFIG_CHANGE_GRACE_MS = 2_000L

        private const val NO_GRACE = Long.MIN_VALUE
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}

/**
 * The [AppVisibility] the app hands out: on screen *and* not behind the lock screen (see [AppLock]).
 *
 * A prompt that asks for a person (a security key, a fingerprint, the key of a server never seen)
 * would otherwise open under the lock screen, in front of nobody who proved who they are, and wait
 * for its deadline. Seen from here the locked app is as absent as one in the background: those
 * prompts end at once with "nobody on screen", and a tunnel that needs one is held and the user is
 * told by the notification, exactly as in the background.
 *
 * The prompt that unlocks the app is not one of them, it does not ask this (see
 * `BiometricPromptCoordinator.authenticateVault`).
 */
@Singleton
class UnlockedAppVisibility @Inject constructor(
    private val tracker: ActivityVisibilityTracker,
    private val appLock: AppLock,
    private val wipeGate: VaultWipeGate,
) : AppVisibility {

    /** Without the wipe gate of the app (tests). Not an injection point. */
    constructor(tracker: ActivityVisibilityTracker, appLock: AppLock) : this(tracker, appLock, VaultWipeGate())

    /**
     * Also not visible while the wipe of the vault has the app closed (see [VaultWipeGate]): the modal
     * of the wipe covers the screens, nobody can answer a prompt under it, and a prompt that is up or
     * queued must end with "nobody on screen" so that the closing of the wipe is not held by a person.
     */
    override fun isVisible(): Boolean =
        tracker.isVisible() && !appLock.isLocked() && !wipeGate.isClosed()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AppVisibilityModule {
    // No @Singleton here: the implementation is the singleton, the alias hands out the same instance.
    @Binds
    abstract fun bindAppVisibility(impl: UnlockedAppVisibility): AppVisibility
}
