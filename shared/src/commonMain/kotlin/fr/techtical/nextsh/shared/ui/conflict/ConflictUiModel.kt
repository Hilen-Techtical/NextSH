// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

// The legacy API below is deprecated but still implemented, and it refers to itself.
@file:Suppress("DEPRECATION")

package fr.techtical.nextsh.shared.ui.conflict

import fr.techtical.nextsh.shared.core.sync.ConflictSerializer
import fr.techtical.nextsh.shared.core.sync.CredentialType
import fr.techtical.nextsh.shared.core.sync.SyncableEntityType
import fr.techtical.nextsh.shared.domain.model.CustomTerminalTheme
import fr.techtical.nextsh.shared.domain.model.Fido2Mode
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.Snippet
import fr.techtical.nextsh.shared.domain.model.SshKey
import fr.techtical.nextsh.shared.domain.model.TunnelConfig
import fr.techtical.nextsh.shared.domain.tunnel.TunnelBrowserPath

/**
 * Shared UI-facing helpers for rendering a conflict's decoded payload.
 *
 * Pure data layer: no Compose, no Android SDK, no Desktop Swing. The
 * platform screens consume [TypedEntity] to lay out conflict cards;
 * platform-specific concerns (colors, typography, relative-time formatting,
 * and every user-facing string) stay on each side.
 *
 * This file holds the decoding. The typed model (what a row, a value and a title
 * are) is in `ConflictTypedModel.kt`. The legacy French rendering of it
 * ([DecodedEntity], [decodeEntity], [diffFields], [displayName]) is at the end of this
 * file, kept until both screens use the typed model.
 */

/**
 * Decodes [json] (a serialized `SyncEntry`) into the typed rows shown on a conflict card.
 *
 * This is the one place that reads a payload: the legacy [decodeEntity] is rendered from its
 * result, so the two APIs always show the same fields in the same order.
 *
 * Every synced field that can make two versions differ must appear here: a field
 * left out makes the two cards look identical while the conflict is real
 * (`ConflictUiModelTest` fails when a [Host] or [TunnelConfig] property is neither
 * displayed nor explicitly ignored). A tombstone is a single [ConflictField.DELETED] row.
 *
 * Never put a secret here: references to vault entries are shortened ids, and the
 * encrypted payload of a credential is never decoded.
 *
 * Returns null if the JSON is malformed or cannot be decoded into the expected type.
 */
