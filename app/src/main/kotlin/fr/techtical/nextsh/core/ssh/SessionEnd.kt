// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

/**
 * How a registered SSH session ended, told to the [SessionEndListener]s of the
 * [SshSessionManager].
 */
sealed interface SessionEnd {

    /**
     * The app closed it: the last reference was released, or [SshSessionManager.disconnect] forced
     * it. Whoever still counted on it (a tunnel, an SFTP client) lost its transport all the same.
     */
    data object Closed : SessionEnd

    /** The transport dropped under it: the network, the server, a broken link. [reason] is the name of the SSHJ disconnect reason. */
    data class Lost(val reason: String) : SessionEnd

    /**
     * The liveness watchdog heard nothing from the server for too long: the link is mute (a NAT
     * that forgot the connection, a Wi-Fi without Internet, a server gone without a word). The
     * transport may still look connected to SSHJ. See [SshSessionManager.declareUnresponsive].
     */
    data object Unresponsive : SessionEnd

    /** The SSHJ disconnect reason that stands for this end, `BY_APPLICATION` for [Closed]. */
    val reasonName: String
        get() = when (this) {
            Closed -> "BY_APPLICATION"
            is Lost -> reason
            Unresponsive -> "KEEPALIVE_TIMEOUT"
        }
}

/**
 * Told, once per session, when a session leaves the registry of the [SshSessionManager]: a session
 * that nobody uses any more, one that is forced shut, or one whose transport died. Never told for
 * a session that was not registered (a connection that failed).
 *
 * Called on the thread that noticed the end: the SSH transport thread for a lost connection, the
 * caller's own for a release. It must be short, must not block, and must not call back into the
 * session manager in a way that waits for another thread. An exception it throws is logged and
 * does not reach the other listeners.
 */
fun interface SessionEndListener {
    fun onSessionEnded(sessionId: String, end: SessionEnd)
}
