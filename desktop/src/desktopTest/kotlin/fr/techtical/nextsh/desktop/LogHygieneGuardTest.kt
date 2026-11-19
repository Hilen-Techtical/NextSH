// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guard against personal data coming back into the Desktop logs.
 *
 * The rule (all levels, debug included): a log line carries fixed text, operation and state
 * names, counts, sizes, durations, numeric codes, algorithm and transport names and exception
 * CLASS names only. Entity and device ids go through `LogId.of(id)`, exceptions through
 * `errorClass(e)` or the `throwable` argument of `Logger.e`.
 *
 * This test reads the SOURCE TEXT of `desktop/src/desktopMain/kotlin` (and `desktop/src/commonMain`
 * when it holds Kotlin) and fails on, inside every `Logger.d/w/e(...)` call:
 *
 *  1. [Rule.EXCEPTION_MESSAGE]: `.message`, `.localizedMessage`, `getMessage()`.
 *  2. [Rule.RAW_BYTES]: `toHex(`, `toHexString(` (but not `Integer.toHexString`, used for HRESULT codes),
 *     `HexFormat`, `contentToString(`.
 *  3. [Rule.STACK_TRACE]: `stackTraceToString(`, `.stackTrace`, `printStackTrace` (the Logger prints a
 *     safe trace by itself when given the throwable).
 *  4. [Rule.SENSITIVE_VALUE]: a template or an argument that names a sensitive value
 *     ([SENSITIVE_WORDS]: label, host, hostname, username, path, alias, fingerprint, deviceName,
 *     deviceId, rpId, address, ip, port, id, name, title, message, msg, text, output, content, value,
 *     command ...), unless it sits inside a safe wrapper ([LogScanner.SAFE_WRAPPERS]: `LogId.of`,
 *     `errorClass`, `pointerState`, `describeFailure`) or is only measured (`credentialId.size`). A
 *     token is sensitive when it is, or ends with as a camelCase word, one of those words:
 *     `config.label`, `$deviceId`, `result.hostPort`, `localPort`, `syncId`, `readerName`. A
 *     CONSTANT (all caps) is fixed text and passes, as does `simpleName` (a class name).
 *  5. [Rule.NON_LITERAL_MESSAGE]: the message (second argument) does not start with a string literal. A
 *     message held in a variable (`Logger.w(TAG, reason)`) cannot be checked by reading the call.
 *     `buildString { ... }` is accepted only in the files of [BUILD_STRING_ALLOW_LIST], and its content is
 *     checked by the rules above like any other argument.
 *  6. [Rule.BARE_TEMPLATE]: a template that prints a whole object: `$e`, `${result}`, `$state`, `${x.cause}`,
 *     `${y.toString()}`, `$settled`, `$reason`, `$msg`, `$status`, `$response` (a bare name or a chain ending
 *     in one of [BARE_WORDS], as a word). `Throwable.toString()` quotes the message, a state object quotes
 *     its fields. The few deliberate uses are listed in [BARE_TEMPLATE_ALLOW_LIST].
 *
 * It also fails on [Rule.CONSOLE_OUTPUT] (`println`, `print`, `printStackTrace`, `System.out`, `System.err`
 * anywhere, except the files of [CONSOLE_ALLOW_LIST]) and on [Rule.SCANNER_MISMATCH] (a `Logger` call the
 * scanner cannot parse, or a `Logger.<member>` that is not a `d`, `w` or `e` call).
 *
 * The arguments of `StartupTrace.mark` and `StartupTrace.async` are scanned with the rules 1 to 4 and 6.
 *
 * Comments are ignored, string literals are checked only through their `${...}` templates (the
 * fixed text of a message may say "host" or "label").
 *
 * Known limits, on purpose: the check is lexical. It cannot see a sensitive value held by a variable with
 * an innocent name. Put a runtime assertion in the test of the code that builds the message (see
 * `captureLogs`, `taggedBy` and `assertNothingScrubbed`). The test also fails if the sources cannot be
 * found or if the scan finds suspiciously little, so that a moved directory cannot turn it into a no-op.
 */
class LogHygieneGuardTest {

    private enum class Rule {
        EXCEPTION_MESSAGE,
        RAW_BYTES,
        STACK_TRACE,
        SENSITIVE_VALUE,
        NON_LITERAL_MESSAGE,
        BARE_TEMPLATE,
        CONSOLE_OUTPUT,
        SCANNER_MISMATCH,
    }

    private data class Violation(val file: String, val line: Int, val rule: Rule, val excerpt: String) {
        override fun toString() = "$file:$line [$rule] $excerpt"
    }

    /** What a file is allowed, beyond the rules. All empty by default. */
    private class Policy(
        val consoleAllowed: Boolean = false,
        val buildStringAllowed: Boolean = false,
        val bareTemplatesAllowed: Set<String> = emptySet(),
    )

    private class ScanResult(
        val violations: List<Violation>,
        val filesScanned: Int,
        val logCalls: Int,
        val traceCalls: Int,
    )

    // ── The real scan ────────────────────────────────────────────────────────

    private val srcDir: File by lazy { locateSrcDir() }

