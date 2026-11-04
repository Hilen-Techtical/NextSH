// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.domain.model

/** Why a port of a tunnel cannot be used. */
enum class TunnelPortProblem {
    /** The local port is not between [TunnelPortRules.MIN_PORT] and [TunnelPortRules.MAX_PORT]. */
    LOCAL_OUT_OF_RANGE,

    /**
     * The app would listen on a local port below [TunnelPortRules.FIRST_UNPRIVILEGED_PORT]: Android
     * never lets an app do that, the bind fails with a permission error whatever the port.
     */
    LOCAL_RESERVED,

    /** The remote port is not between [TunnelPortRules.MIN_PORT] and [TunnelPortRules.MAX_PORT]. */
    REMOTE_OUT_OF_RANGE,
}

/**
 * The ports a tunnel may use on Android. One set of rules for the form, which refuses to save a
 * tunnel that breaks them, and for the start, which refuses to start one that came from elsewhere
 * (the sync, an import) instead of crashing on it: `ServerSocket` throws an
 * `IllegalArgumentException`, not an `IOException`, for a port out of range.
 *
 * Kept free of Android and of the tunnel manager on purpose: the same file serves the `patch/` and
 * the `minor/` branches.
 */
object TunnelPortRules {
    const val MIN_PORT = 1
    const val MAX_PORT = 65535

    /** The first port an app may listen on. Below it the kernel asks for a privilege no app has. */
    const val FIRST_UNPRIVILEGED_PORT = 1024

    /**
     * True when the app listens on the local port of a tunnel of [type]: a local forward and a
     * SOCKS5 proxy do. A remote forward does not: its local port is where the app connects, on the
     * phone, when the server hands it a connection, so any port in range is fine there.
     */
    fun listensLocally(type: TunnelType): Boolean = when (type) {
        TunnelType.LOCAL_FORWARD,
        TunnelType.DYNAMIC_SOCKS5,
        -> true

        TunnelType.REMOTE_FORWARD -> false
    }

    /** What is wrong with [port] as the local port of a tunnel of [type], or null when it can be used. */
    fun localPortProblem(type: TunnelType, port: Int): TunnelPortProblem? = when {
        port !in MIN_PORT..MAX_PORT -> TunnelPortProblem.LOCAL_OUT_OF_RANGE
        listensLocally(type) && port < FIRST_UNPRIVILEGED_PORT -> TunnelPortProblem.LOCAL_RESERVED
        else -> null
    }

    /**
     * What is wrong with [port] as the remote port of a tunnel of [type], or null when it can be
     * used. A SOCKS5 proxy has no remote port: never a problem there. A remote port below 1024 is
     * allowed: whether the server lets the account listen on it is the server's decision.
     */
    fun remotePortProblem(type: TunnelType, port: Int): TunnelPortProblem? = when {
        type == TunnelType.DYNAMIC_SOCKS5 -> null
        port !in MIN_PORT..MAX_PORT -> TunnelPortProblem.REMOTE_OUT_OF_RANGE
        else -> null
    }

    /** The first problem with the ports of [config], the local port first, or null when both can be used. */
    fun problemOf(config: TunnelConfig): TunnelPortProblem? =
        localPortProblem(config.type, config.localPort)
            ?: remotePortProblem(config.type, config.remotePort)
}
