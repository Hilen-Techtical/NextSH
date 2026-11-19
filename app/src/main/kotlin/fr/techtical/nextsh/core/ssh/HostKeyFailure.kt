// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import androidx.annotation.StringRes
import fr.techtical.nextsh.R
import fr.techtical.nextsh.shared.domain.model.SshErrorCode
import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.common.SSHException
import java.io.IOException
import java.util.IdentityHashMap

/** Chains are short; this only guards against a cause cycle. */
private const val MAX_CAUSE_DEPTH = 32

/**
 * How the host-key prompt of one connection attempt ended, when it ended without the user's answer.
 * The marker that tells these apart from a key that does not match: for SSHJ all of them are the
 * same refusal of the key (`HOST_KEY_NOT_VERIFIABLE`), which [isHostKeyFailure] reads as a
 * security event and the reconnection as `HOST_KEY_MISMATCH`, with a message that talks about a
 * key that changed. Said the way [Fido2PromptEnd] does for the security key sheet.
 *
 * A key the user refuses is not one of these: it is a refusal, and keeps its reading.
 */
internal enum class HostKeyPromptEnd {
    /**
     * The app was not on screen when the prompt was asked for (a reconnection driven by a service):
     * no dialog was shown, nobody could answer. The user is asked to open the app.
     */
    NO_SCREEN,

    /** Nobody answered before the deadline of the prompt. */
    TIMED_OUT,

    /** The connection was cancelled (screen left, tunnel start abandoned) and took the prompt with it. */
    CANCELLED,
}

/**
 * The connection failed because its host-key prompt ended with [end], not because the key did not
 * match. Thrown by the client factory around SSHJ's own exception (the [cause]), an `IOException`
 * so that every connect path reads it in its network failure branch, where `hostKeyResultOrNull`
 * looks at it first.
 */
internal class HostKeyPromptEndedException(
    val end: HostKeyPromptEnd,
    cause: Throwable,
) : IOException("Host key prompt ended: $end", cause)

/** The [HostKeyPromptEnd] that [e], or anything in its cause chain, carries; null when the host key prompt is not the cause. */
internal fun hostKeyPromptEnd(e: Throwable): HostKeyPromptEnd? {
    val seen = IdentityHashMap<Throwable, Unit>()
    var cause: Throwable? = e
    while (cause != null && seen.size < MAX_CAUSE_DEPTH && seen.put(cause, Unit) == null) {
        if (cause is HostKeyPromptEndedException) return cause.end
        cause = cause.cause
    }
    return null
}

/**
 * The error code of a connection whose host-key prompt ended with [end].
 *
 * The `FIDO2_*` codes are reused on purpose, as for the fingerprint prompt (see
 * [biometricAuthFailureCode]): they are the values the shared enum has for "the user cancelled /
 * did not answer an interactive prompt", they are non-retryable, and every caller that matters (the
 * tunnels above all) already treats them so, which is what keeps an automatic reconnection from
 * opening the same prompt again into an empty screen. A new code would change `:shared`.
 * [SshErrorCode.HOST_KEY_MISMATCH] is not an option: it says the key changed, and the user would be
 * sent to look for an attack that is not there.
 */
internal fun hostKeyPromptEndCode(end: HostKeyPromptEnd): SshErrorCode = when (end) {
    HostKeyPromptEnd.NO_SCREEN -> SshErrorCode.FIDO2_TIMEOUT
    HostKeyPromptEnd.TIMED_OUT -> SshErrorCode.FIDO2_TIMEOUT
    HostKeyPromptEnd.CANCELLED -> SshErrorCode.FIDO2_USER_CANCELLED
}

/** The message the user reads for [hostKeyPromptEndCode]: what to do, not what SSH said. */
@StringRes
internal fun hostKeyPromptEndMessageRes(end: HostKeyPromptEnd): Int = when (end) {
    HostKeyPromptEnd.NO_SCREEN -> R.string.auth_error_host_key_no_screen
    HostKeyPromptEnd.TIMED_OUT -> R.string.auth_error_host_key_timeout
    HostKeyPromptEnd.CANCELLED -> R.string.auth_error_fido2_cancelled
}

/**
 * True when [e], or anything in its cause chain, is SSHJ's refusal of the host key.
 *
 * SSHJ's `TransportImpl.verifyHost` throws `TransportException(HOST_KEY_NOT_VERIFIABLE, ...)` when
 * no `HostKeyVerifier` accepts the key: while connecting (`client.connect()`, so before any
 * authentication, inside the client factory) and also in the middle of an authentication, on a
 * re-key. It is a security event: the key the server shows is not the one that was trusted.
 *
 * The decision is the disconnect reason, never the text of the message: the message is SSHJ's own
 * wording, and an unrelated failure can mention a host key too.
 *
 * It cannot tell a key that does not match from a prompt that nobody could or did answer: both
 * end in the same exception. The second carries a [HostKeyPromptEnd] (see [hostKeyPromptEnd]),
 * which is looked at first.
 */
internal fun isHostKeyFailure(e: Throwable): Boolean {
    val seen = IdentityHashMap<Throwable, Unit>()
    var cause: Throwable? = e
    while (cause != null && seen.size < MAX_CAUSE_DEPTH && seen.put(cause, Unit) == null) {
        if (cause is SSHException && cause.disconnectReason == DisconnectReason.HOST_KEY_NOT_VERIFIABLE) return true
        cause = cause.cause
    }
    return false
}