    private val scan: ScanResult by lazy {
        val src = srcDir
        val roots = listOf("desktopMain/kotlin", "commonMain").map { File(src, it) }.filter { it.isDirectory }
        val files = roots.flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .sortedBy { it.path }
        val violations = mutableListOf<Violation>()
        var logCalls = 0
        var traceCalls = 0
        for (file in files) {
            val relative = file.relativeTo(src).invariantSeparatorsPath
            val policy = Policy(
                consoleAllowed = relative in CONSOLE_ALLOW_LIST,
                buildStringAllowed = relative in BUILD_STRING_ALLOW_LIST,
                bareTemplatesAllowed = BARE_TEMPLATE_ALLOW_LIST[relative].orEmpty(),
            )
            val result = LogScanner.scan(relative, file.readText(Charsets.UTF_8), policy)
            violations += result.violations
            logCalls += result.logCalls
            traceCalls += result.traceCalls
        }
        ScanResult(violations, files.size, logCalls, traceCalls)
    }

    @Test
    fun `the desktop sources are found and the scan is not vacuous`() {
        val result = scan
        assertTrue(result.filesScanned >= 100, "only ${result.filesScanned} source files under $srcDir: wrong directory?")
        assertTrue(result.logCalls >= 150, "only ${result.logCalls} Logger calls scanned: the scanner no longer recognises them")
        assertTrue(result.traceCalls >= 10, "only ${result.traceCalls} StartupTrace calls scanned: the scanner no longer recognises them")
    }

    @Test
    fun `the allow-lists name files and expressions that still exist`() {
        val listed = CONSOLE_ALLOW_LIST + BUILD_STRING_ALLOW_LIST + BARE_TEMPLATE_ALLOW_LIST.keys
        for (relative in listed) {
            assertTrue(File(srcDir, relative).isFile, "stale allow-list entry, the file is gone: $relative")
        }
        for ((relative, expressions) in BARE_TEMPLATE_ALLOW_LIST) {
            val text = File(srcDir, relative).readText(Charsets.UTF_8)
            for (expression in expressions) {
                assertTrue(text.contains(expression), "stale allow-list entry, $relative no longer uses '$expression'")
            }
        }
    }

    @Test
    fun `no log call prints an exception message`() = assertNoViolation(Rule.EXCEPTION_MESSAGE)

    @Test
    fun `no log call prints raw bytes`() = assertNoViolation(Rule.RAW_BYTES)

    @Test
    fun `no log call prints a stack trace by hand`() = assertNoViolation(Rule.STACK_TRACE)

    @Test
    fun `no log call interpolates a personal value unless wrapped in LogId`() = assertNoViolation(Rule.SENSITIVE_VALUE)

    @Test
    fun `every log message starts with a string literal`() = assertNoViolation(Rule.NON_LITERAL_MESSAGE)

    @Test
    fun `no log call prints a whole object`() = assertNoViolation(Rule.BARE_TEMPLATE)

    @Test
    fun `no console output outside the allow-list`() = assertNoViolation(Rule.CONSOLE_OUTPUT)

    @Test
    fun `the scanner understands every Logger call in the sources`() = assertNoViolation(Rule.SCANNER_MISMATCH)

    private fun assertNoViolation(rule: Rule) {
        val found = scan.violations.filter { it.rule == rule }
        assertTrue(
            found.isEmpty(),
            "$rule: ${found.size} violation(s). Log fixed text, counts, codes, LogId.of(id) and errorClass(e) only:\n" +
                found.joinToString("\n") { "  $it" },
        )
    }

    // ── The scanner is itself under test ─────────────────────────────────────

    /** `$` in the snippets below, so that they stay readable inside raw strings. */
    private val D = '$'

    private fun rulesIn(source: String, policy: Policy = Policy()): List<Rule> =
        LogScanner.scan("Snippet.kt", source, policy).violations.map { it.rule }

    /** [source] must be flagged with [rule] (it may be flagged with others too: `e.message` is also a bare name). */
    private fun assertFlagged(rule: Rule, source: String) {
        val rules = rulesIn(source)
        assertTrue(rule in rules, "expected $rule for: $source (got $rules)")
    }

