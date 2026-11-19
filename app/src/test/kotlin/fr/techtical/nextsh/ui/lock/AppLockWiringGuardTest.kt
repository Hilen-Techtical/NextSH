// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.lock

import fr.techtical.nextsh.core.logging.blankComments
import fr.techtical.nextsh.testutil.ProjectFiles
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

/**
 * Smoke alarm on the wiring of the lock, the part the unit tests cannot run: a lifecycle observer in
 * an Activity, a dialog window in a composition, the Hilt binding of the visibility. What can be run
 * is run (`AppLockTest`, `NavGraphLockTest`, `VaultUnlockViewModelTest`, `UnlockedAppVisibility` in
 * `AppVisibilityTest`); this checks that the files still wire those pieces the way the design needs.
 *
 * Same compromise and same rule as `BiometricPromptWiringGuardTest`: a cheap source check. A source
 * that cannot be found FAILS the test (see [ProjectFiles]).
 */
class AppLockWiringGuardTest {

    /** The code of a main source file of the app, comments blanked. */
    private fun code(path: String): String = blankComments(ProjectFiles.mainSource(path).readText())

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

    // ── MainActivity: the lifecycle tells the lock ────────────────────────────

    @Test
    fun `the activity tells the lock when the app comes back, before anything else reacts`() {
        val main = code("MainActivity.kt")
        val onStart = bodyOf(main, "override fun onStart(")

        // Only the first started instance is a return: the tracker says so (0 to 1), a relaunch is not.
        assertTrue(squeeze(onStart).contains("if (visibilityTracker.onActivityStarted()) appLock.onAppShown()"))
        assertEquals(1, Regex("""appLock\.onAppShown\(\)""").findAll(main).count())
    }

    @Test
    fun `the activity tells the lock when the app leaves, for the last instance only and never for a rotation`() {
        val main = code("MainActivity.kt")
        val onStop = squeeze(bodyOf(main, "override fun onStop("))

        assertTrue(
            onStop.contains("val leftTheForeground = visibilityTracker.onActivityStopped(changingConfigurations = isChangingConfigurations)"),
            "the tracker says whether it was the last instance (1 to 0)",
        )
        assertTrue(onStop.contains("if (leftTheForeground && !isChangingConfigurations) {"), "a rotation does not start the clock")
        assertTrue(
            onStop.contains("appLock.onAppHidden(ownerPromptOpen = biometricPromptCoordinator.openDialog() is VaultAuthRequest)"),
            "with the vault dialog up, the screen of the phone code stops the activity without the owner leaving",
        )
        assertEquals(1, Regex("""appLock\.onAppHidden\(""").findAll(main).count())
    }

    @Test
    fun `a new activity in a live process locks, a recreation and a relaunch of the instance do not`() {
        val main = code("MainActivity.kt")
        val onCreate = squeeze(bodyOf(main, "override fun onCreate("))
        val onNewIntent = bodyOf(main, "override fun onNewIntent(")

        assertTrue(onCreate.contains("if (savedInstanceState == null) appLock.lock()"), "no saved state: a new activity")
        assertFalse(onNewIntent.contains("appLock"), "the relaunch of the instance that is up does not lock")
        assertEquals(1, Regex("""appLock\.lock\(\)""").findAll(main).count())
    }

    @Test
    fun `the activity hands the lock to the navigation, and creates the lock screen window itself, last`() {
        val main = squeeze(code("MainActivity.kt"))

        assertTrue(main.contains("@Inject lateinit var appLock: AppLock"))
        assertTrue(main.contains("val locked by appLock.locked.collectAsState()"))
        assertTrue(main.contains("locked = locked,"), "NavGraph gets the lock")
        assertTrue(main.contains("onLockScreenNeededChanged = { lockScreenNeeded = it },"), "NavGraph says whether the window is needed")
        // A dialog window is above the ones created before it: the lock screen comes after the hosts of the prompts.
        val lockScreen = main.indexOf("if (lockScreenNeeded) AppLockScreen()")
        assertTrue(lockScreen >= 0, "created from the state NavGraph reports")
        for (before in listOf("NavGraph(", "HostKeyPromptDialog(", "Fido2PromptHost(", "BiometricPromptHost(")) {
            assertTrue(main.indexOf(before) in 0 until lockScreen, "$before comes before the lock screen")
        }
        assertEquals(1, Regex("""AppLockScreen\(\)""").findAll(main).count())
    }

