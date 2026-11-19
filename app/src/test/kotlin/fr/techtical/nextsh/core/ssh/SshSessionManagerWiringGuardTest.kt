// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.core.logging.blankComments
import fr.techtical.nextsh.testutil.ProjectFiles
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

/**
 * Smoke alarm on the wiring of the interactive connections of `SshSessionManager`
 * (`connectWithSkKey`, `connectWithBiometricKey`, and the `connectInteractively` skeleton they
 * share), the one thing the unit tests cannot reach by running it: `SSHClient.connect` and the SSH
 * transport thread that runs the handlers. What can be run is run (`SshSessionManagerConnectTest`,
 * with a fake client and the real auth methods, `Fido2CallerCancellationTest`); this only checks
 * that the production functions still call the pieces those tests exercise, in their own bodies.
 *
 * Same compromise and same rule as `LogHygieneGuardTest`: a cheap source check. A source that cannot
 * be found FAILS the test (see [ProjectFiles]), so a guard that cannot find its source never passes
 * silently.
 */
class SshSessionManagerWiringGuardTest {

    // ── The real source ───────────────────────────────────────────────────────

    /** The text of [fileName] of `core/ssh`; fails if the source is missing. */
    private fun sourceOf(fileName: String): String = ProjectFiles.mainSource("core/ssh/$fileName").readText()

    private fun bodyOf(fileName: String, signature: String): String =
        functionBody(sourceOf(fileName), signature) ?: fail("$signature not found in $fileName")

    /** The body of `connectWithSkKey`. */
    private fun connectWithSkKeyBody(): String = bodyOf("SshSessionManager.kt", "suspend fun connectWithSkKey(")

    /** The body of the skeleton that both interactive connections go through. */
    private fun connectInteractivelyBody(): String =
        bodyOf("SshSessionManager.kt", "private suspend fun connectInteractively(")

    /** The body of `InteractiveAuth.prompting`, the watchdog sleep around a prompt. */
    private fun promptingBody(): String = bodyOf("InteractiveAuth.kt", "suspend fun <T> prompting(")

    @Test
    fun `the interactive connections make the prompt a child of the connecting coroutine`() {
        val body = connectInteractivelyBody()

        assertTrue(body.contains("currentCoroutineContext()[Job]"), "it must capture the Job of the connecting coroutine")
        assertTrue(
            Regex("""InteractiveAuth\(\s*connection\s*,\s*watchdog\s*\)""").containsMatchIn(body),
            "it must hand that Job to the method it builds, through InteractiveAuth",
        )
        assertTrue(promptingBody().contains("block(connection)"), "prompting must give the prompt that Job")
    }

    @Test
    fun `connectWithSkKey passes the Job of the connection to the FIDO2 request`() {
        val body = connectWithSkKeyBody()

        assertTrue(
            Regex("""auth\.prompting\s*\{\s*connection\s*->""").containsMatchIn(body),
            "the FIDO2 handler must run its prompt through InteractiveAuth.prompting",
        )
        assertTrue(
            Regex("""requestFido2Signature\(\s*challengeCallback\s*,\s*request\s*,\s*connection\b""").containsMatchIn(body),
            "it must pass that Job to requestFido2Signature",
        )
    }

    @Test
    fun `connectWithSkKey goes through the shared skeleton and never authenticates itself`() {
        val body = connectWithSkKeyBody()

        assertTrue(body.contains("connectInteractively("), "connectWithSkKey must use the skeleton")
        assertFalse(Regex("""\bclient\.auth\(""").containsMatchIn(body), "client.auth is called by the skeleton only")
        assertFalse(body.contains("AuthWatchdog("), "the watchdog is the skeleton's")
    }

    @Test
    fun `connectWithSkKey puts the credential ids it is given in the request of the prompt`() {
        val body = connectWithSkKeyBody()

        // Fido2ChallengeRequest defaults the list to empty: a request built without it compiles, and
        // a non-resident key then has nothing to answer with.
        val handler = lambdaOfCall(body, "SkAuthMethod(") ?: fail("SkAuthMethod has no handler")
        assertTrue(
            Regex("""Fido2ChallengeRequest\([^)]*allowedCredentialIds\s*=\s*allowedCredentialIds\b""").containsMatchIn(handler),
            "the request built in the handler must carry the allowedCredentialIds parameter",
        )
    }

