// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.navigation

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import fr.techtical.nextsh.R
import fr.techtical.nextsh.ui.theme.NearBlack
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import fr.techtical.nextsh.core.shortcut.ShortcutRequest
import fr.techtical.nextsh.ui.browser.BrowserOverlay
import fr.techtical.nextsh.ui.browser.TunnelBrowserViewModel
import fr.techtical.nextsh.ui.components.BottomNavDestination
import fr.techtical.nextsh.ui.components.NextShBottomBar
import fr.techtical.nextsh.ui.hosts.HostDetailScreen
import fr.techtical.nextsh.ui.hosts.HostImportScreen
import fr.techtical.nextsh.ui.hosts.HostListScreen
import fr.techtical.nextsh.ui.hosts.HostViewModel
import fr.techtical.nextsh.ui.lock.LocalAppLocked
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

/**
 * True before the app is usable: the first-launch flow, or the vault still locked. The shortcut and
 * resume requests wait behind it.
 */
private fun isBeforeUnlock(route: String?): Boolean =
    route == null || route == Routes.VaultUnlock.route || route == Routes.Onboarding.route

private fun isTunnelListRoute(route: String?): Boolean = route?.startsWith("tunnel_list") == true

/**
 * Whether the lock screen window ([AppLockScreen], created by `MainActivity`) covers the app: the app
 * is locked ([locked], see `AppLock`), the first launch is over, and the screen on the back stack is
 * not one of those that come before the unlock ([isBeforeUnlock]). On the unlock screen of the launch
 * the route is the lock: it calls `AppLock.unlock()` itself before it navigates, and nothing is open
 * under it. [onboardingCompleted] is null while the setting is still being read.
 */
internal fun shouldShowLockScreen(locked: Boolean, onboardingCompleted: Boolean?, route: String?): Boolean =
    locked && onboardingCompleted == true && !isBeforeUnlock(route)

/**
 * Whether the opaque layer of the composition hides the screens and takes their touches: the same as
 * [shouldShowLockScreen], plus the frames where the route is still null (the navigation restores its
 * back stack, which can be a screen of the app, and the lock screen window is not up yet). The window
 * of the lock screen takes over from it and also covers the dialogs; this layer is what keeps a frame
 * of the content from showing before the window does.
 */
internal fun shouldCoverContent(locked: Boolean, onboardingCompleted: Boolean?, route: String?): Boolean =
    locked && onboardingCompleted == true && route != Routes.VaultUnlock.route && route != Routes.Onboarding.route

/**
 * Whether a restart of the tunnels that waited for the user must take the user to the tunnel list
 * now. Before the unlock screen is passed it must not: [VaultUnlockScreen] sends the user there once
 * the vault is open. The same while the app is [locked] over a screen: the lock screen is that
 * unlock, and the restart waits behind it. On the list already there is nowhere to go, the screen
 * starts the tunnels. Anywhere else the user goes to the list, whatever screen they are on.
 */
internal fun shouldOpenTunnelListForResume(resumePending: Boolean, route: String?, locked: Boolean): Boolean =
    resumePending && !locked && !isBeforeUnlock(route) && !isTunnelListRoute(route)

/** How a restart of tunnels gets to the tunnel list, see [resumeNavigation]. */
internal enum class ResumeNavigation {
    /** Nowhere to go: nothing to resume, the unlock screen not passed, or already on the list. */
    NONE,

    /** Like the Tunnels tab (`popUpTo` the host list, `saveState`, `restoreState`). */
    TAB_STYLE,

    /** On top of the screen, saving nothing. */
    ON_TOP,
}

/**
 * Where the restart of the tunnels that waited for the user takes the user: [ResumeNavigation.NONE]
 * when [shouldOpenTunnelListForResume] says no. Otherwise, on a back stack shaped like a tab press
 * leaves it ([isTabShape]), the list opens like the Tunnels tab does, restoring a saved list (it is
 * opened without a tunnel to start, so a restored one is right). On any other back stack the list
 * opens on top and nothing is saved: the pop of a tab press would park a terminal screen or a form
 * it pops, saved where nothing restores it, where on top the terminal stays and Back returns to it.
 * [previousRoute] is the route of the entry under the one on screen. While the app is [locked] there
 * is nowhere to go yet, whatever the screen: the restart goes on when the lock screen is passed.
 */