    // ── NavGraph: what it says about the lock screen ──────────────────────────

    @Test
    fun `the navigation does not create the lock screen, it says whether it is needed, and hides the content until it is up`() {
        val navGraph = squeeze(code("ui/navigation/NavGraph.kt"))

        assertFalse(navGraph.contains("AppLockScreen()"), "the window is created by MainActivity, after the prompts")
        assertTrue(navGraph.contains("val lockScreenNeeded = shouldShowLockScreen(locked, onboardingCompleted, currentRoute)"))
        assertTrue(navGraph.contains("SideEffect { onLockScreenNeededChanged(lockScreenNeeded) }"))
        // The layer: opaque, over the browser overlay, takes every touch.
        val cover = navGraph.substringAfter("if (shouldCoverContent(locked, onboardingCompleted, currentRoute)) {")
        assertTrue(cover.startsWith(" Box("), "a layer is drawn")
        assertTrue(cover.substringBefore("LOCK_COVER_Z_INDEX = ").contains(".zIndex(LOCK_COVER_Z_INDEX)"))
        assertTrue(cover.contains(".background(NearBlack)"), "opaque")
        assertTrue(cover.contains("awaitEachGesture {"), "a gesture handler")
        assertTrue(cover.contains("awaitFirstDown(requireUnconsumed = false).consume()"), "that takes the first touch")
        assertTrue(cover.contains("event.changes.forEach { it.consume() }"), "and every event that follows, down to the last finger up")
        assertTrue(navGraph.indexOf("BrowserOverlay(") < navGraph.indexOf("if (shouldCoverContent("), "above the overlay of the browser")
    }

    @Test
    fun `the lock has no default value, a caller that forgot it would leave the app open`() {
        val navGraph = squeeze(code("ui/navigation/NavGraph.kt"))

        assertFalse(navGraph.contains("locked: Boolean = false"))
        assertTrue(Regex("""locked: Boolean,""").findAll(navGraph).count() >= 4, "NavGraph and the three pure decisions")
        assertTrue(navGraph.contains("locked = locked,"), "the browser overlay is given it")
    }

    @Test
    fun `the dialogs of the tunnel browser wait for the unlock, their windows would be over the lock screen`() {
        val browser = code("ui/browser/TunnelBrowserScreen.kt")

        assertTrue(browser.contains("locked: Boolean,"), "no default")
        assertTrue(browser.contains("if (uiState.showExternalNavWarning && !locked) {"), "the warning of the external navigation")
        assertTrue(browser.contains("if (authPrompt != null && isVisible && !locked) {"), "the HTTP authentication challenge")
        assertEquals(2, Regex("""!locked""").findAll(browser).count())
    }

    @Test
    fun `the replace dialog of the SFTP browser waits for the unlock, its window comes up after the answer of a server`() {
        val browser = code("ui/sftp/SftpBrowserScreen.kt")

        assertTrue(browser.contains("locked: Boolean,"), "no default")
        assertFalse(browser.contains("locked: Boolean ="), "no default")
        assertTrue(squeeze(browser).contains("if (uploadConflict != null && !locked) {"), "the dialog of the replacement")
        assertEquals(1, Regex("""!locked""").findAll(browser).count())
        // The state of the conflict stays in the view model: the dialog is back at the unlock.
        assertTrue(squeeze(browser).contains("val uploadConflict = uiState.uploadConflict"))
    }