fun decodeTypedEntity(entityType: SyncableEntityType, json: String): TypedEntity? {
    return try {
        when (entityType) {
            SyncableEntityType.HOST -> {
                val entry = ConflictSerializer.decodeHost(json)
                val h: Host? = entry.payload
                if (h == null) {
                    deletedEntity()
                } else {
                    TypedEntity(
                        title = ConflictTitle.Label(h.label),
                        fields = listOf(
                            row(ConflictField.LABEL, text(h.label)),
                            row(ConflictField.ADDRESS, text("${h.username}@${h.hostname}:${h.port}")),
                            row(ConflictField.AUTH_TYPE, ConflictValue.AuthMethod(h.authType)),
                            row(ConflictField.GROUP, optionalText(h.group)),
                            row(ConflictField.FAVORITE, yesNo(h.isFavorite)),
                            // Raw value: Android turns the keepalive off at 0, Desktop clamps it
                            // to 5 s, so no value is given a meaning here.
                            row(ConflictField.KEEP_ALIVE, ConflictValue.Seconds(h.keepAliveSeconds)),
                            row(ConflictField.AUTO_RECONNECT, yesNo(h.autoReconnect)),
                            row(ConflictField.TERMINAL_THEME, terminalThemeValue(h.terminalTheme)),
                            row(ConflictField.FIDO2_MODE, fido2ModeValue(h.fido2Mode)),
                            // Reference to the stored password, SSH key or certificate depending
                            // on authType: a shortened id, never a secret.
                            row(ConflictField.CREDENTIAL_REFERENCE, shortIdValue(h.credentialId)),
                        ),
                    )
                }
            }

            SyncableEntityType.TUNNEL -> {
                val entry = ConflictSerializer.decodeTunnel(json)
                val t: TunnelConfig? = entry.payload
                if (t == null) {
                    deletedEntity()
                } else {
                    TypedEntity(
                        title = ConflictTitle.Label(t.label),
                        fields = listOf(
                            row(ConflictField.LABEL, text(t.label)),
                            row(ConflictField.TUNNEL_TYPE, ConflictValue.TunnelKind(t.type)),
                            row(
                                ConflictField.FORWARDING,
                                text("${t.localPort} → ${t.remoteHost}:${t.remotePort}"),
                            ),
                            row(ConflictField.HOST_REFERENCE, shortIdValue(t.hostId)),
                            // A conflict that differs only by the browser path must be visible.
                            // Shown in full up to the validation limit (a longer value can only
                            // come from a peer and is capped), so any valid difference stays visible.
                            row(ConflictField.BROWSER_PATH, text(t.browserPath.capForDisplay())),
                            row(ConflictField.FAVORITE, yesNo(t.isFavorite)),
                            row(ConflictField.AUTO_START, yesNo(t.autoStart)),
                            row(ConflictField.OPEN_BROWSER_ON_CONNECT, yesNo(t.openBrowserOnConnect)),
                            row(
                                ConflictField.KEEP_ALIVE_AFTER_BROWSER_CLOSE,
                                yesNo(t.keepAliveAfterBrowserClose),
                            ),
                        ),
                    )
                }
            }

            SyncableEntityType.SSH_KEY -> {
                val entry = ConflictSerializer.decodeSshKey(json)
                val k: SshKey? = entry.payload
                if (k == null) {
                    deletedEntity()
                } else {
                    val pubKeyShort = if (k.publicKey.length > PUBLIC_KEY_MAX_DISPLAY) {
                        k.publicKey.take(PUBLIC_KEY_MAX_DISPLAY) + ELLIPSIS
                    } else {
                        k.publicKey
                    }
                    TypedEntity(
                        title = ConflictTitle.Label(k.label),
                        fields = listOf(
                            row(ConflictField.LABEL, text(k.label)),
                            row(ConflictField.KEY_TYPE, ConflictValue.SshKeyKind(k.keyType)),
                            row(ConflictField.PUBLIC_KEY, text(pubKeyShort)),
                        ),
                    )
                }
            }

            SyncableEntityType.SNIPPET -> {
                val entry = ConflictSerializer.decodeSnippet(json)
                val s: Snippet? = entry.payload
                if (s == null) {
                    deletedEntity()
                } else {
                    val cmdShort = if (s.command.length > COMMAND_MAX_DISPLAY) {
                        s.command.take(COMMAND_MAX_DISPLAY) + ELLIPSIS
                    } else {
                        s.command
                    }
                    TypedEntity(
                        title = ConflictTitle.Label(s.label),
                        fields = listOf(
                            row(ConflictField.LABEL, text(s.label)),
                            row(ConflictField.CATEGORY, optionalText(s.category)),
                            row(ConflictField.COMMAND, text(cmdShort)),
                        ),
                    )
                }
            }

            SyncableEntityType.CUSTOM_TERMINAL_THEME -> {
                val entry = ConflictSerializer.decodeCustomTerminalTheme(json)
                val t: CustomTerminalTheme? = entry.payload
                if (t == null) {
                    deletedEntity()
                } else {
                    TypedEntity(
                        title = ConflictTitle.Label(t.name),
                        fields = listOf(
                            row(ConflictField.NAME, text(t.name)),
                            row(ConflictField.BACKGROUND, text(argbHex(t.background))),
                            row(ConflictField.FOREGROUND, text(argbHex(t.foreground))),
                        ),
                    )
                }
            }

            // SECURITY: CredentialEntry.encryptedPayload must NEVER be decrypted or shown.
            // Conflict resolution surfaces metadata only (type + short id + timestamp).
            SyncableEntityType.CREDENTIAL -> {
                val entry = ConflictSerializer.decodeCredential(json)
                val c: fr.techtical.nextsh.shared.core.sync.CredentialEntry? = entry.payload
                if (c == null) {
                    deletedEntity()
                } else {
                    TypedEntity(
                        title = ConflictTitle.Credential(c.type),
                        fields = listOf(
                            row(ConflictField.CREDENTIAL_TYPE, ConflictValue.CredentialKind(c.type)),
                            // Always cut, even when short: a credential id is a full UUID.
                            row(
                                ConflictField.CREDENTIAL_ID,
                                text(c.credentialId.take(SHORT_ID_LENGTH) + ELLIPSIS),
                            ),
                            row(ConflictField.ENCRYPTED_CONTENT, ConflictValue.Encrypted),
                        ),
                    )
                }
            }
        }
    } catch (e: Exception) {
        null
    }
}

/** A tombstone: no label, one "deleted" row. */
private fun deletedEntity(): TypedEntity = TypedEntity(
    title = ConflictTitle.Deleted,
    fields = listOf(row(ConflictField.DELETED, yesNo(true))),
)

private fun row(field: ConflictField, value: ConflictValue): ConflictFieldRow =
    ConflictFieldRow(field, value)

