// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.shared.domain.model.SshErrorCode

/**
 * True for the failures that no automatic retry may repeat: the tunnel stays in error with its
 * message, held out of the automatic reconnection, until the user starts it again. This is the one
 * predicate that decides it (`SshTunnelManager.holdReconnectIfNonRetryable`, used by the
 * reconnection, the auto-start and the manual start).
 *
 * - **Authentication**: a refused or missing credential, a key that cannot be read here (wrong
 *   passphrase), a missing security key, a security key prompt the user cancelled or let expire,
 *   a key error. The answer comes from the user or from the credentials, and with a FIDO2 host
 *   every attempt would pop the key sheet again over whatever screen the user is on.
 * - **Host key mismatch**: the key the server shows is not the one that was trusted. That is a
 *   security event (a possible man-in-the-middle), not a hiccup: it is never retried behind the
 *   user's back, every retry would be one more connection to a server that is not the one that was
 *   trusted. The user sees the message and decides.
 *
 * Network failures (unreachable host, timeout, port in use) are not listed: they are what the
 * automatic reconnection is for. [SshErrorCode.AUTH_EXPIRED] is not listed either: it is an
 * authentication window (the vault's, or a biometric key's) that the user renews with the
 * fingerprint on the screen that starts the connection, so nothing is held. The reconnection does
 * not retry it with its back-off either (it would get the same answer): it stops at the first
 * attempt and leaves the tunnel in error, see `ReconnectTunnelsUseCase`.
 *
 * The `when` is exhaustive on purpose: a new [SshErrorCode] does not compile until someone decides
 * whether it may be retried.
 */
fun SshErrorCode.isNonRetryable(): Boolean = when (this) {
    SshErrorCode.AUTH_FAILED,
    SshErrorCode.FIDO2_NO_KEY,
    SshErrorCode.FIDO2_USER_CANCELLED,
    SshErrorCode.FIDO2_TIMEOUT,
    SshErrorCode.HOST_KEY_MISMATCH,
    -> true

    SshErrorCode.HOST_UNREACHABLE,
    SshErrorCode.PORT_IN_USE,
    SshErrorCode.TIMEOUT,
    SshErrorCode.UNKNOWN,
    SshErrorCode.AUTH_EXPIRED,
    -> false
}
