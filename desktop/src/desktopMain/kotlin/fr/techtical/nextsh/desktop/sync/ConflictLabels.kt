// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sync

import androidx.compose.runtime.Composable
import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.conflict_credential_type_certificate
import fr.techtical.nextsh.desktop.generated.resources.conflict_credential_type_password
import fr.techtical.nextsh.desktop.generated.resources.conflict_credential_type_ssh_key_passphrase
import fr.techtical.nextsh.desktop.generated.resources.conflict_credential_type_ssh_private_key
import fr.techtical.nextsh.desktop.generated.resources.conflict_entity_credential
import fr.techtical.nextsh.desktop.generated.resources.conflict_entity_host
import fr.techtical.nextsh.desktop.generated.resources.conflict_entity_snippet
import fr.techtical.nextsh.desktop.generated.resources.conflict_entity_ssh_key
import fr.techtical.nextsh.desktop.generated.resources.conflict_entity_terminal_theme
import fr.techtical.nextsh.desktop.generated.resources.conflict_entity_tunnel
import fr.techtical.nextsh.desktop.generated.resources.conflict_fido2_mode_hardware_key
import fr.techtical.nextsh.desktop.generated.resources.conflict_fido2_mode_passkey
import fr.techtical.nextsh.desktop.generated.resources.conflict_field_auth_type
import fr.techtical.nextsh.desktop.generated.resources.conflict_field_auto_start
import fr.techtical.nextsh.desktop.generated.resources.conflict_field_credential_id
import fr.techtical.nextsh.desktop.generated.resources.conflict_field_credential_reference
import fr.techtical.nextsh.desktop.generated.resources.conflict_field_credential_type
import fr.techtical.nextsh.desktop.generated.resources.conflict_field_deleted
import fr.techtical.nextsh.desktop.generated.resources.conflict_field_encrypted_content
import fr.techtical.nextsh.desktop.generated.resources.conflict_field_favorite
import fr.techtical.nextsh.desktop.generated.resources.conflict_field_fido2_mode
import fr.techtical.nextsh.desktop.generated.resources.conflict_field_forwarding
import fr.techtical.nextsh.desktop.generated.resources.conflict_field_group
import fr.techtical.nextsh.desktop.generated.resources.conflict_field_host_reference
import fr.techtical.nextsh.desktop.generated.resources.conflict_field_keep_alive
import fr.techtical.nextsh.desktop.generated.resources.conflict_field_keep_alive_after_browser_close
import fr.techtical.nextsh.desktop.generated.resources.conflict_field_label
import fr.techtical.nextsh.desktop.generated.resources.conflict_field_open_browser_on_connect
import fr.techtical.nextsh.desktop.generated.resources.conflict_field_public_key
import fr.techtical.nextsh.desktop.generated.resources.conflict_field_tunnel_type
import fr.techtical.nextsh.desktop.generated.resources.conflict_title_deleted
import fr.techtical.nextsh.desktop.generated.resources.conflict_value_custom_theme
import fr.techtical.nextsh.desktop.generated.resources.conflict_value_encrypted
import fr.techtical.nextsh.desktop.generated.resources.conflict_value_no
import fr.techtical.nextsh.desktop.generated.resources.conflict_value_seconds
import fr.techtical.nextsh.desktop.generated.resources.conflict_value_yes
import fr.techtical.nextsh.desktop.generated.resources.host_detail_auth_type_biometric
import fr.techtical.nextsh.desktop.generated.resources.host_detail_auth_type_certificate
import fr.techtical.nextsh.desktop.generated.resources.host_detail_auth_type_fido2
import fr.techtical.nextsh.desktop.generated.resources.host_detail_auth_type_password
import fr.techtical.nextsh.desktop.generated.resources.host_detail_auth_type_ssh_key
import fr.techtical.nextsh.desktop.generated.resources.host_detail_auto_reconnect
import fr.techtical.nextsh.desktop.generated.resources.host_detail_hostname
import fr.techtical.nextsh.desktop.generated.resources.host_detail_terminal_theme
import fr.techtical.nextsh.desktop.generated.resources.snippets_field_category
import fr.techtical.nextsh.desktop.generated.resources.snippets_field_command
import fr.techtical.nextsh.desktop.generated.resources.theme_editor_background
import fr.techtical.nextsh.desktop.generated.resources.theme_editor_foreground
import fr.techtical.nextsh.desktop.generated.resources.theme_editor_name
import fr.techtical.nextsh.desktop.generated.resources.tunnel_config_browser_path_label
import fr.techtical.nextsh.desktop.generated.resources.tunnel_config_type_dynamic_label
import fr.techtical.nextsh.desktop.generated.resources.tunnel_config_type_local_label
import fr.techtical.nextsh.desktop.generated.resources.tunnel_config_type_remote_label
import fr.techtical.nextsh.desktop.generated.resources.vault_generate_dialog_type_field
import fr.techtical.nextsh.desktop.generated.resources.vault_keytype_ecdsa256_label
import fr.techtical.nextsh.desktop.generated.resources.vault_keytype_ecdsa384_label
import fr.techtical.nextsh.desktop.generated.resources.vault_keytype_ecdsa521_label
import fr.techtical.nextsh.desktop.generated.resources.vault_keytype_ed25519_label
import fr.techtical.nextsh.desktop.generated.resources.vault_keytype_rsa_label
import fr.techtical.nextsh.desktop.generated.resources.vault_keytype_sk_ecdsa256_label
import fr.techtical.nextsh.desktop.generated.resources.vault_keytype_sk_ed25519_label
import fr.techtical.nextsh.desktop.theme.TerminalThemeId
import fr.techtical.nextsh.shared.core.sync.CredentialType
import fr.techtical.nextsh.shared.core.sync.SyncableEntityType
import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.model.Fido2Mode
import fr.techtical.nextsh.shared.domain.model.SshKeyType
import fr.techtical.nextsh.shared.domain.model.TunnelType
import fr.techtical.nextsh.shared.ui.conflict.ConflictField
import fr.techtical.nextsh.shared.ui.conflict.ConflictTitle
import fr.techtical.nextsh.shared.ui.conflict.ConflictValue
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Turns the typed model of a conflict card (`fr.techtical.nextsh.shared.ui.conflict`) into text
 * in the language of the Desktop. `:shared` only says what a row is ([ConflictField]) and what it
 * holds ([ConflictValue]); every word the user reads comes from the resources of this module.
 *
 * Each `when` below is exhaustive on purpose: a new field, entity type or enum entry in `:shared`
 * does not compile here until it has its string resource (FR and EN, see `strings.xml`).
 *
 * A label of an existing form is reused when it says the same thing (host, tunnel, snippet and
 * theme forms, vault key types); otherwise the string is a `conflict_*` resource.
 */

