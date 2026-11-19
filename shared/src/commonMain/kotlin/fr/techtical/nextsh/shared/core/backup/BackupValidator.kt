// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.backup

import fr.techtical.nextsh.shared.core.sync.LAN_SYNC_SECRET_KEY_PREFIX
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.SshKey
import fr.techtical.nextsh.shared.domain.model.TunnelConfig
import fr.techtical.nextsh.shared.domain.model.TunnelType
import fr.techtical.nextsh.shared.domain.tunnel.TunnelBrowserPath

/** A document that went through [BackupValidator]. */
class ValidatedBackup(
    /** The input reduced to its accepted entries (v1 core, new sections, settings and producer cleaned). */
    val document: BackupDocument,
    val invalidSnippets: Int,
    val invalidThemes: Int,
    val invalidKnownHosts: Int,
    val invalidSettings: Int,
    val invalidCertificates: Int = 0,
    val invalidHosts: Int = 0,
    val invalidSshKeys: Int = 0,
    /** Tunnels invalid by themselves, over the cap, or whose host of the file was dropped. */
    val invalidTunnels: Int = 0,
    /** Passwords, private keys and key passphrases ignored (invalid, over a cap or over the global budget). */
    val invalidSecrets: Int = 0,
) {
    /**
     * Entries of the whole document (v1 core, new sections, certificates included) that were ignored because
     * they are invalid or beyond the maximum count of their section. The LAN sync secret, which is never
     * imported, is removed without being counted.
     */
    val invalidTotal: Int
        get() = invalidSnippets + invalidThemes + invalidKnownHosts + invalidSettings + invalidCertificates +
            invalidHosts + invalidSshKeys + invalidTunnels + invalidSecrets

    // Hand written: never print the document.
    override fun toString(): String = "ValidatedBackup"
}

/** Outcome of [BackupValidator.validate]. */
sealed interface BackupValidationResult {
    class Valid(val backup: ValidatedBackup) : BackupValidationResult {
        override fun toString(): String = "Valid"
    }

    /**
     * No longer returned by [BackupValidator.validate] (a section over its maximum count keeps its first
     * entries and counts the surplus as invalid). Kept so that exhaustive `when` expressions still compile.
     */
    data object Malformed : BackupValidationResult
}

