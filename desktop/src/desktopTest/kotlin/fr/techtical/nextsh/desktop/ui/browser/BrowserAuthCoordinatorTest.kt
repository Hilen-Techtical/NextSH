// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.ui.browser

import fr.techtical.nextsh.shared.domain.tunnel.TunnelBrowserPath
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests of [BrowserAuthCoordinator] with a fake DevTools sender: no Chromium involved.
 */
class BrowserAuthCoordinatorTest {

    // ── Fakes and helpers ─────────────────────────────────────────────────────

    private class Sent(val method: String, val params: JsonObject) {
        val requestId: String get() = params.getValue("requestId").jsonPrimitive.content

        /** `CancelAuth`, `ProvideCredentials` or null for a plain continue. */
        val response: String?
            get() = (params["authChallengeResponse"] as? JsonObject)?.get("response")?.jsonPrimitive?.content

        val username: String?
            get() = (params["authChallengeResponse"] as? JsonObject)?.get("username")?.jsonPrimitive?.content

        val password: String?
            get() = (params["authChallengeResponse"] as? JsonObject)?.get("password")?.jsonPrimitive?.content
    }

    private class FakeSender : CdpSender {
        val sent = CopyOnWriteArrayList<Sent>()
        var failure: RuntimeException? = null

        override fun send(method: String, params: JsonObject) {
            failure?.let { throw it }
            sent += Sent(method, params)
        }

        fun continues() = sent.filter { it.method == "Fetch.continueRequest" }
        fun cancels() = sent.filter { it.response == "CancelAuth" }
        fun provides() = sent.filter { it.response == "ProvideCredentials" }
        fun authAnswers() = sent.filter { it.method == "Fetch.continueWithAuth" }
        fun clear() = sent.clear()
    }

    private class FakeListener : BrowserAuthListener {
        val prompts = CopyOnWriteArrayList<HttpAuthPrompt?>()
        val notices = CopyOnWriteArrayList<AuthNotice>()
        val current: HttpAuthPrompt? get() = prompts.lastOrNull()

        override fun onPromptChanged(prompt: HttpAuthPrompt?) {
            prompts += prompt
        }

        override fun onNotice(notice: AuthNotice) {
            notices += notice
        }
    }

    private val origin = "http://127.0.0.1:8081"
    private var now = 1_000_000L
    private val sender = FakeSender()
    private val listener = FakeListener()
    private val coordinator = BrowserAuthCoordinator(origin, sender, listener, clock = { now })

    private fun paused(id: String, url: String = "http://127.0.0.1:8081/a?token=secret-in-url") = buildJsonObject {
        put("requestId", id)
        putJsonObject("request") {
            put("url", url)
            put("method", "GET")
        }
        put("resourceType", "Document")
    }.toString()

    private fun auth(
        id: String,
        source: String? = "Server",
        authOrigin: String? = origin,
        scheme: String? = "basic",
        realm: String? = "harness",
    ) = buildJsonObject {
        put("requestId", id)
        putJsonObject("request") { put("url", "http://127.0.0.1:8081/a"); put("method", "GET") }
        putJsonObject("authChallenge") {
            if (source != null) put("source", source)
            if (authOrigin != null) put("origin", authOrigin)
            if (scheme != null) put("scheme", scheme)
            if (realm != null) put("realm", realm)
        }
    }.toString()

    private fun challenge(id: String, realm: String? = "harness") =
        coordinator.onCdpEvent("Fetch.authRequired", auth(id, realm = realm))

    private fun promptId(): Long = assertNotNull(listener.current).id

    private fun submit(user: String = "alice", pass: String = "s3cret"): Pair<CharArray, CharArray> {
        val u = user.toCharArray()
        val p = pass.toCharArray()
        coordinator.submit(promptId(), u, p)
        return u to p
    }

    private fun dismiss() = coordinator.cancel(promptId())

    private fun CharArray.isWiped() = all { it == '\u0000' }

    // ── Fetch.requestPaused ───────────────────────────────────────────────────

    @Test
    fun `every paused request is continued with its own id`() {
        coordinator.onCdpEvent("Fetch.requestPaused", paused("job-1"))
        coordinator.onCdpEvent("Fetch.requestPaused", paused("job-2"))
        coordinator.onCdpEvent("Fetch.requestPaused", paused("job-3"))

        assertEquals(listOf("job-1", "job-2", "job-3"), sender.continues().map { it.requestId })
        assertEquals(3, sender.sent.size)
        assertEquals(setOf("requestId"), sender.continues().first().params.keys)
    }

    @Test
    fun `a paused request with malformed JSON is still continued through the fallback id`() {
        coordinator.onCdpEvent("Fetch.requestPaused", """{"requestId":"job-9","request":{"url":""")

        assertEquals(listOf("job-9"), sender.continues().map { it.requestId })
    }

