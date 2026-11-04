// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.TriangleAlert
import com.sun.jna.CallbackReference
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinUser.WindowProc
import dev.datlag.kcef.KCEF
import fr.techtical.nextsh.desktop.components.ConfirmDeleteDialog
import fr.techtical.nextsh.desktop.components.SidebarTab
import fr.techtical.nextsh.desktop.core.diagnostics.StartupTrace
import fr.techtical.nextsh.desktop.core.security.WindowSecurityState
import fr.techtical.nextsh.desktop.data.di.DesktopContainer
import fr.techtical.nextsh.desktop.data.preferences.DesktopSettingsStore
import fr.techtical.nextsh.desktop.data.preferences.LanguagePref
import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.quit_confirm_body
import fr.techtical.nextsh.desktop.generated.resources.quit_confirm_button
import fr.techtical.nextsh.desktop.generated.resources.quit_confirm_title
import fr.techtical.nextsh.desktop.navigation.Screen
import fr.techtical.nextsh.desktop.sessions.HostPickerDialog
import fr.techtical.nextsh.desktop.sessions.SnippetPickerDialog
import fr.techtical.nextsh.desktop.window.WindowsDarkTitleBar
import fr.techtical.nextsh.desktop.window.WindowsFirstFrameReveal
import fr.techtical.nextsh.desktop.window.WindowsRestoreBounds
import fr.techtical.nextsh.desktop.window.paintBackdrop
import fr.techtical.nextsh.desktop.window.screenFor
import fr.techtical.nextsh.desktop.window.usableScreenBounds
import fr.techtical.nextsh.shared.domain.model.TunnelStatus
import fr.techtical.nextsh.shared.util.Logger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import net.i2p.crypto.eddsa.EdDSASecurityProvider
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.jetbrains.compose.resources.stringResource
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Rectangle
import java.awt.event.KeyEvent
import java.security.Security
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

