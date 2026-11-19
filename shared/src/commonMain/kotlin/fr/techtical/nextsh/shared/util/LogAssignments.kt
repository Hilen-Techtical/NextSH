// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.util

/**
 * `key=value`, `"key": value`, `\"key\": value` (inside an escaped JSON string) and, for a
 * secret name or an HTTP header only, `key: value` pairs for [LogScrubber].
 *
 * The regex matches a key and its separator only. The value is then read by hand, so a
 * neutral key never swallows its value and a pair nested in another value is still seen:
 * `config=password=x`, `params=a=b&password=x`, `msg="said password=x"`,
 * `{"auth":{"password":"x"}}`, `{"data":[{"password":"x"}]}`.
 *
 * Value of a classified key:
 * - quoted (`"..."` or `'...'`, same line, up to 512 chars): the quoted text, quotes kept;
 * - structured (`{...}` or `[...]`, up to 4096 chars): the balanced block;
 * - otherwise, for a secret key: up to whitespace or `, ; ) ] }`;
 * - otherwise, for an id or personal key: up to `, ; ] }`, the end of the line, a `)` that
 *   closes nothing opened in the value, the next ` key=` or a trailing ` (ErrorClass)`
 *   idiom, so `label=Prod (EU) DB 1, port=22` masks `Prod (EU) DB 1` as a whole;
 * - an unterminated quote or block is masked to the end of the line (fail safe).
 *
 * Up to 64 blanks after the separator are skipped, line breaks included for a secret key.
 * A [LogId] fingerprint, bare or quoted, is kept under an id key.
 */
internal object LogAssignments {

    enum class Kind { NEUTRAL, SECRET, ID, PERSONAL }

    private class Value(
        val start: Int,
        val end: Int,
        val content: String,
        val quote: Char?,
        /** Value written `\"...\"`, inside an escaped JSON string. */
        val escaped: Boolean = false,
    )

    private enum class Form { QUOTED, EQUALS, COLON }

    private class KeyMatch(val key: String, val form: Form, val blankAfterColon: Boolean)

    private const val MAX_QUOTED = 512
    private const val MAX_STRUCTURED = 4096
    private const val SECRET_STOP = ",;)]}"
    private const val FIELD_STOP = ",;)]}\r\n"

    private val OWN_TOKENS = listOf(LogScrubber.ID, LogScrubber.VALUE, LogScrubber.SECRET)

    /** ` (TransportException<-SocketTimeoutException)`: an [errorClass] idiom after a value. */
    private val ERROR_CHAIN = Regex(
        """[ \t]*\(\p{Lu}[A-Za-z0-9_$]{0,80}(?:Exception|Error|Throwable)(?:<-(?:\p{Lu}[A-Za-z0-9_$]{0,80}|\.\.\.)){0,8}\)""",
    )

    // Start of a bare key: not glued to a letter, digit, `_` or backslash, except right after
    // a literal `\n`, `\r` or `\t` escape (`step 1\nlabel=x`), or after a backslash that does
    // not start such an escape (`dir\password=x`).
    private const val KEY_START = """(?:(?<=\\[nrt])|(?<![\p{L}\p{N}_\\])|(?<=\\)(?![nrt]))"""

    // Group 2 / 3: quoted key (dots allowed: `spring.datasource.password`) and colon.
    // Group 4 / 5: escaped quoted key, `{\"password\":\"x\"}` inside a JSON string.
    // Group 6 / 7: bare key and equal sign.
    // Group 8 / 9 / 10: bare key, colon and the blanks after it: `password: x`, `Authorization: x`.
    private val KEY = Regex(
        """(["'])([A-Za-z_][A-Za-z0-9_.-]{0,63})\1(\s{0,3}:\s{0,3})""" +
            """|\\"([A-Za-z_][A-Za-z0-9_.-]{0,63})\\"(\s{0,3}:\s{0,3})""" +
            """|$KEY_START([A-Za-z_][A-Za-z0-9_-]{0,63})(\s{0,3}=\s{0,3})""" +
            """|$KEY_START([A-Za-z_][A-Za-z0-9_-]{0,63})(\s{0,3}:)(?!:)([ \t]{0,3})""",
    )

    /**
     * Matched as a plain suffix of the lower case key with `_`, `-` and `.` removed
     * (fail-safe): `sharedSecret`, `keyPassphrase`, `x-auth-token`, `aws_secret_key`.
     */
    private val SECRET_KEY_SUFFIXES = listOf(
        "password", "passwd", "pwd", "passphrase", "secret", "token", "credential",
        "privatekey", "privkey", "apikey", "secretkey", "sessionkey", "masterkey", "accesskey",
        "encryptionkey", "signingkey", "hmackey", "sharedkey", "vaultkey", "unlockkey",
        "recoverycode", "cookie", "authorization",
    )