internal fun resumeNavigation(
    resumePending: Boolean,
    currentRoute: String?,
    previousRoute: String?,
    locked: Boolean,
): ResumeNavigation =
    when {
        !shouldOpenTunnelListForResume(resumePending, currentRoute, locked) -> ResumeNavigation.NONE
        isTabShape(currentRoute, previousRoute) -> ResumeNavigation.TAB_STYLE
        else -> ResumeNavigation.ON_TOP
    }

/** What to do with the launcher shortcut that is pending, see [decideShortcut]. */
internal sealed interface ShortcutStep {
    /** No shortcut is pending. */
    data object None : ShortcutStep

    /** The app is still locked: the shortcut stays pending and acts after the unlock. */
    data object Wait : ShortcutStep

    /** A restart of tunnels has priority: the shortcut is consumed without acting. */
    data object Drop : ShortcutStep

    /** The app is open: act on the shortcut now. */
    data class Open(val request: ShortcutRequest) : ShortcutStep
}

/**
 * Whether the pending request is used up by this step: it acted ([ShortcutStep.Open]) or was
 * dropped ([ShortcutStep.Drop]). A request is consumed exactly once, so that it never fires later.
 */
internal val ShortcutStep.consumesRequest: Boolean
    get() = this is ShortcutStep.Open || this is ShortcutStep.Drop

/**
 * What a launcher shortcut does now, from the destination on screen. [request] was checked against
 * the token of this install by `MainActivity`; an intent that failed the check never got here, it
 * is [ShortcutStep.None].
 *
 * Before the unlock screen is passed the shortcut waits ([ShortcutStep.Wait]), and so it does while
 * the app is [locked] over a screen (the lock screen is that unlock again); past it, on an app that is
 * already open, it acts at once ([ShortcutStep.Open]). A pending restart of the tunnels that waited
 * for the user wins ([ShortcutStep.Drop]), as it always did. It waits too while the wipe of the vault has
 * the app closed ([wipeClosed]): the host or the tunnel it names is being erased, and a tap then connects
 * and starts nothing.
 */
internal fun decideShortcut(
    request: ShortcutRequest?,
    resumePending: Boolean,
    route: String?,
    locked: Boolean,
    wipeClosed: Boolean = false,
): ShortcutStep =
    when {
        request == null -> ShortcutStep.None
        locked || wipeClosed || isBeforeUnlock(route) -> ShortcutStep.Wait
        resumePending -> ShortcutStep.Drop
        else -> ShortcutStep.Open(request)
    }

