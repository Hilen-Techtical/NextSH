// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.util

/**
 * Safety net that masks values which must never reach a log line.
 *
 * Log policy (all levels, debug included): a log line carries fixed text, operation and
 * state names, counts, sizes, durations, numeric codes, algorithm and transport names and
 * exception CLASS names only. Call sites are responsible for that ([LogId] for ids,
 * [errorClass] for throwables). This scrubber only catches what slips through, by shape:
 *
 * | Shape, in the order the rules are applied | Token |
 * |---|---|
 * | PEM block, even unterminated | `<pem>` |
 * | `scheme://...`, `file:/...` | `<uri>` |
 * | `secret=value` (password, passphrase, secret, token, pin, pass, cookie, authorization, master / access / signing key, jwt, psk, ...), also JSON (escaped or dotted keys), nested, `password: x` and `Authorization: ...` | `key=<secret>` |
 * | `id=value`, `syncId=`, `device_id=`, `uuid=` (a [LogId] value is kept) | `key=<id>` |
 * | `label=`, `host=`, `user=`, `name=`, `tunnelName=`, `sshPort=`, `path=`, `alias=`, `email=`, ... | `key=<value>` |
 * | `SHA256:...`, `MD5:...`, colon hex of 6+ pairs (fingerprint, MAC) | `<fp>` |
 * | UUID | `<id>` |
 * | `user@host`, Unicode included (except `@openssh.com`, `@libssh.org`, `@ssh.com` algorithm names, and `Object@1a2b3c` hash codes after a class name) | `<host>` |
 * | IPv4 / IPv6 address, with optional port, brackets and zone | `<ip>` |
 * | absolute path: POSIX, `~/`, Windows drive, UNC, `\\?\` and `\\.\`; a following `:port` is absorbed | `<path>` |
 * | dotted host with port, `localhost:port`, Unicode labels included | `<host>` |
 * | dotted host ending in a common or LAN suffix (`.com`, `.fr`, `.lan`, `.local`, ...) | `<host>` |
 * | hex run of [MIN_HEX_RUN]+ chars with a digit and a letter | `<hex>` |
 * | base64 run of [MIN_BASE64_RUN]+ chars (or [MIN_MARKED_BASE64_RUN]+ with `+`, `/` or `=`) mixing digits, upper and lower case | `<b64>` |
 * | line feed, carriage return, tab, other C0 / C1 controls, Unicode line separators, bidi controls | `\n`, `\r`, space, `\uXXXX` |
 *
 * Flags and counts are not secrets: `hasPassword=0`, `needsPin=1`, `pin=true`,
 * `credentials=3` are kept. So are `className=`, `algorithmName=` and other technical names.
 *
 * What survives on purpose: words, Kotlin class names, stack frames such as
 * `Foo.bar(Foo.kt:42)`, numbers, durations, versions such as `1.0.0`, short codes such as
 * HRESULT `0x80090027` or SW `6A80`, [LogId] fingerprints, algorithm names such as
 * `sk-ssh-ed25519@openssh.com`, transport strings such as `HID:0, PC/SC:0`.
 *
 * Known limits: a single label host without port (`myserver`), a relative path, a
 * free-text label or a username alone cannot be recognised by shape. A last path segment
 * with spaces keeps its tail unless it ends with a file extension (`C:\x\My notes` gives
 * `<path> notes`). An id or personal value runs to the next delimiter and may take the
 * rest of the line with it. Never log such values in the first place.
 *
 * No pattern backtracks catastrophically, the input is capped at [MAX_INPUT_CHARS] and
 * every entry point fails closed.
 *
 * The patterns are compiled on first use. An application that wants that cost off its
 * first real log call may call `LogScrubber.clean("x=y")` once at startup; the shared
 * module does not do it for them.
 */
object LogScrubber {

    const val IP = "<ip>"
    const val HOST = "<host>"
    const val PATH = "<path>"
    const val URI = "<uri>"
    const val FINGERPRINT = "<fp>"
    const val PEM = "<pem>"
    const val ID = "<id>"
    const val BASE64 = "<b64>"
    const val HEX = "<hex>"
    const val SECRET = "<secret>"
    const val VALUE = "<value>"