/** Shown for a value that is absent (no group, no FIDO2 mode...). Not a word, so not translated. */
private const val NO_VALUE = "-"

// ── Names of the rows ─────────────────────────────────────────────────────────

/** The name of a row of a conflict card. A short noun, never a form label with a colon. */
internal fun ConflictField.conflictLabelRes(): StringResource = when (this) {
    ConflictField.LABEL -> Res.string.conflict_field_label
    ConflictField.NAME -> Res.string.theme_editor_name
    ConflictField.ADDRESS -> Res.string.host_detail_hostname
    ConflictField.AUTH_TYPE -> Res.string.conflict_field_auth_type
    ConflictField.GROUP -> Res.string.conflict_field_group
    ConflictField.FAVORITE -> Res.string.conflict_field_favorite
    ConflictField.KEEP_ALIVE -> Res.string.conflict_field_keep_alive
    ConflictField.AUTO_RECONNECT -> Res.string.host_detail_auto_reconnect
    ConflictField.TERMINAL_THEME -> Res.string.host_detail_terminal_theme
    ConflictField.FIDO2_MODE -> Res.string.conflict_field_fido2_mode
    ConflictField.CREDENTIAL_REFERENCE -> Res.string.conflict_field_credential_reference
    ConflictField.TUNNEL_TYPE -> Res.string.conflict_field_tunnel_type
    ConflictField.FORWARDING -> Res.string.conflict_field_forwarding
    ConflictField.HOST_REFERENCE -> Res.string.conflict_field_host_reference
    ConflictField.BROWSER_PATH -> Res.string.tunnel_config_browser_path_label
    ConflictField.AUTO_START -> Res.string.conflict_field_auto_start
    ConflictField.OPEN_BROWSER_ON_CONNECT -> Res.string.conflict_field_open_browser_on_connect
    ConflictField.KEEP_ALIVE_AFTER_BROWSER_CLOSE -> Res.string.conflict_field_keep_alive_after_browser_close
    ConflictField.KEY_TYPE -> Res.string.vault_generate_dialog_type_field
    ConflictField.PUBLIC_KEY -> Res.string.conflict_field_public_key
    ConflictField.CATEGORY -> Res.string.snippets_field_category
    ConflictField.COMMAND -> Res.string.snippets_field_command
    ConflictField.BACKGROUND -> Res.string.theme_editor_background
    ConflictField.FOREGROUND -> Res.string.theme_editor_foreground
    ConflictField.CREDENTIAL_TYPE -> Res.string.conflict_field_credential_type
    ConflictField.CREDENTIAL_ID -> Res.string.conflict_field_credential_id
    ConflictField.ENCRYPTED_CONTENT -> Res.string.conflict_field_encrypted_content
    ConflictField.DELETED -> Res.string.conflict_field_deleted
}

