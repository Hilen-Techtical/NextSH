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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val TAG = "TermiusImport"

/**
 * Parses a Termius export JSON into a neutral [List] of [ParsedHost].
 *
 * ## Targeted schema
 *
 * Termius does not publish a stable export JSON schema, and the field names differ
 * between the user-facing "Export → JSON" file and the internal IndexedDB/sync
 * model that community tools reverse-engineer. We therefore parse **tolerantly**:
 * a single host object is matched against a set of field aliases drawn from both
 * shapes, and identity / group / ssh-key collections are resolved either inline
 * (nested object) or by id reference into top-level collections.
 *
 * Field aliases recognised per host (first present wins):
 *  - hostname : `address`, `host`, `hostname`, `remote_address`
 *  - port     : `port`, `ssh_port`
 *  - label    : `label`, `title`, `name`
 *  - username : `username`, `user_name`, `ssh_username` (or via the linked identity)
 *  - group    : `group` / `parent_group` (string name, or id → group lookup)
 *  - identity : `identity` (inline object or id), `credential`
 *  - ssh key  : `ssh_key`, `key`, `key_id`, `identity.ssh_key`
 *
 * Top-level collections (any of these, when present, are indexed by `id`/`label`
 * for reference resolution):
 *  - hosts      : `hosts`, `connections` (or the document root may be a bare array)
 *  - identities : `identities`
 *  - groups     : `groups`
 *  - ssh_keys   : `ssh_keys`, `keys`, `sshKeys`
 *
 * Identity object aliases: `username`/`user_name`, `password`, `ssh_key`/`key`/`key_id`.
 * SSH key object aliases: `private_key`/`privateKey`, `passphrase`, `label`.
 *
 * Sources researched:
 *  - Termius docs (Identities = username + password + key; Groups nest hosts):
 *    https://docs.termius.com/keychain/identities
 *  - termius-cli CSV import columns (Label, Group, Address, Port, Username, …):
 *    https://docs.termius.com/getting-started/import-existing-hosts
 *  - ZacharyZcR/termius-exporter reverse-engineered internal model
 *    (`host`/`port`/`title`/`user_name`/`key_id`; identity `username`/`password`;
 *    ssh key `private_key`/`label`/`passphrase`):
 *    https://github.com/ZacharyZcR/termius-exporter
 *
 * Malformed individual entries are skipped (logged, not thrown) so one bad host
 * never discards the whole import.
 */
object TermiusImporter {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val HOST_COLLECTION_KEYS = listOf("hosts", "connections")
    private val IDENTITY_COLLECTION_KEYS = listOf("identities")
    private val GROUP_COLLECTION_KEYS = listOf("groups")
    private val KEY_COLLECTION_KEYS = listOf("ssh_keys", "sshKeys", "keys")

    private val HOSTNAME_KEYS = listOf("address", "host", "hostname", "remote_address")
    private val PORT_KEYS = listOf("port", "ssh_port")
    private val LABEL_KEYS = listOf("label", "title", "name")
    private val USERNAME_KEYS = listOf("username", "user_name", "ssh_username")
    private val GROUP_REF_KEYS = listOf("group", "parent_group", "group_id", "parent_group_id")
    private val IDENTITY_REF_KEYS = listOf("identity", "credential", "identity_id", "credential_id")
    private val KEY_REF_KEYS = listOf("ssh_key", "key", "ssh_key_id", "key_id")
    private val PASSWORD_KEYS = listOf("password")
    private val PRIVATE_KEY_KEYS = listOf("private_key", "privateKey", "value", "content")
    private val PASSPHRASE_KEYS = listOf("passphrase")

    /**
     * Parses [content] (UTF-8 Termius export JSON) into hosts. Returns an empty
     * list on a fundamentally unparseable document (logged at warn). Individual
     * malformed hosts are skipped.
     */
    fun parse(content: String): List<ParsedHost> {
        val root: JsonElement = try {
            json.parseToJsonElement(content)
        } catch (e: Exception) {
            // SECURITY: never interpolate e.message here: for a
            // JsonDecodingException it embeds a raw excerpt of the offending
            // input, which may carry a password/PEM straight from the file.
            Logger.w(TAG, "Termius export is not valid JSON, aborting parse (${e::class.simpleName})")
            return emptyList()
        }

        val rootObject: JsonObject? = (root as? JsonObject)
        val hostsArray: JsonArray = when {
            root is JsonArray -> root
            rootObject != null -> firstArray(rootObject, HOST_COLLECTION_KEYS) ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }

        // Build reference lookup tables (id -> object) for any top-level collections.
        val identities = rootObject?.let { indexById(firstArray(it, IDENTITY_COLLECTION_KEYS)) } ?: emptyMap()
        val groups = rootObject?.let { indexById(firstArray(it, GROUP_COLLECTION_KEYS)) } ?: emptyMap()
        val keys = rootObject?.let { indexById(firstArray(it, KEY_COLLECTION_KEYS)) } ?: emptyMap()

        val result = ArrayList<ParsedHost>(minOf(hostsArray.size, MAX_IMPORT_ENTRIES))
        for (element in hostsArray) {
            // Defensive cap against a pathological huge file: truncate (non-fatal).
            if (result.size >= MAX_IMPORT_ENTRIES) {
                Logger.w(TAG, "Termius export exceeds $MAX_IMPORT_ENTRIES entries, truncating")
                break
            }
            val host = (element as? JsonObject) ?: continue
            val parsed = parseHost(host, identities, groups, keys)
            if (parsed != null) result += parsed
        }
        return result
    }

