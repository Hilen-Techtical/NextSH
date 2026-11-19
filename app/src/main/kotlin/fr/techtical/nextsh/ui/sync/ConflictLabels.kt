// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sync

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import fr.techtical.nextsh.R
import fr.techtical.nextsh.domain.model.Fido2Mode
import fr.techtical.nextsh.domain.model.SshKeyType
import fr.techtical.nextsh.shared.core.sync.CredentialType
import fr.techtical.nextsh.shared.core.sync.SyncableEntityType
import fr.techtical.nextsh.shared.ui.conflict.ConflictField
import fr.techtical.nextsh.shared.ui.conflict.ConflictTitle
import fr.techtical.nextsh.shared.ui.conflict.ConflictValue
import fr.techtical.nextsh.ui.hosts.authTypeLabelRes
import fr.techtical.nextsh.ui.theme.TerminalThemeId
import fr.techtical.nextsh.ui.tunnels.tunnelTypeLabelRes

/**
 * Les mots de la carte d'un conflit de synchronisation.
 *
 * `:shared` décode chaque version en un modèle typé (`TypedEntity` : un titre, des lignes
 * `ConflictField` + `ConflictValue`) sans aucune phrase. Ce fichier dit ces énumérés en ressources, dans
 * la langue de l'utilisateur. Les `when` sont exhaustifs : un champ ou une valeur ajoutés dans `:shared`
 * ne compilent pas ici tant qu'ils n'ont pas leur texte.
 */

/**
 * Le nom du type d'élément en conflit, pour la pastille de la carte.
 *
 * Le nom vient d'une ressource, dans la langue de l'utilisateur, en casse de phrase ("Clé SSH") ;
 * la pastille le met en capitales à l'affichage.
 */
@StringRes
internal fun conflictEntityLabelRes(type: SyncableEntityType): Int = when (type) {
    SyncableEntityType.HOST -> R.string.conflict_entity_host
    SyncableEntityType.TUNNEL -> R.string.conflict_entity_tunnel
    SyncableEntityType.SSH_KEY -> R.string.conflict_entity_ssh_key
    SyncableEntityType.SNIPPET -> R.string.conflict_entity_snippet
    SyncableEntityType.CREDENTIAL -> R.string.conflict_entity_credential
    SyncableEntityType.CUSTOM_TERMINAL_THEME -> R.string.conflict_entity_custom_terminal_theme
}

/**
 * Le nom d'une ligne de la carte. Quand un formulaire de l'app dit déjà la même chose en un mot, c'est son
 * libellé qui est repris (Nom, Groupe, Commande...) ; les autres ont une ressource `conflict_field_*`.
 * Chaque champ a sa propre ressource, même quand deux champs se lisent pareil dans une langue : une
 * traduction peut alors les distinguer sans toucher au code.
 */
@StringRes
internal fun conflictFieldLabelRes(field: ConflictField): Int = when (field) {
    ConflictField.LABEL -> R.string.label_name
    ConflictField.NAME -> R.string.label_theme_name
    ConflictField.ADDRESS -> R.string.conflict_field_address
    ConflictField.AUTH_TYPE -> R.string.section_authentication
    ConflictField.GROUP -> R.string.label_group
    ConflictField.FAVORITE -> R.string.conflict_field_favorite
    ConflictField.KEEP_ALIVE -> R.string.label_keepalive_interval
    ConflictField.AUTO_RECONNECT -> R.string.conflict_field_auto_reconnect
    ConflictField.TERMINAL_THEME -> R.string.conflict_field_terminal_theme
    ConflictField.FIDO2_MODE -> R.string.conflict_field_fido2_mode
    ConflictField.CREDENTIAL_REFERENCE -> R.string.conflict_field_credential_reference
    ConflictField.TUNNEL_TYPE -> R.string.section_tunnel_type
    ConflictField.FORWARDING -> R.string.conflict_field_forwarding
    ConflictField.HOST_REFERENCE -> R.string.conflict_field_host_reference
    ConflictField.BROWSER_PATH -> R.string.label_tunnel_browser_path
    ConflictField.AUTO_START -> R.string.label_auto_start
    ConflictField.OPEN_BROWSER_ON_CONNECT -> R.string.conflict_field_open_browser_on_connect
    ConflictField.KEEP_ALIVE_AFTER_BROWSER_CLOSE -> R.string.conflict_field_keep_alive_after_browser_close
    ConflictField.KEY_TYPE -> R.string.conflict_field_key_type
    ConflictField.PUBLIC_KEY -> R.string.conflict_field_public_key
    ConflictField.CATEGORY -> R.string.conflict_field_category
    ConflictField.COMMAND -> R.string.label_snippet_command
    ConflictField.BACKGROUND -> R.string.label_theme_background
    ConflictField.FOREGROUND -> R.string.label_theme_foreground
    ConflictField.CREDENTIAL_TYPE -> R.string.conflict_field_credential_type
    ConflictField.CREDENTIAL_ID -> R.string.conflict_field_credential_id
    ConflictField.ENCRYPTED_CONTENT -> R.string.conflict_field_encrypted_content
    ConflictField.DELETED -> R.string.conflict_field_deleted
}