/**
 * Semantic validation of the whole document: the v1 core (hosts, SSH keys, tunnels, secrets) and the
 * sections new in version 2.
 *
 * A structure error never gets here (the codec already refused the file). An invalid entry is
 * dropped and counted, the rest is kept. A section over its maximum count keeps its first N entries and
 * counts the surplus as invalid. Nothing is logged and nothing is thrown with content.
 *
 * Certificates: id as above, value not empty and at most 64 KiB of UTF-8, at most
 * [BackupLimits.MAX_CERTIFICATES] entries.
 *
 * V1 core, `browserPath` of a tunnel included. A value is first normalized (what gets restored is the
 * normalized value), then checked:
 * - labels and group (host, SSH key, tunnel) are cleaned, never refused: control chars (C0, DEL, C1) become
 *   a space and the text is cut to 4 096 chars (what `ImportSanitizer` also allows).
 * - user name: control chars (C0, DEL, C1) become a space (such a name never connected, while the host, its
 *   label and its tunnels are data); over 4 096 chars it is refused; empty is allowed.
 * - host: id; `hostname` (whitespace around it removed) 1 to 255 chars without whitespace, control or invisible
 *   format char inside (same rule as the host part of a known-host key); `port` 1..65535; `credentialId` empty
 *   or at most 128 chars without control char; `terminalTheme` at most 128 chars without control char. At most
 *   [BackupLimits.MAX_HOSTS] entries, a duplicate id is invalid (the first one wins). An editor accepts a
 *   space inside a host name (and any port on Android): such a host is not restorable.
 * - SSH key: [SshKeyRules.normalizeForStorage] (public key line trimmed and cleaned to one line, never refused
 *   under 64 KiB; `fido2RpId` cleaned; `fido2CredentialId` and `keystoreAlias` refused with a control char).
 *   At most [BackupLimits.MAX_SSH_KEYS]. Which half of a key already on the device wins is decided by the
 *   importer with [BackupKeyRules].
 * - tunnel: id; `hostId` an id; `localPort` 1..65535; `remotePort` 1..65535 and `remoteHost` empty or a host
 *   name as above (trimmed), except for SOCKS5 whose remote end is neither checked nor kept (`remoteHost`
 *   becomes empty). `browserPath`: `/` off LOCAL_FORWARD (as the editors store it), else normalized by
 *   [TunnelBrowserPath.normalize] and `/` when [TunnelBrowserPath.validate] refuses it (the fallback the browser
 *   applies anyway); never a reason to drop the tunnel, never counted. At most [BackupLimits.MAX_TUNNELS]. A
 *   tunnel whose `hostId` is the id of a host of the file that was dropped is invalid too (a tunnel whose host
 *   is simply absent from the file is kept: the importer decides).
 * - secrets (`credentials`, `privateKeys`, `keyPassphrases`): id; value at most 64 KiB of UTF-8, not empty for a
 *   private key; at most 10 000 entries per map; at most 16 MiB of values over all secrets (private keys,
 *   passphrases, passwords, certificates in that order; an entry that does not fit is invalid). No format
 *   check of a private key. A password whose id starts with the LAN sync secret prefix is removed without
 *   being counted. No cascade: a dropped host or key keeps its secrets.
 *
 * Text rules of labels, names, categories and commands live in [BackupFieldRules] (shared with the editors).
 *
 * Rules (spec 4.2):
 * - ids (snippet, theme, snippet `hostId`): not empty, at most 128 chars, no control char. Theme id: a
 *   UUID (so never a preset name). A duplicate id inside the file is invalid (the first one wins).
 * - `label`, `name`, `category`: at most 256 chars, no control char (C0, DEL, C1) and no invisible
 *   format char. A theme name is not blank.
 * - `command`: at most 64 KiB of UTF-8, `\n` and `\t` allowed, any other control char and any
 *   invisible format char refused.
 * - `ansi`: exactly 16 entries; background, foreground, cursor and ANSI colors have alpha 0xFF
 *   (the editors force it), the selection color keeps any alpha.
 * - `createdAt`, `addedAt`: 0 or more.
 * - `hostPort`: `host:port` split at the LAST colon, port 1..65535 in digits, host not empty, at most 255
 *   chars, no whitespace, no control char, no invisible format char. A duplicate `hostPort` inside the file is
 *   invalid (the first one wins).
 * - `keyType`: one of [BackupFormat.KNOWN_HOST_KEY_TYPES]; `publicKey`: valid padded base64 of 1 to 8 KiB. The
 *   check that SSHJ can read the blob and that its type equals `keyType` is the platform hook
 *   `knownHostCheck` (jvmCommon `KnownHostPortable.fromPortable`).
 * - settings: only values inside the exact domain of each field are kept (no clamping).
 */
object BackupValidator {