/**
 * Opens what [request] asked for, on the screen the user is on.
 *
 * - A host goes to the terminal screen, of which there is one: the tabs belong to `TerminalTabStore`
 *   and not to the entry, so a second `Sessions` destination would not copy them, but it would
 *   stack a second screen on the same tabs. The screens above the terminal are popped (`popUpTo`,
 *   the terminal itself stays) and `launchSingleTop` swaps its arguments in place: the entry is
 *   kept, and the new `hostId` makes `TerminalScreen` show the tab the host already has, or connect
 *   and add one if it has none. With no terminal on the stack a new one is pushed, on top of what
 *   the user was doing. The host list is put under it if the back stack has neither.
 * - [ShortcutRequest.OpenTerminal] (a tap on the notification of the terminal service) goes the
 *   same way without a host: the terminal opens on its tabs as they are.
 * - A tunnel goes to a new tunnel list, which starts it by itself (see
 *   `TunnelViewModel.autoStartTunnelById`). How it gets there depends on the back stack, see
 *   [tunnelShortcutNavigation]:
 *   - On a back stack shaped like a tab press leaves it (the host list alone, or a tab on the host
 *     list) it navigates like the Tunnels tab (`popUpTo` the host list with `saveState`), but
 *     without `restoreState`: a restored list would keep its old arguments, whereas a new entry has a
 *     new start flag (see [oneShotStartTunnelId]) and starts the tunnel again if the user tapped the
 *     same shortcut after stopping it. Going through the same pop as a tab keeps the saved states in
 *     the shape a tab press leaves them, so that the Hosts tab goes back to the host list (see
 *     [bottomBarAction]). A tunnel list that is the screen on top is dropped first, unsaved: the new
 *     one replaces it, and no stale copy is left for the Tunnels tab to restore. A list saved by an
 *     earlier tab press can still be there, with its ViewModel: harmless, and not restored over the
 *     new one.
 *   - Anywhere else it goes on top of the screen, and the tunnel list that is on the back stack is
 *     replaced (`popUpTo` inclusive, nothing saved). Saving would park the screens it pops where
 *     nothing restores them, with their ViewModel alive: a terminal screen, and a form with its
 *     content. The terminal sessions are not at stake, they live in `TerminalTabStore`. On top,
 *     Back leads to the live screen. The unlock that restarts tunnels leaves the tunnel list alone
 *     on the back stack: it is replaced in place.
 */
private fun NavHostController.openShortcut(request: ShortcutRequest) {
    when (request) {
        is ShortcutRequest.ConnectHost -> {
            if (!isOnBackStack(Routes.Sessions.route) && !isOnBackStack(Routes.HostList.route)) {
                navigate(Routes.HostList.route) { launchSingleTop = true }
            }
            navigate(Routes.Sessions.createRoute(request.hostId)) {
                popUpTo(Routes.Sessions.route) { inclusive = false }
                launchSingleTop = true
            }
        }
        is ShortcutRequest.StartTunnel -> {
            val route = Routes.TunnelList.createRoute(request.tunnelId)
            val current = currentDestination?.route
            when (tunnelShortcutNavigation(current, previousBackStackEntry?.destination?.route)) {
                TunnelShortcutNavigation.TAB_STYLE -> {
                    // Only the screen on top can be a tunnel list in this shape (the host list is the
                    // root): popping up to the list by route, as a list below the host list would
                    // make it, could empty the back stack.
                    if (isTunnelListRoute(current)) popBackStack()
                    navigate(route) {
                        popUpTo(Routes.HostList.route) { saveState = true }
                        launchSingleTop = true
                    }
                }
                TunnelShortcutNavigation.ON_TOP ->
                    navigate(route) { popUpTo(Routes.TunnelList.route) { inclusive = true } }
            }
        }
        is ShortcutRequest.OpenTerminal -> {
            if (!isOnBackStack(Routes.Sessions.route) && !isOnBackStack(Routes.HostList.route)) {
                navigate(Routes.HostList.route) { launchSingleTop = true }
            }
            navigate(Routes.Sessions.createRoute()) {
                popUpTo(Routes.Sessions.route) { inclusive = false }
                launchSingleTop = true
            }
        }
    }
}

/**
 * Whether the back stack has the shape a press on a tab of the bottom bar leaves it in: the host list
 * alone, or one tab directly on the host list. [previousRoute] is the route of the entry under the
 * one on screen. Only then is popping up to the host list with `saveState` safe: it saves a tab,
 * which a tab press restores, and nothing else. Any other screen above the host list (a terminal, a
 * form) would be parked by the pop, saved where nothing restores it.
 */
internal fun isTabShape(currentRoute: String?, previousRoute: String?): Boolean =
    (currentRoute == Routes.HostList.route && previousRoute == null) ||
        (isBottomNavRoute(currentRoute) && previousRoute == Routes.HostList.route)

/** How a tunnel shortcut gets to the tunnel list, see [tunnelShortcutNavigation]. */
internal enum class TunnelShortcutNavigation {
    /** Like the Tunnels tab (`popUpTo` the host list, `saveState`), without `restoreState`. */
    TAB_STYLE,

    /** On top of the screen, replacing the tunnel list that is on the back stack, saving nothing. */
    ON_TOP,
}

