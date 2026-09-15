// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sync

import java.net.InetAddress
import java.net.NetworkInterface

object LanAddressDetector {

    data class LanInterface(val address: InetAddress, val displayName: String)

    private val VIRTUAL_PATTERNS = setOf("virtualbox", "vmware", "hyper-v", "tap", "loopback")

    /**
     * First usable site-local IPv4 interface, or null when none is available.
     *
     * Never throws. `NetworkInterface.getNetworkInterfaces()` and the per-interface `isUp` /
     * `inetAddresses` probes read live OS state and raise `SocketException` while an adapter is
     * being torn down or brought up: routine during a VPN connect, a docking event or a Wi-Fi
     * switch. Callers are all "detect or give up for now" loops (server start, network watcher,
     * enrollment), and every one of them already handles null; letting a transient throw escape
     * instead killed the caller's coroutine.
     */
    fun detect(): LanInterface? = runCatching {
        NetworkInterface.getNetworkInterfaces()
            ?.asSequence()
            ?.filter { iface ->
                iface.isUp &&
                    !iface.isLoopback &&
                    !isVirtual(iface.displayName)
            }
            ?.flatMap { iface ->
                iface.inetAddresses.asSequence().map { addr -> iface to addr }
            }
            ?.filter { (_, addr) ->
                addr is java.net.Inet4Address &&
                    !addr.isLoopbackAddress &&
                    !addr.isLinkLocalAddress &&
                    addr.isSiteLocalAddress
            }
            ?.map { (iface, addr) -> LanInterface(addr, iface.displayName) }
            ?.firstOrNull()
    }.getOrNull()

    private fun isVirtual(displayName: String): Boolean {
        val lower = displayName.lowercase()
        return VIRTUAL_PATTERNS.any { lower.contains(it) }
    }
}