fun main() {
    // **Force Skiko OpenGL renderer on Windows.** Must be set BEFORE any
    // Skiko / Compose class is loaded, otherwise it has no effect (Skiko
    // reads the property once at its first class-init).
    //
    // Why: Skiko's default Windows pipeline is DirectX 12 with a swap
    // chain created via `CreateSwapChainForComposition` and
    // `DXGI_SCALING_STRETCH` (hard-coded in skiko/.../directXRedrawer.cc).
    // During a live edge-resize, DWM composes the window faster than
    // Skia re-renders, so the swap chain stretches the previous
    // back-buffer to fill the new bounds, and the newly exposed edge
    // pixels are rendered with alpha 0, which DWM paints as WHITE
    // (not transparent and not the AWT `window.background`). Result:
    // a ~16 ms white flash on the side being resized (left, right,
    // bottom: the top is the OS title bar, no Skia involvement).
    //
    // OpenGL does NOT use DXGI swap-chain stretching: the new pixels
    // simply expose `window.background` (NearBlack) until Skia paints
    // them. The performance difference vs DirectX 12 is imperceptible
    // on the NextSH UI (no heavy animations, no Compose blur, no
    // particle effects).
    //
    // Refs:
    //   - https://github.com/JetBrains/compose-multiplatform/issues/2925
    //     (officially recommended workaround by JetBrains / MatkovIvan)
    //   - https://github.com/JetBrains/skiko/blob/master/skiko/src/awtMain/cpp/windows/directXRedrawer.cc
    //     (root cause in upstream Skiko)
    //   - https://github.com/JetBrains/compose-multiplatform/issues/4521
    //     (m-sasha confirms the swap-chain stretch is the cause)
    if (System.getProperty("os.name", "").lowercase().contains("windows")) {
        System.setProperty("skiko.renderApi", "OPENGL")
    }

    // Startup tracing: see [StartupTrace]. Always println to stdout; tees
    // to `~/.nextsh/startup.log` when `NEXTSH_STARTUP_LOG=1` so packaged
    // MSI installs without a console can still be profiled. Call sites in
    // VaultPinManager, DatabaseFactory, App.kt, LanSyncServerLifecycle and
    // TlsCertificateManager extend the timeline past `application{}`.
    StartupTrace.mark("entered main()")

    // Splash is shown by the JVM ITSELF via the `-splash:` flag (see
    // `application.jvmArgs` in desktop/build.gradle.kts): the PNG is
    // displayed before `main()` is even invoked, so the user sees the
    // brand mark within ~100 ms of double-clicking the .exe. An earlier
    // attempt to draw the splash from a `JWindow` constructed here had
    // an unacceptable 6 s cold-load penalty on the first run after MSI
    // install (Toolkit + Swing + ImageIO class graph all paged in from
    // disk). The JVM splash sidesteps that entirely because the native
    // launcher decodes the PNG before the JVM proper starts up.
    //
    // The splash is closed by [closeJvmSplash], from the main `Window {}`
    // block (see below): once the window is revealed after its first frame
    // when it is hidden until then (Windows, [WindowsFirstFrameReveal]),
    // otherwise at the end of its setup effect. Note that AWT closes the
    // splash by itself when the first window is shown (`Window.show()`, just
    // before the native show), which no code of ours can defer: when the
    // window is hidden until its first frame, that comes BEFORE the reveal,
    // so the explicit close there is then a traced no-op.
    // Touching `java.awt.SplashScreen` is the first reference to the
    // `java.desktop` module from our code, and that triggers the
    // transitive class load of the whole AWT/Swing/ImageIO graph
    // synchronously on this thread (~2 s on cold first install,
    // ~80 ms warm). The two marks around the call isolate that cost
    // from anything before/after so we can confirm where the time
    // is actually spent.
    StartupTrace.mark("about to touch java.desktop (getSplashScreen)")
    val jvmSplashActive = java.awt.SplashScreen.getSplashScreen() != null
    StartupTrace.mark("JVM splash active = $jvmSplashActive (java.desktop loaded)")

    // Crypto provider registration runs on a background thread so the
    // main thread proceeds straight to Compose init. BouncyCastle's
    // class-loading + JCE registration costs 100-500 ms on first run:
    // that delay used to happen BEFORE the window appeared. By moving
    // it to a background thread, the user sees the window ~200-500 ms
    // sooner. Vault unlock uses SunJCE (PBKDF2WithHmacSHA256 +
    // AES/GCM/NoPadding) NOT BouncyCastle: only SSH operations need
    // BC / i2p EdDSA, and those happen seconds after the user starts
    // typing their PIN, by which time the providers are ready.
    Thread({
        val cryptoT0 = System.nanoTime()
        if (Security.getProvider("BC") == null) {
            Security.addProvider(BouncyCastleProvider())
        }
        // SSHJ 0.38.0 expects Ed25519 keys to be `net.i2p.crypto.eddsa.EdDSAPublicKey`
        // instances: JDK 17+ native Ed25519 returns
        // `sun.security.ec.ed.EdDSAPublicKeyImpl` which fails to cast in
        // SSHJ's buffer encoder. Registering the i2p provider and
        // generating through it keeps all Ed25519 key objects on the
        // expected type.
        if (Security.getProvider(EdDSASecurityProvider.PROVIDER_NAME) == null) {
            Security.addProvider(EdDSASecurityProvider())
        }
        val cryptoMs = (System.nanoTime() - cryptoT0) / 1_000_000
        StartupTrace.async("crypto providers registered", cryptoMs)
    }, "nextsh-crypto-init").apply { isDaemon = false }.start()
    StartupTrace.mark("crypto-init thread launched (async)")

    // Apply UI locale before any Compose resource is resolved. compose-resources
    // uses Locale.getDefault() at composition time, so this must run before
    // application { }. Changes take effect on the next app restart. The Settings
    // UI documents this with a hint message.
    when (DesktopContainer.settingsStore.settings.value.languagePreference) {
        LanguagePref.FR -> Locale.setDefault(Locale.FRENCH)
        LanguagePref.EN -> Locale.setDefault(Locale.ENGLISH)
        LanguagePref.SYSTEM -> Unit // keep JVM default
    }
    StartupTrace.mark("locale applied")

    // Dispose Chromium natives even on forced JVM exit: crash, kill -9, Task Manager.
    // Post-application dispose below only runs on normal close.
    Runtime.getRuntime().addShutdownHook(Thread { KCEF.disposeBlocking() })

    // KCEF (Chromium) is deferred until the first browser-tunnel activation
    // (see `DesktopBrowserOverlay.onActivateKcef`). Eager init at startup
    // cost 800ms-2s of cold launch even for users who never open an
    // in-app browser: KCEF.init is suspending and downloads up to ~200 MB
    // of native binaries on first run. Lazy init pushes that to whenever
    // the user opens the first LOCAL_FORWARD tunnel; subsequent opens are
    // instant thanks to the on-disk cache.

    // Compute initial window geometry from persisted prefs. The app always
    // starts maximized; the floating geometry matters as the size the OS
    // restores to AND as the screen it maximizes onto (Windows maximizes a
    // window on the screen that holds its initial bounds). It is the user's
    // last floating position + size, or, when nothing usable was saved (first
    // launch, corrupt prefs, or an old maximized-sized value written by
    // earlier builds), a default size capped at 80 % of the usable area and
    // centered. Both the 80 % cap and the centering refer to the LAUNCH
    // screen: the one the saved geometry sits on, so the app reopens where it
    // was left, or the primary screen when the prefs are unset or that screen
    // is gone. See [chooseLaunchScreen], [resolveInitialWindowState] and
    // [DesktopSettingsStore.WindowGeometry].
    val settingsStore = DesktopContainer.settingsStore
    val savedGeometry = settingsStore.settings.value.windowGeometry
    val primaryBounds = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
    val launchScreen = chooseLaunchScreen(savedGeometry, usableScreenBounds(), primaryBounds)
    val initial = resolveInitialWindowState(savedGeometry, launchScreen)

    // Bounds the window is CREATED with (see [openingGeometry]). On Windows
    // that is the whole usable area of the launch screen, not the floating
    // geometry: the window is maximized right away, and opening it at a
    // smaller size makes Windows show it small and enlarge it a few dozen
    // milliseconds later, before Compose has drawn a frame (a blank flash).
    // The floating geometry is then installed as the restore rectangle once
    // the window is maximized: see [WindowsRestoreBounds] and the effect in
    // the `Window` block. Other platforms get the floating geometry as is.
    val fixRestoreBounds = initial.maximized && WindowsRestoreBounds.isPlatformSupported
    val opening = openingGeometry(initial, launchScreen, openAtUsableArea = fixRestoreBounds)
    StartupTrace.mark("geometry resolved → entering application{}")

    application {
        remember {
            StartupTrace.mark("application{} body composing")
        }
        LaunchedEffect(Unit) {
            StartupTrace.mark("application{} first composition (post-frame)")
        }

        // ── Window visibility (minimize-to-tray-on-close) ────────────────────
        // Drives the `Window(visible = ...)` parameter below. IMPORTANT: the
        // `Window` composable itself must NEVER be wrapped in `if (windowVisible)`:
        // pulling it out of composition would tear down its AWT peer, the
        // whole content subtree, and every live SSH/SFTP session rendered in
        // it. `visible = false` only hides the OS window; `appScope` coroutines
        // (SSH sessions, tunnels, sync) are untouched and keep running while
        // the app lives in the system tray. See [DesktopSettingsStore.Settings.minimizeToTrayOnClose].
        var windowVisible by remember { mutableStateOf(true) }

        // OS-decorated window: the title bar drag, edge resize, snap
        // layouts (Win+Arrow / Win11 fly-out), AeroSnap, Alt+Space
        // window menu, and accessibility are ALL handled natively by the
        // OS. Earlier builds used `undecorated = true` plus a
        // Compose-rendered custom title bar plus AWT mouse-listener
        // workarounds for drag/resize (`TitleBarDragController`,
        // `WindowEdgeResize`). Multiple visual bugs persisted across
        // CMP 1.6.11 and 1.7.3 (border-disappears-on-resize,
        // tremor-during-drag, white edge during resize) and the
        // workaround surface was growing: see
        // `~/.claude/projects/.../project_compose_terminal_migration.md`
        // for the analogous JediTerm Swing → Compose Canvas migration
        // that triggered this same architectural simplification.
        //
        // The OS title bar is themed via JNA `DwmSetWindowAttribute`
        // (see [WindowsDarkTitleBar]) so it visually continues the
        // NearBlack Compose brand strip rendered immediately below it.
        val windowState: WindowState = rememberWindowState(
            placement = if (initial.maximized) WindowPlacement.Maximized else WindowPlacement.Floating,
            position = opening.position,
            size = opening.size,
        )

        // ── Geometry persistence ──────────────────────────────────────────
        // With OS-decorated window the system itself drives the move /
        // resize / placement transitions, and Compose updates
        // `windowState` in response (one-way AWT → Compose). There is no
        // feedback loop and no tremor.
        //
        // BUT `windowState.position` / `.size` are NOT a floating-geometry
        // memory: Compose rewrites them on every AWT componentMoved /
        // componentResized, so while the window is maximized they hold the
        // maximized bounds (usable area + the invisible 7 px frame on each
        // side, e.g. -7,-7 1550×830). Persisting them as-is made the next
        // launch hand that huge size to the OS as its "restore" rectangle.
        // We therefore remember the last FLOATING geometry ourselves, only
        // updated while `placement == Floating` (see [nextFloatingGeometry]),
        // and persist that instead.
        var lastFloating by remember {
            mutableStateOf(FloatingGeometry(initial.floatingPosition, initial.floatingSize))
        }
        LaunchedEffect(windowState) {
            // The AWT events of one native transition (move, resize,
            // maximize) reach Compose as separate state writes, so a sample
            // taken between them can pair `Floating` with half-updated
            // values (e.g. the maximized position while the placement has not
            // flipped yet). `collectLatest` + `delay` is a debounce: only a
            // state that stays unchanged for [FLOATING_SETTLE_MS] is trusted.
            // A minimized window is skipped (Windows parks it at -32000,
            // -32000 while `placement` stays Floating).
            snapshotFlow {
                if (windowState.isMinimized) {
                    null
                } else {
                    Triple(windowState.placement, windowState.position, windowState.size)
                }
            }.collectLatest { sample ->
                if (sample == null) return@collectLatest
                delay(FLOATING_SETTLE_MS)
                val (placement, position, size) = sample
                lastFloating = nextFloatingGeometry(lastFloating, placement, position, size)
            }
        }

        // Persisted on every placement transition and once on close. The
        // maximized flag is read live; the geometry is [lastFloating], so a
        // write can never replace the floating size with the maximized one.
        // A crash between a drag-end and the next write loses that last
        // adjustment, an accepted trade-off given how narrow that interval is.
        //
        // One live value is still consulted: the screen the window is on NOW.
        // [lastFloating] only follows moves made while floating, so a
        // maximized window sent to another screen (Win+Shift+Arrow) would
        // leave it on the old one and the app would reopen at the wrong
        // place. [relocateFloatingToScreen] moves the geometry that gets
        // WRITTEN onto the window's screen. [lastFloating] itself is left
        // alone on purpose: the relocation is a projection recomputed at every
        // write from the live window, and Windows carries the restore
        // rectangle along with a maximized window, so un-maximizing makes the
        // tracker above observe the real floating bounds anyway.
        val persistFromWindow: () -> Unit = {
            val floating = relocateFloatingToScreen(
                floating = lastFloating.toRectangle(),
                windowRect = liveWindowRect(windowState.isMinimized, windowState.position, windowState.size),
                screens = usableScreenBounds(),
            )
            persistGeometry(
                settingsStore,
                WindowSnapshot(
                    floating = floating.toFloatingGeometry(),
                    maximized = windowState.placement == WindowPlacement.Maximized,
                ),
            )
        }

        // ── Window-level shortcut state (Cmd+K, Ctrl+Shift+S, tab mgmt) ──────
        // We capture these via the global AWT [KeyEventDispatcher] below rather
        // than a `Modifier.onPreviewKeyEvent` deeper in the tree so the
        // shortcuts work regardless of which Compose subtree currently has
        // focus (sidebar click, post-session-close limbo, etc.) AND even while
        // the heavyweight JediTerm SwingPanel owns focus. The Modifier-based
        // and even the Window-level `onPreviewKeyEvent` approaches failed: the
        // former only delivers preview events along the focus chain (which can
        // be a detached subtree right after a SwingPanel is disposed), and the
        // latter wrongly fired over the focused terminal (closing the tab
        // mid-typing in e.g. nano). The AWT dispatcher is the one proven path.
        //
        // **Startup perf**: the heavy DI accesses (CmdKViewModel,
        // SessionManager, SnippetRepository → Database) are wrapped in
        // [lazy] / deferred to the `if (snippetPickerOpen)` branch so
        // their construction is paid AFTER the first window paint, not
        // during the cold path while the user is still seeing nothing.
        var cmdKOpen by remember { mutableStateOf(false) }
        var snippetPickerOpen by remember { mutableStateOf(false) }
        // Ctrl+Shift+T host picker, hoisted at Window level so the shortcut
        // works regardless of which Compose subtree has focus (it is driven by
        // the global AWT dispatcher below, so it fires even over the terminal).
        var newTabPickerOpen by remember { mutableStateOf(false) }
        val cmdKViewModelLazy = remember { lazy { DesktopContainer.cmdKViewModel } }
        // Same DI instance SessionTabsScreen uses; lazy so the heavy session-
        // manager graph is paid AFTER the first window paint (mirrors cmdK).
        val sessionManagerLazy = remember { lazy { DesktopContainer.sessionManager } }
        val currentScreen by DesktopContainer.navigator.current.collectAsState()

        // Compose's `onPreviewKeyEvent` does NOT reliably see keystrokes when
        // a heavyweight AWT focus owner (the JediTerm `SwingPanel`) owns
        // focus. We register a global AWT [KeyEventDispatcher] that runs BEFORE
        // the focus owner so Ctrl+Shift+K (Cmd+K palette), Ctrl+Shift+S and the tab-management
        // shortcuts work even when the terminal has focus.
        val currentScreenRef = rememberUpdatedState(currentScreen)
        DisposableEffect(Unit) {
            val dispatcher = KeyEventDispatcher { ev ->
                if (ev.id != KeyEvent.KEY_PRESSED) return@KeyEventDispatcher false
                val vaultLocked = currentScreenRef.value is Screen.VaultUnlock ||
                    currentScreenRef.value is Screen.FirstLaunch
                if (vaultLocked) return@KeyEventDispatcher false
                // The shortcuts help window (ShortcutsWindow) is a modal
                // reference card in intent: global shortcuts must not act
                // on the main window behind it while it's open (e.g.
                // Ctrl+Shift+W silently closing a tab underneath it).
                if (DesktopContainer.shortcutsWindowVisible.value) return@KeyEventDispatcher false
                val ctrl = (ev.modifiersEx and KeyEvent.CTRL_DOWN_MASK) != 0
                val shift = (ev.modifiersEx and KeyEvent.SHIFT_DOWN_MASK) != 0
                when {
                    ctrl && shift && ev.keyCode == KeyEvent.VK_K -> {
                        if (!cmdKOpen) {
                            cmdKViewModelLazy.value.reset()
                            cmdKOpen = true
                        }
                        true
                    }
                    ctrl && shift && ev.keyCode == KeyEvent.VK_S -> {
                        if (!snippetPickerOpen) snippetPickerOpen = true
                        true
                    }
                    // ── Tab-management shortcuts ───────────────────────────
                    // ALL registered GLOBALLY (here, not in a Compose
                    // onPreviewKeyEvent) so they fire even while the JediTerm
                    // SwingPanel has focus. We use Ctrl+Shift combos (and
                    // Ctrl+Tab) precisely because terminals never deliver them
                    // to the remote shell, so capturing them globally never
                    // steals a keystroke the user meant for the terminal.
                    // (An earlier Ctrl+T / Ctrl+W scheme via the Compose
                    // Window onPreviewKeyEvent was buggy: the window-level
                    // preview pass DID fire over the focused terminal, so
                    // Ctrl+W closed the tab mid-typing in e.g. nano.)
                    // Gated to the Sessions screen so they're inert elsewhere.

                    // Ctrl+Shift+T → open the host picker for a NEW tab.
                    ctrl && shift && ev.keyCode == KeyEvent.VK_T &&
                        currentScreenRef.value is Screen.Sessions -> {
                        if (!newTabPickerOpen) newTabPickerOpen = true
                        true
                    }
                    // Ctrl+Shift+W → close the active tab.
                    ctrl && shift && ev.keyCode == KeyEvent.VK_W &&
                        currentScreenRef.value is Screen.Sessions -> {
                        sessionManagerLazy.value.activeTabId.value?.let {
                            sessionManagerLazy.value.closeSession(it)
                        }
                        true
                    }
                    // Ctrl+Tab → next tab, Ctrl+Shift+Tab → previous tab.
                    // Wrap-around both directions; matches Windows Terminal.
                    ctrl && ev.keyCode == KeyEvent.VK_TAB &&
                        currentScreenRef.value is Screen.Sessions -> {
                        if (shift) sessionManagerLazy.value.selectPreviousTab()
                        else sessionManagerLazy.value.selectNextTab()
                        true
                    }
                    // Ctrl+Shift+N → next TERMINAL pane, Ctrl+Shift+P →
                    // previous, within the active tab's pane tree. Same DFS
                    // wrap-around as selectNextTab/selectPreviousTab (see
                    // [DesktopSessionManager.focusNextPane] / [focusPreviousPane])
                    // and gated to Sessions like every other tab/pane shortcut
                    // above. Panes only exist inside session tabs. SFTP panes
                    // are skipped: they never take terminal keyboard focus, so
                    // stopping on one moved the ring without moving the caret.
                    // Both are documented in the shortcuts help window (see
                    // [ShortcutsWindow]): no longer exposed as palette
                    // commands (removed in favor of that help window, see
                    // ALL_COMMANDS in CmdKViewModel.kt).
                    ctrl && shift && ev.keyCode == KeyEvent.VK_N &&
                        currentScreenRef.value is Screen.Sessions -> {
                        sessionManagerLazy.value.focusNextPane()
                        true
                    }
                    ctrl && shift && ev.keyCode == KeyEvent.VK_P &&
                        currentScreenRef.value is Screen.Sessions -> {
                        sessionManagerLazy.value.focusPreviousPane()
                        true
                    }
                    // Ctrl+Shift+B → toggle broadcast ("synchronize-panes")
                    // on the active tab: fans commands/keystrokes out to
                    // every connected Terminal pane it contains. No-op inside
                    // the manager if fewer than 2 panes are connected: see
                    // [DesktopSessionManager.toggleBroadcastOnActiveTab].
                    // Gated to Sessions like every other tab/pane shortcut.
                    // Also documented in the shortcuts help window (see
                    // [ShortcutsWindow]): no longer exposed as a palette
                    // command.
                    ctrl && shift && ev.keyCode == KeyEvent.VK_B &&
                        currentScreenRef.value is Screen.Sessions -> {
                        sessionManagerLazy.value.toggleBroadcastOnActiveTab()
                        true
                    }
                    // ── Sidebar navigation shortcuts ───────────────────────
                    // Ctrl+Shift+1..5 → the 5 primary nav-rail tabs, Ctrl+Shift+,
                    // → Settings. NOT gated to Screen.Sessions (unlike the
                    // tab/pane shortcuts above): these move BETWEEN screens,
                    // so they must work from every unlocked screen, same as
                    // Ctrl+Shift+K. The `vaultLocked` early-return at the top
                    // of this dispatcher already excludes VaultUnlock /
                    // FirstLaunch, so no extra gate is needed here either.
                    // Mirrors [SidebarTab.entries] order (HOSTS, SESSIONS,
                    // TUNNELS, TRANSFERS, VAULT) and reuses App.kt's
                    // screenForTab so the shortcut and the sidebar click both
                    // resolve to the exact same [Screen].
                    ctrl && shift && ev.keyCode == KeyEvent.VK_1 -> {
                        DesktopContainer.navigator.navigate(screenForTab(SidebarTab.HOSTS))
                        true
                    }
                    ctrl && shift && ev.keyCode == KeyEvent.VK_2 -> {
                        DesktopContainer.navigator.navigate(screenForTab(SidebarTab.SESSIONS))
                        true
                    }
                    ctrl && shift && ev.keyCode == KeyEvent.VK_3 -> {
                        DesktopContainer.navigator.navigate(screenForTab(SidebarTab.TUNNELS))
                        true
                    }
                    ctrl && shift && ev.keyCode == KeyEvent.VK_4 -> {
                        DesktopContainer.navigator.navigate(screenForTab(SidebarTab.TRANSFERS))
                        true
                    }
                    ctrl && shift && ev.keyCode == KeyEvent.VK_5 -> {
                        DesktopContainer.navigator.navigate(screenForTab(SidebarTab.VAULT))
                        true
                    }
                    ctrl && shift && ev.keyCode == KeyEvent.VK_COMMA -> {
                        DesktopContainer.navigator.navigate(screenForTab(SidebarTab.SETTINGS))
                        true
                    }
                    else -> false
                }
            }
            val kfm = KeyboardFocusManager.getCurrentKeyboardFocusManager()
            kfm.addKeyEventDispatcher(dispatcher)
            onDispose { kfm.removeKeyEventDispatcher(dispatcher) }
        }

        // Single REAL exit path: persists geometry, shuts down container,
        // exits the JVM. Never called directly by a user gesture when
        // something would be lost: both entry points (the window's close
        // button X and the tray "Quit" menu item) go through
        // [requestQuitWithConfirmation] first, which only reaches this lambda
        // when there is nothing active, or once the user confirmed via the
        // "Quitter quand même" button of the quit-confirm dialog below.
        //
        // The window is hidden right after the geometry is saved and BEFORE
        // the teardown, so the user never sees it (see [runExitSequence]).
        // `closeApp` is defined outside the `Window {}` block, hence the
        // reference kept in [mainWindowRef].
        val mainWindowRef = remember { AtomicReference<java.awt.Window?>(null) }
        val closeApp = {
            persistFromWindow()
            runExitSequence(
                hide = { mainWindowRef.get()?.let(::hideForExit) },
                shutdown = { DesktopContainer.shutdown() },
                exit = { exitApplication() },
            )
        }

        // ── Installer-driven shutdown (MSI upgrade / uninstall) ──────────────
        // Entry point for [InstallerCloseWatch]: goes STRAIGHT to the real
        // exit path above: no minimize-to-tray, no confirm dialog, neither
        // of which an unattended installer can answer.
        //
        // It is a separate entry point (rather than `requestClose`) because
        // of an ordering race. Within ONE `util:CloseApplication` row WiX
        // posts WM_CLOSE FIRST and WM_QUERYENDSESSION only after, so the
        // WINDOW_CLOSING born of that WM_CLOSE can already be queued on the
        // EDT (and hit `requestClose` with the latch still un-armed) by
        // the time the WndProc hook sees the end-session message. Our own
        // `main.wxs` avoids that by splitting the messages across
        // `Sequence`-ordered rows (end-session first), so the latch is
        // normally armed before any WM_CLOSE; this lambda is what keeps the
        // reversed order safe anyway. The hook posts it with `invokeLater`,
        // which lands after any queued WINDOW_CLOSING (EDT event queue is
        // FIFO), so a hide-to-tray that just happened is immediately
        // followed by the clean exit.
        //
        // `AtomicBoolean` guard: both paths can fire for a single installer
        // close, and tearing the container down twice would run the JVM
        // exit / vault shutdown concurrently with itself.
        val installerCloseDone = remember { AtomicBoolean(false) }
        val currentCloseApp by rememberUpdatedState(closeApp)
        val closeForInstaller = {
            if (installerCloseDone.compareAndSet(false, true)) {
                Logger.d("InstallerCloseWatch", "installer close: clean shutdown")
                currentCloseApp()
            }
        }

        // ── OS close button (X): minimize-to-tray / confirm / direct exit ───
        // Three-way decision, computed fresh on every click (see
        // [resolveCloseAction] for the pure logic, unit-tested):
        //  1. "Minimize to tray on close" is ON and the tray icon is actually
        //     registered → just hide the window (`windowVisible = false`),
        //     no shutdown at all. Sessions/tunnels keep running.
        //  2. Otherwise, if any SSH session or tunnel is currently active →
        //     ask for confirmation (counts shown in the dialog) before
        //     tearing everything down.
        //  3. Otherwise → close immediately, nothing to lose.
        var quitConfirmOpen by remember { mutableStateOf(false) }
        var quitConfirmSessionCount by remember { mutableStateOf(0) }
        var quitConfirmTunnelCount by remember { mutableStateOf(0) }

        // What the dialog calls "sessions SSH actives" = live shells, i.e.
        // Terminal panes in Connected state across every tab, NOT `tabs.size`,
        // which counts SFTP tabs (they piggyback on a terminal's SSH client)
        // and tabs sitting on a dead / connecting terminal, while missing the
        // extra shells of a split tab. See
        // [DesktopSessionManager.connectedTerminalPaneCount].
        val countActiveSessions = { DesktopContainer.sessionManager.connectedTerminalPaneCount() }
        val countActiveTunnels = {
            DesktopContainer.tunnelService.tunnelStates.value.values.count {
                it.status == TunnelStatus.ACTIVE || it.status == TunnelStatus.RECONNECTING
            }
        }

        // Confirm-then-quit, WITHOUT the minimize-to-tray branch: something
        // active → show the dialog, nothing active → exit straight away.
        // Shared by the close button (via [requestClose]) and the tray "Quit"
        // menu item, so both destroy live sessions only after an explicit yes.
        val requestQuitWithConfirmation = {
            val activeSessions = countActiveSessions()
            val activeTunnels = countActiveTunnels()
            if (activeSessions > 0 || activeTunnels > 0) {
                quitConfirmSessionCount = activeSessions
                quitConfirmTunnelCount = activeTunnels
                quitConfirmOpen = true
            } else {
                closeApp()
            }
        }

        val requestClose = {
            when (
                resolveCloseAction(
                    minimizeToTrayOnClose = settingsStore.settings.value.minimizeToTrayOnClose,
                    trayRegistered = DesktopContainer.tunnelTray.isRegistered,
                    activeSessionCount = countActiveSessions(),
                    activeTunnelCount = countActiveTunnels(),
                    closingForInstaller = InstallerCloseWatch.closingForInstaller,
                )
            ) {
                CloseAction.HIDE_TO_TRAY -> windowVisible = false
                // CONFIRM_QUIT and CLOSE_DIRECT differ only by whether
                // anything is active: exactly the test
                // [requestQuitWithConfirmation] re-runs, so both land there.
                CloseAction.CONFIRM_QUIT, CloseAction.CLOSE_DIRECT -> requestQuitWithConfirmation()
                // The OS/Restart Manager (MSI upgrade, uninstall) is ending
                // our session: no dialog, no tray, straight to a clean
                // shutdown. An unattended installer cannot answer a modal
                // confirmation, and a process left alive in the tray keeps
                // every installed file locked, which is exactly the
                // "already running" upgrade failure this exists to avoid.
                CloseAction.FORCE_CLOSE -> closeForInstaller()
            }
        }

        Window(
            onCloseRequest = requestClose,
            title = "NextSH",
            // The icon feeds the taskbar button and Alt+Tab. It must stay
            // (without it Java shows its coffee cup there), but
            // [WindowsDarkTitleBar] hides it in the title bar itself.
            icon = painterResource("images/nextsh_logo.png"),
            state = windowState,
            undecorated = false,
            visible = windowVisible,
        ) {
            // Lets `closeApp`, defined outside this block, hide the window
            // before the teardown (see [runExitSequence]).
            DisposableEffect(window) {
                mainWindowRef.set(window)
                onDispose { mainWindowRef.compareAndSet(window, null) }
            }

            // `true` when the window is now hidden from DWM until its first
            // frame (Windows only, see [WindowsFirstFrameReveal]): then the
            // reveal effect below MUST run, and it owns closing the splash.
            val windowCloaked = remember {
                StartupTrace.mark("Window{} content composing")

                // **AWT backdrop = NearBlack, set BEFORE the first show.**
                // This composition runs while the window is displayable but
                // still hidden (its background is still the default light
                // grey, 240/240/240), so the backdrop is in place for the
                // very first paint. Whenever
                // the OS resizes the window (the maximize right after the
                // first show, an edge drag later), the newly exposed pixels
                // show the AWT/Swing default light grey (seen as white)
                // until Compose's Skia canvas repaints. With NearBlack as
                // backdrop there is no visible flash even if Compose lags.
                // Native Win32 apps draw synchronously (Direct2D/GDI) and
                // have no such gap; Compose goes Skia -> OpenGL/DirectX. The
                // frame and every component below it (the panes Swing paints
                // under the canvas, and the Skia canvas itself if it already
                // exists) are all set, since any of them can show through.
                paintBackdrop(window)

                // Windows only: hide the window from DWM until Compose has
                // presented its first frame. The backdrop above only covers
                // what Swing paints; the first show still flashed a blank
                // surface before the first Skia/OpenGL frame. Kept LAST in
                // this block so nothing can throw between the cloak and the
                // effect that lifts it (see the reveal effect below, which
                // lifts it on every path).
                WindowsFirstFrameReveal.cloak(window).also { cloaked ->
                    StartupTrace.mark("window cloaked until first frame = $cloaked")
                }
            }

            // Windows only: once the window is showing and maximized, install
            // the floating geometry as the rectangle it restores to. The window
            // was created at the usable area of its screen so that it opens
            // without a visible resize (see `opening` in `main`), which left
            // the OS with the wrong restore rectangle. Polls until the window
            // is on screen (this effect starts BEFORE the first show) and
            // applies once; the target is read at that moment. See
            // [WindowsRestoreBounds.applyWhenMaximized].
            if (fixRestoreBounds) {
                LaunchedEffect(Unit) {
                    WindowsRestoreBounds.applyWhenMaximized(
                        window = window,
                        refLogical = launchScreen,
                        target = { lastFloating.toRectangle() },
                    )
                }
            }

            // Lifts the DWM cloak set in the `remember` above, once the window
            // is showing AND Compose has rendered its first frame (or after
            // the safety delay, whichever comes first), then closes the JVM
            // splash if AWT has not already (see [closeJvmSplash]). The cloak
            // is lifted on every path, including failure and cancellation, so
            // the window can never stay invisible; it is set once at startup
            // and never again, so the tray re-show (`window.isVisible = true`)
            // is unaffected. `withFrameNanos` is the frame clock of this
            // window: it resumes when Compose starts a frame, hence the short
            // settle delay inside the helper. When the window was not cloaked,
            // nothing happens here and the setup effect below closes the
            // splash as before.
            LaunchedEffect(Unit) {
                WindowsFirstFrameReveal.revealAfterFirstFrame(
                    window = window,
                    cloaked = windowCloaked,
                    awaitFrame = { withFrameNanos { } },
                    onRevealed = {
                        if (windowCloaked) {
                            StartupTrace.mark("window revealed (cloak lifted)")
                            closeJvmSplash()
                        }
                    },
                    trace = StartupTrace::mark,
                )
            }

            // Theme the OS title bar to NextSH dark + NearBlack (Win 11
            // 22H2+ for caption color; Win 10 18985+ for dark mode only:
            // see [WindowsDarkTitleBar]). The HWND already exists here
            // because the window is displayable; this effect runs BEFORE the
            // first show, not after it.
            LaunchedEffect(Unit) {
                StartupTrace.mark("Window post-show LaunchedEffect entered")
                try {
                    WindowsDarkTitleBar.apply(window)

                    // Arms the WM_QUERYENDSESSION watcher so an MSI upgrade /
                    // uninstall (Restart Manager) can close us cleanly instead
                    // of hitting minimize-to-tray or a confirmation dialog it
                    // cannot answer. The callback is posted on the EDT by the
                    // hook itself and wins the race against the WM_CLOSE that
                    // arrives first: see [InstallerCloseWatch] and
                    // `closeForInstaller` above.
                    InstallerCloseWatch.install(window, onInstallerClose = closeForInstaller)

                    // Minimum window size: guarantees grid-of-cards screens
                    // (Tunnels, Vault, Hosts...) stay legible. 1024 × 600 is the
                    // tablet/laptop floor we document in the README.
                    window.minimumSize = Dimension(1024, 600)

                    // Register the main AWT window for the hide-from-screen-capture
                    // feature; on Windows toggles SetWindowDisplayAffinity.
                    //
                    // Applied unconditionally (not only when the setting is ON) so
                    // WindowSecurityState.hideFromCapture is seeded from the
                    // persisted setting rather than left on its default: every
                    // secondary window reads that flow to protect its own HWND
                    // (see window/WindowCaptureProtection.kt), so a stale `false`
                    // there would silently leak dialog content into captures.
                    // WDA_NONE is the OS default, so the ON→OFF call is a no-op.
                    WindowSecurityState.registerMainWindow(window)
                    WindowSecurityState.apply(
                        DesktopContainer.settingsStore.settings.value.hideFromScreenCapture
                    )

                    // System clipboard FlavorListener + tray icon: both AWT-
                    // hosted, must wait until the window peer is live.
                    DesktopContainer.clipboardManager.start()
                    DesktopContainer.tunnelTray.start(
                        onOpen = {
                            // Handles BOTH cases the tray icon click can recover
                            // from: the window merely minimized (isMinimized),
                            // and the window hidden entirely via
                            // "minimize to tray on close" (windowVisible = false).
                            // `window.isVisible` is set directly (not just via
                            // the `windowVisible` state) so the window reappears
                            // immediately rather than waiting on the next
                            // recomposition to apply `visible = windowVisible`.
                            windowVisible = true
                            windowState.isMinimized = false
                            window.isVisible = true
                            window.toFront()
                            window.requestFocus()
                        },
                        onQuit = {
                            // The tray "Quit" is most often clicked precisely when
                            // the window is hidden in the tray: the one moment the
                            // user cannot see what they are about to destroy. So we
                            // bring the window back FIRST (the confirm dialog is
                            // rendered inside it and would otherwise be invisible),
                            // then run the exact same confirmation flow as the
                            // window's close button. Nothing active → exits
                            // immediately, no flash of a pointless dialog.
                            windowVisible = true
                            windowState.isMinimized = false
                            window.isVisible = true
                            window.toFront()
                            window.requestFocus()
                            requestQuitWithConfirmation()
                        },
                    )
                    StartupTrace.mark("post-show LaunchedEffect done (titlebar/tray/clipboard)")
                } finally {
                    // Window NOT cloaked (not Windows, or the cloak was
                    // refused): there is no reveal to wait for, so the JVM
                    // splash closes here, once the window is themed and set
                    // up, as before. Never skipped, even if the setup above
                    // throws. A cloaked window leaves it to the reveal effect
                    // above, which closes it once the first frame is out.
                    if (!windowCloaked) closeJvmSplash()
                }
            }

            // Persist on every placement transition (floating ↔ maximized ↔
            // fullscreen) so the maximized flag is up to date; the geometry
            // written is [lastFloating], not the (possibly maximized) live
            // `windowState` bounds. Observing `placement` directly avoids
            // polling.
            LaunchedEffect(windowState.placement) {
                persistFromWindow()
            }

            App(
                cmdKOpen = cmdKOpen,
                onCmdKOpen = {
                    if (!cmdKOpen) {
                        cmdKViewModelLazy.value.reset()
                        cmdKOpen = true
                    }
                },
                onCmdKDismiss = { cmdKOpen = false },
                onOpenSnippetPicker = {
                    if (!snippetPickerOpen) snippetPickerOpen = true
                },
            )

            // Snippet picker: heavy DI accesses (SnippetRepository → Database
            // + SessionManager) deferred to the FIRST Ctrl+Shift+S press so
            // they don't block the cold-start window paint. The database
            // open + session manager init happen once on first picker open
            // (~100-500 ms), then are cached for subsequent opens.
            if (snippetPickerOpen) {
                val snippets by DesktopContainer.snippetRepository.observeAll()
                    .collectAsState(initial = emptyList())
                val sessionManager = remember { DesktopContainer.sessionManager }
                SnippetPickerDialog(
                    snippets = snippets,
                    activeHostId = sessionManager.activeTabHost()?.id,
                    onPick = { snippet ->
                        sessionManager.sendToActiveTerminal(snippet.command)
                        snippetPickerOpen = false
                    },
                    onDismiss = { snippetPickerOpen = false },
                )
            }

            // Focus restore: when the snippet picker closes (pick, Esc, X,
            // Cancel: every path flips `snippetPickerOpen` to false), hand
            // keyboard focus back to the active terminal. Otherwise Compose
            // restores focus to the last owner in the main window, e.g. the
            // TabBar snippet button, which re-activates on Enter and REOPENS
            // the picker instead of running the just-inserted command. The
            // LaunchedEffect body runs after the recomposition that removed
            // the DialogWindow (and disposed its AWT peer), so the epoch is
            // observed once the modal is gone. `sessionManagerLazy.value` is
            // safe here: the wasOpen guard means the picker already opened,
            // which initialised the session manager (startup-perf invariant
            // documented above stays intact).
            var snippetPickerWasOpen by remember { mutableStateOf(false) }
            LaunchedEffect(snippetPickerOpen) {
                if (snippetPickerOpen) {
                    snippetPickerWasOpen = true
                } else if (snippetPickerWasOpen) {
                    snippetPickerWasOpen = false
                    sessionManagerLazy.value.requestTerminalFocus()
                }
            }

            // Ctrl+Shift+T new-tab host picker: reuses the SAME
            // HostPickerDialog + openSession flow as the TabBar's "+" button
            // (SessionTabsScreen). Hoisted here so the keyboard shortcut works
            // regardless of which Compose subtree has focus.
            if (newTabPickerOpen) {
                val hosts by DesktopContainer.hostRepository.observeAll()
                    .collectAsState(initial = emptyList())
                val sessionManager = remember { DesktopContainer.sessionManager }
                HostPickerDialog(
                    hosts = hosts,
                    onPick = { host ->
                        newTabPickerOpen = false
                        sessionManager.openSession(host)
                    },
                    onDismiss = { newTabPickerOpen = false },
                )
            }

            // Same focus restore as the snippet picker above. Matters mostly
            // for Esc-dismiss: the pick path opens a new tab whose sessionId-
            // keyed focus effect grabs focus anyway (and wins, running after
            // this epoch bump targets the old terminal).
            var newTabPickerWasOpen by remember { mutableStateOf(false) }
            LaunchedEffect(newTabPickerOpen) {
                if (newTabPickerOpen) {
                    newTabPickerWasOpen = true
                } else if (newTabPickerWasOpen) {
                    newTabPickerWasOpen = false
                    sessionManagerLazy.value.requestTerminalFocus()
                }
            }

            // Quit confirmation: reached via `requestQuitWithConfirmation`
            // from either the window's close button (X, when minimize-to-tray
            // is off or the tray is unavailable) or the tray "Quit" menu item,
            // and only when at least one SSH session or tunnel is currently
            // active. Reuses
            // the shared destructive-confirmation dialog (same one used by
            // vault wipe / host delete elsewhere) rather than a bespoke
            // AlertDialog, so the quit prompt looks and behaves like every
            // other "are you sure" in the app.
            if (quitConfirmOpen) {
                ConfirmDeleteDialog(
                    title = stringResource(Res.string.quit_confirm_title),
                    message = stringResource(
                        Res.string.quit_confirm_body,
                        quitConfirmSessionCount,
                        quitConfirmTunnelCount,
                    ),
                    confirmLabel = stringResource(Res.string.quit_confirm_button),
                    icon = Lucide.TriangleAlert,
                    onConfirm = {
                        quitConfirmOpen = false
                        closeApp()
                    },
                    onDismiss = { quitConfirmOpen = false },
                )
            }
        }
    }

    // Dispose Chromium cleanly: prevents orphan processes on Windows.
    KCEF.disposeBlocking()
}

