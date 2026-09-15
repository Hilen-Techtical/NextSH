// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.service

import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.tray_open_nextsh
import fr.techtical.nextsh.desktop.generated.resources.tray_quit
import fr.techtical.nextsh.desktop.generated.resources.tray_stop_all_tunnels
import fr.techtical.nextsh.desktop.generated.resources.tray_tooltip_default
import fr.techtical.nextsh.desktop.generated.resources.tray_tooltip_many
import fr.techtical.nextsh.desktop.generated.resources.tray_tooltip_one
import fr.techtical.nextsh.shared.domain.model.TunnelState
import fr.techtical.nextsh.shared.domain.model.TunnelStatus
import fr.techtical.nextsh.shared.util.AppScope
import fr.techtical.nextsh.shared.util.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import java.awt.AWTException
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import javax.imageio.ImageIO

/**
 * System-tray icon mirroring Android's [TunnelForegroundService] notification.
 *
 * **Menu rendering** : we use the native [java.awt.PopupMenu]. On Windows
 * 10/11 it inherits the OS theme (dark menu chrome when system theme is
 * dark), and the OS handles positioning + dismissal natively. Custom
 * Swing alternatives (`JPopupMenu`, `JWindow`, undecorated `JFrame`) all
 * trade the styling gain against real bugs: phantom taskbar entries,
 * focus quirks, off-screen rendering when sizing math fails. The native
 * popup is the right call for tray UX even if it can't pick up the
 * Techtical palette.
 *
 * Lifecycle: [start] called once from `Main.kt`'s LaunchedEffect (after
 * AWT/Swing is up), [stop] from [DesktopContainer.shutdown]. Silently
 * disabled where [SystemTray.isSupported] returns false.
 */
class DesktopTunnelTray(
    private val appScope: AppScope,
    private val tunnelService: DesktopTunnelService,
) {

    @Volatile
    private var trayIcon: TrayIcon? = null

    @Volatile
    private var observeJob: Job? = null

    /**
     * True once the tray icon is actually registered with the OS. `start()`
     * fails silently (no exception surfaced to the caller) when
     * [SystemTray.isSupported] is false, the icon resource is missing, or
     * [SystemTray.add] throws [AWTException]: callers that want to offer a
     * "minimize to tray" behavior MUST check this first, otherwise the
     * window could be hidden with no way for the user to bring it back.
     */
    val isRegistered: Boolean get() = trayIcon != null

    /**
     * Registers the tray icon. `suspend` because the menu labels and default
     * tooltip are resolved from compose-resources ([getString]): the AWT
     * `PopupMenu` / `TrayIcon` are not composables, so they cannot use
     * `stringResource()`, but the underlying resource loader is the same
     * suspend API used under the hood by `stringResource()`. Called once
     * from `Main.kt`'s post-show `LaunchedEffect`, i.e. already inside a
     * coroutine.
     */
    suspend fun start(onOpen: () -> Unit, onQuit: () -> Unit) {
        if (trayIcon != null) return
        if (!SystemTray.isSupported()) {
            Logger.w("TunnelTray", "SystemTray not supported on this platform: skipping")
            return
        }
        val image = loadIcon() ?: run {
            Logger.w("TunnelTray", "icon resource missing: skipping tray")
            return
        }

        val openLabel = getString(Res.string.tray_open_nextsh)
        val stopLabel = getString(Res.string.tray_stop_all_tunnels)
        val quitLabel = getString(Res.string.tray_quit)
        val defaultTooltip = getString(Res.string.tray_tooltip_default)

        val popup = PopupMenu().apply {
            add(MenuItem(openLabel).apply { addActionListener { onOpen() } })
            add(MenuItem(stopLabel).apply {
                addActionListener {
                    appScope.coroutineScope.launch { tunnelService.stopAllTunnels() }
                }
            })
            addSeparator()
            // "Quitter" means "leave the app", not "leave it silently". It is
            // typically clicked while the window is HIDDEN in the tray, so the
            // user has no view of what is still running: an unconditional
            // exit here used to kill live SSH sessions and tunnels with no
            // prompt at all. `onQuit` is therefore expected to restore the
            // window and run the SAME confirmation flow as the window's close
            // button (see `Main.kt`'s `requestQuitWithConfirmation`); it exits
            // straight away only when nothing is active. This item never
            // minimizes to tray. We are already there.
            add(MenuItem(quitLabel).apply { addActionListener { onQuit() } })
        }

        val icon = TrayIcon(image, defaultTooltip, popup).apply {
            isImageAutoSize = true
            addActionListener { onOpen() }
        }

        try {
            SystemTray.getSystemTray().add(icon)
            trayIcon = icon
        } catch (e: AWTException) {
            Logger.w("TunnelTray", "could not register tray icon: ${e.javaClass.simpleName}")
            return
        }

        observeJob = appScope.coroutineScope.launch {
            tunnelService.tunnelStates.collectLatest { states -> updateTooltip(states) }
        }
    }

    fun stop() {
        observeJob?.cancel()
        observeJob = null
        trayIcon?.let { current ->
            try {
                SystemTray.getSystemTray().remove(current)
            } catch (e: Exception) {
                Logger.w("TunnelTray", "remove failed: ${e.javaClass.simpleName}")
            }
        }
        trayIcon = null
    }

    private suspend fun updateTooltip(states: Map<String, TunnelState>) {
        val active = states.values.count {
            it.status == TunnelStatus.ACTIVE || it.status == TunnelStatus.RECONNECTING
        }
        val text = when (active) {
            0 -> getString(Res.string.tray_tooltip_default)
            1 -> getString(Res.string.tray_tooltip_one)
            else -> getString(Res.string.tray_tooltip_many, active)
        }
        trayIcon?.toolTip = text
    }

    private fun loadIcon(): java.awt.Image? {
        val stream = javaClass.classLoader.getResourceAsStream(ICON_RESOURCE) ?: return null
        return stream.use { ImageIO.read(it) }
    }

    private companion object {
        const val ICON_RESOURCE = "images/nextsh_logo.png"
    }
}
