// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh

import android.content.Intent
import android.os.Bundle
import fr.techtical.nextsh.core.ActivityVisibilityTracker
import fr.techtical.nextsh.core.PendingAuthResume
import fr.techtical.nextsh.core.ScreenCapturePolicy
import fr.techtical.nextsh.core.spendAuthResumeToken
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import dagger.hilt.android.AndroidEntryPoint
import fr.techtical.nextsh.core.auth.HardwareKeyAuthenticator
import fr.techtical.nextsh.core.auth.SecurityKeyDiscovery
import fr.techtical.nextsh.core.lock.AppLock
import fr.techtical.nextsh.core.shortcut.PendingShortcut
import fr.techtical.nextsh.core.shortcut.ShortcutTokenStore
import fr.techtical.nextsh.core.shortcut.ShortcutUpdater
import fr.techtical.nextsh.core.shortcut.readShortcutRequest
import fr.techtical.nextsh.core.ssh.BiometricPromptCoordinator
import fr.techtical.nextsh.core.ssh.Fido2PromptCoordinator
import fr.techtical.nextsh.core.ssh.HostKeyPromptCoordinator
import fr.techtical.nextsh.core.vault.AndroidVaultWipe
import fr.techtical.nextsh.core.vault.VaultWipeGate
import fr.techtical.nextsh.core.vault.VaultWipeState
import fr.techtical.nextsh.core.ssh.VaultAuthRequest
import fr.techtical.nextsh.service.AuthRequiredNotifier
import fr.techtical.nextsh.service.TerminalServiceController
import fr.techtical.nextsh.service.resumeTerminalService
import fr.techtical.nextsh.ui.components.BiometricPromptHost
import fr.techtical.nextsh.ui.components.Fido2PromptHost
import fr.techtical.nextsh.ui.components.HostKeyPromptDialog
import fr.techtical.nextsh.ui.lock.AppLockScreen
import fr.techtical.nextsh.ui.navigation.NavGraph
import fr.techtical.nextsh.ui.sessions.TerminalTabStore
import fr.techtical.nextsh.ui.settings.VaultWipeOverlay
import fr.techtical.nextsh.ui.settings.shouldRestartAfterWipe
import fr.techtical.nextsh.ui.theme.TechticalTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var hostKeyPromptCoordinator: HostKeyPromptCoordinator
    @Inject lateinit var fido2PromptCoordinator: Fido2PromptCoordinator
    @Inject lateinit var biometricPromptCoordinator: BiometricPromptCoordinator
    @Inject lateinit var hardwareKeyAuthenticator: HardwareKeyAuthenticator
    @Inject lateinit var securityKeyDiscovery: SecurityKeyDiscovery
    @Inject lateinit var visibilityTracker: ActivityVisibilityTracker
    @Inject lateinit var pendingAuthResume: PendingAuthResume
    @Inject lateinit var authRequiredNotifier: AuthRequiredNotifier
    @Inject lateinit var shortcutTokens: ShortcutTokenStore
    @Inject lateinit var shortcutUpdater: ShortcutUpdater
    @Inject lateinit var terminalTabStore: TerminalTabStore
    @Inject lateinit var terminalService: TerminalServiceController

    // The wipe of the vault belongs to the app: the activity shows its modal and restarts the app after it.
    @Inject lateinit var androidVaultWipe: AndroidVaultWipe
    @Inject lateinit var wipeGate: VaultWipeGate

    /** The app was told to restart after the wipe and this activity is still up: the modal stays. */
    private var wipeRestarting by mutableStateOf(false)

    // Locks the screens of the app after a delay out of the foreground, see AppLock.
    @Inject lateinit var appLock: AppLock

    // The launcher shortcut to act on, already checked against the token of this install. Held by
    // the app and not by the activity, so that it survives a recreation before the unlock.
    @Inject lateinit var pendingShortcut: PendingShortcut

    override fun onCreate(savedInstanceState: Bundle?) {
        // Install the AndroidX cold-start splash before super.onCreate. Dismisses
        // immediately to the normal UI (no keep-on-screen condition / artificial delay).
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ScreenCapturePolicy.protect(window)
        // A new activity (not a recreation) in a process that is alive already, after the app was
        // swept from the recents while a service kept the process: nothing that was unlocked before
        // is answerable at the gate (prompts, shortcuts) until the owner unlocks again. A recreation
        // (rotation, restored state) keeps the lock as it is, and so does a relaunch of this
        // instance (onNewIntent does not come through here).
        if (savedInstanceState == null) appLock.lock()
        // A restored activity (rotation, or the process died and the system hands the original
        // intent back) does not act on the shortcut of its intent a second time.
        takeShortcut(intent, restored = savedInstanceState != null)
        consumeAuthResumeToken(intent)

        // The wipe of the vault is over: the app restarts on the creation of the vault, in a task of its
        // own (CLEAR_TASK). Only with the activity on screen (STARTED): the system refuses to start an
        // activity from the background, so a wipe that ends while the user is away waits for their return
        // (the collection starts again with the state, which is still DONE). The new activity has empty
        // ViewModelStores: what the screens held in memory (a form of host, a pasted key, an explorer)
        // goes with the old one.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                wipeGate.state.collect { if (shouldRestartAfterWipe(it)) restartAfterWipe() }
            }
        }

        // The NFC reader of the security key discovery belongs to the Activity on screen: it is
        // handed this one at resume and takes it back at pause, so that nothing keeps the Activity
        // longer and the reader (which would catch contactless payment cards too) never runs in
        // the background.
        //
        // The same observer tells the app when it is on screen (STARTED, not RESUMED: a dialog
        // activity on top only pauses this one), so that a prompt needing the user is not opened
        // for nobody, see AppVisibility. It also tells the lock when the app leaves and comes back.
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                // First: the lock screen must be up before anything else reacts to the return. Only
                // the first started instance is a return: a second one that starts before the old one
                // stopped (a relaunch) is not.
                if (visibilityTracker.onActivityStarted()) appLock.onAppShown()
                // Coming back to the app: a request of the notification that nobody followed up
                // for ten minutes, or a shortcut left on the lock screen for two, is forgotten, so
                // it does not act, or hide a shortcut, later.
                pendingAuthResume.dropExpired()
                pendingShortcut.dropExpired()
                // The system may have refused the publication of the shortcuts to a start in the
                // background (rate limit); on screen the limit is reset.
                shortcutUpdater.republishIfRefused()
                // A terminal tab that came up while the app was in the background could not start
                // the foreground service (the system refuses it there), and nothing would ask
                // again: on screen the start is allowed.
                resumeTerminalService(terminalTabStore.state.value.tabs, terminalService)
            }

            override fun onStop(owner: LifecycleOwner) {
                // A recreation for a configuration change stops this instance before the new one
                // starts: the tracker must not read that gap as "hidden", and a rotation is no
                // reason to start the clock of the lock. Nor is the stop of an old instance while a
                // new one is up: only the last stopped instance is an absence. And with the vault
                // dialog up, the screen of the phone code stops the activity without the owner leaving.
                val leftTheForeground = visibilityTracker.onActivityStopped(changingConfigurations = isChangingConfigurations)
                if (leftTheForeground && !isChangingConfigurations) {
                    appLock.onAppHidden(ownerPromptOpen = biometricPromptCoordinator.openDialog() is VaultAuthRequest)
                }
            }

            override fun onResume(owner: LifecycleOwner) {
                securityKeyDiscovery.onActivityResumed(this@MainActivity)
            }

            override fun onPause(owner: LifecycleOwner) {
                securityKeyDiscovery.onActivityPaused(this@MainActivity)
            }
        })

        setContent {
            val shortcut by pendingShortcut.request.collectAsState()
            // Tunnels to restart after a tap on the "authentication required" notification: the
            // token was checked in consumeAuthResumeToken, this is the validated result.
            val authResume by pendingAuthResume.ready.collectAsState()
            val locked by appLock.locked.collectAsState()
            val wipeState by wipeGate.state.collectAsState()
            // Whether the lock screen window is to be up: NavGraph decides, it knows the screen that is
            // open, but the window is created here, at the very end (see below).
            var lockScreenNeeded by remember { mutableStateOf(false) }

            TechticalTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    NavGraph(
                        pendingShortcut = shortcut,
                        tunnelResumePending = authResume != null,
                        locked = locked,
                        wipeClosed = wipeState != VaultWipeState.IDLE || wipeRestarting,
                        onLockScreenNeededChanged = { lockScreenNeeded = it },
                        // Compare and clear, once, and false once it expired: a newer shortcut that
                        // came in meanwhile stays.
                        onShortcutConsumed = { consumed -> pendingShortcut.consume(consumed) },
                    )
                    // Overlay global : TOFU host key prompt déclenché depuis n'importe
                    // quel écran (HostList, Tunnels, …) via le callback wiré App-side.
                    HostKeyPromptDialog(coordinator = hostKeyPromptCoordinator)
                    // Idem pour la demande de clé de sécurité FIDO2 (feuille modale), qui doit
                    // apparaître même si la connexion part de la liste d'hôtes, du SFTP ou d'un tunnel.
                    Fido2PromptHost(
                        coordinator = fido2PromptCoordinator,
                        hardwareKeyAuthenticator = hardwareKeyAuthenticator,
                    )
                    // Same place for the fingerprint of a biometric key (system dialog): one prompt
                    // at a time with the FIDO2 sheet (shared turn).
                    BiometricPromptHost(coordinator = biometricPromptCoordinator)
                    // The modal of the wipe of the vault (progress, then failure with retry). Created after
                    // the windows above and BEFORE the lock screen: it covers every screen, and the lock
                    // screen covers it if the app locks during the wipe, which goes on under it.
                    VaultWipeOverlay(wipe = androidVaultWipe, restarting = wipeRestarting)
                    // The lock screen is a dialog window, and dialog windows stack in the order they are
                    // created: this one is created after the windows of the prompts above, and after the
                    // dialogs of the screens that were open when the app locked. Being last in the
                    // composition is not what keeps it on top; what does is that those prompts do not
                    // show while the app is locked (it counts as absent for them).
                    if (lockScreenNeeded) AppLockScreen()
                }
            }
        }
    }

    /**
     * Restarts the app on its first screen once the wipe of the vault is done: the gate goes back to IDLE
     * (one caller wins, see [VaultWipeGate.acknowledgeDone]) and the task is cleared, so that nothing of the
     * erased data stays in an activity or a view model. `onboarding_completed` is false by then, the new
     * activity opens the creation of the vault.
     */
    private fun restartAfterWipe() {
        if (!wipeGate.acknowledgeDone()) return
        // The gate is open again but the old screens must not show in the gap before the new activity.
        wipeRestarting = true
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takeShortcut(intent, restored = false)
        consumeAuthResumeToken(intent)
    }

    /**
     * The "authentication required" notification restarts tunnels with a one-time token, see
     * [spendAuthResumeToken]. Like a launcher shortcut (see [takeShortcut]), a validated resume may
     * run on an app that is already open: the tunnel list starts the tunnels, and it is only
     * reachable past the unlock screen.
     */
    private fun consumeAuthResumeToken(intent: Intent?) {
        spendAuthResumeToken(intent, pendingAuthResume) { authRequiredNotifier.cancel() }
    }

    /**
     * The launcher shortcut of [intent], if it carries the token of this install: it replaces the
     * pending one (an intent without a shortcut, the launcher icon for example, clears it). The
     * extras are taken out of the intent. `NavGraph` acts on it as soon as the vault is unlocked,
     * and only once.
     *
     * A [restored] activity (a recreation, whose intent was already handled) offers nothing, not even
     * null: the request that is pending, if any, is the one of the instance it replaces.
     */
    private fun takeShortcut(intent: Intent?, restored: Boolean) {
        val request = readShortcutRequest(intent, shortcutTokens, restored)
        if (!restored) pendingShortcut.offer(request)
    }
}