// ─────────────────────────────────────────────────────────────────────────────
// Window geometry persistence helpers
// ─────────────────────────────────────────────────────────────────────────────

// Minimum window size: also enforced by `window.minimumSize` (1024 × 600)
// in the LaunchedEffect further up. Validated on load so a corrupted prefs
// entry (e.g. saved 5×5 from a buggy earlier build) can never resurrect a
// "window invisible on un-maximize" state, and on live tracking so a
// transient tiny size (minimize animation) is never remembered.
internal const val MIN_FLOATING_WIDTH_DP = 1024
internal const val MIN_FLOATING_HEIGHT_DP = 600

/** Default floating size on a roomy screen, before the 80 % cap below. */
private const val DEFAULT_FLOATING_WIDTH_DP = 1280
private const val DEFAULT_FLOATING_HEIGHT_DP = 800

/** The default floating size never exceeds this share of the usable area. */
private const val DEFAULT_FLOATING_MAX_PERCENT = 80

/**
 * A size covering at least this share of the usable area (width OR height) is
 * treated as a maximized size, not as a real floating size: see [fillsScreen].
 */
private const val SAVED_SIZE_FILLS_SCREEN_PERCENT = 95

/**
 * How long the window state must stay unchanged before the floating geometry
 * is remembered: see the tracker in [main].
 */
