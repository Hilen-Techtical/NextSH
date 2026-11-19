// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import fr.techtical.nextsh.core.logging.DebugLogTree
import fr.techtical.nextsh.core.lock.AppLock
import fr.techtical.nextsh.core.lock.OwnerPromptEndedAway
import fr.techtical.nextsh.core.lock.PromptsEndedByLock
import fr.techtical.nextsh.core.logging.ReleaseLogTree
import fr.techtical.nextsh.core.shortcut.ShortcutUpdater
import fr.techtical.nextsh.core.ssh.BiometricPromptCoordinator
import fr.techtical.nextsh.core.ssh.Fido2PromptCoordinator
import fr.techtical.nextsh.core.ssh.HostKeyPromptCoordinator
import fr.techtical.nextsh.core.ssh.SshSessionManager
import fr.techtical.nextsh.core.ssh.TunnelReconciler
import fr.techtical.nextsh.core.vault.AndroidVaultWipe
import fr.techtical.nextsh.data.preferences.SettingsDataStore
import fr.techtical.nextsh.data.repository.OrphanTunnelRepair
import fr.techtical.nextsh.shared.core.sync.SyncScheduler
import fr.techtical.nextsh.ui.sessions.TerminalTabStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import java.security.Security
import javax.inject.Inject

@HiltAndroidApp
class App : Application() {

    @Inject lateinit var shortcutUpdater: ShortcutUpdater
    @Inject lateinit var syncScheduler: SyncScheduler
    @Inject lateinit var settingsDataStore: SettingsDataStore
    @Inject lateinit var sshSessionManager: SshSessionManager
    @Inject lateinit var hostKeyPromptCoordinator: HostKeyPromptCoordinator
    @Inject lateinit var fido2PromptCoordinator: Fido2PromptCoordinator
    @Inject lateinit var biometricPromptCoordinator: BiometricPromptCoordinator
    @Inject lateinit var orphanTunnelRepair: OrphanTunnelRepair
    @Inject lateinit var tunnelReconciler: TunnelReconciler
    @Inject lateinit var terminalTabStore: TerminalTabStore
    @Inject lateinit var appLock: AppLock
    @Inject lateinit var promptsEndedByLock: PromptsEndedByLock
    @Inject lateinit var ownerPromptEndedAway: OwnerPromptEndedAway
    @Inject lateinit var androidVaultWipe: AndroidVaultWipe

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()

        // Android default user.home is "/" (read-only), which breaks
        // java.util.prefs.Preferences persistence used by DeviceIdentity in :shared.
        // Without this, deviceId() regenerates a fresh UUID at every app start,
        // invalidating the peer's EnrolledDevice record and causing HMAC 401 on
        // the next sync. Pointing user.home to the app-private filesDir makes
        // prefs persist across restarts.
        System.setProperty("user.home", filesDir.absolutePath)

        // Remplacer le provider BouncyCastle d'Android (incomplet depuis API 28)
        // par notre version embarquée (bcprov-jdk18on) qui supporte RSA, Ed25519, etc.
        setupBouncyCastleProvider()

        if (BuildConfig.DEBUG) {
            Timber.plant(DebugLogTree())
        } else {
            Timber.plant(ReleaseLogTree())
        }

        // A wipe of the vault that the death of the process cut resumes here, BEFORE anything that opens a
        // session, starts a tunnel or syncs: the app is closed (VaultWipeGate) from this call on, and
        // everything below that would work on the old vault finds it refusing. The wipe runs in its own
        // scope; the modal of the activity shows it as soon as the activity is there.
        val resumingWipe = androidVaultWipe.resumeIfPending()

        // Install the interactive SSH callbacks at App-level, before anything can open a
        // connection (the tunnel reconciler below may). They must be live regardless of which
        // screen the user is on, and they are the only installation: SessionViewModel used to wire
        // them in init, but a connection started from the host list, SFTP or a tunnel never
        // creates that ViewModel, so a fresh install would fail the first connection.
        //  - host-key TOFU: "Could not verify host key..." without it;
        //  - FIDO2 signature: "FIDO2 callback not configured" without it;
        //  - fingerprint of a biometric key: no dialog, and the connection fails, without it.
        sshSessionManager.hostKeyVerificationCallback = { unknown ->
            hostKeyPromptCoordinator.promptUnknown(unknown)
        }
        fido2PromptCoordinator.install(sshSessionManager)
        biometricPromptCoordinator.install(sshSessionManager)

        shortcutUpdater.startObserving()
        tunnelReconciler.startObserving()
        // The terminal tabs follow the edits made to their hosts for as long as the app lives.
        terminalTabStore.startObservingHosts()
        followAutoLockDelay()
        // The prompts that are up when the app locks end as for an app that was left.
        promptsEndedByLock.start(appScope)
        // A vault dialog that ends while the app is away starts the clock of the lock at that moment.
        ownerPromptEndedAway.start(appScope)
        startSyncSchedulerIfEnabled()
        // Not while a wipe resumes: it would write tombstones into a database that is being erased.
        if (!resumingWipe) repairOrphanTunnels()
    }

    /**
     * Keeps the delay of the automatic lock in [AppLock] as the settings have it, for as long as the
     * app lives. [AppLock] is asked for the delay inside a lifecycle callback and cannot wait for the
     * settings store, so it holds a copy. Until the first value comes it has the default.
     */
    private fun followAutoLockDelay() {
        appScope.launch {
            settingsDataStore.settingsFlow
                .map { it.autoLockDelay }
                .distinctUntilChanged()
                .collect { appLock.setAutoLockDelay(it) }
        }
    }

    /**
     * Reads persisted sync settings once and conditionally starts the scheduler.
     * Uses a fire-and-forget coroutine on [appScope] to avoid blocking [onCreate].
     */
    private fun startSyncSchedulerIfEnabled() {
        appScope.launch {
            val settings = settingsDataStore.settingsFlow.first()
            if (settings.syncEnabled) {
                syncScheduler.start(settings.syncInterval.intervalMs)
                Timber.tag("App").d("SyncScheduler started (intervalMs=${settings.syncInterval.intervalMs})")
            } else {
                Timber.tag("App").d("SyncScheduler not started: sync disabled in settings")
            }
        }
    }

    /**
     * Deletes the live tunnels whose host is deleted, left by an earlier sync (see
     * [OrphanTunnelRepair]). Off the main thread, and after `user.home` is set in [onCreate]:
     * the tombstone clock tick reads the device id from it. A failure is logged by class name
     * inside [OrphanTunnelRepair.runSafely], never thrown.
     */
    private fun repairOrphanTunnels() {
        appScope.launch { orphanTunnelRepair.runSafely() }
    }

    private fun setupBouncyCastleProvider() {
        Security.removeProvider("BC")
        Security.insertProviderAt(org.bouncycastle.jce.provider.BouncyCastleProvider(), 1)
        // SSHJ 0.38.0 expects Ed25519 keys to be `net.i2p.crypto.eddsa.EdDSAPublicKey`
        // instances: the JDK/Android native Ed25519 providers return a different class
        // that fails to cast in SSHJ's buffer encoder. Registering the i2p provider
        // (shipped transitively via SSHJ) and using it for Ed25519 gen/parse keeps
        // every EdDSA key object on the type SSHJ knows how to serialise.
        if (Security.getProvider(net.i2p.crypto.eddsa.EdDSASecurityProvider.PROVIDER_NAME) == null) {
            Security.addProvider(net.i2p.crypto.eddsa.EdDSASecurityProvider())
        }
    }
}