    private fun parseHost(
        host: JsonObject,
        identities: Map<String, JsonObject>,
        groups: Map<String, JsonObject>,
        keys: Map<String, JsonObject>,
    ): ParsedHost? {
        val rawHostname = firstString(host, HOSTNAME_KEYS)
        val hostname = ImportSanitizer.sanitizeIdentifier(rawHostname)
        if (hostname == null) {
            // No usable address: nothing we can connect to. Skip silently-ish.
            Logger.w(TAG, "Skipping Termius host with missing/invalid address")
            return null
        }

        val port = ImportSanitizer.sanitizePortLenient(firstString(host, PORT_KEYS))

        // Resolve the linked identity: inline object, or id reference into `identities`.
        val identity = resolveRef(host, IDENTITY_REF_KEYS, identities)

        // Username: prefer the host's own field, else the identity's.
        val rawUsername = firstString(host, USERNAME_KEYS)
            ?: identity?.let { firstString(it, USERNAME_KEYS) }
        val username = ImportSanitizer.sanitizeIdentifier(rawUsername).orEmpty()

        // Group: a name string on the host, or an id reference into `groups`.
        val group = resolveGroupName(host, groups)

        val label = ImportSanitizer.sanitizeLabel(firstString(host, LABEL_KEYS), fallback = hostname)

        // Secrets: password from identity (or host), private key from referenced ssh key.
        val passwordStr = firstString(identity ?: host, PASSWORD_KEYS)
            ?: firstString(host, PASSWORD_KEYS)

        val keyObject = resolveRef(host, KEY_REF_KEYS, keys)
            ?: identity?.let { resolveRef(it, KEY_REF_KEYS, keys) }
        val privateKeyPem = keyObject?.let { firstString(it, PRIVATE_KEY_KEYS) }?.takeIf { it.isNotBlank() }
        val keyPassphraseStr = keyObject?.let { firstString(it, PASSPHRASE_KEYS) }

        val authType = when {
            privateKeyPem != null -> AuthType.SSH_KEY
            keyObject != null -> AuthType.SSH_KEY // referenced key with no exportable PEM (key body lives outside export)
            else -> AuthType.PASSWORD
        }

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

    // ── Reference resolution ──────────────────────────────────────────────────

    /**
     * Resolves a reference field that may be either an inline [JsonObject] or a
     * scalar id pointing into [collection]. Returns the referenced/inline object,
     * or null when neither form is present.
     */
    private fun resolveRef(
        owner: JsonObject,
        refKeys: List<String>,
        collection: Map<String, JsonObject>,
    ): JsonObject? {
        for (key in refKeys) {
            val element = owner[key] ?: continue
            when (element) {
                is JsonObject -> return element
                is JsonPrimitive -> {
                    val id = element.contentOrNull ?: continue
                    collection[id]?.let { return it }
                }
                else -> Unit
            }
        }
        return null
    }

    private fun resolveGroupName(host: JsonObject, groups: Map<String, JsonObject>): String? {
        for (key in GROUP_REF_KEYS) {
            val element = host[key] ?: continue
            when (element) {
                is JsonObject -> {
                    // Inline group object: take its label/title/name.
                    firstString(element, LABEL_KEYS)?.let { return ImportSanitizer.sanitizeGroup(it) }
                }
                is JsonPrimitive -> {
                    val value = element.contentOrNull ?: continue
                    // Either a group id (resolve into collection) or a literal name.
                    val resolved = groups[value]?.let { firstString(it, LABEL_KEYS) }
                    return ImportSanitizer.sanitizeGroup(resolved ?: value)
                }
                else -> Unit
            }
        }
        return null
    }

    // ── JSON helpers ────────────────────────────────────────────────────────────

    private fun firstArray(obj: JsonObject, keys: List<String>): JsonArray? {
        for (key in keys) {
            val element = obj[key]
            if (element is JsonArray) return element
        }
        return null
    }

    /**
     * Indexes a collection array by each element's `id` (or `label`, as fallback),
     * so host reference fields can resolve to the full object. Non-object elements
     * are ignored.
     */
    private fun indexById(array: JsonArray?): Map<String, JsonObject> {
        if (array == null) return emptyMap()
        val map = HashMap<String, JsonObject>(array.size)
        for (element in array) {
            val obj = (element as? JsonObject) ?: continue
            val id = firstString(obj, listOf("id"))
            if (id != null) map[id] = obj
            // Also index by label so a host that references a group/identity by name resolves.
            firstString(obj, LABEL_KEYS)?.let { label -> map.putIfAbsent(label, obj) }
        }
        return map
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
