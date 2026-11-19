// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.vault

import fr.techtical.nextsh.shared.util.errorClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** The closing steps that come before the erase, see [stopVaultDependentWork]. */
internal enum class StopStep { SYNC, PROMPTS, TUNNELS, TRANSFERS, TABS, SESSIONS, TRANSFER_LIST }

/** The erase steps, see [eraseVaultData]. */
internal enum class EraseStep {
    VAULT, TABLES, KNOWN_HOSTS, SYNC_SWITCH, CREDENTIAL_META, WEB_DATA, NOTIFICATIONS, ONBOARDING, MARKER,
}

/**
 * What the closing needs from the app, one call per [StopStep]. An interface so that the order and the
 * handling of failures are tested on the JVM; [AndroidVaultWipeOps] is the one that touches the app.
 */
interface VaultWipeStopOps {
    /** Stops the LAN sync and waits for the passes in flight. False when one did not end in time. */
    suspend fun stopSync(): Boolean

    /** Ends the prompts that are up: every one answers "nobody on screen". */
    suspend fun endPrompts()

    /** Stops every tunnel for good: nothing reconnects, nothing starts. */
    suspend fun stopTunnels()

    /** Cancels the transfers and waits, bounded, for them to end. False when one did not end in time. */
    suspend fun cancelTransfers(): Boolean

    /** Closes the terminal tabs. */
    suspend fun closeTabs()

    /** Closes every SSH session whoever holds it, bounded. False when one did not close in time. */
    suspend fun closeSessions(): Boolean

    /** Empties the list of the transfers: it holds file names and remote paths. */
    suspend fun clearTransferList()
}

/** What the erase needs from the app, one call per [EraseStep]. An error is an exception. */
interface VaultWipeEraseOps {
    /** Erases the vault without decrypting anything, and the biometric keys of the Keystore. */
    suspend fun eraseVault()
    suspend fun clearTables()
    suspend fun clearKnownHosts()
    suspend fun disableSync()
    suspend fun deleteCredentialMeta()
    suspend fun clearWebData()
    suspend fun cancelNotifications()

    /** Makes the next start open the creation of the vault. */
    suspend fun resetOnboarding()

    /** Forgets that a wipe is under way: the process that dies from now on does not resume it. */
    suspend fun clearMarker()

    /** Whether the vault is really gone: no entry left in its file, no old vault file. */
    suspend fun vaultGone(): Boolean
}

/** Fixed log texts, one per step: a log line never carries anything but fixed text and the class of the error. */
private fun StopStep.logFailure(e: Throwable) = when (this) {
    StopStep.SYNC -> Timber.e("Wipe closing step failed: LAN sync (${errorClass(e)})")
    StopStep.PROMPTS -> Timber.e("Wipe closing step failed: prompts (${errorClass(e)})")
    StopStep.TUNNELS -> Timber.e("Wipe closing step failed: tunnels (${errorClass(e)})")
    StopStep.TRANSFERS -> Timber.e("Wipe closing step failed: transfers (${errorClass(e)})")
    StopStep.TABS -> Timber.e("Wipe closing step failed: terminal tabs (${errorClass(e)})")
    StopStep.SESSIONS -> Timber.e("Wipe closing step failed: sessions (${errorClass(e)})")
    StopStep.TRANSFER_LIST -> Timber.e("Wipe closing step failed: transfer list (${errorClass(e)})")
}

private fun StopStep.logNotDone() = when (this) {
    StopStep.SYNC -> Timber.w("Wipe closing step incomplete: sync did not end in time")
    StopStep.TRANSFERS -> Timber.w("Wipe closing step incomplete: transfers did not end in time")
    StopStep.SESSIONS -> Timber.w("Wipe closing step incomplete: sessions did not close in time")
    else -> Timber.w("Wipe closing step incomplete")
}

