// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.vault

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.techtical.nextsh.core.PendingAuthResume
import fr.techtical.nextsh.core.crypto.KeystoreManager
import fr.techtical.nextsh.core.lock.PromptsEndedByLock
import fr.techtical.nextsh.core.ssh.KnownHostsVerifier
import fr.techtical.nextsh.core.ssh.SshSessionManager
import fr.techtical.nextsh.core.ssh.SshTunnelManager
import fr.techtical.nextsh.core.ssh.TransferTracker
import fr.techtical.nextsh.core.sync.AndroidSyncScheduler
import fr.techtical.nextsh.data.db.AppDatabase
import fr.techtical.nextsh.data.preferences.SettingsDataStore
import fr.techtical.nextsh.service.AuthRequiredNotifier
import fr.techtical.nextsh.service.DeletedTunnelNotifier
import fr.techtical.nextsh.service.SftpTransferServiceControl
import fr.techtical.nextsh.ui.browser.BrowserSessionHolder
import fr.techtical.nextsh.ui.browser.WebDataEraser
import fr.techtical.nextsh.ui.sessions.TerminalTabStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** How long the closing waits for the LAN sync to leave SYNCING. */
internal const val WIPE_SYNC_WAIT_MS = 5_000L

/** How long the closing waits for the copies of the cancelled transfers to end. */
internal const val WIPE_TRANSFERS_WAIT_MS = 5_000L

/** How long the closing waits for the SSH clients to close. */
internal const val WIPE_SESSIONS_WAIT_MS = 10_000L

/** The file of [VaultWipeMarker]. Not sensitive: it holds one flag. */
private const val MARKER_FILE = "nextsh_wipe_marker"
private const val MARKER_KEY = "wipe_pending"

/**
 * The memory of a wipe under way, which survives the death of the process. The closing stops the foreground
 * services, and an app swept from the recents then dies in the middle of the erase: without this the next
 * start would open the unlock screen on a vault that is half erased. [AndroidVaultWipe.resumeIfPending]
 * replays the wipe when it finds the marker.
 *
 * A plain SharedPreferences file, read in `Application.onCreate` before anything starts (a DataStore read
 * suspends), and written with `commit()`: the write is on disk when the call returns, which is the point.
 * Cleared as the very last step of the erase, and by "Close" on the failure dialog: a marker that stayed
 * would wipe, later, a vault that the user created in the meantime.
 */
@Singleton
class VaultWipeMarker @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs by lazy { context.getSharedPreferences(MARKER_FILE, Context.MODE_PRIVATE) }

    fun isPending(): Boolean = prefs.getBoolean(MARKER_KEY, false)

    /** True when the marker is on disk. */
    fun set(): Boolean = prefs.edit().putBoolean(MARKER_KEY, true).commit() && isPending()

    /** True when the marker is gone from the disk. */
    fun clear(): Boolean = prefs.edit().remove(MARKER_KEY).commit() && !isPending()
}

/**
 * Runs the steps of a closing or an erase one after the other, each in its own try: a step that fails does
 * not skip the next ones (the next ones are part of the same promise as the first), and the first failure is
 * raised at the end so that the step counts as failed and is replayed. A [CancellationException] is never
 * kept: it goes up at once.
 */
private class Attempts {
    private var failure: Exception? = null

    suspend fun run(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (failure == null) failure = e
        }
    }

    fun rethrow() {
        failure?.let { throw it }
    }
}

/**
 * What the closing and the erase of the "Wipe the vault" action do to the app, on the real singletons. The
 * order and the handling of failures are in [stopVaultDependentWork], [eraseVaultData] and [VaultWipeRunner]
 * (tested on the JVM); this class is what touches the app, one block per step.
 *
 * The vault is erased by [VaultManager.wipeAllEntries] on the raw preferences: nothing is decrypted, the
 * master key of the Keystore is not asked for and the vault does not need to be open. The master key stays
 * (decision of 1.1.0): the vault stays open and empty, and the connections that read it afterwards find
 * no secret and fail cleanly.
 */
