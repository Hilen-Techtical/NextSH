// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.data.di

import fr.techtical.nextsh.desktop.components.CmdKViewModel
import fr.techtical.nextsh.desktop.core.auth.fido2.Fido2Enroller
import fr.techtical.nextsh.desktop.core.auth.fido2.Fido2UiState
import fr.techtical.nextsh.desktop.core.auth.fido2.winwebauthn.Fido2ProviderDispatcher
import fr.techtical.nextsh.desktop.core.browser.KcefInitializer
import fr.techtical.nextsh.desktop.core.clipboard.DesktopClipboardManager
import fr.techtical.nextsh.desktop.ui.browser.DesktopBrowserSessionHolder
import fr.techtical.nextsh.desktop.ui.browser.DesktopTunnelBrowserViewModel
import fr.techtical.nextsh.shared.domain.usecase.StopTunnelUseCase
import fr.techtical.nextsh.desktop.core.network.DesktopNetworkMonitor
import fr.techtical.nextsh.desktop.core.ssh.DesktopKnownHostsStore
import fr.techtical.nextsh.desktop.core.ssh.DesktopKnownHostsVerifier
import fr.techtical.nextsh.desktop.core.ssh.DesktopSftpManager
import fr.techtical.nextsh.desktop.core.ssh.DesktopSshKeyManager
import fr.techtical.nextsh.desktop.core.ssh.DesktopSshSessionManager
import fr.techtical.nextsh.desktop.core.ssh.DesktopTransferManager
import fr.techtical.nextsh.desktop.core.ssh.DesktopTunnelManager
import fr.techtical.nextsh.desktop.core.ssh.VaultSshClientFactory
import fr.techtical.nextsh.desktop.service.DesktopTunnelService
import fr.techtical.nextsh.desktop.service.DesktopTunnelTray
import fr.techtical.nextsh.desktop.core.sync.DesktopSyncRepository
import fr.techtical.nextsh.desktop.core.sync.DesktopSyncScheduler
import fr.techtical.nextsh.desktop.core.vault.DesktopVaultExporter
import fr.techtical.nextsh.desktop.core.vault.DesktopVaultImporter
import fr.techtical.nextsh.desktop.core.vault.DesktopVaultManager
import fr.techtical.nextsh.desktop.core.vault.VaultPinManager
import fr.techtical.nextsh.desktop.sessions.DesktopSessionManager
import fr.techtical.nextsh.desktop.data.db.DatabaseFactory
import fr.techtical.nextsh.desktop.data.preferences.DesktopSettingsStore
import fr.techtical.nextsh.desktop.data.db.repository.DesktopCustomTerminalThemeRepository
import fr.techtical.nextsh.desktop.data.db.repository.DesktopEnrolledDeviceRepository
import fr.techtical.nextsh.desktop.data.db.repository.DesktopHostFolderRepository
import fr.techtical.nextsh.desktop.data.db.repository.DesktopHostRepository
import fr.techtical.nextsh.desktop.data.db.repository.DesktopPendingConflictRepository
import fr.techtical.nextsh.desktop.data.db.repository.DesktopSnippetRepository
import fr.techtical.nextsh.desktop.data.db.repository.DesktopSshKeyRepository
import fr.techtical.nextsh.desktop.data.db.repository.DesktopTunnelRepository
import fr.techtical.nextsh.desktop.navigation.DesktopNavigator
import fr.techtical.nextsh.desktop.scope.DesktopAppScope
import fr.techtical.nextsh.desktop.sync.LanSyncServer
import fr.techtical.nextsh.desktop.sync.LanSyncServerLifecycle
import fr.techtical.nextsh.desktop.sync.TlsCertificateManager
import java.io.File
import fr.techtical.nextsh.shared.core.sync.CredentialMetaStore
import fr.techtical.nextsh.shared.core.sync.CredentialSyncRepository
import fr.techtical.nextsh.shared.core.sync.DeviceIdentity
import fr.techtical.nextsh.shared.core.sync.EnrolledDeviceRepository
import fr.techtical.nextsh.shared.core.sync.EnrolledDeviceSecretStore
import fr.techtical.nextsh.shared.core.sync.FileCredentialMetaStore
import fr.techtical.nextsh.shared.core.sync.JvmCredentialSyncRepository
import fr.techtical.nextsh.shared.core.sync.PendingConflictRepository
import fr.techtical.nextsh.shared.core.sync.SyncRepository
import fr.techtical.nextsh.shared.domain.repository.CustomTerminalThemeRepository
import fr.techtical.nextsh.shared.domain.repository.HostRepository
import fr.techtical.nextsh.shared.domain.repository.SnippetRepository
import fr.techtical.nextsh.shared.domain.repository.SshKeyRepository
import fr.techtical.nextsh.shared.domain.repository.TunnelRepository
import fr.techtical.nextsh.shared.domain.ssh.SshKeyManager
import fr.techtical.nextsh.shared.domain.usecase.GenerateSshKeyUseCase
import fr.techtical.nextsh.shared.core.vault.VaultKeyProvider
import fr.techtical.nextsh.shared.domain.vault.VaultManager
import fr.techtical.nextsh.shared.util.AppScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