private const val FLOATING_SETTLE_MS = 200L

/**
 * Last known FLOATING (neither maximized nor fullscreen) window geometry.
 * [position] is absolute by construction: a platform-default or aligned
 * position is never remembered.
 */
internal data class FloatingGeometry(
    val position: WindowPosition.Absolute,
    val size: DpSize,
)

/**
 * Pure update rule for the remembered floating geometry: returns the new
 * [FloatingGeometry] when the observed window state is a trustworthy floating
 * one, [current] otherwise.
 *
 * Compose rewrites `windowState.position` / `.size` from every AWT
 * componentMoved / componentResized, so they hold the maximized bounds while
 * the window is maximized. Only a [WindowPlacement.Floating] sample can
 * therefore carry floating geometry. A sample is also ignored when:
 *  - [position] is not [WindowPosition.Absolute] (platform default / aligned),
 *    or has an unspecified coordinate;
 *  - [size] is unspecified or below the window minimum
 *    ([MIN_FLOATING_WIDTH_DP] × [MIN_FLOATING_HEIGHT_DP], which is also what
 *    `window.minimumSize` enforces, so a smaller value is a transient state).
 */
internal fun nextFloatingGeometry(
    current: FloatingGeometry,
    placement: WindowPlacement,
    position: WindowPosition,
    size: DpSize,
): FloatingGeometry {
    if (placement != WindowPlacement.Floating) return current
    val absolute = position as? WindowPosition.Absolute ?: return current
    if (!absolute.x.isSpecified || !absolute.y.isSpecified) return current
    if (!size.isSpecified) return current
    if (size.width.value < MIN_FLOATING_WIDTH_DP || size.height.value < MIN_FLOATING_HEIGHT_DP) return current
    return FloatingGeometry(absolute, size)
}

