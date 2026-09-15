// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.navigation

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SyncAlt
import androidx.compose.material3.Scaffold
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import fr.techtical.nextsh.R
import fr.techtical.nextsh.ui.theme.NearBlack
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import fr.techtical.nextsh.core.shortcut.ShortcutUpdater
import fr.techtical.nextsh.ui.browser.BrowserOverlay
import fr.techtical.nextsh.ui.browser.TunnelBrowserViewModel
import fr.techtical.nextsh.ui.components.BottomNavDestination
import fr.techtical.nextsh.ui.components.NextShBottomBar
import fr.techtical.nextsh.ui.hosts.HostDetailScreen
import fr.techtical.nextsh.ui.hosts.HostImportScreen
import fr.techtical.nextsh.ui.hosts.HostListScreen
import fr.techtical.nextsh.ui.hosts.HostViewModel
import fr.techtical.nextsh.ui.onboarding.FirstLaunchScreen
import fr.techtical.nextsh.ui.onboarding.OnboardingViewModel
import fr.techtical.nextsh.ui.sessions.SessionTabsScreen
import fr.techtical.nextsh.ui.settings.KnownHostsScreen
import fr.techtical.nextsh.ui.settings.SettingsScreen
import fr.techtical.nextsh.ui.sync.ConflictResolutionScreen
import fr.techtical.nextsh.ui.sync.EnrolledDevicesScreen
import fr.techtical.nextsh.ui.sync.EnrollmentScanScreen
import fr.techtical.nextsh.ui.tunnels.TunnelConfigScreen
import fr.techtical.nextsh.ui.tunnels.TunnelListScreen
import fr.techtical.nextsh.ui.tunnels.TunnelViewModel
import fr.techtical.nextsh.ui.sftp.SftpBrowserScreen
import fr.techtical.nextsh.ui.snippets.SnippetListScreen
import fr.techtical.nextsh.ui.themes.TerminalThemesScreen
import fr.techtical.nextsh.ui.vault.Fido2EnrollScreen
import fr.techtical.nextsh.ui.vault.KeyImportScreen
import fr.techtical.nextsh.ui.vault.VaultScreen
import fr.techtical.nextsh.ui.vault.VaultUnlockScreen

private val BOTTOM_NAV_ROUTES = setOf(
    Routes.HostList.route,
    Routes.Vault.route,
    Routes.Settings.route,
)

private fun isBottomNavRoute(route: String?): Boolean {
    if (route == null) return false
    return route in BOTTOM_NAV_ROUTES || route.startsWith("tunnel_list")
}

/**
 * Écrans où le paysage apporte quelque chose : un terminal gagne des colonnes,
 * un explorateur de fichiers gagne des métadonnées lisibles.
 *
 * Partout ailleurs, listes et formulaires, le paysage n'apporte rien et dégrade
 * la saisie : l'application reste en portrait.
 */
private fun isRotatableRoute(route: String?): Boolean {
    if (route == null) return false
    return route.startsWith("sessions") || route.startsWith("sftp_browser")
}

