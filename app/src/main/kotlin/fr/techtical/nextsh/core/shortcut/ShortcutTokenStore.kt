// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.shortcut

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.techtical.nextsh.core.SecretToken
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The secret that the launcher shortcuts of the app carry, so that `MainActivity` acts only on a
 * shortcut the app published itself.
 *
 * The activity is exported (launcher): any app can start it with the extras of a shortcut. Without
 * a secret, a forged intent would connect to a host, or start a tunnel (a local port that every
 * app of the phone can then use), as soon as the vault is open. [ShortcutUpdater] puts the token in
 * every shortcut it publishes, and the activity ignores a shortcut whose token is not this one. The
 * notification of the terminal sessions (`TerminalSessionService`) opens the same activity through
 * the same checked path, so its `PendingIntent` carries the token too.
 *
 * One token per install, made on first use and then kept: the shortcuts the user pinned to the
 * home screen hold the token they were published with, so it must not change from one launch to
 * the next. It lives in a private file of the app and not in the vault, because the shortcuts are
 * published at startup, before the vault is unlocked. The file is out of the backup and the device
 * transfer (`data_extraction_rules.xml`), and `allowBackup` is off: the system backup does not copy
 * it. It is not secret from the system itself: the shortcut service of the system persists the
 * intent of every shortcut, token included (it shows in `dumpsys shortcut`, which needs adb or a
 * privileged caller). It does not hand that intent to launcher apps, nor to other apps, which is
 * what the token is for: those cannot build a shortcut intent that the activity accepts.
 *
 * The notification of the terminal sessions follows the same trust model. Its `PendingIntent` holds
 * the intent with the token, and the system holds it, as it holds the shortcut intents: readable
 * through `dumpsys` (adb or a privileged caller), not by another app, which cannot read or fill in
 * an immutable `PendingIntent` it did not create.
 *
 * The token is a secret: it never goes to a log or to saved state. It travels only in the extras of
 * the intents the app builds for the system to hand back to it, the shortcuts and the notification
 * of the terminal sessions, never in an intent to another app or in a Bundle the app keeps.
 */
@Singleton
class ShortcutTokenStore internal constructor(
    private val prefs: SharedPreferences,
    private val random: SecureRandom,
) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        SecureRandom(),
    )

    private val lock = Any()

    /** The token of this install, made the first time it is asked for. */
    fun token(): String = synchronized(lock) {
        val stored = prefs.getString(KEY_TOKEN, null)
        if (stored != null && SecretToken.isWellFormed(stored)) return@synchronized stored
        val fresh = SecretToken.generate(random)
        // The new value is seen at once by every reader of the file in this process; the write to
        // disk follows. A process that died in between would make another token at its next start,
        // which republishes the shortcuts anyway.
        prefs.edit { putString(KEY_TOKEN, fresh) }
        fresh
    }

    /**
     * Whether [candidate] is the token of this install, compared in constant time. Null, empty,
     * wrong or of another install: false.
     */
    fun matches(candidate: String?): Boolean =
        SecretToken.matches(SecretToken.toBytes(token()), candidate)

    companion object {
        /** Keep in step with the exclusions of `res/xml/data_extraction_rules.xml`. */
        const val PREFS_NAME = "nextsh_shortcut"
        private const val KEY_TOKEN = "token"
    }
}
