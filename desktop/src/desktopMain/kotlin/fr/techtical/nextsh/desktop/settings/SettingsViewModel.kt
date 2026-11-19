// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.settings

import fr.techtical.nextsh.desktop.core.security.WindowSecurityState
import fr.techtical.nextsh.desktop.core.ssh.DesktopKnownHostsStore
import fr.techtical.nextsh.desktop.core.sync.DesktopSyncScheduler
import fr.techtical.nextsh.desktop.core.vault.VaultPinManager
import fr.techtical.nextsh.desktop.data.db.DesktopDatabase
import fr.techtical.nextsh.desktop.data.di.DesktopContainer
import fr.techtical.nextsh.desktop.vault.VaultError
import fr.techtical.nextsh.desktop.data.di.VaultWipeRunner
import fr.techtical.nextsh.desktop.data.di.VaultWipeState
import fr.techtical.nextsh.desktop.data.preferences.DesktopSettingsStore
import fr.techtical.nextsh.desktop.data.preferences.LanguagePref
import fr.techtical.nextsh.desktop.scope.ScreenViewModel
import fr.techtical.nextsh.desktop.service.DesktopTunnelTray
import fr.techtical.nextsh.desktop.sync.LanSyncServer
import fr.techtical.nextsh.desktop.sync.LanSyncServerLifecycle
import fr.techtical.nextsh.shared.core.sync.SyncState
import fr.techtical.nextsh.shared.domain.model.CustomTerminalTheme
import fr.techtical.nextsh.shared.domain.repository.CustomTerminalThemeRepository
import fr.techtical.nextsh.shared.domain.vault.VaultManager
import fr.techtical.nextsh.shared.util.AppScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel de l'écran Réglages. Les observateurs (langue, thèmes personnalisés) vivent dans le scope de
 * l'écran ; l'effacement du coffre, la génération de la phrase de récupération et l'enregistrement d'un
 * thème restent dans le scope de l'app : ils vont au bout même si l'écran est quitté juste après.
 */