    private val ALPHA_MASK: Int = 0xFF000000.toInt()
    private val UUID_REGEX =
        Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    /**
     * @param knownHostCheck extra check per known host, run after the structural rules; return false to
     * count the entry as invalid. The default accepts everything.
     */
    fun validate(
        document: BackupDocument,
        knownHostCheck: (BackupKnownHost) -> Boolean = { true },
    ): BackupValidationResult {
        // Over the cap: only the first N are looked at, the surplus counts as invalid (size difference below).
        val snippetIds = HashSet<String>()
        val snippets = document.snippets.take(BackupLimits.MAX_SNIPPETS)
            .filter { isValidSnippet(it) && snippetIds.add(it.id) }

        val themeIds = HashSet<String>()
        val themes = document.customThemes.take(BackupLimits.MAX_CUSTOM_THEMES)
            .filter { isValidTheme(it) && themeIds.add(it.id) }

        val hostPorts = HashSet<String>()
        val knownHosts = document.knownHosts.take(BackupLimits.MAX_KNOWN_HOSTS).filter {
            isValidKnownHost(it) && knownHostCheck(it) && hostPorts.add(it.hostPort)
        }

        // V1 core. Hosts first: the ids of the dropped ones decide the tunnels.
        val hostIds = HashSet<String>()
        val hosts = document.hosts.take(BackupLimits.MAX_HOSTS).map { normalizeHost(it) }
            .filter { isValidHost(it) && hostIds.add(it.id) }
        val keptHostIds = hosts.mapTo(HashSet()) { it.id }
        val droppedHostIds = HashSet<String>()
        if (hosts.size != document.hosts.size) {
            for (h in document.hosts) if (h.id !in keptHostIds) droppedHostIds.add(h.id)
        }

        val tunnelIds = HashSet<String>()
        val tunnels = document.tunnels.take(BackupLimits.MAX_TUNNELS).map { normalizeTunnel(it) }.filter {
            isValidTunnel(it) && it.hostId !in droppedHostIds && tunnelIds.add(it.id)
        }

        val keyIds = HashSet<String>()
        val sshKeys = document.sshKeys.take(BackupLimits.MAX_SSH_KEYS).mapNotNull { SshKeyRules.normalizeForStorage(it) }
            .filter { keyIds.add(it.id) }

        // Secrets share one budget, spent in this order: private keys (irreplaceable), key passphrases,
        // passwords, then certificates. A skipped LAN secret is neither kept nor counted.
        val budget = SecretBudget()
        var invalidSecrets = 0
        val privateKeys = LinkedHashMap<String, String>()
        var examined = 0
        for ((id, value) in document.privateKeys) {
            if (examined++ >= BackupLimits.MAX_PRIVATE_KEYS) break
            if (isValidId(id) && budget.accept(value, allowEmpty = false)) privateKeys[id] = value
        }
        invalidSecrets += document.privateKeys.size - privateKeys.size

        val passphrases = LinkedHashMap<String, String>()
        examined = 0
        for ((id, value) in document.keyPassphrases) {
            if (examined++ >= BackupLimits.MAX_KEY_PASSPHRASES) break
            if (isValidId(id) && budget.accept(value, allowEmpty = true)) passphrases[id] = value
        }
        invalidSecrets += document.keyPassphrases.size - passphrases.size

        val credentials = LinkedHashMap<String, String>()
        val lanSecrets = document.credentials.keys.count { it.startsWith(LAN_SYNC_SECRET_KEY_PREFIX) }
        examined = 0
        for ((id, value) in document.credentials) {
            if (id.startsWith(LAN_SYNC_SECRET_KEY_PREFIX)) continue
            if (examined++ >= BackupLimits.MAX_CREDENTIALS) break
            if (isValidId(id) && budget.accept(value, allowEmpty = true)) credentials[id] = value
        }
        invalidSecrets += document.credentials.size - lanSecrets - credentials.size

        val certificates = LinkedHashMap<String, String>()
        for ((id, value) in document.certificates) {
            if (certificates.size >= BackupLimits.MAX_CERTIFICATES) break
            if (isValidId(id) && budget.accept(value, allowEmpty = false)) certificates[id] = value
        }

        val settings = document.settings?.let { cleanSettings(it) }
        val producer = document.producer?.takeIf { isValidProducer(it) }

        val cleaned = document.copy(
            hosts = hosts,
            sshKeys = sshKeys,
            tunnels = tunnels,
            credentials = credentials,
            privateKeys = privateKeys,
            keyPassphrases = passphrases,
            snippets = snippets,
            customThemes = themes,
            knownHosts = knownHosts,
            certificates = certificates,
            settings = settings?.first,
            producer = producer,
        )
        return BackupValidationResult.Valid(
            ValidatedBackup(
                document = cleaned,
                invalidSnippets = document.snippets.size - snippets.size,
                invalidThemes = document.customThemes.size - themes.size,
                invalidKnownHosts = document.knownHosts.size - knownHosts.size,
                invalidSettings = settings?.second ?: 0,
                invalidCertificates = document.certificates.size - certificates.size,
                invalidHosts = document.hosts.size - hosts.size,
                invalidSshKeys = document.sshKeys.size - sshKeys.size,
                invalidTunnels = document.tunnels.size - tunnels.size,
                invalidSecrets = invalidSecrets,
            ),
        )
    }