    @Test
    fun `a paused event without any request id sends nothing and does not throw`() {
        coordinator.onCdpEvent("Fetch.requestPaused", "{}")
        coordinator.onCdpEvent("Fetch.requestPaused", "not json at all")
        coordinator.onCdpEvent("Fetch.requestPaused", "")
        coordinator.onCdpEvent("Fetch.requestPaused", """{"requestId":42}""")

        assertTrue(sender.sent.isEmpty())
    }

    @Test
    fun `a request id nested in a header value is not mistaken for the real one`() {
        // The hostile page controls header values; JSON escaping keeps the quotes escaped.
        val hostile = buildJsonObject {
            putJsonObject("request") {
                putJsonObject("headers") { put("X-Evil", "\"requestId\":\"evil-id\"") }
            }
        }.toString()
        coordinator.onCdpEvent("Fetch.requestPaused", hostile)

        assertTrue(sender.sent.isEmpty())
    }

    @Test
    fun `unrelated events are ignored`() {
        coordinator.onCdpEvent("Network.requestWillBeSent", paused("job-1"))
        coordinator.onCdpEvent("Page.loadEventFired", "{}")

        assertTrue(sender.sent.isEmpty())
        assertTrue(listener.prompts.isEmpty())
    }

    @Test
    fun `a failing sender never escapes into the caller`() {
        sender.failure = IllegalStateException("channel closed")

        coordinator.onCdpEvent("Fetch.requestPaused", paused("job-1"))
        coordinator.onCdpEvent("Fetch.authRequired", auth("job-2", authOrigin = "http://evil.example"))
        challenge("job-3")
        coordinator.cancel(promptId())

        assertNull(listener.current)
    }

    // ── Fetch.authRequired: who may be prompted ───────────────────────────────

    @Test
    fun `a basic challenge from the tunnel without credentials publishes a prompt and answers nothing yet`() {
        challenge("job-1", realm = "Private area")

        val prompt = assertNotNull(listener.current)
        assertEquals("127.0.0.1:8081", prompt.origin)
        assertEquals("Private area", prompt.realm)
        assertFalse(prompt.rejected)
        assertTrue(sender.sent.isEmpty(), "the request must wait for the user")
    }

    @Test
    fun `the scheme is matched case-insensitively`() {
        coordinator.onCdpEvent("Fetch.authRequired", auth("job-1", scheme = "Basic"))

        assertNotNull(listener.current)
        assertTrue(listener.notices.isEmpty())
    }

    @Test
    fun `the realm is sanitized before it reaches the prompt`() {
        val rlo = 0x202E.toChar()
        val newline = 10.toChar()

        challenge("job-1", realm = "Admin" + rlo + newline + "area")

        assertEquals("Adminarea", assertNotNull(listener.current).realm)
    }

    @Test
    fun `a missing or blank realm becomes null in the prompt`() {
        challenge("job-1", realm = null)
        assertNull(assertNotNull(listener.current).realm)
        dismiss()
        now += AUTH_CANCEL_COOLDOWN_MS

        challenge("job-2", realm = "   ")

        assertNull(assertNotNull(listener.current).realm)
    }

    @Test
    fun `a proxy challenge is cancelled with a notice and never prompted`() {
        coordinator.onCdpEvent("Fetch.authRequired", auth("job-1", source = "Proxy", authOrigin = "http://proxy.corp:3128"))

        assertEquals(listOf("job-1"), sender.cancels().map { it.requestId })
        assertEquals(listOf(AuthNotice.UNSUPPORTED_CHALLENGE), listener.notices.toList())
        assertTrue(listener.prompts.isEmpty())
        assertTrue(sender.provides().isEmpty())
    }

    @Test
    fun `a proxy challenge claiming the tunnel origin is still refused`() {
        coordinator.onCdpEvent("Fetch.authRequired", auth("job-1", source = "Proxy", authOrigin = origin))

        assertEquals(1, sender.cancels().size)
        assertTrue(listener.prompts.isEmpty())
    }

    @Test
    fun `a challenge without a source is refused`() {
        coordinator.onCdpEvent("Fetch.authRequired", auth("job-1", source = null))

        assertEquals(1, sender.cancels().size)
        assertTrue(listener.prompts.isEmpty())
    }

    @Test
    fun `only the exact tunnel origin may be prompted for`() {
        val foreign = listOf(
            "http://127.0.0.1:8082",
            "http://127.0.0.1",
            "http://localhost:8081",
            "http://[::1]:8081",
            "https://127.0.0.1:8081",
            "http://127.0.0.1:8081.evil.example",
            "http://evil.example:8081",
            "http://127.0.0.1:8081/",
            "http://user@127.0.0.1:8081",
            "HTTP://127.0.0.1:8081",
            "",
        )
        for ((index, foreignOrigin) in foreign.withIndex()) {
            coordinator.onCdpEvent("Fetch.authRequired", auth("job-$index", authOrigin = foreignOrigin))
        }

        assertEquals(foreign.size, sender.cancels().size)
        assertTrue(listener.prompts.isEmpty(), "no prompt for a foreign origin")
        assertTrue(sender.provides().isEmpty(), "credentials never go to a foreign origin")
    }