/**
 * Snapshot of the values we persist between sessions: the remembered
 * [floating] geometry (see [nextFloatingGeometry]) and whether the window is
 * currently [maximized]. The live `windowState.position` / `.size` are
 * deliberately NOT part of it: Compose keeps them in sync with the real AWT
 * bounds, so while the window is maximized they describe the maximized
 * bounds, not the geometry to restore to.
 */
private data class WindowSnapshot(
    val floating: FloatingGeometry,
    val maximized: Boolean,
)

/**
 * Initial window state computed before `application { }` from saved prefs.
 * The window always starts maximized; [floatingPosition] / [floatingSize] are
 * the geometry the OS restores to on un-maximize, and the screen they sit on
 * is the one the window is maximized onto. When nothing usable was saved
 * (first launch, corrupt prefs, or a size that fills the screen), that is the
 * default size (see [resolveInitialWindowState]) centered on the launch
 * screen (see [chooseLaunchScreen]).
 */
internal data class InitialWindowState(
    val maximized: Boolean,
    val floatingPosition: WindowPosition.Absolute,
    val floatingSize: DpSize,
)

/**
 * Position and size the window is CREATED with (what `rememberWindowState`
 * receives). The window is maximized at once, and the OS only enlarges it
 * after showing it, so any creation size smaller than the final one makes a
 * visible resize on top of a not yet painted window. When
 * [openAtUsableArea] is true (Windows, where [WindowsRestoreBounds] installs
 * the real restore rectangle afterwards), the creation bounds are therefore
 * the whole usable area of [launchScreen], which is nearly the maximized
 * size. Otherwise they are the floating geometry of [initial], which the OS
 * uses as the restore rectangle directly.
 */