    @Test
    fun `the scanner flags what the rule forbids`() {
        assertFlagged(Rule.EXCEPTION_MESSAGE, """Logger.w(TAG, "failed: ${D}{e.message}")""")
        assertFlagged(Rule.EXCEPTION_MESSAGE, """Logger.d(TAG, "x ${D}{e.cause?.message}")""")
        assertFlagged(Rule.EXCEPTION_MESSAGE, """Logger.w(TAG, "x " + e.localizedMessage)""")
        assertFlagged(Rule.RAW_BYTES, """Logger.d(TAG, "apdu=${D}{apdu.toHex()}")""")
        assertFlagged(Rule.RAW_BYTES, """Logger.d(TAG, "bytes=${D}{data.toHexString()}")""")
        assertFlagged(Rule.STACK_TRACE, """Logger.w(TAG, "x " + e.stackTraceToString())""")
        assertFlagged(Rule.SENSITIVE_VALUE, """Logger.d(TAG, "tunnel ${D}{config.label}")""")
        assertFlagged(Rule.SENSITIVE_VALUE, """Logger.d(TAG, "device ${D}deviceId")""")
        assertFlagged(Rule.SENSITIVE_VALUE, """Logger.d(TAG, "at ${D}{result.hostPort}")""")
        assertFlagged(Rule.SENSITIVE_VALUE, """Logger.d(TAG, "on ${D}{config.localPort}")""")
        assertFlagged(Rule.SENSITIVE_VALUE, """Logger.d(TAG, "sync ${D}{conflict.entityId}")""")
        assertFlagged(Rule.SENSITIVE_VALUE, """Logger.d(TAG, "id " + device.deviceId)""")
        // A call over several lines, with a nested string inside a template.
        assertFlagged(Rule.EXCEPTION_MESSAGE, "Logger.w(\n    TAG,\n    \"a ${D}{if (x) \"b\" else e.message}\",\n)")
        assertEquals(listOf(Rule.CONSOLE_OUTPUT), rulesIn("""println("x")"""))
        assertEquals(listOf(Rule.CONSOLE_OUTPUT), rulesIn("""e.printStackTrace()"""))
        assertEquals(listOf(Rule.CONSOLE_OUTPUT), rulesIn("""System.err.println("x")"""))
        // A violation is reported once per rule and per call.
        assertEquals(listOf(Rule.RAW_BYTES), rulesIn("""Logger.d(TAG, "apdu=${D}{apdu.toHex()} ${D}{other.toHex()}")"""))
    }

    @Test
    fun `the scanner flags the words added after the first review`() {
        for (token in listOf("item.name", "terminal.name", "title", "response.text", "proc.output", "page.content", "entry.value", "command.first", "readerName", "msg")) {
            assertFlagged(Rule.SENSITIVE_VALUE, """Logger.d(TAG, "x ${D}{$token}")""")
        }
        // A class name is not a name.
        assertEquals(emptyList(), rulesIn("""Logger.d(TAG, "x ${D}{e.javaClass.simpleName} ${D}{state::class.simpleName}")"""))
    }

    @Test
    fun `the scanner flags a message that is not a string literal`() {
        assertFlagged(Rule.NON_LITERAL_MESSAGE, """Logger.w(TAG, message)""")
        assertFlagged(Rule.NON_LITERAL_MESSAGE, """Logger.w(TAG, reason, e)""")
        assertFlagged(Rule.NON_LITERAL_MESSAGE, """Logger.w(TAG, if (a) "x" else "y")""")
        assertFlagged(Rule.NON_LITERAL_MESSAGE, """Logger.d(TAG, line + "x")""")
        assertFlagged(Rule.NON_LITERAL_MESSAGE, """Logger.d(TAG)""")
        // buildString is refused by default, accepted where listed, and its content is still checked.
        val built = """Logger.d(TAG, buildString { append("a ${D}{n}") })"""
        assertEquals(listOf(Rule.NON_LITERAL_MESSAGE), rulesIn(built))
        assertEquals(emptyList(), rulesIn(built, Policy(buildStringAllowed = true)))
        assertTrue(
            Rule.EXCEPTION_MESSAGE in rulesIn("""Logger.d(TAG, buildString { append("a ${D}{e.message}") })""", Policy(buildStringAllowed = true)),
        )
    }

    @Test
    fun `the scanner flags a template that prints a whole object`() {
        for (template in listOf(
            "${D}e", "${D}{e}", "${D}{ex}", "${D}{t}", "${D}throwable", "${D}{result}", "${D}{state}", "${D}current", "${D}settled",
            "${D}{reason}", "${D}{msg}", "${D}{message}", "${D}{status}", "${D}{response}", "${D}{x.cause}", "${D}{y.toString()}",
            "${D}{lockResult.reason}", "${D}{openResult}", "${D}{state.status}", "${D}{x?.cause}",
        )) {
            assertFlagged(Rule.BARE_TEMPLATE, """Logger.d(TAG, "x $template")""")
        }
        // The deliberate uses are allowed by expression, per file.
        val allowed = Policy(bareTemplatesAllowed = setOf("lockResult.reason"))
        assertEquals(emptyList(), rulesIn("""Logger.d(TAG, "x ${D}{lockResult.reason}")""", allowed))
        assertEquals(listOf(Rule.BARE_TEMPLATE), rulesIn("""Logger.d(TAG, "x ${D}{other.reason}")""", allowed))
    }

    @Test
    fun `the scanner fails on what it cannot parse instead of skipping it`() {
        // An unbalanced call.
        assertEquals(listOf(Rule.SCANNER_MISMATCH), rulesIn("""Logger.d(TAG, "x" """))
        // A Logger member that is not a d, w or e call.
        assertEquals(listOf(Rule.SCANNER_MISMATCH), rulesIn("""Logger.i(TAG, "x")"""))
        assertEquals(listOf(Rule.SCANNER_MISMATCH), rulesIn("""Logger.setDebugEnabled(true)"""))
    }