    @Test
    fun `a foreign origin that is not loopback is refused silently`() {
        for ((index, foreignOrigin) in listOf("http://evil.example:8081", "https://cdn.example", "http://192.168.1.10:8081", "http://127.0.0.1.evil.example", "").withIndex()) {
            coordinator.onCdpEvent("Fetch.authRequired", auth("job-$index", authOrigin = foreignOrigin))
        }

        assertEquals(5, sender.cancels().size)
        assertTrue(listener.notices.isEmpty())
    }

    @Test
    fun `another loopback address gets one notice per navigation and no credentials`() {
        for ((index, other) in listOf("https://127.0.0.1:8081", "http://127.0.0.1:9999", "http://localhost:8081", "http://[::1]:8081", "http://127.0.0.1").withIndex()) {
            coordinator.onCdpEvent("Fetch.authRequired", auth("job-$index", authOrigin = other))
        }

        assertEquals(5, sender.cancels().size)
        assertEquals(listOf(AuthNotice.OTHER_ADDRESS), listener.notices.toList(), "one-shot notice")
        assertTrue(listener.prompts.isEmpty())
        assertTrue(sender.provides().isEmpty())

        coordinator.onUserNavigation()
        coordinator.onCdpEvent("Fetch.authRequired", auth("job-9", authOrigin = "https://127.0.0.1:8081"))

        assertEquals(2, listener.notices.size, "a new navigation re-arms the notice")
    }

    @Test
    fun `the two notices are independent one-shots`() {
        coordinator.onCdpEvent("Fetch.authRequired", auth("job-1", scheme = "digest"))
        coordinator.onCdpEvent("Fetch.authRequired", auth("job-2", authOrigin = "http://127.0.0.1:9999"))
        coordinator.onCdpEvent("Fetch.authRequired", auth("job-3", scheme = "digest"))
        coordinator.onCdpEvent("Fetch.authRequired", auth("job-4", authOrigin = "http://127.0.0.1:9999"))

        assertEquals(listOf(AuthNotice.UNSUPPORTED_CHALLENGE, AuthNotice.OTHER_ADDRESS), listener.notices.toList())
    }

    @Test
    fun `a challenge without an origin is refused`() {
        coordinator.onCdpEvent("Fetch.authRequired", auth("job-1", authOrigin = null))

        assertEquals(1, sender.cancels().size)
        assertTrue(listener.prompts.isEmpty())
    }

    @Test
    fun `credentials held are never sent to a foreign origin`() {
        challenge("job-1")
        submit()
        sender.clear()

        coordinator.onCdpEvent("Fetch.authRequired", auth("job-2", authOrigin = "http://127.0.0.1:9999"))
        coordinator.onCdpEvent("Fetch.authRequired", auth("job-3", source = "Proxy"))

        assertTrue(sender.provides().isEmpty())
        assertEquals(2, sender.cancels().size)
    }

    @Test
    fun `other schemes are cancelled with a single notice per navigation`() {
        for ((index, scheme) in listOf("digest", "ntlm", "negotiate", "bearer", "").withIndex()) {
            coordinator.onCdpEvent("Fetch.authRequired", auth("job-$index", scheme = scheme))
        }

        assertEquals(5, sender.cancels().size)
        assertEquals(1, listener.notices.size, "one-shot notice")
        assertTrue(listener.prompts.isEmpty())

        coordinator.onUserNavigation()
        coordinator.onCdpEvent("Fetch.authRequired", auth("job-9", scheme = "digest"))

        assertEquals(2, listener.notices.size, "a new navigation re-arms the notice")
    }

    @Test
    fun `a challenge without a scheme is refused with the unsupported notice`() {
        coordinator.onCdpEvent("Fetch.authRequired", auth("job-1", scheme = null))

        assertEquals(1, sender.cancels().size)
        assertEquals(1, listener.notices.size)
    }

    @Test
    fun `a foreign origin using another scheme gets no notice`() {
        coordinator.onCdpEvent("Fetch.authRequired", auth("job-1", authOrigin = "http://cdn.example", scheme = "digest"))

        assertEquals(1, sender.cancels().size)
        assertTrue(listener.notices.isEmpty())
    }

    @Test
    fun `a malformed challenge is cancelled when its id can be read and ignored otherwise`() {
        coordinator.onCdpEvent("Fetch.authRequired", """{"requestId":"job-1","authChallenge":{"source":""")
        coordinator.onCdpEvent("Fetch.authRequired", "garbage")
        coordinator.onCdpEvent("Fetch.authRequired", """{"requestId":"job-2"}""")

        assertEquals(listOf("job-1", "job-2"), sender.cancels().map { it.requestId })
        assertTrue(listener.prompts.isEmpty())
    }

