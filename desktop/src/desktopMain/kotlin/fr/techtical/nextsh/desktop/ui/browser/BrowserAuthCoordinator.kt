// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.ui.browser

import fr.techtical.nextsh.shared.util.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.atomic.AtomicLong

/**
 * Sends one Chrome DevTools Protocol command to the browser. Implementations must not
 * block and must not wait for the answer: the answer of a command that targets a request
 * which is gone (page navigated away) never comes.
 */
fun interface CdpSender {
    fun send(method: String, params: JsonObject)
}

/**
 * What the sign-in panel needs to render. Holds no secret.
 *
 * @property id identifies this very prompt: answers ([BrowserAuthCoordinator.submit],
 *   [BrowserAuthCoordinator.cancel]) carry it so a late click on a panel that was
 *   already replaced cannot answer the next challenge.
 * @property origin `host:port` of the tunnelled service asking for credentials.
 * @property realm sanitized server-provided realm, or null when absent or blank.
 * @property rejected true when credentials the user typed were just refused, so the panel
 *   can say so.
 */
data class HttpAuthPrompt(
    val id: Long,
    val origin: String,
    val realm: String?,
    val rejected: Boolean,
)

/** Informational messages the browser overlay renders with a localized string. */
enum class AuthNotice {
    /** The service asked for a sign-in method NextSH does not handle (only Basic is). */
    UNSUPPORTED_CHALLENGE,

    /** A loopback service other than the tunnel's own (another port, https...) asked for credentials. */
    OTHER_ADDRESS,

    /** Sign-in handling could not be switched on: Chromium may show its own window. */
    INTERCEPTION_UNAVAILABLE,
}

/** Receives what the coordinator wants the UI to show. Called with the coordinator's lock held: never block. */
interface BrowserAuthListener {
    fun onPromptChanged(prompt: HttpAuthPrompt?)
    fun onNotice(notice: AuthNotice)
}

/**
 * HTTP authentication for the in-app browser, driven through the Chrome DevTools Protocol
 * `Fetch` domain. Chromium's own "Sign in" window never appears: with `Fetch.enable` and
 * `handleAuthRequests`, every challenge is delivered here as `Fetch.authRequired` and the
 * request stays paused until [BrowserAuthCoordinator] answers it.
 *
 * One instance per browser. Pure Kotlin: events come in as (name, JSON) through
 * [onCdpEvent], commands go out through [CdpSender], and the UI is told through
 * [BrowserAuthListener], so everything is testable without Chromium.
 *
 * Rules:
 * - Every `Fetch.requestPaused` is continued at once (the `*` pattern pauses every request).
 * - Every `Fetch.authRequired` ends up answered: refused (`CancelAuth`) when it comes from a
 *   proxy, from another origin than [tunnelOrigin] or uses another scheme than Basic;
 *   answered with the held credentials; or queued behind the sign-in prompt until the user
 *   submits or cancels. `Default` is never sent, it would open Chromium's native window.
 * - The same request id challenged again after it was answered means the server refused the
 *   credentials: they are wiped and the prompt comes back flagged as rejected.
 * - Credentials only ever go to [tunnelOrigin], are kept as [CharArray], and are wiped when
 *   refused, on [close], or when a submit is ignored.
 *
 * Thread safety: [onCdpEvent] runs on the CEF UI thread, [submit], [cancel],
 * [onUserNavigation] and [close] on the UI thread of the app. State changes and UI
 * notifications happen under one lock, commands are sent after releasing it, in a
 * `finally`, so a failure in between never leaves a request unanswered. A listener that
 * throws is ignored: it must not be able to break the state machine.
 *
 * Nothing here logs an event or a command: both carry URLs, and commands carry credentials.
 */