    /** Secret keys matched as a whole word, so that `spin`, `bypass`, `compass` or `keks` survive. */
    private val SECRET_KEY_WORDS = listOf("pin", "pass", "pw", "pswd", "dek", "kek", "psk", "jwt", "sshpass")

    /** Secrets whose value runs to the end of the line: `Bearer x`, `a=b; c=d`. */
    private val LINE_SECRET_SUFFIXES = listOf("authorization", "cookie", "xapikey", "xauthtoken")

    /** HTTP headers carrying a secret, recognised in the `Name: value` form. */
    private val HEADER_NAMES = setOf("authorization", "proxyauthorization", "cookie", "setcookie", "xapikey", "xauthtoken")

    /**
     * Secret names exact enough to trust in the bare `name: value` form. `pass` and `pw` are
     * left out: `retry pass: 2` is an ordinary sentence.
     */
    private val PLAIN_SECRET_NAMES = (SECRET_KEY_SUFFIXES + SECRET_KEY_WORDS).toSet() - setOf("pass", "pw")

    /** Matched as a whole word (`syncId`, `device_id`, `uuid`) so that `valid` or `android` survive. */
    private val ID_KEY_WORDS = listOf("id", "ids", "uuid")

    /**
     * Matched as a whole word (`hostLabel`, `sshPort`, `errorMessage`) so that `ghost`,
     * `import` or `transport` survive. `message`, `msg` and `cause` hold free text that may
     * quote a host or a path, `terminal` and `reader` a device name. `reason` stays neutral:
     * it carries enum names.
     */
    private val PERSONAL_KEY_WORDS = listOf(
        "label", "host", "hostname", "user", "username", "login", "alias", "name", "devicename",
        "filename", "path", "dir", "email", "fingerprint", "address", "addr", "rpid", "port",
        "message", "msg", "cause", "terminal", "reader",
    )

    /** `<prefix>Name` keys that name code or protocol elements, not user data: `className`, `algorithmName`. */
    private val TECHNICAL_NAME_PREFIXES = setOf(
        "class", "simple", "method", "function", "field", "type", "enum", "package", "module", "loader",
        "thread", "tag", "algorithm", "alg", "cipher", "kex", "mac", "curve", "digest", "hash",
        "signature", "transport", "protocol", "scheme", "provider", "codec", "format", "state", "event",
        "command", "operation", "op", "action", "route", "screen", "task", "job", "worker", "feature",
        "permission", "channel", "mode", "level",
    )

    private val TRIVIAL_VALUES = setOf("true", "false", "null", "")

    /** A secret key holding exactly one of these is a flag, not a secret: `needsPin=1`, `hasPassword=0`. */
    private val FLAG_VALUES = setOf("0", "1")

    /** Returns [s] with the value of every secret, id and personal key replaced by its token. */
    fun mask(s: String): String {
        var m = KEY.find(s) ?: return s
        val out = StringBuilder(s.length)
        var cursor = 0
        while (true) {
            val km = keyMatch(m)
            val key = km.key
            val kind = kindOf(km)
            val valueStart = skipBlanks(s, m.range.last + 1, newlines = kind == Kind.SECRET)
            var resumeAt = m.range.last + 1
            if (kind != Kind.NEUTRAL && !(kind == Kind.ID && logIdAt(s, valueStart))) {
                val read = readValue(s, valueStart, kind, key)
                val v = if (read != null && kind != Kind.SECRET) coverNestedSecrets(s, read) else read
                if (v != null && !isTrivial(key, kind, v)) {
                    out.append(s, cursor, v.start).append(replacement(kind, v))
                    cursor = v.end
                    resumeAt = v.end
                }
            }
            m = KEY.find(s, resumeAt) ?: break
        }
        out.append(s, cursor, s.length)
        return out.toString()
    }

