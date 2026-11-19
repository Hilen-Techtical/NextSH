// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.vault

import fr.techtical.nextsh.shared.domain.repository.HostRepository
import fr.techtical.nextsh.shared.domain.repository.SshKeyRepository
import fr.techtical.nextsh.shared.domain.vault.VaultManager

/**
 * Wrappers for a long write loop that lives outside the wipe (the host import of `:shared` writes one row
 * then its secrets, entry by entry, with no transaction and no way to wait for the wipe): every WRITE
 * checks the gate first and fails with a fixed text when the app is closed, so that nothing is written
 * after the tables were erased. Reads and deletions go through: the rollback of the entry in progress
 * must still work.
 */
internal fun checkWipeOpen(gate: VaultWipeGate) {
    check(!gate.isClosed()) { "The vault is being wiped" }
}

internal class WipeGatedHostRepository(
    private val delegate: HostRepository,
    private val gate: VaultWipeGate,
) : HostRepository by delegate {
    override suspend fun save(host: fr.techtical.nextsh.shared.domain.model.Host) {
        checkWipeOpen(gate)
        delegate.save(host)
    }

    override suspend fun update(host: fr.techtical.nextsh.shared.domain.model.Host) {
        checkWipeOpen(gate)
        delegate.update(host)
    }
}

internal class WipeGatedSshKeyRepository(
    private val delegate: SshKeyRepository,
    private val gate: VaultWipeGate,
) : SshKeyRepository by delegate {
    override suspend fun save(key: fr.techtical.nextsh.shared.domain.model.SshKey) {
        checkWipeOpen(gate)
        delegate.save(key)
    }

    override suspend fun update(key: fr.techtical.nextsh.shared.domain.model.SshKey) {
        checkWipeOpen(gate)
        delegate.update(key)
    }
}

internal class WipeGatedVaultManager(
    private val delegate: VaultManager,
    private val gate: VaultWipeGate,
) : VaultManager by delegate {
    override suspend fun storePassword(credentialId: String, password: CharArray) {
        checkWipeOpen(gate)
        delegate.storePassword(credentialId, password)
    }

    override suspend fun storePrivateKey(keyId: String, privateKeyPem: String) {
        checkWipeOpen(gate)
        delegate.storePrivateKey(keyId, privateKeyPem)
    }

    override suspend fun storeCertificate(certId: String, certPem: String) {
        checkWipeOpen(gate)
        delegate.storeCertificate(certId, certPem)
    }

    override suspend fun storeKeyPassphrase(keyId: String, passphrase: CharArray) {
        checkWipeOpen(gate)
        delegate.storeKeyPassphrase(keyId, passphrase)
    }
}