    // ── Prompt, submit, credentials ───────────────────────────────────────────

    @Test
    fun `a second challenge while the prompt is up joins it instead of opening another`() {
        challenge("job-1")
        challenge("job-2")
        challenge("job-3", realm = "another realm")

        assertEquals(1, listener.prompts.size)
        assertTrue(sender.sent.isEmpty())

        submit()

        assertEquals(listOf("job-1", "job-2", "job-3"), sender.provides().map { it.requestId })
    }

    @Test
    fun `submit answers every waiting request, clears the prompt and keeps the credentials`() {
        challenge("job-1")
        challenge("job-2")

        submit("alice", "s3cret")

        assertNull(listener.current)
        assertEquals(listOf("job-1", "job-2"), sender.provides().map { it.requestId })
        assertTrue(sender.provides().all { it.username == "alice" && it.password == "s3cret" })
        assertTrue(sender.provides().all { it.method == "Fetch.continueWithAuth" })

        // A later request of the same origin is answered at once, without a new prompt.
        sender.clear()
        listener.prompts.clear()
        challenge("job-3")

        assertEquals(listOf("job-3"), sender.provides().map { it.requestId })
        assertTrue(listener.prompts.isEmpty())
    }

    @Test
    fun `credentials are escaped in the JSON sent to the browser`() {
        challenge("job-1")
        val username = "al\"ice\\\\x"
        val password = "p\"w\\\n{\"requestId\":\"x\"}\t" + 0x2028.toChar()

        submit(username, password)

        val sentCommand = sender.provides().single()
        assertEquals(username, sentCommand.username)
        assertEquals(password, sentCommand.password)
        // What actually crosses to Chromium is the serialized text: it must parse back to the same values.
        val reparsed = Json.parseToJsonElement(sentCommand.params.toString()).jsonObject
        val response = reparsed.getValue("authChallengeResponse").jsonObject
        assertEquals(username, response.getValue("username").jsonPrimitive.content)
        assertEquals(password, response.getValue("password").jsonPrimitive.content)
        assertEquals("job-1", reparsed.getValue("requestId").jsonPrimitive.content)
        assertEquals(setOf("requestId", "authChallengeResponse"), reparsed.keys)
    }

    @Test
    fun `an empty username with a password is accepted`() {
        challenge("job-1")

        submit("", "token-only")

        assertEquals("", sender.provides().single().username)
        assertEquals("token-only", sender.provides().single().password)
    }

    @Test
    fun `a duplicate challenge for a waiting id is queued once`() {
        challenge("job-1")
        challenge("job-1")

        submit()

        assertEquals(1, sender.provides().size)
    }

    @Test
    fun `submit with a stale prompt id is ignored and wipes the arrays it was given`() {
        challenge("job-1")
        val id = promptId()
        val user = "mallory".toCharArray()
        val pass = "other".toCharArray()

        coordinator.submit(id + 1, user, pass)
        coordinator.submit(id - 1, user, pass)

        assertTrue(user.isWiped() && pass.isWiped())
        assertTrue(sender.sent.isEmpty())
        assertEquals(id, listener.current?.id)

        submit("alice", "pw")

        assertEquals("alice", sender.provides().single().username)
    }

    @Test
    fun `a second submit for the same prompt is ignored`() {
        challenge("job-1")
        val id = promptId()
        coordinator.submit(id, "alice".toCharArray(), "one".toCharArray())
        val user = "mallory".toCharArray()
        val pass = "two".toCharArray()

        coordinator.submit(id, user, pass)

        assertEquals(1, sender.provides().size)
        assertEquals("one", sender.provides().single().password)
        assertTrue(user.isWiped() && pass.isWiped())
    }

    @Test
    fun `an answer to a replaced prompt cannot answer the next one`() {
        challenge("job-1")
        val firstId = promptId()
        submit("alice", "wrong")
        // Refused: the same id is challenged again, a new prompt replaces the first.
        challenge("job-1")
        val secondId = promptId()
        assertTrue(secondId > firstId)
        sender.clear()

        // The double click on the first panel lands after the second one appeared.
        val user = "alice".toCharArray()
        val pass = "wrong".toCharArray()
        coordinator.submit(firstId, user, pass)
        coordinator.cancel(firstId)

        assertTrue(sender.sent.isEmpty())
        assertTrue(user.isWiped() && pass.isWiped())
        assertEquals(secondId, listener.current?.id)
    }

    @Test
    fun `submit and cancel without a prompt do nothing`() {
        val user = "alice".toCharArray()
        val pass = "pw".toCharArray()

        coordinator.submit(1L, user, pass)
        coordinator.cancel(1L)

        assertTrue(sender.sent.isEmpty())
        assertTrue(user.isWiped() && pass.isWiped())
        // An ignored cancel starts no cooldown: the next challenge is prompted.
        challenge("job-1")
        assertNotNull(listener.current)
    }