@Composable
fun NavGraph(
    pendingShortcutAction: String? = null,
    pendingHostId: String? = null,
    pendingTunnelId: String? = null,
    onShortcutConsumed: () -> Unit = {},
) {
    // First-launch gating: read the persisted onboarding-completed flag (shared
    // VM instance so completeOnboarding() updates what this resolver reads). The
    // flag is `null` while loading: defer building the NavHost until resolved
    // to avoid a flash of the unlock screen on a fresh install.
    val onboardingViewModel: OnboardingViewModel = hiltViewModel()
    val onboardingCompleted by onboardingViewModel.onboardingCompleted.collectAsState()

    // Wait for the flag to resolve before deciding the start destination.
    if (onboardingCompleted == null) {
        Box(modifier = Modifier.fillMaxSize().background(NearBlack))
        return
    }
    val startDestination =
        if (onboardingCompleted == true) Routes.VaultUnlock.route else Routes.Onboarding.route

    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val bottomNavDestinations = listOf(
        BottomNavDestination(
            route = Routes.HostList.route,
            label = stringResource(R.string.nav_hosts),
            icon = Icons.Rounded.Dns,
        ),
        BottomNavDestination(
            route = Routes.TunnelList.route,
            label = stringResource(R.string.nav_tunnels),
            icon = Icons.Rounded.SyncAlt,
        ),
        BottomNavDestination(
            route = Routes.Vault.route,
            label = stringResource(R.string.nav_vault),
            icon = Icons.Rounded.Security,
        ),
        BottomNavDestination(
            route = Routes.Settings.route,
            label = stringResource(R.string.nav_settings),
            icon = Icons.Rounded.Settings,
        ),
    )

    val showBottomBar = isBottomNavRoute(currentRoute)

    // Scoped to the activity: holds the persistent WebView overlay
    val browserVm: TunnelBrowserViewModel = hiltViewModel()
    val visibleBrowserTunnelId by browserVm.sessionHolder.visibleTunnelId.collectAsState()

    // Orientation pilotée en un seul endroit, à partir de la destination
    // courante. La décider dans chaque écran, comme avant, provoquait un
    // clignotement pendant les transitions : l'écran quitté imposait le
    // portrait juste après que le nouvel écran ait demandé la liberté.
    //
    // Android 16 ignore ces demandes sur les écrans d'au moins 600 dp, où la
    // rotation reste donc libre. C'est le comportement voulu : la contrainte
    // n'existe que pour éviter un paysage inutile sur téléphone.
    val activity = LocalContext.current as? Activity
    val allowRotation = isRotatableRoute(currentRoute) || visibleBrowserTunnelId != null
    LaunchedEffect(activity, allowRotation) {
        @Suppress("SourceLockedOrientationActivity")
        activity?.requestedOrientation = if (allowRotation) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = {
                if (showBottomBar) {
                    NextShBottomBar(
                        currentRoute = currentRoute,
                        onNavigate = { route ->
                            val navRoute = if (route == Routes.TunnelList.route) Routes.TunnelList.createRoute() else route
                            navController.navigate(navRoute) {
                                // Éviter l'empilement : revenir à la même destination ou remonter jusqu'à HostList
                                popUpTo(Routes.HostList.route) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        destinations = bottomNavDestinations,
                    )
                }
            },
        ) { contentPadding ->
            NavHost(
                navController    = navController,
                startDestination = startDestination,
                modifier         = Modifier
                    .padding(contentPadding)
                    // Scaffold applique les marges système à son contenu sans
                    // les déclarer consommées : sa documentation demande les
                    // deux, padding ET consumeWindowInsets. Sans la seconde,
                    // chaque écran imbriqué, qui a lui aussi son Scaffold et sa
                    // TopAppBar, réappliquait les mêmes marges. En haut cela
                    // donnait une bande vide sous la barre d'état, en bas une
                    // bande entre le contenu et la barre de navigation de
                    // l'application.
                    // Effet de bord voulu : les navigationBarsPadding des
                    // écrans deviennent sans effet, la marge basse étant déjà
                    // portée ici.
                    .consumeWindowInsets(contentPadding),
            ) {
                // ── First-launch onboarding ───────────────────────────────────────
                composable(Routes.Onboarding.route) {
                    FirstLaunchScreen(
                        viewModel = onboardingViewModel,
                        onCompleted = {
                            // Skip path: vault is ready, go straight to host list.
                            navController.navigate(Routes.HostList.route) {
                                popUpTo(Routes.Onboarding.route) { inclusive = true }
                            }
                        },
                        onConfigureSync = {
                            // Configure path: land on host list, then open the
                            // enrollment scanner on top so back returns to the
                            // host list (not onboarding).
                            navController.navigate(Routes.HostList.route) {
                                popUpTo(Routes.Onboarding.route) { inclusive = true }
                            }
                            navController.navigate(Routes.EnrollmentScan.route)
                        },
                    )
                }

                // ── Vault unlock ──────────────────────────────────────────────────
                composable(Routes.VaultUnlock.route) {
                    VaultUnlockScreen(
                        onUnlocked = {
                            val action = pendingShortcutAction
                            val hostId = pendingHostId
                            val tunnelId = pendingTunnelId
                            onShortcutConsumed()
                            when (action) {
                                ShortcutUpdater.ACTION_CONNECT_HOST -> {
                                    navController.navigate(Routes.HostList.route) {
                                        popUpTo(Routes.VaultUnlock.route) { inclusive = true }
                                    }
                                    hostId?.let {
                                        navController.navigate(Routes.Sessions.createRoute(it))
                                    }
                                }
                                ShortcutUpdater.ACTION_START_TUNNEL -> {
                                    navController.navigate(Routes.TunnelList.createRoute(tunnelId)) {
                                        popUpTo(Routes.VaultUnlock.route) { inclusive = true }
                                    }
                                }
                                else -> {
                                    navController.navigate(Routes.HostList.route) {
                                        popUpTo(Routes.VaultUnlock.route) { inclusive = true }
                                    }
                                }
                            }
                        },
                    )
                }

                // ── Host list ─────────────────────────────────────────────────────
                composable(Routes.HostList.route) {
                    val viewModel: HostViewModel = hiltViewModel()
                    HostListScreen(
                        viewModel            = viewModel,
                        onNavigateToDetail   = { hostId ->
                            navController.navigate(
                                if (hostId != null) Routes.HostDetail.createRoute(hostId)
                                else Routes.HostDetail.createRoute("new")
                            )
                        },
                        onNavigateToSession  = { hostId ->
                            navController.navigate(Routes.Sessions.createRoute(hostId))
                        },
                        onNavigateToSnippets = {
                            navController.navigate(Routes.SnippetList.route)
                        },
                        onNavigateToImport = {
                            navController.navigate(Routes.HostImport.route)
                        },
                        onNavigateToSettings = {
                            navController.navigate(Routes.Settings.route) {
                                popUpTo(Routes.HostList.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        onNavigateToSftp = { sessionId, hostLabel ->
                            navController.navigate(Routes.SftpBrowser.createRoute(sessionId, hostLabel))
                        },
                        onNavigateToConflictResolution = {
                            navController.navigate(Routes.ConflictResolution.route)
                        },
                    )
                }

                // ── Host import (Termius / KeePassXC) ──────────────────────────────
                composable(Routes.HostImport.route) {
                    HostImportScreen(
                        onBack = { navController.popBackStack() },
                    )
                }

                // ── Host detail ───────────────────────────────────────────────────
                composable(
                    route = Routes.HostDetail.route,
                    arguments = listOf(
                        navArgument("hostId") { type = NavType.StringType }
                    )
                ) { backStackEntry ->
                    val rawHostId = backStackEntry.arguments?.getString("hostId")
                    // "new" est le sentinelle pour la création
                    val hostId = if (rawHostId == "new") null else rawHostId
                    val viewModel: HostViewModel = hiltViewModel()
                    HostDetailScreen(
                        viewModel = viewModel,
                        hostId    = hostId,
                        onBack    = { navController.popBackStack() },
                    )
                }

                // ── Sessions / Terminal ───────────────────────────────────────────
                composable(
                    route = Routes.Sessions.route,
                    arguments = listOf(
                        navArgument("hostId") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        }
                    )
                ) { backStackEntry ->
                    val hostId = backStackEntry.arguments?.getString("hostId")
                    SessionTabsScreen(
                        hostId = hostId,
                        onBack = { navController.popBackStack() },
                        onNavigateToSftp = { sessionId, hostLabel ->
                            navController.navigate(Routes.SftpBrowser.createRoute(sessionId, hostLabel))
                        },
                    )
                }

                // ── Tunnel list ───────────────────────────────────────────────────
                composable(
                    route = Routes.TunnelList.route,
                    arguments = listOf(
                        navArgument("startTunnelId") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        }
                    )
                ) { backStackEntry ->
                    val startTunnelId = backStackEntry.arguments?.getString("startTunnelId")
                    val viewModel: TunnelViewModel = hiltViewModel()
                    TunnelListScreen(
                        viewModel          = viewModel,
                        startTunnelId      = startTunnelId,
                        onNavigateToConfig = { tunnelId ->
                            navController.navigate(
                                Routes.TunnelConfig.createRoute(tunnelId ?: Routes.TunnelConfig.NEW)
                            )
                        },
                        onBack             = { navController.popBackStack() },
                        onOpenBrowser      = { tunnelId ->
                            browserVm.sessionHolder.show(tunnelId)
                        },
                    )
                }

                // ── Tunnel config ─────────────────────────────────────────────────
                composable(
                    route = Routes.TunnelConfig.route,
                    arguments = listOf(
                        navArgument("tunnelId") { type = NavType.StringType }
                    )
                ) { backStackEntry ->
                    val rawTunnelId = backStackEntry.arguments?.getString("tunnelId")
                    val tunnelId = if (rawTunnelId == Routes.TunnelConfig.NEW) null else rawTunnelId
                    val viewModel: TunnelViewModel = hiltViewModel()
                    TunnelConfigScreen(
                        tunnelId  = tunnelId,
                        onBack    = { navController.popBackStack() },
                        viewModel = viewModel,
                    )
                }

                // ── Snippets ──────────────────────────────────────────────────────
                composable(Routes.SnippetList.route) {
                    SnippetListScreen(
                        onBack = { navController.popBackStack() },
                    )
                }

                // ── Vault / Clés ──────────────────────────────────────────────────
                composable(Routes.Vault.route) {
                    VaultScreen(
                        onNavigateToKeyImport = {
                            navController.navigate(Routes.KeyImport.route)
                        },
                        onNavigateToFido2Enroll = {
                            navController.navigate(Routes.Fido2Enroll.route)
                        },
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(Routes.KeyImport.route) {
                    KeyImportScreen(
                        onBack = { navController.popBackStack() },
                    )
                }

                // ── FIDO2 Enrollment (YubiKey hardware key) ───────────────────
                composable(Routes.Fido2Enroll.route) {
                    Fido2EnrollScreen(
                        onBack = { navController.popBackStack() },
                    )
                }

                // ── Settings ──────────────────────────────────────────────────────
                composable(Routes.Settings.route) {
                    SettingsScreen(
                        onBack = { navController.popBackStack() },
                        onNavigateToKnownHosts = {
                            navController.navigate(Routes.KnownHosts.route)
                        },
                        onNavigateToEnrollmentScan = {
                            navController.navigate(Routes.EnrollmentScan.route)
                        },
                        onNavigateToConflictResolution = {
                            navController.navigate(Routes.ConflictResolution.route)
                        },
                        onNavigateToEnrolledDevices = {
                            navController.navigate(Routes.EnrolledDevices.route)
                        },
                        onNavigateToTerminalThemes = {
                            navController.navigate(Routes.TerminalThemes.route)
                        },
                    )
                }

                // ── Thèmes terminal (depuis Settings) ─────────────────────────────
                composable(Routes.TerminalThemes.route) {
                    TerminalThemesScreen(
                        onBack = { navController.popBackStack() },
                    )
                }

                // ── Enrolled Devices (Phase 2 Wave 5) ─────────────────────────────
                composable(Routes.EnrolledDevices.route) {
                    EnrolledDevicesScreen(
                        onBack = { navController.popBackStack() },
                    )
                }

                // ── Known Hosts ────────────────────────────────────────────────────
                composable(Routes.KnownHosts.route) {
                    KnownHostsScreen(
                        onBack = { navController.popBackStack() },
                    )
                }

                // ── Enrollment Scan (Phase 2 LAN Sync) ────────────────────────────
                composable(Routes.EnrollmentScan.route) {
                    EnrollmentScanScreen(
                        onBack = { navController.popBackStack() },
                    )
                }

                // ── Conflict Resolution (Phase 2 Wave 4.2) ────────────────────────
                composable(Routes.ConflictResolution.route) {
                    ConflictResolutionScreen(
                        onBack = { navController.popBackStack() },
                    )
                }

                // ── SFTP Browser ──────────────────────────────────────────────────
                composable(
                    route = Routes.SftpBrowser.route,
                    arguments = listOf(
                        navArgument("sessionId") { type = NavType.StringType },
                        navArgument("hostLabel") {
                            type = NavType.StringType
                            defaultValue = ""
                        },
                    )
                ) { backStackEntry ->
                    val sessionId = backStackEntry.arguments?.getString("sessionId") ?: return@composable
                    val hostLabel = backStackEntry.arguments?.getString("hostLabel") ?: ""
                    SftpBrowserScreen(
                        sessionId = sessionId,
                        hostLabel = hostLabel,
                        onBack    = { navController.popBackStack() },
                    )
                }
            }

        }

        // Persistent browser overlay: always composed when a session is active,
        // z-index controls whether it's on top (visible) or behind (minimized).
        // The WebView is NEVER detached from its parent ViewGroup.
        BrowserOverlay(
            sessionHolder = browserVm.sessionHolder,
            viewModel = browserVm,
        )
    }
}
