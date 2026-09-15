// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.import

import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.util.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private const val TAG = "NextShImport"

/**
 * Parses NextSH's own native JSON export into a neutral [List] of [ParsedHost].
 * This targets the "nextsh-hosts" format produced by NextSH's own host export
 * feature, as opposed to [TermiusImporter] / [KeePassXcImporter], which target
 * third-party tools.
 *
 * ## Schema (v1)
 *
 * ```json
 * {
 *   "format": "nextsh-hosts",
 *   "version": 1,
 *   "hosts": [
 *     {
 *       "hostname": "10.0.0.12",          // REQUIRED, the only mandatory field
 *       "label": "Prod Web 01",           // optional, defaults to hostname
 *       "port": 22,                        // optional, defaults to 22; Int or numeric
 *                                            // String accepted; an explicit but
 *                                            // out-of-range/unparsable value skips
 *                                            // the whole entry (strict validation)
 *       "username": "deploy",              // optional, defaults to ""
 *       "group": "Production",             // optional
 *       "auth": "password" | "ssh_key",    // optional, case-insensitive; defaults to
 *                                            // "ssh_key" when a privateKey is present,
 *                                            // otherwise "password"; any other value
 *                                            // falls back to "password"
 *       "favorite": false,                 // reserved, see below (accepted, ignored)
 *       "id": "row-42",                    // optional, becomes ParsedHost.sourceId
 *       "password": "…",                   // optional
 *       "privateKey": "-----BEGIN OPENSSH PRIVATE KEY-----\n…", // optional, INLINE PEM ONLY
 *       "keyPassphrase": "…"                // optional
 *     }
 *   ]
 * }
 * ```
 *
 * A bare array of host objects at the document root (`[ { ... }, ... ]`) is also
 * accepted, without the `format`/`version`/`hosts` envelope.
 *
 * ## Aliases (Termius-style tolerance)
 *
 *  - hostname : `hostname`, `host`, `address`
 *  - label    : `label`, `title`, `name`
 *  - username : `username`, `user`
 *  - group    : `group`, `folder`
 *  - auth     : `auth`, `auth_type`, `authType`
 *
 * ## Reserved / not supported in v1
 *
 * `favorite`, `keepAliveSeconds` and `autoReconnect` are accepted syntactically
 * (their presence never fails the parse) but are silently ignored: [ParsedHost]
 * does not carry these fields as of this version, so there is nothing to
 * populate them into. A future schema bump can wire them once the pivot model
 * grows the corresponding fields. See [ParsedHost]/[HostImportService] before
 * adding support rather than smuggling extra state through this importer.
 *
 * ## Security
 *
 *  - This parser never touches the filesystem. Only the inline `privateKey` PEM
 *    string is read; a `privateKeyFile`-shaped field (or any field name
 *    suggesting a path) is simply never looked up: there is no code path here
 *    that opens a file, so such a field is inert rather than "sanitized".
 *  - `hostname` is mandatory and sanitized via [ImportSanitizer.sanitizeIdentifier];
 *    an entry with a missing/unsafe hostname is dropped, not silently repaired.
 *  - `port` uses [ImportSanitizer.sanitizePortStrict]: an explicit but invalid
 *    port (out of 1..65535, or non-numeric) skips the whole entry rather than
 *    silently clamping it, since a native export is expected to be well-formed.
 *  - `label` / `username` / `group` go through the same [ImportSanitizer] helpers
 *    used by the other importers.
 *
 * Malformed individual entries are skipped (logged, not thrown) so one bad host
 * never discards the whole import. An unparseable document yields an empty list.
 */
object NextShImporter {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val HOSTNAME_KEYS = listOf("hostname", "host", "address")
    private val LABEL_KEYS = listOf("label", "title", "name")
    private val USERNAME_KEYS = listOf("username", "user")
    private val GROUP_KEYS = listOf("group", "folder")
    private val AUTH_KEYS = listOf("auth", "auth_type", "authType")