private fun text(value: String): ConflictValue = ConflictValue.Text(value)

private fun yesNo(value: Boolean): ConflictValue = ConflictValue.YesNo(value)

/** [ConflictValue.None] for a null value; an empty string is a value, shown as such. */
private fun optionalText(value: String?): ConflictValue =
    if (value == null) ConflictValue.None else ConflictValue.Text(value)

private fun String.capForDisplay(): String =
    if (length > TunnelBrowserPath.MAX_LENGTH) take(TunnelBrowserPath.MAX_LENGTH) + ELLIPSIS else this

/** First 12 characters of an id, followed by an ellipsis when it was cut; none when blank. */
private fun shortIdValue(id: String): ConflictValue = when {
    id.isBlank() -> ConflictValue.None
    id.length > SHORT_ID_LENGTH -> ConflictValue.Text(id.take(SHORT_ID_LENGTH) + ELLIPSIS)
    else -> ConflictValue.Text(id)
}

private fun fido2ModeValue(mode: Fido2Mode?): ConflictValue =
    if (mode == null) ConflictValue.None else ConflictValue.Fido2(mode)

private val UUID_PATTERN =
    Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

/**
 * `Host.terminalTheme` holds either the name of a built-in preset or the UUID of a custom
 * theme. The presets live in each app (this module has no display name for them), so their
 * name is given as is; a custom UUID becomes its 8 first characters. A value from a peer is
 * capped.
 */
private fun terminalThemeValue(theme: String): ConflictValue = when {
    theme.isBlank() -> ConflictValue.None
    UUID_PATTERN.matches(theme) -> ConflictValue.CustomTheme(theme.take(CUSTOM_THEME_ID_LENGTH))
    theme.length > THEME_NAME_MAX_DISPLAY ->
        ConflictValue.PresetTheme(theme.take(THEME_NAME_MAX_DISPLAY) + ELLIPSIS)
    else -> ConflictValue.PresetTheme(theme)
}

private const val ELLIPSIS = "…"
private const val SHORT_ID_LENGTH = 12
private const val CUSTOM_THEME_ID_LENGTH = 8
private const val THEME_NAME_MAX_DISPLAY = 32
private const val PUBLIC_KEY_MAX_DISPLAY = 30
private const val COMMAND_MAX_DISPLAY = 60

/** Formats an ARGB int as a #RRGGBB hex string for display in conflict cards. */
private fun argbHex(argb: Int): String {
    val rgb = argb and 0xFFFFFF
    val hex = rgb.toString(16).uppercase().padStart(6, '0')
    return "#$hex"
}

// ── Legacy API (French wording) ───────────────────────────────────────────────
//
// TO REMOVE once the Android and Desktop conflict screens use the typed model
// ([decodeTypedEntity], [TypedEntity], [diffTypedFields]): everything below, plus the
// tests that pin it, can go in one commit. Until then both screens call it and must keep
// compiling and showing the same text.
//
// Nothing here reads a payload. [decodeEntity] renders the result of [decodeTypedEntity]
// into French strings, so a field added to the typed decoding shows up here too (the
// `when` expressions below are exhaustive and fail to compile on a new field or value).

@Deprecated(
    message = "Hard-coded French. Map SyncableEntityType with the screen's own string resources. " +
        "To remove once the Android and Desktop conflict screens use the typed model.",
)
val SyncableEntityType.displayName: String
    get() = when (this) {
        SyncableEntityType.HOST -> "Hôte"
        SyncableEntityType.TUNNEL -> "Tunnel"
        SyncableEntityType.SSH_KEY -> "Clé SSH"
        SyncableEntityType.SNIPPET -> "Snippet"
        SyncableEntityType.CREDENTIAL -> "Credential"
        SyncableEntityType.CUSTOM_TERMINAL_THEME -> "Thème terminal"
    }

private val CredentialType.displayName: String
    get() = when (this) {
        CredentialType.HOST_PASSWORD -> "Mot de passe"
        CredentialType.SSH_PRIVATE_KEY -> "Clé privée SSH"
        CredentialType.SSH_PRIVATE_KEY_PASSPHRASE -> "Passphrase de clé SSH"
        CredentialType.CERTIFICATE -> "Certificat SSH"
    }

/**
 * A simplified representation of a decoded entity for display purposes.
 * [primaryLabel] is the entity's main label (used in the card title).
 * [fields] is an ordered list of (field name → display value) pairs.
 *
 * Labels and values follow the wording of the apps' own screens, short and lowercase,
 * in French only. To remove once the Android and Desktop conflict screens use
 * [TypedEntity].
 */