    @Test
    fun `the scanner reads a raw string that ends with extra quotes`() {
        // """say "hi"""" is the raw text: say "hi"
        val quoted = "Logger.d(TAG, \"\"\"say \"hi\"\"\"\")\nLogger.w(TAG, \"${D}{e.message}\")"
        val result = LogScanner.scan("Snippet.kt", quoted, Policy())
        assertEquals(2, result.logCalls)
        assertTrue(result.violations.any { it.rule == Rule.EXCEPTION_MESSAGE && it.line == 2 }, "got ${result.violations}")
        assertTrue(result.violations.none { it.rule == Rule.SCANNER_MISMATCH || it.rule == Rule.NON_LITERAL_MESSAGE }, "got ${result.violations}")
        // The same literal outside a call must not swallow the next call either.
        val before = "val q = \"\"\"a\"\"\"\"\nLogger.w(TAG, \"${D}{e.message}\")"
        assertFlagged(Rule.EXCEPTION_MESSAGE, before)
        assertTrue(Rule.SCANNER_MISMATCH !in rulesIn(before))
    }

    @Test
    fun `the scanner covers StartupTrace arguments`() {
        assertEquals(listOf(Rule.SENSITIVE_VALUE), rulesIn("""StartupTrace.mark("opened ${D}{cfg.label}")"""))
        assertFlagged(Rule.EXCEPTION_MESSAGE, """StartupTrace.async("failed ${D}{e.message}", ms)""")
        assertFlagged(Rule.BARE_TEMPLATE, """StartupTrace.mark("state ${D}state")""")
        // A timing label may be chosen by an expression, and a count is fine.
        assertEquals(emptyList(), rulesIn("""StartupTrace.mark(if (closed) "closed" else "absent")"""))
        assertEquals(emptyList(), rulesIn("""StartupTrace.mark("folders ready (${D}{folders.size} folders)")"""))
        assertEquals(1, LogScanner.scan("Snippet.kt", """StartupTrace.mark("x")""", Policy()).traceCalls)
    }

    @Test
    fun `the scanner lets compliant code through`() {
        val clean = listOf(
            """Logger.d(TAG, "device=${D}{LogId.of(deviceId)}")""",
            """Logger.w(TAG, "failed (${D}{errorClass(e)})")""",
            """Logger.e(TAG, "failed", e)""",
            // The size of an id is a size; a numeric code formatted as hex is a code.
            """Logger.d(TAG, "credentialId.size=${D}{credentialId.size}")""",
            """Logger.w(TAG, "HRESULT=0x${D}{Integer.toHexString(hr)}")""",
            // Wrappers that are safe by construction.
            """Logger.d(TAG, "p=${D}{pointerState(options.pCancellationId)} f=${D}{describeFailure(e)}")""",
            """Logger.d(TAG, "n=${D}{list.size} port=${D}SYNC_PORT attempt ${D}attempt/${D}MAX")""",
            """Logger.d(TAG, "state ${D}{state::class.simpleName} ${D}{stateName(current)}")""",
            """Logger.d(TAG, "applied=${D}{result.cleanApplied} conflicts=${D}{result.conflicts.size}")""",
            // A message over several lines, the way the sources write them.
            "Logger.w(\n    TAG,\n    \"a \" +\n        \"b ${D}{errorClass(e)}\",\n)",
            // A raw string literal is a string literal.
            "Logger.d(\"Tag\", \"\"\"raw text\"\"\")",
            // Fixed text may say host or label; only templates are checked.
            """Logger.d(TAG, "the host label is hidden")""",
            """Logger.d(TAG, "keys: ${D}{if (a) "host" else "x"}")""",
            // Comments are ignored.
            "// Logger.w(TAG, \"${D}{e.message}\")\nval x = 1",
            "/* println(\"x\") and e.message */\nval y = 2",
            // "//" inside a string is not a comment, and the call after it is still scanned.
            "val u = \"http://example\"; Logger.d(TAG, \"ok\")",
            // A character literal holding a quote does not open a string.
            "val q = '\"'; Logger.d(TAG, \"ok\")",
        )
        for (source in clean) assertEquals(emptyList(), rulesIn(source), "wrongly flagged: $source")
        // The console allow-list is honoured.
        assertEquals(emptyList(), rulesIn("""println("x")""", Policy(consoleAllowed = true)))
    }

    @Test
    fun `the scanner counts the log calls it sees`() {
        val source = """
            Logger.d(TAG, "a")
            Logger.w(TAG, "b")
            Logger.e(TAG, "c", e)
            val other = "Logger.d(not a call)"
            // Logger.w(TAG, "in a comment")
        """.trimIndent()
        assertEquals(3, LogScanner.scan("Snippet.kt", source, Policy()).logCalls)
    }

    // ── Configuration ────────────────────────────────────────────────────────