object DesktopContainer {
    val appScope: AppScope by lazy { DesktopAppScope() }
    val database by lazy { DatabaseFactory.create() }
    val hostRepository: HostRepository by lazy { DesktopHostRepository(database.db) }
    val hostFolderRepository: DesktopHostFolderRepository by lazy { DesktopHostFolderRepository(database.db) }
    val tunnelRepository: TunnelRepository by lazy { DesktopTunnelRepository(database.db) }
    val sshKeyRepository: SshKeyRepository by lazy { DesktopSshKeyRepository(database.db) }
    val snippetRepository: SnippetRepository by lazy { DesktopSnippetRepository(database.db) }
    val customThemeRepository: CustomTerminalThemeRepository by lazy { DesktopCustomTerminalThemeRepository(database.db) }
    val enrolledDeviceRepository: EnrolledDeviceRepository by lazy { DesktopEnrolledDeviceRepository(database.db) }
    val pendingConflictRepository: PendingConflictRepository by lazy { DesktopPendingConflictRepository(database.db) }
    val syncRepository: SyncRepository by lazy {
        DesktopSyncRepository(hostRepository, tunnelRepository, sshKeyRepository, snippetRepository, customThemeRepository, pendingConflictRepository)
    }
    val navigator by lazy { DesktopNavigator() }

    val vaultPinManager: VaultPinManager by lazy { VaultPinManager() }
    val vaultManager: VaultManager by lazy { DesktopVaultManager(vaultPinManager) }
    val vaultKeyProvider: VaultKeyProvider by lazy { VaultKeyProvider() }
    val enrolledDeviceSecretStore: EnrolledDeviceSecretStore by lazy { EnrolledDeviceSecretStore(vaultManager) }
    val credentialMetaStore: CredentialMetaStore by lazy {
        val home = System.getenv("NEXTSH_HOME")?.takeIf { it.isNotBlank() }
            ?: "${System.getProperty("user.home")}/.nextsh"
        FileCredentialMetaStore(File(home, "credential-meta.json"))
    }
    val credentialSyncRepository: CredentialSyncRepository by lazy {
        JvmCredentialSyncRepository(
            vault = vaultManager,
            metaStore = credentialMetaStore,
            pendingConflictRepository = pendingConflictRepository,
            localDeviceId = { DeviceIdentity.deviceId() },
        )
    }
    val sshKeyManager: SshKeyManager by lazy { DesktopSshKeyManager() }
    val knownHostsStore: DesktopKnownHostsStore by lazy { DesktopKnownHostsStore(database.db) }
    val knownHostsVerifier: DesktopKnownHostsVerifier by lazy { DesktopKnownHostsVerifier(knownHostsStore) }

    // ── FIDO2 ─────────────────────────────────────────────────────────────────
    /** État UI partagé : signale quand un touch YubiKey est attendu (→ dialog Compose). */
    val fido2UiState: Fido2UiState by lazy { Fido2UiState() }

