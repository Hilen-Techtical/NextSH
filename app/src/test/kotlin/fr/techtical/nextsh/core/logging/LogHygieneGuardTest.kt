// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.logging

import fr.techtical.nextsh.testutil.ProjectFiles
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Source-level guard of the log policy: a log line carries fixed text, operation and state
 * names, counts, sizes, durations, numeric codes, algorithm names and exception CLASS names. It
 * never carries a secret, a user-owned value (label, hostname, username, path, alias,
 * fingerprint, device name, URI, rpId), peer or server text, `e.message`, or a raw id (ids go
 * through `LogId.of`).
 *
 * The Timber trees ([LogTreesTest]) and `LogScrubber` are the safety net, this test is the first
 * line: it reads `app/src/main/kotlin` and fails on a log call (`Timber.x`, `Timber.log`,
 * `Timber.tag(..).x`, `Logger.x`) that
 *
 * 1. reads an exception or result message (`.message`, `.localizedMessage`), a stack trace text or
 *    a `.toString()`;
 * 2. interpolates or passes an object whose text is not under our control: a bare name or chain
 *    that ends with `e`, `ex`, `t`, `err`, `error`, `exception`, `throwable`, `cause`, `result`,
 *    `response` or `it` (`"$e"`, `"${result}"`, `Timber.w("x %s", e)`);
 * 3. puts a sensitive identifier in the message, in its arguments or in the tag (see
 *    [LogCallScanner] `SENSITIVE_TOKENS`: label, host, path, alias, name, url, port, text, ...), or
 *    an identifier that ends with `Id` or `ID` (an entity or device id), unless it sits inside
 *    `LogId.of(...)`;
 * 4. is `Timber.x(variable)`: a message built elsewhere cannot be checked here;
 * 5. is written `Timber.d(TAG, "text")`: Timber takes the first argument as the message, so only the
 *    tag is printed. The call must be `Timber.tag(TAG).d("text")`;
 * 6. hands a throwable to the log, whatever the variable is called (`Timber.w(e, "text")`,
 *    `Timber.log(priority, failure, "text")`, `Logger.e(TAG, "text", e)`): the message of an
 *    exception can carry a host, an address or a path, and a tree added later would receive it
 *    whole. Write the class in the message instead: `Timber.w("text (${errorClass(e)})")`.
 *
 * Wrappers that make a value safe: `LogId.of(...)`, `errorClass(...)`, `safeStackTrace(...)` and
 * `LogScrubber.clean(...)`. Comments are blanked before calls are looked for, so a comment never
 * counts. A justified exception carries the marker `log-guard:allow <reason>` in a comment on the
 * line of the call, or on the line before it (none is expected).
 *
 * The Termux fork gets its own check (`TerminalEmulator.java`, `TerminalSession.java`): the message
 * of every `Logger.log*` call must be made of string literals, integers, known int variables and
 * exception class names. The text it handles comes from the remote host (escape sequence payloads,
 * for example a copied secret in OSC 52).
 *
 * Same compromise as `SyncUpsertGuardTest`: a cheap source check. A source that cannot be found
 * FAILS the test (see [ProjectFiles]).
 */
class LogHygieneGuardTest {

    // ── The real sources ─────────────────────────────────────────────────────────

    @Test
    fun `no log call of the app leaks a message, a user value or a raw id`() {
        val root = ProjectFiles.mainSourceDir()
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        // A guard that scans nothing proves nothing.
        assertTrue(files.size > 100, "expected the whole app source tree, found ${files.size} files under $root")

        val violations = files.flatMap { file ->
            LogCallScanner.scan(file.readText(), file.relativeTo(root).path.replace('\\', '/'))
        }
        assertTrue(
            violations.isEmpty(),
            "Log calls that break the log policy (see the KDoc of ${LogHygieneGuardTest::class.simpleName}):\n" +
                violations.joinToString("\n") { "  $it" },
        )
    }

    @Test
    fun `the call sites are really scanned`() {
        val calls = ProjectFiles.mainSourceDir().walkTopDown().filter { it.isFile && it.extension == "kt" }
            .sumOf { LogCallScanner.countCalls(it.readText()) }
        // 300+ Timber calls today: a scanner that stops matching would pass the test above.
        assertTrue(calls > 200, "the scanner found only $calls log calls")
    }

    @Test
    fun `every log call of the Termux emulator and session is fixed text, ints or class names`() {
        val directory = ProjectFiles.repoDir("terminal/terminal-emulator/src/main/java/com/termux/terminal")
        var scanned = 0
        val violations = listOf("TerminalEmulator.java", "TerminalSession.java").flatMap { name ->
            val file = File(directory, name)
            assertTrue(file.isFile, "$name not found in $directory")
            scanned += TermuxLogScanner.countCalls(file.readText())
            TermuxLogScanner.scan(file.readText(), name)
        }
        assertTrue(scanned >= 15, "the Termux scanner found only $scanned log calls")
        assertTrue(
            violations.isEmpty(),
            "Termux log calls that could carry text sent by the remote host:\n" +
                violations.joinToString("\n") { "  $it" },
        )
    }