    @Test
    fun `the interactive connections raise the transport wait for the authentication only`() {
        val body = connectInteractivelyBody()

        assertTrue(
            Regex("""withTransportTimeout\(\s*FIDO2_AUTH_TRANSPORT_TIMEOUT_MS\s*,\s*TRANSPORT_TIMEOUT_MS\s*\)""")
                .containsMatchIn(body),
            "client.auth must run inside withTransportTimeout(FIDO2_AUTH_TRANSPORT_TIMEOUT_MS, TRANSPORT_TIMEOUT_MS)",
        )
        // The call that waits for the server's answer is the one in the lambda, and the only one.
        val lambda = lambdaOfCall(body, "withTransportTimeout(") ?: fail("withTransportTimeout has no lambda")
        assertTrue(lambda.contains("client.auth("), "client.auth must be called inside the lambda of withTransportTimeout")
        assertEquals(1, Regex("""\bclient\.auth\(""").findAll(body).count(), "client.auth( calls in connectInteractively")
    }

    @Test
    fun `the interactive connections bound the server's silence with the watchdog`() {
        val body = connectInteractivelyBody()

        assertTrue(body.contains("AuthWatchdog("), "it must build the watchdog")
        // Its timers must not be children of the coroutine of the connection: they would die with
        // it when it is cancelled, while client.auth keeps blocking a thread.
        assertTrue(
            Regex("""AuthWatchdog\(\s*watchdogScope\b""").containsMatchIn(body),
            "the watchdog must run in a scope of its own, not in the cancellable scope of the connection",
        )
        assertTrue(
            Regex("""val\s+watchdogScope\s*=\s*CoroutineScope\(\s*SupervisorJob\(\)""").containsMatchIn(body),
            "that scope must be independent of the connecting coroutine",
        )
        // Armed once more between the building of the method and the authentication itself, i.e.
        // before client.auth starts (the arm of the prompt is the one of the end of the prompt).
        val betweenMethodAndAuth = body.substringAfter("buildMethod(").substringBefore("withTransportTimeout(")
        assertTrue(betweenMethodAndAuth.contains("watchdog.arm()"), "armed before client.auth")
        // Asleep while the user works with the key, awake again when the prompt ends, even on failure.
        val prompting = promptingBody()
        assertTrue(prompting.contains("watchdog.disarm()"), "disarmed while the prompt is open")
        assertTrue(
            Regex("""finally\s*\{[^}]*watchdog\.arm\(\)""").containsMatchIn(prompting),
            "armed again in a finally when the prompt ends",
        )
        // Closed in the finally of the whole attempt.
        assertTrue(
            Regex("""finally\s*\{[^}]*watchdog\.close\(\)""").containsMatchIn(body),
            "closed as soon as the authentication has returned",
        )
        // And closed right after a successful client.auth, before the session is registered: a
        // watchdog that expired in the meantime makes the attempt a failure, not a session.
        val afterAuth = body.substringAfter("authReturned.set(true)")
        assertTrue(
            Regex("""if\s*\(\s*watchdog\.close\(\)\s*\)\s*\{[^}]*abandon\(\s*sessionId[^}]*\}""").containsMatchIn(afterAuth),
            "a watchdog that had expired turns the success into an abandoned attempt",
        )
        assertTrue(
            afterAuth.indexOf("watchdog.close()") < afterAuth.indexOf("registerSession("),
            "checked before the session is registered",
        )
    }

    @Test
    fun `the interactive connections close the client when the connection is cancelled during the authentication`() {
        val body = connectInteractivelyBody()

        // A child that only ends by cancellation, and closes the client then, unless the
        // authentication has already returned.
        assertTrue(
            Regex("""cancellationGuard\s*=\s*launch\(\s*start\s*=\s*CoroutineStart\.UNDISPATCHED\s*\)""").containsMatchIn(body),
            "the guard must be UNDISPATCHED: one cancelled before it ever ran would close nothing",
        )
        val guard = lambdaOfCall(body, "cancellationGuard = launch(start = CoroutineStart.UNDISPATCHED)")
            ?: fail("the cancellation guard is gone")
        assertTrue(guard.contains("awaitCancellation()"), "the guard waits for the cancellation")
        assertTrue(
            Regex("""catch\s*\(\s*e:\s*CancellationException\s*\)\s*\{[^}]*authReturned[^}]*client\.disconnect\(\)""")
                .containsMatchIn(guard),
            "on cancellation, and while the authentication is under way, it closes the client",
        )
        assertTrue(
            Regex("""finally\s*\{[^}]*authReturned\.set\(true\)""").containsMatchIn(body),
            "authReturned is set as soon as client.auth has returned, whatever the outcome",
        )
        assertTrue(
            Regex("""finally\s*\{[^}]*cancellationGuard\?\.cancel\(\)""").containsMatchIn(body),
            "the guard is ended with the function, or the coroutine would wait for it",
        )
    }

