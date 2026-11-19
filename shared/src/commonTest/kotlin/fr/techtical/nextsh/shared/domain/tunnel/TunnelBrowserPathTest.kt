// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.domain.tunnel

import fr.techtical.nextsh.shared.domain.model.TunnelConfig
import fr.techtical.nextsh.shared.domain.model.TunnelType
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

// Non-ASCII, invisible and bidi characters are written as unicode escapes on purpose: this
// file is public, and a literal U+202E would be a Trojan Source warning.
class TunnelBrowserPathTest {

    private fun hex(path: String): String = path.map { it.code.toString(16) }.toString()

    // --- normalize -------------------------------------------------------------

    @Test
    fun `normalize maps empty and blank input to the root`() {
        assertEquals("/", TunnelBrowserPath.normalize(""))
        assertEquals("/", TunnelBrowserPath.normalize("   "))
        assertEquals("/", TunnelBrowserPath.normalize("\t\n \r\n"))
    }

    @Test
    fun `normalize keeps a path that already starts with a slash`() {
        assertEquals("/", TunnelBrowserPath.normalize("/"))
        assertEquals("/admin", TunnelBrowserPath.normalize("/admin"))
        assertEquals("/api/v1/health", TunnelBrowserPath.normalize("/api/v1/health"))
        assertEquals("/a/b?c=d#e", TunnelBrowserPath.normalize("/a/b?c=d#e"))
    }

    @Test
    fun `normalize prepends the missing leading slash`() {
        assertEquals("/admin", TunnelBrowserPath.normalize("admin"))
        assertEquals("/api/v1/health", TunnelBrowserPath.normalize("api/v1/health"))
        assertEquals("/?x=1", TunnelBrowserPath.normalize("?x=1"))
        assertEquals("/#top", TunnelBrowserPath.normalize("#top"))
    }

    @Test
    fun `normalize trims surrounding whitespace only`() {
        assertEquals("/admin", TunnelBrowserPath.normalize("  /admin \n"))
        assertEquals("/admin", TunnelBrowserPath.normalize("\tadmin\t"))
        // Inner whitespace is kept so that validate can reject it instead of hiding it.
        assertEquals("/a b", TunnelBrowserPath.normalize("/a b"))
    }

    @Test
    fun `normalize does not rewrite a double slash or other path tricks`() {
        assertEquals("//evil.com", TunnelBrowserPath.normalize("//evil.com"))
        assertEquals("/@evil.com", TunnelBrowserPath.normalize("@evil.com"))
        assertEquals("/\\evil.com", TunnelBrowserPath.normalize("\\evil.com"))
    }

    @Test
    fun `normalize is idempotent`() {
        val inputs = listOf(
            "", " ", "/", "admin", "/admin", " admin ", "?x=1", "#f", "//x", "a b", "\\x",
            "\u00e9", "/\u00e9", "\u00a0", "\u00a0admin\u00a0", "%zz", "x".repeat(600),
        )
        for (input in inputs) {
            val once = TunnelBrowserPath.normalize(input)
            assertEquals(once, TunnelBrowserPath.normalize(once), "not idempotent for ${hex(input)}")
            assertTrue(once.startsWith("/"), "no leading slash for ${hex(input)}")
        }
    }

    // --- validate: accepted ----------------------------------------------------

    @Test
    fun `validate accepts ordinary paths`() {
        val valid = listOf(
            "/",
            "/admin",
            "/admin/",
            "/api/v1/health",
            "/index.html",
            "/a-b_c.d~e",
            "/?x=1&y=2",
            "/#frag",
            "/p?q=a%20b#top",
            "/app/#/dashboard",
        )
        for (path in valid) {
            assertNull(TunnelBrowserPath.validate(path), "should be valid: $path")
        }
    }

    @Test
    fun `validate accepts a question mark repeated in the query and one hash with query like text`() {
        // RFC 3986: "?" is legal inside a query and inside a fragment, only a second "#" is not.
        val valid = listOf("/a?b?c", "/a??", "/a#b?c", "/a#b/c", "/a#b:c@d", "/a?x=1#y=2", "/?#", "/#?")
        for (path in valid) {
            assertNull(TunnelBrowserPath.validate(path), "should be valid: $path")
        }
    }

