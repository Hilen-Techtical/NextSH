// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.domain.usecase.ConnectSessionUseCase
import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.SessionStatus
import fr.techtical.nextsh.shared.domain.model.SshErrorCode
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.shared.domain.model.SshSession
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidTunnelSessionProviderTest {

    private val hostA = host("host-a")
    private val hostB = host("host-b")

    private val liveSessions = MutableStateFlow<List<SshSession>>(emptyList())

    /** The references held on each registered session, as the real manager counts them. */
    private val references = mutableMapOf<String, Int>()

    /** Sessions that die between the look at the registry and the reference that follows. */
    private val diesBeforeAcquire = mutableSetOf<String>()

    private val sessionManager = mockk<SshSessionManager>().also {
        every { it.sessions } returns liveSessions
        // The client of every registered session is alive unless a test says otherwise.
        every { it.isConnected(any()) } returns true
        every { it.isAuthenticated(any()) } returns true
        // The counting of the real manager: a reference on a registered session, the last release
        // takes it out of the registry.
        every { it.acquire(any()) } answers {
            val id = firstArg<String>()
            if (id in diesBeforeAcquire) {
                liveSessions.value = liveSessions.value.filter { s -> s.id != id }
            }
            val registered = liveSessions.value.any { s -> s.id == id }
            if (registered) references.merge(id, 1, Int::plus)
            registered
        }
        every { it.release(any()) } answers {
            val id = firstArg<String>()
            val left = (references[id] ?: 0) - 1
            if (left > 0) {
                references[id] = left
            } else {
                references.remove(id)
                liveSessions.value = liveSessions.value.filter { s -> s.id != id }
            }
        }
    }
    private val connectSession = mockk<ConnectSessionUseCase>()

    private fun held(sessionId: String): Int = references[sessionId] ?: 0

    private val linger = 10_000L

    /**
     * The attempts run on the scheduler of the test, so that runCurrent drives them too, and so does
     * the linger of their own reference (virtual time).
     */
    private fun TestScope.newProvider() =
        AndroidTunnelSessionProvider(
            sessionManager, connectSession, StandardTestDispatcher(testScheduler), { delay(linger) },
        )

    // ── Reuse and connection ─────────────────────────────────────────────────

    @Test
    fun `reuses the connected session of the host without connecting`() = runTest {
        val provider = newProvider()
        liveSessions.value = listOf(session("live-a", hostA))

        val result = provider.sessionFor(hostA)

        assertEquals(SshResult.Success("live-a"), result)
        coVerify(exactly = 0) { connectSession(any()) }
        // The tunnel that is about to start on it holds a reference of its own on the session.
        assertEquals(1, held("live-a"))
    }

    @Test
    fun `every request that reuses a session takes its own reference on it`() = runTest {
        val provider = newProvider()
        liveSessions.value = listOf(session("live-a", hostA))
        // The tab that opened it holds the first.
        references["live-a"] = 1

        provider.sessionFor(hostA)
        provider.sessionFor(hostA)

        assertEquals(3, held("live-a"))
    }

    @Test
    fun `a session that dies between the look and the reference is not handed out, a new one is connected`() = runTest {
        val provider = newProvider()
        liveSessions.value = listOf(session("dying-a", hostA))
        diesBeforeAcquire += "dying-a"
        coEvery { connectSession(hostA) } coAnswers { connected(session("new-a", hostA)) }

        val result = provider.sessionFor(hostA)

        assertEquals(SshResult.Success("new-a"), result)
        assertEquals(0, held("dying-a"))
        advanceUntilIdle()
        assertEquals(1, held("new-a"))
    }

    @Test
    fun `connects when the only sessions are other hosts or not connected`() = runTest {
        val provider = newProvider()
        liveSessions.value = listOf(
            session("live-b", hostB),
            session("connecting-a", hostA, SessionStatus.CONNECTING),
            session("dead-a", hostA, SessionStatus.DISCONNECTED),
            session("error-a", hostA, SessionStatus.ERROR),
        )
        coEvery { connectSession(hostA) } coAnswers { connected(session("new-a", hostA)) }

        val result = provider.sessionFor(hostA)

        assertEquals(SshResult.Success("new-a"), result)
        coVerify(exactly = 1) { connectSession(hostA) }
        // The request owns one reference, and the attempt keeps the one it had from the connection
        // for the linger, then gives it back.
        assertEquals(2, held("new-a"))
        advanceUntilIdle()
        assertEquals(1, held("new-a"))
    }

    @Test
    fun `the reference of the attempt lingers for its whole delay, and not a moment longer`() = runTest {
        val provider = newProvider()
        coEvery { connectSession(hostA) } coAnswers { connected(session("new-a", hostA)) }

        provider.sessionFor(hostA)

        assertEquals(2, held("new-a"))
        advanceTimeBy(linger - 1)
        runCurrent()
        assertEquals(2, held("new-a"), "still lingering")
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, held("new-a"), "given back, the request's own is left")
    }

    @Test
    fun `a tunnel of the wave that fails to start leaves the session to the next one, within the linger`() = runTest {
        val provider = newProvider()
        coEvery { connectSession(hostA) } coAnswers { connected(session("new-a", hostA)) }

        // Tunnel A gets the session, then fails to start (its local port is used): the tunnel manager
        // gives the reference back. It is the last one but the attempt's own.
        assertEquals(SshResult.Success("new-a"), provider.sessionFor(hostA))
        sessionManager.release("new-a")
        assertTrue(liveSessions.value.any { it.id == "new-a" }, "the session did not close under the wave")

        // Tunnel B, next in the serial auto-start, reuses it: no second connection, no second prompt.
        assertEquals(SshResult.Success("new-a"), provider.sessionFor(hostA))
        coVerify(exactly = 1) { connectSession(hostA) }
        advanceUntilIdle()
        assertEquals(1, held("new-a"), "B's own reference holds it once the linger is over")
        assertTrue(liveSessions.value.any { it.id == "new-a" })
    }

    @Test
    fun `without a next tunnel, the session of a failed start closes when the linger is over`() = runTest {
        val provider = newProvider()
        coEvery { connectSession(hostA) } coAnswers { connected(session("new-a", hostA)) }
        provider.sessionFor(hostA)
        sessionManager.release("new-a")

        advanceTimeBy(linger)
        runCurrent()

        assertTrue(liveSessions.value.isEmpty(), "nobody holds it any more")
    }

    // ── hasLiveSession: the rule of reuse, asked without connecting ──────────

    @Test
    fun `hasLiveSession is true only where sessionFor would reuse a session, and never connects`() = runTest {
        val provider = newProvider()
        assertFalse(provider.hasLiveSession(hostA), "no session at all")

        liveSessions.value = listOf(
            session("zombie-a", hostA),
            session("connecting-a", hostA, SessionStatus.CONNECTING),
            session("half-a", hostA),
            session("live-b", hostB),
        )
        every { sessionManager.isConnected("zombie-a") } returns false
        every { sessionManager.isAuthenticated("half-a") } returns false
        assertFalse(provider.hasLiveSession(hostA), "a dead client, a session still connecting, an unauthenticated one")
        assertTrue(provider.hasLiveSession(hostB), "another host's live session is that host's")

        liveSessions.value = liveSessions.value + session("live-a", hostA)
        assertTrue(provider.hasLiveSession(hostA))
        assertEquals(SshResult.Success("live-a"), provider.sessionFor(hostA), "the same answer as the reuse")
        coVerify(exactly = 0) { connectSession(any()) }
    }
    // ── A reused session must be alive ───────────────────────────────────────

    @Test
    fun `a connected session whose client is dead is not reused and a new connection is made`() = runTest {
        val provider = newProvider()
        // Registered CONNECTED, but the client died and the status did not follow yet.
        liveSessions.value = listOf(session("zombie-a", hostA))
        every { sessionManager.isConnected("zombie-a") } returns false
        coEvery { connectSession(hostA) } coAnswers { connected(session("new-a", hostA)) }

        val result = provider.sessionFor(hostA)

        assertEquals(SshResult.Success("new-a"), result)
        coVerify(exactly = 1) { connectSession(hostA) }
        // Left to whoever opened it: the provider neither closes nor unregisters it.
        assertTrue(liveSessions.value.any { it.id == "zombie-a" })
    }

    @Test
    fun `a connected session whose client reports itself not authenticated is not reused`() = runTest {
        // A defensive check: the real manager only registers a client once it is authenticated,
        // so this pins that the provider asks, not a state the manager produces today.
        val provider = newProvider()
        liveSessions.value = listOf(session("half-a", hostA))
        every { sessionManager.isAuthenticated("half-a") } returns false
        coEvery { connectSession(hostA) } coAnswers { connected(session("new-a", hostA)) }

        val result = provider.sessionFor(hostA)

        assertEquals(SshResult.Success("new-a"), result)
        coVerify(exactly = 1) { connectSession(hostA) }
    }

    @Test
    fun `a live session is still reused when a dead one of the same host comes first`() = runTest {
        val provider = newProvider()
        liveSessions.value = listOf(session("zombie-a", hostA), session("live-a", hostA))
        every { sessionManager.isConnected("zombie-a") } returns false

        val result = provider.sessionFor(hostA)

        assertEquals(SshResult.Success("live-a"), result)
        coVerify(exactly = 0) { connectSession(any()) }
    }

    @Test
    fun `a session left by a cancelled attempt is not reused if its client is dead by then`() = runTest {
        val provider = newProvider()
        val windDown = CompletableDeferred<Unit>()
        var calls = 0
        // The client of the session this attempt will leave dies right after: no session is
        // registered under that id yet, so the first look of the next request finds nothing and
        // only the check under the lock of the host sees it.
        every { sessionManager.isConnected("late-a") } returns false
        coEvery { connectSession(hostA) } coAnswers {
            calls++
            if (calls == 1) {
                try {
                    awaitCancellation()
                } catch (e: CancellationException) {
                    withContext(NonCancellable) { windDown.await() }
                    connected(session("late-a", hostA))
                }
            } else {
                connected(session("new-a", hostA))
            }
        }

        val first = launch { provider.sessionFor(hostA) }
        runCurrent()
        first.cancelAndJoin()
        runCurrent()
        val next = async { provider.sessionFor(hostA) }
        runCurrent()
        assertFalse(next.isCompleted)

        windDown.complete(Unit)

        assertEquals(SshResult.Success("new-a"), next.await())
        assertEquals(2, calls)
    }

    @Test
    fun `an error of the connection is passed on unchanged`() = runTest {
        val provider = newProvider()
        coEvery { connectSession(hostA) } returns
            SshResult.Error(SshErrorCode.FIDO2_USER_CANCELLED, "Cle de securite annulee")

        val result = provider.sessionFor(hostA)

        assertEquals(SshResult.Error(SshErrorCode.FIDO2_USER_CANCELLED, "Cle de securite annulee"), result)
    }

    @Test
    fun `a live session that appears while an attempt is pending is reused at once`() = runTest {
        val provider = newProvider()
        val gate = CompletableDeferred<Unit>()
        coEvery { connectSession(hostA) } coAnswers {
            gate.await()
            connected(session("new-a", hostA))
        }
        val pending = async { provider.sessionFor(hostA) }
        runCurrent()

        // Connected elsewhere meanwhile (a terminal opened from the host list, say).
        liveSessions.value = listOf(session("live-a", hostA))
        val late = async { provider.sessionFor(hostA) }
        runCurrent()

        assertEquals(SshResult.Success("live-a"), late.await())
        assertFalse(pending.isCompleted)
        gate.complete(Unit)
        assertEquals(SshResult.Success("new-a"), pending.await())
        coVerify(exactly = 1) { connectSession(hostA) }
        advanceUntilIdle()
        assertEquals(1, held("live-a"))
        assertEquals(1, held("new-a"))
    }

    // ── One attempt per host and per wave ────────────────────────────────────

    @Test
    fun `simultaneous requests for one host share one attempt and its session`() = runTest {
        val provider = newProvider()
        val gate = CompletableDeferred<Unit>()
        coEvery { connectSession(hostA) } coAnswers {
            gate.await()
            connected(session("new-a", hostA))
        }

        val requests = List(4) { async { provider.sessionFor(hostA) } }
        runCurrent()

        // One request reached the use case, the others wait for its outcome.
        coVerify(exactly = 1) { connectSession(hostA) }
        assertTrue(requests.none { it.isCompleted })

        gate.complete(Unit)

        requests.forEach { assertEquals(SshResult.Success("new-a"), it.await()) }
        coVerify(exactly = 1) { connectSession(hostA) }
        // One reference per request, and the attempt's own while it lingers.
        assertEquals(5, held("new-a"))
        advanceUntilIdle()
        // Then none left over for the attempt that connected it: the tunnels are the only holders,
        // and the last of them to stop closes the session.
        assertEquals(4, held("new-a"))
        // A request after the end finds the session the attempt left, and connects no more.
        assertEquals(SshResult.Success("new-a"), provider.sessionFor(hostA))
        coVerify(exactly = 1) { connectSession(hostA) }
        assertEquals(5, held("new-a"))
    }

    @Test
    fun `the reference of the attempt goes back once the last request has taken its own`() = runTest {
        val provider = newProvider()
        val gate = CompletableDeferred<Unit>()
        coEvery { connectSession(hostA) } coAnswers {
            gate.await()
            connected(session("new-a", hostA))
        }
        val first = async { provider.sessionFor(hostA) }
        val second = async { provider.sessionFor(hostA) }
        runCurrent()

        gate.complete(Unit)
        first.await()
        second.await()

        // The two requests, and the attempt's own for the linger...
        assertEquals(3, held("new-a"))
        advanceUntilIdle()
        // ...then it goes back. If it stayed, the session would outlive its last tunnel for good.
        assertEquals(2, held("new-a"))
        sessionManager.release("new-a")
        sessionManager.release("new-a")
        assertTrue(liveSessions.value.isEmpty(), "closed with the last tunnel, nothing was left holding it")
    }

    @Test
    fun `simultaneous requests get the same failure and the key is asked once`() = runTest {
        val provider = newProvider()
        val gate = CompletableDeferred<Unit>()
        coEvery { connectSession(hostA) } coAnswers {
            gate.await()
            SshResult.Error(SshErrorCode.FIDO2_USER_CANCELLED, "Cle de securite annulee")
        }

        val requests = List(4) { async { provider.sessionFor(hostA) } }
        runCurrent()
        gate.complete(Unit)

        val results = requests.map { it.await() }
        assertEquals(
            SshResult.Error(SshErrorCode.FIDO2_USER_CANCELLED, "Cle de securite annulee"),
            results.first(),
        )
        // The very same outcome, not four attempts that happen to fail alike.
        results.forEach { assertSame(results.first(), it) }
        coVerify(exactly = 1) { connectSession(hostA) }
    }

    @Test
    fun `simultaneous requests get the same other failure`() = runTest {
        val provider = newProvider()
        val gate = CompletableDeferred<Unit>()
        coEvery { connectSession(hostA) } coAnswers {
            gate.await()
            SshResult.Error(SshErrorCode.HOST_UNREACHABLE, "Hote injoignable")
        }

        val requests = List(3) { async { provider.sessionFor(hostA) } }
        runCurrent()
        gate.complete(Unit)

        requests.forEach {
            assertEquals(SshResult.Error(SshErrorCode.HOST_UNREACHABLE, "Hote injoignable"), it.await())
        }
        coVerify(exactly = 1) { connectSession(hostA) }
    }

    @Test
    fun `a request after the end of a failed attempt starts a new one`() = runTest {
        val provider = newProvider()
        var attempts = 0
        coEvery { connectSession(hostA) } coAnswers {
            attempts++
            if (attempts == 1) {
                SshResult.Error(SshErrorCode.FIDO2_USER_CANCELLED, "Cle de securite annulee")
            } else {
                connected(session("new-a", hostA))
            }
        }

        val first = provider.sessionFor(hostA)
        val second = provider.sessionFor(hostA)

        assertEquals(SshResult.Error(SshErrorCode.FIDO2_USER_CANCELLED, "Cle de securite annulee"), first)
        assertEquals(SshResult.Success("new-a"), second)
        assertEquals(2, attempts)
    }

    @Test
    fun `two hosts connect in parallel without waiting for each other`() = runTest {
        val provider = newProvider()
        val gateA = CompletableDeferred<Unit>()
        coEvery { connectSession(hostA) } coAnswers {
            gateA.await()
            SshResult.Error(SshErrorCode.FIDO2_USER_CANCELLED, "Cle de securite annulee")
        }
        coEvery { connectSession(hostB) } coAnswers { connected(session("new-b", hostB)) }

        val a = async { provider.sessionFor(hostA) }
        runCurrent()
        val b = async { provider.sessionFor(hostB) }
        runCurrent()

        // B is done while A is still connecting, and A's failure does not reach B.
        assertTrue(b.isCompleted)
        assertFalse(a.isCompleted)
        assertEquals(SshResult.Success("new-b"), b.await())

        gateA.complete(Unit)
        assertEquals(SshResult.Error(SshErrorCode.FIDO2_USER_CANCELLED, "Cle de securite annulee"), a.await())
        coVerify(exactly = 1) { connectSession(hostA) }
        coVerify(exactly = 1) { connectSession(hostB) }
    }

    // ── Cancellation ─────────────────────────────────────────────────────────

    @Test
    fun `a request that leaves does not cancel the attempt while others wait`() = runTest {
        val provider = newProvider()
        val gate = CompletableDeferred<Unit>()
        var attemptCancelled = false
        coEvery { connectSession(hostA) } coAnswers {
            try {
                gate.await()
            } catch (e: CancellationException) {
                attemptCancelled = true
                throw e
            }
            connected(session("new-a", hostA))
        }

        // The one that leaves is the one that started the attempt.
        val leaving = launch { provider.sessionFor(hostA) }
        runCurrent()
        val stayingOne = async { provider.sessionFor(hostA) }
        val stayingTwo = async { provider.sessionFor(hostA) }
        runCurrent()

        leaving.cancelAndJoin()
        runCurrent()
        assertFalse(attemptCancelled)

        gate.complete(Unit)
        assertEquals(SshResult.Success("new-a"), stayingOne.await())
        assertEquals(SshResult.Success("new-a"), stayingTwo.await())
        assertFalse(attemptCancelled)
        coVerify(exactly = 1) { connectSession(hostA) }
    }

    @Test
    fun `the attempt is cancelled when its last request leaves and the next one starts anew`() = runTest {
        val provider = newProvider()
        var attemptCancelled = false
        var calls = 0
        coEvery { connectSession(hostA) } coAnswers {
            calls++
            if (calls == 1) {
                // The real use case turns every exception, a cancellation included, into an error.
                try {
                    awaitCancellation()
                } catch (e: CancellationException) {
                    attemptCancelled = true
                    SshResult.Error(SshErrorCode.UNKNOWN, "swallowed")
                }
            } else {
                connected(session("new-a", hostA))
            }
        }
        var firstResult: SshResult<String>? = null

        val first = launch { firstResult = provider.sessionFor(hostA) }
        val second = launch { provider.sessionFor(hostA) }
        runCurrent()

        first.cancelAndJoin()
        runCurrent()
        assertFalse(attemptCancelled)

        second.cancelAndJoin()
        runCurrent()

        assertTrue(first.isCancelled)
        assertTrue(second.isCancelled)
        assertTrue(attemptCancelled)
        assertNull(firstResult)

        // Nothing is left running for the host: the next request makes a fresh attempt.
        assertEquals(SshResult.Success("new-a"), provider.sessionFor(hostA))
        assertEquals(2, calls)
    }

    @Test
    fun `a request arriving while a cancelled attempt winds down waits for it`() = runTest {
        val provider = newProvider()
        val windDown = CompletableDeferred<Unit>()
        var calls = 0
        var inProgress = 0
        var mostInProgress = 0
        coEvery { connectSession(hostA) } coAnswers {
            calls++
            inProgress++
            mostInProgress = maxOf(mostInProgress, inProgress)
            try {
                if (calls == 1) {
                    try {
                        awaitCancellation()
                    } catch (e: CancellationException) {
                        // A blocking connection that takes its time to give up.
                        withContext(NonCancellable) { windDown.await() }
                        SshResult.Error(SshErrorCode.UNKNOWN, "swallowed")
                    }
                } else {
                    connected(session("new-a", hostA))
                }
            } finally {
                inProgress--
            }
        }

        val first = launch { provider.sessionFor(hostA) }
        runCurrent()
        first.cancelAndJoin()
        runCurrent()

        val next = async { provider.sessionFor(hostA) }
        runCurrent()

        // The first attempt is cancelled but not over: no second connection yet.
        assertEquals(1, calls)
        assertFalse(next.isCompleted)

        windDown.complete(Unit)

        assertEquals(SshResult.Success("new-a"), next.await())
        assertEquals(2, calls)
        assertEquals(1, mostInProgress)
    }

    @Test
    fun `a third request after a cancelled second one still waits for the first attempt`() = runTest {
        val provider = newProvider()
        val windDown = CompletableDeferred<Unit>()
        var calls = 0
        var inProgress = 0
        var mostInProgress = 0
        coEvery { connectSession(hostA) } coAnswers {
            calls++
            inProgress++
            mostInProgress = maxOf(mostInProgress, inProgress)
            try {
                if (calls == 1) {
                    try {
                        awaitCancellation()
                    } catch (e: CancellationException) {
                        // A blocking connection that takes its time to give up.
                        withContext(NonCancellable) { windDown.await() }
                        SshResult.Error(SshErrorCode.UNKNOWN, "swallowed")
                    }
                } else {
                    connected(session("new-a", hostA))
                }
            } finally {
                inProgress--
            }
        }

        val first = launch { provider.sessionFor(hostA) }
        runCurrent()
        first.cancelAndJoin()
        runCurrent()

        // The second request queues behind the first attempt, then gives up before its turn.
        val second = launch { provider.sessionFor(hostA) }
        runCurrent()
        second.cancelAndJoin()
        runCurrent()

        val third = async { provider.sessionFor(hostA) }
        runCurrent()

        // The first attempt is still winding down: neither of the others may connect meanwhile.
        assertEquals(1, calls)
        assertEquals(1, mostInProgress)
        assertFalse(third.isCompleted)

        windDown.complete(Unit)

        assertEquals(SshResult.Success("new-a"), third.await())
        assertEquals(2, calls)
        assertEquals(1, mostInProgress)
    }

    @Test
    fun `a session left by a cancelled attempt is reused instead of connecting again`() = runTest {
        val provider = newProvider()
        val windDown = CompletableDeferred<Unit>()
        var calls = 0
        coEvery { connectSession(hostA) } coAnswers {
            calls++
            if (calls == 1) {
                try {
                    awaitCancellation()
                } catch (e: CancellationException) {
                    // The connection went through just as its last waiter left: the session is
                    // registered, but nobody collects the value of the cancelled attempt.
                    withContext(NonCancellable) { windDown.await() }
                    connected(session("late-a", hostA))
                }
            } else {
                connected(session("new-a", hostA))
            }
        }

        val first = launch { provider.sessionFor(hostA) }
        runCurrent()
        first.cancelAndJoin()
        runCurrent()

        // No live session yet when this request arrives: it waits for the first attempt to end.
        val next = async { provider.sessionFor(hostA) }
        runCurrent()
        assertFalse(next.isCompleted)

        windDown.complete(Unit)

        // Nobody was left to receive the late session, but its connection went through: the
        // attempt's reference keeps it for the linger, and the next request takes it, with a
        // reference of its own, instead of asking for the key a second time.
        assertEquals(SshResult.Success("late-a"), next.await())
        assertEquals(1, calls)
        assertEquals(2, held("late-a"), "the request's, and the attempt's while it lingers")
        advanceUntilIdle()
        assertEquals(1, held("late-a"))
    }

    @Test
    fun `the session of a cancelled attempt that nobody takes closes when the linger is over`() = runTest {
        val provider = newProvider()
        val windDown = CompletableDeferred<Unit>()
        coEvery { connectSession(hostA) } coAnswers {
            try {
                awaitCancellation()
            } catch (e: CancellationException) {
                withContext(NonCancellable) { windDown.await() }
                connected(session("late-a", hostA))
            }
        }
        val first = launch { provider.sessionFor(hostA) }
        runCurrent()
        first.cancelAndJoin()
        runCurrent()
        windDown.complete(Unit)
        runCurrent()

        assertEquals(1, held("late-a"), "lingering, so a request that comes now finds it live")
        advanceUntilIdle()

        assertEquals(0, held("late-a"))
        assertTrue(liveSessions.value.isEmpty(), "closed: nobody took it")
    }

    @Test
    fun `a request that leaves does not take the session from the one that stays`() = runTest {
        val provider = newProvider()
        val gate = CompletableDeferred<Unit>()
        coEvery { connectSession(hostA) } coAnswers {
            gate.await()
            connected(session("new-a", hostA))
        }
        val leaving = launch { provider.sessionFor(hostA) }
        val staying = async { provider.sessionFor(hostA) }
        runCurrent()

        leaving.cancelAndJoin()
        gate.complete(Unit)

        assertEquals(SshResult.Success("new-a"), staying.await())
        advanceUntilIdle()
        assertEquals(1, held("new-a"), "the waiter that stayed owns the only reference")
    }

    @Test
    fun `nothing is held when the connection fails`() = runTest {
        val provider = newProvider()
        coEvery { connectSession(hostA) } returns SshResult.Error(SshErrorCode.HOST_UNREACHABLE, "Hote injoignable")

        provider.sessionFor(hostA)

        assertTrue(references.isEmpty())
        verify(exactly = 0) { sessionManager.release(any()) }
    }

    // ── Failures of the connection ───────────────────────────────────────────

    @Test
    fun `an unexpected exception becomes an error with a fixed message`() = runTest {
        val provider = newProvider()
        coEvery { connectSession(hostA) } throws IllegalStateException("detail-with-a-secret")

        val result = provider.sessionFor(hostA)

        val failure = result as SshResult.Error
        assertEquals(SshErrorCode.UNKNOWN, failure.code)
        assertEquals("Connexion impossible : erreur inattendue", failure.message)
        assertFalse(failure.message.contains("secret"))
        // The attempt is over: the next request is not stuck on it.
        coEvery { connectSession(hostA) } coAnswers { connected(session("new-a", hostA)) }
        assertEquals(SshResult.Success("new-a"), provider.sessionFor(hostA))
    }

    @Test
    fun `simultaneous requests get the same error when the exception is unexpected`() = runTest {
        val provider = newProvider()
        val gate = CompletableDeferred<Unit>()
        coEvery { connectSession(hostA) } coAnswers {
            gate.await()
            error("detail-with-a-secret")
        }

        val requests = List(3) { async { provider.sessionFor(hostA) } }
        runCurrent()
        gate.complete(Unit)

        val results = requests.map { it.await() }
        results.forEach {
            assertEquals(
                SshResult.Error(SshErrorCode.UNKNOWN, "Connexion impossible : erreur inattendue"),
                it,
            )
            // The very same outcome of the one attempt, not an error built by each request.
            assertSame(results.first(), it)
        }
        coVerify(exactly = 1) { connectSession(hostA) }
    }

    @Test
    fun `a cancellation raised inside the connection while the caller is active is a failure`() = runTest {
        val provider = newProvider()
        coEvery { connectSession(hostA) } throws CancellationException("raised by the connection")

        val result = provider.sessionFor(hostA)

        assertEquals(
            SshResult.Error(SshErrorCode.UNKNOWN, "Connexion impossible : erreur inattendue"),
            result,
        )
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * A successful connection registers its session, as the session manager does, with the one
     * reference of the caller (here, the attempt).
     */
    private fun connected(session: SshSession): SshResult<SshSession> {
        liveSessions.value = liveSessions.value + session
        references[session.id] = 1
        return SshResult.Success(session)
    }

    private fun session(id: String, host: Host, status: SessionStatus = SessionStatus.CONNECTED) =
        SshSession(id = id, host = host, status = status, connectedAt = 1L)

    private fun host(id: String) = Host(
        id = id,
        label = "Server $id",
        hostname = "10.0.0.1",
        username = "admin",
        authType = AuthType.FIDO2,
        credentialId = "cred-$id",
    )
}