    @Test
    fun `the interactive connections close the client of a failed attempt on every failure path`() {
        val body = connectInteractivelyBody()

        // One abandon per catch (CancellationException, UserAuthException, TransportException,
        // IOException, Exception), one for the missing callback, and one for a watchdog that
        // expired as the call returned.
        assertEquals(7, Regex("""\babandon\(sessionId\s*,""").findAll(body).count(), "abandon() calls")
        assertFalse(body.contains("cleanupSession("), "cleanupSession alone leaves the client open")
    }

    @Test
    fun `every connection is run by the function that releases a session whose caller left`() {
        val source = sourceOf("SshSessionManager.kt")
        for (signature in listOf(
            "override suspend fun connectWithPassword(",
            "override suspend fun connectWithKey(",
            "override suspend fun connectWithCertificate(",
            "private suspend fun connectInteractively(",
        )) {
            val body = functionBody(source, signature) ?: fail("$signature not found")
            // `withContext` alone would drop a session registered just before the cancellation.
            assertFalse(body.contains("withContext("), "$signature must not run its connection by itself")
            // From the signature to the opening brace of the body: the parameters, then `= connecting`.
            val header = source.substringAfter(signature).substringBefore("{")
            assertTrue(header.contains("= connecting"), "$signature goes through connecting {}")
            assertTrue(body.contains("ensureActive()"), "$signature checks the caller before it registers a session")
            assertTrue(
                Regex("""catch\s*\(\s*e:\s*CancellationException\s*\)\s*\{[^}]*abandon\(sessionId\s*,\s*attempt\)[^}]*throw e""")
                    .containsMatchIn(body),
                "$signature abandons its attempt and rethrows a cancellation",
            )
        }
    }

    @Test
    fun `connecting releases the session it registered when its caller is cancelled`() {
        val body = bodyOf("SshSessionManager.kt", "private suspend fun connecting(")

        assertTrue(body.contains("withContext(Dispatchers.IO)"), "it runs the connection on the IO dispatcher")
        assertTrue(
            Regex("""catch\s*\(\s*e:\s*Throwable\s*\)\s*\{[^}]*secret\?\.fill\([^}]*registration\.sessionId\?\.let\s*\{\s*release\(it\)\s*\}[^}]*throw e""")
                .containsMatchIn(body),
            "any way out wipes the secret, releases the registered session and goes on",
        )
    }

    // ── The fingerprint connection ────────────────────────────────────────────

    /** The body of `connectWithBiometricKey`. */
    private fun connectWithBiometricKeyBody(): String =
        bodyOf("SshSessionManager.kt", "suspend fun connectWithBiometricKey(")

    @Test
    fun `connectWithBiometricKey goes through the shared skeleton and never authenticates itself`() {
        val body = connectWithBiometricKeyBody()

        assertTrue(body.contains("connectInteractively("), "connectWithBiometricKey must use the skeleton")
        assertTrue(Regex("""keystoreKey\s*=\s*true""").containsMatchIn(body), "its key lives in the Android Keystore")
        assertFalse(Regex("""\bclient\.auth\(""").containsMatchIn(body), "client.auth is called by the skeleton only")
        assertFalse(body.contains("AuthWatchdog("), "the watchdog is the skeleton's")
        assertFalse(body.contains("authPublickey("), "SSHJ must not sign by itself with an unauthorized Signature")
    }

    @Test
    fun `connectWithBiometricKey passes the Job of the connection to the fingerprint request`() {
        val body = connectWithBiometricKeyBody()

        assertTrue(
            Regex("""auth\.prompting\s*\{\s*connection\s*->""").containsMatchIn(body),
            "the signer must run its prompt through InteractiveAuth.prompting",
        )
        assertTrue(
            Regex("""requestBiometricAuthorization\(\s*promptCallback\s*,\s*request\s*,\s*connection\b""").containsMatchIn(body),
            "it must pass that Job to requestBiometricAuthorization",
        )
    }