    @Test
    fun `validate accepts every sub delim and the characters colon at slash question hash`() {
        assertNull(TunnelBrowserPath.validate("/!\$&'()*+,;="))
        assertNull(TunnelBrowserPath.validate("/:@/?#"))
        assertNull(TunnelBrowserPath.validate("/@user"))
        assertNull(TunnelBrowserPath.validate("/a:b"))
        assertNull(TunnelBrowserPath.validate("/a;b=c"))
    }

    @Test
    fun `validate accepts well formed percent encoding`() {
        assertNull(TunnelBrowserPath.validate("/%2F"))
        assertNull(TunnelBrowserPath.validate("/%2f"))
        assertNull(TunnelBrowserPath.validate("/%C3%A9"))
        assertNull(TunnelBrowserPath.validate("/%e9"))
        assertNull(TunnelBrowserPath.validate("/%00"))
        assertNull(TunnelBrowserPath.validate("/a%20b%20c"))
        assertNull(TunnelBrowserPath.validate("/%41%42%43"))
    }

    // --- validate: length ------------------------------------------------------

    @Test
    fun `validate accepts exactly 512 characters and rejects 513`() {
        val atLimit = "/" + "a".repeat(TunnelBrowserPath.MAX_LENGTH - 1)
        assertEquals(512, atLimit.length)
        assertNull(TunnelBrowserPath.validate(atLimit))

        val overLimit = "/" + "a".repeat(TunnelBrowserPath.MAX_LENGTH)
        assertEquals(513, overLimit.length)
        assertEquals(TunnelBrowserPathError.TOO_LONG, TunnelBrowserPath.validate(overLimit))
    }

    @Test
    fun `validate reports the length before anything else`() {
        assertEquals(TunnelBrowserPathError.TOO_LONG, TunnelBrowserPath.validate("/" + " ".repeat(600)))
        assertEquals(TunnelBrowserPathError.TOO_LONG, TunnelBrowserPath.validate("a".repeat(600)))
        assertEquals(TunnelBrowserPathError.TOO_LONG, TunnelBrowserPath.validate("/" + "#".repeat(600)))
    }

    // --- validate: missing leading slash ---------------------------------------

    @Test
    fun `validate reports a missing leading slash`() {
        // Defense in depth: a null result must always mean "safe to append after the authority".
        for (path in listOf("", "admin", "@evil.com", ":80", "?x=1", "#f", "evil.com/x", "\\evil.com", " /a")) {
            assertEquals(
                TunnelBrowserPathError.MISSING_LEADING_SLASH,
                TunnelBrowserPath.validate(path),
                "should be rejected: <$path>",
            )
        }
    }

    // --- validate: rejected characters -----------------------------------------

    @Test
    fun `validate rejects spaces control characters and non ASCII`() {
        val invalid = listOf(
            "/ a", "/a b", "/a ", "/\ta", "/a\nb", "/a\rb", "/\u0000", "/\u0001", "/\u001f", "/\u007f",
            "/\u0085", "/\u00e9", "/\u65e5\u672c", "/\u00a0", "/\uff0f", "/\u202e", "/\ud83d\ude00",
            "/\u2028", "/\u200b", "/\u3042",
        )
        for (path in invalid) {
            assertEquals(
                TunnelBrowserPathError.INVALID_CHARACTER,
                TunnelBrowserPath.validate(path),
                "should be rejected: ${hex(path)}",
            )
        }
    }

    @Test
    fun `validate rejects backslash and the characters outside RFC 3986`() {
        val invalid = listOf("\\", "\"", "<", ">", "^", "`", "{", "|", "}", "[", "]")
        for (c in invalid) {
            assertEquals(
                TunnelBrowserPathError.INVALID_CHARACTER,
                TunnelBrowserPath.validate("/a${c}b"),
                "should be rejected: $c",
            )
        }
        assertEquals(TunnelBrowserPathError.INVALID_CHARACTER, TunnelBrowserPath.validate("/\\evil.com"))
    }