    // ── The Kotlin scanner itself ────────────────────────────────────────────────

    private fun scan(code: String) = LogCallScanner.scan(code, "Sample.kt")

    @Test
    fun `scanner flags exception messages`() {
        assertEquals(1, scan("""Timber.w("Auth failed: ${'$'}{e.message}")""").size)
        assertEquals(1, scan("""Timber.w("Auth failed: ${'$'}{e.localizedMessage}")""").size)
        assertEquals(1, scan("""Timber.e("failed: ${'$'}{result.message}")""").size)
        assertEquals(1, scan("""Timber.tag("X").e(e.stackTraceToString())""").size)
        assertEquals(1, scan("""Logger.w(TAG, "failed " + e.message)""").size)
    }

    @Test
    fun `scanner flags interpolated exceptions and results`() {
        assertEquals(1, scan("""Timber.w("failed: ${'$'}e")""").size)
        assertEquals(1, scan("""Timber.w("failed: ${'$'}{e}")""").size)
        assertEquals(1, scan("""Timber.w("failed: ${'$'}{ex}")""").size)
        assertEquals(1, scan("""Timber.w("failed: ${'$'}{result}")""").size)
        assertEquals(1, scan("""Timber.w("failed: ${'$'}response")""").size)
        assertEquals(1, scan("""Timber.w("failed: ${'$'}{sync.cause}")""").size)
        assertEquals(1, scan("""Timber.w("failed: ${'$'}{it}")""").size)
        assertEquals(1, scan("""Timber.w("failed: ${'$'}{outcome?.error}")""").size)
        assertEquals(1, scan("""Timber.w("failed: %s", e)""").size)
        assertEquals(1, scan("""Timber.w("failed: %s", result)""").size)
        assertEquals(1, scan("""Timber.w("failed: " + e)""").size)
        assertEquals(1, scan("""Timber.w("failed: ${'$'}{count.toString()}")""").size)
        assertEquals(1, scan("""Timber.w("failed: %s", count.toString())""").size)
        assertEquals(1, scan("""Timber.w(result)""").size)
        assertEquals(1, scan("""Timber.w(it)""").size)
    }

    @Test
    fun `scanner flags a plain variable as the whole message`() {
        assertEquals(1, scan("""Timber.w(summary)""").size)
        assertEquals(1, scan("""Timber.tag(TAG).i(line)""").size)
        assertEquals(1, scan("""Timber.w(e)""").size)
        assertEquals(1, scan("""Timber.tag(TAG).e(exception)""").size)
    }

    @Test
    fun `scanner flags a throwable handed to the log`() {
        assertEquals(1, scan("""Timber.w(e, "x")""").size)
        assertEquals(1, scan("""Timber.w(e)""").size)
        assertEquals(1, scan("""Timber.tag(TAG).e(exception)""").size)
        assertEquals(1, scan("""Timber.tag(TAG).w(e, "x")""").size)
        // Whatever the variable is called.
        assertEquals(1, scan("""Timber.w(failure, "x")""").size)
        assertEquals(1, scan("""Timber.e(closeError, "x")""").size)
        assertEquals(1, scan("""Timber.w(outcome.cause, "x")""").size)
        assertEquals(1, scan("""Timber.log(priority, e, "x")""").size)
        assertEquals(1, scan("""Timber.log(priority, failure, "x")""").size)
        assertEquals(1, scan("""Logger.e(TAG, "x", e)""").size)
        assertEquals(1, scan("""Logger.e(TAG, "x", failure)""").size)
        assertEquals(1, scan("Timber.w(\n    e,\n    \"x\",\n)").size)
        // Not a bare name: a call, a cast, `!!`, a constructor, a named argument, a single non-literal argument.
        assertEquals(1, scan("""Timber.e(rootCause(e), "x")""").size)
        assertEquals(1, scan("""Timber.w(e as Exception, "x")""").size)
        assertEquals(1, scan("""Timber.w(e!!, "x")""").size)
        assertEquals(1, scan("""Timber.w(IllegalStateException("boom"), "x")""").size)
        assertEquals(1, scan("""Logger.e(TAG, "x", throwable = e)""").size)
        assertEquals(1, scan("""Logger.e(TAG, "x", rootCause(e))""").size)
        assertEquals(1, scan("""Timber.e(state.failure)""").size)
        assertEquals(1, scan("""Timber.tag(TAG).e(state.failure!!)""").size)
        assertEquals(1, scan("""Timber.w(wrap(e))""").size)
        assertEquals(1, scan("Logger.e(TAG, \"x\", e,\n)").size)
        // The class in the message is the accepted form.
        assertEquals(0, scan("""Timber.w("x (${'$'}{errorClass(e)})")""").size)
        assertEquals(0, scan("""Timber.tag(TAG).w("x (${'$'}{errorClass(failure)})")""").size)
        assertEquals(0, scan("""Timber.i("SFTP chmod %o done", permissions)""").size)
        assertEquals(0, scan("""Timber.log(priority, "x")""").size)
        assertEquals(0, scan("""Logger.e(TAG, "x")""").size)
        assertEquals(0, scan("""Timber.w("x",)""").size)
        assertEquals(0, scan("""Timber.tag(TAG).e(LogScrubber.clean(message))""").size)
        assertEquals(0, scan("""Timber.log(priority, errorClass(e))""").size)
        assertEquals(0, scan("""Logger.e(TAG, "x", errorClass(e))""").size)
    }