private fun EraseStep.logFailure(e: Throwable) = when (this) {
    EraseStep.VAULT -> Timber.e("Wipe erase step failed: vault (${errorClass(e)})")
    EraseStep.TABLES -> Timber.e("Wipe erase step failed: tables (${errorClass(e)})")
    EraseStep.KNOWN_HOSTS -> Timber.e("Wipe erase step failed: known hosts (${errorClass(e)})")
    EraseStep.SYNC_SWITCH -> Timber.e("Wipe erase step failed: sync switch (${errorClass(e)})")
    EraseStep.CREDENTIAL_META -> Timber.e("Wipe erase step failed: credential metadata (${errorClass(e)})")
    EraseStep.WEB_DATA -> Timber.e("Wipe erase step failed: web data (${errorClass(e)})")
    EraseStep.NOTIFICATIONS -> Timber.e("Wipe erase step failed: notifications (${errorClass(e)})")
    EraseStep.ONBOARDING -> Timber.e("Wipe erase step failed: onboarding flag (${errorClass(e)})")
    EraseStep.MARKER -> Timber.e("Wipe erase step failed: wipe marker (${errorClass(e)})")
}

/**
 * Closes everything that holds a connection or reads the vault and the database, BEFORE anything is
 * erased. Erasing first would leave live sessions, tunnels that reconnect against an empty vault and a
 * sync that writes the database back.
 *
 * Order matters:
 *  1. The LAN sync stops first and is waited for, so that no peer can write after the erase.
 *  2. The prompts end: the one on screen, the ones queued, the later ones (the closed gate makes the
 *     app count as absent for them) answer "no". A connection or a tunnel waiting for an answer would
 *     otherwise never end, and a question surviving the wipe could trust the key of an erased host.
 *  3. The tunnels: every one is stopped for good, nothing reconnects and no start is accepted. BEFORE
 *     the tables: otherwise the tunnels that disappear from the database read as deleted on another
 *     device and the user is told so.
 *  4. The transfers are cancelled (bounded wait). Before the sessions: a cancelled upload cleans its
 *     temporary file while its channel is open.
 *  5. The terminal tabs are closed.
 *  6. Every session is closed whoever holds it (explorers, offers, holds), bounded.
 *  7. The transfer list is emptied.
 *
 * Every step is best effort, in a try of its own: one that fails (logged by class only) never prevents
 * the following ones, and never the erase that comes after (the caller runs it whatever this returns).
 * The user asked for the wipe, a session that would not close is no reason to keep their secrets. A
 * [CancellationException] is never swallowed. Returns true when every step went through.
 */
internal suspend fun stopVaultDependentWork(ops: VaultWipeStopOps): Boolean {
    var allDone = true

    /** A step returns false when it ended without finishing its job (a timeout). */
    suspend fun step(which: StopStep, block: suspend () -> Boolean) {
        try {
            if (!block()) {
                allDone = false
                which.logNotDone()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            allDone = false
            which.logFailure(e)
        }
    }

    step(StopStep.SYNC) { ops.stopSync() }
    step(StopStep.PROMPTS) { ops.endPrompts(); true }
    step(StopStep.TUNNELS) { ops.stopTunnels(); true }
    step(StopStep.TRANSFERS) { ops.cancelTransfers() }
    step(StopStep.TABS) { ops.closeTabs(); true }
    step(StopStep.SESSIONS) { ops.closeSessions() }
    step(StopStep.TRANSFER_LIST) { ops.clearTransferList(); true }
    return allDone
}

/**
 * The erase itself, once [stopVaultDependentWork] is done: the vault (and with it the LAN secrets of
 * the enrolled devices) and the biometric keys, every table in one transaction that notifies its
 * observers, the known hosts, the LAN sync switch (the enrolled devices are gone), the credential
 * metadata files (they describe credentials that no longer exist), the web data of the tunnel browser
 * and the notifications that name tunnels.
 *
 * Two steps are conditional, and stay pending until their condition holds:
 *  - ONBOARDING (the next start opens the creation of the vault) only once the vault is erased: a start
 *    on the creation screen over a vault that still holds secrets would hide an incomplete wipe.
 *  - MARKER (a dying process no longer resumes the wipe) only once everything else is erased: it is the
 *    memory of the work left.
 *
 * Only the [steps] asked for run, so that a retry replays what failed and nothing else. Every step is
 * tried even if one fails. Returns the steps that failed or are blocked (empty: everything is erased).
 * A [CancellationException] is never swallowed.
 */
internal suspend fun eraseVaultData(
    ops: VaultWipeEraseOps,
    steps: Set<EraseStep> = EraseStep.entries.toSet(),
): Set<EraseStep> {
    val failed = linkedSetOf<EraseStep>()

    suspend fun step(which: EraseStep, block: suspend () -> Unit) {
        if (which !in steps) return
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed += which
            which.logFailure(e)
        }
    }

    step(EraseStep.VAULT) { ops.eraseVault() }
    step(EraseStep.TABLES) { ops.clearTables() }
    step(EraseStep.KNOWN_HOSTS) { ops.clearKnownHosts() }
    step(EraseStep.SYNC_SWITCH) { ops.disableSync() }
    step(EraseStep.CREDENTIAL_META) { ops.deleteCredentialMeta() }
    step(EraseStep.WEB_DATA) { ops.clearWebData() }
    step(EraseStep.NOTIFICATIONS) { ops.cancelNotifications() }
    // Only once the vault is erased: this pass, or an earlier one (the step is then not asked for).
    if (EraseStep.ONBOARDING in steps) {
        if (EraseStep.VAULT in failed) failed += EraseStep.ONBOARDING else step(EraseStep.ONBOARDING) { ops.resetOnboarding() }
    }
    // Last, and only when nothing is left to do: it is the memory of the work left.
    if (EraseStep.MARKER in steps) {
        if (failed.isNotEmpty()) failed += EraseStep.MARKER else step(EraseStep.MARKER) { ops.clearMarker() }
    }
    return failed
}