    @Test
    fun `the two ways to the SFTP browser give it the lock, the full screen one from the navigation, the pane of the split from the local`() {
        val navGraph = squeeze(code("ui/navigation/NavGraph.kt"))
        assertTrue(
            navGraph.contains(
                "SftpBrowserScreen( sessionId = sessionId, hostLabel = hostLabel, onBack = { navController.popBackStack() }, locked = locked, )",
            ),
            "the route of the full screen browser gives it the lock",
        )

        val embedded = squeeze(code("ui/sftp/EmbeddedSftpBrowser.kt"))
        assertTrue(embedded.contains("locked = LocalAppLocked.current,"), "the pane of the split reads it from the local")

        // The local is provided by the navigation for everything it hosts, from the same value as the rest.
        assertTrue(navGraph.contains("CompositionLocalProvider(LocalAppLocked provides locked) {"), "NavGraph provides the local")
        val local = squeeze(code("ui/lock/LocalAppLocked.kt"))
        assertTrue(local.contains("compositionLocalOf<Boolean> { error("), "no default value: read outside NavGraph it fails loudly")
        assertFalse(Regex("""compositionLocalOf(?:<Boolean>)?\s*\{\s*(?:false|true)""").containsMatchIn(local), "a silent default leaves the dialog over the lock screen")
    }

    @Test
    fun `the lock reaches the three decisions of the navigation`() {
        val navGraph = squeeze(code("ui/navigation/NavGraph.kt"))

        assertTrue(navGraph.contains("decideShortcut(request, tunnelResumePending, currentRoute, locked, wipeClosed)"))
        assertTrue(navGraph.contains("locked = locked, )"), "resumeNavigation gets it")
        assertTrue(navGraph.contains("LaunchedEffect(tunnelResumePending, currentRoute, locked)"), "and the effect runs again at the unlock")
        assertTrue(navGraph.contains("LaunchedEffect(pendingShortcut, tunnelResumePending, currentRoute, locked, wipeClosed)"))
    }

    // ── The lock screen: a window of its own ──────────────────────────────────

    @Test
    fun `the lock screen is a full screen dialog window that Back and a tap outside cannot dismiss`() {
        val screen = squeeze(code("ui/lock/AppLockScreen.kt"))

        assertTrue(screen.contains("Dialog("), "a window of its own, so it also covers the dialogs and sheets that are open")
        assertTrue(screen.contains("dismissOnBackPress = true"), "Back reaches onDismissRequest")
        assertTrue(screen.contains("dismissOnClickOutside = false"))
        assertTrue(screen.contains("usePlatformDefaultWidth = false"), "full width")
        assertTrue(screen.contains("decorFitsSystemWindows = false"), "under the system bars too")
        assertTrue(screen.contains("onDismissRequest = { activity?.moveTaskToBack(true) }"), "Back leaves the app, it never uncovers the content")
    }

    @Test
    fun `the lock screen only goes away with an unlock, and never touches the secure flag`() {
        val screen = code("ui/lock/AppLockScreen.kt")

        // The window inherits the secure flag of the activity (ScreenCapturePolicy): the default securePolicy.
        for (forbidden in listOf("FLAG_SECURE", "clearFlags", "securePolicy", "ScreenCapturePolicy", "finish(")) {
            assertFalse(screen.contains(forbidden), "$forbidden")
        }
        assertFalse(Regex("""appLock|AppLock\b""").containsMatchIn(screen.replace("AppLockScreen", "")), "it does not unlock by itself: the view model does, after the vault is open")
    }

    @Test
    fun `the panel of the lock screen is the one of the unlock screen of the launch`() {
        val lock = code("ui/lock/AppLockScreen.kt")
        val unlock = code("ui/vault/VaultUnlockScreen.kt")

        assertTrue(lock.contains("VaultUnlockPanel("))
        assertTrue(unlock.contains("internal fun VaultUnlockPanel("))
        assertEquals(2, Regex("""VaultUnlockPanel\(""").findAll(unlock).count(), "defined and used by the screen of the launch")
        assertTrue(lock.contains("hiltViewModel()") || lock.contains("VaultUnlockViewModel = hiltViewModel()"))
    }

    // ── AppLock and the visibility ────────────────────────────────────────────

    @Test
    fun `the lock counts on the elapsed real time of the device, which deep sleep does not stop`() {
        val lock = code("core/lock/AppLock.kt")

        assertTrue(lock.contains("SystemClock.elapsedRealtime()"))
        assertFalse(lock.contains("System.nanoTime"), "the monotonic clock of the JVM stops in deep sleep")
        assertFalse(lock.contains("currentTimeMillis"), "and the wall clock can be set back")
        assertTrue(lock.contains("@Volatile"), "read from the lifecycle callback, written from the settings collector")
    }