    @Test
    fun `scanner flags user values and raw ids`() {
        assertEquals(1, scan("""Timber.i("connected ${'$'}{host.label} (${'$'}{host.hostname}:${'$'}{host.port})")""").size)
        assertEquals(1, scan("""Timber.d("SFTP mkdir ${'$'}path")""").size)
        assertEquals(1, scan("""Timber.d("alias ${'$'}alias")""").size)
        assertEquals(1, scan("""Timber.d("fp ${'$'}{result.fingerprint}")""").size)
        assertEquals(1, scan("""Timber.d("device ${'$'}{device.deviceName}")""").size)
        assertEquals(1, scan("""Timber.d("uri ${'$'}localUri")""").size)
        assertEquals(1, scan("""Timber.d("session ${'$'}sessionId")""").size)
        assertEquals(1, scan("""Timber.d("device ${'$'}{device.deviceId}")""").size)
        assertEquals(1, scan("""Timber.d("credential ${'$'}{entry.credentialID}")""").size)
        assertEquals(1, scan("""Timber.d("host %s", host.hostname)""").size)
        assertEquals(1, scan("Timber.d(\"multi\n line ${'$'}{key.label}\")").size)
    }

    @Test
    fun `scanner flags the other user and peer values`() {
        for (token in listOf("name", "title", "command", "url", "port", "localPort", "remoteHost", "sshHost", "bindAddress", "text", "content")) {
            assertEquals(1, scan("""Timber.d("value ${'$'}{item.$token}")""").size, token)
        }
    }

    @Test
    fun `scanner flags the tag passed as the message`() {
        assertEquals(1, scan("""Timber.d(TAG, "doWork: starting")""").size)
        assertEquals(1, scan("""Timber.w("App", "SyncScheduler not started")""").size)
        assertEquals(1, scan("Timber.d(\n    TAG,\n    \"text\",\n)").size)
        assertEquals(1, scan("""Timber.e(LOG_TAG, "text")""").size)
    }

    @Test
    fun `scanner checks Timber log and the tag argument`() {
        assertEquals(1, scan("""Timber.log(priority, "failed ${'$'}{e.message}")""").size)
        assertEquals(1, scan("""Timber.log(priority, e, "failed")""").size)
        assertEquals(1, scan("""Timber.tag(host.label).d("x")""").size)
        assertEquals(1, scan("""Timber.tag("Host-${'$'}{host.hostname}").d("x")""").size)
        assertEquals(0, scan("""Timber.tag(TAG).d("x")""").size)
    }

    @Test
    fun `scanner accepts the safe forms`() {
        val safe = listOf(
            """Timber.tag(TAG).d("doWork: starting")""",
            """Timber.w("failed (${'$'}{errorClass(e)})")""",
            """Timber.i("SSH connected hostId=${'$'}{LogId.of(host.id)}")""",
            """Timber.w("Transfer ${'$'}{LogId.of(request.id)} failed (${'$'}{result.code})")""",
            """Timber.tag(tag).e(LogScrubber.clean(message))""",
            """Timber.e("Backup export failed: %s", e.javaClass.simpleName)""",
            """Timber.w("failed (${'$'}{e::class.java.simpleName})")""",
            """Timber.i("SFTP chmod %o done", permissions)""",
            """Timber.d("WebView JS console [${'$'}{it.messageLevel()}]")""",
            """Timber.i("Permission ${'$'}{if (granted) "accordee" else "refusee"}")""",
            """Timber.d("Dynamic shortcuts updated: %d hosts, %d tunnels", hosts, tunnels)""",
            """Timber.w("failed ${'$'}{result.code} ${'$'}{outcome.status}")""",
            """Timber.plant(DebugLogTree())""",
            """Timber.uprootAll()""",
            """Logger.d(TAG, "Sync pushed ${'$'}count entries")""",
            // Text inside a string literal is not code, whatever it says.
            """Timber.w("the e.message and host.label words are only text here")""",
            // A comment that quotes a bad pattern is not code either.
            "Timber.w(\n    // never e.message here\n    \"fixed\",\n)",
            "/* Timber.d(TAG, \"commented out ${'$'}{e.message}\") */\nval x = 1",
            "// Timber.d(TAG, \"commented out\")\nval x = 1",
            "/**\n * Use Timber.tag(TAG).d(\"x ${'$'}path\") not Timber.d(TAG, \"x\").\n */\nfun f() {}",
        )
        for (sample in safe) {
            assertEquals(emptyList<String>(), scan(sample), sample)
        }
    }