    @Test
    fun `connectWithBiometricKey opens the Keystore signature at PK_OK, asks, and signs only afterwards`() {
        val body = connectWithBiometricKeyBody()

        val signer = lambdaOfCall(body, "BiometricAuthMethod(publicKey)") ?: fail("BiometricAuthMethod has no signer")
        // Not before the server has accepted the key: the signer is what runs at PK_OK.
        assertFalse(
            body.substringBefore("BiometricAuthMethod(").contains("openSignature()"),
            "the Keystore signature must not be opened before the connection",
        )
        val opened = signer.indexOf("openSignature()")
        val asked = signer.indexOf("requestBiometricAuthorization(")
        val updated = signer.indexOf("signature.update(")
        val signed = signer.indexOf("signature.sign()")
        assertTrue(opened in 0 until asked, "opened, then asked")
        assertTrue(asked in 0 until updated, "asked, then updated")
        assertTrue(updated in 0 until signed, "updated, then signed")
        assertTrue(signer.contains("if (!authorized)"), "no signing without an authorization")
    }

    @Test
    fun `connectWithBiometricKey never gives the data to sign to the prompt`() {
        val body = connectWithBiometricKeyBody()

        // The request carries the Keystore handle and the labels: the data to sign stays on the SSH side.
        val request = Regex("""BiometricSignRequest\(([^)]*)\)""").find(body)?.groupValues?.get(1) ?: fail("no BiometricSignRequest")
        assertFalse(request.contains("signingData"), request)
    }

    @Test
    fun `connectWithBiometricKey refuses a key that is not EC P-256 before connecting`() {
        val body = connectWithBiometricKeyBody()

        val check = body.indexOf("isBiometricKeyAlgorithm(")
        assertTrue(check in 0 until body.indexOf("connectInteractively("), "checked before the skeleton builds a client")
    }

    // ── The client factory and the host key prompt ────────────────────────────

    /** The body of the factory of the SSH client: the TCP connection, the key exchange, the host key. */
    private fun createSshClientBody(): String = bodyOf("SshSessionManager.kt", "private suspend fun createSshClient(")

    /** The body of the check of an unknown host key. */
    private fun confirmBody(): String = bodyOf("InteractiveHostKeyVerifier.kt", "private fun confirm(")

    @Test
    fun `the key exchange is waited for longer around client connect, and nowhere else`() {
        val body = createSshClientBody()

        assertTrue(
            Regex("""withTransportTimeout\(\s*keyExchangeTimeoutMs\s*,\s*TRANSPORT_TIMEOUT_MS\s*\)""").containsMatchIn(body),
            "client.connect must run inside withTransportTimeout(keyExchangeTimeoutMs, TRANSPORT_TIMEOUT_MS)",
        )
        // The wait is the one of the key exchange, which the host key prompt is part of: it is the call
        // in the lambda, and the only one.
        val lambda = lambdaOfCall(body, "withTransportTimeout(") ?: fail("withTransportTimeout has no lambda")
        assertTrue(lambda.contains("client.connect("), "client.connect must be called inside the lambda of withTransportTimeout")
        assertEquals(1, Regex("""\bclient\.connect\(""").findAll(body).count(), "client.connect( calls in createSshClient")
    }

    @Test
    fun `the length of the wait is decided by whether a key is stored for the host`() {
        val body = createSshClientBody()

        assertTrue(
            Regex(
                """keyExchangeTimeoutMs\(\s*hostKeyStored\s*=\s*knownHostsVerifier\.findExistingAlgorithms\(\s*host\.hostname\s*,\s*host\.port\s*\)\.isNotEmpty\(\)\s*,?\s*\)""",
            ).containsMatchIn(body),
            "the wait must be raised for a host with no stored key only, looked up as the verifier looks it up",
        )
    }

    @Test
    fun `the host key verifier is the interactive one, with the connection as parent of its prompt`() {
        val body = createSshClientBody()

        assertTrue(Regex("""InteractiveHostKeyVerifier\(""").containsMatchIn(body), "createSshClient must build the interactive verifier")
        assertTrue(
            Regex("""connection\s*=\s*currentCoroutineContext\(\)\[Job\]""").containsMatchIn(body),
            "the verifier must get the Job of the connecting coroutine",
        )
        assertTrue(body.contains("client.addHostKeyVerifier(verifier)"), "and it is the one the client uses")
    }

    @Test
    fun `a failed connect reads how the prompt ended, then takes the prompt away, then says it`() {
        val body = createSshClientBody()

        val read = body.indexOf("verifier.promptEnd")
        val abandon = body.indexOf("verifier.abandonPrompt()")
        val marker = body.indexOf("HostKeyPromptEndedException(")
        assertTrue(read >= 0 && abandon >= 0 && marker >= 0, "all three must be there: $read, $abandon, $marker")
        // Abandoning ends the prompt as cancelled: it must be read before, or the real cause is lost.
        assertTrue(read < abandon, "the end of the prompt is read before the prompt is abandoned")
        assertTrue(abandon < marker, "and the marker is thrown once the prompt is gone")
    }

