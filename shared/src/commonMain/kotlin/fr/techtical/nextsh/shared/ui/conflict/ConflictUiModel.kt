// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.ui.conflict

import fr.techtical.nextsh.shared.core.sync.ConflictSerializer
import fr.techtical.nextsh.shared.core.sync.CredentialType
import fr.techtical.nextsh.shared.core.sync.SyncableEntityType
import fr.techtical.nextsh.shared.domain.model.CustomTerminalTheme
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.Snippet
import fr.techtical.nextsh.shared.domain.model.SshKey
import fr.techtical.nextsh.shared.domain.model.TunnelConfig

/**
 * Shared UI-facing helpers for rendering a conflict's decoded payload.
 *
 * Pure data layer: no Compose, no Android SDK, no Desktop Swing. The
 * platform screens consume [DecodedEntity] to lay out conflict cards;
 * platform-specific concerns (colors, typography, relative-time formatting)
 * stay on each side.
 */

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
 */
data class DecodedEntity(
    val primaryLabel: String,
    val fields: List<Pair<String, String>>,
)

/** Returns null if the JSON is malformed or cannot be decoded into the expected type. */
fun decodeEntity(entityType: SyncableEntityType, json: String): DecodedEntity? {
    return try {
        when (entityType) {
            SyncableEntityType.HOST -> {
                val entry = ConflictSerializer.decodeHost(json)
                val h: Host? = entry.payload
                if (h == null) {
                    DecodedEntity(
                        primaryLabel = "(supprimé)",
                        fields = listOf("supprimé" to "oui"),
                    )
                } else {
                    DecodedEntity(
                        primaryLabel = h.label,
                        fields = listOf(
                            "label" to h.label,
                            "hôte" to "${h.username}@${h.hostname}:${h.port}",
                            "authType" to h.authType.name,
                            "groupe" to (h.group ?: "-"),
                        ),
                    )
                }
            }

            SyncableEntityType.TUNNEL -> {
                val entry = ConflictSerializer.decodeTunnel(json)
                val t: TunnelConfig? = entry.payload
                if (t == null) {
                    DecodedEntity(
                        primaryLabel = "(supprimé)",
                        fields = listOf("supprimé" to "oui"),
                    )
                } else {
                    DecodedEntity(
                        primaryLabel = t.label,
                        fields = listOf(
                            "label" to t.label,
                            "type" to t.type.name,
                            "forwarding" to "${t.localPort} → ${t.remoteHost}:${t.remotePort}",
                            "hostId" to t.hostId.take(12) + "…",
                        ),
                    )
                }
            }

            SyncableEntityType.SSH_KEY -> {
                val entry = ConflictSerializer.decodeSshKey(json)
                val k: SshKey? = entry.payload
                if (k == null) {
                    DecodedEntity(
                        primaryLabel = "(supprimé)",
                        fields = listOf("supprimé" to "oui"),
                    )
                } else {
                    val pubKeyShort = if (k.publicKey.length > 30) {
                        k.publicKey.take(30) + "…"
                    } else {
                        k.publicKey
                    }
                    DecodedEntity(
                        primaryLabel = k.label,
                        fields = listOf(
                            "label" to k.label,
                            "keyType" to k.keyType.name,
                            "publicKey" to pubKeyShort,
                        ),
                    )
                }
            }

            SyncableEntityType.SNIPPET -> {
                val entry = ConflictSerializer.decodeSnippet(json)
                val s: Snippet? = entry.payload
                if (s == null) {
                    DecodedEntity(
                        primaryLabel = "(supprimé)",
                        fields = listOf("supprimé" to "oui"),
                    )
                } else {
                    val cmdShort = if (s.command.length > 60) {
                        s.command.take(60) + "…"
                    } else {
                        s.command
                    }
                    DecodedEntity(
                        primaryLabel = s.label,
                        fields = listOf(
                            "label" to s.label,
                            "catégorie" to (s.category ?: "-"),
                            "commande" to cmdShort,
                        ),
                    )
                }
            }

            SyncableEntityType.CUSTOM_TERMINAL_THEME -> {
                val entry = ConflictSerializer.decodeCustomTerminalTheme(json)
                val t: CustomTerminalTheme? = entry.payload
                if (t == null) {
                    DecodedEntity(
                        primaryLabel = "(supprimé)",
                        fields = listOf("supprimé" to "oui"),
                    )
                } else {
                    DecodedEntity(
                        primaryLabel = t.name,
                        fields = listOf(
                            "nom" to t.name,
                            "fond" to argbHex(t.background),
                            "texte" to argbHex(t.foreground),
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
                    DecodedEntity(
                        primaryLabel = "(supprimé)",
                        fields = listOf("supprimé" to "oui"),
                    )
                } else {
                    DecodedEntity(
                        primaryLabel = c.type.displayName,
                        fields = listOf(
                            "type" to c.type.displayName,
                            "credentialId" to c.credentialId.take(12) + "…",
                            "chiffré" to "oui (contenu non affichable)",
                        ),
                    )
                }
            }
        }
    } catch (e: Exception) {
        null
    }
}

/** Formats an ARGB int as a #RRGGBB hex string for display in conflict cards. */
private fun argbHex(argb: Int): String {
    val rgb = argb and 0xFFFFFF
    val hex = rgb.toString(16).uppercase().padStart(6, '0')
    return "#$hex"
}

/** Returns the set of field names that differ between [local] and [remote]. */
fun diffFields(local: DecodedEntity, remote: DecodedEntity): Set<String> {
    val localMap = local.fields.toMap()
    val remoteMap = remote.fields.toMap()
    val allKeys = (localMap.keys + remoteMap.keys).toSet()
    return allKeys.filter { key -> localMap[key] != remoteMap[key] }.toSet()
}
