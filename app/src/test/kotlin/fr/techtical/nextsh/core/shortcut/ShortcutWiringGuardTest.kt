// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.shortcut

import fr.techtical.nextsh.core.logging.blankComments
import fr.techtical.nextsh.testutil.ProjectFiles
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

/**
 * Smoke alarm on the wiring of the launcher shortcuts between `MainActivity` and `NavGraph`, the one
 * thing the unit tests cannot run: an Activity that the system recreates, redelivers intents to, and
 * a navigation graph. What can be run is run (`ShortcutRequestTest` reads intents,
 * `PendingShortcutTest` spends requests, `NavGraphShortcutTest` decides); this checks that the two
 * files still use those pieces the way the design needs.
 *
 * Same compromise and same rule as `BiometricPromptWiringGuardTest` and
 * `SshSessionManagerWiringGuardTest`: a cheap source check. A source that cannot be found FAILS the
 * test (see [ProjectFiles]).
 */
class ShortcutWiringGuardTest {

    /** The code of a main source file of the app, comments blanked. */
    private fun code(path: String): String = blankComments(ProjectFiles.mainSource(path).readText())

    private val main get() = code("MainActivity.kt")
    private val navGraph get() = code("ui/navigation/NavGraph.kt")

    /** The text between the braces that follow [signature]; fails if there is none. */
    private fun bodyOf(code: String, signature: String): String {
        val start = code.indexOf(signature)
        assertTrue(start >= 0, "$signature not found")
        val open = code.indexOf('{', start)
        var depth = 0
        for (i in open until code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(open + 1, i)
                }
            }
        }
        fail<Unit>("$signature has no end")
        return ""
    }

    private fun squeeze(text: String) = text.replace(Regex("""\s+"""), " ")

    // ── MainActivity: what the intents are allowed to do ──────────────────────

    @Test
    fun `a restored activity reads its intent as already handled, a new intent never does`() {
        val main = main

        assertTrue(
            squeeze(bodyOf(main, "override fun onCreate(")).contains("takeShortcut(intent, restored = savedInstanceState != null)"),
            "restored comes from savedInstanceState",
        )
        assertTrue(
            squeeze(bodyOf(main, "override fun onNewIntent(")).contains("takeShortcut(intent, restored = false)"),
            "a new intent is never a redelivery",
        )
    }

    @Test
    fun `the activity reads the shortcut through the checked reader, and a restored one offers nothing`() {
        val take = squeeze(bodyOf(main, "private fun takeShortcut("))

        assertTrue(take.contains("readShortcutRequest(intent, shortcutTokens, restored)"), "the token is checked in one place")
        assertTrue(take.contains("if (!restored) pendingShortcut.offer(request)"), "a recreation keeps the pending request")
        // The extras are never read in the activity itself.
        for (extra in listOf("EXTRA_SHORTCUT_ACTION", "EXTRA_HOST_ID", "EXTRA_TUNNEL_ID", "EXTRA_SHORTCUT_TOKEN")) {
            assertFalse(main.contains(extra), "$extra is read by readShortcutRequest only")
        }
    }

    @Test
    fun `the pending shortcut is held by the app, expires on start, and is spent through the holder`() {
        val main = main

        assertTrue(main.contains("@Inject lateinit var pendingShortcut: PendingShortcut"), "held by the app, not by the activity")
        assertFalse(main.contains("mutableStateOf<ShortcutRequest"), "no copy of the request in the activity")
        val onStart = bodyOf(main, "override fun onStart(")
        assertTrue(onStart.contains("pendingShortcut.dropExpired()"), "a request left on the lock screen is forgotten")
        assertTrue(onStart.contains("shortcutUpdater.republishIfRefused()"), "a refused publication is retried on screen")
        assertTrue(squeeze(main).contains("onShortcutConsumed = { consumed -> pendingShortcut.consume(consumed) }"), "NavGraph spends through the holder")
        assertTrue(main.contains("pendingShortcut.request.collectAsState()"), "NavGraph follows the holder")
    }

    // ── NavGraph: where a shortcut acts, and how ──────────────────────────────

    @Test
    fun `the shortcut acts in one effect, and only if the holder says it was spent there`() {
        val navGraph = navGraph
        // The lock is a key of the effect: the shortcut that waited behind the lock screen acts when it goes.
        val effect = squeeze(bodyOf(navGraph, "LaunchedEffect(pendingShortcut, tunnelResumePending, currentRoute, locked, wipeClosed)"))

        assertTrue(effect.contains("decideShortcut(request, tunnelResumePending, currentRoute, locked, wipeClosed)"), "the decision is the pure function")
        val spent = effect.indexOf("onShortcutConsumed(request)")
        assertTrue(spent >= 0, "the effect spends the request")
        assertTrue(effect.contains("if (spent && step is ShortcutStep.Open)"), "and acts only if this call spent it")
        assertTrue(effect.indexOf("openShortcut(") > spent, "after it was spent")
        assertEquals(2, Regex("""\.openShortcut\(""").findAll(navGraph).count(), "the definition and one call site")
    }

    @Test
    fun `the unlock does not act on a shortcut, it only leads to the list`() {
        val unlocked = bodyOf(navGraph, "onUnlocked = ")

        assertFalse(unlocked.contains("pendingShortcut"))
        assertFalse(unlocked.contains("onShortcutConsumed"))
        assertFalse(unlocked.contains("openShortcut"))
    }

    @Test
    fun `a host shortcut reuses the terminal screen instead of stacking a second one`() {
        val open = squeeze(bodyOf(navGraph, "private fun NavHostController.openShortcut("))
        val host = open.substringAfter("is ShortcutRequest.ConnectHost ->").substringBefore("is ShortcutRequest.StartTunnel")

        assertTrue(host.contains("navigate(Routes.Sessions.createRoute(request.hostId)) {"), "to the terminal of the host")
        val block = host.substringAfter("navigate(Routes.Sessions.createRoute(request.hostId)) {")
        assertTrue(block.contains("popUpTo(Routes.Sessions.route) { inclusive = false }"), "back to the terminal that is open")
        assertTrue(block.contains("launchSingleTop = true"), "and its entry, hence its ViewModel, is kept")
        assertTrue(host.contains("!isOnBackStack(Routes.Sessions.route) && !isOnBackStack(Routes.HostList.route)"), "the host list only if there is nothing under")
    }

    @Test
    fun `opening the terminal reuses the terminal screen like a host shortcut, without a host`() {
        val open = squeeze(bodyOf(navGraph, "private fun NavHostController.openShortcut("))
        val terminal = open.substringAfter("is ShortcutRequest.OpenTerminal ->")

        assertTrue(terminal.contains("!isOnBackStack(Routes.Sessions.route) && !isOnBackStack(Routes.HostList.route)"), "the host list only if there is nothing under")
        assertTrue(terminal.contains("navigate(Routes.Sessions.createRoute()) {"), "to the terminal, with no host to connect to")
        val block = terminal.substringAfter("navigate(Routes.Sessions.createRoute()) {")
        assertTrue(block.contains("popUpTo(Routes.Sessions.route) { inclusive = false }"), "back to the terminal that is open")
        assertTrue(block.contains("launchSingleTop = true"), "and its entry is kept")
        assertFalse(terminal.contains("hostId"), "the request has no target")
    }

    @Test
    fun `the notification of the terminal service reaches the activity through the checked filiere, immutable`() {
        val service = squeeze(
            code("service/TerminalSessionService.kt")
                .substringAfter("private fun contentIntent()")
                .substringBefore("private fun closeAllIntent()"),
        )

        assertTrue(
            service.contains("ShortcutUpdater.openTerminalIntent(Intent(this, MainActivity::class.java), shortcutTokens.token())"),
            "the token of the install, in the one intent the reader accepts",
        )
        assertTrue(service.contains("PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT"), "no other app fills the intent in")
        assertTrue(service.contains("CONTENT_REQUEST_CODE"), "a request code of its own: the identity of a PendingIntent ignores the extras")
    }

    @Test
    fun `a tunnel shortcut opens one fresh tunnel list, popping like a tab and never restoring`() {
        val open = squeeze(bodyOf(navGraph, "private fun NavHostController.openShortcut("))
        val tunnel = open.substringAfter("is ShortcutRequest.StartTunnel ->")
        val tabStyle = tunnel.substringAfter("TunnelShortcutNavigation.TAB_STYLE ->").substringBefore("TunnelShortcutNavigation.ON_TOP ->")
        val onTop = tunnel.substringAfter("TunnelShortcutNavigation.ON_TOP ->")

        // The way in is decided by the pure function, from the route on screen and the one under it.
        assertTrue(
            tunnel.contains("when (tunnelShortcutNavigation(current, previousBackStackEntry?.destination?.route))"),
            "the shape of the back stack decides",
        )
        // Like the Tunnels tab, on the shape a tab press leaves: the same pop, so that the Hosts tab finds the saved states as a tab leaves them.
        assertTrue(tabStyle.contains("navigate(route) {"), "to the tunnel list asked for")
        assertTrue(tabStyle.contains("popUpTo(Routes.HostList.route) { saveState = true }"), "popping up to the host list, saving")
        assertTrue(tabStyle.contains("launchSingleTop = true"))
        // A restored list would keep its old arguments and its start flag: the tunnel would not start.
        assertFalse(open.contains("restoreState"), "a shortcut never restores a saved list")
        // The list on top goes unsaved, so no stale copy is left behind. Never popped to by route: a list below the host list would empty the stack.
        assertTrue(tabStyle.contains("if (isTunnelListRoute(current)) popBackStack()"))
        assertTrue(tabStyle.indexOf("popBackStack()") < tabStyle.indexOf("navigate(route)"), "dropped before the new one")
        assertFalse(tabStyle.contains("popBackStack(Routes.TunnelList.route"))
        // Anywhere else it goes on top and saves nothing: a terminal under it must not be parked.
        assertTrue(onTop.contains("navigate(route) { popUpTo(Routes.TunnelList.route) { inclusive = true } }"))
        assertFalse(onTop.contains("saveState"), "nothing popped there is saved")
    }

    @Test
    fun `the restart of tunnels pops like a tab only on the shape of a tab press, and goes on top saving nothing elsewhere`() {
        val resumeEffect = squeeze(bodyOf(navGraph, "LaunchedEffect(tunnelResumePending, currentRoute, locked)"))

        assertTrue(
            resumeEffect.contains(
                "resumeNavigation( resumePending = tunnelResumePending, currentRoute = currentRoute, " +
                    "previousRoute = navController.previousBackStackEntry?.destination?.route, locked = locked, )",
            ),
            "the decision is the pure function, from the route on screen and the one under it",
        )
        val tabStyle = resumeEffect.substringAfter("ResumeNavigation.TAB_STYLE ->").substringBefore("ResumeNavigation.ON_TOP ->")
        val onTop = resumeEffect.substringAfter("ResumeNavigation.ON_TOP ->")
        // On the shape of a tab press, the options of the Tunnels tab: it opens a list without a tunnel, a restored one is right.
        assertTrue(tabStyle.contains("popUpTo(Routes.HostList.route) { saveState = true }"))
        assertTrue(tabStyle.contains("launchSingleTop = true"))
        assertTrue(tabStyle.contains("restoreState = true"))
        // Elsewhere nothing is popped, so nothing is saved: a terminal under the list is not parked.
        assertTrue(onTop.contains("navController.navigate(Routes.TunnelList.createRoute()) { launchSingleTop = true }"))
        assertFalse(onTop.contains("popUpTo"))
        assertFalse(onTop.contains("saveState"))
        assertFalse(onTop.contains("restoreState"))
    }

    @Test
    fun `the Hosts tab pops back to the host list instead of restoring a saved stack on top of it`() {
        val navGraph = navGraph
        val bar = squeeze(navGraph.substringAfter("NextShBottomBar(").substringBefore("destinations = bottomNavDestinations"))

        assertTrue(
            bar.contains(
                "bottomBarAction( target = route, currentRoute = currentRoute, " +
                    "previousRoute = navController.previousBackStackEntry?.destination?.route, " +
                    "hostListOnBackStack = navController.isOnBackStack(Routes.HostList.route), )",
            ),
            "the decision is the pure function, from the route on screen and the one under it",
        )
        assertTrue(bar.contains("BottomBarAction.STAY -> Unit"), "the tab on screen does nothing")
        // Directly on the host list a tab is saved, as a tab press does; with a screen between, it is closed as Back would.
        assertTrue(
            bar.contains("BottomBarAction.POP_TO_HOST_LIST_SAVING -> navController.popBackStack(Routes.HostList.route, inclusive = false, saveState = true)"),
            "popped to, saving the tab",
        )
        assertTrue(
            bar.contains("BottomBarAction.POP_TO_HOST_LIST_CLOSING -> navController.popBackStack(Routes.HostList.route, inclusive = false, saveState = false)"),
            "popped to, saving nothing",
        )
        // Without the host list on the back stack, it is put there without restoring anything.
        val show = bar.substringAfter("BottomBarAction.SHOW_HOST_LIST ->").substringBefore("BottomBarAction.NAVIGATE,")
        assertTrue(show.contains("navController.navigate(Routes.HostList.route) { launchSingleTop = true }"))
        assertFalse(show.contains("restoreState"), "a stale or null mapping of the host list must not be restored")
        // The other tabs keep the options that restore their state; saving the tab on screen is the
        // only thing that depends on the screens between it and the host list.
        val navigate = bar.substringAfter("BottomBarAction.NAVIGATE, BottomBarAction.NAVIGATE_CLOSING -> {")
        assertTrue(navigate.contains("saveState = action == BottomBarAction.NAVIGATE"), "closed, not saved, when NAVIGATE_CLOSING")
        assertTrue(navigate.contains("popUpTo(Routes.HostList.route) {"))
        assertTrue(navigate.contains("restoreState = true"))
    }

    @Test
    fun `the tunnel list starts its tunnel once per entry, through the saved state of the entry`() {
        val navGraph = navGraph
        val helper = squeeze(bodyOf(navGraph, "private fun oneShotStartTunnelId("))

        assertTrue(helper.contains("remember(entry.id)"), "decided once while the entry is composed")
        assertTrue(helper.contains("tunnelToStartOnOpen("), "through the pure decision")
        assertTrue(helper.contains("handle.get<Boolean>(START_TUNNEL_HANDLED_KEY) == true"), "the flag is read from the SavedStateHandle")
        assertTrue(helper.contains("handle[START_TUNNEL_HANDLED_KEY] = true"), "and set once the start was handed to the screen")
        assertTrue(helper.contains("val handle = entry.savedStateHandle"), "the handle the back stack saves and restores with the entry")
        // The destination takes its tunnel from the helper, never from the argument directly.
        val destination = squeeze(navGraph).substringAfter("composable( route = Routes.TunnelList.route,")
            .substringBefore("TunnelListScreen(")
        assertTrue(destination.contains("oneShotStartTunnelId(backStackEntry)"))
        assertEquals(1, Regex("""getString\("startTunnelId"\)""").findAll(navGraph).count(), "the argument is read in the helper only")
    }

    @Test
    fun `the back stack is looked at without the restricted currentBackStack`() {
        val navGraph = navGraph

        // Not currentBackStackEntryAsState, which is public: the bare property.
        assertFalse(Regex("""\bcurrentBackStack\b""").containsMatchIn(navGraph), "RestrictedApi outside androidx")
        assertTrue(bodyOf(navGraph, "private fun NavHostController.isOnBackStack(").contains("getBackStackEntry(route)"))
    }

    @Test
    fun `the browser overlay is put away before a shortcut or a restart of tunnels navigates`() {
        val navGraph = navGraph
        val shortcutEffect = bodyOf(navGraph, "LaunchedEffect(pendingShortcut, tunnelResumePending, currentRoute, locked, wipeClosed)")
        val resumeEffect = bodyOf(navGraph, "LaunchedEffect(tunnelResumePending, currentRoute, locked)")

        assertTrue(shortcutEffect.indexOf("hideBrowserOverlay()") in 0 until shortcutEffect.indexOf("openShortcut("))
        assertTrue(resumeEffect.indexOf("hideBrowserOverlay()") in 0 until resumeEffect.indexOf("navigate("))
        assertTrue(squeeze(bodyOf(navGraph, "val hideBrowserOverlay = ")).contains("browserVm.sessionHolder.hide()"))
    }
}