    @Test
    fun `scanner reads past an escaped quote char literal`() {
        // A char literal '\'' used to leave the lexer one quote late and swallow the rest of the call.
        val sample = "Timber.w(\"a ${'$'}{x.indexOf('\\'')} b ${'$'}{e.message}\")"
        assertEquals(1, scan(sample).size, sample)
        assertEquals(0, scan("Timber.w(\"a ${'$'}{x.indexOf('\\'')} b\")").size)
        assertEquals(0, scan("Timber.w(\"a ${'$'}{x.indexOf('\\u0000')} b\")").size)
    }

    @Test
    fun `scanner honours the allow marker`() {
        val sameLine = """Timber.d("file ${'$'}path") // log-guard:allow test fixture path, not user data"""
        assertEquals(0, scan(sameLine).size)
        val lineBefore = "// log-guard:allow justified\nTimber.d(\"file ${'$'}path\")"
        assertEquals(0, scan(lineBefore).size)
        val farBefore = "// log-guard:allow justified\n\n\nTimber.d(\"file ${'$'}path\")"
        assertEquals(1, scan(farBefore).size)
    }

    @Test
    fun `scanner reports the line of the call`() {
        val sample = "fun f() {\n    val x = 1\n    Timber.w(\"bad ${'$'}{e.message}\")\n}\n"
        val violation = scan(sample).single()
        assertTrue(violation.startsWith("Sample.kt:3:"), violation)
    }

    @Test
    fun `scanner counts the log calls`() {
        val sample = """
            Timber.d("a")
            Timber.tag("T").w("b")
            Logger.e(TAG, "c")
            Timber.log(3, "d")
            Timber.plant(x)
            // Timber.d("not counted")
        """.trimIndent()
        assertEquals(4, LogCallScanner.countCalls(sample))
    }

    // ── The Termux scanner itself ────────────────────────────────────────────────

    private fun scanJava(code: String) = TermuxLogScanner.scan(code, "Sample.java")

    @Test
    fun `termux scanner flags text that can come from the remote host`() {
        assertEquals(1, scanJava("""Logger.logWarn(mClient, LOG_TAG, "Unhandled name: '" + trans + "'");""").size)
        assertEquals(1, scanJava("""Logger.logError(mClient, LOG_TAG, "OSC invalid string '" + textParameter + "");""").size)
        assertEquals(1, scanJava("""Logger.logWarn(mClient, LOG_TAG, "Failed: " + e.getMessage());""").size)
        assertEquals(1, scanJava("""logError("Unknown sequence char '" + (char) b + "'");""").size)
        assertEquals(1, scanJava("""Logger.logError(mClient, LOG_TAG, dcs);""").size)
        assertEquals(1, scanJava("Logger.logStackTraceWithMessage(mClient, LOG_TAG, \"Bad \" + part, e);").size)
        assertEquals(1, scanJava("""client.logWarn(LOG_TAG, "x " + part);""").size)
    }

    @Test
    fun `termux scanner accepts literals, ints and class names`() {
        val safe = listOf(
            """Logger.logError(mClient, LOG_TAG, "OSC Manipulate selection, invalid base64 string");""",
            """Logger.logWarn(mClient, LOG_TAG, "Ignoring request, bit=" + externalBit);""",
            """Logger.logError(mClient, LOG_TAG, "(ignored) CSI: " + getArg0(-1) + " to " + getArg1(-1));""",
            """Logger.logWarn(mClient, LOG_TAG, String.format("SGR unknown code %d", code));""",
            """Logger.logWarn(mClient, LOG_TAG, "Failed sending SIGKILL (" + e.getClass().getSimpleName() + ")");""",
            "Logger.logStackTraceWithMessage(mClient, LOG_TAG, \"Error getting current directory\", e);",
            """logError("Unimplemented sequence char (U+" + String.format("%04x", b) + ")");""",
            """private void logError(String errorType) {""",
            "// Logger.logWarn(mClient, LOG_TAG, \"x\" + part);",
        )
        for (sample in safe) {
            assertEquals(emptyList<String>(), scanJava(sample), sample)
        }
    }
}

/** Blanks the comments of a Kotlin or Java source with spaces, keeping offsets and line breaks. */
internal fun blankComments(source: String): String = CommentBlanker(source).run()

private class CommentBlanker(private val s: String) {
    private val out = s.toCharArray()
    private var i = 0

    fun run(): String {
        code(closeAtBrace = false)
        return String(out)
    }

