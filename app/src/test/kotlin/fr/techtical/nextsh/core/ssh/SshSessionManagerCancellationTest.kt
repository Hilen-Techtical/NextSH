// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.data.preferences.SettingsDataStore
import fr.techtical.nextsh.shared.core.ssh.fido2.SkKeyType
import fr.techtical.nextsh.shared.core.ssh.fido2.SkSshPublicKey
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.testutil.RecordingTree
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import timber.log.Timber
import java.util.concurrent.atomic.AtomicReference

/**
 * A caller that leaves while a connection is made must not leave a session behind. Whatever the
 * moment of the cancellation, the session is either never registered, or released (and so closed):
 * a registered session with nobody to own its reference would stay open, unseen, until the process
 * ends.
 */
class SshSessionManagerCancellationTest {

    private enum class Path { PASSWORD, KEY, CERTIFICATE, FIDO2 }

    private fun skKey() = SkSshPublicKey(SkKeyType.SK_ED25519, ByteArray(32) { 1 })

    private suspend fun connect(harness: SessionHarness, path: Path): SshResult<*> = when (path) {
        Path.PASSWORD -> harness.manager.connectWithPassword(harness.host, "secret".toCharArray())
        Path.KEY -> harness.manager.connectWithKey(harness.host, "PEM", null)
        Path.CERTIFICATE -> harness.manager.connectWithCertificate(harness.host, "PEM", "CERT", null)
        Path.FIDO2 -> {
            harness.manager.fido2ChallengeCallback = { Fido2ChallengeResponse.Cancelled }
            harness.manager.connectWithSkKey(harness.host, skKey())
        }
    }

    /**
     * Connects on [path] in a coroutine of its own, and cancels it from inside [hook]: [hook] is told
     * how to set the moment (during the authentication, right after the registration) and gets the
     * cancellation to run there. Returns the job once it is over.
     */
    private fun cancelledConnection(
        harness: SessionHarness,
        path: Path,
        hook: (SessionHarness, cancel: () -> Unit) -> Unit,
    ): Job {
        val caller = AtomicReference<Job?>(null)
        hook(harness) { caller.get()?.cancel() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // Lazy: the hook reads the job, which must be known before the connection can run.
        val job = scope.launch(start = CoroutineStart.LAZY) { connect(harness, path) }
        caller.set(job)
        job.start()
        runBlocking { withTimeout(5_000) { job.join() } }
        scope.cancel()
        return job
    }

    // ── Before the session is registered ──────────────────────────────────────

    @Test
    fun `a caller cancelled during the authentication leaves no session, whatever the path`() {
        for (path in Path.entries) {
            val harness = SessionHarness()

            val job = cancelledConnection(harness, path) { h, cancel -> h.duringAuthentication = cancel }

            assertTrue(job.isCancelled, "$path: the cancellation reaches the caller")
            assertTrue(harness.manager.activeSessions.value.isEmpty(), "$path: nothing is registered")
            assertEquals(1, harness.closedLinks(), "$path: the client of the attempt is closed")
        }
    }

    // ── After the session is registered ───────────────────────────────────────

    @Test
    fun `a caller cancelled right after the registration releases the session, whatever the path`() {
        for (path in Path.entries) {
            val harness = SessionHarness()
            val ends = mutableListOf<Pair<String, SessionEnd>>()
            harness.manager.addSessionEndListener { id, end -> ends += id to end }

            val job = cancelledConnection(harness, path) { h, cancel -> h.afterRegistration = cancel }

            assertTrue(job.isCancelled, "$path")
            // The result was dropped with the cancellation: its one reference was nobody's, so it was
            // given back, which closed the session.
            assertTrue(harness.manager.activeSessions.value.isEmpty(), "$path: the session is released")
            assertEquals(1, harness.closedLinks(), "$path: and closed")
            assertEquals(1, ends.size, "$path: announced once")
            assertEquals(SessionEnd.Closed, ends.single().second, "$path")
        }
    }

    @Test
    fun `a caller that is not cancelled keeps the session it is given`() {
        for (path in Path.entries) {
            val harness = SessionHarness()

            val result = runBlocking { connect(harness, path) }

            assertTrue(result is SshResult.Success, "$path: $result")
            assertEquals(1, harness.manager.activeSessions.value.size, "$path")
            assertEquals(0, harness.closedLinks(), "$path")
        }
    }

    // ── The cancellation is never turned into a result ────────────────────────

    @Test
    fun `a cancellation out of the client factory is rethrown, not logged as an error nor turned into UNKNOWN`() {
        val tree = RecordingTree()
        Timber.plant(tree)
        try {
            for (path in Path.entries) {
                val harness = SessionHarness()
                harness.manager.clientFactory = { throw CancellationException("connection cancelled") }

                val failure = runCatching { runBlocking { connect(harness, path) } }.exceptionOrNull()

                assertTrue(failure is CancellationException, "$path: $failure")
                assertTrue(harness.manager.activeSessions.value.isEmpty(), "$path: the attempt is forgotten")
            }
        } finally {
            Timber.uproot(tree)
        }

        val errors = tree.entries.filter { it.priority >= android.util.Log.ERROR }
        assertTrue(errors.isEmpty(), "a cancellation is not an error to log: $errors")
    }

    @Test
    fun `a cancellation during the authentication that comes out as an exception is rethrown too`() {
        for (path in Path.entries) {
            val harness = SessionHarness()
            harness.duringAuthentication = { throw CancellationException("connection cancelled") }

            val failure = runCatching { runBlocking { connect(harness, path) } }.exceptionOrNull()

            assertTrue(failure is CancellationException, "$path: $failure")
            assertTrue(harness.manager.activeSessions.value.isEmpty(), "$path")
            assertEquals(1, harness.closedLinks(), "$path")
        }
    }

    // ── A caller that is cancelled before the connection even begins ──────────

    @Test
    fun `a caller already cancelled has its password and passphrase wiped, and nothing is attempted`() {
        for (path in listOf(Path.PASSWORD, Path.KEY, Path.CERTIFICATE)) {
            val harness = SessionHarness()
            val secret = "secret".toCharArray()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

            // Cancelled before the call: `withContext` throws at its entry and the block never runs,
            // which is where the secret used to be wiped.
            val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                currentCoroutineContext().job.cancel()
                when (path) {
                    Path.PASSWORD -> harness.manager.connectWithPassword(harness.host, secret)
                    Path.KEY -> harness.manager.connectWithKey(harness.host, "PEM", secret)
                    else -> harness.manager.connectWithCertificate(harness.host, "PEM", "CERT", secret)
                }
            }
            runBlocking { withTimeout(5_000) { job.join() } }
            scope.cancel()

            assertTrue(job.isCancelled, "$path")
            assertFalse(secret.any { it != '\u0000' }, "$path: the secret does not outlive the attempt")
            assertTrue(harness.manager.activeSessions.value.isEmpty(), "$path: no entry")
            assertEquals(0, harness.closedLinks(), "$path: no client was ever built")
        }
    }