    /** Replacement for a whole line that carries an unmistakable secret (see [containsSecret]). */
    const val REDACTED_LINE = "[REDACTED, sensitive data]"

    /** Longer inputs are cut on a whitespace boundary (partial last word dropped) before scrubbing. */
    const val MAX_INPUT_CHARS = 16_384

    /** Hex runs shorter than this survive (HRESULT, SW, hash codes, [LogId] output). */
    const val MIN_HEX_RUN = 16

    /** Plain base64 runs shorter than this survive (long class names stay readable). */
    const val MIN_BASE64_RUN = 32

    /** Minimum length of a base64 run that contains `+`, `/` or `=` padding. */
    const val MIN_MARKED_BASE64_RUN = 16

    private const val TRUNCATED = " [truncated]"

    // ── API ───────────────────────────────────────────────────────────────────────────
    //
    // Every entry point fails closed: whatever goes wrong while scrubbing, including a
    // pattern that does not compile on the running platform (the rules live in [Rules],
    // initialised on first use, so that such a failure is caught here), the caller gets
    // REDACTED_LINE (or true for containsSecret) and never an exception or the raw text.

    /**
     * Returns [text] with every recognised value replaced by its token and its control
     * characters neutralised (see [neutralizeControlChars]), or [REDACTED_LINE] if
     * scrubbing fails. Scrubbing an already scrubbed text is expected to leave it
     * unchanged (tested on representative lines), not guaranteed for every input.
     */
    fun scrub(text: String): String = failClosed(REDACTED_LINE) {
        if (text.isEmpty()) text else Rules.scrub(bound(text))
    }

    /**
     * True when [text] carries an unmistakable secret: a PEM block (even unterminated) or
     * a `password=`, `passphrase=`, `secret=`, `token=`, `pin=` ... assignment (also in
     * JSON form, nested in another value or object) with a non trivial value. Such a line
     * is dropped as a whole. True as well if the check itself fails.
     */
    fun containsSecret(text: String): Boolean = failClosed(true) { Rules.containsSecret(bound(text)) }

    /**
     * What a logger prints for [message]: [REDACTED_LINE] when [containsSecret], else
     * [scrub], both on the input bounded to [MAX_INPUT_CHARS]. Never throws.
     */
    fun clean(message: String): String = failClosed(REDACTED_LINE) {
        val bounded = bound(message)
        if (Rules.containsSecret(bounded)) REDACTED_LINE else Rules.scrub(bounded)
    }

    // ── Internals ─────────────────────────────────────────────────────────────────────

    private fun bound(text: String): String {
        if (text.length <= MAX_INPUT_CHARS) return text
        val cut = text.substring(0, MAX_INPUT_CHARS)
        // Drop the partial last word: half a token would no longer match its pattern.
        val lastSpace = cut.indexOfLast { it.isWhitespace() }
        val kept = if (lastSpace > 0) cut.substring(0, lastSpace) else ""
        return kept + TRUNCATED
    }

    /**
     * Patterns and the code that applies them, kept apart from the public object so that a
     * failure while compiling them surfaces inside [failClosed] instead of in the caller.
     *
     * Android runs these patterns on ICU, the JVM on java.util.regex. Only features both
     * support are used: possessive quantifiers, bounded lookbehind, `\p{L}` / `\p{N}`
     * classes, inline `(?i)`, back reference `\1`. `{`, `}`, `[` and `]` are escaped inside
     * character classes (ICU reads `[` there as a nested set), `\w`, `\d` and `\b` are
     * avoided (ASCII on the JVM, Unicode on ICU).
     */
    private object Rules {

        // ── Patterns (order of application matters, see scrub) ──────────────────────────
        //
        // java.util.regex recurses once per iteration of a greedy or lazy GROUP repetition, so
        // an unbounded `(?:...)+` overflows the stack on a long input. Group repetitions are
        // therefore bounded ({1,16}, {5,63}) or possessive (paths); character class runs are free.