    private fun blank(from: Int, to: Int) {
        for (k in from until minOf(to, out.size)) {
            if (out[k] != '\n' && out[k] != '\r') out[k] = ' '
        }
    }

    private fun code(closeAtBrace: Boolean) {
        var depth = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> string()
                c == '\'' -> charLiteral()
                c == '/' && s.startsWith("//", i) -> {
                    val start = i
                    while (i < s.length && s[i] != '\n') i++
                    blank(start, i)
                }
                c == '/' && s.startsWith("/*", i) -> {
                    val start = i
                    var nesting = 0
                    // Kotlin block comments nest.
                    do {
                        if (s.startsWith("/*", i)) { nesting++; i += 2 }
                        else if (s.startsWith("*/", i)) { nesting--; i += 2 }
                        else i++
                    } while (i < s.length && nesting > 0)
                    blank(start, i)
                }
                closeAtBrace && c == '{' -> { depth++; i++ }
                closeAtBrace && c == '}' -> {
                    i++
                    if (depth == 0) return
                    depth--
                }
                else -> i++
            }
        }
    }

    private fun charLiteral() {
        i++ // opening quote
        if (i < s.length && s[i] == '\\') i += 2 // the escape and the escaped char: '\'' and '\n'
        while (i < s.length && s[i] != '\'' && s[i] != '\n') i++ // the rest of '\u0000'
        i++ // closing quote
    }

    private fun string() {
        val raw = s.startsWith("\"\"\"", i)
        i += if (raw) 3 else 1
        while (i < s.length) {
            val c = s[i]
            if (!raw && c == '\\') { i += 2; continue }
            if (raw && s.startsWith("\"\"\"", i)) {
                i += 3
                while (i < s.length && s[i] == '"') i++
                return
            }
            if (!raw && c == '"') { i++; return }
            if (c == '$' && s.startsWith("\${", i)) {
                i += 2
                code(closeAtBrace = true)
                continue
            }
            i++
        }
    }
}

/**
 * Minimal Kotlin lexer, enough to read a log call: it walks the balanced parentheses of the call,
 * skips string literals (raw ones included), char literals and comments, and keeps the code that is
 * outside literals plus the expressions of string templates (`${...}` and `$name`).
 */
private class Lexer(val s: String, var i: Int) {

    /** Code of every `${...}` and `$name` template met while lexing, literal text removed. */
    val templateExpressions = mutableListOf<String>()

