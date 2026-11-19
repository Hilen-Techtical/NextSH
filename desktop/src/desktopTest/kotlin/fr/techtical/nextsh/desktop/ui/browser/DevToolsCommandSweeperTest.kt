// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.ui.browser

import java.util.Collections
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tests of [DevToolsCommandSweeper] with fake DevTools clients: no Chromium involved. */
class DevToolsCommandSweeperTest {

    // Same shape as org.cef.browser.CefDevToolsClient: a private final synchronized map.
    private class FakeClient {
        private val queuedCommands_: MutableMap<Int, CompletableFuture<String>> =
            Collections.synchronizedMap(HashMap())

        fun add(id: Int, done: Boolean): CompletableFuture<String> {
            val f = CompletableFuture<String>()
            if (done) f.complete("{}")
            queuedCommands_[id] = f
            return f
        }

        fun size() = queuedCommands_.size
        fun has(id: Int) = queuedCommands_.containsKey(id)
    }

    private class ClientWithoutField

    private class ClientWithWrongType {
        @Suppress("unused")
        private val queuedCommands_: String = "not a map"
    }

    private class ClientWithNullField {
        @Suppress("unused")
        private val queuedCommands_: MutableMap<Int, Any>? = null
    }

    private var now = 10_000L

    private fun sweeperFor(client: FakeClient) = assertNotNull(DevToolsCommandSweeper.forClient(client) { now })

    // ── The guard ──

    @Test
    fun `a client with the expected field gets a sweeper`() {
        assertNotNull(DevToolsCommandSweeper.forClient(FakeClient()))
    }

    @Test
    fun `a client without the field goes without a sweeper`() {
        assertNull(DevToolsCommandSweeper.forClient(ClientWithoutField()))
    }

    @Test
    fun `a field of another type goes without a sweeper`() {
        assertNull(DevToolsCommandSweeper.forClient(ClientWithWrongType()))
    }

    @Test
    fun `a null field goes without a sweeper`() {
        assertNull(DevToolsCommandSweeper.forClient(ClientWithNullField()))
    }

    @Test
    fun `forMap only accepts a map`() {
        assertNull(DevToolsCommandSweeper.forMap(null))
        assertNull(DevToolsCommandSweeper.forMap("map"))
        assertNull(DevToolsCommandSweeper.forMap(listOf(1)))
        assertNotNull(DevToolsCommandSweeper.forMap(HashMap<Int, Any>()))
    }

    @Test
    fun `the real JCEF client has the field the sweeper expects and it can be opened`() {
        // Guards the assumption against a JCEF update that renames, retypes or seals the field.
        val field = org.cef.browser.CefDevToolsClient::class.java.getDeclaredField(DevToolsCommandSweeper.FIELD_NAME)
        assertTrue(Map::class.java.isAssignableFrom(field.type), "field type ${field.type}")
        assertTrue(field.trySetAccessible(), "the module system must let the sweeper read the field")
    }

    // ── Logging: fixed text, once ──

    private fun captureStderr(block: () -> Unit): String {
        val captured = java.io.ByteArrayOutputStream()
        val original = System.err
        System.setErr(java.io.PrintStream(captured, true, "UTF-8"))
        try {
            block()
        } finally {
            System.setErr(original)
        }
        return captured.toString("UTF-8")
    }

    @Test
    fun `an unreachable queue is logged once with a fixed text`() {
        DevToolsCommandSweeper.resetLogForTest()

        val log = captureStderr {
            DevToolsCommandSweeper.forClient(ClientWithoutField())
            DevToolsCommandSweeper.forClient(ClientWithWrongType())
            DevToolsCommandSweeper.forClient(ClientWithNullField())
        }

        assertEquals(1, Regex("not reachable").findAll(log).count(), log)
        assertTrue(log.contains("devtools command queue not reachable, finished commands are not swept"), log)
        assertFalse(log.contains("Exception") || log.contains("NoSuchField"), "no exception text: $log")
    }

    @Test
    fun `a reachable queue logs nothing`() {
        DevToolsCommandSweeper.resetLogForTest()

        val log = captureStderr { DevToolsCommandSweeper.forClient(FakeClient()) }

        assertEquals("", log)
    }

    @Test
    fun `disabling logs a fixed text once and never the exception message`() {
        val map = object : HashMap<Any?, Any?>() {
            override val entries: MutableSet<MutableMap.MutableEntry<Any?, Any?>>
                get() = throw IllegalStateException("secret-looking message")
        }
        val sweeper = assertNotNull(DevToolsCommandSweeper.forMap(map) { now })

        val log = captureStderr {
            sweeper.sweep(now)
            sweeper.sweep(now)
            sweeper.maybeSweep()
        }

        assertEquals(1, Regex("sweeping disabled").findAll(log).count(), log)
        assertFalse(log.contains("secret-looking message"), log)
    }

    @Test
    fun `entries of another type are left alone`() {
        val map = Collections.synchronizedMap(HashMap<Any?, Any?>())
        map[1] = "not a future"
        map[2] = null
        val sweeper = assertNotNull(DevToolsCommandSweeper.forMap(map) { now })

        sweeper.sweep(now)
        now += DevToolsCommandSweeper.MIN_AGE_MS
        val removed = sweeper.sweep(now)

        assertEquals(0, removed)
        assertEquals(2, map.size)
        assertFalse(sweeper.isDisabled)
    }