    /**
     * Widens a personal or id value so that it also covers the value of any secret key that
     * starts inside it and ends beyond it. `msg="password=\n\nx` or `msg="secret={"a":"x"}`:
     * the quoted text ends early, the secret it holds does not, and scrub must not print it.
     */
    private fun coverNestedSecrets(s: String, v: Value): Value {
        var end = v.end
        var m = KEY.find(s, v.start)
        while (m != null && m.range.first < end) {
            val km = keyMatch(m)
            if (kindOf(km) == Kind.SECRET) {
                val inner = readValue(s, skipBlanks(s, m.range.last + 1, newlines = true), Kind.SECRET, km.key)
                if (inner != null && !isTrivial(km.key, Kind.SECRET, inner) && inner.end > end) end = inner.end
            }
            m = m.next()
        }
        return if (end == v.end) v else Value(v.start, end, s.substring(v.start, end), null)
    }

    /** True when any secret key of [s], nested or not, carries a non trivial value. */
    fun containsSecret(s: String): Boolean {
        var m = KEY.find(s)
        while (m != null) {
            val km = keyMatch(m)
            val key = km.key
            if (kindOf(km) == Kind.SECRET) {
                val v = readValue(s, skipBlanks(s, m.range.last + 1, newlines = true), Kind.SECRET, key)
                if (v != null && !isTrivial(key, Kind.SECRET, v)) return true
            }
            m = m.next()
        }
        return false
    }

    fun classify(key: String): Kind = when {
        isSecretKey(key) || isPluralSecretKey(key) -> Kind.SECRET
        ID_KEY_WORDS.any { endsWithWord(key, it) } -> Kind.ID
        PERSONAL_KEY_WORDS.any { endsWithWord(key, it) } && !isTechnicalName(key) -> Kind.PERSONAL
        else -> Kind.NEUTRAL
    }

    /**
     * Kind of a matched key. The bare `name: value` form is common in plain sentences
     * (`makeCredential: HRESULT ...`, `storePassword: failed`), so it only counts for an
     * HTTP header or for an unmistakable secret name followed by a blank: `password: x`,
     * `token: x`, `db_password: x`. It never marks an id or personal value.
     */
    private fun kindOf(km: KeyMatch): Kind {
        val kind = classify(km.key)
        if (km.form != Form.COLON) return kind
        val name = normalized(km.key)
        if (name in HEADER_NAMES) return Kind.SECRET
        val plain = name in PLAIN_SECRET_NAMES || km.key.any { it == '_' || it == '-' }
        return if (kind == Kind.SECRET && km.blankAfterColon && plain) Kind.SECRET else Kind.NEUTRAL
    }

    private fun normalized(key: String) = key.lowercase().filter { it != '_' && it != '-' && it != '.' }

    private fun isSecretKey(key: String): Boolean {
        val lower = normalized(key)
        return SECRET_KEY_SUFFIXES.any { lower.endsWith(it) } || SECRET_KEY_WORDS.any { endsWithWord(key, it) }
    }

    /** `credentials`, `passwords`, `tokens`, `pins`: may hold a count. */
    private fun isPluralSecretKey(key: String): Boolean =
        !isSecretKey(key) && key.length > 1 && key.last().lowercaseChar() == 's' && isSecretKey(key.dropLast(1))

    private fun isTechnicalName(key: String): Boolean {
        val lower = key.lowercase()
        if (lower == "name" || !endsWithWord(key, "name")) return false
        return lower.removeSuffix("name").trimEnd('_', '-', '.') in TECHNICAL_NAME_PREFIXES
    }

    /**
     * True when [key] is [word] itself (any case) or ends with it as a camelCase, snake_case
     * kebab-case or dotted word: `sshPort`, `local_port`, `deviceID`, `db.user` but not
     * `import` or `valid`.
     */
    fun endsWithWord(key: String, word: String): Boolean {
        val lower = key.lowercase()
        if (lower == word) return true
        if (!lower.endsWith(word)) return false
        val start = key.length - word.length
        val first = key[start]
        val before = key[start - 1]
        return before == '_' || before == '-' || before == '.' || (first.isUpperCase() && !before.isUpperCase())
    }

    /**
     * Blanks between the separator and the value beyond the 3 the key regex takes, as in
     * column aligned dumps (`password=      x`). Line breaks too for a secret key, so that
     * `password=\n\n\n\nx` is still seen. At most 64 chars.
     */
    private fun skipBlanks(s: String, from: Int, newlines: Boolean): Int {
        var i = from
        while (i < s.length && i - from < 64 && (s[i] == ' ' || s[i] == '\t' || (newlines && (s[i] == '\n' || s[i] == '\r')))) i++
        return i
    }

