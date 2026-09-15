// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.FrameWindowScope
import fr.techtical.nextsh.desktop.components.CmdKOverlay
import fr.techtical.nextsh.desktop.components.DesktopSidebar
import fr.techtical.nextsh.desktop.components.OpenLinkConfirmDialog
import fr.techtical.nextsh.desktop.core.auth.fido2.YubiKeyTouchDialog
import fr.techtical.nextsh.desktop.components.ShortcutsWindow
import fr.techtical.nextsh.desktop.components.SidebarTab
import fr.techtical.nextsh.desktop.components.StatusBar
import fr.techtical.nextsh.desktop.core.diagnostics.StartupTrace
import fr.techtical.nextsh.desktop.data.db.repository.HostFolderWithHosts
import fr.techtical.nextsh.desktop.data.di.DesktopContainer
import fr.techtical.nextsh.desktop.sessions.SessionTab
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.TunnelStatus
import fr.techtical.nextsh.desktop.hosts.HostDetailScreen
import fr.techtical.nextsh.desktop.hosts.HostListScreen
import fr.techtical.nextsh.desktop.navigation.Screen
import fr.techtical.nextsh.desktop.sessions.HostKeyMismatchAlert
import fr.techtical.nextsh.desktop.sessions.SessionTabsScreen
import fr.techtical.nextsh.desktop.sessions.TabContent
import fr.techtical.nextsh.desktop.sessions.TerminalTabStatus
import fr.techtical.nextsh.desktop.sessions.UnknownHostKeyDialog
import fr.techtical.nextsh.desktop.settings.KnownHostsScreen
import fr.techtical.nextsh.desktop.transfers.TransfersScreen
import fr.techtical.nextsh.desktop.settings.SettingsScreen
import fr.techtical.nextsh.desktop.sync.ConflictResolutionScreen
import fr.techtical.nextsh.desktop.sync.EnrolledDevicesScreen
import fr.techtical.nextsh.desktop.sync.EnrollmentScreen
import fr.techtical.nextsh.desktop.theme.NearBlack
import fr.techtical.nextsh.desktop.theme.TechticalTheme
import fr.techtical.nextsh.desktop.tunnels.TunnelConfigScreen
import fr.techtical.nextsh.desktop.tunnels.TunnelListScreen
import fr.techtical.nextsh.desktop.ui.browser.DesktopBrowserOverlay
import fr.techtical.nextsh.desktop.onboarding.FirstLaunchScreen
import fr.techtical.nextsh.desktop.snippets.SnippetListScreen
import fr.techtical.nextsh.desktop.vault.VaultScreen
import fr.techtical.nextsh.desktop.vault.VaultUnlockScreen