    @Test
    fun `prompt ids increase with each new prompt`() {
        challenge("job-1")
        val first = promptId()
        dismiss()
        now += AUTH_CANCEL_COOLDOWN_MS

        challenge("job-2")

        assertTrue(promptId() > first)
    }

    // ── Refused credentials ───────────────────────────────────────────────────

    @Test
    fun `the same request challenged again after an answer means the credentials were refused`() {
        challenge("job-1")
        val (user, pass) = submit("alice", "wrong")
        sender.clear()
        listener.prompts.clear()

        challenge("job-1")

        val prompt = assertNotNull(listener.current)
        assertTrue(prompt.rejected)
        assertTrue(user.isWiped() && pass.isWiped(), "refused credentials are wiped")
        assertTrue(sender.sent.isEmpty(), "the request waits for new credentials")

        // New credentials answer the very same request again.
        submit("alice", "right")

        assertEquals(listOf("job-1"), sender.provides().map { it.requestId })
        assertEquals("right", sender.provides().single().password)
    }

    @Test
    fun `after a refusal the old credentials are not reused for other requests`() {
        challenge("job-1")
        submit("alice", "wrong")
        challenge("job-1")
        sender.clear()

        challenge("job-2")

        assertTrue(sender.provides().isEmpty())
        assertEquals(1, listener.prompts.count { it != null && it.rejected })
    }

    @Test
    fun `a refusal of several in-flight requests re-prompts once`() {
        challenge("job-1")
        challenge("job-2")
        submit("alice", "wrong")
        listener.prompts.clear()

        challenge("job-1")
        challenge("job-2")

        assertEquals(1, listener.prompts.size)
        assertTrue(assertNotNull(listener.current).rejected)

        submit("alice", "right")

        assertEquals(setOf("job-1", "job-2"), sender.provides().takeLast(2).map { it.requestId }.toSet())
    }

    @Test
    fun `a refusal inside the cooldown after a cancel does not bring the prompt back`() {
        challenge("job-1")
        challenge("job-2")
        submit("alice", "wrong")
        challenge("job-1") // refused: prompt again
        dismiss()
        sender.clear()
        listener.prompts.clear()
        now += 1

        // The refusal of the other request, answered with the same credentials, arrives late.
        challenge("job-2")

        assertTrue(listener.prompts.isEmpty())
        assertEquals(listOf("job-2"), sender.cancels().map { it.requestId })
    }

    @Test
    fun `answered ids are bounded and the oldest is forgotten first`() {
        challenge("first")
        submit("alice", "pw")
        for (i in 1..BrowserAuthCoordinator.MAX_ANSWERED) challenge("filler-$i")
        sender.clear()

        // The first id was evicted: challenged again it is simply a new request, not a refusal.
        challenge("first")

        assertEquals(listOf("first"), sender.provides().map { it.requestId })
        assertTrue(listener.prompts.none { it?.rejected == true })
    }

    // ── Cancel and cooldown ───────────────────────────────────────────────────

    @Test
    fun `cancel cancels every waiting request and clears the prompt`() {
        challenge("job-1")
        challenge("job-2")

        dismiss()

        assertEquals(listOf("job-1", "job-2"), sender.cancels().map { it.requestId })
        assertNull(listener.current)
        assertTrue(sender.provides().isEmpty())
    }

    @Test
    fun `a second cancel for the same prompt does nothing`() {
        challenge("job-1")
        val id = promptId()

        coordinator.cancel(id)
        coordinator.cancel(id)

        assertEquals(1, sender.cancels().size)
    }

    @Test
    fun `a challenge repeated within the cooldown after a cancel is auto-cancelled`() {
        challenge("job-1")
        dismiss()
        sender.clear()
        listener.prompts.clear()
        now += AUTH_CANCEL_COOLDOWN_MS - 1

        challenge("job-2")

        assertEquals(listOf("job-2"), sender.cancels().map { it.requestId })
        assertTrue(listener.prompts.isEmpty())
    }

    @Test
    fun `a challenge after the cooldown prompts again`() {
        challenge("job-1")
        dismiss()
        listener.prompts.clear()
        now += AUTH_CANCEL_COOLDOWN_MS

        challenge("job-2")

        assertNotNull(listener.current)
        assertFalse(listener.current!!.rejected)
    }

    @Test
    fun `the cooldown only covers the same realm`() {
        challenge("job-1", realm = "Private area")
        dismiss()
        listener.prompts.clear()

        challenge("job-2", realm = "Other realm")

        assertEquals("Other realm", listener.current?.realm)
    }

    @Test
    fun `a clock that moved backwards does not extend the cooldown`() {
        challenge("job-1")
        dismiss()
        listener.prompts.clear()
        now -= 60_000L

        challenge("job-2")

        assertNotNull(listener.current)
    }