        val PEM_BLOCK = Regex("""-----BEGIN [A-Z0-9 ]{1,64}-----[\s\S]*?-----END [A-Z0-9 ]{1,64}-----""")
        val PEM_OPEN = Regex("""-----BEGIN [A-Z0-9 ]{1,64}-----[\s\S]*""")

        // Boundary class: letters, digits, underscore, Unicode aware on every platform. Java's \w
        // is ASCII only while Android's ICU \w is Unicode, so \w, \d and \b are never used here.
        const val W = """\p{L}\p{N}_"""

        val URI_PATTERN = Regex("""(?i)(?<![$W])(?:[a-z][a-z0-9+.-]{1,31}://|file:/)[^\s"'<>)]*""")

        val FINGERPRINT_PREFIXED = Regex("""(?i)(?<![$W])(?:SHA(?:1|224|256|384|512)|MD5):[A-Za-z0-9+/=:]{16,}""")
        val FINGERPRINT_COLON_HEX = Regex("""(?<![0-9A-Fa-f:])[0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5,63}(?![0-9A-Fa-f:])""")

        val UUID_PATTERN = Regex(
            """(?<![0-9A-Fa-f])[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}(?![0-9A-Fa-f])""",
        )

        /** `user@host` (group 1 / 2), Unicode aware, keeping SSH algorithm names (`@openssh.com`, ...). */
        val USER_AT_HOST = Regex(
            """(?<![$W.+%-])([\p{L}\p{N}._%+-]{1,64})@(?!(?:openssh\.com|libssh\.org|ssh\.com)(?![$W.-]))([\p{L}\p{N}-]{1,63}(?:\.[\p{L}\p{N}-]{1,63}){0,16})""",
        )
        val HASH_CODE = Regex("""[0-9a-f]{1,8}""")

        val IPV6_CANDIDATE = Regex(
            """(?<![$W:.%])(?=[0-9A-Fa-f]*:[0-9A-Fa-f]*:)([0-9A-Fa-f:]{2,39})((?<=:)(?:[0-9]{1,3}\.){3}[0-9]{1,3})?(%[A-Za-z0-9_.-]{1,32})?(?![$W:]|\.[0-9])""",
        )
        val IPV4 = Regex(
            """(?<![$W.])(?:(?:25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])\.){3}(?:25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])(?::[0-9]{1,5})?(?![$W]|\.[0-9])""",
        )

        // One path segment: no separator, no whitespace, no quote or bracket. `<` and `>` are
        // allowed so that a token already placed inside a path (`/var/<ip>/x`) is absorbed. A
        // segment may hold single spaces only when a separator follows, so "C:\Users\Jean Dupont\x"
        // is fully masked while "C:\Users\me failed" keeps " failed". The last segment may hold
        // spaces only when it ends with a file extension ("My Keys.txt"), up to 8 words.
        // Possessive quantifiers keep the scan linear, the {1,8} repetition bounds the backtracking.
        const val SEG = """[^\\/\s"'|()\[\]\{\},;:]"""
        const val LAST_SEG = """(?:$SEG++(?: $SEG++){1,8}(?<=\.[A-Za-z][A-Za-z0-9]{0,4})|$SEG*+)"""
        const val SEGMENTS_BS = """(?:$SEG++(?: $SEG++)*+[\\/])*+$LAST_SEG"""
        const val SEGMENTS_FS = """(?:$SEG++(?: $SEG++)*+/)*+$LAST_SEG"""
        const val PATH_START = """(?<![^\s(\[\{'"=,;:])"""
        val LONG_PATH = Regex("""$PATH_START\\\\[?.]\\(?:[A-Za-z]:[\\/])?$SEGMENTS_BS""")
        val UNC_PATH = Regex("""$PATH_START\\\\(?=$SEG)$SEGMENTS_BS""")
        val DRIVE_PATH = Regex("""(?<![$W])[A-Za-z]:[\\/]$SEGMENTS_BS""")
        val POSIX_PATH = Regex("""$PATH_START~?/(?=$SEG)$SEGMENTS_FS""")