@Composable
fun FrameWindowScope.App(
    cmdKOpen: Boolean,
    onCmdKOpen: () -> Unit,
    onCmdKDismiss: () -> Unit,
    onOpenSnippetPicker: () -> Unit,
) {
    TechticalTheme {
        // **Cold-start optimization** : only the navigator is touched at
        // the App() top level. Every other [DesktopContainer] field
        // (syncScheduler, transferManager, sessionManager, fido2UiState,
        // cmdKViewModel) is wrapped inside `!vaultLocked` so the chain
        // of lazy-init (database open, Ktor server prep, FIDO2 native
        // bindings, …) does NOT pay its cost while the user is still on
        // VaultUnlock / FirstLaunch screens. ~500-2000 ms of perceived
        // cold-launch slowness comes from that DI fan-out.
        val navigator = DesktopContainer.navigator
        val screen by navigator.current.collectAsState()
        val vaultLocked = screen is Screen.VaultUnlock || screen is Screen.FirstLaunch

        // État du dialog de confirmation d'ouverture du dépôt GitHub depuis
        // le clic sur l'indicateur de version dans la StatusBar. Hoisté ici
        // pour que le dialog flotte au-dessus de toute la chrome (titre,
        // sidebar, content) sans dépendance à la branche `screen` courante.
        var showRepoLinkDialog by remember { mutableStateOf(false) }

        if (cmdKOpen && !vaultLocked) {
            // DI access deferred to this branch: the cmdKViewModel is
            // only needed once the user opens the palette, never during
            // the cold-start vault-locked screens.
            CmdKOverlay(
                viewModel = DesktopContainer.cmdKViewModel,
                parentWindow = window,
                onDismiss = onCmdKDismiss,
            )
        }

        // Fenêtre d'aide « Autres raccourcis clavier », ouverte par la commande
        // du même nom dans la palette. Hébergée ici, au même niveau que
        // [CmdKOverlay], parce que c'est la palette qui la déclenche et que le
        // gate `!vaultLocked` s'applique naturellement (vault verrouillé =
        // aucun raccourci actif, cf. le dispatcher AWT de Main.kt). Le flag est
        // porté par le container : le [CmdKViewModel] y est construit une fois
        // pour toutes et ne peut pas écrire dans un `remember` Compose. Lire ce
        // `MutableStateFlow` ne déclenche aucune init paresseuse : l'invariant
        // de démarrage à froid documenté plus haut est préservé.
        if (!vaultLocked) {
            val shortcutsWindowVisible by DesktopContainer.shortcutsWindowVisible.collectAsState()
            if (shortcutsWindowVisible) {
                ShortcutsWindow(
                    onDismiss = { DesktopContainer.shortcutsWindowVisible.value = false },
                )
            }
            // Focus restore: same pattern as the snippet/host pickers in
            // Main.kt (MR !62): when the shortcuts window closes, hand
            // keyboard focus back to the active terminal. Otherwise Compose
            // restores focus to whatever Compose node had it before the
            // window opened (e.g. the sidebar's Cmd+K bar), and the next
            // keystroke is swallowed there instead of reaching the terminal.
            // `wasOpen` guards a cold start (window never opened) from
            // firing a spurious focus request.
            var shortcutsWindowWasOpen by remember { mutableStateOf(false) }
            LaunchedEffect(shortcutsWindowVisible) {
                if (shortcutsWindowVisible) {
                    shortcutsWindowWasOpen = true
                } else if (shortcutsWindowWasOpen) {
                    shortcutsWindowWasOpen = false
                    DesktopContainer.sessionManager.requestTerminalFocus()
                }
            }
        }
        // Verrouillage du vault pendant que la fenêtre est ouverte : le flag est
        // porté par le container (il survit à la disparition de la branche
        // ci-dessus), sans ce reset la fenêtre réapparaîtrait toute seule au
        // prochain déverrouillage.
        LaunchedEffect(vaultLocked) {
            if (vaultLocked) DesktopContainer.shortcutsWindowVisible.value = false
        }

        if (showRepoLinkDialog) {
            OpenLinkConfirmDialog(
                url = RepositoryUrls.GITHUB,
                what = "le dépôt GitHub public de NextSH",
                onConfirm = {
                    openExternalLink(RepositoryUrls.GITHUB)
                    showRepoLinkDialog = false
                },
                onDismiss = { showRepoLinkDialog = false },
            )
        }

        // OS-decorated window: the system frame paints the title bar
        // (NearBlack via DwmSetWindowAttribute, see [WindowsDarkTitleBar])
        // plus the four resize borders. No Compose-rendered chrome strip
        // lives at the window root anymore; the Cmd+K omnibox has moved
        // into the sidebar (32 dp band at the top of [DesktopSidebar],
        // only 240 dp wide: the entire horizontal band above the main
        // content area is freed up for actual content like terminal panes
        // and host lists).
        Box(modifier = Modifier.fillMaxSize().background(NearBlack)) {
            // Host-key + FIDO2 dialogs float above every UNLOCKED screen.
            // Wrapped in `!vaultLocked` so the sessionManager + fido2UiState
            // DI chain (which transitively opens the database) isn't
            // touched during cold launch while the user is still typing
            // their PIN on VaultUnlock / FirstLaunch. The dialogs would
            // never fire during locked state anyway (no SSH ops possible).
            if (!vaultLocked) {
                val hostKeyPrompt by DesktopContainer.sessionManager.hostKeyPrompt.collectAsState()
                val hostKeyMismatch by DesktopContainer.sessionManager.hostKeyMismatch.collectAsState()
                val fido2TouchRequest by DesktopContainer.fido2UiState.touchInProgress.collectAsState()

                hostKeyPrompt?.let { prompt ->
                    UnknownHostKeyDialog(
                        prompt = prompt,
                        onAccept = { DesktopContainer.sessionManager.acceptHostKey() },
                        onReject = { DesktopContainer.sessionManager.rejectHostKey() },
                    )
                }
                hostKeyMismatch?.let { mismatch ->
                    HostKeyMismatchAlert(
                        mismatch = mismatch,
                        onDismiss = { DesktopContainer.sessionManager.dismissMismatchAlert() },
                    )
                }
                YubiKeyTouchDialog(
                    visible = fido2TouchRequest != null,
                    deviceLabel = fido2TouchRequest?.deviceLabel,
                    onCancel = { fido2TouchRequest?.onCancel?.invoke() },
                )
            }

            when (val s = screen) {
                is Screen.FirstLaunch -> FirstLaunchScreen(
                    onCompleted = { navigator.navigate(Screen.HostList) },
                    onConfigureSync = { navigator.navigate(Screen.Enrollment(fromFirstLaunch = true)) },
                )
                is Screen.VaultUnlock -> VaultUnlockScreen(
                    onUnlocked = {
                        DesktopContainer.lanSyncServerLifecycle.onVaultUnlocked()
                        // Idempotent: re-binds the sync server when the LAN IP changes (DHCP).
                        DesktopContainer.lanSyncServerLifecycle.startNetworkWatcher()
                        DesktopContainer.tunnelService.onVaultUnlocked()
                        navigator.navigate(Screen.HostList)
                    },
                )
                else -> {
                    LaunchedEffect(Unit) {
                        StartupTrace.mark("MainLayout post-unlock first composition")
                    }
                    // All these DI accesses are deferred to the post-unlock
                    // MainLayout composition: they're never evaluated while
                    // the user is on VaultUnlock / FirstLaunch screens.
                    val activeSessions by DesktopContainer.sessionManager.tabs.collectAsState()
                    val foldersWithHosts by DesktopContainer.hostFolderRepository
                        .observeAllWithHosts()
                        .collectAsState(initial = emptyList<HostFolderWithHosts>())
                    var hostListReadyLogged by remember { mutableStateOf(false) }
                    LaunchedEffect(foldersWithHosts) {
                        if (!hostListReadyLogged && foldersWithHosts.isNotEmpty()) {
                            StartupTrace.mark("hostList ready (${foldersWithHosts.size} folders)")
                            hostListReadyLogged = true
                        }
                    }
                    val tunnelStates by DesktopContainer.tunnelManager.tunnelStates.collectAsState()
                    val tunnelsActive = tunnelStates.values.count { it.status == TunnelStatus.ACTIVE }
                    // tunnelsTotal must reflect all *configured* tunnels (persisted),
                    // not just the runtime states map (which is empty until something
                    // is started). Source of truth = tunnelRepository.
                    val configuredTunnels by DesktopContainer.tunnelRepository
                        .observeAll()
                        .collectAsState(initial = emptyList())
                    val tunnelsTotal = configuredTunnels.size
                    val settings by DesktopContainer.settingsStore.settings.collectAsState()
                    val syncEnabled = settings.syncEnabled
                    val enrolledDevices by DesktopContainer.enrolledDeviceRepository
                        .observeAll()
                        .collectAsState(initial = emptyList())
                    val syncState by DesktopContainer.syncScheduler.syncState.collectAsState()
                    val pendingConflicts = syncState.pendingConflicts
                    val activeTransfers by DesktopContainer.transferManager.activeCount.collectAsState()
                    MainLayout(
                        current = s,
                        pendingConflicts = pendingConflicts,
                        activeTransfers = activeTransfers,
                        activeSessions = activeSessions,
                        foldersWithHosts = foldersWithHosts,
                        tunnelsActive = tunnelsActive,
                        tunnelsTotal = tunnelsTotal,
                        syncEnabled = syncEnabled,
                        syncStatus = syncState.status,
                        lastSyncAt = syncState.lastSyncAt,
                        enrolledDevicesCount = enrolledDevices.size,
                        onSelectTab = { tab -> navigator.navigate(screenForTab(tab)) },
                        onSelectSession = { sessionId ->
                            DesktopContainer.sessionManager.selectTab(sessionId)
                            navigator.navigate(Screen.Sessions)
                        },
                        onCloseSession = { sessionId ->
                            DesktopContainer.sessionManager.closeSession(sessionId)
                        },
                        onConnectHost = { host: Host ->
                            DesktopContainer.sessionManager.openSessionById(host.id)
                            navigator.navigate(Screen.Sessions)
                        },
                        onNewHost = { navigator.navigate(Screen.HostDetail(hostId = null)) },
                        onVersionClick = { showRepoLinkDialog = true },
                        onCmdKTrigger = onCmdKOpen,
                    ) {
                    when (s) {
                        // HostList et HostDetail partagent la même branche pour que
                        // HostListScreen reste mounté à travers la transition (sinon
                        // chaque switch crée un remount → blink des LaunchedEffect
                        // de chargement initial). HostDetail s'overlay au-dessus
                        // dans le même Box ; le scrim de l'overlay absorbe les clics
                        // donc les callbacks de HostList restent actifs sans risque.
                        is Screen.HostList, is Screen.HostDetail -> Box(modifier = Modifier.fillMaxSize()) {
                            HostListScreen(
                                onAddHost = { navigator.navigate(Screen.HostDetail(hostId = null)) },
                                onEditHost = { id -> navigator.navigate(Screen.HostDetail(hostId = id)) },
                                onConnectHost = { id ->
                                    DesktopContainer.sessionManager.openSessionById(id)
                                    navigator.navigate(Screen.Sessions)
                                },
                                onConnectSftp = { id ->
                                    DesktopContainer.sessionManager.openSessionById(id, thenOpenSftp = true)
                                    navigator.navigate(Screen.Sessions)
                                },
                                onOpenSnippets = { navigator.navigate(Screen.SnippetList) },
                            )
                            if (s is Screen.HostDetail) {
                                HostDetailScreen(
                                    hostId = s.hostId,
                                    onBack = { navigator.navigate(Screen.HostList) },
                                )
                            }
                        }
                        is Screen.Vault -> VaultScreen(
                            onBack = { navigator.navigate(Screen.HostList) },
                        )
                        // TunnelList et TunnelConfig partagent la même branche (même
                        // pattern que HostList/HostDetail) : la liste reste mountée
                        // pendant que l'overlay TunnelConfig est ouvert au-dessus :
                        // évite le blink des LaunchedEffect au transition.
                        is Screen.TunnelList, is Screen.TunnelConfig -> Box(modifier = Modifier.fillMaxSize()) {
                            TunnelListScreen(
                                onBack = { navigator.navigate(Screen.HostList) },
                                onAddTunnel = { navigator.navigate(Screen.TunnelConfig(tunnelId = null)) },
                                onEditTunnel = { id -> navigator.navigate(Screen.TunnelConfig(tunnelId = id)) },
                            )
                            if (s is Screen.TunnelConfig) {
                                TunnelConfigScreen(
                                    tunnelId = s.tunnelId,
                                    onBack = { navigator.navigate(Screen.TunnelList) },
                                )
                            }
                        }
                        is Screen.Settings -> SettingsScreen(
                            onBack = { navigator.navigate(Screen.HostList) },
                            onOpenKnownHosts = { navigator.navigate(Screen.KnownHosts) },
                            onOpenEnrollment = { navigator.navigate(Screen.Enrollment()) },
                            onVaultWiped = { navigator.navigate(Screen.FirstLaunch) },
                            onPurged = { kotlin.system.exitProcess(0) },
                            onOpenConflictResolution = { navigator.navigate(Screen.ConflictResolution) },
                            onOpenEnrolledDevices = { navigator.navigate(Screen.EnrolledDevices) },
                        )
                        is Screen.EnrolledDevices -> EnrolledDevicesScreen(
                            onBack = { navigator.navigate(Screen.Settings) },
                        )
                        is Screen.KnownHosts -> KnownHostsScreen(
                            onBack = { navigator.navigate(Screen.Settings) },
                        )
                        is Screen.Enrollment -> EnrollmentScreen(
                            // Back routes to HostList when arriving from FirstLaunch
                            // (vault was just created, Settings doesn't exist as a
                            // back target yet); routes to Settings otherwise.
                            onBack = {
                                navigator.navigate(
                                    if (s.fromFirstLaunch) Screen.HostList else Screen.Settings
                                )
                            },
                        )
                        is Screen.Sessions -> SessionTabsScreen(onOpenSnippetPicker = onOpenSnippetPicker)
                        is Screen.Transfers -> TransfersScreen()
                        is Screen.ConflictResolution -> ConflictResolutionScreen(
                            onBack = { navigator.navigate(Screen.Settings) },
                        )
                        is Screen.SnippetList -> SnippetListScreen(
                            onBack = { navigator.navigate(Screen.HostList) },
                        )
                        Screen.FirstLaunch, Screen.VaultUnlock -> Unit // handled above
                    }
                    }
                }
            }

                // In-app browser overlay: floats above all UNLOCKED screens.
                // Visibility is driven internally by sessionHolder.activeTunnelId;
                // renders as a zero-size placeholder while no session is active.
                // Wrapped in `!vaultLocked` so the browserSessionHolder /
                // tunnelBrowserViewModel / kcefInitializer DI chain is deferred
                // until after vault unlock (none of these can fire useful work
                // before unlock anyway, there are no tunnel sessions yet).
                if (!vaultLocked) {
                    DesktopBrowserOverlay(
                        sessionHolder = DesktopContainer.browserSessionHolder,
                        viewModel = DesktopContainer.tunnelBrowserViewModel,
                        kcefState = DesktopContainer.kcefInitializer.state,
                        onActivateKcef = { DesktopContainer.kcefInitializer.initialize() },
                    )
                }
        } // end root Box
    }
}