    private companion object {
        /**
         * Files allowed to write to the console directly, relative to `desktop/src`.
         * `StartupTrace` writes start-up timings (operation names and milliseconds), nothing else,
         * and runs before the Logger exists.
         */
        val CONSOLE_ALLOW_LIST: Set<String> = setOf(
            "desktopMain/kotlin/fr/techtical/nextsh/desktop/core/diagnostics/StartupTrace.kt",
        )

        /**
         * Files where a log message may be a `buildString { ... }`: the two multi-line struct
         * diagnostics of the Windows WebAuthn provider. The content is checked like any argument.
         */
        val BUILD_STRING_ALLOW_LIST: Set<String> = setOf(
            "desktopMain/kotlin/fr/techtical/nextsh/desktop/core/auth/fido2/winwebauthn/WindowsWebAuthnProvider.kt",
        )

        /**
         * Templates that print a whole object on purpose, per file, as written in the template.
         * - `logReason` (DesktopTunnelManager): a log-only reason built by the callers from fixed
         *   text, a protocol reason code or an exception class chain, never the user-facing text.
         * - `lockResult.reason` (KcefInitializer): the contract on `LockResult.Error` is fixed
         *   text plus an exception class name.
         */
        val BARE_TEMPLATE_ALLOW_LIST: Map<String, Set<String>> = mapOf(
            "desktopMain/kotlin/fr/techtical/nextsh/desktop/core/ssh/DesktopTunnelManager.kt" to setOf("logReason"),
            "desktopMain/kotlin/fr/techtical/nextsh/desktop/core/browser/KcefInitializer.kt" to setOf("lockResult.reason"),
        )

        /**
         * Words that name a value the user owns, text a peer sent, or a secret. A token matches
         * when it equals one of them or ends with one as a camelCase word (`hostPort`, `syncId`,
         * `localPort`, `readerName`). Lower case.
         */
        val SENSITIVE_WORDS: Set<String> = setOf(
            "label", "hostname", "host", "hostport", "username", "user", "login", "path", "alias",
            "fingerprint", "devicename", "displayname", "filename", "file", "dir", "directory",
            "deviceid", "rpid", "address", "addr", "ip", "port", "uri", "url", "id", "ids",
            "terminalname", "readername", "password", "passphrase", "pin", "secret", "token",
            "name", "title", "message", "msg", "text", "output", "content", "value", "command",
        )

        /** Tokens that look sensitive by their last word but are not: `simpleName` is a class name. */
        val SAFE_TOKENS: Set<String> = setOf("simpleName")

        /**
         * Names of objects that must not be printed whole: a throwable (`toString()` quotes its
         * message), a result, a state or a response (their `toString()` quotes their fields), a
         * status text. Matched like [SENSITIVE_WORDS]: the whole name or its last camelCase word.
         */
        val BARE_WORDS: Set<String> = setOf(
            "e", "ex", "t", "throwable", "result", "state", "current", "settled", "reason", "msg",
            "message", "status", "response", "cause",
        )

        /** The Gradle test task runs in `desktop/`; an IDE usually runs from the repository root. */
        fun locateSrcDir(): File {
            val candidates = listOf("src", "desktop/src")
            return candidates.map { File(it) }.firstOrNull { File(it, "desktopMain/kotlin").isDirectory }
                ?: error(
                    "desktop sources not found (working directory ${File(".").absoluteFile.normalize()}, " +
                        "tried $candidates): the log hygiene guard cannot run",
                )
        }
    }

    // ── Scanner ──────────────────────────────────────────────────────────────

    private object LogScanner {

        class Result(val violations: List<Violation>, val logCalls: Int, val traceCalls: Int)

        private val LOGGER_CALL = Regex("""\bLogger\s*\.\s*(?:d|w|e)\s*\(""")
        private val LOGGER_MEMBER = Regex("""\bLogger\s*\.\s*[A-Za-z_]\w*""")
        private val TRACE_CALL = Regex("""\bStartupTrace\s*\.\s*(?:mark|async)\s*\(""")
        private val EXCEPTION_MESSAGE = Regex("""\.\s*(?:message|localizedMessage)\b|\bgetMessage\s*\(|\bgetLocalizedMessage\s*\(""")
        // Integer.toHexString / Long.toHexString format a numeric code (an HRESULT): allowed.
        private val RAW_BYTES = Regex("""\btoHex\s*\(|(?<!Integer\.)(?<!Long\.)\btoHexString\s*\(|\bHexFormat\b|\bcontentToString\s*\(""")
        private val SIZE_ACCESS = Regex("""^\s*\??\.\s*(?:size|length|isEmpty|isNotEmpty|count)\b""")
        private val STACK_TRACE = Regex("""\bstackTraceToString\s*\(|\bstackTrace\b|\bprintStackTrace\b""")
        private val CONSOLE_OUTPUT = Regex("""\bprintln\s*\(|\bprint\s*\(|\bprintStackTrace\s*\(|\bSystem\s*\.\s*(?:out|err)\b""")
        private val IDENTIFIER = Regex("""[A-Za-z_][A-Za-z0-9_]*""")
        private val CAMEL_BOUNDARY = Regex("""(?<=[a-z0-9])(?=[A-Z])""")
        private val BARE_CHAIN = Regex("""[A-Za-z_]\w*(?:\s*\??\.\s*[A-Za-z_]\w*)*""")
        private val CHAIN_SEPARATOR = Regex("""\s*\??\.\s*""")

