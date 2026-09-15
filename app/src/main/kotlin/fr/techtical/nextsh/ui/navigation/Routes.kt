// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.navigation

sealed class Routes(val route: String) {
    // First-launch onboarding stepper (shown once, before unlock)
    object Onboarding       : Routes("onboarding")

    // Vault unlock (écran d'entrée)
    object VaultUnlock      : Routes("vault_unlock")

    // Hosts
    object HostList         : Routes("host_list")
    object HostDetail       : Routes("host_detail/{hostId}") {
        fun createRoute(hostId: String) = "host_detail/$hostId"
    }
    // Import des hôtes depuis Termius / KeePassXC
    object HostImport       : Routes("host_import")

    // Sessions / Terminal
    object Sessions         : Routes("sessions?hostId={hostId}") {
        fun createRoute(hostId: String? = null) =
            if (hostId != null) "sessions?hostId=$hostId" else "sessions"
    }

    // Tunnels
    object TunnelList : Routes("tunnel_list?startTunnelId={startTunnelId}") {
        fun createRoute(startTunnelId: String? = null) =
            if (startTunnelId != null) "tunnel_list?startTunnelId=$startTunnelId"
            else "tunnel_list"
    }
    object TunnelConfig     : Routes("tunnel_config/{tunnelId}") {
        fun createRoute(tunnelId: String) = "tunnel_config/$tunnelId"
        const val NEW = "new"
    }
    // Vault / Clés
    object Vault            : Routes("vault")
    object KeyImport        : Routes("key_import")

    // Snippets
    object SnippetList      : Routes("snippet_list")

    // Settings
    object Settings         : Routes("settings")
    object KnownHosts       : Routes("known_hosts")

    // LAN Sync: Enrollment (Phase 2)
    object EnrollmentScan   : Routes("enrollment_scan")

    // Conflict Resolution (Phase 2 Wave 4.2)
    object ConflictResolution : Routes("conflict_resolution")

    // Enrolled Devices (Phase 2 Wave 5)
    object EnrolledDevices : Routes("enrolled_devices")

    // SFTP Browser
    object SftpBrowser : Routes("sftp_browser/{sessionId}?hostLabel={hostLabel}") {
        fun createRoute(sessionId: String, hostLabel: String = "") =
            "sftp_browser/$sessionId?hostLabel=${android.net.Uri.encode(hostLabel)}"
    }

    // FIDO2 enrollment (hardware YubiKey)
    object Fido2Enroll : Routes("fido2_enroll")

    // Gestion des thèmes terminal personnalisés (depuis Settings)
    object TerminalThemes : Routes("terminal_themes")
}
