// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.ui.conflict

import fr.techtical.nextsh.shared.core.sync.CredentialType
import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.model.Fido2Mode
import fr.techtical.nextsh.shared.domain.model.SshKeyType
import fr.techtical.nextsh.shared.domain.model.TunnelType

/**
 * Typed, language-neutral model of a conflict card.
 *
 * Nothing here is a user-facing sentence: a row is a [ConflictField] (what the row is) and a
 * [ConflictValue] (what it holds), a card has a [ConflictTitle]. Each screen turns them into
 * text with its own string resources, so a user reads the card in the app language. The only
 * texts carried by the model are the ones the user typed (labels, group, command...), the
 * data the peer sent (host address, ports, paths) and abbreviated ids, none of which is
 * translated.
 *
 * The legacy API in `ConflictUiModel.kt` ([DecodedEntity], [decodeEntity], [diffFields],
 * [displayName]) is a French rendering of this model: both come from the same decoding
 * ([decodeTypedEntity]), so they cannot diverge.
 */

/**
 * One displayable field of a conflict card, for every synced entity type.
 *
 * It is also the key of the comparison between the local and the remote version
 * ([diffTypedFields]): two rows with the same field are compared by value. A field is
 * shared by several entity types only when it has the same meaning in each ([LABEL],
 * [FAVORITE]); fields that look alike but differ in meaning are separate entries
 * ([TUNNEL_TYPE] and [CREDENTIAL_TYPE]).
 *
 * Never rename or remove an entry without adapting the Android and the Desktop screens:
 * both map each entry to a string resource with an exhaustive `when`.
 */
enum class ConflictField {
    /** Label typed by the user. Host, tunnel, SSH key and snippet. */
    LABEL,

    /** Name typed by the user. Custom terminal theme. */
    NAME,

    /** `user@hostname:port`. Host. */
    ADDRESS,

    /** How the host authenticates ([ConflictValue.AuthMethod]). Host. */
    AUTH_TYPE,

    /** Group the host belongs to, or none. Host. */
    GROUP,

    /** Favorite flag. Host and tunnel. */
    FAVORITE,

    /** Keepalive interval, raw value in seconds ([ConflictValue.Seconds]). Host. */
    KEEP_ALIVE,

    /** Automatic reconnection flag. Host. */
    AUTO_RECONNECT,

    /** Terminal theme: built-in preset or custom theme. Host. */
    TERMINAL_THEME,

    /** FIDO2 mode, or none ([ConflictValue.Fido2]). Host. */
    FIDO2_MODE,

    /** Shortened id of the stored password, SSH key or certificate. Host. */
    CREDENTIAL_REFERENCE,

    /** Kind of tunnel ([ConflictValue.TunnelKind]). Tunnel. */
    TUNNEL_TYPE,

    /** `localPort -> remoteHost:remotePort`. Tunnel. */
    FORWARDING,

    /** Shortened id of the host the tunnel goes through. Tunnel. */
    HOST_REFERENCE,

    /** Path the in-app browser opens. Tunnel. */
    BROWSER_PATH,

    /** Automatic start flag. Tunnel. */
    AUTO_START,

    /** Open-the-browser-on-connect flag. Tunnel. */
    OPEN_BROWSER_ON_CONNECT,

    /** Keep-the-tunnel-alive-after-the-browser-closes flag. Tunnel. */
    KEEP_ALIVE_AFTER_BROWSER_CLOSE,

    /** Kind of SSH key ([ConflictValue.SshKeyKind]). SSH key. */
    KEY_TYPE,

    /** Public key, cut after 30 characters. SSH key. */
    PUBLIC_KEY,

    /** Category typed by the user, or none. Snippet. */
    CATEGORY,

    /** Command, cut after 60 characters. Snippet. */
    COMMAND,

    /** Background color as `#RRGGBB`. Custom terminal theme. */
    BACKGROUND,

    /** Foreground (text) color as `#RRGGBB`. Custom terminal theme. */
    FOREGROUND,

    /** Kind of credential ([ConflictValue.CredentialKind]). Credential. */
    CREDENTIAL_TYPE,

