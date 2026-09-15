// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.domain.ssh

import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.shared.domain.model.SshSession
import kotlinx.coroutines.flow.Flow

interface SshSessionManager {
    val sessions: Flow<List<SshSession>>
    suspend fun connectWithPassword(host: Host, password: CharArray): SshResult<SshSession>
    suspend fun connectWithKey(host: Host, privateKeyPem: String, passphrase: CharArray? = null): SshResult<SshSession>
    suspend fun connectWithCertificate(host: Host, privateKeyPem: String, certPem: String, passphrase: CharArray? = null): SshResult<SshSession>
    suspend fun disconnect(sessionId: String)
    suspend fun getSession(sessionId: String): SshSession?
    fun isConnected(sessionId: String): Boolean
}