        const val LABEL = """[\p{L}\p{N}](?:[\p{L}\p{N}-]{0,61}[\p{L}\p{N}])?"""
        const val HOST_START = """(?<![$W.@/\\-])"""
        val HOST_PORT = Regex("""$HOST_START((?:$LABEL\.){1,16}(\p{L}[\p{L}\p{N}-]{0,62})|localhost):[0-9]{1,5}(?![$W])""")
        val FQDN = Regex(
            """$HOST_START(?:$LABEL\.){1,16}(?:com|net|org|edu|gov|io|dev|cloud|info|biz|eu|fr|de|uk|be|ch|ca|us|nl|es|it|local|localdomain|lan|home|internal|intranet|corp|arpa|test|example|invalid|onion|lab|priv|private)(?![$W-]|\.[$W])""",
        )

        val BRACKETED_IP = Regex("""\[<ip>\](?::[0-9]{1,5})?""")
        val TOKEN_PORT = Regex("""(<ip>|<host>|<path>):[0-9]{1,5}(?![0-9])""")
        val USER_BEFORE_TOKEN = Regex("""(?<![$W.+%-])[\p{L}\p{N}._%+-]{1,64}@(<ip>|<host>)""")
        val HEX_RUN = Regex("""(?<![A-Za-z0-9])(?:0[xX])?[0-9A-Fa-f]{$MIN_HEX_RUN,}(?![A-Za-z0-9])""")
        val BASE64_RUN = Regex("""(?<![A-Za-z0-9+/_-])[A-Za-z0-9+/_-]{$MIN_MARKED_BASE64_RUN,}={0,2}(?![A-Za-z0-9+/_=-])""")

        /** `host:port` whose suffix is a source file extension is a stack frame, not a host. */
        val SOURCE_FILE_EXTENSIONS = setOf(
            "kt", "kts", "java", "scala", "groovy", "gradle", "js", "ts", "py",
            "c", "cc", "cpp", "h", "hpp", "m", "mm", "swift", "rs", "go",
        )

        fun scrub(text: String): String {
            var s = text
            s = PEM_BLOCK.replace(s, PEM)
            s = PEM_OPEN.replace(s, PEM)
            s = URI_PATTERN.replace(s, URI)
            s = LogAssignments.mask(s)
            s = FINGERPRINT_PREFIXED.replace(s, FINGERPRINT)
            s = FINGERPRINT_COLON_HEX.replace(s, FINGERPRINT)
            s = UUID_PATTERN.replace(s, ID)
            s = USER_AT_HOST.replace(s) { m -> if (isObjectHashCode(m.groupValues[1], m.groupValues[2])) m.value else HOST }
            // Addresses before paths, so that "/10.0.0.1:22" loses its port with the address.
            s = IPV6_CANDIDATE.replace(s) { m ->
                if (isIpv6(m.groupValues[1], hasV4Tail = m.groupValues[2].isNotEmpty())) IP else m.value
            }
            s = IPV4.replace(s, IP)
            s = LONG_PATH.replace(s, PATH)
            s = UNC_PATH.replace(s, PATH)
            s = DRIVE_PATH.replace(s, PATH)
            s = POSIX_PATH.replace(s, PATH)
            s = HOST_PORT.replace(s) { m ->
                if (m.groupValues[2].lowercase() in SOURCE_FILE_EXTENSIONS) m.value else HOST
            }
            s = FQDN.replace(s, HOST)
            s = BRACKETED_IP.replace(s, IP)
            s = TOKEN_PORT.replace(s) { m -> m.groupValues[1] }
            s = USER_BEFORE_TOKEN.replace(s) { m -> m.groupValues[1] }
            s = HEX_RUN.replace(s) { m -> if (looksLikeHex(m.value)) HEX else m.value }
            s = BASE64_RUN.replace(s) { m -> if (looksLikeBase64(m.value)) BASE64 else m.value }
            // Last, so that the rules above still see real line breaks as delimiters.
            return neutralizeControlChars(s)
        }

        fun containsSecret(text: String): Boolean =
            PEM_OPEN.containsMatchIn(text) || LogAssignments.containsSecret(text)