    @Test
    fun `the lock logs fixed texts only`() {
        val lock = code("core/lock/AppLock.kt")

        val logs = Regex("""Timber\.\w+\(([^)]*)\)""").findAll(lock).map { it.groupValues[1].trim() }.toSet()
        assertEquals(setOf("\"App locked\"", "\"App unlocked\""), logs)
    }

    @Test
    fun `the prompts see the app through the lock, the app hands out no other visibility`() {
        val visibility = squeeze(code("core/AppVisibility.kt"))

        assertTrue(visibility.contains("abstract fun bindAppVisibility(impl: UnlockedAppVisibility): AppVisibility"))
        assertTrue(visibility.contains("override fun isVisible(): Boolean = tracker.isVisible() && !appLock.isLocked()"))
    }

    @Test
    fun `the application keeps the delay of the settings in the lock`() {
        val app = squeeze(code("App.kt"))
        // The chain is written on several lines: its dots are glued back.
        val follow = squeeze(bodyOf(code("App.kt"), "private fun followAutoLockDelay(")).replace(" .", ".")

        assertTrue(app.contains("followAutoLockDelay()"), "started at onCreate")
        assertTrue(follow.contains("settingsDataStore.settingsFlow.map { it.autoLockDelay }.distinctUntilChanged().collect { appLock.setAutoLockDelay(it) }"))
    }

    // ── The unlock steps ──────────────────────────────────────────────────────

    @Test
    fun `the vault is opened before the app is unlocked, in the view model of the unlock`() {
        val viewModel = code("ui/vault/VaultUnlockViewModel.kt")
        val open = bodyOf(viewModel, "private suspend fun openVaultAndUnlock(")

        assertTrue(open.indexOf("vaultManager.open()") in 0 until open.indexOf("appLock.unlock()"), "open first")
        assertTrue(open.contains("withContext(ioDispatcher)"), "off the main thread")
        assertTrue(viewModel.contains("biometricPrompts.authenticateVault(VaultAuthPurpose.UNLOCK)"))
    }

    @Test
    fun `the screens of the unlock never show the text of the system dialog nor of an exception`() {
        for (path in listOf("ui/vault/VaultUnlockViewModel.kt", "ui/vault/VaultUnlockScreen.kt", "ui/lock/AppLockScreen.kt", "ui/onboarding/OnboardingViewModel.kt")) {
            val code = code(path)
            assertFalse(code.contains("errString"), "$path: the text of the system dialog")
            assertFalse(Regex("""\be\.message\b|\.localizedMessage""").containsMatchIn(code), "$path: what an exception says")
        }
    }

    @Test
    fun `no one shows a vault prompt of its own, the three former callers ask the coordinator`() {
        assertTrue(code("ui/vault/VaultUnlockViewModel.kt").contains("authenticateVault("))
        assertTrue(code("ui/onboarding/OnboardingViewModel.kt").contains("authenticateVault("))
        assertTrue(code("ui/hosts/HostViewModel.kt").contains("authenticateVault(VaultAuthPurpose.CONFIRM_SAVE)"))
    }

    // ── The places that lock on the spot ──────────────────────────────────────

    @Test
    fun `a connection and an enrollment that meet an expired vault lock the app on the spot`() {
        val connect = bodyOf(code("domain/usecase/ConnectSessionUseCase.kt"), "private fun lockAndReportVaultExpired(")
        val enrollment = squeeze(code("ui/sync/EnrollmentScanViewModel.kt"))

        assertTrue(connect.contains("appLock.lock()"))
        assertTrue(enrollment.contains("appLock.lock() _state.value = ScannedEnrollment.Error(EnrollmentError.VaultLocked)"))
    }