    // ── Any way out of the connection wipes the secret ────────────────────────

    @Test
    fun `an exception that leaves the connection before its own handling still wipes the secret`() {
        for (path in listOf(Path.PASSWORD, Path.KEY, Path.CERTIFICATE)) {
            // The settings store fails when the connect timeout is read, the first step of every path,
            // before the block has a try of its own.
            val settings = mockk<SettingsDataStore>()
            every { settings.settingsFlow } returns flow { throw java.io.IOException("datastore unreadable") }
            val manager = SshSessionManager(
                knownHostsVerifier = mockk(),
                sshKeyManager = mockk(),
                settingsDataStore = settings,
                context = mockk(relaxed = true),
            )
            val secret = "secret".toCharArray()

            val failure = runCatching {
                runBlocking {
                    when (path) {
                        Path.PASSWORD -> manager.connectWithPassword(SessionHarness().host, secret)
                        Path.KEY -> manager.connectWithKey(SessionHarness().host, "PEM", secret)
                        else -> manager.connectWithCertificate(SessionHarness().host, "PEM", "CERT", secret)
                    }
                }
            }.exceptionOrNull()

            assertTrue(failure is java.io.IOException, "$path: $failure")
            assertFalse(secret.any { it != '\u0000' }, "$path: the secret does not outlive the attempt")
            assertTrue(manager.activeSessions.value.isEmpty(), "$path")
        }
    }

    @Test
    fun `a connection that succeeds keeps working with the secret it was given until the authentication`() {
        // The password is read by the authentication, then wiped by the block: the way out by an
        // exception must not have changed the way in. Checked on what the client was given.
        val harness = SessionHarness()
        val given = mutableListOf<String>()
        harness.manager.clientFactory = {
            mockk<net.schmizz.sshj.SSHClient>(relaxed = true).also { client ->
                every { client.transport } returns mockk(relaxed = true)
                every { client.isConnected } returns true
                every { client.authPassword(any<String>(), any<String>()) } answers { given += secondArg<String>() }
            }
        }
        val password = "hunter2".toCharArray()

        val result = runBlocking { harness.manager.connectWithPassword(harness.host, password) }

        assertTrue(result is SshResult.Success, "$result")
        assertEquals(listOf("hunter2"), given)
        assertFalse(password.any { it != '\u0000' }, "wiped right after the authentication, as before")
    }

    // ── The cancellation of the very first step ───────────────────────────────

    @Test
    fun `a caller cancelled while the timeout is read wipes the password and registers nothing`() {
        val read = kotlinx.coroutines.CompletableDeferred<Unit>()
        val settings = mockk<SettingsDataStore>()
        every { settings.settingsFlow } returns flow {
            read.complete(Unit)
            awaitCancellation()
        }
        val manager = SshSessionManager(
            knownHostsVerifier = mockk(),
            sshKeyManager = mockk(),
            settingsDataStore = settings,
            context = mockk(relaxed = true),
        )
        val password = "secret".toCharArray()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val job = scope.launch { manager.connectWithPassword(SessionHarness().host, password) }

        runBlocking { withTimeout(5_000) { read.await() } }
        job.cancel()
        runBlocking { withTimeout(5_000) { job.join() } }
        scope.cancel()

        assertTrue(job.isCancelled)
        assertTrue(manager.activeSessions.value.isEmpty(), "no CONNECTING entry was left")
        assertFalse(password.any { it != '\u0000' }, "the password does not outlive the attempt")
    }
}
