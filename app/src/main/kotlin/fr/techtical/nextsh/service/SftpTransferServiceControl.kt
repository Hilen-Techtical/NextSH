// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.service

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.techtical.nextsh.shared.util.errorClass
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the rest of the app says to [SftpTransferService] without being a screen or the service itself.
 * Its own class so that the code that needs it (the closing of the "Wipe the vault" action) is tested
 * without Android.
 */
@Singleton
class SftpTransferServiceControl @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Tells the service to cancel every transfer and release its holds ([SftpTransferService.ACTION_CANCEL_ALL_TRANSFERS]).
     * False when the system refuses to start the service from the background: nothing is running then
     * either, a transfer keeps the service in the foreground.
     */
    fun cancelAll(): Boolean = try {
        context.startService(
            Intent(context, SftpTransferService::class.java).setAction(SftpTransferService.ACTION_CANCEL_ALL_TRANSFERS),
        )
        true
    } catch (e: IllegalStateException) {
        Timber.w("The transfer service could not be reached (${errorClass(e)})")
        false
    }
}