    @Test
    fun `validate classifies every single character like the RFC 3986 reference set`() {
        // Independent oracle: written out here, not derived from the implementation.
        val allowed = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789" +
            "-._~" + "!\$&'()*+,;=" + ":@/?#"
        for (code in 0..0xFFFF) {
            val c = code.toChar()
            if (c == '%') continue
            val actual = TunnelBrowserPath.validate("/$c")
            val expected = if (c in allowed) null else TunnelBrowserPathError.INVALID_CHARACTER
            if (actual != expected) fail("U+${code.toString(16)}: expected $expected but was $actual")
        }
    }

    // --- validate: percent encoding --------------------------------------------

    @Test
    fun `validate rejects a percent sign without two hex digits`() {
        val invalid = listOf("/%", "/%z", "/%zz", "/%2", "/%2g", "/%g2", "/a%", "/a%2", "/%%20", "/%e", "/% 20")
        for (path in invalid) {
            assertEquals(
                TunnelBrowserPathError.INVALID_PERCENT_ENCODING,
                TunnelBrowserPath.validate(path),
                "should be rejected: $path",
            )
        }
    }

    // --- validate: fragment marker ---------------------------------------------

    @Test
    fun `validate rejects a second hash because java net URI refuses it`() {
        val invalid = listOf("/a#b#c", "/##", "/#?#", "/a#b?c#d", "/#/#", "/a#b#c#d", "/#%41#")
        for (path in invalid) {
            assertEquals(
                TunnelBrowserPathError.MULTIPLE_FRAGMENT_MARKERS,
                TunnelBrowserPath.validate(path),
                "should be rejected: $path",
            )
        }
    }

    // --- validate: error ordering ----------------------------------------------

    @Test
    fun `validate reports the first problem from left to right`() {
        assertEquals(TunnelBrowserPathError.INVALID_CHARACTER, TunnelBrowserPath.validate("/ %zz"))
        assertEquals(TunnelBrowserPathError.INVALID_PERCENT_ENCODING, TunnelBrowserPath.validate("/%zz "))
        assertEquals(TunnelBrowserPathError.INVALID_CHARACTER, TunnelBrowserPath.validate("/a#b #c"))
        assertEquals(TunnelBrowserPathError.MULTIPLE_FRAGMENT_MARKERS, TunnelBrowserPath.validate("/a#b#c "))
        assertEquals(TunnelBrowserPathError.MULTIPLE_FRAGMENT_MARKERS, TunnelBrowserPath.validate("/a#b#%zz"))
        assertEquals(TunnelBrowserPathError.INVALID_PERCENT_ENCODING, TunnelBrowserPath.validate("/%zz#a#b"))
    }

    @Test
    fun `every error value can be produced`() {
        val produced = listOf("/" + "a".repeat(512), "", "/ ", "/%", "/#a#b")
            .map { TunnelBrowserPath.validate(it) }
            .toSet()

        assertEquals(TunnelBrowserPathError.values().toSet(), produced)
    }

    @Test
    fun `validate agrees with an independent regex on random strings`() {
        // Differential test against a regex written from RFC 3986 (path, query, fragment),
        // plus the rule that at most one hash is accepted.
        val oracle = Regex("(?:[A-Za-z0-9\\-._~!\$&'()*+,;=:@/?#]|%[0-9A-Fa-f]{2})*")
        val pool = "/ab09-._~!\$&'()*+,;=:@?#%%%fFgG## \t\n\r\\\"<>^`{|}[]\u00e9\u3042\u0000\u007f\u00a0"
        val random = Random(20261009)
        repeat(20_000) {
            val body = buildString { repeat(random.nextInt(0, 24)) { append(pool[random.nextInt(pool.length)]) } }
            val path = if (random.nextBoolean()) "/$body" else body
            val expectedValid = path.startsWith("/") && path.length <= 512 &&
                oracle.matches(path) && path.count { it == '#' } <= 1
            val actualValid = TunnelBrowserPath.validate(path) == null
            if (expectedValid != actualValid) fail("disagreement for ${hex(path)}: expected valid=$expectedValid")
        }
    }

