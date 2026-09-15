// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.vault

import fr.techtical.nextsh.shared.core.sync.SyncEntry
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.SshKey
import fr.techtical.nextsh.shared.domain.model.TunnelConfig
import fr.techtical.nextsh.shared.domain.repository.HostRepository
import fr.techtical.nextsh.shared.domain.repository.SshKeyRepository
import fr.techtical.nextsh.shared.domain.repository.TunnelRepository
import fr.techtical.nextsh.shared.domain.vault.VaultManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory fakes used by the vault-backup tests. Each repository mutates its
 * backing `MutableStateFlow` on save() so both the observe flow and the snapshot
 * return the same data without needing a real database or coroutine dispatcher.
 */

internal class FakeHostRepositoryMem(initial: List<Host> = emptyList()) : HostRepository {
    private val state = MutableStateFlow(initial)
    override fun observeAll(): Flow<List<Host>> = state.asStateFlow()
    override fun observeByGroup(group: String): Flow<List<Host>> =
        MutableStateFlow(state.value.filter { it.group == group }).asStateFlow()
    override fun observeGroups(): Flow<List<String>> =
        MutableStateFlow(state.value.mapNotNull { it.group }.distinct()).asStateFlow()
    override suspend fun getById(id: String): Host? = state.value.firstOrNull { it.id == id }
    override suspend fun save(host: Host) {
        state.value = state.value.filterNot { it.id == host.id } + host
    }
    override suspend fun update(host: Host) = save(host)
    override suspend fun delete(id: String) {
        state.value = state.value.filterNot { it.id == id }
    }
    override suspend fun updateLastConnected(id: String) = Unit
    override fun observeFavorites(): Flow<List<Host>> =
        MutableStateFlow(state.value.filter { it.isFavorite }).asStateFlow()
    override suspend fun setFavorite(id: String, isFavorite: Boolean) = Unit
    override suspend fun getAllSyncEntries(): List<SyncEntry<Host>> = emptyList()
    override suspend fun upsertSyncEntry(entry: SyncEntry<Host>) = Unit
    override suspend fun hardDelete(id: String) = delete(id)
}

internal class FakeSshKeyRepositoryMem(initial: List<SshKey> = emptyList()) : SshKeyRepository {
    private val state = MutableStateFlow(initial)
    override fun observeAll(): Flow<List<SshKey>> = state.asStateFlow()
    override suspend fun getById(id: String): SshKey? = state.value.firstOrNull { it.id == id }
    override suspend fun save(key: SshKey) {
        state.value = state.value.filterNot { it.id == key.id } + key
    }
    override suspend fun update(key: SshKey) = save(key)
    override suspend fun delete(id: String) {
        state.value = state.value.filterNot { it.id == id }
    }
    override suspend fun getAllSyncEntries(): List<SyncEntry<SshKey>> = emptyList()
    override suspend fun upsertSyncEntry(entry: SyncEntry<SshKey>) = Unit
    override suspend fun hardDelete(id: String) = delete(id)
}

internal class FakeTunnelRepositoryMem(initial: List<TunnelConfig> = emptyList()) : TunnelRepository {
    private val state = MutableStateFlow(initial)
    override fun observeAll(): Flow<List<TunnelConfig>> = state.asStateFlow()
    override fun observeByHost(hostId: String): Flow<List<TunnelConfig>> =
        MutableStateFlow(state.value.filter { it.hostId == hostId }).asStateFlow()
    override suspend fun getById(id: String): TunnelConfig? = state.value.firstOrNull { it.id == id }
    override suspend fun getAutoStartTunnels(): List<TunnelConfig> = state.value.filter { it.autoStart }
    override suspend fun save(config: TunnelConfig) {
        state.value = state.value.filterNot { it.id == config.id } + config
    }
    override suspend fun update(config: TunnelConfig) = save(config)
    override suspend fun delete(id: String) {
        state.value = state.value.filterNot { it.id == id }
    }
    override fun observeFavorites(): Flow<List<TunnelConfig>> =
        MutableStateFlow(state.value.filter { it.isFavorite }).asStateFlow()
    override suspend fun setFavorite(id: String, isFavorite: Boolean) = Unit
    override suspend fun getAllSyncEntries(): List<SyncEntry<TunnelConfig>> = emptyList()
    override suspend fun upsertSyncEntry(entry: SyncEntry<TunnelConfig>) = Unit
    override suspend fun hardDelete(id: String) = delete(id)
}

/**
 * In-memory VaultManager fake. Stores passwords and private keys as byte/string
 * maps: everything else is a no-op with a safe default so the few methods the
 * exporter/importer care about behave correctly.
 */
internal class FakeVaultManagerMem : VaultManager {
    val passwords = mutableMapOf<String, CharArray>()
    val privateKeys = mutableMapOf<String, String>()
    val certs = mutableMapOf<String, String>()
    val keyPassphrases = mutableMapOf<String, CharArray>()

    override suspend fun storePassword(credentialId: String, password: CharArray) {
        passwords[credentialId] = password.copyOf()
    }
    override suspend fun getPassword(credentialId: String): CharArray? = passwords[credentialId]?.copyOf()
    override suspend fun storePrivateKey(keyId: String, privateKeyPem: String) {
        privateKeys[keyId] = privateKeyPem
    }
    override suspend fun getPrivateKey(keyId: String): String? = privateKeys[keyId]
    override suspend fun storeCertificate(certId: String, certPem: String) { certs[certId] = certPem }
    override suspend fun getCertificate(certId: String): String? = certs[certId]
    override suspend fun deleteCertificate(certId: String) { certs.remove(certId) }
    override suspend fun storeKeyPassphrase(keyId: String, passphrase: CharArray) {
        keyPassphrases[keyId] = passphrase.copyOf()
    }
    override suspend fun getKeyPassphrase(keyId: String): CharArray? = keyPassphrases[keyId]?.copyOf()
    override suspend fun deleteKeyPassphrase(keyId: String) { keyPassphrases.remove(keyId) }
    override suspend fun listStoredKeyPassphraseIds(): List<String> = keyPassphrases.keys.toList()
    override suspend fun deleteCredential(credentialId: String) {
        passwords.remove(credentialId)
        privateKeys.remove(credentialId)
        certs.remove(credentialId)
        keyPassphrases.remove(credentialId)
    }
    override suspend fun wipeVault() {
        passwords.clear(); privateKeys.clear(); certs.clear(); keyPassphrases.clear()
    }
    override suspend fun isInitialized(): Boolean = true
    override suspend fun listStoredCredentialIds(): List<String> = passwords.keys.toList()
    override suspend fun listStoredKeyIds(): List<String> = privateKeys.keys.toList()
    override suspend fun listStoredCertificateIds(): List<String> = certs.keys.toList()
}