    /**
     * Parses [content] (UTF-8 NextSH native export JSON) into hosts. Returns an
     * empty list on a fundamentally unparseable document (logged at warn).
     * Individual malformed hosts are skipped.
     */
    fun parse(content: String): List<ParsedHost> {
        val root: JsonElement = try {
            json.parseToJsonElement(content)
        } catch (e: Exception) {
            // SECURITY: never interpolate e.message here: for a
            // JsonDecodingException it embeds a raw excerpt of the offending
            // input, which may carry a password/PEM straight from the file.
            Logger.w(TAG, "NextSH export is not valid JSON, aborting parse (${e::class.simpleName})")
            return emptyList()
        }

        val hostsArray: JsonArray = when {
            root is JsonArray -> root
            root is JsonObject -> (root["hosts"] as? JsonArray) ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }

        val result = ArrayList<ParsedHost>(minOf(hostsArray.size, MAX_IMPORT_ENTRIES))
        for (element in hostsArray) {
            // Defensive cap against a pathological huge file: truncate (non-fatal).
            if (result.size >= MAX_IMPORT_ENTRIES) {
                Logger.w(TAG, "NextSH export exceeds $MAX_IMPORT_ENTRIES entries, truncating")
                break
            }
            val host = (element as? JsonObject) ?: continue
            val parsed = parseHost(host)
            if (parsed != null) result += parsed
        }
        return result
    }

    private fun parseHost(host: JsonObject): ParsedHost? {
        val rawHostname = firstString(host, HOSTNAME_KEYS)
        val hostname = ImportSanitizer.sanitizeIdentifier(rawHostname)
        if (hostname == null) {
            Logger.w(TAG, "Skipping NextSH host entry with missing/invalid hostname")
            return null
        }

        val port = sanitizePortField(host["port"])
        if (port == null) {
            Logger.w(TAG, "Skipping NextSH host entry: invalid port")
            return null
        }

        val username = ImportSanitizer.sanitizeIdentifier(firstString(host, USERNAME_KEYS)).orEmpty()
        val group = ImportSanitizer.sanitizeGroup(firstString(host, GROUP_KEYS))
        val label = ImportSanitizer.sanitizeLabel(firstString(host, LABEL_KEYS), fallback = hostname)

        // Inline secrets only: no field here is ever treated as a filesystem path.
        val privateKeyPem = firstString(host, listOf("privateKey"))?.takeIf { it.isNotBlank() }
        val passwordStr = firstString(host, listOf("password"))
        val keyPassphraseStr = firstString(host, listOf("keyPassphrase"))

        val authType = resolveAuthType(firstString(host, AUTH_KEYS), hasPrivateKey = privateKeyPem != null)

        val sourceId = firstString(host, listOf("id"))

        return ParsedHost(
            label = label,
            hostname = hostname,
            port = port,
            username = username,
            authType = authType,
            group = group,
            password = passwordStr?.takeIf { it.isNotEmpty() }?.toCharArray(),
            privateKeyPem = privateKeyPem,
            keyPassphrase = keyPassphraseStr?.takeIf { it.isNotEmpty() }?.toCharArray(),
            sourceId = sourceId,
        )
    }

    /**
     * Resolves the `auth` field: case-insensitive match against `password` /
     * `ssh_key` (a couple of short synonyms are tolerated too). A missing or
     * unrecognised value falls back to [AuthType.SSH_KEY] when an inline private
     * key PEM is present, otherwise [AuthType.PASSWORD]: this is the same
     * "unknown → password" fallback used across the import package.
     */
    private fun resolveAuthType(raw: String?, hasPrivateKey: Boolean): AuthType =
        when (raw?.trim()?.lowercase()) {
            "ssh_key", "sshkey", "key" -> AuthType.SSH_KEY
            "password", "pwd" -> AuthType.PASSWORD
            else -> if (hasPrivateKey) AuthType.SSH_KEY else AuthType.PASSWORD
        }

    /**
     * Reads the `port` field, accepting either a JSON number or a numeric JSON
     * string, and validates it strictly via [ImportSanitizer.sanitizePortStrict].
     * A missing/non-scalar field defaults to port 22 (via the strict sanitizer's
     * own null handling); a present-but-invalid value returns null so the caller
     * skips the entry instead of silently clamping it.
     */
    private fun sanitizePortField(element: JsonElement?): Int? {
        val raw = (element as? JsonPrimitive)?.contentOrNull
        return ImportSanitizer.sanitizePortStrict(raw)
    }

    /** Returns the first present, non-null scalar string among [keys]. */
    private fun firstString(obj: JsonObject, keys: List<String>): String? {
        for (key in keys) {
            val element = obj[key] ?: continue
            val value = when (element) {
                is JsonPrimitive -> element.contentOrNull
                else -> null
            }
            if (value != null) return value
        }
        return null
    }
}