    // ---- v1 core ------------------------------------------------------------------------------

    /** Extra characters tolerated around a value before it is trimmed (a larger string is refused as is). */
    private const val TRIM_MARGIN = 64

    /**
     * [s] without the whitespace around it, unless it is longer than [maxLen] plus a small margin: such a
     * string cannot be valid, so it is returned as is and refused on its raw length (no copy of a huge string).
     */
    internal fun trimBounded(s: String, maxLen: Int): String = if (s.length > maxLen + TRIM_MARGIN) s else s.trim()

    /**
     * A label or a group the way it gets restored: control chars (C0, DEL, C1) become a space and the length
     * is cut to [BackupLimits.MAX_FREE_TEXT_LENGTH]. A label never costs an entry that works.
     */
    internal fun cleanLabel(s: String): String {
        var end = minOf(s.length, BackupLimits.MAX_FREE_TEXT_LENGTH)
        if (end < s.length && end > 0 && s[end - 1].isHighSurrogate()) end-- // never cut a surrogate pair
        if (end == s.length && s.none { it.isISOControl() }) return s
        return buildString(end) { for (i in 0 until end) append(if (s[i].isISOControl()) ' ' else s[i]) }
    }

    /** [s] with every control char (C0, DEL, C1) replaced by a space, nothing else changed (no cut). */
    internal fun replaceControlChars(s: String): String =
        if (s.none { it.isISOControl() }) s else buildString(s.length) { for (c in s) append(if (c.isISOControl()) ' ' else c) }

    /**
     * [h] the way it gets restored: label and group cleaned, whitespace around the host name removed, control
     * chars of the user name replaced by a space (a name over the limit is left as is and refused).
     */
    private fun normalizeHost(h: Host): Host = h.copy(
        label = cleanLabel(h.label),
        hostname = trimBounded(h.hostname, BackupLimits.MAX_HOST_LENGTH),
        username = if (h.username.length > BackupLimits.MAX_FREE_TEXT_LENGTH) h.username else replaceControlChars(h.username),
        group = h.group?.let { cleanLabel(it) },
    )

    /**
     * [t] the way it gets restored: label cleaned, remote host trimmed, and emptied for SOCKS5 (it has no
     * remote end, so whatever the field holds is dropped), browser path as [restoredBrowserPath].
     */
    private fun normalizeTunnel(t: TunnelConfig): TunnelConfig = t.copy(
        label = cleanLabel(t.label),
        remoteHost = if (t.type == TunnelType.DYNAMIC_SOCKS5) "" else trimBounded(t.remoteHost, BackupLimits.MAX_HOST_LENGTH),
        browserPath = restoredBrowserPath(t),
    )

    /**
     * The browser path a restore keeps: [TunnelBrowserPath.DEFAULT] off LOCAL_FORWARD (the editors store it so)
     * and for any value [TunnelBrowserPath.validate] refuses (the browser would fall back to it anyway), else the
     * normalized value. A huge value is dropped on its raw length, without a copy.
     */
    private fun restoredBrowserPath(t: TunnelConfig): String {
        if (t.type != TunnelType.LOCAL_FORWARD) return TunnelBrowserPath.DEFAULT
        if (t.browserPath.length > TunnelBrowserPath.MAX_LENGTH + TRIM_MARGIN) return TunnelBrowserPath.DEFAULT
        val normalized = TunnelBrowserPath.normalize(t.browserPath)
        return if (TunnelBrowserPath.validate(normalized) == null) normalized else TunnelBrowserPath.DEFAULT
    }

