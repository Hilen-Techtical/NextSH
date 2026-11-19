// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the "authentication required" notification needs once a tunnel was added to the waiting
 * list: the [token] to put in its intent, and the [labels] of every waiting tunnel to name them.
 *
 * The labels are user text: they go to the notification only, never to a log. The token is a
 * secret for as long as it is valid: [toString] hides both so that a log line cannot carry them.
 */
class AuthResumeNotice(val token: String, val labels: List<String>) {
    override fun toString(): String = "AuthResumeNotice"
}

/** What [PendingAuthResume.retainWaiting] did to the waiting list. */
sealed interface WaitingChange {

    /** No waiting tunnel left the list (or none was waiting): the notification, if there is one, is still right. */
    data object Unchanged : WaitingChange

    /**
     * Some waiting tunnels left and others still wait: the notification still names the ones that
     * left, and counts them. [notice] is what it must say now (the labels that remain, with the
     * token it already carries, which stays good: no tunnel was added).
     */
    class Narrowed(val notice: AuthResumeNotice) : WaitingChange {
        override fun toString(): String = "Narrowed"
    }

    /** The last waiting tunnel left: the token is void, there is nothing left for the notification to restart. */
    data object Emptied : WaitingChange
}

/**
 * The tunnels that stopped reconnecting because their host needs the user (a security key or a
 * fingerprint) while the app was not on screen, and the one-time token that lets the notification
 * restart them.
 *
 * Why a token and not the ids in the intent. `MainActivity` is exported (launcher, shortcuts): any
 * app can start it with any extras. Tunnel ids in the extras of the notification would therefore be
 * forgeable, and a forged intent would start tunnels, and open the key sheet, on its own say. The
 * intent carries only a random token; the ids stay here, in memory. A token is good for one tap:
 *
 * - [register] issues a new token every time a tunnel is added, so the intent of an older
 *   notification is void as soon as a newer one is posted;
 * - [consume] compares in constant time, accepts the current token once, and returns the ids. A
 *   wrong, old or replayed token returns null and changes nothing.
 *
 * The state is in memory on purpose. After the death of the process the holder is empty: tapping a
 * stale notification just opens the app, the tunnels stay in error and the user restarts them by
 * hand. Nothing here is written to disk, to a Bundle or to a log.
 *
 * Two stages. The tunnels *waiting* for the user are what the notification is about. Once the
 * token is consumed they become *ready*: validated, and waiting for the vault to be unlocked and
 * the tunnel list to be on screen ([ready], [takeReady]). The stage lives here and not in the
 * activity so that a rotation or a recreation of the activity between the tap and the start does
 * not lose them.
 *
 * Only the *ready* stage expires, after [READY_TTL_MS]. The tap is the consent: a request the user
 * made and then did not follow up must not wake up much later, or a user who tapped the
 * notification, left at the unlock screen and came back hours later through the launcher would get
 * tunnels restarted, and their launcher shortcut (consumed at unlock, behind the same screen)
 * dropped. The time is counted from the tap ([consume]), not from the report. The *waiting* stage
 * does not age: a tunnel that dropped at night is still waiting when the user taps the
 * notification in the morning, and that tap must restart it. It lives until [retainWaiting] finds
 * the tunnel no longer in error, until [consume], until a new [register] replaces the token, or
 * until the process dies. What is restarted is checked again at the start (`ResumeTunnelsUseCase`).
 * Expiry is lazy, applied whenever the holder is touched; the activity calls [dropExpired] each
 * time it comes up, which is when it matters. The clock is the monotonic one of the JVM,
 * injectable for tests.
 */