    @Test
    fun `a submit starts no cooldown`() {
        challenge("job-1")
        submit("alice", "wrong")
        challenge("job-1")

        assertTrue(assertNotNull(listener.current).rejected)
    }

    // ── Navigation, close ─────────────────────────────────────────────────────

    @Test
    fun `user navigation cancels the waiting requests and clears the prompt without a cooldown`() {
        challenge("job-1")
        challenge("job-2")

        coordinator.onUserNavigation()

        assertEquals(listOf("job-1", "job-2"), sender.cancels().map { it.requestId })
        assertNull(listener.current)

        listener.prompts.clear()
        challenge("job-3")

        assertNotNull(listener.current, "not a refusal: the next challenge prompts at once")
    }

    @Test
    fun `user navigation ends the cooldown so a cancel then a navigation prompts again`() {
        challenge("job-1")
        dismiss()
        sender.clear()
        listener.prompts.clear()
        now += 1 // still deep inside the cooldown

        coordinator.onUserNavigation()
        challenge("job-2")

        assertNotNull(listener.current, "the page the user asked for may ask for credentials")
        assertTrue(sender.cancels().isEmpty())
    }

    @Test
    fun `without a navigation the cooldown still holds off a repeated challenge`() {
        challenge("job-1")
        dismiss()
        sender.clear()
        listener.prompts.clear()
        now += 1

        challenge("job-2")

        assertNull(listener.current)
        assertEquals(1, sender.cancels().size)
    }

    @Test
    fun `user navigation keeps the credentials`() {
        challenge("job-1")
        submit("alice", "pw")
        sender.clear()

        coordinator.onUserNavigation()
        challenge("job-2")

        assertEquals(listOf("job-2"), sender.provides().map { it.requestId })
    }

    @Test
    fun `user navigation with nothing waiting sends nothing`() {
        coordinator.onUserNavigation()

        assertTrue(sender.sent.isEmpty())
        assertTrue(listener.prompts.isEmpty())
    }

    @Test
    fun `close cancels what waits, clears the prompt and wipes the credentials`() {
        challenge("job-1")
        val (user, pass) = submit("alice", "s3cret")
        challenge("job-2") // answered at once with the held credentials
        challenge("job-1") // refused: the credentials are wiped and a new prompt waits
        challenge("job-3")
        val (user2, pass2) = Pair("bob".toCharArray(), "pw2".toCharArray())
        assertTrue(user.isWiped() && pass.isWiped())
        sender.clear()

        coordinator.close()

        assertEquals(listOf("job-1", "job-3"), sender.cancels().map { it.requestId })
        assertNull(listener.current)
        // Submitting afterwards changes nothing and wipes what it was given.
        coordinator.submit(1L, user2, pass2)
        assertTrue(user2.isWiped() && pass2.isWiped())
    }

    @Test
    fun `close wipes credentials that were still held`() {
        challenge("job-1")
        val (user, pass) = submit("alice", "s3cret")
        assertFalse(user.isWiped())

        coordinator.close()

        assertTrue(user.isWiped(), "username wiped")
        assertTrue(pass.isWiped(), "password wiped")
    }

    @Test
    fun `after close nothing is prompted and nothing is left paused`() {
        challenge("job-1")
        submit()
        coordinator.close()
        sender.clear()
        listener.prompts.clear()

        coordinator.onCdpEvent("Fetch.authRequired", auth("job-2"))
        coordinator.onCdpEvent("Fetch.requestPaused", paused("job-3"))

        assertTrue(sender.provides().isEmpty(), "no credentials after close")
        assertEquals(listOf("job-2"), sender.cancels().map { it.requestId })
        assertEquals(listOf("job-3"), sender.continues().map { it.requestId })
        assertTrue(listener.prompts.isEmpty())
    }

    @Test
    fun `close is idempotent`() {
        challenge("job-1")

        coordinator.close()
        coordinator.close()

        assertEquals(1, sender.cancels().size)
    }

    // ── A late refusal of an old request ──

    @Test
    fun `a late refusal of a request answered with older credentials does not wipe the new ones`() {
        challenge("job-1")
        challenge("job-2")
        submit("alice", "old") // job-1 and job-2 answered with the old credentials
        challenge("job-1") // refused: credentials wiped, prompt again (rejected)
        val retryId = promptId()
        sender.clear()
        listener.prompts.clear()
        val (user, pass) = Pair("alice".toCharArray(), "new".toCharArray())
        coordinator.submit(retryId, user, pass)
        assertEquals(listOf("job-1"), sender.provides().map { it.requestId })
        sender.clear()

        // The refusal of job-2, answered with the OLD credentials, arrives only now.
        challenge("job-2")

        assertEquals(listOf("job-2"), sender.provides().map { it.requestId }, "answered again with the new credentials")
        assertEquals("new", sender.provides().single().password)
        assertFalse(user.isWiped() || pass.isWiped(), "the new credentials are still held")
        assertTrue(listener.prompts.none { it != null }, "no new prompt")
        sender.clear()
        challenge("job-3")
        assertEquals("new", sender.provides().single().password)
    }