@Deprecated(
    message = "Hard-coded French. Use TypedEntity and translate with the screen's own string " +
        "resources. To remove once the Android and Desktop conflict screens use the typed model.",
)
data class DecodedEntity(
    val primaryLabel: String,
    val fields: List<Pair<String, String>>,
)

/**
 * French rendering of [decodeTypedEntity]: same payload reading, same fields, same order.
 * Returns null if the JSON is malformed or cannot be decoded into the expected type.
 */
@Deprecated(
    message = "Hard-coded French. Use decodeTypedEntity and translate with the screen's own " +
        "string resources. To remove once the Android and Desktop conflict screens use the " +
        "typed model.",
)
fun decodeEntity(entityType: SyncableEntityType, json: String): DecodedEntity? =
    decodeTypedEntity(entityType, json)?.toLegacy()

/** Returns the set of field names that differ between [local] and [remote]. */
@Deprecated(
    message = "Compares French field names. Use diffTypedFields. To remove once the Android " +
        "and Desktop conflict screens use the typed model.",
)
fun diffFields(local: DecodedEntity, remote: DecodedEntity): Set<String> {
    val localMap = local.fields.toMap()
    val remoteMap = remote.fields.toMap()
    val allKeys = (localMap.keys + remoteMap.keys).toSet()
    return allKeys.filter { key -> localMap[key] != remoteMap[key] }.toSet()
}

private fun TypedEntity.toLegacy(): DecodedEntity = DecodedEntity(
    primaryLabel = title.legacyText(),
    fields = fields.map { it.field.legacyName() to it.value.legacyText() },
)

private fun ConflictTitle.legacyText(): String = when (this) {
    is ConflictTitle.Label -> text
    is ConflictTitle.Credential -> type.displayName
    ConflictTitle.Deleted -> "(supprimé)"
}

/** The row names of the legacy cards: the apps' wording for most, the raw property name for a few. */
private fun ConflictField.legacyName(): String = when (this) {
    ConflictField.LABEL -> "label"
    ConflictField.NAME -> "nom"
    ConflictField.ADDRESS -> "hôte"
    ConflictField.AUTH_TYPE -> "authType"
    ConflictField.GROUP -> "groupe"
    ConflictField.FAVORITE -> "favori"
    ConflictField.KEEP_ALIVE -> "keep-alive"
    ConflictField.AUTO_RECONNECT -> "reconnexion automatique"
    ConflictField.TERMINAL_THEME -> "thème"
    ConflictField.FIDO2_MODE -> "mode FIDO2"
    ConflictField.CREDENTIAL_REFERENCE -> "clé ou mot de passe"
    ConflictField.TUNNEL_TYPE -> "type"
    ConflictField.FORWARDING -> "forwarding"
    ConflictField.HOST_REFERENCE -> "hostId"
    ConflictField.BROWSER_PATH -> "chemin navigateur"
    ConflictField.AUTO_START -> "démarrage automatique"
    ConflictField.OPEN_BROWSER_ON_CONNECT -> "ouvrir le navigateur"
    ConflictField.KEEP_ALIVE_AFTER_BROWSER_CLOSE -> "actif après navigateur"
    ConflictField.KEY_TYPE -> "keyType"
    ConflictField.PUBLIC_KEY -> "publicKey"
    ConflictField.CATEGORY -> "catégorie"
    ConflictField.COMMAND -> "commande"
    ConflictField.BACKGROUND -> "fond"
    ConflictField.FOREGROUND -> "texte"
    ConflictField.CREDENTIAL_TYPE -> "type"
    ConflictField.CREDENTIAL_ID -> "credentialId"
    ConflictField.ENCRYPTED_CONTENT -> "chiffré"
    ConflictField.DELETED -> "supprimé"
}

private fun ConflictValue.legacyText(): String = when (this) {
    is ConflictValue.Text -> text
    is ConflictValue.YesNo -> if (value) "oui" else "non"
    is ConflictValue.Seconds -> "$seconds s"
    ConflictValue.None -> "-"
    is ConflictValue.PresetTheme -> name
    is ConflictValue.CustomTheme -> "personnalisé ($idPrefix$ELLIPSIS)"
    is ConflictValue.Fido2 -> when (mode) {
        Fido2Mode.HARDWARE_KEY -> "clé matérielle"
        Fido2Mode.PASSKEY -> "passkey"
    }
    is ConflictValue.AuthMethod -> authType.name
    is ConflictValue.TunnelKind -> tunnelType.name
    is ConflictValue.SshKeyKind -> keyType.name
    is ConflictValue.CredentialKind -> credentialType.displayName
    ConflictValue.Encrypted -> "oui (contenu non affichable)"
}