/**
 * [TunnelShortcutNavigation.TAB_STYLE] on a back stack shaped like a tab press leaves it
 * ([isTabShape]), [TunnelShortcutNavigation.ON_TOP] on any other: a terminal or a form on screen or
 * under the tab, or no host list at all.
 */
internal fun tunnelShortcutNavigation(currentRoute: String?, previousRoute: String?): TunnelShortcutNavigation =
    if (isTabShape(currentRoute, previousRoute)) TunnelShortcutNavigation.TAB_STYLE else TunnelShortcutNavigation.ON_TOP

/** What a press on a tab of the bottom bar does, see [bottomBarAction]. */
internal enum class BottomBarAction {
    /** The tab is the one on screen. */
    STAY,

    /** Back to the host list, directly under the tab on screen: popped to, saving the tab. */
    POP_TO_HOST_LIST_SAVING,

    /** Back to the host list with other screens between: popped to, closing them as Back would. */
    POP_TO_HOST_LIST_CLOSING,

    /** The host list is not on the back stack: put it there, restoring nothing. */
    SHOW_HOST_LIST,

    /** Navigate to the tab, restoring what was saved for it, saving the tab on screen. */
    NAVIGATE,

    /**
     * Like [NAVIGATE], but the screens between the tab on screen and the host list are closed, not
     * saved: a terminal under a tunnel list that a shortcut opened on top would be parked otherwise.
     */
    NAVIGATE_CLOSING,
}

/**
 * What the Hosts tab does depends on the back stack. Navigating to the host list with `restoreState`
 * restores whatever the back stack saved under the host list, and that can be a stack of other
 * screens: a `popUpTo` with `saveState` maps the destination it pops up to onto the state it just
 * saved, unless a tab press from the host list mapped it to nothing first, and a stale or null
 * mapping makes the press push a stack back or do nothing. So the Hosts tab never restores:
 *
 * - on the tab that is on screen the press does nothing ([BottomBarAction.STAY]);
 * - with the host list on the back stack it pops back to it. Directly under the tab on screen it
 *   saves the tab, as a tab press does, so that the other tabs find their state again
 *   ([BottomBarAction.POP_TO_HOST_LIST_SAVING]). With other screens between ([previousRoute] is not the
 *   host list: a terminal under a tunnel list that a shortcut opened on top, say) it pops them
 *   without saving, as Back would ([BottomBarAction.POP_TO_HOST_LIST_CLOSING]): saved, a terminal
 *   screen would be parked with nothing to restore it. Its sessions are not lost either way, they
 *   live in `TerminalTabStore`;
 * - without the host list on the back stack (the unlock that restarts tunnels leaves only the tunnel
 *   list) it puts it there without restoring ([BottomBarAction.SHOW_HOST_LIST]).
 *
 * Every other tab navigates with `restoreState`, as it did, and saves the tab on screen. With other
 * screens between that tab and the host list it closes them instead ([BottomBarAction.NAVIGATE_CLOSING]),
 * for the same reason as above.
 */
internal fun bottomBarAction(
    target: String,
    currentRoute: String?,
    previousRoute: String?,
    hostListOnBackStack: Boolean,
): BottomBarAction =
    when {
        // The tab on screen: nothing to do. Closing the screens under it would drop a terminal screen for a press that changes nothing.
        target == currentRoute -> BottomBarAction.STAY
        target != Routes.HostList.route ->
            if (hostListOnBackStack && currentRoute != Routes.HostList.route && previousRoute != Routes.HostList.route) {
                BottomBarAction.NAVIGATE_CLOSING
            } else {
                BottomBarAction.NAVIGATE
            }
        !hostListOnBackStack -> BottomBarAction.SHOW_HOST_LIST
        previousRoute == Routes.HostList.route -> BottomBarAction.POP_TO_HOST_LIST_SAVING
        else -> BottomBarAction.POP_TO_HOST_LIST_CLOSING
    }