    /** Shortened id of the credential. Credential. */
    CREDENTIAL_ID,

    /** The secret itself, always [ConflictValue.Encrypted]. Credential. */
    ENCRYPTED_CONTENT,

    /**
     * Tombstone marker, always [ConflictValue.YesNo] with `true`. The only row of a deleted
     * entity, whatever its type.
     */
    DELETED,
}

/**
 * The value of one row, as data. A screen builds the text from it with its own resources.
 *
 * Values are compared with `equals` by [diffTypedFields]. What is cut for display is cut
 * here (see each case), so the comparison sees what the card shows, like the legacy API.
 */
sealed interface ConflictValue {

    /**
     * Text to show as is, not translated: typed by the user (group, category), sent by the
     * peer (host address, forwarding, path, public key, command) or an abbreviated id.
     * Already cut for display; when it was cut, it ends with an ellipsis.
     */
    data class Text(val text: String) : ConflictValue

    /** A flag. Shown as yes or no. */
    data class YesNo(val value: Boolean) : ConflictValue

    /** A duration. The raw number, no meaning is given to any value (0 included). */
    data class Seconds(val seconds: Int) : ConflictValue

    /** No value (no group, no FIDO2 mode, blank id or theme). Shown as a placeholder. */
    data object None : ConflictValue

    /**
     * Name of a built-in terminal preset (the enum name, e.g. `DRACULA`). The presets live in
     * each app, which maps the name to its own display name and shows an unknown name as is.
     * Capped at 32 characters, with an ellipsis, when it comes from a peer.
     */
    data class PresetTheme(val name: String) : ConflictValue

    /**
     * A custom terminal theme: the first 8 characters of its id. Shown as "custom", followed
     * by the prefix and an ellipsis (the id is never shown whole).
     */
    data class CustomTheme(val idPrefix: String) : ConflictValue

    /** FIDO2 mode of a host. Shown with the apps' own name for the mode. */
    data class Fido2(val mode: Fido2Mode) : ConflictValue

    /** Authentication type of a host. */
    data class AuthMethod(val authType: AuthType) : ConflictValue

    /** Type of a tunnel. */
    data class TunnelKind(val tunnelType: TunnelType) : ConflictValue

    /** Type of an SSH key. */
    data class SshKeyKind(val keyType: SshKeyType) : ConflictValue

    /** Type of a credential (password, private key, passphrase, certificate). */
    data class CredentialKind(val credentialType: CredentialType) : ConflictValue

    /**
     * Content that is encrypted and never shown (the payload of a credential). Carries no
     * data on purpose: nothing about the secret can reach a screen.
     */
    data object Encrypted : ConflictValue
}

/** Title of a conflict card. */
sealed interface ConflictTitle {

    /** The label (or name) typed by the user. */
    data class Label(val text: String) : ConflictTitle

    /** A credential has no label of its own: it is titled by its type. */
    data class Credential(val type: CredentialType) : ConflictTitle

    /** The entity is deleted on this side: there is no label to show. */
    data object Deleted : ConflictTitle
}

/** One row of a conflict card. */
data class ConflictFieldRow(
    val field: ConflictField,
    val value: ConflictValue,
)

/**
 * A decoded entity in the typed model: the title and the ordered rows of one side of a
 * conflict card. The order of [fields] is the order the card shows them in.
 */
data class TypedEntity(
    val title: ConflictTitle,
    val fields: List<ConflictFieldRow>,
)

/**
 * Returns the fields whose value differs between [local] and [remote], or that exist on one
 * side only (a deleted entity has a single [ConflictField.DELETED] row, so everything else
 * differs from a live one). Typed counterpart of [diffFields].
 */
fun diffTypedFields(local: TypedEntity, remote: TypedEntity): Set<ConflictField> {
    val localMap = local.fields.associate { it.field to it.value }
    val remoteMap = remote.fields.associate { it.field to it.value }
    val allFields = localMap.keys + remoteMap.keys
    return allFields.filter { field -> localMap[field] != remoteMap[field] }.toSet()
}