    /**
     * True when [h] would be restored (also usable at export time to count what a restore would refuse).
     * Label and group are cleaned, never refused. Still refused: an empty or invalid id, a host name that is
     * empty or holds whitespace, a control or invisible char inside (or is over 255 chars), a port outside
     * 1..65535, a user name over 4 096 chars (a control char in it is replaced, not refused), an over-long or
     * control-char credential id or terminal theme. An editor accepts some of these (for example a space inside
     * a host name or any port on Android): such a host cannot connect, but it is not restorable either.
     */
    fun isValidHost(h: Host): Boolean =
        isValidId(h.id) &&
            isValidHostName(trimBounded(h.hostname, BackupLimits.MAX_HOST_LENGTH)) &&
            h.port in 1..65535 &&
            h.username.length <= BackupLimits.MAX_FREE_TEXT_LENGTH &&
            isValidReference(h.credentialId, allowEmpty = true) &&
            isValidReference(h.terminalTheme, allowEmpty = true)

    /** True when [k] would be restored (also usable at export time): [SshKeyRules.normalizeForStorage] keeps it. */
    fun isValidSshKey(k: SshKey): Boolean = SshKeyRules.normalizeForStorage(k) != null

    /**
     * True when [t] would be restored on its own merits (also usable at export time). The label is cleaned,
     * never refused. A SOCKS5 tunnel has no remote end: its remote host and port are not checked. The cascade
     * from a dropped host is applied by [validate] only.
     */
    fun isValidTunnel(t: TunnelConfig): Boolean =
        isValidId(t.id) &&
            isValidId(t.hostId) &&
            t.localPort in 1..65535 &&
            (t.type == TunnelType.DYNAMIC_SOCKS5 ||
                (t.remotePort in 1..65535 &&
                    trimBounded(t.remoteHost, BackupLimits.MAX_HOST_LENGTH).let { it.isEmpty() || isValidHostName(it) }))

    /**
     * True when a secret value (password, key passphrase, private key, certificate) is within the per-value
     * bound: at most 64 KiB of UTF-8 and, unless [allowEmpty], not empty. The global budget of the file is
     * applied by [validate] only.
     */
    fun isValidSecretValue(value: String, allowEmpty: Boolean): Boolean =
        (allowEmpty || value.isNotEmpty()) &&
            utf8SizeUpTo(value, BackupLimits.MAX_SECRET_VALUE_BYTES) <= BackupLimits.MAX_SECRET_VALUE_BYTES

    /** Free text of the v1 core: bounded, no control char (C0, DEL, C1), empty and invisible chars allowed. */
    internal fun isValidFreeText(s: String): Boolean =
        s.length <= BackupLimits.MAX_FREE_TEXT_LENGTH && s.none { it.isISOControl() }

    internal fun isValidShortText(s: String): Boolean =
        s.length <= BackupLimits.MAX_TEXT_LENGTH && s.none { it.isISOControl() }

    /** Id-like reference (credential id, terminal theme): like an id, optionally empty. */
    private fun isValidReference(s: String, allowEmpty: Boolean): Boolean =
        (allowEmpty || s.isNotEmpty()) && s.length <= BackupLimits.MAX_ID_LENGTH && s.none { it.isISOControl() }

    /**
     * Spends the per-value bound and the global budget of the secrets. [accept] is true when the value is
     * within both; an entry that does not fit is refused and the budget is left untouched.
     */
    private class SecretBudget {
        private var used = 0L

