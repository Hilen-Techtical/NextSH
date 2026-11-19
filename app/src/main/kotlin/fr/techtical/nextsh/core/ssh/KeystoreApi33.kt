// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import android.annotation.SuppressLint
import android.os.Build

/**
 * What the Keystore's own errors say about a failure, read from API 33 where
 * `android.security.KeyStoreException` exists (before, [NONE]).
 *
 * @property userAuthenticationRequired the key asks for a fingerprint that was not given, or not for
 * this operation
 * @property confirmsKeyLoss the key does not exist any more, or is corrupted: regenerating it is right
 * @property transient the Keystore itself says the failure may pass (`isTransientFailure`)
 * @property numericCode the numeric error code of the first Keystore error of the chain, or null if
 * there is none (a number, never sensitive, for the log)
 */
internal class KeystoreSigns(
    val userAuthenticationRequired: Boolean,
    val confirmsKeyLoss: Boolean,
    val transient: Boolean,
    val numericCode: Int?,
) {
    /** True if the chain holds a Keystore error at all. */
    val present: Boolean get() = numericCode != null

    companion object {
        val NONE = KeystoreSigns(
            userAuthenticationRequired = false,
            confirmsKeyLoss = false,
            transient = false,
            numericCode = null,
        )
    }
}

/**
 * The part of [classifyLocalKeyFailure] that needs `android.security.KeyStoreException`, a class
 * that only exists from API 33. It lives in an object of its own, called with the SDK level, so that
 * no older runtime ever has to resolve the class.
 */
internal object KeystoreApi33 {

    /**
     * The signs the Keystore errors of [chain] give. The SDK level is a parameter, not
     * `Build.VERSION.SDK_INT`, so that a JVM test can reach the API 33 branch; lint cannot follow it
     * like the real field, hence the one suppression of the file, on the one function that uses the
     * new class.
     */
    @SuppressLint("NewApi")
    fun inspect(chain: List<Throwable>, sdkInt: Int): KeystoreSigns {
        if (sdkInt < Build.VERSION_CODES.TIRAMISU) return KeystoreSigns.NONE
        var userAuthenticationRequired = false
        var confirmsKeyLoss = false
        var transient = false
        var numericCode: Int? = null
        for (cause in chain) {
            if (cause !is android.security.KeyStoreException) continue
            val code = cause.numericErrorCode
            if (numericCode == null) numericCode = code
            // Plain comparisons: a when on these codes makes lint ask for every one of the 16 (SwitchIntDef).
            if (code == android.security.KeyStoreException.ERROR_USER_AUTHENTICATION_REQUIRED) userAuthenticationRequired = true
            if (code == android.security.KeyStoreException.ERROR_KEY_DOES_NOT_EXIST ||
                code == android.security.KeyStoreException.ERROR_KEY_CORRUPTED
            ) {
                confirmsKeyLoss = true
            }
            if (cause.isTransientFailure) transient = true
        }
        return KeystoreSigns(userAuthenticationRequired, confirmsKeyLoss, transient, numericCode)
    }
}