/** `getBackStackEntry` throws when the destination is not on the back stack. */
private fun NavHostController.isOnBackStack(route: String): Boolean =
    runCatching { getBackStackEntry(route) }.isSuccess

/**
 * Where an entry of the tunnel list remembers that the start asked by its route was handled: its
 * `SavedStateHandle`, which the back stack saves and restores with the entry (bottom bar tabs,
 * recreation of the activity, death of the process).
 */
internal const val START_TUNNEL_HANDLED_KEY = "start_tunnel_handled"

/**
 * The tunnel the list must start when it opens: the one the route asked for ([requestedId], from a
 * launcher shortcut), but only the first time. The route argument stays on the entry for good, so
 * an entry that the bottom bar saved and restores later, or that an activity recreation brings
 * back, still carries it: starting again would restart a tunnel that the user stopped meanwhile.
 * [alreadyHandled] says this entry started it already.
 */
internal fun tunnelToStartOnOpen(requestedId: String?, alreadyHandled: Boolean): String? =
    if (alreadyHandled) null else requestedId

/**
 * The tunnel to start for [entry], once in its life: a new shortcut navigation makes a new entry
 * (see [tunnelShortcutNavigation]: a navigation without `restoreState` after a pop, either way),
 * which has a flag of its own and starts its tunnel; the same entry restored does not. Decided when
 * the entry comes into composition and kept while it stays there, so that marking it handled does
 * not take the argument away from the screen that is using it.
 */
@Composable
private fun oneShotStartTunnelId(entry: NavBackStackEntry): String? {
    val handle = entry.savedStateHandle
    val startId = remember(entry.id) {
        tunnelToStartOnOpen(
            requestedId = entry.arguments?.getString("startTunnelId"),
            alreadyHandled = handle.get<Boolean>(START_TUNNEL_HANDLED_KEY) == true,
        )
    }
    LaunchedEffect(startId) {
        if (startId != null) handle[START_TUNNEL_HANDLED_KEY] = true
    }
    return startId
}

/**
 * [pendingShortcut] is the launcher shortcut to act on. `MainActivity` made it only from an intent
 * that carries the token of this install (the activity is exported: the extras alone can be forged
 * by any app). It acts as soon as the vault is unlocked, at the unlock or at once on an app that is
 * already open. [onShortcutConsumed] spends it when it acts or is dropped, and answers whether this
 * call was the one that spent it: false if it was spent already, replaced, or expired.
 *
 * [tunnelResumePending] says tunnels that waited for the user are to be restarted: the notification
 * that reported them was tapped and `MainActivity` checked its one-time token. The tunnel list is
 * where they are started. It wins over a shortcut.
 *
 * [locked] says the lock screen is to cover the app (`AppLock`); it has no default, a caller that
 * forgot it would leave the app open. Over whatever screen is up, once the first launch is over and
 * past the unlock screen of the launch (see [shouldShowLockScreen]), the lock screen window is up:
 * `MainActivity` creates it, at the very end of its composition, and is told whether it is needed by
 * [onLockScreenNeededChanged]. Until the window is up an opaque layer of this composition hides the
 * screens and takes their touches ([shouldCoverContent]). While the app is locked a shortcut and a
 * restart of tunnels wait for the unlock as they do at the launch, and the dialogs of the tunnel
 * browser and of the SFTP browser stay away. Nothing under the lock changes.
 *
 * The lock is also provided to everything this hosts as [LocalAppLocked], for the screens that are
 * several layers down and open a dialog that must wait for the unlock (the SFTP pane of the split
 * terminal).
 */
@Composable
fun NavGraph(
    pendingShortcut: ShortcutRequest? = null,
    tunnelResumePending: Boolean = false,
    locked: Boolean,
    wipeClosed: Boolean = false,
    onLockScreenNeededChanged: (Boolean) -> Unit = {},
    onShortcutConsumed: (ShortcutRequest) -> Boolean = { true },
) {
    CompositionLocalProvider(LocalAppLocked provides locked) {
        NavGraphContent(
            pendingShortcut = pendingShortcut,
            tunnelResumePending = tunnelResumePending,
            locked = locked,
            wipeClosed = wipeClosed,
            onLockScreenNeededChanged = onLockScreenNeededChanged,
            onShortcutConsumed = onShortcutConsumed,
        )
    }
}

