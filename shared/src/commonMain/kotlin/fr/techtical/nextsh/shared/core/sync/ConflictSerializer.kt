// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.sync

import fr.techtical.nextsh.shared.domain.model.CustomTerminalTheme
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.Snippet
import fr.techtical.nextsh.shared.domain.model.SshKey
import fr.techtical.nextsh.shared.domain.model.TunnelConfig
import kotlinx.serialization.json.Json

/**
 * Serializes and deserializes [SyncEntry] values for storage in [PendingConflict].
 *
 * The [encode] function requires an unchecked cast because [SyncEntry] is generic and the
 * caller-supplied [entityType] determines the concrete type at runtime. The cast is safe
 * provided callers pair the correct entity instance with its [SyncableEntityType], which
 * is enforced structurally in CrdtEngine / SyncRepository where conflicts are created.
 */
object ConflictSerializer {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(entityType: SyncableEntityType, entry: SyncEntry<*>): String =
        when (entityType) {
            SyncableEntityType.HOST ->
                json.encodeToString(
                    SyncEntry.serializer(Host.serializer()),
                    @Suppress("UNCHECKED_CAST") (entry as SyncEntry<Host>),
                )
            SyncableEntityType.TUNNEL ->
                json.encodeToString(
                    SyncEntry.serializer(TunnelConfig.serializer()),
                    @Suppress("UNCHECKED_CAST") (entry as SyncEntry<TunnelConfig>),
                )
            SyncableEntityType.SSH_KEY ->
                json.encodeToString(
                    SyncEntry.serializer(SshKey.serializer()),
                    @Suppress("UNCHECKED_CAST") (entry as SyncEntry<SshKey>),
                )
            SyncableEntityType.SNIPPET ->
                json.encodeToString(
                    SyncEntry.serializer(Snippet.serializer()),
                    @Suppress("UNCHECKED_CAST") (entry as SyncEntry<Snippet>),
                )
            SyncableEntityType.CREDENTIAL ->
                json.encodeToString(
                    SyncEntry.serializer(CredentialEntry.serializer()),
                    @Suppress("UNCHECKED_CAST") (entry as SyncEntry<CredentialEntry>),
                )
            SyncableEntityType.CUSTOM_TERMINAL_THEME ->
                json.encodeToString(
                    SyncEntry.serializer(CustomTerminalTheme.serializer()),
                    @Suppress("UNCHECKED_CAST") (entry as SyncEntry<CustomTerminalTheme>),
                )
        }

    fun decodeHost(jsonStr: String): SyncEntry<Host> =
        json.decodeFromString(SyncEntry.serializer(Host.serializer()), jsonStr)

    fun decodeTunnel(jsonStr: String): SyncEntry<TunnelConfig> =
        json.decodeFromString(SyncEntry.serializer(TunnelConfig.serializer()), jsonStr)

    fun decodeSshKey(jsonStr: String): SyncEntry<SshKey> =
        json.decodeFromString(SyncEntry.serializer(SshKey.serializer()), jsonStr)

    fun decodeSnippet(jsonStr: String): SyncEntry<Snippet> =
        json.decodeFromString(SyncEntry.serializer(Snippet.serializer()), jsonStr)

    fun decodeCredential(jsonStr: String): SyncEntry<CredentialEntry> =
        json.decodeFromString(SyncEntry.serializer(CredentialEntry.serializer()), jsonStr)

    fun decodeCustomTerminalTheme(jsonStr: String): SyncEntry<CustomTerminalTheme> =
        json.decodeFromString(SyncEntry.serializer(CustomTerminalTheme.serializer()), jsonStr)
}
