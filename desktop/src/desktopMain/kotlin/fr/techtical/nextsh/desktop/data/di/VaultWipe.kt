// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.data.di

import fr.techtical.nextsh.desktop.core.ssh.DesktopKnownHostsStore
import fr.techtical.nextsh.desktop.core.ssh.DesktopTransferManager
import fr.techtical.nextsh.desktop.data.db.DesktopDatabase
import fr.techtical.nextsh.desktop.data.preferences.DesktopSettingsStore
import fr.techtical.nextsh.desktop.service.DesktopTunnelService
import fr.techtical.nextsh.desktop.sessions.DesktopSessionManager
import fr.techtical.nextsh.desktop.sync.LanSyncServerLifecycle
import fr.techtical.nextsh.shared.domain.vault.VaultManager
import fr.techtical.nextsh.shared.util.Logger
import fr.techtical.nextsh.shared.util.errorClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "VaultWipe"

/** Where the "Wipe the vault" action stands. Held by the container: the whole app reads it, not one screen. */
enum class VaultWipeState { IDLE, RUNNING, FAILED }

/** The closing steps that come before the erase, see [stopVaultDependentWork]. */
internal enum class StopStep { SYNC, HOST_KEYS, TUNNELS, TRANSFERS, SESSIONS, TRANSFER_LIST, TEMPORARY_FILES }

/** The erase steps, see [eraseVaultData]. */
internal enum class EraseStep { VAULT, TABLES, KNOWN_HOSTS, SYNC_SWITCH, CREDENTIAL_META }

/** Fixed log texts, one per step: a log line never carries anything but fixed text and the class of the error. */
private fun StopStep.logFailure(e: Throwable) = when (this) {
    StopStep.SYNC -> Logger.e(TAG, "Wipe closing step failed: LAN sync (${errorClass(e)})")
    StopStep.HOST_KEYS -> Logger.e(TAG, "Wipe closing step failed: host key prompts (${errorClass(e)})")
    StopStep.TUNNELS -> Logger.e(TAG, "Wipe closing step failed: tunnels (${errorClass(e)})")
    StopStep.TRANSFERS -> Logger.e(TAG, "Wipe closing step failed: transfers (${errorClass(e)})")
    StopStep.SESSIONS -> Logger.e(TAG, "Wipe closing step failed: sessions (${errorClass(e)})")
    StopStep.TRANSFER_LIST -> Logger.e(TAG, "Wipe closing step failed: transfer list (${errorClass(e)})")
    StopStep.TEMPORARY_FILES -> Logger.e(TAG, "Wipe closing step failed: temporary files (${errorClass(e)})")
}

private fun StopStep.logNotDone() = when (this) {
    StopStep.TRANSFERS -> Logger.w(TAG, "Wipe closing step incomplete: transfers did not end in time")
    StopStep.SESSIONS -> Logger.w(TAG, "Wipe closing step incomplete: sessions did not close in time")
    else -> Logger.w(TAG, "Wipe closing step incomplete")
}

private fun EraseStep.logFailure(e: Throwable) = when (this) {
    EraseStep.VAULT -> Logger.e(TAG, "Wipe erase step failed: vault (${errorClass(e)})")
    EraseStep.TABLES -> Logger.e(TAG, "Wipe erase step failed: tables (${errorClass(e)})")
    EraseStep.KNOWN_HOSTS -> Logger.e(TAG, "Wipe erase step failed: known hosts (${errorClass(e)})")
    EraseStep.SYNC_SWITCH -> Logger.e(TAG, "Wipe erase step failed: sync switch (${errorClass(e)})")
    EraseStep.CREDENTIAL_META -> Logger.e(TAG, "Wipe erase step failed: credential metadata (${errorClass(e)})")
}

