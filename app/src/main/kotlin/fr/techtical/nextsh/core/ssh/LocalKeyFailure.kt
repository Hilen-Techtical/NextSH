// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import android.os.Build
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import com.hierynomus.sshj.common.KeyDecryptionFailedException
import fr.techtical.nextsh.core.crypto.MISSING_BIOMETRIC_KEY_MESSAGE
import fr.techtical.nextsh.shared.domain.model.SshErrorCode
import fr.techtical.nextsh.shared.util.errorClass
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.connection.ConnectionException
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.userauth.UserAuthException
import java.security.GeneralSecurityException
import java.security.KeyStoreException
import java.security.UnrecoverableKeyException
import java.util.IdentityHashMap

/**
 * A problem with the private key itself, on this device, that no server and no retry can fix.
 * Told apart from a refusal of the credential (the server said no) and from a broken exchange
 * (the transport failed), which are not the same thing for the user nor for the reconnection.
 */
internal enum class LocalKeyFailure {
    /**
     * The key file cannot be read or decrypted: wrong or missing passphrase, corrupt or unsupported
     * key, unrecognized format, bad certificate. For an Android Keystore key it is the catch-all of
     * every failure that does not prove the key is gone (a provider error, a Keystore that
     * restarted, an operation that was pruned): the key is not blamed and the user may try again,
     * see [BIOMETRIC_KEY_INVALID] for what does prove it.
     */
    UNREADABLE,

    /**
     * The Android Keystore key was invalidated (biometrics changed) or deleted: it must be
     * regenerated. Only these signs say so: [KeyPermanentlyInvalidatedException]; the missing-entry
     * [KeyStoreException] of the app's own `KeystoreManager` ([MISSING_BIOMETRIC_KEY_MESSAGE]); from
     * API 33 the Keystore's own error codes for a key that does not exist and for a corrupted one
     * (unless the error says the failure is transient); and [UnrecoverableKeyException], which
     * counts only conditionally, since it is also raised for a lookup that may fail for a moment:
     * not when a Keystore error of the chain says the failure is transient or is another one than
     * the two above (see [classifyLocalKeyFailure]). Regenerating a healthy key cuts the access to
     * every server it is authorized on, so nothing weaker may send the user there.
     */
    BIOMETRIC_KEY_INVALID,

    /**
     * The Android Keystore key needs a fresh biometric authentication (its auth window ran out).
     * Not a failure of the key: the user authenticates again, like for an expired vault window.
     */
    BIOMETRIC_AUTH_EXPIRED,
}

/**
 * Prefix of the message `KeyedAuthMethod` (SSHJ) gives the [UserAuthException] it throws when the
 * `KeyProvider` fails: "Problem getting public key from ..." / "Problem getting private key from ...".
 * The key file is read and decrypted lazily, during the authentication.
 */
private const val KEY_PROBLEM_PREFIX = "Problem getting"

/** Chains are short; this only guards against a cause cycle. */
private const val MAX_CAUSE_DEPTH = 32

/**
 * The local key problem that [e] stands for, found in its cause chain, or null if there is none.
 *
 * Recognized, most specific first:
 *  - [UserNotAuthenticatedException], and on API 33+ the `android.security.KeyStoreException` whose
 *    numeric code says user authentication is required: the biometric window ran out, or the
 *    signature was not authorized ([sdkInt] guards the second, it does not exist before);
 *  - [KeyPermanentlyInvalidatedException], the missing-entry [KeyStoreException] of the app's
 *    `KeystoreManager`, and on API 33+ a Keystore error that says the key does not exist or is
 *    corrupted: the Keystore key itself is gone. They are [GeneralSecurityException]s too, the more
 *    specific reading wins over the generic one;
 *  - [UnrecoverableKeyException], which is how the AndroidKeyStore reports a key it cannot get, and
 *    **not airtight proof**: it is also raised for a lookup error that may be transient. From API 33
 *    the Keystore error in its cause chain decides: a transient failure, or any code but "does not
 *    exist" and "corrupted", reads as a plain unreadable key (retry); with no such error in the
 *    chain (before API 33, or a bare exception) it reads as a lost key. That trade-off is kept on
 *    purpose: below API 33 nothing tells the two apart, and the message for a lost key only asks
 *    to regenerate when the sign is the strongest one available;
 *  - [KeyDecryptionFailedException], and the "Problem getting ... key" exception of `KeyedAuthMethod`
 *    together with whatever it wraps: the key file could not be read or decrypted;
 *  - any other [GeneralSecurityException] on the way, **unless it sits under a transport exception**:
 *    a cipher or MAC failure of the SSH transport is a `GeneralSecurityException` too, wrapped by a
 *    `TransportException`, and it is the connection that broke, not the key. From a
 *    [TransportException] or a [ConnectionException] down, security exceptions are the transport's.
 *
 * [sdkInt] is a parameter so that the API 33 branch can be tested on a JVM, where
 * `Build.VERSION.SDK_INT` is 0.
 */
