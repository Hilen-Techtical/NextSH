// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.util

import timber.log.Timber
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** What the Android actual hands to the Timber trees planted by the app. */
class LoggerAndroidTest {

    private data class Record(val tag: String?, val message: String, val throwable: Throwable?)

    private val records = mutableListOf<Record>()

    private val tree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            records += Record(tag, message, t)
        }
    }

    @BeforeTest
    fun setUp() = Timber.plant(tree)

    @AfterTest
    fun tearDown() = Timber.uproot(tree)

    @Test
    fun `messages and tags are scrubbed before reaching the tree`() {
        Logger.w("Ssh\nE/Fake", "connect 10.0.0.5 failed")
        val record = records.single()
        assertEquals("Ssh\\nE/Fake", record.tag)
        assertEquals("connect <ip> failed", record.message)
    }

    @Test
    fun `a throwable is forwarded with a clean message`() {
        val boom = IllegalStateException("x")
        Logger.e("Sync", "push failed", boom)
        val record = records.single()
        assertSame(boom, record.throwable)
        assertTrue(record.message.startsWith("push failed"), record.message)
    }

    @Test
    fun `a throwable is not forwarded when its line is dropped as a secret`() {
        Logger.e("Vault", "unlock password=hunter2", IllegalStateException("hunter2 rejected"))
        val record = records.single()
        assertNull(record.throwable)
        assertEquals("${LogScrubber.REDACTED_LINE} [IllegalStateException]", record.message)
    }
}