internal fun openingGeometry(
    initial: InitialWindowState,
    launchScreen: Rectangle,
    openAtUsableArea: Boolean,
): FloatingGeometry =
    if (openAtUsableArea) {
        FloatingGeometry(
            WindowPosition(launchScreen.x.dp, launchScreen.y.dp),
            DpSize(launchScreen.width.dp, launchScreen.height.dp),
        )
    } else {
        FloatingGeometry(initial.floatingPosition, initial.floatingSize)
    }

/**
 * Closes the JVM-rendered splash (`-splash:`, see `application.jvmArgs` in
 * desktop/build.gradle.kts) and traces what actually happened. Never throws,
 * and safe to call more than once.
 *
 * There is nothing to close when no `-splash:` flag was active (dev run),
 * and also when AWT got there first: `Window.show()` closes the splash on
 * the first window shown, just before the native show. The trace line says
 * which of the two it was, so it stays factual.
 */
private fun closeJvmSplash() {
    val closed = runCatching {
        val splash = java.awt.SplashScreen.getSplashScreen()
        splash?.close()
        splash != null
    }.getOrDefault(false)
    StartupTrace.mark(
        if (closed) "splash closed" else "splash already closed (AWT at first show) or absent",
    )
}

/**
 * Default floating size for [usableBounds]: 1280×800, capped at 80 % of the
 * usable area per dimension (integer division, so the result never exceeds
 * 80 %), raised to the window minimum, then capped at the usable area so the
 * window never overflows a screen smaller than the minimum.
 * E.g. a 1536×816 usable area gives 1228×652.
 */
internal fun defaultFloatingSize(usableBounds: Rectangle): DpSize {
    val width = minOf(DEFAULT_FLOATING_WIDTH_DP, usableBounds.width * DEFAULT_FLOATING_MAX_PERCENT / 100)
        .coerceAtLeast(MIN_FLOATING_WIDTH_DP)
        .coerceAtMost(usableBounds.width)
    val height = minOf(DEFAULT_FLOATING_HEIGHT_DP, usableBounds.height * DEFAULT_FLOATING_MAX_PERCENT / 100)
        .coerceAtLeast(MIN_FLOATING_HEIGHT_DP)
        .coerceAtMost(usableBounds.height)
    return DpSize(width.dp, height.dp)
}

/** Top-left corner that centers a window of [size] in [usableBounds]. */
internal fun centeredIn(usableBounds: Rectangle, size: DpSize): WindowPosition.Absolute =
    WindowPosition(
        (usableBounds.x + (usableBounds.width - size.width.value.toInt()) / 2).dp,
        (usableBounds.y + (usableBounds.height - size.height.value.toInt()) / 2).dp,
    )

/**
 * True when a [width] × [height] window covers (nearly) the whole of
 * [usableBounds]: width OR height >= 95 % of it, compared in integer percent.
 * Such a size is a maximized size, not a real floating one. Shared by the
 * launch repair ([resolveInitialWindowState]) and the move to another screen
 * ([relocateFloatingToScreen]), so both agree on what "fills the screen" means.
 */
internal fun fillsScreen(width: Int, height: Int, usableBounds: Rectangle): Boolean =
    width * 100 >= usableBounds.width * SAVED_SIZE_FILLS_SCREEN_PERCENT ||
        height * 100 >= usableBounds.height * SAVED_SIZE_FILLS_SCREEN_PERCENT

/**
 * The screen the app should open on: the one of [screens] holding most of the
 * saved floating rectangle ([screenFor]), so NextSH reopens where the user
 * left it. Falls back to [primary] (the usable area of the primary screen)
 * when:
 *  - no geometry was ever saved ([DesktopSettingsStore.WindowGeometry.isUnset]);
 *  - the saved rectangle overlaps no connected screen, e.g. the second screen
 *    was unplugged since: [resolveInitialWindowState] then recenters on
 *    [primary];
 *  - [screens] is empty (enumeration failed or headless).
 *
 * Pure: [screens] and [primary] come from the caller, no AWT global access.
 */
internal fun chooseLaunchScreen(
    saved: DesktopSettingsStore.WindowGeometry,
    screens: List<Rectangle>,
    primary: Rectangle,
): Rectangle {
    if (saved.isUnset) return primary
    return screenFor(Rectangle(saved.x, saved.y, saved.width, saved.height), screens) ?: primary
}

/** [FloatingGeometry] as a plain rectangle: 1 dp = 1 AWT unit, like in [persistGeometry]. */
private fun FloatingGeometry.toRectangle(): Rectangle = Rectangle(
    position.x.value.toInt(),
    position.y.value.toInt(),
    size.width.value.toInt(),
    size.height.value.toInt(),
)

private fun Rectangle.toFloatingGeometry(): FloatingGeometry =
    FloatingGeometry(WindowPosition(x.dp, y.dp), DpSize(width.dp, height.dp))

/**
 * Live bounds of the window, from the values Compose mirrors from AWT, or
 * null when they cannot be trusted: the window is [minimized] (Windows parks
 * it at -32000,-32000), [position] is not [WindowPosition.Absolute] with both
 * coordinates specified, or [size] is unspecified. While maximized these are
 * the maximized bounds (usable area + the 7 px invisible frame), which is
 * exactly what [screenFor] needs to tell which screen the window is on.
 */
internal fun liveWindowRect(minimized: Boolean, position: WindowPosition, size: DpSize): Rectangle? {
    if (minimized) return null
    val absolute = position as? WindowPosition.Absolute ?: return null
    if (!absolute.x.isSpecified || !absolute.y.isSpecified) return null
    if (!size.isSpecified) return null
    return Rectangle(
        absolute.x.value.toInt(),
        absolute.y.value.toInt(),
        size.width.value.toInt(),
        size.height.value.toInt(),
    )
}

/**
 * Keeps the geometry that gets persisted on the screen the window really is
 * on. The floating tracker ([nextFloatingGeometry]) only sees moves made while
 * floating, so a MAXIMIZED window sent to another screen (Win+Shift+Arrow)
 * would leave [floating] on the old one and the next launch would reopen
 * there.
 *
 * Returns [floating] unchanged when [windowRect] is null (unknown or
 * minimized: see [liveWindowRect]), when it overlaps none of [screens], or
 * when its screen ([screenFor]) is the same as the one of [floating]. Else
 * [floating] is replaced by a geometry on the window's screen: its size is
 * kept unless it would fill that screen ([fillsScreen]), in which case the
 * default size of that screen is used ([defaultFloatingSize]); it is centered
 * there ([centeredIn]), since an absolute position on one screen means nothing
 * on another.
 */
internal fun relocateFloatingToScreen(
    floating: Rectangle,
    windowRect: Rectangle?,
    screens: List<Rectangle>,
): Rectangle {
    if (windowRect == null) return floating
    val target = screenFor(windowRect, screens) ?: return floating
    if (target == screenFor(floating, screens)) return floating
    val size = if (fillsScreen(floating.width, floating.height, target)) {
        defaultFloatingSize(target)
    } else {
        DpSize(floating.width.dp, floating.height.dp)
    }
    return FloatingGeometry(centeredIn(target, size), size).toRectangle()
}

/**
 * Compute the initial window state from saved prefs + the OS's usable
 * bounds (screen minus taskbar) of the launch screen, [usableBounds] (see
 * [chooseLaunchScreen]: the screen the app was left on, else the primary one).
 *
 * The window always launches maximized. The floating geometry is what the OS
 * restores to, and it is chosen as follows:
 *  - prefs unset, corrupt (below the 1024×600 minimum), or describing a size
 *    that fills the screen ([fillsScreen]: width OR height >= 95 % of
 *    [usableBounds]): the default size of [defaultFloatingSize], centered on
 *    [usableBounds]. The 95 % rule repairs prefs written by earlier builds,
 *    which persisted the MAXIMIZED bounds (usable area + the invisible 7 px
 *    frame, e.g. -7,-7 1550×830) as if they were the floating geometry;
 *    handed back to the OS, that size became the "restore" rectangle and
 *    un-maximizing changed nothing.
 *  - otherwise the saved size, kept as is, at the saved position if it still
 *    intersects the usable area, else centered. A lost position is OK, not
 *    opening the window is not.
 */
internal fun resolveInitialWindowState(
    saved: DesktopSettingsStore.WindowGeometry,
    usableBounds: Rectangle,
): InitialWindowState {
    val defaultSize = defaultFloatingSize(usableBounds)
    val defaultState = InitialWindowState(
        maximized = true,
        floatingPosition = centeredIn(usableBounds, defaultSize),
        floatingSize = defaultSize,
    )

    // Defense: corrupted-tiny saved geometry (only a buggy earlier
    // session could have written width/height below the documented
    // 1024 × 600 minimum) → treat as unset.
    val isCorrupt = (saved.width > 0 && saved.width < MIN_FLOATING_WIDTH_DP) ||
        (saved.height > 0 && saved.height < MIN_FLOATING_HEIGHT_DP)

    // A "floating" size covering (nearly) the whole usable area is in fact a
    // leftover maximized size: see the KDoc. Compared in integer percent.
    val isMaximizedLeftover = !saved.isUnset && fillsScreen(saved.width, saved.height, usableBounds)

    if (saved.isUnset || isCorrupt || isMaximizedLeftover) return defaultState

    // Position must place at least part of the window on the launch screen,
    // otherwise re-center (for a saved screen that is still connected this
    // always holds, [chooseLaunchScreen] picked it from the saved rectangle).
    val savedRect = Rectangle(saved.x, saved.y, saved.width, saved.height)
    val floatingSize = DpSize(saved.width.dp, saved.height.dp)
    val floatingPos = if (usableBounds.intersects(savedRect)) {
        WindowPosition(saved.x.dp, saved.y.dp)
    } else {
        centeredIn(usableBounds, floatingSize)
    }

    // **Always launch maximized**, regardless of `saved.maximized`. The
    // user spec'd "Ouverture de l'application par défaut en 'agrandi'":
    // we honor that on every launch, not just the very first one.
    // The saved floating geometry (size + position) is preserved so
    // that un-maximizing via the OS restore button restores to the
    // user's last floating size and position.
    return InitialWindowState(
        maximized = true,
        floatingPosition = floatingPos,
        floatingSize = floatingSize,
    )
}