        /**
         * Calls whose result is safe by construction, so what they wrap is not checked:
         * `LogId.of(id)` (per-process fingerprint), `errorClass(e)` (class names),
         * `pointerState(p)` ("set" or "null"), `describeFailure(e)` (class names and SCARD statuses),
         * `describeCtapInfo(v, t)` (two counts and a flag), `stateName(s)` (the class name of a state).
         */
        private val SAFE_WRAPPERS = listOf("LogId.of(", "errorClass(", "pointerState(", "describeFailure(", "describeCtapInfo(", "stateName(")

        fun scan(file: String, source: String, policy: Policy): Result {
            val code = blankComments(source)
            // Same length as [code], literals blanked: a call or a println is only looked for in real code.
            val masked = maskLiterals(code)
            val violations = mutableListOf<Violation>()
            var calls = 0
            var unbalanced = 0
            var traceCalls = 0

            for (match in LOGGER_CALL.findAll(masked)) {
                val open = match.range.last
                val line = lineOf(code, match.range.first)
                val close = matchingParen(code, open)
                if (close == null) {
                    unbalanced++
                    violations += Violation(file, line, Rule.SCANNER_MISMATCH, "unbalanced Logger call: " + excerptOf(code, match.range.first, code.length))
                    continue
                }
                calls++
                inspect(file, line, excerptOf(code, match.range.first, close + 1), code.substring(open + 1, close), policy, messageMustBeLiteral = true, violations)
            }

            for (match in TRACE_CALL.findAll(masked)) {
                val open = match.range.last
                val line = lineOf(code, match.range.first)
                val close = matchingParen(code, open)
                if (close == null) {
                    violations += Violation(file, line, Rule.SCANNER_MISMATCH, "unbalanced StartupTrace call: " + excerptOf(code, match.range.first, code.length))
                    continue
                }
                traceCalls++
                inspect(file, line, excerptOf(code, match.range.first, close + 1), code.substring(open + 1, close), policy, messageMustBeLiteral = false, violations)
            }

            // Cross-check with a naive count: every `Logger.<member>` must have been parsed as a d, w or e call.
            val members = LOGGER_MEMBER.findAll(masked).count()
            if (members != calls + unbalanced) {
                violations += Violation(
                    file, 1, Rule.SCANNER_MISMATCH,
                    "$members Logger member accesses but $calls parsed Logger.d/w/e calls: " +
                        LOGGER_MEMBER.findAll(masked)
                            .filterNot { LOGGER_CALL.find(masked, it.range.first)?.range?.first == it.range.first }
                            .joinToString { "'" + it.value.replace(Regex("""\s+"""), "") + "' at line " + lineOf(code, it.range.first) },
                )
            }

            if (!policy.consoleAllowed) {
                val lines = CONSOLE_OUTPUT.findAll(masked).map { lineOf(masked, it.range.first) }.distinct()
                for (line in lines) {
                    val text = code.lineSequence().drop(line - 1).firstOrNull().orEmpty().trim()
                    violations += Violation(file, line, Rule.CONSOLE_OUTPUT, text.take(140))
                }
            }
            return Result(violations, calls, traceCalls)
        }

        private fun excerptOf(code: String, from: Int, to: Int): String =
            code.substring(from, minOf(to, from + 140)).replace(Regex("""\s+"""), " ")

        private fun inspect(
            file: String,
            line: Int,
            excerpt: String,
            body: String,
            policy: Policy,
            messageMustBeLiteral: Boolean,
            out: MutableList<Violation>,
        ) {
            val fragments = fragmentsOf(body)
            val outside = fragments.joinToString(" ")

            if (EXCEPTION_MESSAGE.containsMatchIn(outside)) out += Violation(file, line, Rule.EXCEPTION_MESSAGE, excerpt)
            if (RAW_BYTES.containsMatchIn(outside)) out += Violation(file, line, Rule.RAW_BYTES, excerpt)
            if (STACK_TRACE.containsMatchIn(outside)) out += Violation(file, line, Rule.STACK_TRACE, excerpt)
            if (fragments.any { sensitiveTokens(it).isNotEmpty() }) out += Violation(file, line, Rule.SENSITIVE_VALUE, excerpt)

            val bare = templatesOf(body).map { it.trim() }.filter { isBareTemplate(it) && it !in policy.bareTemplatesAllowed }
            if (bare.isNotEmpty()) out += Violation(file, line, Rule.BARE_TEMPLATE, "${bare.joinToString { "\${$it}" }} in $excerpt")

            if (messageMustBeLiteral) {
                val message = secondArgument(body)
                val literal = message != null && message.startsWith("\"")
                val allowedBuildString = message != null && policy.buildStringAllowed && BUILD_STRING.containsMatchIn(message)
                if (!literal && !allowedBuildString) out += Violation(file, line, Rule.NON_LITERAL_MESSAGE, excerpt)
            }
        }

        private val BUILD_STRING = Regex("""^buildString\s*\{""")