@Composable
private fun MainLayout(
    current: Screen,
    pendingConflicts: Int = 0,
    activeTransfers: Int = 0,
    activeSessions: List<SessionTab>,
    foldersWithHosts: List<HostFolderWithHosts>,
    tunnelsActive: Int = 0,
    tunnelsTotal: Int = 0,
    syncEnabled: Boolean = false,
    syncStatus: fr.techtical.nextsh.shared.core.sync.SyncStatus = fr.techtical.nextsh.shared.core.sync.SyncStatus.IDLE,
    lastSyncAt: Long? = null,
    enrolledDevicesCount: Int = 0,
    onSelectTab: (SidebarTab) -> Unit,
    onSelectSession: (String) -> Unit,
    onCloseSession: (String) -> Unit,
    onConnectHost: (Host) -> Unit,
    onNewHost: () -> Unit = {},
    onVersionClick: () -> Unit = {},
    onCmdKTrigger: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(NearBlack)) {
        Row(modifier = Modifier.fillMaxSize().weight(1f)) {
            DesktopSidebar(
                activeTab = activeTabFor(current),
                activeSessions = activeSessions,
                foldersWithHosts = foldersWithHosts,
                pendingConflicts = pendingConflicts,
                activeTransfers = activeTransfers,
                onSelectTab = onSelectTab,
                onSelectSession = onSelectSession,
                onCloseSession = onCloseSession,
                onConnectHost = onConnectHost,
                onCmdKTrigger = onCmdKTrigger,
                onNewHost = onNewHost,
                latencyForSession = { tab -> resolveLatencyFlow(tab) },
            )
            Box(modifier = Modifier.fillMaxSize()) { content() }
        }
        StatusBar(
            sessionsActive = activeSessions.size,
            tunnelsActive = tunnelsActive,
            tunnelsTotal = tunnelsTotal,
            vaultUnlocked = current !is Screen.VaultUnlock && current !is Screen.FirstLaunch,
            syncEnabled = syncEnabled,
            enrolledDevicesCount = enrolledDevicesCount,
            syncStatus = syncStatus,
            lastSyncAt = lastSyncAt,
            onVersionClick = onVersionClick,
        )
    }
}

