// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.util

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Secret forms wrapped in random context: a prefix and a suffix drawn from atoms seen in
 * real log lines. Whatever the context, the line must be dropped by [LogScrubber.clean]
 * and [LogScrubber.scrub] alone must not print the secret.
 */
class LogScrubberPropertyTest {

    private val secret = "hunter2"

    private val forms = listOf(
        "password=$secret", "password = $secret", "password=    $secret", "--password=$secret",
        "\"password\":\"$secret\"", "\"password\": \"$secret\"", "'password': '$secret'",
        "{\"auth\":{\"password\":\"$secret\"}}", "\\\"password\\\":\\\"$secret\\\"",
        "password: $secret", "token: $secret", "db_password: $secret", "Authorization: Bearer $secret",
        "X-API-Key: $secret", "cookie=SID=$secret", "passphrase='$secret'", "secret={\"a\":\"$secret\"}",
        "apiKey=$secret", "password=\n\n$secret", "x-auth-token=$secret", "masterKey=$secret",
    )

    private val prefixes = listOf(
        "", " ", "a=", "config=", "msg=\"", "note=\"", "(", "{", "[", "\n", "\\n", "\\t", "\t",
        "label=Prod DB ", "id=1a2b3c4d ", "x,", "&", ";", "user=root ", "E/Tag: ", "{\"data\":[",
        "params=foo=bar&", "Host(", "message=Connect failed ",
    )

    private val suffixes = listOf(
        "", " ", ",", ")", "\"", "}", "]", " tail", "\n", "&x=1", ";", " (IOException)", ",\"n\":1}",
        " user=root", "'",
    )

    @Test
    fun `a secret form is caught whatever the surrounding context`() {
        val random = Random(20261012)
        repeat(4_000) {
            val input = prefixes.random(random) + forms.random(random) + suffixes.random(random)
            assertTrue(LogScrubber.containsSecret(input), "not detected: $input")
            assertEquals(LogScrubber.REDACTED_LINE, LogScrubber.clean(input), input)
            val scrubbed = LogScrubber.scrub(input)
            assertFalse(scrubbed.contains(secret), "scrub leaked [$input] as [$scrubbed]")
        }
    }

    @Test
    fun `every prefix and suffix around every form, exhaustively`() {
        val failures = mutableListOf<String>()
        for (prefix in prefixes) for (form in forms) for (suffix in suffixes) {
            val input = prefix + form + suffix
            if (!LogScrubber.containsSecret(input)) failures += "not detected: [$input]"
            val scrubbed = LogScrubber.scrub(input)
            if (scrubbed.contains(secret)) failures += "scrub leaked: [$input] as [$scrubbed]"
        }
        assertTrue(failures.isEmpty(), "${failures.size} failures:\n" + failures.take(40).joinToString("\n"))
    }
}