    /**
     * Dispatcher FIDO2 : sélectionne automatiquement [WindowsWebAuthnProvider]
     * (Windows WebAuthn API native, dialog Hello natif) ou [YubiKitFidoManager]
     * (PC/SC + HID, Linux/macOS + Windows fallback).
     *
     * Sur Windows 10 1903+ avec webauthn.dll disponible → [WindowsWebAuthnProvider].
     * Sinon → [YubiKitFidoManager] avec callbacks [fido2UiState] pour le dialog Compose.
     */
    val fido2Dispatcher: Fido2ProviderDispatcher by lazy {
        Fido2ProviderDispatcher(fido2UiState)
    }

    /** Enrôlement FIDO2, délégué au dispatcher (WebAuthn API ou YubiKit). */
    val fido2Enroller: Fido2Enroller by lazy { fido2Dispatcher.enroller }
    // ── /FIDO2 ────────────────────────────────────────────────────────────────

    val sshSessionManager: DesktopSshSessionManager by lazy {
        DesktopSshSessionManager(
            hostKeyVerifier = knownHostsVerifier,
            fido2Signer = fido2Dispatcher.signer,
            connectTimeoutMsProvider = { settingsStore.settings.value.connectionTimeout.coerceIn(5, 30) * 1000 },
        )
    }
    val sftpManager: DesktopSftpManager by lazy { DesktopSftpManager(sshSessionManager) }
    val transferManager: DesktopTransferManager by lazy { DesktopTransferManager(sftpManager, appScope) }
    val sessionManager: DesktopSessionManager by lazy {
        DesktopSessionManager(
            hostRepository = hostRepository,
            vaultManager = vaultManager,
            sshSessionManager = sshSessionManager,
            sftpManager = sftpManager,
            knownHostsVerifier = knownHostsVerifier,
            appScope = appScope,
            sshKeyRepository = sshKeyRepository,
            customThemeRepository = customThemeRepository,
        )
    }
    val settingsStore: DesktopSettingsStore by lazy { DesktopSettingsStore() }

    val clipboardManager: DesktopClipboardManager by lazy {
        DesktopClipboardManager(
            appScope = appScope,
            timeoutSecondsProvider = { settingsStore.settings.value.clipboardClearTimeout },
        )
    }

    val tlsCertificateManager: TlsCertificateManager by lazy {
        TlsCertificateManager(
            dir = File(System.getProperty("user.home"), ".nextsh"),
            masterKeyFn = { vaultKeyProvider.getOrCreateMasterKey() },
        )
    }

    val generateSshKeyUseCase: GenerateSshKeyUseCase by lazy {
        GenerateSshKeyUseCase(sshKeyManager, sshKeyRepository, vaultManager)
    }

    val syncScheduler: DesktopSyncScheduler by lazy { DesktopSyncScheduler(pendingConflictRepository) }

    // ── Wave 4 (vault backup) ────────────────────────────────────────────────
    internal val vaultExporter: DesktopVaultExporter by lazy {
        DesktopVaultExporter(
            vaultManager = vaultManager,
            sshKeyRepository = sshKeyRepository,
            hostRepository = hostRepository,
            tunnelRepository = tunnelRepository,
        )
    }
    internal val vaultImporter: DesktopVaultImporter by lazy {
        DesktopVaultImporter(
            vaultManager = vaultManager,
            sshKeyRepository = sshKeyRepository,
            hostRepository = hostRepository,
            tunnelRepository = tunnelRepository,
        )
    }
    // ── end Wave 4 ───────────────────────────────────────────────────────────

    val lanSyncServer: LanSyncServer by lazy {
        LanSyncServer(
            appScope = appScope,
            tlsCertificateManager = tlsCertificateManager,
            enrolledDeviceRepository = enrolledDeviceRepository,
            secretStore = enrolledDeviceSecretStore,
            syncRepository = syncRepository,
            credentialSyncRepository = credentialSyncRepository,
            deviceIdentity = DeviceIdentity,
            syncScheduler = syncScheduler,
        )
    }

    val lanSyncServerLifecycle: LanSyncServerLifecycle by lazy {
        LanSyncServerLifecycle(
            server = lanSyncServer,
            settingsStore = settingsStore,
            appScope = appScope,
        )
    }