/** The badge of a card: what kind of element is in conflict. */
internal fun SyncableEntityType.conflictLabelRes(): StringResource = when (this) {
    SyncableEntityType.HOST -> Res.string.conflict_entity_host
    SyncableEntityType.TUNNEL -> Res.string.conflict_entity_tunnel
    SyncableEntityType.SSH_KEY -> Res.string.conflict_entity_ssh_key
    SyncableEntityType.SNIPPET -> Res.string.conflict_entity_snippet
    SyncableEntityType.CREDENTIAL -> Res.string.conflict_entity_credential
    SyncableEntityType.CUSTOM_TERMINAL_THEME -> Res.string.conflict_entity_terminal_theme
}

// ── Names of the values ───────────────────────────────────────────────────────

/** Name of a kind of credential, for the type row and for the title of a credential card. */
internal fun CredentialType.conflictLabelRes(): StringResource = when (this) {
    CredentialType.HOST_PASSWORD -> Res.string.conflict_credential_type_password
    CredentialType.SSH_PRIVATE_KEY -> Res.string.conflict_credential_type_ssh_private_key
    CredentialType.SSH_PRIVATE_KEY_PASSPHRASE -> Res.string.conflict_credential_type_ssh_key_passphrase
    CredentialType.CERTIFICATE -> Res.string.conflict_credential_type_certificate
}

/** Same wording as the host form. */
internal fun AuthType.conflictLabelRes(): StringResource = when (this) {
    AuthType.PASSWORD -> Res.string.host_detail_auth_type_password
    AuthType.SSH_KEY -> Res.string.host_detail_auth_type_ssh_key
    AuthType.CERTIFICATE -> Res.string.host_detail_auth_type_certificate
    AuthType.FIDO2 -> Res.string.host_detail_auth_type_fido2
    AuthType.BIOMETRIC_KEY -> Res.string.host_detail_auth_type_biometric
}

/** Same wording as the tunnel form. */
internal fun TunnelType.conflictLabelRes(): StringResource = when (this) {
    TunnelType.LOCAL_FORWARD -> Res.string.tunnel_config_type_local_label
    TunnelType.REMOTE_FORWARD -> Res.string.tunnel_config_type_remote_label
    TunnelType.DYNAMIC_SOCKS5 -> Res.string.tunnel_config_type_dynamic_label
}

/** Short names of the vault (algorithm names, the same in every language). */
internal fun SshKeyType.conflictLabelRes(): StringResource = when (this) {
    SshKeyType.ED25519 -> Res.string.vault_keytype_ed25519_label
    SshKeyType.RSA_4096 -> Res.string.vault_keytype_rsa_label
    SshKeyType.ECDSA_256 -> Res.string.vault_keytype_ecdsa256_label
    SshKeyType.ECDSA_384 -> Res.string.vault_keytype_ecdsa384_label
    SshKeyType.ECDSA_521 -> Res.string.vault_keytype_ecdsa521_label
    SshKeyType.SK_ED25519 -> Res.string.vault_keytype_sk_ed25519_label
    SshKeyType.SK_ECDSA_256 -> Res.string.vault_keytype_sk_ecdsa256_label
}