/**
 * Resolves the live RTT [StateFlow] for the SSH session backing [tab].
 * Walks Splits via `focusedSlot` to find the active leaf pane:
 *  - Terminal Connected → its [DesktopSshTerminalSession.latencyMs]
 *  - SFTP → the linked terminal session's [DesktopSshTerminalSession.latencyMs]
 *  - Anything else (Connecting, Error, etc.) → null (no flow)
 *
 * The sidebar [SessionPill] collects this flow so the latency stays live.
 */
private fun resolveLatencyFlow(
    tab: SessionTab,
): kotlinx.coroutines.flow.StateFlow<Long?>? {
    var node: TabContent = tab.content
    while (node is TabContent.Split) {
        node = node.pane(node.focusedSlot)
    }
    return when (val n = node) {
        is TabContent.Terminal -> (n.status as? TerminalTabStatus.Connected)?.terminal?.latencyMs
        is TabContent.Sftp -> DesktopContainer.sessionManager.terminalSessionFor(n.linkedSshSessionId)?.latencyMs
        is TabContent.Split -> null // unreachable: loop above unwrapped Splits
    }
}

private fun activeTabFor(screen: Screen): SidebarTab = when (screen) {
    is Screen.HostList, is Screen.HostDetail, is Screen.SnippetList -> SidebarTab.HOSTS
    is Screen.Sessions -> SidebarTab.SESSIONS
    is Screen.TunnelList, is Screen.TunnelConfig -> SidebarTab.TUNNELS
    is Screen.Vault -> SidebarTab.VAULT
    is Screen.Transfers -> SidebarTab.TRANSFERS
    is Screen.Settings, is Screen.KnownHosts, is Screen.Enrollment,
    is Screen.ConflictResolution, is Screen.EnrolledDevices -> SidebarTab.SETTINGS
    is Screen.FirstLaunch, is Screen.VaultUnlock -> SidebarTab.HOSTS
}