class AndroidVaultWipeOps @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsDataStore: SettingsDataStore,
    private val syncScheduler: AndroidSyncScheduler,
    private val promptsEndedByLock: PromptsEndedByLock,
    private val tunnelManager: SshTunnelManager,
    private val authRequiredNotifier: AuthRequiredNotifier,
    private val pendingAuthResume: PendingAuthResume,
    private val browserSessionHolder: BrowserSessionHolder,
    private val transferTracker: TransferTracker,
    private val terminalTabStore: TerminalTabStore,
    private val sessionManager: SshSessionManager,
    private val vaultManager: VaultManager,
    private val keystoreManager: KeystoreManager,
    private val database: AppDatabase,
    private val knownHostsVerifier: KnownHostsVerifier,
    private val deletedTunnelNotifier: DeletedTunnelNotifier,
    private val transferServiceControl: SftpTransferServiceControl,
    private val webDataEraser: WebDataEraser,
    private val marker: VaultWipeMarker,
) : VaultWipeStopOps, VaultWipeEraseOps {

    // ── Start ────────────────────────────────────────────────────────────────

    /**
     * The first thing a wipe does: writes the marker that lets a dying process resume it. A marker that
     * cannot be written is logged and the wipe goes on: the user asked for it, and the wipe usually
     * ends before the process does.
     */
    suspend fun beginWipe() {
        if (!withContext(Dispatchers.IO) { marker.set() }) Timber.w("Wipe marker could not be written")
    }

    // ── Closing ──────────────────────────────────────────────────────────────

    override suspend fun stopSync(): Boolean {
        val attempts = Attempts()
        // Off first: if the process dies in the middle, `App.onCreate` does not start the sync again.
        attempts.run { settingsDataStore.updateSyncEnabled(false) }
        var ended = false
        attempts.run { ended = syncScheduler.stopAndAwait(WIPE_SYNC_WAIT_MS) }
        attempts.rethrow()
        return ended
    }

    override suspend fun endPrompts() {
        promptsEndedByLock.endPrompts()
    }

    override suspend fun stopTunnels() {
        val attempts = Attempts()
        // Every tunnel is marked as stopped by hand before anything closes (nothing reconnects), and the
        // closed gate refuses every start. The flags of manual stop are NOT cleared here.
        attempts.run { tunnelManager.stopAllTunnels() }
        attempts.run { authRequiredNotifier.cancel() }
        attempts.run { pendingAuthResume.retainWaiting(emptySet()) }
        // The WebView belongs to the main thread.
        attempts.run { withContext(Dispatchers.Main) { browserSessionHolder.close() } }
        attempts.rethrow()
    }

    override suspend fun cancelTransfers(): Boolean {
        transferTracker.cancelAll()
        // False when the system refuses a service start from the background: nothing is running either
        // (a transfer holds the service in the foreground), and the tracker is already cancelled.
        transferServiceControl.cancelAll()
        // The copies themselves, not the tracker: the tracker says CANCELLED at once, the job ends later.
        return withTimeoutOrNull(WIPE_TRANSFERS_WAIT_MS) {
            transferTracker.copying.first { it.isEmpty() }
            true
        } ?: false
    }

    override suspend fun closeTabs() {
        terminalTabStore.closeAll()
    }

    override suspend fun closeSessions(): Boolean = sessionManager.closeAllAndWait(WIPE_SESSIONS_WAIT_MS)

    override suspend fun clearTransferList() {
        transferTracker.clear()
    }

    // ── Erase ────────────────────────────────────────────────────────────────

    override suspend fun eraseVault() {
        val attempts = Attempts()
        // The biometric keys of the Keystore: without it the `ssh_keys` rows are gone but the keys still sign.
        attempts.run { withContext(Dispatchers.IO) { keystoreManager.deleteAllBiometricKeys() } }
        attempts.run {
            val erased = withContext(Dispatchers.IO) { vaultManager.wipeAllEntries() }
            if (!erased) throw IOException("Vault could not be erased")
        }
        attempts.rethrow()
    }

    override suspend fun clearTables() {
        withContext(Dispatchers.IO) { database.clearAllTables() }
        // The sync state still describes the erased data (conflict count, last sync, error).
        syncScheduler.resetAfterWipe()
    }

    override suspend fun clearKnownHosts() {
        withContext(Dispatchers.IO) { knownHostsVerifier.clearAll() }
    }

    override suspend fun disableSync() {
        settingsDataStore.updateSyncEnabled(false)
    }

    override suspend fun deleteCredentialMeta() {
        withContext(Dispatchers.IO) { deleteCredentialMetaFile(File(context.filesDir, CREDENTIAL_META_FILE_NAME)) }
    }

    override suspend fun clearWebData() {
        withContext(Dispatchers.Main) { webDataEraser.eraseAll() }
    }

    override suspend fun cancelNotifications() {
        deletedTunnelNotifier.cancelAll()
    }

    override suspend fun resetOnboarding() {
        settingsDataStore.setOnboardingCompleted(false)
    }

    override suspend fun clearMarker() {
        if (!withContext(Dispatchers.IO) { marker.clear() }) throw IOException("Wipe marker could not be cleared")
    }

    override suspend fun vaultGone(): Boolean = withContext(Dispatchers.IO) { vaultManager.isWiped() }
}

