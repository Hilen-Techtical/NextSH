// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.service

/** A tunnel that was running and was stopped because it, or its host, was deleted on another device. */
data class StoppedTunnel(val id: String, val label: String)

/**
 * The tunnels stopped by ONE pass of [DesktopTunnelService]: the deletion of a host stops all its running
 * tunnels at once, and the user must get one message about them, not one per tunnel. Never empty.
 *
 * Carries labels, so it must never be logged.
 */
data class TunnelDeletionStop(val tunnels: List<StoppedTunnel>)