/**
 * Maps a [SidebarTab] to the [Screen] it should navigate to. `internal`
 * (not `private`) so Main.kt's global AWT KeyEventDispatcher can reuse the
 * exact same mapping for the Ctrl+Shift+1..5 / Ctrl+Shift+, shortcuts:
 * keeping the sidebar click and the keyboard shortcut in lockstep by
 * construction instead of duplicating the tab→screen table.
 */
internal fun screenForTab(tab: SidebarTab): Screen = when (tab) {
    SidebarTab.HOSTS -> Screen.HostList
    SidebarTab.SESSIONS -> Screen.Sessions
    SidebarTab.TUNNELS -> Screen.TunnelList
    SidebarTab.VAULT -> Screen.Vault
    SidebarTab.TRANSFERS -> Screen.Transfers
    SidebarTab.SETTINGS -> Screen.Settings
}

/**
 * Ouvre [url] dans le navigateur système via `Desktop.browse`. Garde-fou
 * schéma : seules les URLs `http`/`https` sont transmises à l'AWT, ce qui
 * bloque les schémas dangereux (`file://`, `javascript:`, `shell:`, …)
 * qui pourraient déclencher l'exécution de ressources locales si une URL
 * malicieuse était stockée dans la constante (défense en profondeur).
 * Les exceptions AWT (Desktop non supporté, navigateur introuvable) sont
 * absorbées silencieusement : on ne dispose pas d'un canal de feedback
 * UI à ce niveau et l'utilisateur peut copier l'URL depuis le dialog.
 */
private fun openExternalLink(url: String) {
    val scheme = runCatching { java.net.URI(url).scheme?.lowercase() }.getOrNull()
    if (scheme != "http" && scheme != "https") return
    runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }
}