/** Name of the file of the credential metadata, in the files folder of the app. */
internal const val CREDENTIAL_META_FILE_NAME = "credential-meta.json"

/**
 * Deletes the credential metadata file, the temporary sibling an interrupted write may have left, and the
 * `.corrupt.<timestamp>` copies the store makes of a file it cannot read (they hold content hashes of
 * secrets). A file that is already absent is fine, one that cannot be deleted is an error; every deletion
 * is tried before the error is raised.
 */
internal fun deleteCredentialMetaFile(file: File) {
    val parent = file.absoluteFile.parentFile
    val targets = buildList {
        add(file)
        add(File(parent, file.name + ".tmp"))
        parent?.listFiles { candidate -> candidate.name.startsWith(file.name + ".corrupt.") }?.let { addAll(it) }
    }
    val left = targets.count { target -> target.exists() && !target.delete() && target.exists() }
    if (left > 0) throw IOException("Credential metadata file could not be deleted")
}

/**
 * Runs the "Wipe the vault" action for the whole app, one wipe at a time. The state is the one of the
 * [gate]: RUNNING from the request to the end (the modal of the activity is up, and the app is closed for
 * everything that opens, starts, syncs or asks), then DONE once everything is erased, or FAILED when
 * something could not be erased even after a second attempt. FAILED keeps what is left to erase and
 * stays closed.
 *
 * The app is told to restart on the creation of the vault (DONE) ONLY when every step succeeded and the
 * vault is gone: leaving earlier would let the user create a vault that the erase then deletes, and
 * leaving with data left behind would hide an incomplete wipe.
 *
 * The gate is released only at the real end, and always: success (DONE, then the activity restarts the
 * app and the gate goes back to IDLE), [dismissFailure] ("Close") or a cancellation (the `finally`).
 * [retry] replays the closing first (a session opened since the failure must not survive), then what is
 * left to erase. [dismissFailure] says whether the vault is still there: gone, the next start opens the
 * creation of the vault and the app restarts on it; still there, the app stays where it is.
 *
 * [scope] must be a scope of the application, never one of a screen: leaving the settings, a rotation
 * or the destruction of the activity must not cut a wipe in the middle.
 *
 * [begin] runs first (the marker that lets a dying process resume the wipe), [stop] closes everything,
 * [erase] runs the given erase steps and returns those that failed, [ops] answers [VaultWipeEraseOps.vaultGone]
 * and does the last erase steps of an abandoned wipe.
 */