        /** `java.lang.Object@6d06d69c`, `Foo$Bar@1a2b`: a JVM identity hash code, not an address. */
        fun isObjectHashCode(local: String, domain: String): Boolean =
            HASH_CODE.matches(domain) && (local.contains('.') || local.first().isUpperCase())

        fun looksLikeHex(run: String): Boolean {
            val digits = if (run.startsWith("0x") || run.startsWith("0X")) run.substring(2) else run
            return digits.any { it in '0'..'9' } && digits.any { it in 'a'..'f' || it in 'A'..'F' }
        }

        fun looksLikeBase64(run: String): Boolean {
            val marked = run.any { it == '+' || it == '/' || it == '=' }
            if (run.length < MIN_BASE64_RUN && !marked) return false
            return run.any { it in '0'..'9' } && run.any { it in 'A'..'Z' } && run.any { it in 'a'..'z' }
        }

        /**
         * Validates an IPv6 candidate made of hex groups and colons ([core]), optionally
         * followed by an embedded IPv4 tail that counts as two groups.
         */
        fun isIpv6(core: String, hasV4Tail: Boolean): Boolean {
            val s = if (hasV4Tail) core + "0:0" else core
            if (s.contains(":::")) return false
            val compressed = s.indexOf("::")
            if (compressed >= 0 && s.indexOf("::", compressed + 1) >= 0) return false
            if (s.startsWith(":") && !s.startsWith("::")) return false
            if (s.endsWith(":") && !s.endsWith("::")) return false
            val groups = s.split(':').filter { it.isNotEmpty() }
            if (groups.any { it.length > 4 }) return false
            return if (compressed >= 0) groups.size in 1..7 else groups.size == 8
        }
    }
}

/**
 * Makes [text] safe to print on one log line: a line feed becomes `\n`, a carriage return
 * `\r`, a tab a space, and any other C0 or C1 control, DEL, Unicode line or paragraph
 * separator, bidirectional control, invisible format character (zero width space and
 * joiners, word joiner, invisible operators, byte order mark, Arabic letter mark) and Unicode
 * tag character (U+E0000 to U+E007F, as its two UTF-16 units) becomes `\uXXXX`. A message can
 * then no longer forge a second journald or logcat line, drive the terminal that displays
 * it, or hide text from the person reading it.
 */
internal fun neutralizeControlChars(text: String): String {
    if (text.none { isUnsafeChar(it) || it == TAG_HIGH_SURROGATE }) return text
    val out = StringBuilder(text.length + 16)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c == '\n' -> out.append("\\n")
            c == '\r' -> out.append("\\r")
            c == '\t' -> out.append(' ')
            isUnsafeChar(c) -> out.appendEscaped(c)
            c == TAG_HIGH_SURROGATE && text.getOrNull(i + 1)?.code in TAG_LOW_SURROGATES -> {
                out.appendEscaped(c).appendEscaped(text[i + 1])
                i++
            }
            else -> out.append(c)
        }
        i++
    }
    return out.toString()
}

/** High surrogate of the Unicode tag block U+E0000 to U+E007F. */
private const val TAG_HIGH_SURROGATE = '\uDB40'
private val TAG_LOW_SURROGATES = 0xDC00..0xDC7F

private fun StringBuilder.appendEscaped(c: Char): StringBuilder =
    append("\\u").append(c.code.toString(16).uppercase().padStart(4, '0'))

private fun isUnsafeChar(c: Char): Boolean {
    val code = c.code
    return code < 0x20 || code in 0x7F..0x9F || code == 0x061C || code in 0x200B..0x200F ||
        code == 0x2028 || code == 0x2029 || code in 0x202A..0x202E || code in 0x2060..0x2064 ||
        code in 0x2066..0x2069 || code == 0xFEFF
}
/**
 * Runs [block] and returns [fallback] if it throws anything, errors included
 * (ExceptionInInitializerError, StackOverflowError, ...). Logging helpers use it so that a
 * log call never throws and never falls back to printing the raw input.
 */
@Suppress("TooGenericExceptionCaught")
internal inline fun <T> failClosed(fallback: T, block: () -> T): T =
    try {
        block()
    } catch (t: Throwable) {
        fallback
    }
