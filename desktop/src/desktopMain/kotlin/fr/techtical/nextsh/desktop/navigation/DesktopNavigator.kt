// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.navigation

import fr.techtical.nextsh.desktop.data.di.DesktopContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class Screen {
    /**
     * Fresh-install onboarding (Welcome → Create vault → Optional sync).
     * Replaces the previous standalone `VaultSetup` screen: the vault
     * creation step is folded into the stepper. Routed when
     * `vaultPinManager.isSetup() == false` (no vault.meta on disk).
     */
    data object FirstLaunch : Screen()
    data object VaultUnlock : Screen()
    data object HostList : Screen()
    data class HostDetail(val hostId: String?) : Screen()
    data object Vault : Screen()
    data object TunnelList : Screen()
    data class TunnelConfig(val tunnelId: String?) : Screen()
    data object Settings : Screen()
    data object KnownHosts : Screen()
    /**
     * `fromFirstLaunch = true` when reached via the FirstLaunch onboarding
     * "Configure now" button. Back then routes to HostList (vault was just
     * created: there's no Settings to go back to). Default false = entered
     * from Settings, back routes to Settings.
     */
    data class Enrollment(val fromFirstLaunch: Boolean = false) : Screen()
    data object Sessions : Screen()
    data object Transfers : Screen()
    data object ConflictResolution : Screen()
    data object EnrolledDevices : Screen()
    data object SnippetList : Screen()
}

class DesktopNavigator {
    private val initial: Screen =
        if (DesktopContainer.vaultPinManager.isSetup()) Screen.VaultUnlock else Screen.FirstLaunch

    private val _current = MutableStateFlow<Screen>(initial)
    val current: StateFlow<Screen> = _current.asStateFlow()

    fun navigate(screen: Screen) {
        _current.value = screen
    }
}