        /** The text after the first top-level comma of [body] (the message of a `Logger` call), or null. */
        private fun secondArgument(body: String): String? {
            var depth = 0
            var i = 0
            while (i < body.length) {
                when (body[i]) {
                    '"' -> {
                        i = skipString(body, i)
                        continue
                    }
                    '\'' -> {
                        i = skipCharLiteral(body, i)
                        continue
                    }
                    '(', '[', '{' -> depth++
                    ')', ']', '}' -> depth--
                    ',' -> if (depth == 0) return body.substring(i + 1).trimStart()
                }
                i++
            }
            return null
        }

        /** True when [expression] prints a whole object: a bare name or chain of [BARE_WORDS], `x.toString()`, `x.cause`. */
        private fun isBareTemplate(expression: String): Boolean {
            if (expression.endsWith(".toString()")) return true
            if (!BARE_CHAIN.matches(expression)) return false
            val last = expression.split(CHAIN_SEPARATOR).last()
            return last.lowercase() in BARE_WORDS || lastWord(last) in BARE_WORDS
        }

        private fun lastWord(token: String): String = CAMEL_BOUNDARY.split(token).last().lowercase()

        private fun lineOf(text: String, index: Int): Int = text.substring(0, index).count { it == '\n' } + 1

        /** [code] with the inside of every string and character literal replaced by spaces (newlines kept). */
        private fun maskLiterals(code: String): String {
            val out = StringBuilder(code.length)
            var i = 0
            while (i < code.length) {
                val c = code[i]
                if (c == '"' || c == '\'') {
                    val end = if (c == '"') skipString(code, i) else skipCharLiteral(code, i)
                    for (j in i until end) out.append(if (code[j] == '\n') '\n' else ' ')
                    i = end
                } else {
                    out.append(c)
                    i++
                }
            }
            return out.toString()
        }

        private fun sensitiveTokens(fragment: String): List<String> {
            val cleaned = removeSafeWrappers(fragment)
            return IDENTIFIER.findAll(cleaned)
                .filter { isSensitive(it.value) }
                // The size of a value is a size, not the value: `credentialId.size`.
                .filterNot { SIZE_ACCESS.containsMatchIn(cleaned.substring(it.range.last + 1)) }
                .map { it.value }
                .toList()
        }

        private fun isSensitive(token: String): Boolean {
            if (token == token.uppercase()) return false // CONSTANT: fixed text
            if (token in SAFE_TOKENS) return false
            return token.lowercase() in SENSITIVE_WORDS || lastWord(token) in SENSITIVE_WORDS
        }

        /** Deletes every [SAFE_WRAPPERS] call: what it wraps is allowed. Fragments hold no string literal text. */
        private fun removeSafeWrappers(fragment: String): String {
            var text = fragment
            while (true) {
                val (start, wrapper) = SAFE_WRAPPERS
                    .map { text.indexOf(it) to it }
                    .filter { it.first >= 0 }
                    .minByOrNull { it.first }
                    ?: return text
                val open = start + wrapper.length - 1
                var depth = 0
                var end = -1
                for (i in open until text.length) {
                    when (text[i]) {
                        '(' -> depth++
                        ')' -> {
                            depth--
                            if (depth == 0) {
                                end = i
                                break
                            }
                        }
                    }
                }
                text = if (end < 0) text.substring(0, start) else text.substring(0, start) + " " + text.substring(end + 1)
            }
        }

        /**
         * The code of [text] outside string literals, at every nesting level: the text between
         * literals, plus, recursively, each `${expression}` and each `$identifier` found inside a
         * literal. The fixed text of a literal is dropped.
         */
        fun fragmentsOf(text: String): List<String> {
            val result = mutableListOf<String>()
            val outside = StringBuilder()
            var i = 0
            while (i < text.length) {
                val c = text[i]
                when {
                    c == '"' -> i = readLiteral(text, i, result)
                    c == '\'' -> {
                        i = skipCharLiteral(text, i)
                        outside.append(' ')
                    }
                    else -> {
                        outside.append(c)
                        i++
                    }
                }
            }
            result += outside.toString()
            return result
        }

        /**
         * Every template of the string literals of [text], as written: the `expression` of each
         * `${expression}`, the identifier of each `$identifier`, at every nesting level.
         */
        fun templatesOf(text: String): List<String> {
            val result = mutableListOf<String>()
            var i = 0
            while (i < text.length) {
                val c = text[i]
                i = when (c) {
                    '"' -> collectTemplates(text, i, result)
                    '\'' -> skipCharLiteral(text, i)
                    else -> i + 1
                }
            }
            return result
        }

        private fun collectTemplates(text: String, start: Int, out: MutableList<String>): Int {
            val raw = text.startsWith("\"\"\"", start)
            var i = start + if (raw) 3 else 1
            while (i < text.length) {
                val c = text[i]
                when {
                    raw && text.startsWith("\"\"\"", i) -> return rawEnd(text, i)
                    !raw && c == '"' -> return i + 1
                    !raw && c == '\\' -> i += 2
                    c == '$' && i + 1 < text.length && text[i + 1] == '{' -> {
                        val end = skipTemplate(text, i + 2)
                        val expression = text.substring(i + 2, maxOf(i + 2, end - 1))
                        out += expression
                        out += templatesOf(expression)
                        i = end
                    }
                    c == '$' && i + 1 < text.length && (text[i + 1].isLetter() || text[i + 1] == '_') -> {
                        var j = i + 1
                        while (j < text.length && (text[j].isLetterOrDigit() || text[j] == '_')) j++
                        out += text.substring(i + 1, j)
                        i = j
                    }
                    else -> i++
                }
            }
            return text.length
        }