    @Test
    fun `the check of an unknown key refuses an abandoned connection first, and runs the prompt as a child of the connection`() {
        val body = confirmBody()

        assertTrue(body.indexOf("abandoned") in 0 until body.indexOf("callback()"), "abandoned is read before anything is asked")
        assertTrue(body.contains("Job(connection)"), "the prompt gets a scope that is a child of the connection")
        assertTrue(body.contains("withContext(scope)"), "and the callback runs in it")
        assertTrue(body.contains("scope.cancel()"), "the scope never outlives the prompt")
    }

    // ── The scoping of the guard itself ───────────────────────────────────────

    @Test
    fun `the body is that of the function asked for, not of its neighbours`() {
        val source = """
            class C {
                suspend fun connectWithSkKey(host: Host): R = withContext(IO) {
                    val a = "x${'$'}{y}"
                    try { work() } catch (e: Exception) { abandon(sessionId, c) }
                }

                suspend fun other(): R = withContext(IO) {
                    currentCoroutineContext()[Job]
                }
            }
        """.trimIndent()

        val body = functionBody(source, "suspend fun connectWithSkKey(")!!

        assertTrue(body.contains("abandon("))
        assertFalse(body.contains("currentCoroutineContext"), "the neighbour's code must not satisfy the guard")
    }

    @Test
    fun `braces in comments do not move the end of the body`() {
        val source = """
            suspend fun connectWithSkKey(): R = withContext(IO) {
                // } an early close in a comment
                work()
            }
            val after = 1
        """.trimIndent()

        val body = functionBody(source, "suspend fun connectWithSkKey(")!!

        assertTrue(body.contains("work()"))
        assertFalse(body.contains("val after"))
    }

    @Test
    fun `a missing function is reported as missing`() {
        assertEquals(null, functionBody("class C { fun other() {} }", "suspend fun connectWithSkKey("))
    }

    @Test
    fun `the lambda of a call is the block that follows it, not the code around it`() {
        val code = """
            client.auth(user, method)
            client.withTransportTimeout(A, B) {
                run { inner() }
                client.auth(user, method)
            }
            client.auth(user, method)
        """.trimIndent()

        val lambda = lambdaOfCall(code, "withTransportTimeout(")!!

        assertEquals(1, Regex("""client\.auth\(""").findAll(lambda).count(), "one call inside, two outside")
        assertTrue(lambda.contains("inner()"), "nested braces stay inside")
    }

    @Test
    fun `a call that is not followed by a lambda has none`() {
        assertEquals(null, lambdaOfCall("client.withTransportTimeout(A, B)\nclient.auth(u, m)", "withTransportTimeout("))
        assertEquals(null, lambdaOfCall("nothing here", "withTransportTimeout("))
    }

    /**
     * The block `{ ... }` that follows the call [call] (its arguments, then the trailing lambda),
     * without the braces, or null if the call has no trailing lambda. The parentheses of the
     * arguments are matched, so `(A, f(B)) { ... }` is read whole.
     */
    private fun lambdaOfCall(code: String, call: String): String? {
        val start = code.indexOf(call)
        if (start < 0) return null
        var i = start + call.length
        // A call written with its opening parenthesis has arguments to skip; a bare name
        // (`x = launch`) is directly followed by its lambda.
        if (call.endsWith("(")) {
            var parens = 1
            while (i < code.length && parens > 0) {
                when (code[i]) {
                    '(' -> parens++
                    ')' -> parens--
                }
                i++
            }
        }
        // Only blanks may separate the arguments from the lambda.
        while (i < code.length && code[i].isWhitespace()) i++
        if (i >= code.length || code[i] != '{') return null
        val open = i
        var depth = 0
        for (j in open until code.length) {
            when (code[j]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(open + 1, j)
                }
            }
        }
        return null
    }

    /**
     * The text between the braces of the function that starts with [signature], comments blanked,
     * or null. The body starts at the first `{` after the signature's `=` (expression body
     * `= withContext(...) {`) or after its parameter list.
     */
    private fun functionBody(source: String, signature: String): String? {
        val code = blankComments(source)
        val start = code.indexOf(signature)
        if (start < 0) return null
        val open = code.indexOf('{', code.indexOf(')', start))
        if (open < 0) return null
        var depth = 0
        for (i in open until code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(open + 1, i)
                }
            }
        }
        return null
    }
}
