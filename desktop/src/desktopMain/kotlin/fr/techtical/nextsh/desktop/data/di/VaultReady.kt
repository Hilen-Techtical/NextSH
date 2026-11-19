// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.data.di

import fr.techtical.nextsh.desktop.service.DesktopTunnelService
import fr.techtical.nextsh.desktop.sync.LanSyncServerLifecycle

/**
 * Starts what depends on a usable vault, for the three ways a vault becomes usable: the PIN unlock, and the
 * two exits of the first launch (skip the sync step, or go on to configure it). They start exactly the same
 * services, so that the first session after the vault creation is not a degraded one.
 *
 *  - The LAN sync lifecycle learns that the vault is open ([LanSyncServerLifecycle.onVaultUnlocked]). The
 *    server itself only starts if the user turned sync on: the setting is opt-in and off by default, so a
 *    fresh vault starts nothing here. Without this call the lifecycle stays "locked" for the whole first
 *    session, and turning sync on in Settings (after enrolling a phone, say) waits for a restart.
 *  - The network watcher re-binds the server when the LAN address changes. Its ticks do nothing while the
 *    gate (vault open and sync on) is closed.
 *  - The tunnel service starts its watches and the auto-start tunnels ([DesktopTunnelService.onVaultUnlocked]).
 *
 * Idempotent, as each of the three calls is. The enrollment server of the first launch is not involved: it
 * listens on its own port (47732 and above), the sync server on 47731.
 */
internal fun startVaultDependentServices(
    syncLifecycle: LanSyncServerLifecycle,
    tunnelService: DesktopTunnelService,
) {
    syncLifecycle.onVaultUnlocked()
    // Idempotent: re-binds the sync server when the LAN IP changes (DHCP).
    syncLifecycle.startNetworkWatcher()
    tunnelService.onVaultUnlocked()
}