internal class VaultWipeRunner(
    private val scope: CoroutineScope,
    private val gate: VaultWipeGate,
    private val begin: suspend () -> Unit,
    private val stop: suspend () -> Boolean,
    private val erase: suspend (Set<EraseStep>) -> Set<EraseStep>,
    private val ops: VaultWipeEraseOps,
) {
    private var pending: Set<EraseStep> = emptySet()
    private val dismissing = AtomicBoolean(false)

    /** Starts a wipe. Ignored while another one runs or while the app is wiped and not yet restarted. */
    fun start() {
        if (dismissing.get()) return
        if (!gate.compareAndSet(VaultWipeState.IDLE, VaultWipeState.RUNNING) &&
            !gate.compareAndSet(VaultWipeState.FAILED, VaultWipeState.RUNNING)
        ) return
        gate.setCloseBlocked(false)
        pending = EraseStep.entries.toSet()
        scope.launch { run(withBegin = true) }
    }

    /**
     * Resumes a wipe that the death of the process cut: the same wipe, from the start (the closing is
     * trivial in a fresh process, and every erase step is idempotent). Ignored like [start].
     */
    fun resume() = start()

    /** Replays the closing and what is left to erase, after a FAILED. Ignored in any other state. */
    fun retry() {
        if (dismissing.get() || !gate.compareAndSet(VaultWipeState.FAILED, VaultWipeState.RUNNING)) return
        gate.setCloseBlocked(false)
        scope.launch { run(withBegin = false) }
    }

    /**
     * The user closed the failure dialog. The gate stays closed until this is over, then the app is
     * restarted on the creation of the vault (the vault is gone) or stays as it is (it is still there).
     * Ignored unless FAILED, and ignored twice.
     */
    fun dismissFailure() {
        if (gate.state.value != VaultWipeState.FAILED || !dismissing.compareAndSet(false, true)) return
        gate.setCloseBlocked(false)
        scope.launch {
            try {
                val stillThere = try {
                    !ops.vaultGone()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    true
                }
                // A retry that won the race while the vault was read: the wipe runs again, nothing to abandon.
                if (gate.state.value != VaultWipeState.FAILED) return@launch
                // The marker goes whatever the result: a process that dies after the user closed the dialog
                // must not wipe, later, a vault that the user created meanwhile. If it cannot be cleared the
                // dialog stays (FAILED): leaving would let the next start wipe the NEW vault.
                val markerCleared = try {
                    ops.clearMarker()
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e("Wipe abandon step failed (${errorClass(e)})")
                    false
                }
                if (!markerCleared) {
                    gate.setCloseBlocked(true)
                    return@launch
                }
                gate.setCloseBlocked(false)
                if (!stillThere) quietly { ops.resetOnboarding() }
                // Compare and set: a retry that started meanwhile keeps its RUNNING.
                gate.compareAndSet(VaultWipeState.FAILED, if (stillThere) VaultWipeState.IDLE else VaultWipeState.DONE)
            } finally {
                dismissing.set(false)
            }
        }
    }

    private suspend fun quietly(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e("Wipe abandon step failed (${errorClass(e)})")
        }
    }

    private suspend fun run(withBegin: Boolean) {
        try {
            if (withBegin) quietly { begin() }
            try {
                stop()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A failing closing never prevents the erase.
                Timber.e("Wipe closing failed (${errorClass(e)})")
            }
            try {
                // Twice: a step that failed on a transient cause (a file held open for a moment) gets one more go.
                repeat(2) {
                    if (pending.isNotEmpty()) pending = erase(pending)
                }
                if (pending.isEmpty() && !ops.vaultGone()) pending = setOf(EraseStep.VAULT, EraseStep.ONBOARDING, EraseStep.MARKER)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e("Wipe erase failed (${errorClass(e)})")
                if (pending.isEmpty()) pending = setOf(EraseStep.VAULT, EraseStep.ONBOARDING, EraseStep.MARKER)
            }
            // Stays closed on a failure: lockdown on, until a retry ends or the user closes the dialog.
            gate.set(if (pending.isEmpty()) VaultWipeState.DONE else VaultWipeState.FAILED)
        } finally {
            // Whatever way out that is not one of the two above (a cancellation): do not leave the app
            // closed, nor the state at RUNNING, for the rest of the process.
            if (gate.state.value == VaultWipeState.RUNNING) gate.set(VaultWipeState.IDLE)
        }
    }
}