    /** Lexes code until the [close] bracket of depth zero; string literals become `"_"`. */
    fun lexCode(close: Char): String {
        val open = if (close == ')') '(' else '{'
        val out = StringBuilder()
        var depth = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> { out.append("\"_\""); lexString() }
                c == '\'' -> { skipCharLiteral(); out.append("'_'") }
                c == '/' && s.startsWith("//", i) -> { while (i < s.length && s[i] != '\n') i++ }
                c == '/' && s.startsWith("/*", i) -> {
                    val end = s.indexOf("*/", i + 2)
                    i = if (end < 0) s.length else end + 2
                }
                c == open -> { depth++; out.append(c); i++ }
                c == close -> {
                    i++
                    if (depth == 0) return out.toString()
                    depth--
                    out.append(c)
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    private fun skipCharLiteral() {
        i++ // opening quote
        if (i < s.length && s[i] == '\\') i += 2 // the escape and the escaped char: '\'' and '\n'
        while (i < s.length && s[i] != '\'' && s[i] != '\n') i++ // the rest of '\u0000'
        i++ // closing quote
    }

    private fun lexString() {
        val raw = s.startsWith("\"\"\"", i)
        i += if (raw) 3 else 1
        while (i < s.length) {
            val c = s[i]
            if (!raw && c == '\\') { i += 2; continue }
            if (raw && s.startsWith("\"\"\"", i)) {
                i += 3
                while (i < s.length && s[i] == '"') i++
                return
            }
            if (!raw && c == '"') { i++; return }
            if (c == '$' && s.startsWith("\${", i)) {
                i += 2
                templateExpressions += lexCode('}')
                continue
            }
            if (c == '$' && i + 1 < s.length && (s[i + 1].isLetter() || s[i + 1] == '_')) {
                var j = i + 1
                while (j < s.length && (s[j].isLetterOrDigit() || s[j] == '_')) j++
                templateExpressions += s.substring(i + 1, j)
                i = j
                continue
            }
            i++
        }
    }
}

/** Splits [code] on [separator] outside any bracket pair. */
private fun splitTopLevel(code: String, separator: Char): List<String> {
    val parts = mutableListOf<String>()
    var depth = 0
    var start = 0
    for ((index, c) in code.withIndex()) {
        when (c) {
            '(', '[', '{' -> depth++
            ')', ']', '}' -> depth--
            separator -> if (depth == 0) { parts += code.substring(start, index); start = index + 1 }
        }
    }
    parts += code.substring(start)
    return parts
}

internal object LogCallScanner {

    /** Wrappers that turn a value into something a log line may carry. Their arguments are not checked. */
    private val SAFE_WRAPPERS = listOf("LogId.of", "errorClass", "safeStackTrace", "LogScrubber.clean")

    /** Lowercase identifiers that name user-owned or peer-owned values. */
    private val SENSITIVE_TOKENS = setOf(
        "label", "hostlabel", "hostname", "host", "username", "user", "login", "email",
        "path", "safepath", "safeoldpath", "remotepath", "localpath", "oldpath", "newpath", "parentpath",
        "filename", "foldername", "displayname", "uri", "localuri", "sourceuri", "destinationuri",
        "alias", "keystorealias", "fingerprint", "devicename", "rpid", "lastknownhost", "address",
        "password", "passphrase", "secret", "message",
        "name", "title", "command", "url", "port", "localport", "remotehost", "sshhost", "bindaddress",
        "text", "content",
    )

    /** What a throwable parameter is called. Allowed in the throwable position of a call. */
    private val THROWABLE_NAMES = setOf("e", "ex", "t", "th", "err", "error", "exception", "throwable", "cause")

    /** Last segment of a name or chain whose `toString()` we do not control: never interpolated. */
    private val OPAQUE_NAMES = THROWABLE_NAMES + setOf("result", "response", "it")

    private val MESSAGE_ACCESS = Regex("""\.(?:message|localizedMessage)\b|\bstackTraceToString\b|\bgetStackTraceString\b|\bprintStackTrace\b""")
    private val TO_STRING = Regex("""\.toString\s*\(""")
    private val IDENTIFIER = Regex("""[A-Za-z_][A-Za-z0-9_]*""")

    /** `name = ` prefix of a Kotlin named argument (not `==`). */
    private val NAMED_ARGUMENT = Regex("""^[A-Za-z_]\w*\s*=(?!=)\s*""")

    /** A name, or a chain of names joined by `.`, `?.` or `!!.`. */
    private val NAME_CHAIN = Regex("""[A-Za-z_]\w*(?:\s*(?:\?\.|!!\.|\.)\s*[A-Za-z_]\w*)*""")

    /** `id`, `ID`, `uuid` or camelCase `...Id` / `...ID` (`sessionId`, `deviceId`, `credentialID`). */
    private val ID_TOKEN = Regex("""id|ID|uuid|UUID|[A-Za-z0-9]*[a-z0-9](?:Id|ID)""")

    /** `Timber.d(TAG, "text")`: the first argument is a tag, the second a string. */
    private val TAG_AS_MESSAGE = Regex("""^\(\s*(?:\w*(?:TAG|Tag|tag)\w*|"[A-Za-z0-9_.]+")\s*,\s*"""")

    private const val ALLOW_MARKER = "log-guard:allow"

    private val TIMBER_CALL = Regex("""\bTimber\.(?:tag\(|(?:[vdiwe]|wtf|log)\()""")
    private val TIMBER_METHOD_AFTER_TAG = Regex("""\s*\.\s*((?:[vdiwe]|wtf|log))\(""")
    private val LOGGER_CALL = Regex("""\bLogger\.[dwe]\(""")

    private enum class Kind { TIMBER_LEVEL, TIMBER_LOG, LOGGER }

    private class Call(val start: Int, val openParen: Int, val kind: Kind, val tagOpenParen: Int?)

    fun countCalls(source: String): Int = findCalls(blankComments(source)).size

    /** One line per violation: `File.kt:LINE: rule: call text`. */
    fun scan(source: String, fileName: String): List<String> {
        val code = blankComments(source)
        val lines = source.lines() // the original: the allow marker lives in a comment
        val out = mutableListOf<String>()
        for (call in findCalls(code)) {
            val lexer = Lexer(code, call.openParen + 1)
            val argsCode = lexer.lexCode(')')
            val rawCall = code.substring(call.openParen, lexer.i.coerceAtMost(code.length))
            val startLine = code.substring(0, call.start).count { it == '\n' }
            val endLine = startLine + rawCall.count { it == '\n' }
            val allowed = (maxOf(0, startLine - 1)..minOf(endLine, lines.lastIndex))
                .any { lines[it].contains(ALLOW_MARKER) }
            if (allowed) continue

            val problems = mutableListOf<String>()
            if (call.kind == Kind.TIMBER_LEVEL && TAG_AS_MESSAGE.containsMatchIn(rawCall)) {
                problems += "the first argument of Timber.x is the message, use Timber.tag(TAG).x(...)"
            }

            // The tag argument is logged with every line: same rules, no throwable position.
            call.tagOpenParen?.let { open ->
                val tagLexer = Lexer(code, open + 1)
                val tagCode = tagLexer.lexCode(')')
                for (fragment in listOf(tagCode) + tagLexer.templateExpressions) {
                    problems += checkTokens(stripSafeWrappers(fragment)).map { "tag argument: $it" }
                }
            }

            // Template fragments: `$name` and `${expression}` of the message.
            for (fragment in lexer.templateExpressions) {
                val stripped = stripSafeWrappers(fragment)
                problems += checkTokens(stripped)
                opaqueName(stripped)?.let { problems += "interpolated object ($it), log its fields or errorClass" }
            }

            // Arguments, outside the string literals.
            val args = splitTopLevel(argsCode, ',').filter { it.isNotBlank() } // a trailing comma leaves an empty last part
            for ((index, arg) in args.withIndex()) {
                val stripped = stripSafeWrappers(arg).trim()
                problems += checkTokens(stripped)
                // `"text" + e` concatenates: each part is an argument of its own.
                val parts = splitTopLevel(stripped, '+')
                // Whatever sits in the throwable position and is not a string literal is a throwable: a name, a call,
                // a cast, `!!`, a constructor, or a named argument (`throwable = e`).
                val handsThrowable = index == throwableIndex(call.kind) && args.size > minArgsForThrowable(call.kind) &&
                    isNotLiteral(stripped)
                if (handsThrowable) {
                    problems += "throwable passed to the log ($stripped), write errorClass(e) in the message"
                    continue
                }
                for (part in parts) {
                    val opaque = opaqueName(part) ?: continue
                    problems += "object passed as text ($opaque), log its fields or errorClass"
                }
            }
            val only = args.singleOrNull()?.let { stripSafeWrappers(it).trim() } // safe wrappers are accepted alone too
            if (call.kind == Kind.TIMBER_LEVEL && only != null && isNotLiteral(only)) {
                problems += if (only.lowercase() in THROWABLE_NAMES || !IDENTIFIER.matches(only)) {
                    "throwable passed to the log ($only), write errorClass(e) in the message"
                } else {
                    "message built elsewhere ($only), cannot be checked: write the text in the call"
                }
            }

            if (problems.isNotEmpty()) {
                val snippet = rawCall.replace(Regex("""\s+"""), " ").take(140)
                out += "$fileName:${startLine + 1}: ${problems.distinct().joinToString("; ")}: $snippet"
            }
        }
        return out
    }

    /** Position of the throwable parameter: `Timber.w(e, "text")`, `Timber.log(p, e, "text")`, `Logger.e(tag, "text", e)`. */
    private fun throwableIndex(kind: Kind): Int = when (kind) {
        Kind.TIMBER_LEVEL -> 0
        Kind.TIMBER_LOG -> 1
        Kind.LOGGER -> 2
    }

    /** True when [argument], once wrappers and a `name =` prefix are gone, is not a string literal or a safe wrapper call. */
    private fun isNotLiteral(argument: String): Boolean {
        val value = argument.trim().replace(NAMED_ARGUMENT, "")
        return value.isNotEmpty() && value != "_" && !value.startsWith("\"")
    }

    /** Argument count above which the throwable position is occupied: `Timber.w("x")` has no throwable, `Timber.w(e, "x")` has. */
    private fun minArgsForThrowable(kind: Kind): Int = when (kind) {
        Kind.TIMBER_LEVEL -> 1
        Kind.TIMBER_LOG -> 1
        Kind.LOGGER -> 2
    }

    /** Last segment of [fragment] when it is a bare name or chain ending in one of [OPAQUE_NAMES]. */
    private fun opaqueName(fragment: String): String? {
        val trimmed = fragment.trim()
        if (!NAME_CHAIN.matches(trimmed)) return null
        val last = trimmed.split('.', '?', '!').last { it.isNotBlank() }.trim()
        return last.takeIf { it.lowercase() in OPAQUE_NAMES }
    }

    /** Message or stack text access, `.toString()`, user or peer identifiers, raw ids. */
    private fun checkTokens(fragment: String): List<String> {
        val problems = mutableListOf<String>()
        MESSAGE_ACCESS.find(fragment)?.let { problems += "message or stack trace text (${it.value.trim()})" }
        if (TO_STRING.containsMatchIn(fragment)) problems += "toString() of an object we do not control"
        for (token in IDENTIFIER.findAll(fragment).map { it.value }) {
            if (token.lowercase() in SENSITIVE_TOKENS) problems += "user or peer value ($token)"
            else if (ID_TOKEN.matches(token)) problems += "raw id ($token), use LogId.of"
        }
        return problems
    }

    /** Opening parenthesis of every `Timber.x(` / `Timber.tag(..).x(` / `Logger.x(` call. */
    private fun findCalls(source: String): List<Call> {
        val calls = mutableListOf<Call>()
        for (match in TIMBER_CALL.findAll(source)) {
            if (match.value.endsWith("tag(")) {
                // Timber.tag(TAG).d(...) : skip the balanced tag(...) then read .d(
                val tagOpen = match.range.last
                val tagLexer = Lexer(source, tagOpen + 1)
                tagLexer.lexCode(')')
                val after = TIMBER_METHOD_AFTER_TAG.matchAt(source, tagLexer.i) ?: continue
                val kind = if (after.groupValues[1] == "log") Kind.TIMBER_LOG else Kind.TIMBER_LEVEL
                calls += Call(match.range.first, after.range.last, kind, tagOpen)
            } else {
                val kind = if (match.value.endsWith("log(")) Kind.TIMBER_LOG else Kind.TIMBER_LEVEL
                calls += Call(match.range.first, match.range.last, kind, null)
            }
        }
        for (match in LOGGER_CALL.findAll(source)) {
            calls += Call(match.range.first, match.range.last, Kind.LOGGER, null)
        }
        return calls
    }

    /** Removes `Wrapper(...)` calls (balanced parentheses) from a code-only fragment. */
    private fun stripSafeWrappers(code: String): String {
        var text = code
        for (wrapper in SAFE_WRAPPERS) {
            var from = 0
            while (true) {
                val at = text.indexOf("$wrapper(", from)
                if (at < 0) break
                var depth = 0
                var end = at + wrapper.length
                while (end < text.length) {
                    when (text[end]) {
                        '(' -> depth++
                        ')' -> { depth--; if (depth == 0) break }
                    }
                    end++
                }
                text = text.substring(0, at) + "_" + text.substring(minOf(end + 1, text.length))
                from = at + 1
            }
        }
        return text
    }
}

/**
 * Guard of the Termux fork log calls (Java). The terminal handles bytes sent by the remote host,
 * so the message of a `Logger.log*` call (or of the private `logError(String)` of the emulator, or
 * of a direct `client.log*` call) is made of string literals, integers, int variables of the
 * emulator, `String.format` of those and exception class names. Anything else, such as a decoded
 * name, a payload string, a character or `e.getMessage()`, is a violation.
 */
internal object TermuxLogScanner {

    private val CALL = Regex(
        """(?<![\w.])(Logger\.log(?:Error|Warn|Info|Debug|Verbose|StackTraceWithMessage)|logError)\(|(?<![\w])((?:m?[cC]lient)\.log(?:Error|Warn|Info|Debug|Verbose))\(""",
    )

    /** Int-valued names of TerminalEmulator.java that may be concatenated into a message. */
    private const val INT_NAMES = "mode|externalBit|code|color|mEscapeState|b|parameter|errorType|error"

    private val ALLOWED_PARTS = listOf(
        Regex(""""_""""), // a string literal (the lexer replaces its content)
        Regex("""-?\d+"""),
        Regex(INT_NAMES),
        Regex("""getArg[01]\(-?\d+\)"""),
        Regex("""String\.format\("_"(?:\s*,\s*(?:$INT_NAMES))*\)"""),
        Regex("""\w+\.getClass\(\)\.getSimpleName\(\)"""),
    )

    fun countCalls(source: String): Int = findCalls(blankComments(source)).size

    private class JavaCall(val start: Int, val open: Int, val messageIndex: Int)

    private fun findCalls(source: String): List<JavaCall> {
        val calls = mutableListOf<JavaCall>()
        for (match in CALL.findAll(source)) {
            val name = match.groupValues[1].ifEmpty { match.groupValues[2] }
            // The definition `private void logError(String errorType) {` is not a call.
            val lineStart = source.lastIndexOf('\n', match.range.first) + 1
            if (source.substring(lineStart, match.range.first).contains("void ")) continue
            val messageIndex = when {
                name.startsWith("Logger.") -> 2 // Logger.logX(client, tag, message[, throwable])
                name == "logError" -> 0 // the private logError(String) of the emulator
                else -> 1 // client.logX(tag, message)
            }
            calls += JavaCall(match.range.first, match.range.last, messageIndex)
        }
        return calls
    }

    /** One line per violation: `File.java:LINE: part: call text`. */
    fun scan(source: String, fileName: String): List<String> {
        val code = blankComments(source)
        val out = mutableListOf<String>()
        for (call in findCalls(code)) {
            val lexer = Lexer(code, call.open + 1)
            val argsCode = lexer.lexCode(')')
            val args = splitTopLevel(argsCode, ',')
            val message = args.getOrNull(call.messageIndex)?.trim() ?: continue
            val bad = splitTopLevel(message, '+').map { it.trim() }
                .filter { part -> ALLOWED_PARTS.none { it.matches(part) } }
            if (bad.isNotEmpty()) {
                val line = code.substring(0, call.start).count { it == '\n' } + 1
                val snippet = code.substring(call.open, lexer.i.coerceAtMost(code.length)).replace(Regex("""\s+"""), " ").take(140)
                out += "$fileName:$line: not fixed text, int or class name (${bad.joinToString(", ")}): $snippet"
            }
        }
        return out
    }
}