    val enrolledDevicesViewModel: fr.techtical.nextsh.desktop.sync.EnrolledDevicesViewModel by lazy {
        fr.techtical.nextsh.desktop.sync.EnrolledDevicesViewModel(
            enrolledDeviceRepository = enrolledDeviceRepository,
            secretStore = enrolledDeviceSecretStore,
            appScope = appScope,
        )
    }

    val conflictResolutionViewModel: fr.techtical.nextsh.desktop.sync.ConflictResolutionViewModel by lazy {
        fr.techtical.nextsh.desktop.sync.ConflictResolutionViewModel(
            pendingConflictRepository = pendingConflictRepository,
            hostRepository = hostRepository,
            tunnelRepository = tunnelRepository,
            sshKeyRepository = sshKeyRepository,
            snippetRepository = snippetRepository,
            customThemeRepository = customThemeRepository,
            syncScheduler = syncScheduler,
            appScope = appScope,
            credentialSyncRepository = credentialSyncRepository,
            enrolledDeviceRepository = enrolledDeviceRepository,
            secretStore = enrolledDeviceSecretStore,
        )
    }

    // ── In-app browser (Phase 1: KCEF engine) ───────────────────────────────
    val kcefInitializer: KcefInitializer by lazy {
        KcefInitializer(
            bundleDir = java.io.File(System.getProperty("user.home"), ".nextsh/kcef"),
            coroutineScope = appScope.coroutineScope,
        )
    }

    // ── In-app browser (Phase 2: session holder + browser ViewModel) ────────
    val browserSessionHolder: DesktopBrowserSessionHolder by lazy {
        DesktopBrowserSessionHolder()
    }
    val tunnelBrowserViewModel: DesktopTunnelBrowserViewModel by lazy {
        DesktopTunnelBrowserViewModel(
            tunnelRepository = tunnelRepository,
            tunnelManager = tunnelManager,
            stopTunnel = StopTunnelUseCase(tunnelManager),
            sessionHolder = browserSessionHolder,
            appScope = appScope,
        )
    }
    // ── /In-app browser ───────────────────────────────────────────────────────

    // ── Cmd+K command palette (Phase 1.4) ─────────────────────────────────────
    /**
     * Visibilité de la fenêtre d'aide « Autres raccourcis clavier »
     * ([fr.techtical.nextsh.desktop.components.ShortcutsWindow]).
     *
     * La commande qui l'ouvre est exécutée dans [CmdKViewModel], construit ici
     * une fois pour toutes : il ne peut pas piloter directement un `remember`
     * Compose. Un flag partagé (champ simple, aucune init paresseuse : le coût
     * de démarrage à froid documenté plus haut reste intact) fait le pont
     * entre le VM et le point d'hébergement de la fenêtre dans `App.kt`.
     */
    val shortcutsWindowVisible = MutableStateFlow(false)

    val cmdKViewModel: CmdKViewModel by lazy {
        CmdKViewModel(
            hostRepository = hostRepository,
            navigate = { screen -> navigator.navigate(screen) },
            onConnectHost = { hostId ->
                // Opens the SSH session then navigates to Sessions. Navigation
                // is handled by the caller (executeItem) after this lambda returns.
                sessionManager.openSessionById(hostId)
            },
            // Order matters: stop tunnels first (need vault for any reconnect),
            // stop LAN sync server (revokes the running TLS endpoint), THEN
            // wipe the master key. After this, the user lands on VaultUnlock.
            onLockVault = {
                appScope.coroutineScope.launch {
                    tunnelService.stopAllTunnels()
                    lanSyncServerLifecycle.onVaultLocked()
                    vaultPinManager.lock()
                }
            },
            scope = appScope.coroutineScope,
            // Aide raccourcis : purement UI, aucune navigation ni action sur
            // les sessions. App.kt observe ce flag et monte la fenêtre.
            onShowShortcuts = { shortcutsWindowVisible.value = true },
        )
    }
    // ── /Cmd+K ────────────────────────────────────────────────────────────────