internal fun classifyLocalKeyFailure(e: Throwable, sdkInt: Int = Build.VERSION.SDK_INT): LocalKeyFailure? {
    val chain = causeChain(e)
    val keystore = KeystoreApi33.inspect(chain, sdkInt)
    var unreadable = false
    var invalid = keystore.confirmsKeyLoss && !keystore.transient
    var expired = keystore.userAuthenticationRequired
    var unrecoverable = false
    var underTransport = false

    for (cause in chain) {
        when {
            cause is UserNotAuthenticatedException -> expired = true
            cause is KeyPermanentlyInvalidatedException -> invalid = true
            cause is UnrecoverableKeyException -> unrecoverable = true
            cause is KeyStoreException && cause.message == MISSING_BIOMETRIC_KEY_MESSAGE -> invalid = true
            cause is KeyDecryptionFailedException -> unreadable = true
            cause is UserAuthException && cause.message?.startsWith(KEY_PROBLEM_PREFIX) == true -> unreadable = true
            cause is GeneralSecurityException && !underTransport -> unreadable = true
        }
        if (cause is TransportException || cause is ConnectionException) underTransport = true
    }
    if (unrecoverable) {
        // Not airtight (see above): a Keystore error that is there and does not confirm the loss wins.
        if (!keystore.present || (keystore.confirmsKeyLoss && !keystore.transient)) invalid = true else unreadable = true
    }
    return when {
        expired -> LocalKeyFailure.BIOMETRIC_AUTH_EXPIRED
        invalid -> LocalKeyFailure.BIOMETRIC_KEY_INVALID
        unreadable -> LocalKeyFailure.UNREADABLE
        else -> null
    }
}

/**
 * What to write in a log line about the problem that stopped a Keystore key: the class of its root
 * cause and, from API 33, the numeric code of the Keystore's own error when there is one. Both are
 * fixed names and numbers, never what the exception says. [sdkInt] as in [classifyLocalKeyFailure].
 */
internal fun keyProblemLogDescription(problem: Throwable, sdkInt: Int = Build.VERSION.SDK_INT): String {
    val chain = causeChain(problem)
    val name = errorClass(chain.last())
    val code = KeystoreApi33.inspect(chain, sdkInt).numericCode
    return if (code == null) name else "$name, code=$code"
}

/** [e] and its causes, outermost first. Bounded, and safe against a cause cycle. */
internal fun causeChain(e: Throwable): List<Throwable> {
    val seen = IdentityHashMap<Throwable, Unit>()
    val chain = mutableListOf<Throwable>()
    var cause: Throwable? = e
    while (cause != null && seen.size < MAX_CAUSE_DEPTH && seen.put(cause, Unit) == null) {
        chain += cause
        cause = cause.cause
    }
    return chain
}

/** The innermost cause of [e]: the class of what really failed, which is what a log line may say of it. */
internal fun rootCause(e: Throwable): Throwable = causeChain(e).last()

/**
 * A failure that happened while a connect function was loading the key, or in the cryptography of
 * its authentication, wrapped so that the catch-all of the connect function can tell it from a
 * failure of the connection. Whatever the exception was (an `IllegalArgumentException` for an
 * unrecognized format or a bad certificate, an `IllegalStateException` of SSHJ for an unsupported
 * cipher, a Keystore exception), it is about the key, here, and not about the network.
 */