    // ── Sweeping ──

    @Test
    fun `a done entry is removed only after it was seen done for the minimum age`() {
        val client = FakeClient().also { it.add(1, done = true) }
        val sweeper = sweeperFor(client)

        assertEquals(0, sweeper.sweep(now), "first sighting only marks it")
        assertTrue(client.has(1))
        now += DevToolsCommandSweeper.MIN_AGE_MS - 1
        assertEquals(0, sweeper.sweep(now))
        assertTrue(client.has(1))
        now += 1
        assertEquals(1, sweeper.sweep(now))
        assertFalse(client.has(1))
    }

    @Test
    fun `a pending entry is never removed`() {
        val client = FakeClient().also { it.add(1, done = false) }
        val sweeper = sweeperFor(client)

        repeat(5) {
            sweeper.sweep(now)
            now += 60_000
        }

        assertTrue(client.has(1), "a command that never completes stays")
    }

    @Test
    fun `an entry that completes later starts its own age`() {
        val client = FakeClient()
        val f = client.add(1, done = false)
        val sweeper = sweeperFor(client)
        sweeper.sweep(now)
        now += 60_000
        sweeper.sweep(now)

        f.complete("{}")
        assertEquals(0, sweeper.sweep(now), "just completed: young")
        assertTrue(client.has(1))
        now += DevToolsCommandSweeper.MIN_AGE_MS
        assertEquals(1, sweeper.sweep(now))
    }

    @Test
    fun `only the old done entries go in a mixed queue`() {
        val client = FakeClient()
        client.add(1, done = true)
        client.add(2, done = false)
        val sweeper = sweeperFor(client)
        sweeper.sweep(now)
        now += DevToolsCommandSweeper.MIN_AGE_MS
        client.add(3, done = true)

        assertEquals(1, sweeper.sweep(now))

        assertFalse(client.has(1))
        assertTrue(client.has(2))
        assertTrue(client.has(3), "too young")
    }

    @Test
    fun `maybeSweep is rate limited`() {
        val client = FakeClient().also { it.add(1, done = true) }
        val sweeper = sweeperFor(client)

        sweeper.maybeSweep() // marks entry 1 at t0
        now += DevToolsCommandSweeper.MIN_AGE_MS
        client.add(2, done = true)
        sweeper.maybeSweep() // interval passed: removes 1, marks 2
        assertFalse(client.has(1))
        assertTrue(client.has(2))

        now += 10 // inside the interval
        sweeper.maybeSweep()
        now += DevToolsCommandSweeper.MIN_AGE_MS - 20
        sweeper.maybeSweep()
        assertTrue(client.has(2), "the sweeps inside the interval were skipped")
    }

    @Test
    fun `a long browsing session keeps the queue bounded`() {
        val client = FakeClient()
        val sweeper = sweeperFor(client)
        var id = 0

        repeat(200) { second ->
            repeat(50) { client.add(++id, done = true) } // 50 finished commands per second
            now += 1_000
            sweeper.maybeSweep()
            if (second > 10) assertTrue(client.size() <= 50 * 4, "size ${client.size()} at second $second")
        }

        assertTrue(client.size() <= 200, "bounded, not ${id} entries")
    }

    @Test
    fun `entries JCEF removed itself are forgotten`() {
        val map = Collections.synchronizedMap(HashMap<Any?, Any?>())
        val sweeper = assertNotNull(DevToolsCommandSweeper.forMap(map) { now })
        map[1] = CompletableFuture.completedFuture("a")
        sweeper.sweep(now) // entry 1 is seen done
        map.clear() // JCEF clears the map when the browser closes
        sweeper.sweep(now) // its tracking is dropped with it

        map[1] = CompletableFuture.completedFuture("b")
        now += DevToolsCommandSweeper.MIN_AGE_MS

        assertEquals(0, sweeper.sweep(now), "the new entry 1 starts a new age")
        assertTrue(map.containsKey(1))
    }
    @Test
    fun `a failure while sweeping disables the sweeper without throwing`() {
        val map = object : HashMap<Any?, Any?>() {
            override val entries: MutableSet<MutableMap.MutableEntry<Any?, Any?>>
                get() = throw IllegalStateException("broken map")
        }
        val sweeper = assertNotNull(DevToolsCommandSweeper.forMap(map) { now })

        assertEquals(0, sweeper.sweep(now))

        assertTrue(sweeper.isDisabled)
        assertEquals(0, sweeper.sweep(now))
        sweeper.maybeSweep()
    }

    @Test
    fun `sweeping while another thread adds entries is safe`() {
        val client = FakeClient()
        val sweeper = sweeperFor(client)
        val writer = Thread {
            repeat(20_000) { client.add(it, done = it % 2 == 0) }
        }
        writer.start()
        repeat(200) {
            now += DevToolsCommandSweeper.MIN_AGE_MS
            sweeper.sweep(now)
        }
        writer.join(10_000)

        assertFalse(sweeper.isDisabled)
        assertFalse(writer.isAlive)
    }
}