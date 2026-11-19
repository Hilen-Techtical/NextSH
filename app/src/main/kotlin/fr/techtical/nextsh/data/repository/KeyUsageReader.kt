// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.data.repository

import fr.techtical.nextsh.data.db.dao.HostDao
import fr.techtical.nextsh.data.db.dao.TunnelDao
import fr.techtical.nextsh.domain.model.AuthType
import fr.techtical.nextsh.shared.util.errorClass
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import javax.inject.Inject

/**
 * What depends on one SSH key: the hosts that connect with it and the tunnels that go through
 * those hosts.
 *
 * [hostLabels] are the labels of the live hosts, alphabetical. They are shown on screen only
 * (the dialog that warns before the key is deleted): never log them.
 */
data class KeyUsage(
    val hostLabels: List<String>,
    val tunnelCount: Int,
) {
    companion object {
        /** Nothing uses the key. */
        val NONE = KeyUsage(hostLabels = emptyList(), tunnelCount = 0)
    }
}

/**
 * Read-only lookup of what still uses a key, asked before the key is deleted (the Vault warns
 * instead of letting hosts break silently). No write, no schema: two queries on the existing
 * tables.
 *
 * A host designates its key by [fr.techtical.nextsh.domain.model.Host.credentialId] when its
 * auth type is one of [KEY_AUTH_TYPES]. A PASSWORD host can carry the id of a key by mistake (a
 * 1.0.0 defect): it does not use the key and does not count. Deleted hosts (soft delete) and
 * deleted tunnels do not count either.
 */
class KeyUsageReader @Inject constructor(
    private val hostDao: HostDao,
    private val tunnelDao: TunnelDao,
) {

    /**
     * What depends on the key [keyId]. A failing read of the hosts is thrown (the caller decides
     * what to show: without the hosts there is nothing to warn about). A failing count of the
     * tunnels is not: the hosts that were read are still worth the warning, so they are returned
     * with no tunnel counted, and only the class of the failure is logged.
     */
    suspend fun usageOf(keyId: String): KeyUsage {
        if (keyId.isBlank()) return KeyUsage.NONE
        val labels = hostDao.getLabelsUsingKey(keyId, KEY_AUTH_TYPES)
        // No host, so no tunnel to count: the second query is skipped.
        if (labels.isEmpty()) return KeyUsage.NONE
        val tunnels = try {
            tunnelDao.countLiveTunnelsOfHostsUsingKey(keyId, KEY_AUTH_TYPES)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("Tunnel count for a key failed (${errorClass(e)})")
            0
        }
        return KeyUsage(labels, tunnels)
    }

    companion object {
        /**
         * The auth types whose credentialId is the id of a key. Spelled out on purpose: an auth
         * type added later is not counted until someone decides it carries a key id (a test
         * compares this list with the enum).
         */
        val KEY_AUTH_TYPES: List<String> = listOf(
            AuthType.SSH_KEY,
            AuthType.CERTIFICATE,
            AuthType.FIDO2,
            AuthType.BIOMETRIC_KEY,
        ).map { it.name }
    }
}