    @Test
    fun `the host save keeps its own retry and does not lock the app, but waits for the unlock to ask for the owner`() {
        val hosts = code("ui/hosts/HostViewModel.kt")
        val confirm = squeeze(bodyOf(hosts, "fun confirmIdentityAndSave("))

        assertFalse(hosts.contains("appLock.lock("))
        assertTrue(confirm.contains("if (appLock.isLocked()) return"), "the request waits, intact, under the lock screen")
        assertTrue(confirm.indexOf("if (_savePhase.value != HostSavePhase.AWAITING_REAUTH) return") < confirm.indexOf("if (appLock.isLocked()) return"))
        assertTrue(confirm.indexOf("if (appLock.isLocked()) return") < confirm.indexOf("activePrompt = prompt"), "before the request is consumed")

        val screen = squeeze(code("ui/hosts/HostDetailScreen.kt"))
        assertTrue(
            screen.contains("combine(viewModel.savePhase, viewModel.appLocked) { phase, locked -> phase == HostSavePhase.AWAITING_REAUTH && !locked }"),
            "the screen asks when the request is there and the lock is not",
        )
        assertTrue(screen.contains("if (reauthToAsk) viewModel.confirmIdentityAndSave()"))
    }

    @Test
    fun `the prompts that are up when the app locks end, from a collector of the lock started with the application`() {
        val app = squeeze(code("App.kt"))
        val ending = squeeze(code("core/lock/PromptsEndedByLock.kt"))

        assertTrue(app.contains("promptsEndedByLock.start(appScope)"))
        assertTrue(ending.contains("appLock.locked.filter { it }.collect { endPrompts() }"), "each time the app goes to locked")
        val end = squeeze(bodyOf(code("core/lock/PromptsEndedByLock.kt"), "internal fun endPrompts("))
        assertTrue(end.contains("hostKeys.endForAbsence()") && end.contains("securityKeys.endForAbsence()") && end.contains("fingerprints.endSignaturesForAbsence()"))
        // The vault request is the unlock itself: only the signature prompts of the coordinator end.
        assertFalse(end.contains("authenticateVault"))
    }

    @Test
    fun `a vault dialog that ends while the app is away starts the clock, from a collector started with the application`() {
        val app = squeeze(code("App.kt"))
        val away = squeeze(code("core/lock/OwnerPromptEndedAway.kt"))
        val coordinator = squeeze(code("core/ssh/BiometricPromptCoordinator.kt"))

        assertTrue(app.contains("ownerPromptEndedAway.start(appScope)"))
        assertTrue(away.contains("fingerprints.vaultRequestEnded.collect { onVaultRequestEnded() }"))
        // The visibility of the tracker, not the one of the prompts: it holds through a rotation, and does not depend on the lock.
        assertTrue(away.contains("if (!tracker.isVisible()) appLock.onAppHidden()"))
        assertTrue(coordinator.contains("if (request is VaultAuthRequest) _vaultRequestEnded.tryEmit(Unit)"), "said for a vault request only, when it retires")
    }

    @Test
    fun `the lock screen asks the phone again on each resume, and its panel scrolls under the system bars`() {
        val screen = squeeze(code("ui/lock/AppLockScreen.kt"))
        val panel = squeeze(code("ui/vault/VaultUnlockScreen.kt"))

        assertTrue(screen.contains("lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { viewModel.refresh() }"))
        assertTrue(panel.contains(".windowInsetsPadding(WindowInsets.systemBars)"), "in the window of the lock screen nothing else carries the bars")
        assertTrue(panel.contains(".verticalScroll(rememberScrollState())"), "the button and the error stay reachable in landscape")
    }

    // ── The settings ──────────────────────────────────────────────────────────

    @Test
    fun `the settings offer the delay and a lock now button in the security section`() {
        val screen = squeeze(code("ui/settings/SettingsScreen.kt"))
        val security = screen.substringAfter("FormSection(title = stringResource(R.string.section_security)) {").substringBefore("FormSection(title = stringResource(R.string.section_danger_zone)")

        assertTrue(security.contains("viewModel.updateAutoLockDelay(snapped)"))
        assertTrue(security.contains("AutoLockDelay.entries"))
        assertTrue(security.contains("onClick = { viewModel.lockNow() }"))
        assertTrue(security.contains("R.string.label_clipboard_expiry"), "next to the expiry of the clipboard")
        assertTrue(security.contains("R.string.action_lock_now"))
    }
}
