// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.util

actual object Logger {

    @Volatile
    private var debugEnabled: Boolean = run {
        // 1. Explicit override via -Dnextsh.debug=true (highest priority).
        // 2. Otherwise: dev runs (no `compose.application.resources.dir`
        //    means we're NOT in a jpackage distribution → IDE / `gradle run`
        //    → emit debug logs by default for visibility).
        System.getProperty("nextsh.debug")?.toBoolean()
            ?: (System.getProperty("compose.application.resources.dir") == null)
    }

    private val sensitivePatterns = Regex(
        "password|pwd|key_|credential|passphrase|secret|token|fido2|ctap|assertion|authenticator|signature|signing",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Tags whose messages are never redacted, even in release mode.
     *
     * Rationale: diagnostic tags like "YubiKit" emit device enumeration info
     * (transport counts, error codes, device labels) that contain no credentials.
     * They match the sensitivePatterns regex incidentally (e.g. "fido2", "authenticator"
     * appear in technical context strings, not in actual secret values). Whitelisting
     * the tag is simpler and safer than modifying the expect/actual API surface.
     */
    private val diagnosticTagPrefixes = setOf("YubiKit", "WinWebAuthn")

    private const val REDACTED = "[REDACTED, sensitive data]"

    fun setDebugEnabled(enabled: Boolean) {
        debugEnabled = enabled
    }

    fun isDebugEnabled(): Boolean = debugEnabled

    actual fun d(tag: String, message: String) {
        if (!debugEnabled) return
        println("D/$tag: $message")
    }

    actual fun w(tag: String, message: String) {
        System.err.println("W/$tag: ${sanitize(tag, message)}")
    }

    actual fun e(tag: String, message: String, throwable: Throwable?) {
        System.err.println("E/$tag: ${sanitize(tag, message)}")
        throwable?.printStackTrace(System.err)
    }

    private fun isDiagnosticTag(tag: String): Boolean =
        diagnosticTagPrefixes.any { tag.startsWith(it) }

    private fun sanitize(tag: String, message: String): String =
        if (!debugEnabled && !isDiagnosticTag(tag) && sensitivePatterns.containsMatchIn(message)) REDACTED else message
}