/**
 * Counterpart of [startVaultDependentServices] for the "Wipe the vault" action: stops and closes everything
 * that holds a connection or reads the vault and the database, BEFORE anything is erased. Erasing first
 * would leave live sessions, tunnels that reconnect against an empty vault and a sync push that writes the
 * database back.
 *
 * Order matters:
 *  1. The LAN sync stops first, so that no peer can write after the erase. Host key questions are then
 *     refused for good until the wipe ends (the one on screen, the ones queued behind it, the later ones):
 *     a connection or a tunnel waiting for an answer would otherwise never end, and a question surviving
 *     the wipe could write the fingerprint of an erased host into the new vault.
 *  2. The tunnels: every one is stopped for good, nothing reconnects ([DesktopTunnelService.onVaultWiped]).
 *  3. The transfers are cancelled (bounded wait). Before the sessions: a cancelled download deletes its
 *     temporary file and a cancelled replacement leaves the original intact only while its channel is open.
 *  4. The sessions: every tab is closed and every SSH session disconnected, in parallel and bounded.
 *  5. The transfer list is emptied (after the sessions: a transfer that outlived its wait is over by now,
 *     and the ones that are not have their later writes ignored), then the temporary files of the downloads
 *     are deleted.
 *
 * Every step is best effort: one that fails (logged by class only) never prevents the following ones, and
 * never the erase that comes after. The user asked for the wipe, a session that would not close is no
 * reason to keep their secrets. A [CancellationException] is never swallowed. Returns true when every step
 * went through.
 */
internal suspend fun stopVaultDependentWork(
    syncLifecycle: LanSyncServerLifecycle,
    tunnelService: DesktopTunnelService,
    transferManager: DesktopTransferManager,
    sessionManager: DesktopSessionManager,
): Boolean {
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

    step(StopStep.SYNC) { syncLifecycle.onVaultLocked(); true }
    step(StopStep.HOST_KEYS) { sessionManager.setHostKeyLockdown(true); true }
    step(StopStep.TUNNELS) { tunnelService.onVaultWiped(); true }
    step(StopStep.TRANSFERS) { transferManager.cancelAll() }
    step(StopStep.SESSIONS) { sessionManager.closeAllSessionsAndWait() }
    step(StopStep.TRANSFER_LIST) { transferManager.clearAll(); true }
    step(StopStep.TEMPORARY_FILES) { transferManager.deleteTemporaries(); true }
    return allDone
}

/**
 * The erase itself, once [stopVaultDependentWork] is done: the vault (and with it the recovery phrase and
 * the LAN secrets of the enrolled devices), every table in one transaction that notifies its observers, the
 * known hosts, the LAN sync switch (the enrolled devices are gone: a server listening for nobody is not a
 * first-launch state) and the credential metadata files (they describe credentials that no longer exist).
 *
 * Only the [steps] asked for run, so that a retry replays what failed and nothing else. Every step is tried
 * even if one fails. Returns the steps that failed (empty: everything is erased). A [CancellationException]
 * is never swallowed.
 */
internal suspend fun eraseVaultData(
    vaultManager: VaultManager,
    database: DesktopDatabase,
    knownHostsStore: DesktopKnownHostsStore,
    settingsStore: DesktopSettingsStore,
    credentialMetaFile: File,
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

    step(EraseStep.VAULT) { vaultManager.wipeVault() }
    step(EraseStep.TABLES) { withContext(Dispatchers.IO) { database.wipeAllTables() } }
    step(EraseStep.KNOWN_HOSTS) { withContext(Dispatchers.IO) { knownHostsStore.deleteAll() } }
    step(EraseStep.SYNC_SWITCH) { settingsStore.updateSyncEnabled(false) }
    step(EraseStep.CREDENTIAL_META) { withContext(Dispatchers.IO) { deleteCredentialMetaFile(credentialMetaFile) } }
    return failed
}

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
    if (left > 0) throw java.io.IOException("Credential metadata file could not be deleted")
}