@Singleton
class PendingAuthResume internal constructor(
    private val clock: () -> Long,
) {

    @Inject
    constructor() : this({ System.nanoTime() / NANOS_PER_MILLI })

    private val lock = Any()
    private val random = SecureRandom()

    /** Tunnel id to its label, in the order they were reported. Does not age, see the class comment. */
    private val waiting = LinkedHashMap<String, String>()

    /** The only token that is good now, as the bytes of its text; null when there is none. */
    private var currentToken: ByteArray? = null

    /** When [ready] was last filled (the last tap), on [clock]. */
    private var readySinceMs = 0L

    private val _ready = MutableStateFlow<List<String>?>(null)

    /**
     * The ids of tunnels whose token was consumed and that are waiting to be started, or null. The
     * list screen starts them (once the vault is unlocked, it is the only place it can be on
     * screen) and empties this through [takeReady]. Goes back to null by itself [READY_TTL_MS] after
     * the tap, at the next [dropExpired].
     */
    val ready: StateFlow<List<String>?> = _ready.asStateFlow()

    /**
     * Adds [tunnelId] (named [label] in the notification) to the waiting list and issues a new
     * token, which voids the one of the previous notification. Returns what the notification
     * needs; adding a tunnel that is already waiting only refreshes its label.
     */
    fun register(tunnelId: String, label: String): AuthResumeNotice = synchronized(lock) {
        waiting[tunnelId] = label
        val token = SecretToken.generate(random)
        currentToken = SecretToken.toBytes(token)
        AuthResumeNotice(token, waiting.values.toList())
    }

    /**
     * Spends [candidate]. If it is the current token, returns the ids of the waiting tunnels, which
     * become [ready], and the token is void from now on (a second call with it returns null), however
     * long ago the notification was posted. Anything else (null, empty, wrong, old, already spent)
     * returns null and changes nothing.
     */
    fun consume(candidate: String?): List<String>? {
        if (candidate.isNullOrEmpty()) return null
        return synchronized(lock) {
            val expected = currentToken ?: return@synchronized null
            // Constant time, see SecretToken.
            if (!SecretToken.matches(expected, candidate)) return@synchronized null
            val ids = waiting.keys.toList()
            waiting.clear()
            currentToken = null
            // Added to what is already ready, if the user tapped twice before the list took it.
            _ready.value = ((_ready.value ?: emptyList()) + ids).distinct()
            readySinceMs = clock()
            ids
        }
    }

    /**
     * Hands over the ready tunnels once, and empties the list: two screens cannot start the same
     * ones. Null when there are none, or when they have expired.
     */
    fun takeReady(): List<String>? = synchronized(lock) {
        purgeExpiredReady()
        val ids = _ready.value
        _ready.value = null
        ids
    }

    /**
     * Forgets the ready tunnels that have waited more than [READY_TTL_MS] since the tap. The
     * activity calls it each time it comes up, so that a request nobody followed up does not act, or
     * hide a launcher shortcut, hours later. The waiting tunnels are not touched: they do not age.
     */
    fun dropExpired() {
        synchronized(lock) { purgeExpiredReady() }
    }

    /**
     * Keeps in the waiting list only the tunnels in [erroredIds], the ones still in error: a tunnel
     * that was started again by any path, stopped or deleted is not waiting for the user any more.
     * Tells what the caller must do with the notification: nothing ([WaitingChange.Unchanged]),
     * take it away because the list was emptied ([WaitingChange.Emptied], the token is void then,
     * there is nothing left for it to restart), or post it again with the labels that remain
     * ([WaitingChange.Narrowed]): left as it was, it would still name and count the tunnels that
     * are gone.
     */
    fun retainWaiting(erroredIds: Set<String>): WaitingChange = synchronized(lock) {
        if (waiting.isEmpty()) return@synchronized WaitingChange.Unchanged
        val before = waiting.size
        waiting.keys.retainAll(erroredIds)
        if (waiting.isEmpty()) {
            currentToken = null
            return@synchronized WaitingChange.Emptied
        }
        if (waiting.size == before) return@synchronized WaitingChange.Unchanged
        // Waiting is not empty, so a registration set the token and nothing has voided it since.
        val token = currentToken?.toString(Charsets.US_ASCII) ?: return@synchronized WaitingChange.Unchanged
        WaitingChange.Narrowed(AuthResumeNotice(token, waiting.values.toList()))
    }

    /** Called with [lock] held. */
    private fun purgeExpiredReady() {
        if (_ready.value != null && clock() - readySinceMs >= READY_TTL_MS) _ready.value = null
    }

    companion object {
        /** The extra of the notification intent that carries the token, and nothing else. */
        const val EXTRA_TOKEN = "fr.techtical.nextsh.extra.AUTH_RESUME_TOKEN"

        /** How long a validated request (a tap) waits for the unlock and the list before it is forgotten. */
        const val READY_TTL_MS = 10 * 60_000L

        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