/**
 * "Wipe the vault" for the whole app. The state is the one of [VaultWipeGate], which the app reads (the modal
 * of the activity, the prompts, the tunnels, the sync, the sessions); the work runs in a scope of the
 * application and never in a view model or a screen: leaving the settings, a rotation or the destruction of
 * the activity must not cut the wipe in the middle.
 *
 * See [VaultWipeRunner] for the sequence, the retry and the end, and [VaultWipeMarker] for the resume after
 * the death of the process.
 */
@Singleton
class AndroidVaultWipe internal constructor(
    private val ops: AndroidVaultWipeOps,
    private val marker: VaultWipeMarker,
    private val gate: VaultWipeGate,
    scope: CoroutineScope,
) {
    @Inject
    constructor(
        ops: AndroidVaultWipeOps,
        marker: VaultWipeMarker,
        gate: VaultWipeGate,
    ) : this(ops, marker, gate, CoroutineScope(SupervisorJob() + Dispatchers.Default))

    private val runner = VaultWipeRunner(
        scope = scope,
        gate = gate,
        begin = { ops.beginWipe() },
        stop = { stopVaultDependentWork(ops) },
        erase = { steps -> eraseVaultData(ops, steps) },
        ops = ops,
    )

    /** True when "Close" could not end the failure (the marker stays): the failure dialog says why. */
    val closeBlocked: StateFlow<Boolean> get() = gate.closeBlocked

    /** Where the wipe stands, for the modal and for the restart of the activity. */
    val state: StateFlow<VaultWipeState> get() = gate.state

    /** Starts the wipe. Ignored while one is under way. */
    fun start() = runner.start()

    /** After a failure: replays the closing, then what is left to erase. */
    fun retry() = runner.retry()

    /** The user closed the failure dialog. */
    fun dismissFailure() = runner.dismissFailure()

    /**
     * Called by `Application.onCreate`, before anything that starts a tunnel, a sync or a session: resumes
     * the wipe that the death of the process cut. True when there was one. The app is closed from this call
     * on (the gate), so the rest of the start finds it refusing.
     */
    fun resumeIfPending(): Boolean {
        if (!marker.isPending()) return false
        Timber.w("A wipe of the vault was cut by the end of the process, resuming it")
        runner.resume()
        return true
    }
}