@Composable
private fun NavGraphContent(
    pendingShortcut: ShortcutRequest?,
    tunnelResumePending: Boolean,
    locked: Boolean,
    wipeClosed: Boolean,
    onLockScreenNeededChanged: (Boolean) -> Unit,
    onShortcutConsumed: (ShortcutRequest) -> Boolean,
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

    // The lock screen window is created by MainActivity, at the end of its composition: it is told
    // whether to be up. Until it is (a frame), the layer below hides the screens.
    val lockScreenNeeded = shouldShowLockScreen(locked, onboardingCompleted, currentRoute)
    SideEffect { onLockScreenNeededChanged(lockScreenNeeded) }

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

    // The browser overlay is drawn over every screen: a shortcut or a restart of tunnels that
    // navigates must put it away first, or the user would see nothing happen behind it. Hiding it
    // keeps the session.
    val hideBrowserOverlay = {
        if (browserVm.sessionHolder.visibleTunnelId.value != null) browserVm.sessionHolder.hide()
    }

    // Restart of the tunnels that waited for the user (see tunnelResumePending). Past the unlock
    // screen the user goes to the tunnel list now, whatever screen is open; before it, onUnlocked
    // below sends them there once the vault is open. The list starts the tunnels.
    LaunchedEffect(tunnelResumePending, currentRoute, locked) {
        val navigation = resumeNavigation(
            resumePending = tunnelResumePending,
            currentRoute = currentRoute,
            previousRoute = navController.previousBackStackEntry?.destination?.route,
            locked = locked,
        )
        if (navigation == ResumeNavigation.NONE) return@LaunchedEffect
        hideBrowserOverlay()
        when (navigation) {
            ResumeNavigation.NONE -> Unit
            ResumeNavigation.TAB_STYLE -> navController.navigate(Routes.TunnelList.createRoute()) {
                popUpTo(Routes.HostList.route) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
            // On top, saving nothing: a terminal under it stays on the back stack and Back returns to it.
            ResumeNavigation.ON_TOP -> navController.navigate(Routes.TunnelList.createRoute()) {
                launchSingleTop = true
            }
        }
    }

    // Launcher shortcut (see pendingShortcut). It waits behind the unlock screen; once the vault is
    // open this is the one place where it acts, whether the unlock just happened (the route changed
    // to the host list) or the app was open already. It is spent through the holder before it acts,
    // which says no if another took it or it expired, so that it cannot fire a second time or late.
    LaunchedEffect(pendingShortcut, tunnelResumePending, currentRoute, locked, wipeClosed) {
        val request = pendingShortcut ?: return@LaunchedEffect
        val step = decideShortcut(request, tunnelResumePending, currentRoute, locked, wipeClosed)
        if (!step.consumesRequest) return@LaunchedEffect
        val spent = onShortcutConsumed(request)
        if (spent && step is ShortcutStep.Open) {
            hideBrowserOverlay()
            navController.openShortcut(step.request)
        }
    }

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
                            val action = bottomBarAction(
                                target = route,
                                currentRoute = currentRoute,
                                previousRoute = navController.previousBackStackEntry?.destination?.route,
                                hostListOnBackStack = navController.isOnBackStack(Routes.HostList.route),
                            )
                            when (action) {
                                BottomBarAction.STAY -> Unit
                                // Retour à la liste d'hôtes sans rien restaurer par-dessus, voir bottomBarAction.
                                BottomBarAction.POP_TO_HOST_LIST_SAVING ->
                                    navController.popBackStack(Routes.HostList.route, inclusive = false, saveState = true)
                                BottomBarAction.POP_TO_HOST_LIST_CLOSING ->
                                    navController.popBackStack(Routes.HostList.route, inclusive = false, saveState = false)
                                BottomBarAction.SHOW_HOST_LIST ->
                                    navController.navigate(Routes.HostList.route) { launchSingleTop = true }
                                BottomBarAction.NAVIGATE, BottomBarAction.NAVIGATE_CLOSING -> {
                                    val navRoute = if (route == Routes.TunnelList.route) Routes.TunnelList.createRoute() else route
                                    navController.navigate(navRoute) {
                                        // Éviter l'empilement : revenir à la même destination ou remonter jusqu'à HostList
                                        popUpTo(Routes.HostList.route) {
                                            // Un écran de détail entre l'onglet et HostList (un terminal)
                                            // serait garé là où rien ne le restaure : on le ferme.
                                            saveState = action == BottomBarAction.NAVIGATE
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
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
                            // Tunnels waiting for the user, asked back by their notification: the
                            // list starts them, the vault is open now. Wins over a shortcut, which
                            // the effect above then drops.
                            val destination = if (tunnelResumePending) {
                                Routes.TunnelList.createRoute()
                            } else {
                                // A pending shortcut acts from here, see the effect above.
                                Routes.HostList.route
                            }
                            navController.navigate(destination) {
                                popUpTo(Routes.VaultUnlock.route) { inclusive = true }
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
                        // The list already holds the connection: hand its session to the terminal.
                        onNavigateToSession  = { hostId, sessionId ->
                            navController.navigate(Routes.Sessions.createRoute(hostId, sessionId))
                        },
                        // The terminal tabs exist already (they outlive the screen): the terminal
                        // selects the tab of the host, or opens on the tabs as they are without a host.
                        onNavigateToTerminal = { hostId ->
                            navController.navigate(Routes.Sessions.createRoute(hostId)) {
                                launchSingleTop = true
                            }
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
                        },
                        // Set only when the host list connected first (see Routes.Sessions).
                        navArgument("sessionId") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                    )
                ) { backStackEntry ->
                    val hostId = backStackEntry.arguments?.getString("hostId")
                    val sessionId = backStackEntry.arguments?.getString("sessionId")
                    SessionTabsScreen(
                        hostId = hostId,
                        sessionId = sessionId,
                        onBack = { navController.popBackStack() },
                        onNavigateToSftp = { sftpSessionId, hostLabel ->
                            navController.navigate(Routes.SftpBrowser.createRoute(sftpSessionId, hostLabel))
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
                    // The start asked by a launcher shortcut is one-shot, see oneShotStartTunnelId.
                    val startTunnelId = oneShotStartTunnelId(backStackEntry)
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
                            // One enrollment screen at a time: a second tap (or a slow double tap)
                            // must not push a copy of it on top of the first.
                            navController.navigate(Routes.Fido2Enroll.route) { launchSingleTop = true }
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
                        // Its dialog of the replacement waits for the unlock, like the ones of the
                        // tunnel browser: it comes up after the answer of a server, and its window would
                        // be over the lock screen.
                        locked    = locked,
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
            // Its dialogs wait for the unlock: they are windows of their own, and would be over the
            // lock screen, which is created before them if they come up after it.
            locked = locked,
        )

        // While the app is locked, an opaque layer over everything of this composition, that takes
        // the touches: the lock screen window (created by MainActivity, see lockScreenNeeded) takes
        // a frame to come up and is what covers the dialogs, this layer is what keeps the content of
        // the screens from showing, or answering a tap, in that frame. Above the browser overlay,
        // whose z-index is 10 while it shows.
        if (shouldCoverContent(locked, onboardingCompleted, currentRoute)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(LOCK_COVER_Z_INDEX)
                    .background(NearBlack)
                    .pointerInput(Unit) {
                        // Every gesture is taken, from the first touch to the last finger up.
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false).consume()
                            do {
                                val event = awaitPointerEvent()
                                event.changes.forEach { it.consume() }
                            } while (event.changes.any { it.pressed })
                        }
                    },
            )
        }
    }
}

/** Above the tunnel browser overlay, which is at 10 while it shows. */
private const val LOCK_COVER_Z_INDEX = 100f