    // --- buildBrowserUrl -------------------------------------------------------

    @Test
    fun `buildBrowserUrl appends a valid path to the loopback authority`() {
        assertEquals("http://127.0.0.1:8080/", TunnelBrowserPath.buildBrowserUrl(8080, "/"))
        assertEquals("http://127.0.0.1:8080/admin", TunnelBrowserPath.buildBrowserUrl(8080, "/admin"))
        assertEquals(
            "http://127.0.0.1:3000/api/v1/health",
            TunnelBrowserPath.buildBrowserUrl(3000, "/api/v1/health"),
        )
        assertEquals(
            "http://127.0.0.1:9090/app/?x=1&y=2#/home",
            TunnelBrowserPath.buildBrowserUrl(9090, "/app/?x=1&y=2#/home"),
        )
        assertEquals("http://127.0.0.1:1/", TunnelBrowserPath.buildBrowserUrl(1, "/"))
        assertEquals("http://127.0.0.1:65535/x", TunnelBrowserPath.buildBrowserUrl(65535, "/x"))
    }

    @Test
    fun `buildBrowserUrl normalizes a stored value that lost its slash or has stray spaces`() {
        assertEquals("http://127.0.0.1:8080/", TunnelBrowserPath.buildBrowserUrl(8080, ""))
        assertEquals("http://127.0.0.1:8080/", TunnelBrowserPath.buildBrowserUrl(8080, "   "))
        assertEquals("http://127.0.0.1:8080/admin", TunnelBrowserPath.buildBrowserUrl(8080, "admin"))
        assertEquals("http://127.0.0.1:8080/admin", TunnelBrowserPath.buildBrowserUrl(8080, " /admin "))
        assertEquals("http://127.0.0.1:8080/?x=1", TunnelBrowserPath.buildBrowserUrl(8080, "?x=1"))
    }

    @Test
    fun `buildBrowserUrl falls back to the root for an invalid stored value`() {
        val root = "http://127.0.0.1:8080/"
        val invalid = listOf(
            "/ a", "/a b", "/\\evil.com", "\\\\evil.com", "/\u00e9", "/%zz", "/%", "/a\nb", "/a\tb",
            "/\u0000", "/<script>", "/\"", "/a#b#c", "/##", "/" + "a".repeat(512),
        )
        for (path in invalid) {
            assertEquals(root, TunnelBrowserPath.buildBrowserUrl(8080, path), "no fallback for ${hex(path)}")
        }
    }

    @Test
    fun `buildBrowserUrl never throws on an out of range port and keeps the digits verbatim`() {
        // The range check (1..65535) belongs to the caller; the URL is just unloadable.
        assertEquals("http://127.0.0.1:0/x", TunnelBrowserPath.buildBrowserUrl(0, "/x"))
        assertEquals("http://127.0.0.1:-1/x", TunnelBrowserPath.buildBrowserUrl(-1, "/x"))
        assertEquals("http://127.0.0.1:65536/x", TunnelBrowserPath.buildBrowserUrl(65536, "/x"))
        for (port in listOf(Int.MIN_VALUE, Int.MAX_VALUE)) {
            val url = TunnelBrowserPath.buildBrowserUrl(port, "/x")
            assertEquals("http://127.0.0.1:$port/x", url)
        }
    }

    // --- model default ---------------------------------------------------------

    @Test
    fun `TunnelConfig defaults to the root path`() {
        val tunnel = TunnelConfig(
            id = "t1",
            label = "Web",
            hostId = "h1",
            type = TunnelType.LOCAL_FORWARD,
            localPort = 8080,
            remoteHost = "localhost",
            remotePort = 80,
        )
        assertEquals(TunnelBrowserPath.DEFAULT, tunnel.browserPath)
        assertEquals("/", tunnel.browserPath)
        assertNull(TunnelBrowserPath.validate(tunnel.browserPath))
    }
}