class SettingsViewModel internal constructor(
    val store: DesktopSettingsStore = DesktopContainer.settingsStore,
    private val vaultManager: VaultManager = DesktopContainer.vaultManager,
    private val database: DesktopDatabase = DesktopContainer.database,
    private val knownHostsStore: DesktopKnownHostsStore = DesktopContainer.knownHostsStore,
    appScope: AppScope = DesktopContainer.appScope,
    private val syncLifecycle: LanSyncServerLifecycle = DesktopContainer.lanSyncServerLifecycle,
    private val syncServer: LanSyncServer = DesktopContainer.lanSyncServer,
    private val syncScheduler: DesktopSyncScheduler = DesktopContainer.syncScheduler,
    private val customThemeRepository: CustomTerminalThemeRepository = DesktopContainer.customThemeRepository,
    private val pinManager: VaultPinManager = DesktopContainer.vaultPinManager,
    private val tunnelTray: DesktopTunnelTray = DesktopContainer.tunnelTray,
    /** The vault wipe state, shared by the whole app (see [DesktopContainer.vaultWipeState]). */
    private val wipeStateSource: StateFlow<VaultWipeState> = DesktopContainer.vaultWipeState,
    /** The wipe runner. Lazy: nothing of the container is built until a wipe is asked for. */
    private val wipeRunner: () -> VaultWipeRunner = { DesktopContainer.vaultWipeRunner },
) : ScreenViewModel(appScope) {
    val settings: StateFlow<DesktopSettingsStore.Settings> = store.settings
    val syncState: StateFlow<LanSyncServer.State> = syncServer.state
    val languagePreference: StateFlow<LanguagePref> = store.settings
        .map { it.languagePreference }
        .stateIn(screenScope, SharingStarted.Eagerly, LanguagePref.SYSTEM)

    /** Scheduler state, exposes [lastSyncAt] to the settings UI. */
    val schedulerState: StateFlow<SyncState> = syncScheduler.syncState

    /**
     * User-defined terminal themes, observed for the global "Terminal themes"
     * manager surfaced from this screen. Mirrors the Host detail VM's flow so the
     * manager and the per-host selector share one source of truth.
     */
    val customThemes: StateFlow<List<CustomTerminalTheme>> =
        customThemeRepository.observeAll()
            .stateIn(screenScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * State of the recovery-phrase management row.
     *
     * @param exists whether the vault already carries a recovery wrap.
     * @param words the freshly generated 12 words, set only while the one-time
     *   display dialog is open. Cleared (back to null) once the user dismisses
     *   it so the plaintext mnemonic is not retained beyond the screen.
     * @param error a user-facing message if generation failed.
     * @param isBusy whether a generate/regenerate call is in flight.
     */
    data class RecoveryState(
        val exists: Boolean = false,
        val words: List<String>? = null,
        val error: VaultError? = null,
        val isBusy: Boolean = false,
    )

    private val _recoveryState = MutableStateFlow(RecoveryState(exists = pinManager.hasRecoveryPhrase()))
    val recoveryState: StateFlow<RecoveryState> = _recoveryState.asStateFlow()

    /**
     * Generates (or regenerates) the recovery phrase for the currently unlocked
     * vault and surfaces the 12 words for one-time display. Regeneration
     * invalidates any previously exported phrase: the caller is responsible
     * for warning the user first. The vault is unlocked while in Settings, so
     * [VaultPinManager.setupRecoveryPhrase] succeeds.
     */
    fun generateRecoveryPhrase() {
        _recoveryState.value = _recoveryState.value.copy(isBusy = true, error = null)
        appScope.coroutineScope.launch {
            val result = withContext(Dispatchers.IO) { pinManager.setupRecoveryPhrase() }
            _recoveryState.value = if (result.isSuccess) {
                RecoveryState(exists = true, words = result.getOrNull(), isBusy = false)
            } else {
                _recoveryState.value.copy(
                    isBusy = false,
                    error = VaultError.RECOVERY_GENERATION_FAILED,
                )
            }
        }
    }

    /** Dismisses the notice of a failed generation. */
    fun dismissRecoveryError() {
        _recoveryState.value = _recoveryState.value.copy(error = null)
    }

    /** Dismisses the one-time word display, dropping the plaintext words. */
    fun dismissRecoveryWords() {
        _recoveryState.value = _recoveryState.value.copy(words = null, error = null)
    }

    fun setConnectionTimeout(seconds: Int) = store.updateConnectionTimeout(seconds)
    fun setTerminalFontSize(px: Int) = store.updateTerminalFontSize(px)
    fun setClipboardClearTimeout(seconds: Int) = store.updateClipboardClearTimeout(seconds)
    fun setSyncEnabled(enabled: Boolean) = syncLifecycle.applySettingsChange(enabled)

    /**
     * Re-checks whether the LAN sync server should be running and restarts it if it
     * is down (B3 self-heal). Idempotent and safe to call repeatedly: it no-ops when
     * sync is disabled / vault is locked, or when the server is already running. Used
     * by the Settings screen on open and by the "Restart server" recovery button so a
     * server that died on a transient boot error recovers without a manual off→on.
     */
    fun ensureSyncRunning() = syncLifecycle.ensureRunning()
    fun setLanguagePreference(pref: LanguagePref) = store.updateLanguagePreference(pref)

    /**
     * Persists the hide-from-screen-capture setting and immediately applies
     * the change to the main window via [WindowSecurityState].
     * No-op at the OS level on non-Windows platforms.
     */
    fun setHideFromScreenCapture(enabled: Boolean) {
        store.updateHideFromScreenCapture(enabled)
        WindowSecurityState.apply(enabled)
    }

    fun setMinimizeToTrayOnClose(enabled: Boolean) = store.updateMinimizeToTrayOnClose(enabled)

    /**
     * Whether the tray icon is actually registered with the OS right now:
     * read directly (not a `Flow`) since it only ever flips once, shortly
     * after startup (`Main.kt`'s post-show `LaunchedEffect`), well before the
     * user can reach this screen. Drives the enabled/disabled state of the
     * "minimize to tray on close" row: the setting is meaningless (and would
     * strand the user with no way to reopen the window) if the tray icon
     * could not be created: see [DesktopTunnelTray.isRegistered].
     */
    val trayAvailable: Boolean get() = tunnelTray.isRegistered

    /**
     * Create or update a custom terminal theme from the global Settings manager,
     * no live SSH session or host required. Persists via the repo (which CRDT-syncs
     * it), then triggers live-apply on the session manager so any OPEN session
     * currently rendering this theme re-paints instantly with the edit. The reactive
     * [customThemes] flow updates the manager list on its own. Mirrors
     * `HostDetailViewModel.saveCustomTheme`.
     */
    fun saveCustomTheme(theme: CustomTerminalTheme) {
        appScope.coroutineScope.launch {
            customThemeRepository.save(theme)
            // Hand the session manager the up-to-date list (merge the saved theme
            // into the current snapshot) so live-apply doesn't race the async
            // observeAll() emission feeding its internal snapshot.
            val merged = customThemes.value.filterNot { it.id == theme.id } + theme
            DesktopContainer.sessionManager.refreshCustomThemes(merged, theme.id)
        }
    }

    /** Delete a custom theme from the global Settings manager. */
    fun deleteCustomTheme(id: String) {
        appScope.coroutineScope.launch {
            customThemeRepository.delete(id)
        }
    }

    /**
     * Progress of the vault wipe: held by the container, so the progress modal, the global shortcuts and this
     * view model all see the same state, whichever screen is shown.
     */
    val wipeState: StateFlow<VaultWipeState> = wipeStateSource


    /**
     * Wipes the vault and returns the app to a true first-launch state, staying in the app: see
     * [VaultWipeRunner]. Closes everything that is open first (sessions, tunnels, transfers, LAN sync, see
     * [stopVaultDependentWork]), then erases the vault, every table, the known hosts, the sync switch and
     * the credential metadata (see [eraseVaultData]). A second request while one runs is ignored.
     *
     * [onDone] (navigation to the creation of the new vault) runs ONLY at the end, once everything is erased:
     * navigating earlier would let the user create a vault that the erase then deletes, and navigating with
     * data left behind would hide an incomplete wipe. A step that fails is replayed once; if it still fails
     * the state is FAILED (the failure dialog offers "Retry") and [onDone] is not called.
     */
    fun wipeVault(onDone: () -> Unit) = wipeRunner().start(onDone)

    /**
     * Complete, irreversible purge of ALL local data for a clean uninstall,
     * distinct from [wipeVault] (which resets to first-launch). Removes the
     * OS-stored master key (keyring/Credential Manager/file fallback) AND the
     * whole `~/.nextsh` and `~/.config/nextsh` dirs. See
     * [DesktopContainer.purgeAllLocalData].
     *
     * Runs on a dedicated non-daemon thread: the purge tears down `appScope`
     * itself, so it must NOT run inside an `appScope` coroutine (that would be
     * cancelled mid-flight). [onDone] (the caller terminates the process) runs
     * once the purge completes.
     */
    fun purgeAllLocalData(onDone: () -> Unit) {
        Thread {
            DesktopContainer.purgeAllLocalData()
            onDone()
        }.apply { name = "nextsh-purge"; isDaemon = false }.start()
    }
}
