// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.domain.tunnel

import java.net.URI
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.fail

// Non-ASCII, invisible and bidi characters are written as unicode escapes on purpose: this
// file is public, and a literal U+202E would be a Trojan Source warning.

/**
 * Security property of [TunnelBrowserPath.buildBrowserUrl]: whatever path is stored (typed
 * by the user, received from a sync peer or read from an old backup), the authority of the
 * URL stays exactly `127.0.0.1:<port>`, and the URL always parses with java.net.URI, which
 * is what the desktop browser uses to recognize the tunnel's own URL as local. Checked with
 * two independent parsers: the RFC 3986 reference regex (appendix B) and java.net.URI.
 *
 * Failure messages are built lazily: the fuzz loops run tens of thousands of iterations.
 */
class TunnelBrowserPathUrlSecurityTest {

    private val port = 8080
    private val expectedAuthority = "127.0.0.1:$port"
    private val root = "http://$expectedAuthority/"

    // RFC 3986 appendix B: group 2 = scheme, group 4 = authority, group 5 = path.
    private val rfc3986 = Regex("^(([^:/?#]+):)?(//([^/?#]*))?([^?#]*)(\\?([^#]*))?(#(.*))?")

    private fun authorityOf(url: String): String? =
        rfc3986.matchEntire(url)?.groups?.get(4)?.value

    private fun schemeOf(url: String): String? =
        rfc3986.matchEntire(url)?.groups?.get(2)?.value

    private fun <T> verifyEq(expected: T, actual: T, what: String, context: () -> String) {
        if (expected != actual) fail("$what: expected <$expected> but was <$actual> (${context()})")
    }

    private fun hex(text: String): String = text.map { it.code.toString(16) }.toString()

    /** Asserts scheme, authority, host, port and absence of user info with both parsers. */
    private fun assertLoopbackAuthority(url: String, context: () -> String) {
        verifyEq("http", schemeOf(url), "scheme", context)
        verifyEq(expectedAuthority, authorityOf(url), "authority", context)
        if (!url.startsWith("http://$expectedAuthority/")) fail("prefix (${context()})")

        // No carve-out: every URL built by buildBrowserUrl must parse, including those with
        // a fragment, a repeated question mark or a double slash.
        val uri = try {
            URI(url)
        } catch (e: java.net.URISyntaxException) {
            fail("java.net.URI refused the URL (${context()}): ${e.message}")
        }
        verifyEq("http", uri.scheme, "uri scheme", context)
        verifyEq("127.0.0.1", uri.host, "uri host", context)
        verifyEq(port, uri.port, "uri port", context)
        verifyEq(null, uri.userInfo, "uri userInfo", context)
        verifyEq(expectedAuthority, uri.rawAuthority, "uri authority", context)
    }

    @Test
    fun `accepted hostile looking paths never change the authority`() {
        val accepted = listOf(
            "/@evil.com",
            "//evil.com",
            "///evil.com",
            "//@evil.com",
            "/..;/x",
            "/../../etc/passwd",
            "/a#b",
            "/a#b@evil.com",
            "/a?b?c",
            "/a#b?c",
            "/a#b/c",
            "/%2F",
            "/%2F%2Fevil.com",
            "/%40evil.com",
            "/:80",
            "/?@evil.com",
            "/?url=http://evil.com",
            "/#@evil.com",
            "/http://evil.com",
            "/;@evil.com",
            "/a@b:c@d",
            "/.",
            "/..",
            "/...",
        )
        for (path in accepted) {
            assertNull(TunnelBrowserPath.validate(path), "should be accepted: $path")
            val url = TunnelBrowserPath.buildBrowserUrl(port, path)
            assertEquals("http://$expectedAuthority$path", url)
            assertLoopbackAuthority(url) { path }
        }
    }

    @Test
    fun `user input without a leading slash cannot become a host or user info`() {
        // A user typing these gets a path prefixed with a slash, never a different host.
        for (input in listOf("@evil.com", "evil.com", "evil.com/x", ":80", "http://evil.com", "user:pw@evil.com")) {
            val url = TunnelBrowserPath.buildBrowserUrl(port, TunnelBrowserPath.normalize(input))
            assertEquals("http://$expectedAuthority/$input", url)
            assertLoopbackAuthority(url) { input }
        }
    }

    @Test
    fun `rejected paths fall back to the root and keep the authority`() {
        val rejected = listOf(
            "/\\evil.com",
            "\\\\evil.com",
            "/\\\\evil.com",
            "/ a",
            "/a b",
            "/\u00e9",
            "/%zz",
            "/%",
            "/a#b#c",
            "/##",
            "/\t/evil.com",
            "/a\n/evil.com",
            "/a\r\nHost: evil.com",
            "/\u0000",
            "/\uff0f\uff0fevil.com",
            "/\u202e",
            "/<script>alert(1)</script>",
            "/\"onload=\"x",
            "/" + "a".repeat(512),
        )
        for (path in rejected) {
            val normalized = TunnelBrowserPath.normalize(path)
            assertNotNull(TunnelBrowserPath.validate(normalized), "should be rejected: ${hex(path)}")
            val url = TunnelBrowserPath.buildBrowserUrl(port, path)
            assertEquals(root, url, "fallback expected for ${hex(path)}")
            assertLoopbackAuthority(url) { hex(path) }
        }
    }

    @Test
    fun `513 characters are rejected and 512 are kept`() {
        assertEquals(root, TunnelBrowserPath.buildBrowserUrl(port, "/" + "a".repeat(512)))
        val atLimit = "/" + "a".repeat(511)
        assertEquals("http://$expectedAuthority$atLimit", TunnelBrowserPath.buildBrowserUrl(port, atLimit))
    }

    @Test
    fun `random hostile input never changes the authority and always yields printable ASCII`() {
        val pool = "/ab09-._~!\$&'()*+,;=:@?#%%%fFgG@@//:: ##\t\n\r\\\"<>^`{|}[]" +
            "\u00e9\u3042\u0000\u007f\u00a0\uff0f\u2028"
        val random = Random(8080)
        repeat(50_000) {
            val raw = buildString { repeat(random.nextInt(0, 30)) { append(pool[random.nextInt(pool.length)]) } }
            val url = TunnelBrowserPath.buildBrowserUrl(port, raw)

            assertLoopbackAuthority(url) { hex(raw) }
            if (!url.all { it.code in 0x21..0x7e }) fail("non printable ASCII in the URL for ${hex(raw)}")
            if ('\\' in url) fail("backslash in the URL for ${hex(raw)}")
            // The path of the built URL is either the normalized input or the root, nothing else.
            val normalized = TunnelBrowserPath.normalize(raw)
            val expected = if (TunnelBrowserPath.validate(normalized) == null) normalized else "/"
            verifyEq("http://$expectedAuthority$expected", url, "built url") { hex(raw) }
        }
    }
}