    // ── Wave 5 (tunnels runtime) ──────────────────────────────────────────────
    val networkMonitor: DesktopNetworkMonitor by lazy { DesktopNetworkMonitor() }
    val tunnelManager: DesktopTunnelManager by lazy {
        DesktopTunnelManager(
            scope = appScope.coroutineScope,
            clientFactory = VaultSshClientFactory(
                vaultManager = vaultManager,
                hostKeyVerifier = knownHostsVerifier,
                connectTimeoutMsProvider = { settingsStore.settings.value.connectionTimeout.coerceIn(5, 30) * 1000 },
            ),
        )
    }
    val tunnelService: DesktopTunnelService by lazy {
        DesktopTunnelService(
            tunnelManager = tunnelManager,
            networkMonitor = networkMonitor,
            tunnelRepository = tunnelRepository,
            hostRepository = hostRepository,
            appScope = appScope,
            browserSessionHolder = browserSessionHolder,
        )
    }
    val tunnelTray: DesktopTunnelTray by lazy {
        DesktopTunnelTray(appScope = appScope, tunnelService = tunnelService)
    }
    // ── /Wave 5 ───────────────────────────────────────────────────────────────

    fun shutdown() {
        tunnelTray.stop()
        clipboardManager.stop()
        tunnelService.onAppExit()
        sessionManager.closeAllSessions()
        lanSyncServer.stop()
        vaultPinManager.lock()
        (appScope as? DesktopAppScope)?.onDestroy() ?: appScope.onDestroy()
    }

    /**
     * Irreversibly removes EVERY trace of local NextSH state for a complete,
     * clean uninstall. User-initiated only (Settings → danger zone), never
     * automatic. The caller is responsible for an explicit confirmation and
     * for terminating the process afterwards (nothing is left to run against).
     *
     * Cross-platform by design. The OS-stored master key does NOT live under
     * `~/.nextsh`: it is in the Windows Credential Manager / libsecret keyring /
     * macOS Keychain, with a file fallback under `~/.config/nextsh`. Deleting
     * only the data dir would leave that key behind. So we call
     * [VaultKeyProvider.wipe] (which targets the active backend, file fallback
     * included) AND delete the data dir AND the key-fallback dir.
     *
     * Every step is best-effort (`runCatching`): one failing step must never
     * abort the rest, so a partial environment still ends up as clean as
     * possible. Order matters: services are stopped and the SQLite driver is
     * closed FIRST, otherwise Windows keeps an exclusive handle on `nextsh.db`
     * and the recursive delete fails.
     */
    fun purgeAllLocalData() {
        // 1. Stop everything holding file handles or background work.
        runCatching { tunnelTray.stop() }
        runCatching { clipboardManager.stop() }
        runCatching { tunnelService.onAppExit() }
        runCatching { sessionManager.closeAllSessions() }
        runCatching { lanSyncServer.stop() }
        runCatching { vaultPinManager.lock() }
        runCatching { database.driver.close() }

        // 2. Remove the OS-stored master key (keyring entry OR file fallback).
        runCatching { vaultKeyProvider.wipe() }

        // 3. Delete the data dir (~/.nextsh) and the key-fallback dir
        //    (~/.config/nextsh), resolved with the SAME blank-guarded overrides
        //    the readers use (DatabaseFactory / VaultPaths / FileKeyStrategy), so
        //    a relocated install is purged too. Each path is null when it can't
        //    be resolved safely (no override + no user.home) → that delete is
        //    skipped rather than ever targeting File("") (the working dir).
        val home = System.getProperty("user.home")?.takeIf { it.isNotBlank() }
        val dataDirPath = System.getenv("NEXTSH_HOME")?.takeIf { it.isNotBlank() }
            ?: home?.let { "$it/.nextsh" }
        val keyFallbackDirPath = System.getenv("NEXTSH_CONFIG_DIR")?.takeIf { it.isNotBlank() }
            ?: System.getProperty("nextsh.config.dir")?.takeIf { it.isNotBlank() }
            ?: home?.let { "$it/.config/nextsh" }
        dataDirPath?.let { runCatching { File(it).deleteRecursively() } }
        keyFallbackDirPath?.let { runCatching { File(it).deleteRecursively() } }

        // 4. Best-effort scope teardown.
        runCatching { (appScope as? DesktopAppScope)?.onDestroy() ?: appScope.onDestroy() }
    }
}