    // ── A listener that throws ──

    private class ControlledListener : BrowserAuthListener {
        @Volatile var failing = false
        val prompts = CopyOnWriteArrayList<HttpAuthPrompt?>()

        override fun onPromptChanged(prompt: HttpAuthPrompt?) {
            prompts += prompt
            if (failing) throw IllegalStateException("ui is gone")
        }

        override fun onNotice(notice: AuthNotice) {
            if (failing) throw IllegalStateException("ui is gone")
        }
    }

    private fun withListener(l: BrowserAuthListener) = BrowserAuthCoordinator(origin, sender, l, clock = { now })

    @Test
    fun `a listener that throws on the prompt cannot stall the state machine`() {
        val l = ControlledListener().also { it.failing = true }
        val c = withListener(l)

        c.onCdpEvent("Fetch.authRequired", auth("job-1"))
        c.onCdpEvent("Fetch.authRequired", auth("job-2"))

        assertTrue(sender.sent.isEmpty(), "both requests wait")
        c.submit(1L, "alice".toCharArray(), "pw".toCharArray())
        assertEquals(listOf("job-1", "job-2"), sender.provides().map { it.requestId })
    }

    @Test
    fun `close with a throwing listener still cancels what waits`() {
        val l = ControlledListener()
        val c = withListener(l)
        c.onCdpEvent("Fetch.authRequired", auth("job-1"))
        l.failing = true

        c.close()

        assertEquals(listOf("job-1"), sender.cancels().map { it.requestId })
    }

    @Test
    fun `close with a throwing listener and a failing sender still wipes the credentials`() {
        val l = ControlledListener()
        val c = withListener(l)
        c.onCdpEvent("Fetch.authRequired", auth("job-1"))
        val (user, pass) = Pair("alice".toCharArray(), "s3cret".toCharArray())
        c.submit(1L, user, pass)
        l.failing = true
        sender.failure = IllegalStateException("channel closed")

        c.close()

        assertTrue(user.isWiped() && pass.isWiped())
    }

    @Test
    fun `cancel and navigation with a throwing listener still answer the requests`() {
        val l = ControlledListener()
        val c = withListener(l)
        c.onCdpEvent("Fetch.authRequired", auth("job-1"))
        l.failing = true
        c.cancel(1L)
        assertEquals(listOf("job-1"), sender.cancels().map { it.requestId })

        sender.clear()
        l.failing = false
        now += AUTH_CANCEL_COOLDOWN_MS
        c.onCdpEvent("Fetch.authRequired", auth("job-2"))
        l.failing = true
        c.onUserNavigation()

        assertEquals(listOf("job-2"), sender.cancels().map { it.requestId })
    }

    @Test
    fun `a throwing notice listener does not stop the refusal`() {
        val l = ControlledListener().also { it.failing = true }
        val c = withListener(l)

        c.onCdpEvent("Fetch.authRequired", auth("job-1", scheme = "digest"))
        c.onCdpEvent("Fetch.authRequired", auth("job-2", authOrigin = "http://127.0.0.1:9999"))

        assertEquals(listOf("job-1", "job-2"), sender.cancels().map { it.requestId })
    }

    // ── Prompt ids and request id extraction ──

    @Test
    fun `prompt ids come from the injected counter`() {
        var next = 41L
        val c = BrowserAuthCoordinator(origin, sender, listener, clock = { now }, newPromptId = { ++next })

        c.onCdpEvent("Fetch.authRequired", auth("job-1"))
        assertEquals(42L, listener.current?.id)
        c.cancel(42L)
        now += AUTH_CANCEL_COOLDOWN_MS
        c.onCdpEvent("Fetch.authRequired", auth("job-2"))

        assertEquals(43L, listener.current?.id)
    }

    @Test
    fun `two coordinators sharing a counter never repeat a prompt id`() {
        val ids = java.util.concurrent.atomic.AtomicLong()
        val first = FakeListener()
        val second = FakeListener()
        val a = BrowserAuthCoordinator(origin, sender, first, newPromptId = ids::incrementAndGet)
        val b = BrowserAuthCoordinator(origin, sender, second, newPromptId = ids::incrementAndGet)

        a.onCdpEvent("Fetch.authRequired", auth("job-1"))
        b.onCdpEvent("Fetch.authRequired", auth("job-1"))

        assertTrue(first.current!!.id != second.current!!.id)
    }