        fun accept(value: String, allowEmpty: Boolean): Boolean {
            if (!allowEmpty && value.isEmpty()) return false
            val size = utf8SizeUpTo(value, BackupLimits.MAX_SECRET_VALUE_BYTES)
            if (size > BackupLimits.MAX_SECRET_VALUE_BYTES) return false
            if (used + size > BackupLimits.MAX_SECRETS_TOTAL_BYTES) return false
            used += size
            return true
        }
    }

    /**
     * UTF-8 size of [s] when it is at most [limit], else `limit + 1`, without encoding the string (a value
     * of tens of MiB is refused on its length alone). A lone surrogate counts for 3 bytes (never under the
     * real size of a well formed string).
     */
    internal fun utf8SizeUpTo(s: String, limit: Int): Int {
        if (s.length > limit) return limit + 1 // at least one byte per char
        var size = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.code < 0x80 -> size += 1
                c.code < 0x800 -> size += 2
                c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate() -> { size += 4; i++ }
                else -> size += 3
            }
            if (size > limit) return limit + 1
            i++
        }
        return size
    }

    // ---- certificates -------------------------------------------------------------------------

    internal fun isValidCertificate(value: String): Boolean =
        isValidSecretValue(value, allowEmpty = false)

    // ---- snippets -----------------------------------------------------------------------------

    /** True when [s] would be restored (also usable at export time to count what a restore would refuse). */
    fun isValidSnippet(s: BackupSnippet): Boolean =
        isValidId(s.id) &&
            BackupFieldRules.snippetLabelProblem(s.label) == null &&
            BackupFieldRules.snippetCategoryProblem(s.category) == null &&
            (s.hostId == null || isValidId(s.hostId)) &&
            BackupFieldRules.snippetCommandProblem(s.command) == null &&
            s.createdAt >= 0

    // ---- themes -------------------------------------------------------------------------------

    /** True when [t] would be restored (also usable at export time to count what a restore would refuse). */
    fun isValidTheme(t: BackupCustomTheme): Boolean =
        isValidId(t.id) &&
            UUID_REGEX.matches(t.id) &&
            t.id !in BackupFormat.TERMINAL_PRESET_NAMES &&
            BackupFieldRules.themeNameProblem(t.name) == null &&
            t.ansi.size == THEME_ANSI_SIZE &&
            isOpaque(t.background) && isOpaque(t.foreground) && isOpaque(t.cursor) &&
            t.ansi.all { isOpaque(it) } &&
            t.createdAt >= 0

    private fun isOpaque(argb: Int): Boolean = (argb and ALPHA_MASK) == ALPHA_MASK

    private const val THEME_ANSI_SIZE = 16

    // ---- known hosts --------------------------------------------------------------------------

    internal fun isValidKnownHost(k: BackupKnownHost): Boolean =
        isValidHostPort(k.hostPort) &&
            k.keyType in BackupFormat.KNOWN_HOST_KEY_TYPES &&
            isValidPublicKeyBase64(k.publicKey) &&
            k.addedAt >= 0

    internal fun isValidHostPort(hostPort: String): Boolean {
        val colon = hostPort.lastIndexOf(':')
        if (colon <= 0) return false // no colon, or empty host
        val host = hostPort.substring(0, colon)
        val port = hostPort.substring(colon + 1)
        if (!isValidHostName(host)) return false
        if (port.isEmpty() || port.length > 5 || !port.all { it in '0'..'9' }) return false
        return port.toInt() in 1..65535
    }

    /**
     * A host name or address: 1 to 255 chars, no whitespace, no control char, no invisible format char.
     * Shared by the `host:port` key of a known host, the `hostname` of a host and the `remoteHost` of a tunnel.
     * The host editors of both platforms call it on the trimmed field, so a host they save is always restorable.
     */
    fun isValidHostName(host: String): Boolean =
        host.isNotEmpty() && host.length <= BackupLimits.MAX_HOST_LENGTH &&
            host.none { it.isWhitespace() || it.isISOControl() || it.isInvisibleFormat() }

    internal fun isValidPublicKeyBase64(value: String): Boolean {
        val size = decodedBase64Size(value) ?: return false
        return size in 1..BackupLimits.MAX_PUBLIC_KEY_BLOB_BYTES
    }

    /** Decoded size of a strictly valid, padded, standard-alphabet base64 string; null when invalid. */
    internal fun decodedBase64Size(value: String): Int? {
        val n = value.length
        if (n == 0 || n % 4 != 0) return null
        // Reject absurd lengths before scanning.
        if (n > (BackupLimits.MAX_PUBLIC_KEY_BLOB_BYTES / 3 + 2) * 4) return null
        var padding = 0
        for (i in 0 until n) {
            val c = value[i]
            when {
                c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '+' || c == '/' -> {
                    if (padding > 0) return null
                }
                c == '=' -> {
                    padding++
                    if (padding > 2 || i < n - 2) return null
                }
                else -> return null
            }
        }
        return n / 4 * 3 - padding
    }

    // ---- shared text rules --------------------------------------------------------------------

    internal fun isValidId(id: String): Boolean =
        id.isNotEmpty() && id.length <= BackupLimits.MAX_ID_LENGTH && id.none { it.isISOControl() }

    // ---- producer -----------------------------------------------------------------------------

    private fun isValidProducer(p: BackupProducer): Boolean =
        p.platform in PLATFORM_NAMES &&
            p.appVersion.length <= BackupLimits.MAX_APP_VERSION_LENGTH &&
            p.appVersion.none { it.isISOControl() || it.isInvisibleFormat() }

    private val PLATFORM_NAMES = setOf("ANDROID", "DESKTOP")

    // ---- settings -----------------------------------------------------------------------------

    private val TIMEOUTS = setOf(5, 10, 15, 20, 25, 30)
    private val LANGUAGES = setOf("SYSTEM", "FR", "EN")

    /** Returns the settings reduced to valid values, and the number of values dropped. */
    private fun cleanSettings(s: BackupSettings): Pair<BackupSettings, Int> {
        var invalid = 0
        fun <T> keep(value: T?, ok: (T) -> Boolean): T? {
            if (value == null) return null
            return if (ok(value)) value else { invalid++; null }
        }
        val common = s.common?.let {
            BackupCommonSettings(connectionTimeoutSeconds = keep(it.connectionTimeoutSeconds) { v -> v in TIMEOUTS })
        }
        val android = s.android?.let {
            BackupAndroidSettings(
                terminalFontSize = keep(it.terminalFontSize) { v -> v in 12..46 },
                scrollbackLines = keep(it.scrollbackLines) { v -> v in 1000..100_000 },
                keepAliveIntervalSeconds = keep(it.keepAliveIntervalSeconds) { v -> v in 0..120 },
                autoReconnect = it.autoReconnect,
            )
        }
        val desktop = s.desktop?.let {
            BackupDesktopSettings(
                terminalFontSizePx = keep(it.terminalFontSizePx) { v -> v in 10..20 },
                language = keep(it.language) { v -> v in LANGUAGES },
            )
        }
        return BackupSettings(common, android, desktop) to invalid
    }
}

/**
 * True for an invisible format char a name or a command must not contain (same list as the SFTP
 * names): bidi controls, U+200B, U+2060 to U+206F, U+FEFF, U+FFF9 to U+FFFB, U+00AD, U+2028, U+2029,
 * U+17B4 and U+17B5. ZWJ, ZWNJ and variation selectors stay allowed (Persian, Indic scripts, emoji).
 * All are in the basic plane. Written as code points, never as raw characters.
 */
internal fun Char.isInvisibleFormat(): Boolean {
    val c = this.code
    return c == 0x00AD || c == 0x061C || c == 0x200B || c == 0x200E || c == 0x200F ||
        c == 0x2028 || c == 0x2029 || c == 0xFEFF ||
        c in 0x202A..0x202E || c in 0x2060..0x206F || c in 0xFFF9..0xFFFB ||
        c in 0x17B4..0x17B5
}