/**
 * Persist the remembered floating geometry plus the current maximized flag.
 * The geometry comes from [WindowSnapshot.floating] (tracked by
 * [nextFloatingGeometry]), never from the live `windowState`: Compose keeps
 * that one in sync with the real window bounds, so reading it while maximized
 * would overwrite the floating size with the maximized one. On restart
 * [resolveInitialWindowState] feeds the values back as the geometry the OS
 * restores to when the user un-maximizes.
 */
private fun persistGeometry(
    settingsStore: DesktopSettingsStore,
    snap: WindowSnapshot,
) {
    settingsStore.updateWindowGeometry(
        DesktopSettingsStore.WindowGeometry(
            x = snap.floating.position.x.value.toInt(),
            y = snap.floating.position.y.value.toInt(),
            width = snap.floating.size.width.value.toInt(),
            height = snap.floating.size.height.value.toInt(),
            maximized = snap.maximized,
        ),
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Close-button decision (minimize-to-tray / confirm / direct exit)
// ─────────────────────────────────────────────────────────────────────────────

/** What the OS close button (X) should do: see [resolveCloseAction]. */
internal enum class CloseAction { HIDE_TO_TRAY, CONFIRM_QUIT, CLOSE_DIRECT, FORCE_CLOSE }

/**
 * Pure decision for what clicking the window's close button (X) should do,
 * extracted out of the `onCloseRequest` lambda so it is unit-testable
 * without spinning up Compose / AWT:
 *
 *  0. [closingForInstaller] is true: the OS/Restart Manager is ending our
 *     session (MSI upgrade or uninstall closing a running NextSH before it
 *     touches its files) → [CloseAction.FORCE_CLOSE], unconditionally,
 *     before any other check. An unattended installer cannot answer a
 *     confirmation dialog and a window merely hidden to the tray keeps
 *     every installed file locked, so this branch must win regardless of
 *     the tray setting or how many sessions/tunnels are active: see
 *     [fr.techtical.nextsh.desktop.InstallerCloseWatch]. This parameter
 *     deliberately has NO default value: every caller must state which
 *     close it is handling, and any new input added here has to be
 *     spelled out in the exhaustive matrix of `MainCloseActionTest`
 *     rather than silently defaulting.
 *  1. "Minimize to tray on close" is enabled AND the tray icon is actually
 *     registered with the OS → [CloseAction.HIDE_TO_TRAY]. The caller must
 *     NOT tear anything down in this case, just hide the window.
 *  2. Otherwise, if any SSH session or tunnel is currently active →
 *     [CloseAction.CONFIRM_QUIT]: the caller shows a confirmation dialog
 *     naming the counts before it destroys them.
 *  3. Otherwise → [CloseAction.CLOSE_DIRECT]: nothing would be lost, close
 *     immediately.
 *
 * Tray availability is checked in addition to the user preference (not
 * instead of it) because [fr.techtical.nextsh.desktop.service.DesktopTunnelTray.start]
 * fails silently when [java.awt.SystemTray.isSupported] is false or
 * [java.awt.SystemTray.add] throws: if the setting were honored blindly,
 * the window could disappear with no way for the user to bring it back.
 */
internal fun resolveCloseAction(
    minimizeToTrayOnClose: Boolean,
    trayRegistered: Boolean,
    activeSessionCount: Int,
    activeTunnelCount: Int,
    closingForInstaller: Boolean,
): CloseAction = when {
    closingForInstaller -> CloseAction.FORCE_CLOSE
    minimizeToTrayOnClose && trayRegistered -> CloseAction.HIDE_TO_TRAY
    activeSessionCount > 0 || activeTunnelCount > 0 -> CloseAction.CONFIRM_QUIT
    else -> CloseAction.CLOSE_DIRECT
}

/**
 * The real exit, in an order the user never sees: [hide] first, so the window
 * disappears at once instead of showing the teardown (the vault locking back
 * to its unlock screen, sessions closing, the OpenGL surface destroyed during
 * the OS close animation), then [shutdown], then [exit].
 *
 * [exit] runs even if [shutdown] throws: the window is already hidden by then,
 * and a failure must not leave an invisible process behind. The exception is
 * still rethrown after [exit]. A failing [hide] is logged and ignored: the
 * exit goes on, at worst with the teardown visible as before.
 */
internal fun runExitSequence(hide: () -> Unit, shutdown: () -> Unit, exit: () -> Unit) {
    try {
        hide()
    } catch (e: Exception) {
        Logger.w("ExitSequence", "hiding the window before exit failed: ${e.javaClass.simpleName}")
    }
    try {
        shutdown()
    } finally {
        exit()
    }
}

/**
 * Hides [window] for [runExitSequence]: cloaked by DWM on Windows (instant,
 * no close animation), plain AWT hide elsewhere or if the cloak is refused.
 */
private fun hideForExit(window: java.awt.Window) {
    if (!WindowsFirstFrameReveal.cloakForExit(window)) window.isVisible = false
}

// ─────────────────────────────────────────────────────────────────────────────
// Installer / Restart Manager close detection (Windows only)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * How long an armed installer-close latch stays valid: 60 s, twice the
 * 30 s `Timeout` of the end-session `util:CloseApplication` row in
 * `installer/windows/jpackage/main.wxs`, and short enough that an upgrade
 * the user cancels at the prompt does not leave the app permanently in
 * force-close mode.
 *
 * A slow user at the installer's Abort/Retry/Ignore prompt is not a
 * problem either, but NOT for the reason an earlier version of this
 * comment gave: pressing Retry does **not** re-send anything. Per WiX
 * 3.11.2's `CloseApps.cpp`, Retry only re-snapshots the running process
 * list: no message is posted, so it never re-arms this latch. The prompt
 * is also the LAST row of the sequence in `main.wxs`, so by the time it
 * appears the end-session message has already been sent and the latch has
 * already been armed, its TTL running. A genuine re-arm can only come from
 * a fresh run of the `WixCloseApplications` custom action (the operator
 * relaunching the installer), which posts the end-session message again
 * from scratch. The TTL, not the prompt, is therefore what has to cover
 * the whole installer window.
 */
internal const val INSTALLER_CLOSE_ARM_TTL_NANOS: Long = 60_000_000_000L

/**
 * Whether an installer close armed at [armedAtNanos] (a `System.nanoTime()`
 * reading, `null` when never armed) is still in effect at [nowNanos].
 *
 * Pure, so the TTL policy is unit-testable without a real WndProc. The
 * subtraction (rather than a comparison of the two absolute values) is
 * the standard `nanoTime` idiom: it stays correct across the counter's
 * wrap-around. A negative elapsed time cannot happen with a monotonic
 * clock inside one process; should it ever, it counts as "still armed",
 * which is the safe direction (an installer close that exits cleanly beats
 * a hidden window keeping installed files locked).
 */
internal fun isInstallerCloseArmed(armedAtNanos: Long?, nowNanos: Long): Boolean {
    if (armedAtNanos == null) return false
    return nowNanos - armedAtNanos < INSTALLER_CLOSE_ARM_TTL_NANOS
}

/**
 * Detects an MSI upgrade or uninstall trying to close a running NextSH
 * instance, so [resolveCloseAction] can bypass minimize-to-tray and the
 * confirm-quit dialog (neither of which an unattended installer can
 * answer) and exit cleanly instead.
 *
 * ## Why this exists
 * `installer/windows/jpackage/main.wxs` wires four `util:CloseApplication`
 * rows (see its header comment) that ask Windows Installer to close
 * `NextSH.exe` before the MSI touches its files. AWT already turns the
 * `WM_CLOSE` part of that into Compose's `onCloseRequest` (see
 * `requestClose` above), but by itself it cannot distinguish "the user
 * clicked X" from "the installer wants us gone": both look identical by
 * the time `WM_CLOSE` arrives. Left alone, an in-place upgrade over a
 * running NextSH would hit whatever the user's ordinary close behavior is
 * (hide to tray, or a confirmation dialog with nobody there to click it),
 * the process would stay alive holding its files open, and the MSI would
 * fail to replace them.
 *
 * ## Message order
 * Microsoft's guidance for RM-aware applications describes
 * `WM_QUERYENDSESSION` (with `lParam` carrying `ENDSESSION_CLOSEAPP`)
 * first, then `WM_CLOSE`. Inside a SINGLE `util:CloseApplication` row WiX
 * does the OPPOSITE: the deferred custom action posts `WM_CLOSE` FIRST
 * (`CloseMessage="yes"`) and only then the `WM_QUERYENDSESSION` /
 * `WM_ENDSESSION` pair (`EndSessionMessage="yes"`). That is exactly why
 * `main.wxs` splits the two messages across two rows ordered by
 * `Sequence`: the end-session row runs first (sequence 1) and the plain
 * `WM_CLOSE` row second (sequence 2), so what NextSH actually receives
 * from our own package is the documented RM order.
 *
 * Both orders are still handled here, because that guarantee is only as
 * good as the authoring: a hand-built MSI or a single-row variant can
 * still deliver `WM_CLOSE` first, and then there is a race: AWT can have
 * queued the `WINDOW_CLOSING` born of that first `WM_CLOSE`, and the EDT
 * can have run `requestClose` and hidden the window to the tray, before
 * this hook ever sees the end-session message. Reading the latch from
 * `requestClose` alone would lose that race.
 *
 * That is why arming does not just flip a flag: it also posts
 * `onInstallerClose` (supplied by [install]) on the EDT via
 * `SwingUtilities.invokeLater`. The AWT event queue is FIFO, so that
 * runnable is guaranteed to run AFTER any `WINDOW_CLOSING` already queued
 * ahead of it: whatever `requestClose` decided (tray, dialog) is
 * immediately followed by the clean shutdown. The `FORCE_CLOSE` branch of
 * [resolveCloseAction] covers the documented RM order, where the latch IS
 * armed before `WM_CLOSE` arrives, which, since `main.wxs` sequences the
 * end-session row ahead of the `WM_CLOSE` row, is the ordinary path for
 * our own installer; the `invokeLater` mechanism above is what keeps the
 * reversed order working as well.
 *
 * Observing `WM_QUERYENDSESSION` requires subclassing the main window's
 * WndProc: AWT exposes no Java-level callback for it. This follows the
 * same JNA-binding pattern as
 * [fr.techtical.nextsh.desktop.window.WindowsDarkTitleBar] (`Native.getWindowPointer`
 * for the HWND, deferred via `SwingUtilities.invokeLater` if the AWT peer
 * isn't realized yet), but additionally replaces the window procedure
 * pointer (`GWLP_WNDPROC`) with a JNA callback that:
 *  1. Checks every incoming message for `WM_QUERYENDSESSION` with the
 *     `ENDSESSION_CLOSEAPP` bit set, and if so arms the latch (see
 *     [closingForInstaller]) and posts `onInstallerClose` on the EDT: a
 *     plain `@Volatile` timestamp is enough here: the callback runs on the
 *     native AWT message-pump thread, `requestClose` reads it later on the
 *     EDT, and there is no compound invariant to protect.
 *  2. ALWAYS forwards the message unchanged to the original AWT window
 *     procedure via `CallWindowProc`: this hook only OBSERVES, it never
 *     consumes or short-circuits a message AWT itself needs to see. Note
 *     in particular that `WM_ENDSESSION` is NOT used to disarm. WiX
 *     3.11.2's deferred close action sends it with `wParam = TRUE` and
 *     `lParam = ENDSESSION_CLOSEAPP`, and only when our
 *     `WM_QUERYENDSESSION` answer was non-zero (the unconditional
 *     `wParam = 0` of WiX issue 4228 is fixed in 3.11.2). It is therefore
 *     a CONFIRMATION that the close is going ahead, never an "installer
 *     gave up" signal, and in the cases where the installer really does
 *     give up (the user aborts at the prompt, or the query was refused) it
 *     is not sent at all. There is simply no message to disarm on, so the
 *     latch is bounded by a TTL instead: see [closingForInstaller].
 *
 * The callback instance is kept in [callback] for the entire process
 * lifetime (a `private object` field is GC-rooted for as long as the class
 * stays loaded, i.e. the whole JVM run): JNA's `CallbackReference` uses a
 * `WeakReference` internally, so losing our own strong reference would let
 * the GC free the native call trampoline while Windows still holds the raw
 * function pointer, crashing on the next message.
 */
internal object InstallerCloseWatch {

    /** `true` only on Windows, a no-op everywhere else. */
    val isPlatformSupported: Boolean =
        System.getProperty("os.name", "").lowercase(Locale.US).contains("windows")

    /**
     * `System.nanoTime()` of the last `WM_QUERYENDSESSION/ENDSESSION_CLOSEAPP`
     * observed, or `null` while no installer close has been seen.
     */
    @Volatile
    private var armedAtNanos: Long? = null

    /**
     * Whether an installer close is currently in progress, i.e. the latch
     * was armed less than [INSTALLER_CLOSE_ARM_TTL_NANOS] ago. Read by
     * `requestClose` in `Main.kt` on every close attempt.
     *
     * The TTL matters because the latch cannot be cleared on the way out.
     * The only candidate signal, `WM_ENDSESSION`, is useless for that: WiX
     * 3.11.2's deferred close action sends it with `wParam = TRUE` /
     * `lParam = ENDSESSION_CLOSEAPP` and ONLY after a non-zero
     * `WM_QUERYENDSESSION` answer, so it confirms that the close is
     * proceeding rather than reporting that the installer gave up, and it
     * never arrives at all in the cases where the installer really does
     * give up. Disarming there would break the feature outright without
     * covering the case it is meant to. Meanwhile the user CAN cancel the
     * upgrade at the prompt and keep using an app whose latch is armed:
     * a permanent latch would then turn every later click on X into an
     * unconfirmed teardown of their live sessions. Bounding the latch to a
     * minute keeps the installer window covered without leaving that trap
     * behind.
     */
    val closingForInstaller: Boolean
        get() = isInstallerCloseArmed(armedAtNanos, System.nanoTime())

    private const val GWLP_WNDPROC = -4
    private const val WM_QUERYENDSESSION = 0x0011
    private const val ENDSESSION_CLOSEAPP = 0x00000001

    /** Keeps the JNA callback trampoline alive: see the class doc above. */
    @Volatile
    private var callback: WindowProc? = null

    /** The AWT-default window procedure, so every message can be chained onward. */
    @Volatile
    private var originalWndProc: Pointer? = null

    /** Posted on the EDT when the latch arms: see the class doc above. */
    @Volatile
    private var onInstallerClose: (() -> Unit)? = null

    /**
     * Install the WndProc hook on [window]. Idempotent: a second call
     * on the same (already-subclassed) window is a no-op.
     *
     * [onInstallerClose] is invoked on the EDT (never on the native
     * message-pump thread) once an installer close is detected, and must
     * be idempotent: the belt-and-braces `FORCE_CLOSE` path can also fire
     * for the same close.
     */
    fun install(window: java.awt.Window, onInstallerClose: () -> Unit) {
        if (!isPlatformSupported) return
        if (originalWndProc != null) return
        this.onInstallerClose = onInstallerClose

        val hwndPtr = getHwnd(window)
        if (hwndPtr == null) {
            // AWT peer not realized yet: defer to the next EDT pass, same
            // fallback WindowsDarkTitleBar.apply uses for the same reason.
            javax.swing.SwingUtilities.invokeLater {
                val deferred = getHwnd(window)
                if (deferred != null) {
                    subclass(deferred)
                } else {
                    Logger.w("InstallerCloseWatch", "HWND still null after invokeLater")
                }
            }
            return
        }
        subclass(hwndPtr)
    }

    private fun subclass(hwndPtr: Pointer) {
        val hwnd = HWND(hwndPtr)
        val proc = WindowProc { hwndArg, uMsg, wParam, lParam ->
            if (uMsg == WM_QUERYENDSESSION &&
                (lParam.toLong() and ENDSESSION_CLOSEAPP.toLong()) != 0L
            ) {
                arm()
            }
            val prev = originalWndProc
            if (prev != null) {
                User32.INSTANCE.CallWindowProc(prev, hwndArg, uMsg, wParam, lParam)
            } else {
                // Should not happen (originalWndProc is published before the
                // swap below), but if it ever did, DefWindowProc is the only
                // correct fallback: returning LRESULT(0) would silently
                // swallow the message, and for WM_QUERYENDSESSION that
                // means "veto the shutdown", the exact opposite of what
                // this class exists to allow.
                User32.INSTANCE.DefWindowProc(hwndArg, uMsg, wParam, lParam)
            }
        }
        // Store the callback BEFORE swapping the pointer so there is no
        // window where a message could arrive with `callback` still null.
        callback = proc

        // Publish the CURRENT window procedure BEFORE installing ours:
        // Windows can dispatch a message to the new WndProc the instant
        // SetWindowLongPtr returns (in fact, from inside the call itself),
        // and reading it back only from that call's return value leaves a
        // window where the callback would see `originalWndProc == null`
        // and drop the message on DefWindowProc.
        val existing = runCatching {
            User32.INSTANCE.GetWindowLongPtr(hwnd, GWLP_WNDPROC)
        }.getOrNull()?.toLong()?.takeIf { it != 0L }?.let { Pointer(it) }
        if (existing != null) originalWndProc = existing

        val fnPointer: Pointer = CallbackReference.getFunctionPointer(proc)
        val prev = runCatching {
            User32.INSTANCE.SetWindowLongPtr(hwnd, GWLP_WNDPROC, fnPointer)
        }.getOrNull()
        if (prev == null) {
            Logger.w("InstallerCloseWatch", "SetWindowLongPtr failed: installer close detection unavailable")
            callback = null
            // Roll back the optimistic publish above: the hook is NOT
            // installed, so `install` must stay retryable (its idempotency
            // guard keys off this very field).
            originalWndProc = null
            return
        }
        originalWndProc = prev
    }

    /**
     * Arm the latch and hand the clean-shutdown request to the EDT. Runs on
     * the native message-pump thread, so it must not touch Swing/Compose
     * state directly: hence the `invokeLater`, which additionally provides
     * the FIFO ordering the class doc relies on.
     */
    private fun arm() {
        armedAtNanos = System.nanoTime()
        Logger.d(
            "InstallerCloseWatch",
            "WM_QUERYENDSESSION/ENDSESSION_CLOSEAPP received: installer close armed",
        )
        val callbackToRun = onInstallerClose ?: return
        javax.swing.SwingUtilities.invokeLater { callbackToRun() }
    }

    private fun getHwnd(window: java.awt.Window): Pointer? {
        return runCatching {
            val ptr = Native.getWindowPointer(window)
            if (ptr == null || ptr == Pointer.NULL) null else ptr
        }.getOrNull()
    }
}