    private fun keyMatch(m: MatchResult): KeyMatch {
        val g = m.groups
        g[2]?.let { return KeyMatch(it.value, Form.QUOTED, blankAfterColon = true) }
        g[4]?.let { return KeyMatch(it.value, Form.QUOTED, blankAfterColon = true) }
        g[6]?.let { return KeyMatch(it.value, Form.EQUALS, blankAfterColon = true) }
        return KeyMatch(g[8]?.value.orEmpty(), Form.COLON, blankAfterColon = !g[10]?.value.isNullOrEmpty())
    }

    private fun replacement(kind: Kind, v: Value): String {
        val token = when (kind) {
            Kind.SECRET -> LogScrubber.SECRET
            Kind.ID -> LogScrubber.ID
            else -> LogScrubber.VALUE
        }
        return when {
            v.escaped -> "\\\"$token\\\""
            v.quote != null -> "${v.quote}$token${v.quote}"
            else -> token
        }
    }

    /**
     * A value that cannot be a secret: `true`, `false`, `null`, empty, and for a secret key
     * also `0` / `1` (a flag) or a number under a plural key (a count: `credentials=3`).
     * A real `pin=1234` is not trivial.
     */
    private fun isTrivial(key: String, kind: Kind, v: Value): Boolean {
        val content = v.content.lowercase()
        if (content in TRIVIAL_VALUES) return true
        if (kind != Kind.SECRET || v.quote != null || v.escaped) return false
        if (content in FLAG_VALUES) return true
        return isPluralSecretKey(key) && content.all { it in '0'..'9' }
    }

    /**
     * A [LogId] fingerprint (8 lowercase hex chars) or [LogId.NONE] is already safe, bare or
     * quoted: `id=1a2b3c4d`, `id="1a2b3c4d"`, `"hostId":"1a2b3c4d"`, `hostId='1a2b3c4d'`.
     */
    private fun logIdAt(s: String, start: Int): Boolean {
        val quote = s.getOrNull(start)
        if (quote == '"' || quote == '\'') {
            val end = logIdEnd(s, start + 1)
            return end > 0 && s.getOrNull(end) == quote
        }
        val end = logIdEnd(s, start)
        return end > 0 && !isWordChar(s.getOrNull(end))
    }

    /** End of the LogId fingerprint or [LogId.NONE] starting at [start], or -1. */
    private fun logIdEnd(s: String, start: Int): Int {
        if (s.getOrNull(start) == '-') return start + 1
        if (start + LogId.LENGTH > s.length) return -1
        for (i in start until start + LogId.LENGTH) {
            if (s[i] !in '0'..'9' && s[i] !in 'a'..'f') return -1
        }
        return start + LogId.LENGTH
    }

    private fun isWordChar(c: Char?): Boolean = c != null && (c.isLetterOrDigit() || c == '_' || c == '-')

    private fun readValue(s: String, start: Int, kind: Kind, key: String): Value? {
        if (start >= s.length) return null
        val c = s[start]
        if (s.startsWith("\\\"", start)) {
            val close = closingEscapedQuote(s, start)
            if (close >= 0) return Value(start, close + 2, s.substring(start + 2, close), null, escaped = true)
            val eol = endOfLine(s, start)
            return Value(start, eol, s.substring(start, eol), null)
        }
        if (c == '"' || c == '\'') {
            val close = closingQuote(s, start, c)
            if (close < 0) {
                val eol = endOfLine(s, start)
                return Value(start, eol, s.substring(start, eol), null)
            }
            if (kind == Kind.SECRET && !endsValue(s, close + 1)) {
                // `password="ab"cd`: the secret goes on after the quote.
                val end = secretEnd(s, close + 1)
                return Value(start, end, s.substring(start, end), null)
            }
            return Value(start, close + 1, s.substring(start + 1, close), c)
        }
        if (c == '{' || c == '[') {
            val close = closingBracket(s, start)
            val end = if (close >= 0) close + 1 else endOfLine(s, start)
            return Value(start, end, s.substring(start, end), null)
        }
        var end = when {
            kind == Kind.SECRET && LINE_SECRET_SUFFIXES.any { normalized(key).endsWith(it) } -> endOfLine(s, start)
            kind == Kind.SECRET && normalized(key) in HEADER_NAMES -> endOfLine(s, start)
            kind == Kind.SECRET -> secretEnd(s, start)
            else -> fieldEnd(s, start)
        }
        // Trailing blanks and a closing quote of the surrounding text are not part of the value.
        while (end > start && (s[end - 1].isWhitespace() || s[end - 1] == '"' || s[end - 1] == '\'')) end--
        if (end <= start) return null
        if (kind == Kind.SECRET) {
            val text = s.substring(start, end)
            // `password=Authorization: Bearer x` or `password=ab"cd ef"`: what follows is part
            // of the secret, mask to the end of the line rather than leave a tail.
            if (text.endsWith(':') || text.any { it == '"' || it == '\'' }) end = endOfLine(s, start)
        }
        return Value(start, end, s.substring(start, end), null)
    }

