// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.import

import fr.techtical.nextsh.shared.domain.model.AuthType

/**
 * Defensive upper bound on the number of [ParsedHost] entries an importer will
 * return from a single file. A pathological / hostile export (millions of rows)
 * is truncated to this many entries rather than exhausting memory. Truncation
 * is non-fatal: the importer keeps the first [MAX_IMPORT_ENTRIES] it parses and
 * silently stops. A real Termius / KeePassXC export is orders of magnitude
 * smaller, so legitimate imports are never affected.
 */
internal const val MAX_IMPORT_ENTRIES: Int = 10_000

/**
 * Neutral, source-agnostic representation of a single host parsed from an external
 * export (Termius, KeePassXC, …). Importers produce a `List<ParsedHost>`; the UI
 * pass lets the user pre-select which entries to keep; [HostImportService] then
 * turns the selected entries into [fr.techtical.nextsh.shared.domain.model.Host]
 * rows plus vault secrets.
 *
 * SECURITY: [password] and [keyPassphrase] are [CharArray] (not String) so the
 * caller can wipe them after the secret is stored in the vault: see
 * [HostImportService.import]. Do not log this object.
 *
 * SECURITY (residual secret material): only the durable [CharArray] fields on
 * this object ([password], [keyPassphrase]) are wiped after the secret is
 * persisted. The *parse-time* copies a secret transited through before reaching
 * this object (the raw export text, the kotlinx-serialization JSON tree, the
 * KeePass XML DOM, and the intermediate `String` values the importers read with
 * `firstString(...)` / `readEntryStrings(...)`) are immutable `String`s and
 * cannot be wiped; they live on the heap until the GC reclaims them. This is an
 * accepted residual: it matches every other secret that enters the app as a
 * `String` (e.g. a pasted PEM), and the window is short (one import call).
 * [privateKeyPem] is likewise a `String` by necessity, because the durable sink
 * (`VaultManager.storePrivateKey(keyId, privateKeyPem: String)`) takes a
 * `String`; a `CharArray` here would only be copied back to a `String` before
 * storage, gaining nothing.
 *
 * Note: this is intentionally NOT a Kotlin `data class`. [CharArray] uses identity
 * equals/hashCode, which makes structural equality on a data class misleading and
 * a generated `toString()` would leak secret material into logs/stack traces.
 * Equality is defined by content (with [CharArray] compared by value) and
 * [toString] is redacted.
 */
class ParsedHost(
    val label: String,
    val hostname: String,
    val port: Int,
    val username: String,
    val authType: AuthType,
    val group: String?,
    val password: CharArray? = null,
    val privateKeyPem: String? = null,
    val keyPassphrase: CharArray? = null,
    /** Stable id from the source export, if any, useful for de-dup / diagnostics. */
    val sourceId: String? = null,
) {
    /** True when this entry carries any secret material the vault must store. */
    val hasSecret: Boolean
        get() = password != null || privateKeyPem != null || keyPassphrase != null

    /** Wipes the secret CharArrays in place. Idempotent; safe to call twice. */
    fun wipeSecrets() {
        password?.fill(WIPE_CHAR)
        keyPassphrase?.fill(WIPE_CHAR)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ParsedHost) return false
        if (label != other.label) return false
        if (hostname != other.hostname) return false
        if (port != other.port) return false
        if (username != other.username) return false
        if (authType != other.authType) return false
        if (group != other.group) return false
        if (!contentEqualsNullable(password, other.password)) return false
        if (privateKeyPem != other.privateKeyPem) return false
        if (!contentEqualsNullable(keyPassphrase, other.keyPassphrase)) return false
        if (sourceId != other.sourceId) return false
        return true
    }

    override fun hashCode(): Int {
        var result = label.hashCode()
        result = 31 * result + hostname.hashCode()
        result = 31 * result + port
        result = 31 * result + username.hashCode()
        result = 31 * result + authType.hashCode()
        result = 31 * result + (group?.hashCode() ?: 0)
        result = 31 * result + (password?.contentHashCode() ?: 0)
        result = 31 * result + (privateKeyPem?.hashCode() ?: 0)
        result = 31 * result + (keyPassphrase?.contentHashCode() ?: 0)
        result = 31 * result + (sourceId?.hashCode() ?: 0)
        return result
    }

    /** Redacted: never expose secret material via toString. */
    override fun toString(): String =
        "ParsedHost(label=$label, hostname=$hostname, port=$port, username=$username, " +
            "authType=$authType, group=$group, hasPassword=${password != null}, " +
            "hasPrivateKey=${privateKeyPem != null}, hasKeyPassphrase=${keyPassphrase != null}, " +
            "sourceId=$sourceId)"

    private fun contentEqualsNullable(a: CharArray?, b: CharArray?): Boolean = when {
        a == null && b == null -> true
        a == null || b == null -> false
        else -> a.contentEquals(b)
    }

    private companion object {
        // Overwrite char for wiping secrets. Char(32) = space; written via Char()
        // rather than a single-quoted literal to avoid the build environment's
        // mangling of in-quote control/space literals into NUL bytes.
        private val WIPE_CHAR: Char = Char(32)
    }
}