internal class KeyFailure(cause: Throwable) : Exception(null, cause) {
    /** What went wrong with the key, classified; an unclassified problem with the key is an unreadable key. */
    val failure: LocalKeyFailure get() = classifyLocalKeyFailure(cause ?: this) ?: LocalKeyFailure.UNREADABLE
}

/**
 * The error code of a connection that failed with [failure]: all of them are decided on this device
 * and no retry changes them, except the Keystore key whose biometric window ran out, which the user
 * renews by authenticating again (`AUTH_EXPIRED`).
 */
internal fun localKeyFailureCode(failure: LocalKeyFailure): SshErrorCode = when (failure) {
    LocalKeyFailure.UNREADABLE -> SshErrorCode.AUTH_FAILED
    LocalKeyFailure.BIOMETRIC_KEY_INVALID -> SshErrorCode.AUTH_FAILED
    LocalKeyFailure.BIOMETRIC_AUTH_EXPIRED -> SshErrorCode.AUTH_EXPIRED
}

/** Runs [block], which loads or parses a key: whatever it throws (cancellation aside) is a [KeyFailure]. */
internal inline fun <T> loadingKey(block: () -> T): T =
    try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        throw KeyFailure(e)
    }

/**
 * Runs [block], an authentication with a key on [client]: a runtime exception (cancellation aside)
 * is the cryptography of the key (SSHJ's `SSHRuntimeException` around a signature, an
 * `IllegalStateException` for a cipher it does not support) and becomes a [KeyFailure]. The
 * exceptions of the exchange are `IOException`s (`TransportException`, `UserAuthException`...) and
 * pass through untouched.
 *
 * One runtime exception is not about the key: `SSHClient.auth` starts with `checkConnected()`, which
 * throws `IllegalStateException("Not connected")` when the link died between `connect()` and the
 * authentication. A [client] that is no longer connected when the exception reaches us means the
 * connection was lost, whatever the exception says: it is reported the way SSHJ reports a transport
 * that dies during the authentication (see [connectionLostBeforeAuthentication]), so that it is
 * retried and the key is not blamed.
 */
internal inline fun <T> authenticatingWithKey(client: SSHClient, block: () -> T): T =
    try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: RuntimeException) {
        throw if (client.isConnected) KeyFailure(e) else connectionLostBeforeAuthentication(e)
    }

/**
 * Runs [block], an authentication on [client] whose runtime exceptions are not those of a key of
 * this device: the password, and the FIDO2 key (its cryptography is the key's own, see
 * [authenticatingWithKey] for the files and the Keystore).
 *
 * Only one runtime exception has a meaning here, the one [authenticatingWithKey] documents:
 * `SSHClient.auth` starts with `checkConnected()` and throws `IllegalStateException("Not connected")`
 * when the link died between `connect()` and the authentication. A [client] that is no longer
 * connected when a runtime exception (cancellation aside) reaches us means the connection was
 * lost, whatever the exception says: it is reported the same way, through
 * [connectionLostBeforeAuthentication], so that it is retried and not shown as an unknown error.
 * On a client that is still connected the exception is left alone.
 */
internal inline fun <T> authenticatingOnConnection(client: SSHClient, block: () -> T): T =
    try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: RuntimeException) {
        throw if (client.isConnected) e else connectionLostBeforeAuthentication(e)
    }

/**
 * The exception of an authentication whose connection was lost before it began: a
 * [UserAuthException] around a [TransportException], the shape SSHJ gives a transport that dies
 * during the authentication, which [classifyUserAuthFailure] reads as a transport failure
 * (`HOST_UNREACHABLE`, retried).
 */
internal fun connectionLostBeforeAuthentication(cause: Throwable): UserAuthException =
    UserAuthException(
        "Not connected",
        TransportException(DisconnectReason.CONNECTION_LOST, "Connection lost before the authentication", cause),
    )