    /**
     * End of an unquoted secret: whitespace, or one of `, ; ) ] }` that is itself followed by
     * whitespace, the end, another delimiter or a quote. `password=ab,cd` is one secret,
     * `password=x, user=y` and `{"password":x}` end where expected.
     */
    private fun secretEnd(s: String, start: Int): Int {
        var i = start
        while (i < s.length && !s[i].isWhitespace() && !(s[i] in SECRET_STOP && endsValue(s, i + 1))) i++
        return i
    }

    /** True when the text at [i] cannot continue a value: end, blank, delimiter or quote. */
    private fun endsValue(s: String, i: Int): Boolean {
        val c = s.getOrNull(i) ?: return true
        return c.isWhitespace() || c in SECRET_STOP || c == '"' || c == '\''
    }

    /**
     * End of an unquoted id or personal value: the next `, ; ] }` or line break, a `)` that
     * closes nothing opened inside the value (`label=Prod (EU) DB` is one value), the next
     * ` key=`, or a trailing ` (ErrorClass<-Cause)` idiom, which is kept readable. A value
     * that starts with one of our own tokens ends right after it, so a second pass (Android
     * runs the shared logger then the app tree) changes nothing.
     */
    private fun fieldEnd(s: String, start: Int): Int {
        for (token in OWN_TOKENS) if (s.startsWith(token, start)) return start + token.length
        var i = start
        var depth = 0
        while (i < s.length && (s[i] !in FIELD_STOP || (s[i] == ')' && depth > 0))) {
            when {
                s[i] == '(' -> depth++
                s[i] == ')' -> depth--
                s[i].isWhitespace() && (nextKeyAt(s, i) || ERROR_CHAIN.matchesAt(s, i)) -> return i
            }
            i++
        }
        return i
    }

    /** True when [i] starts blanks followed by `key=`, the beginning of the next pair. */
    private fun nextKeyAt(s: String, i: Int): Boolean {
        var j = i
        while (j < s.length && (s[j] == ' ' || s[j] == '\t')) j++
        if (j >= s.length || !(s[j].isAsciiLetter() || s[j] == '_')) return false
        val keyStart = j
        while (j < s.length && j - keyStart < 64 && (s[j].isAsciiLetterOrDigit() || s[j] == '_' || s[j] == '-')) j++
        var spaces = 0
        while (j < s.length && s[j] == ' ' && spaces < 3) { j++; spaces++ }
        return j < s.length && s[j] == '='
    }

    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'
    private fun Char.isAsciiLetterOrDigit() = isAsciiLetter() || this in '0'..'9'

    private fun endOfLine(s: String, start: Int): Int {
        var i = start
        while (i < s.length && s[i] != '\n' && s[i] != '\r') i++
        return i
    }

    private fun closingQuote(s: String, start: Int, quote: Char): Int {
        val limit = minOf(s.length, start + 1 + MAX_QUOTED)
        var i = start + 1
        while (i < limit) {
            when (s[i]) {
                '\\' -> i++
                quote -> return i
                '\n', '\r' -> return -1
            }
            i++
        }
        return -1
    }

    /** Index of the `\"` closing the escaped string opened at [start], same line, or -1. */
    private fun closingEscapedQuote(s: String, start: Int): Int {
        val limit = minOf(s.length - 1, start + 2 + MAX_QUOTED)
        var i = start + 2
        while (i < limit) {
            if (s[i] == '\n' || s[i] == '\r') return -1
            if (s[i] == '\\' && s[i + 1] == '"') return i
            if (s[i] == '\\') i++
            i++
        }
        return -1
    }

    private fun closingBracket(s: String, start: Int): Int {
        val limit = minOf(s.length, start + MAX_STRUCTURED)
        var depth = 0
        var quote: Char? = null
        var i = start
        while (i < limit) {
            val c = s[i]
            if (quote != null) {
                if (c == '\\') i++ else if (c == quote) quote = null
            } else {
                when (c) {
                    '"', '\'' -> quote = c
                    '{', '[' -> depth++
                    '}', ']' -> if (--depth == 0) return i
                }
            }
            i++
        }
        return -1
    }
}