    @Test
    fun `the request id is read straight after the prefix without parsing`() {
        assertEquals("interception-job-7.0", BrowserAuthCoordinator.fastRequestId("{\"requestId\":\"interception-job-7.0\",\"request\":{\"url\":\"http://x\"}}"))
        assertNull(BrowserAuthCoordinator.fastRequestId("{\"request\":{},\"requestId\":\"late\"}"))
        assertNull(BrowserAuthCoordinator.fastRequestId("{\"requestId\":\"a\\\"b\",\"x\":1}"), "an escaped quote needs the real parser")
        assertNull(BrowserAuthCoordinator.fastRequestId("{\"requestId\":\"\"}"))
        assertNull(BrowserAuthCoordinator.fastRequestId("{\"requestId\":\"unterminated"))
        assertNull(BrowserAuthCoordinator.fastRequestId("{\"requestId\":\"" + "x".repeat(300) + "\"}"))
        assertNull(BrowserAuthCoordinator.fastRequestId(""))
    }

    @Test
    fun `an id the fast path cannot read is still continued through the parser`() {
        coordinator.onCdpEvent("Fetch.requestPaused", "{\"requestId\":\"a\\\"b\",\"request\":{}}")
        coordinator.onCdpEvent("Fetch.requestPaused", "{\"request\":{},\"requestId\":\"late-id\"}")

        assertEquals(listOf("a\"b", "late-id"), sender.continues().map { it.requestId })
    }

    @Test
    fun `a hostile url cannot change the id read by the fast path`() {
        // The prefix is Chromium's own: whatever the page puts in the request comes later.
        coordinator.onCdpEvent("Fetch.requestPaused", paused("job-real", url = "http://127.0.0.1:8081/?requestId=job-fake\",\"requestId\":\"job-evil"))

        assertEquals(listOf("job-real"), sender.continues().map { it.requestId })
    }

    // ── Limits, concurrency, logging ──────────────────────────────────────────

    @Test
    fun `waiting requests are capped and the excess is cancelled`() {
        val max = BrowserAuthCoordinator.MAX_PENDING
        for (i in 1..max + 5) challenge("job-$i")

        assertEquals(5, sender.cancels().size)
        assertEquals(1, listener.prompts.size)

        submit()

        assertEquals(max, sender.provides().size)
    }

    @Test
    fun `concurrent challenges leave one prompt and no request unanswered`() {
        val count = 64
        val start = CountDownLatch(1)
        val threads = (1..count).map { n ->
            thread(isDaemon = true) {
                start.await()
                challenge("job-$n")
            }
        }
        start.countDown()
        threads.forEach { it.join(5000) }
        assertTrue(threads.none { it.isAlive }, "challenge threads did not finish")

        assertEquals(1, listener.prompts.size)

        submit()

        assertEquals(count, sender.provides().map { it.requestId }.toSet().size)
    }

    @Test
    fun `a failed command is logged by method name only`() {
        val captured = ByteArrayOutputStream()
        val originalErr = System.err
        System.setErr(PrintStream(captured, true, "UTF-8"))
        try {
            challenge("job-secret-id")
            val failing = BrowserAuthCoordinator(origin, { _, _ -> throw IllegalStateException("boom s3cret") }, listener)
            failing.onCdpEvent("Fetch.requestPaused", paused("job-secret-id"))
            failing.onCdpEvent("Fetch.authRequired", auth("job-secret-id", authOrigin = "http://evil.example"))
        } finally {
            System.setErr(originalErr)
        }

        val log = captured.toString("UTF-8")
        assertTrue(log.contains("devtools command failed: Fetch.continueRequest"), log)
        assertFalse(log.contains("job-secret-id"), "request ids are not logged")
        assertFalse(log.contains("secret-in-url"), "URLs are not logged")
        assertFalse(log.contains("s3cret"), "exception messages are not logged")
    }

    // ── Static helpers ────────────────────────────────────────────────────────

    @Test
    fun `enable parameters pause every request and take over authentication`() {
        val params = BrowserAuthCoordinator.enableParams()

        assertEquals("true", params.getValue("handleAuthRequests").jsonPrimitive.content)
        val patterns = params.getValue("patterns").toString()
        assertEquals("""[{"urlPattern":"*","requestStage":"Request"}]""", patterns)
    }

    @Test
    fun `the tunnel origin matches the origin of the URL the browser loads`() {
        for (port in listOf(1, 80, 3000, 8081, 47731, 65535)) {
            val url = URI(TunnelBrowserPath.buildBrowserUrl(port, "/"))
            val expected = buildString {
                append(url.scheme).append("://").append(url.host)
                if (url.port != -1 && url.port != 80) append(":").append(url.port)
            }

            assertEquals(expected, tunnelAuthOrigin(port), "port $port")
        }
    }

    @Test
    fun `the prompt origin is the tunnel origin without its scheme`() {
        assertEquals("127.0.0.1:8081", displayAuthOrigin(tunnelAuthOrigin(8081)))
        assertEquals("127.0.0.1", displayAuthOrigin(tunnelAuthOrigin(80)))
    }
}