/**
 * Runs the "Wipe the vault" action for the whole app, one wipe at a time: [state] is RUNNING from the
 * request to the end (the screen shows its modal and the global shortcuts are ignored meanwhile), then IDLE
 * once everything is erased and [onDone] has run, or FAILED when something could not be erased even after a
 * second attempt. FAILED keeps what is left to erase.
 *
 * The navigation to the creation of the new vault ([onDone]) happens ONLY when every step succeeded and the
 * vault is gone: leaving earlier would let the user create a vault that the erase then deletes, and leaving
 * with data left behind would hide an incomplete wipe.
 *
 * FAILED is as closed as RUNNING: the host key lockdown stays on and the shortcuts stay ignored.
 * [retry] replays the closing first (a session opened since the failure must not survive), then what is
 * left to erase. The lockdown is lifted only at the real end: success, [dismissFailure] ("Close") or a
 * cancellation. "Close" then calls [abandon] with whether the vault is still there, so that the app does
 * not stay on the main screens with a deleted or locked vault.
 *
 * [stop] closes everything first (see [stopVaultDependentWork]), [erase] runs the given erase steps and
 * returns those that failed, [vaultGone] says whether the vault is really gone, [afterWipe] lifts what the
 * closing put in place (the host key lockdown), [abandon] sends the user to the unlock screen (vault still
 * there) or to the creation screen (vault gone).
 */
internal class VaultWipeRunner(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<VaultWipeState>,
    private val stop: suspend () -> Boolean,
    private val erase: suspend (Set<EraseStep>) -> Set<EraseStep>,
    private val vaultGone: suspend () -> Boolean,
    private val afterWipe: () -> Unit,
    private val abandon: suspend (vaultStillThere: Boolean) -> Unit = {},
) {
    private var pending: Set<EraseStep> = emptySet()
    private var onDone: () -> Unit = {}

    /** Starts a wipe. Ignored while another one runs. */
    fun start(onDone: () -> Unit) {
        if (!state.compareAndSet(VaultWipeState.IDLE, VaultWipeState.RUNNING) &&
            !state.compareAndSet(VaultWipeState.FAILED, VaultWipeState.RUNNING)
        ) return
        this.onDone = onDone
        pending = EraseStep.entries.toSet()
        scope.launch { run() }
    }

    /** Replays the closing and what is left to erase, after a FAILED. Ignored in any other state. */
    fun retry() {
        if (!state.compareAndSet(VaultWipeState.FAILED, VaultWipeState.RUNNING)) return
        scope.launch { run() }
    }

    /** The user closed the failure dialog: lifts the lockdown, back to idle, then [abandon]. */
    fun dismissFailure() {
        if (!state.compareAndSet(VaultWipeState.FAILED, VaultWipeState.IDLE)) return
        afterWipe()
        scope.launch {
            val still = try {
                !vaultGone()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                true
            }
            abandon(still)
        }
    }

    private suspend fun run() {
        try {
            try {
                try {
                    stop()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A failing closing step never prevents the erase.
                    Logger.e(TAG, "Wipe closing failed (${errorClass(e)})")
                }
                // Twice: a step that failed on a transient cause (a file held open for a moment) gets one more go.
                repeat(2) {
                    if (pending.isNotEmpty()) pending = erase(pending)
                }
                if (pending.isEmpty() && !vaultGone()) pending = setOf(EraseStep.VAULT)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "Wipe erase failed (${errorClass(e)})")
                if (pending.isEmpty()) pending = setOf(EraseStep.VAULT)
            }
            if (pending.isEmpty()) {
                afterWipe()
                state.value = VaultWipeState.IDLE
                onDone()
            } else {
                // Stays closed: lockdown on, shortcuts ignored, until a retry ends or the user closes it.
                state.value = VaultWipeState.FAILED
            }
        } finally {
            // Whatever way out (a cancellation included) that is not one of the two above: do not leave the
            // lockdown on, nor the state at RUNNING, for the rest of the process.
            if (state.value == VaultWipeState.RUNNING) {
                afterWipe()
                state.value = VaultWipeState.IDLE
            }
        }
    }
}
