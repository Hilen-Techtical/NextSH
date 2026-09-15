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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
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
import fr.techtical.nextsh.shared.domain.model.TunnelStatus
import fr.techtical.nextsh.shared.util.Logger
import net.i2p.crypto.eddsa.EdDSASecurityProvider
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.jetbrains.compose.resources.stringResource
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import java.security.Security
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

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
    // The splash is closed from the post-show `LaunchedEffect` of the
    // main `Window {}` block (see below), exactly when the real Compose
    // window is themed and visible.
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

    // Compute initial window geometry from persisted prefs (fallback: default
    // floating size 1280×800 centered on the primary display, starting
    // maximized). The user's last floating position + size + maximized
    // flag survive restart. See [DesktopSettingsStore.WindowGeometry].
    val settingsStore = DesktopContainer.settingsStore
    val usableBounds = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
    val initial = resolveInitialWindowState(settingsStore.settings.value.windowGeometry, usableBounds)
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
            position = initial.floatingPosition,
            size = initial.floatingSize,
        )

        // ── Geometry persistence ──────────────────────────────────────────
        // With OS-decorated window the system itself drives the move /
        // resize / placement transitions, and Compose updates
        // `windowState` in response (one-way AWT → Compose). There is no
        // feedback loop, no tremor, no need for event-driven persistence
        // hooks. We persist once on close: that's enough because no
        // intermediate write can ever be wrong; the last value in
        // `windowState` always reflects the final geometry. A crash
        // between drag-end and close would lose the most recent
        // adjustment, an accepted trade-off given how narrow that interval is.
        val persistFromWindow: () -> Unit = {
            persistGeometry(
                settingsStore,
                WindowSnapshot(
                    position = windowState.position,
                    size = windowState.size,
                    placement = windowState.placement,
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
        val closeApp = {
            persistGeometry(
                settingsStore,
                WindowSnapshot(
                    position = windowState.position,
                    size = windowState.size,
                    placement = windowState.placement,
                ),
            )
            DesktopContainer.shutdown()
            exitApplication()
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
            icon = painterResource("images/nextsh_logo.png"),
            state = windowState,
            undecorated = false,
            visible = windowVisible,
        ) {
            remember {
                StartupTrace.mark("Window{} content composing")
            }
            // Theme the OS title bar to NextSH dark + NearBlack (Win 11
            // 22H2+ for caption color; Win 10 18985+ for dark mode only:
            // see [WindowsDarkTitleBar]). Must run AFTER the window is
            // shown so the AWT peer / HWND exists; LaunchedEffect runs
            // post-first-composition which is post-show.
            LaunchedEffect(Unit) {
                StartupTrace.mark("Window post-show LaunchedEffect entered")
                WindowsDarkTitleBar.apply(window)

                // Arms the WM_QUERYENDSESSION watcher so an MSI upgrade /
                // uninstall (Restart Manager) can close us cleanly instead
                // of hitting minimize-to-tray or a confirmation dialog it
                // cannot answer. The callback is posted on the EDT by the
                // hook itself and wins the race against the WM_CLOSE that
                // arrives first: see [InstallerCloseWatch] and
                // `closeForInstaller` above.
                InstallerCloseWatch.install(window, onInstallerClose = closeForInstaller)

                // **AWT window background = NearBlack.** During an OS-
                // driven resize, the new pixels added at the edge are
                // briefly painted with the JFrame default background
                // (white) before Compose's Skia canvas repaints to fill
                // them: the user sees a ~16 ms white flash on the edge
                // being resized. Setting `window.background` to NearBlack
                // means those new pixels start out the right color, so
                // even if Compose's repaint lags, there is no visible
                // flash. Native Win32 apps don't have this issue because
                // their content is drawn synchronously via Direct2D/GDI;
                // a Compose Desktop window goes Skia → DirectX which
                // adds the repaint gap.
                window.background = java.awt.Color(0x0F, 0x0F, 0x0F)

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

                // Close the JVM-rendered splash now that the real Compose
                // window is themed, sized and visible. `getSplashScreen()`
                // returns null if no `-splash:` flag was active (e.g. dev
                // run without the build.gradle.kts jvmArgs); in that case
                // the call is a no-op.
                java.awt.SplashScreen.getSplashScreen()?.close()
                StartupTrace.mark("splash closed")
            }

            // Persist geometry on every placement transition (floating ↔
            // maximized ↔ fullscreen). Compose updates `windowState`
            // synchronously when the OS button is clicked; observing
            // `placement` directly avoids polling.
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

/**
 * Snapshot of the values we persist between sessions. With the OS-decorated
 * window, [position] / [size] reflect the *floating* geometry whenever the
 * window is not currently maximized; when it IS maximized, Compose stores
 * the floating geometry separately and exposes it via the next un-maximize.
 * We deal with that subtlety in [persistGeometry].
 */
private data class WindowSnapshot(
    val position: WindowPosition,
    val size: DpSize,
    val placement: WindowPlacement,
)

/**
 * Initial window state computed before `application { }` from saved prefs.
 * When the prefs are unset (first launch), produces: maximized=true,
 * floating size 1280×800 centered on the primary display.
 */
private data class InitialWindowState(
    val maximized: Boolean,
    val floatingPosition: WindowPosition,
    val floatingSize: DpSize,
)

/**
 * Compute the initial window state from saved prefs + the OS's usable
 * bounds (screen minus taskbar). First launch fallback: maximized on usable
 * bounds, floating size 1280×800 centered for later restore.
 *
 * When saved prefs place the window off-screen (display unplugged, etc.),
 * we silently fall back to centered/default, losing a position is OK,
 * not opening the window is not.
 */
// Minimum window size: also enforced by `window.minimumSize` (1024 × 600)
// in the LaunchedEffect below. Validated here on load so a corrupted prefs
// entry (e.g. saved 5×5 from a buggy earlier build) can never resurrect a
// "window invisible on un-maximize" state.
private const val MIN_FLOATING_WIDTH_DP = 1024
private const val MIN_FLOATING_HEIGHT_DP = 600

private fun resolveInitialWindowState(
    saved: DesktopSettingsStore.WindowGeometry,
    usableBounds: java.awt.Rectangle,
): InitialWindowState {
    val defaultFloatingSize = DpSize(1280.dp, 800.dp)
    val centeredFloatingPos = WindowPosition(
        (usableBounds.x + (usableBounds.width - 1280) / 2).coerceAtLeast(0).dp,
        (usableBounds.y + (usableBounds.height - 800) / 2).coerceAtLeast(0).dp,
    )

    // Defense: corrupted-tiny saved geometry (only a buggy earlier
    // session could have written width/height below the documented
    // 1024 × 600 minimum) → treat as unset.
    val isCorrupt = (saved.width > 0 && saved.width < MIN_FLOATING_WIDTH_DP) ||
        (saved.height > 0 && saved.height < MIN_FLOATING_HEIGHT_DP)

    if (saved.isUnset || isCorrupt) {
        return InitialWindowState(
            maximized = true,
            floatingPosition = centeredFloatingPos,
            floatingSize = defaultFloatingSize,
        )
    }

    // Position must place at least part of the window on a connected
    // display, otherwise re-center.
    val savedRect = java.awt.Rectangle(saved.x, saved.y, saved.width, saved.height)
    val onScreen = usableBounds.intersects(savedRect)
    val floatingPos = if (onScreen) WindowPosition(saved.x.dp, saved.y.dp) else centeredFloatingPos
    val floatingSize = DpSize(saved.width.dp, saved.height.dp)

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
 * Persist the current window geometry. Compose's [WindowState] keeps the
 * floating position/size separately from the maximized bounds, so calling
 * `windowState.position` / `.size` while maximized still returns the LAST
 * floating values (not the maximized ones). That is the contract we rely
 * on here: we write `.position` / `.size` directly along with the current
 * `.placement`, and on restart [resolveInitialWindowState] feeds them back
 * as the floating fallback when the user un-maximizes.
 */
private fun persistGeometry(
    settingsStore: DesktopSettingsStore,
    snap: WindowSnapshot,
) {
    val absPos = snap.position as? WindowPosition.Absolute
    val (x, y) = if (absPos != null) {
        absPos.x.value.toInt() to absPos.y.value.toInt()
    } else {
        // PlatformDefault: happens only before Compose has resolved a real
        // coordinate. We write a sentinel that load() interprets as "use
        // default centered position".
        -1 to -1
    }
    settingsStore.updateWindowGeometry(
        DesktopSettingsStore.WindowGeometry(
            x = x,
            y = y,
            width = snap.size.width.value.toInt(),
            height = snap.size.height.value.toInt(),
            maximized = snap.placement == WindowPlacement.Maximized,
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
