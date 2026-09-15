// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.import

import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.util.Logger
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

private const val TAG = "KeePassXcImport"

/**
 * Parses a KeePassXC export (either the **CSV** export or the **KeePass 2 XML**
 * export) into a neutral [List] of [ParsedHost].
 *
 * ## CSV export (stable, documented)
 *
 * KeePassXC's "Database → Export → CSV" produces a header row:
 * `Group,Title,Username,Password,URL,Notes,TOTP,Icon,Last Modified,Created`
 * (older versions omit the trailing TOTP/Icon/date columns). Columns are matched
 * by header name, not position, so column drift across versions is tolerated.
 *
 * ## KeePass 2 XML export (decrypted)
 *
 * `Database → Export → KeePass 2 XML`. Structure:
 * ```
 * <KeePassFile><Root><Group>…<Entry>
 *   <String><Key>Title</Key><Value>…</Value></String>
 *   <String><Key>UserName</Key><Value>…</Value></String>
 *   <String><Key>Password</Key><Value ProtectInMemory="True">…</Value></String>
 *   <String><Key>URL</Key><Value>…</Value></String>
 * </Entry></Group></Root></KeePassFile>
 * ```
 * The XML export is decrypted, so `ProtectInMemory` values are plain text: we
 * read them as-is.
 *
 * ## Field mapping (both formats)
 *  - hostname/port : parsed from `URL` (`ssh://user@host:port`, `host:port`, or
 *    bare host), falling back to `Title` when no URL is present. Default port 22.
 *  - username      : `Username` / `UserName` (URL userinfo fills in if the field
 *    is blank).
 *  - password      : `Password`.
 *  - label         : `Title` (falls back to hostname).
 *  - group         : `Group` (CSV) / enclosing `<Group><Name>` (XML).
 *  - authType      : always PASSWORD (KeePassXC stores secrets, not SSH key PEMs,
 *    in these export formats).
 *
 * Detection: a leading `<?xml` or `<KeePassFile` marks XML; otherwise CSV.
 */
object KeePassXcImporter {

    // Recognised header names (case-insensitive) → logical field.
    private const val H_GROUP = "group"
    private const val H_TITLE = "title"
    private const val H_USERNAME = "username"
    private const val H_PASSWORD = "password"
    private const val H_URL = "url"

    /** Parses [content], auto-detecting CSV vs KeePass 2 XML. */
    fun parse(content: String): List<ParsedHost> {
        val trimmedStart = content.trimStart('﻿', ' ', '\t', '\r', '\n')
        return if (isXml(trimmedStart)) {
            parseXml(content)
        } else {
            parseCsv(content)
        }
    }

    private fun isXml(trimmed: String): Boolean =
        trimmed.startsWith("<?xml", ignoreCase = true) ||
            trimmed.startsWith("<KeePassFile", ignoreCase = true) ||
            trimmed.startsWith("<", ignoreCase = false) && trimmed.contains("<KeePassFile", ignoreCase = true)

    // ── CSV path ────────────────────────────────────────────────────────────────

    fun parseCsv(content: String): List<ParsedHost> {
        val rows = CsvParser.parse(content)
        if (rows.isEmpty()) return emptyList()

        val header = rows.first().map { it.trim().lowercase().trimStart('﻿') }
        val colGroup = header.indexOf(H_GROUP)
        val colTitle = header.indexOf(H_TITLE)
        val colUsername = header.indexOf(H_USERNAME)
        val colPassword = header.indexOf(H_PASSWORD)
        val colUrl = header.indexOf(H_URL)

        if (colTitle < 0 && colUrl < 0) {
            Logger.w(TAG, "KeePassXC CSV has neither Title nor URL column, aborting parse")
            return emptyList()
        }

        val result = ArrayList<ParsedHost>(minOf(rows.size - 1, MAX_IMPORT_ENTRIES))
        for (i in 1 until rows.size) {
            // Defensive cap against a pathological huge file: truncate (non-fatal).
            if (result.size >= MAX_IMPORT_ENTRIES) {
                Logger.w(TAG, "KeePassXC CSV exceeds $MAX_IMPORT_ENTRIES entries, truncating")
                break
            }
            val row = rows[i]
            if (row.all { it.isEmpty() }) continue // blank line
            val parsed = parseRow(row, colGroup, colTitle, colUsername, colPassword, colUrl)
            if (parsed != null) result += parsed
        }
        return result
    }

    private fun parseRow(
        row: List<String>,
        colGroup: Int,
        colTitle: Int,
        colUsername: Int,
        colPassword: Int,
        colUrl: Int,
    ): ParsedHost? {
        fun cell(index: Int): String? = if (index in row.indices) row[index] else null

        val title = cell(colTitle)
        val url = cell(colUrl)
        val usernameField = cell(colUsername)
        val password = cell(colPassword)
        val group = ImportSanitizer.sanitizeGroup(cell(colGroup))

        return buildHost(title, url, usernameField, password, group)
    }

    // ── XML path ──────────────────────────────────────────────────────────────