/** Le nom d'un type de credential : la valeur de la ligne "type" et le titre de la carte d'un credential. */
@StringRes
internal fun credentialTypeLabelRes(type: CredentialType): Int = when (type) {
    CredentialType.HOST_PASSWORD -> R.string.conflict_value_credential_host_password
    CredentialType.SSH_PRIVATE_KEY -> R.string.conflict_value_credential_ssh_private_key
    CredentialType.SSH_PRIVATE_KEY_PASSPHRASE -> R.string.conflict_value_credential_ssh_private_key_passphrase
    CredentialType.CERTIFICATE -> R.string.conflict_value_credential_certificate
}

/** Le mode FIDO2 d'un hôte, avec les mots de l'écran de l'hôte. */
@StringRes
internal fun fido2ModeLabelRes(mode: Fido2Mode): Int = when (mode) {
    Fido2Mode.HARDWARE_KEY -> R.string.fido2_mode_hardware
    Fido2Mode.PASSKEY -> R.string.fido2_mode_passkey
}

/**
 * Le nom d'un type de clé SSH : un nom d'algorithme, le même dans toutes les langues, écrit comme dans
 * le sélecteur du coffre ("RSA-4096") et non comme l'énuméré ("RSA_4096").
 */
internal fun sshKeyTypeName(type: SshKeyType): String = when (type) {
    SshKeyType.ED25519 -> "ED25519"
    SshKeyType.RSA_4096 -> "RSA-4096"
    SshKeyType.ECDSA_256 -> "ECDSA-256"
    SshKeyType.ECDSA_384 -> "ECDSA-384"
    SshKeyType.ECDSA_521 -> "ECDSA-521"
    SshKeyType.SK_ED25519 -> "SK-ED25519"
    SshKeyType.SK_ECDSA_256 -> "SK-ECDSA-256"
}

/**
 * Le nom d'un thème prédéfini du terminal : celui de l'app ("Techtical Dark"), ou le nom reçu tel quel
 * quand l'app ne le connaît pas (un thème d'une version plus récente, ou un nom coupé par `:shared`).
 */
internal fun presetThemeName(name: String): String =
    TerminalThemeId.entries.firstOrNull { it.name == name }?.displayName ?: name

/** Ce que montre une ligne sans valeur : un simple tiret, qui n'est pas un mot et ne se traduit pas. */
internal const val CONFLICT_NO_VALUE = "-"

/** Le texte d'une valeur de ligne, dans la langue de l'utilisateur. */
@Composable
internal fun conflictValueText(value: ConflictValue): String = when (value) {
    // Typed by the user or sent by the peer: shown as is, never translated.
    is ConflictValue.Text -> value.text
    is ConflictValue.YesNo -> stringResource(if (value.value) R.string.conflict_value_yes else R.string.conflict_value_no)
    is ConflictValue.Seconds -> stringResource(R.string.conflict_value_seconds, value.seconds)
    ConflictValue.None -> CONFLICT_NO_VALUE
    is ConflictValue.PresetTheme -> presetThemeName(value.name)
    is ConflictValue.CustomTheme -> stringResource(R.string.conflict_value_custom_theme, value.idPrefix)
    is ConflictValue.Fido2 -> stringResource(fido2ModeLabelRes(value.mode))
    is ConflictValue.AuthMethod -> stringResource(authTypeLabelRes(value.authType))
    is ConflictValue.TunnelKind -> stringResource(tunnelTypeLabelRes(value.tunnelType))
    is ConflictValue.SshKeyKind -> sshKeyTypeName(value.keyType)
    is ConflictValue.CredentialKind -> stringResource(credentialTypeLabelRes(value.credentialType))
    ConflictValue.Encrypted -> stringResource(R.string.conflict_value_encrypted)
}

/** Le titre de la carte d'un conflit, dans la langue de l'utilisateur. */
@Composable
internal fun conflictTitleText(title: ConflictTitle): String = when (title) {
    is ConflictTitle.Label -> title.text
    is ConflictTitle.Credential -> stringResource(credentialTypeLabelRes(title.type))
    ConflictTitle.Deleted -> stringResource(R.string.conflict_value_deleted_title)
}
