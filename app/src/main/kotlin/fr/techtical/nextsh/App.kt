// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import fr.techtical.nextsh.core.shortcut.ShortcutUpdater
import fr.techtical.nextsh.core.ssh.HostKeyPromptCoordinator
import fr.techtical.nextsh.core.ssh.SshSessionManager
import fr.techtical.nextsh.data.preferences.SettingsDataStore
import fr.techtical.nextsh.shared.core.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
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
            Timber.plant(Timber.DebugTree())
        } else {
            Timber.plant(ReleaseTree())
        }

        shortcutUpdater.startObserving()
        startSyncSchedulerIfEnabled()

        // Install host-key TOFU callback at App-level: it must be live
        // regardless of which screen the user is on. SessionViewModel used
        // to wire it in init, but on Android there's no nav entry to
        // Sessions before connecting, so a fresh install would silently
        // fail the first connection ("Could not verify host key…").
        sshSessionManager.hostKeyVerificationCallback = { unknown ->
            hostKeyPromptCoordinator.promptUnknown(unknown)
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
                Timber.d("App", "SyncScheduler started (intervalMs=${settings.syncInterval.intervalMs})")
            } else {
                Timber.d("App", "SyncScheduler not started: sync disabled in settings")
            }
        }
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

private class ReleaseTree : Timber.Tree() {

    private val sensitivePatterns = Regex(
        "password|pwd|key_|credential|passphrase|secret|token|fido2|ctap|assertion|authenticator|signature|signing",
        RegexOption.IGNORE_CASE,
    )

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (priority < Log.INFO) return

        val safeMessage = if (sensitivePatterns.containsMatchIn(message)) {
            "[REDACTED, sensitive data]"
        } else {
            message
        }
        Log.println(priority, tag ?: "NextSH", safeMessage)
    }
}