        /** Reads the literal starting at [start], adds the code of its templates to [out], returns the index after it. */
        private fun readLiteral(text: String, start: Int, out: MutableList<String>): Int {
            val raw = text.startsWith("\"\"\"", start)
            var i = start + if (raw) 3 else 1
            while (i < text.length) {
                val c = text[i]
                when {
                    raw && text.startsWith("\"\"\"", i) -> return rawEnd(text, i)
                    !raw && c == '"' -> return i + 1
                    !raw && c == '\\' -> i += 2
                    c == '$' && i + 1 < text.length && text[i + 1] == '{' -> {
                        val end = skipTemplate(text, i + 2)
                        out += fragmentsOf(text.substring(i + 2, maxOf(i + 2, end - 1)))
                        i = end
                    }
                    c == '$' && i + 1 < text.length && (text[i + 1].isLetter() || text[i + 1] == '_') -> {
                        var j = i + 1
                        while (j < text.length && (text[j].isLetterOrDigit() || text[j] == '_')) j++
                        out += text.substring(i + 1, j)
                        i = j
                    }
                    else -> i++
                }
            }
            return text.length
        }

        /**
         * [i] is the first quote of the closing run of a raw string. Kotlin closes the literal on the
         * LAST three quotes of the run, so `"""say "hi""""` is the text `say "hi"`: the run is consumed whole.
         */
        private fun rawEnd(text: String, i: Int): Int {
            var j = i
            while (j < text.length && text[j] == '"') j++
            return j
        }

        /** [start] is just after `${`; returns the index just after the matching `}`. */
        private fun skipTemplate(text: String, start: Int): Int {
            var depth = 1
            var i = start
            while (i < text.length) {
                when (text[i]) {
                    '"' -> i = skipString(text, i)
                    '\'' -> i = skipCharLiteral(text, i)
                    '{' -> {
                        depth++
                        i++
                    }
                    '}' -> {
                        depth--
                        i++
                        if (depth == 0) return i
                    }
                    else -> i++
                }
            }
            return text.length
        }

        /** [start] is the opening quote; returns the index just after the closing one. */
        private fun skipString(text: String, start: Int): Int {
            val raw = text.startsWith("\"\"\"", start)
            var i = start + if (raw) 3 else 1
            while (i < text.length) {
                val c = text[i]
                when {
                    raw && text.startsWith("\"\"\"", i) -> return rawEnd(text, i)
                    !raw && c == '"' -> return i + 1
                    !raw && c == '\\' -> i += 2
                    c == '$' && i + 1 < text.length && text[i + 1] == '{' -> i = skipTemplate(text, i + 2)
                    else -> i++
                }
            }
            return text.length
        }

        private fun skipCharLiteral(text: String, start: Int): Int {
            if (start + 1 >= text.length) return text.length
            if (text[start + 1] == '\\') {
                val end = text.indexOf('\'', start + 3)
                return if (end < 0) text.length else end + 1
            }
            return minOf(start + 3, text.length)
        }

        /** [open] is the index of `(`; returns the index of the matching `)`, or null. */
        private fun matchingParen(text: String, open: Int): Int? {
            var depth = 0
            var i = open
            while (i < text.length) {
                when (text[i]) {
                    '"' -> {
                        i = skipString(text, i)
                        continue
                    }
                    '\'' -> {
                        i = skipCharLiteral(text, i)
                        continue
                    }
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
                i++
            }
            return null
        }

        /**
         * [source] with every comment replaced by spaces (newlines kept, so line numbers hold).
         * String and character literals are skipped as a whole: `"http://x"` is not a comment.
         */
        fun blankComments(source: String): String {
            val out = StringBuilder(source.length)
            var i = 0
            while (i < source.length) {
                val c = source[i]
                when {
                    c == '"' -> {
                        val end = skipString(source, i)
                        out.append(source, i, end)
                        i = end
                    }
                    c == '\'' -> {
                        val end = skipCharLiteral(source, i)
                        out.append(source, i, end)
                        i = end
                    }
                    c == '/' && source.startsWith("//", i) -> {
                        while (i < source.length && source[i] != '\n') {
                            out.append(' ')
                            i++
                        }
                    }
                    c == '/' && source.startsWith("/*", i) -> {
                        var depth = 0
                        while (i < source.length) {
                            if (source.startsWith("/*", i)) {
                                depth++
                                out.append("  ")
                                i += 2
                            } else if (source.startsWith("*/", i)) {
                                depth--
                                out.append("  ")
                                i += 2
                                if (depth == 0) break
                            } else {
                                out.append(if (source[i] == '\n') '\n' else ' ')
                                i++
                            }
                        }
                    }
                    else -> {
                        out.append(c)
                        i++
                    }
                }
            }
            return out.toString()
        }
    }
}