/** Same wording as the Android app. */
internal fun Fido2Mode.conflictLabelRes(): StringResource = when (this) {
    Fido2Mode.HARDWARE_KEY -> Res.string.conflict_fido2_mode_hardware_key
    Fido2Mode.PASSKEY -> Res.string.conflict_fido2_mode_passkey
}

/**
 * Display name of a built-in terminal theme, or [name] as it is when this Desktop has no preset of
 * that name (a name from a peer running another version, or a name cut for display). Preset names
 * are proper nouns ("Dracula"), so they are not translated.
 */
internal fun presetThemeDisplayName(name: String): String =
    TerminalThemeId.entries.firstOrNull { it.name == name }?.displayName ?: name

// ── Text of a row or of a title ───────────────────────────────────────────────

/**
 * A piece of text of a conflict card before it is read from the resources: either text that is
 * shown as it is, or a string resource with its arguments. Kept apart from the Compose call so
 * that the mapping can be tested without a UI.
 */
internal sealed interface ConflictText {

    /** Typed by the user, sent by the peer or not a word: shown as it is. */
    data class Literal(val text: String) : ConflictText

    /** Read from the resources of the Desktop, [args] filling the positional tokens (`%1$s`). */
    data class Localized(val resource: StringResource, val args: List<Any> = emptyList()) : ConflictText
}

/** The text of the value of a row. */
internal fun ConflictValue.toConflictText(): ConflictText = when (this) {
    is ConflictValue.Text -> ConflictText.Literal(text)
    is ConflictValue.YesNo ->
        ConflictText.Localized(if (value) Res.string.conflict_value_yes else Res.string.conflict_value_no)
    is ConflictValue.Seconds -> ConflictText.Localized(Res.string.conflict_value_seconds, listOf(seconds))
    ConflictValue.None -> ConflictText.Literal(NO_VALUE)
    is ConflictValue.PresetTheme -> ConflictText.Literal(presetThemeDisplayName(name))
    // The string carries the ellipsis: the id of a custom theme is never shown whole.
    is ConflictValue.CustomTheme -> ConflictText.Localized(Res.string.conflict_value_custom_theme, listOf(idPrefix))
    is ConflictValue.Fido2 -> ConflictText.Localized(mode.conflictLabelRes())
    is ConflictValue.AuthMethod -> ConflictText.Localized(authType.conflictLabelRes())
    is ConflictValue.TunnelKind -> ConflictText.Localized(tunnelType.conflictLabelRes())
    is ConflictValue.SshKeyKind -> ConflictText.Localized(keyType.conflictLabelRes())
    is ConflictValue.CredentialKind -> ConflictText.Localized(credentialType.conflictLabelRes())
    ConflictValue.Encrypted -> ConflictText.Localized(Res.string.conflict_value_encrypted)
}

/** The text of the title of a card. */
internal fun ConflictTitle.toConflictText(): ConflictText = when (this) {
    is ConflictTitle.Label -> ConflictText.Literal(text)
    is ConflictTitle.Credential -> ConflictText.Localized(type.conflictLabelRes())
    ConflictTitle.Deleted -> ConflictText.Localized(Res.string.conflict_title_deleted)
}

/**
 * The title of a card, from the title of each version ([local] first, then [remote]; `null` for a
 * version that could not be read). The first title that is not [ConflictTitle.Deleted] wins, so a
 * version deleted on one side only is named after the version that still exists. When every readable
 * version is deleted the title is the deleted marker, and when neither version can be read the card
 * is named after its kind of element ([entityType]), as on Android, never after a fragment of an id.
 */
internal fun conflictCardTitle(
    local: ConflictTitle?,
    remote: ConflictTitle?,
    entityType: SyncableEntityType,
): ConflictText {
    val titles = listOfNotNull(local, remote)
    val title = titles.firstOrNull { it != ConflictTitle.Deleted } ?: titles.firstOrNull()
    return title?.toConflictText() ?: ConflictText.Localized(entityType.conflictLabelRes())
}

/** Reads the text in the language of the Desktop. */
@Composable
internal fun ConflictText.asString(): String = when (this) {
    is ConflictText.Literal -> text
    is ConflictText.Localized -> stringResource(resource, *args.toTypedArray())
}