class BrowserAuthCoordinator(
    private val tunnelOrigin: String,
    private val sender: CdpSender,
    private val listener: BrowserAuthListener,
    // Wall clock in milliseconds, injectable so the cancel cooldown is testable.
    private val clock: () -> Long = System::currentTimeMillis,
    // Source of prompt ids. The view model shares one counter across coordinators, so the id
    // of a new browser's first prompt is never the one of the previous browser's panel.
    newPromptId: (() -> Long)? = null,
) {
    private val lock = Any()
    private val displayOrigin = displayAuthOrigin(tunnelOrigin)
    private val ownPromptIds = AtomicLong()
    private val promptIds: () -> Long = newPromptId ?: { ownPromptIds.incrementAndGet() }

    private var closed = false
    private var username: CharArray? = null
    private var password: CharArray? = null

    // Request ids answered with credentials: a second challenge for one of them is a refusal.
    // Bounded, oldest forgotten first.
    private val answered = LinkedHashSet<String>()

    // Request ids waiting for the user, all of them for [tunnelOrigin].
    private val pending = LinkedHashSet<String>()
    private var prompt: HttpAuthPrompt? = null
    private var cooldown: Cooldown? = null
    private var unsupportedNoticed = false
    private var otherAddressNoticed = false

    private class Cooldown(val realm: String?, val startedAt: Long)

    private class Outbox {
        val commands = ArrayList<Pair<String, JsonObject>>(4)
        fun add(command: Pair<String, JsonObject>) {
            commands += command
        }
    }

    /** Entry point for every DevTools event of the browser. Never throws. */
    fun onCdpEvent(name: String, paramsJson: String) {
        try {
            when (name) {
                EVENT_REQUEST_PAUSED -> onRequestPaused(paramsJson)
                EVENT_AUTH_REQUIRED -> onAuthRequired(paramsJson)
                else -> Unit
            }
        } catch (_: Exception) {
            // A malformed event must not escape into the CEF thread. Not logged: it may
            // carry a URL.
        }
    }

    private fun onRequestPaused(paramsJson: String) {
        val id = fastRequestId(paramsJson) ?: requestIdOf(parseObject(paramsJson), paramsJson) ?: return
        sendQuietly(continueRequest(id))
    }

    private fun onAuthRequired(paramsJson: String) {
        val root = parseObject(paramsJson)
        val id = requestIdOf(root, paramsJson) ?: return
        val challenge = root?.get("authChallenge") as? JsonObject
        val out = Outbox()
        try {
            synchronized(lock) {
                decide(id, challenge, out)
            }
        } finally {
            flush(out)
        }
    }

    // Caller holds the lock.
    private fun decide(id: String, challenge: JsonObject?, out: Outbox) {
        if (closed || challenge == null) {
            out.add(cancelAuth(id))
            return
        }
        val source = challenge.string("source")
        if (source == null || !source.equals("Server", ignoreCase = true)) {
            out.add(cancelAuth(id))
            noticeUnsupported()
            return
        }
        val origin = challenge.string("origin")
        if (origin != tunnelOrigin) {
            // Never a recipient of the tunnel's credentials. A CDN asset or an embedded third
            // party is refused silently; another loopback address (a sibling service, https)
            // is worth telling the user about.
            out.add(cancelAuth(id))
            if (isLoopbackAuthOrigin(origin)) noticeOtherAddress()
            return
        }
        val scheme = challenge.string("scheme")
        if (scheme == null || !scheme.equals("basic", ignoreCase = true)) {
            out.add(cancelAuth(id))
            noticeUnsupported()
            return
        }
        handleBasic(id, sanitizeAuthRealm(challenge.string("realm")), out)
    }

    // Caller holds the lock.
    private fun handleBasic(id: String, realm: String?, out: Outbox) {
        var rejected = false
        if (id in answered) {
            // Same request, challenged again: the server did not accept what we sent.
            answered.remove(id)
            wipeCredentials()
            rejected = true
        }
        val user = username
        val pass = password
        if (user != null && pass != null) {
            remember(id)
            out.add(provideCredentials(id, user, pass))
            return
        }
        if (isInCooldown(realm)) {
            out.add(cancelAuth(id))
            return
        }
        if (pending.size >= MAX_PENDING) {
            out.add(cancelAuth(id))
            return
        }
        pending += id
        if (prompt == null) {
            val shown = HttpAuthPrompt(promptIds(), displayOrigin, realm, rejected)
            prompt = shown
            publishPrompt(shown)
        }
    }

    /**
     * The user confirmed the prompt [promptId]. Answers every waiting request with the
     * credentials and keeps them for the requests that come next.
     *
     * Takes ownership of [username] and [password]: they are kept, then wiped when refused
     * by the server or on [close]. An answer that does not match the pending prompt (already
     * answered, replaced, closed) is ignored and the arrays are wiped at once.
     */
    fun submit(promptId: Long, username: CharArray, password: CharArray) {
        val out = Outbox()
        var accepted = false
        try {
            synchronized(lock) {
                val shown = prompt
                if (!closed && shown != null && shown.id == promptId) {
                    accepted = true
                    wipeCredentials()
                    this.username = username
                    this.password = password
                    // Requests answered with older credentials are forgotten: a late refusal
                    // of one of them must not be taken for a refusal of these new credentials.
                    // Asked again, such a request is simply answered with them.
                    answered.clear()
                    for (id in pending) {
                        remember(id)
                        out.add(provideCredentials(id, username, password))
                    }
                    pending.clear()
                    prompt = null
                    publishPrompt(null)
                }
            }
        } finally {
            if (!accepted) {
                username.fill(WIPE)
                password.fill(WIPE)
            }
            flush(out)
        }
    }

    /**
     * The user dismissed the prompt [promptId] (Cancel, Escape): the waiting requests get
     * the server's own 401 answer. Starts the cooldown for the realm, so a page that
     * challenges again at once does not bring the prompt back in a loop. An id that does not
     * match the pending prompt is ignored.
     */
    fun cancel(promptId: Long) {
        val out = Outbox()
        try {
            synchronized(lock) {
                val shown = prompt
                if (shown == null || shown.id != promptId) return
                cooldown = Cooldown(shown.realm, clock())
                drainPending(out)
            }
        } finally {
            flush(out)
        }
    }

    /**
     * The user navigated (back, forward, reload, new URL): the requests waiting on the
     * prompt are moot. Cancels them and clears the prompt. This is not a refusal, so it also
     * ends any cooldown: the page the user asked for may legitimately ask again. Held
     * credentials are kept. Re-arms the one-shot notices.
     */
    fun onUserNavigation() {
        val out = Outbox()
        try {
            synchronized(lock) {
                unsupportedNoticed = false
                otherAddressNoticed = false
                cooldown = null
                drainPending(out)
            }
        } finally {
            flush(out)
        }
    }

    /**
     * Ends this coordinator (browser closed, tunnel switched, overlay left the composition):
     * wipes the credentials first, then cancels what waits and clears the prompt. Afterwards
     * the requests that still arrive are only continued or cancelled, never prompted for, so
     * nothing stays paused if the browser lives a little longer.
     */
    fun close() {
        val out = Outbox()
        try {
            synchronized(lock) {
                if (closed) return
                closed = true
                wipeCredentials()
                answered.clear()
                cooldown = null
                drainPending(out)
            }
        } finally {
            flush(out)
        }
    }

    // Caller holds the lock. Cancels every waiting request and hides the prompt.
    private fun drainPending(out: Outbox) {
        for (id in pending) out.add(cancelAuth(id))
        pending.clear()
        if (prompt != null) {
            prompt = null
            publishPrompt(null)
        }
    }

    // Caller holds the lock.
    private fun wipeCredentials() {
        username?.fill(WIPE)
        password?.fill(WIPE)
        username = null
        password = null
    }

    // Caller holds the lock.
    private fun remember(id: String) {
        answered += id
        if (answered.size > MAX_ANSWERED) {
            val oldest = answered.iterator()
            oldest.next()
            oldest.remove()
        }
    }

    // Caller holds the lock.
    private fun isInCooldown(realm: String?): Boolean {
        val c = cooldown ?: return false
        if (c.realm != realm) return false
        // A negative elapsed time (clock moved backwards) never counts as cooldown.
        return clock() - c.startedAt in 0 until AUTH_CANCEL_COOLDOWN_MS
    }

    // Caller holds the lock.
    private fun noticeUnsupported() {
        if (unsupportedNoticed) return
        unsupportedNoticed = true
        publishNotice(AuthNotice.UNSUPPORTED_CHALLENGE)
    }

    // Caller holds the lock.
    private fun noticeOtherAddress() {
        if (otherAddressNoticed) return
        otherAddressNoticed = true
        publishNotice(AuthNotice.OTHER_ADDRESS)
    }

    // The listener belongs to the UI: whatever it does, the state machine carries on.
    private fun publishPrompt(shown: HttpAuthPrompt?) {
        try {
            listener.onPromptChanged(shown)
        } catch (_: Exception) {
            Logger.w(TAG, "prompt listener failed")
        }
    }

    private fun publishNotice(notice: AuthNotice) {
        try {
            listener.onNotice(notice)
        } catch (_: Exception) {
            Logger.w(TAG, "notice listener failed")
        }
    }

    private fun flush(out: Outbox) {
        for (command in out.commands) sendQuietly(command)
    }

    private fun sendQuietly(command: Pair<String, JsonObject>) {
        val method = command.first
        try {
            sender.send(method, command.second)
        } catch (_: Exception) {
            // Method name only: the parameters hold request ids and possibly credentials.
            Logger.w(TAG, "devtools command failed: $method")
        }
    }

    companion object {
        private const val TAG = "BrowserAuth"

        const val EVENT_REQUEST_PAUSED = "Fetch.requestPaused"
        const val EVENT_AUTH_REQUIRED = "Fetch.authRequired"
        const val METHOD_ENABLE = "Fetch.enable"
        const val METHOD_CONTINUE_REQUEST = "Fetch.continueRequest"
        const val METHOD_CONTINUE_WITH_AUTH = "Fetch.continueWithAuth"

        /** Waiting requests kept at most: a hostile page could raise thousands of challenges. */
        internal const val MAX_PENDING = 256

        /** Answered request ids remembered at most, to recognise a refusal. */
        internal const val MAX_ANSWERED = 512

        private const val WIPE = '\u0000'

        private val parser = Json

        // Last resort when an event is not valid JSON: a request left paused hangs its page.
        private val requestIdPattern = Regex("\"requestId\"\\s*:\\s*\"([^\"\\\\]{1,256})\"")

        // Chromium serializes `requestId` first. A page controls the URL, the headers and the
        // body of its requests, never the beginning of the event, so reading the id straight
        // after this prefix is safe and saves parsing the whole request for every one of them.
        private const val REQUEST_ID_PREFIX = "{\"requestId\":\""

        /**
         * Parameters of [METHOD_ENABLE]: pause every request at the request stage (the
         * pattern `*`, because an authentication event is only delivered for a request
         * matching a pattern) and hand authentication challenges to us.
         */
        fun enableParams(): JsonObject = buildJsonObject {
            put("handleAuthRequests", true)
            putJsonArray("patterns") {
                addJsonObject {
                    put("urlPattern", "*")
                    put("requestStage", "Request")
                }
            }
        }

        private fun continueRequest(id: String): Pair<String, JsonObject> =
            METHOD_CONTINUE_REQUEST to buildJsonObject { put("requestId", id) }

        private fun cancelAuth(id: String): Pair<String, JsonObject> =
            METHOD_CONTINUE_WITH_AUTH to buildJsonObject {
                put("requestId", id)
                putJsonObject("authChallengeResponse") { put("response", "CancelAuth") }
            }

        // The values are built into a JSON tree, which escapes quotes and backslashes in both.
        // That tree and the text serialized from it hold copies of the password as Strings
        // until the garbage collector reclaims them: they cannot be wiped, but nothing here
        // keeps or logs them beyond this call.
        private fun provideCredentials(id: String, user: CharArray, pass: CharArray): Pair<String, JsonObject> =
            METHOD_CONTINUE_WITH_AUTH to buildJsonObject {
                put("requestId", id)
                putJsonObject("authChallengeResponse") {
                    put("response", "ProvideCredentials")
                    put("username", String(user))
                    put("password", String(pass))
                }
            }

        private fun parseObject(text: String): JsonObject? =
            try {
                parser.parseToJsonElement(text) as? JsonObject
            } catch (_: Exception) {
                null
            }

        /** The id straight after [REQUEST_ID_PREFIX], or null when the event does not look like that. */
        internal fun fastRequestId(raw: String): String? {
            if (!raw.startsWith(REQUEST_ID_PREFIX)) return null
            val start = REQUEST_ID_PREFIX.length
            val end = raw.indexOf('"', start)
            if (end <= start || end - start > 256) return null
            for (i in start until end) {
                val c = raw[i]
                if (c == '\\' || c < ' ') return null
            }
            return raw.substring(start, end)
        }

        private fun requestIdOf(root: JsonObject?, raw: String): String? {
            val parsed = root?.string("requestId")
            if (parsed != null) return parsed
            if (root != null) return null
            return requestIdPattern.find(raw)?.groupValues?.get(1)
        }

        private fun JsonObject.string(key: String): String? {
            val value = this[key] as? JsonPrimitive ?: return null
            return if (value.isString) value.content else null
        }
    }
}