    fun parseXml(content: String): List<ParsedHost> {
        val doc = try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                // Harden against XXE: these exports never reference external entities.
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
                runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
                isXIncludeAware = false
                isExpandEntityReferences = false
                isNamespaceAware = false
            }
            factory.newDocumentBuilder()
                .parse(ByteArrayInputStream(content.toByteArray(Charsets.UTF_8)))
        } catch (e: Exception) {
            // SECURITY: never interpolate e.message here: a SAX/parser
            // exception can embed a raw excerpt of the offending input, which
            // may carry a password straight from the file.
            Logger.w(TAG, "KeePassXC XML is not well-formed, aborting parse (${e::class.simpleName})")
            return emptyList()
        }

        val result = ArrayList<ParsedHost>()
        val entries = doc.getElementsByTagName("Entry")
        for (i in 0 until entries.length) {
            // Defensive cap against a pathological huge file: truncate (non-fatal).
            if (result.size >= MAX_IMPORT_ENTRIES) {
                Logger.w(TAG, "KeePassXC XML exceeds $MAX_IMPORT_ENTRIES entries, truncating")
                break
            }
            val entry = entries.item(i) as? Element ?: continue
            // Skip recycle-bin / history entries (History entries are nested inside <History>).
            if (isInsideHistory(entry)) continue

            val strings = readEntryStrings(entry)
            val title = strings["title"]
            val url = strings["url"]
            val username = strings["username"]
            val password = strings["password"]
            val group = ImportSanitizer.sanitizeGroup(enclosingGroupName(entry))

            val parsed = buildHost(title, url, username, password, group)
            if (parsed != null) result += parsed
        }
        return result
    }

    /** Reads all `<String><Key>…</Key><Value>…</Value></String>` pairs (key lower-cased). */
    private fun readEntryStrings(entry: Element): Map<String, String> {
        val map = HashMap<String, String>()
        val children = entry.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val el = node as Element
            if (el.tagName != "String") continue
            val key = childText(el, "Key")?.trim()?.lowercase() ?: continue
            val value = childText(el, "Value") ?: ""
            map[key] = value
        }
        return map
    }

    /** Direct-child element text by tag name (first match). */
    private fun childText(parent: Element, tag: String): String? {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType == Node.ELEMENT_NODE && (node as Element).tagName == tag) {
                return node.textContent
            }
        }
        return null
    }

    /** The Name of the nearest enclosing <Group>. */
    private fun enclosingGroupName(entry: Node): String? {
        var parent: Node? = entry.parentNode
        while (parent != null) {
            if (parent.nodeType == Node.ELEMENT_NODE && (parent as Element).tagName == "Group") {
                return childText(parent, "Name")
            }
            parent = parent.parentNode
        }
        return null
    }

    private fun isInsideHistory(entry: Node): Boolean {
        var parent: Node? = entry.parentNode
        while (parent != null) {
            if (parent.nodeType == Node.ELEMENT_NODE && (parent as Element).tagName == "History") {
                return true
            }
            parent = parent.parentNode
        }
        return false
    }

    // ── Shared host construction ─────────────────────────────────────────────────

    /**
     * Builds a [ParsedHost] from raw KeePassXC fields. Derives hostname/port from
     * [url] (or [title]) and uses URL userinfo as a username fallback. Returns null
     * when no usable hostname can be derived.
     */
    private fun buildHost(
        title: String?,
        url: String?,
        usernameField: String?,
        password: String?,
        group: String?,
    ): ParsedHost? {
        val target = UrlTarget.parse(url) ?: UrlTarget.parse(title)
        val hostname = ImportSanitizer.sanitizeIdentifier(target?.host)
        if (hostname == null) {
            Logger.w(TAG, "Skipping KeePassXC entry with no derivable hostname")
            return null
        }
        val port = target?.port?.let { ImportSanitizer.clampPort(it) } ?: ImportSanitizer.DEFAULT_SSH_PORT

        val username = ImportSanitizer.sanitizeIdentifier(usernameField)
            ?: ImportSanitizer.sanitizeIdentifier(target?.user)
            ?: ""

        val label = ImportSanitizer.sanitizeLabel(title, fallback = hostname)

        return ParsedHost(
            label = label,
            hostname = hostname,
            port = port,
            username = username,
            authType = AuthType.PASSWORD,
            group = group,
            password = password?.takeIf { it.isNotEmpty() }?.toCharArray(),
            privateKeyPem = null,
            keyPassphrase = null,
            sourceId = null,
        )
    }

    /**
     * A host/port/user triple parsed from a connection string. Accepts
     * `scheme://user@host:port/path`, `user@host:port`, `host:port`, or bare host.
     * The scheme and path are ignored beyond stripping.
     */
    private class UrlTarget(val host: String, val port: Int?, val user: String?) {
        companion object {
            fun parse(raw: String?): UrlTarget? {
                val trimmed = raw?.trim().orEmpty()
                if (trimmed.isEmpty()) return null

                // Strip scheme (ssh://, sftp://, https://, …).
                var rest = trimmed.substringAfter("://", trimmed)
                // Drop any path / query / fragment.
                rest = rest.substringBefore('/').substringBefore('?').substringBefore('#')
                if (rest.isEmpty()) return null

                // Split optional userinfo.
                var user: String? = null
                val atIdx = rest.lastIndexOf('@')
                if (atIdx >= 0) {
                    user = rest.substring(0, atIdx).substringBefore(':').ifEmpty { null }
                    rest = rest.substring(atIdx + 1)
                }
                if (rest.isEmpty()) return null

                // Split host:port. Bracketed IPv6 ([::1]:22) handled minimally.
                var host = rest
                var port: Int? = null
                if (rest.startsWith("[")) {
                    val close = rest.indexOf(']')
                    if (close >= 0) {
                        host = rest.substring(1, close)
                        val after = rest.substring(close + 1)
                        if (after.startsWith(":")) port = after.substring(1).toIntOrNull()
                    }
                } else {
                    val colon = rest.lastIndexOf(':')
                    // Only treat trailing :digits as a port (avoid mangling IPv6 without brackets).
                    if (colon >= 0 && rest.indexOf(':') == colon) {
                        val maybePort = rest.substring(colon + 1)
                        val parsed = maybePort.toIntOrNull()
                        if (parsed != null) {
                            host = rest.substring(0, colon)
                            port = parsed
                        }
                    }
                }
                if (host.isEmpty()) return null
                return UrlTarget(host, port, user)
            }
        }
    }
}
