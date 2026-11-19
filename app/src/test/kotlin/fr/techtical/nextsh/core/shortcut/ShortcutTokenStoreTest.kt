// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.shortcut

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The secret of the launcher shortcuts: made once per install and then kept (the shortcuts pinned
 * to the home screen hold it), and compared without telling how much of a guess was right.
 */
class ShortcutTokenStoreTest {

    private val prefs = FakeSharedPreferences()
    private val store = ShortcutTokenStore(prefs, SecureRandom())

    @Test
    fun `the token is made once and then reused`() {
        val first = store.token()
        val second = store.token()

        assertEquals(first, second)
        assertEquals(1, prefs.writes, "written once")
    }

    @Test
    fun `calls that race for the first token make one, and all get it`() {
        val callers = 16
        val pool = Executors.newFixedThreadPool(callers)
        val go = CountDownLatch(1)
        try {
            // The publication of the shortcuts (IO thread) and the activity (main thread) can ask at once.
            val results = (1..callers).map {
                pool.submit(Callable { go.await(); store.token() })
            }
            go.countDown()

            val tokens = results.map { it.get(10, TimeUnit.SECONDS) }.toSet()

            assertEquals(1, tokens.size, "every caller got the same token")
            assertEquals(1, prefs.writes, "and one was written")
            assertEquals(tokens.single(), prefs.values["token"])
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `a new instance on the same file finds the token of the install, as after a restart of the app`() {
        val first = store.token()

        val afterRestart = ShortcutTokenStore(prefs, SecureRandom())

        assertEquals(first, afterRestart.token())
        assertEquals(1, prefs.writes, "nothing is made again")
    }

    @Test
    fun `a token is 32 random bytes in URL-safe text, and no two installs share one`() {
        val token = store.token()
        val otherInstall = ShortcutTokenStore(FakeSharedPreferences(), SecureRandom()).token()

        // 32 bytes in Base64 without padding: 43 characters.
        assertEquals(43, token.length)
        assertTrue(token.all { it.isLetterOrDigit() || it == '-' || it == '_' }, "URL-safe text")
        assertNotEquals(token, otherInstall)
    }

    @Test
    fun `a stored value that is not a token is replaced by a real one`() {
        prefs.values["token"] = "not a token"

        val token = store.token()

        assertEquals(43, token.length)
        assertEquals(token, prefs.values["token"], "the new one is kept")
    }

    @Test
    fun `the token of the install matches`() {
        assertTrue(store.matches(store.token()))
    }

    @Test
    fun `a wrong, empty or missing token does not match`() {
        val token = store.token()

        assertFalse(store.matches(null))
        assertFalse(store.matches(""))
        assertFalse(store.matches("forged"))
        assertFalse(store.matches("A".repeat(43)))
        assertFalse(store.matches(token.dropLast(1)), "a prefix of the token")
        assertFalse(store.matches(token + "A"), "the token and more")
    }

    @Test
    fun `text beyond ASCII does not match`() {
        val token = store.token()

        assertFalse(store.matches("é" + token.drop(1)), "same length, one accented letter")
        assertFalse(store.matches(token.dropLast(1) + "€"))
    }

    @Test
    fun `the token of another install does not match`() {
        val otherInstall = ShortcutTokenStore(FakeSharedPreferences(), SecureRandom())

        assertFalse(store.matches(otherInstall.token()))
    }

    @Test
    fun `asking whether a token matches makes the token of an install that has none`() {
        assertFalse(store.matches("forged"))

        assertTrue(store.matches(prefs.values["token"] as String))
    }
}
