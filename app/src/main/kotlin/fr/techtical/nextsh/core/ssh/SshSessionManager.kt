// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.techtical.nextsh.R
import fr.techtical.nextsh.data.preferences.SettingsDataStore
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.shared.core.ssh.fido2.SkAuthMethod
import fr.techtical.nextsh.shared.core.ssh.fido2.SkSshPublicKey
import fr.techtical.nextsh.shared.core.ssh.fido2.SkSshSignature
import fr.techtical.nextsh.domain.model.Host
import fr.techtical.nextsh.domain.model.SshErrorCode
import fr.techtical.nextsh.domain.model.SshSession
import fr.techtical.nextsh.domain.model.SessionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import timber.log.Timber
import java.io.IOException
import java.security.PublicKey
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class ActiveSession(
    val session: SshSession,
    val client: SSHClient,
)

data class Fido2ChallengeRequest(
    val signingData: ByteArray,
    val rpId: String,
    val keyType: fr.techtical.nextsh.shared.core.ssh.fido2.SkKeyType = fr.techtical.nextsh.shared.core.ssh.fido2.SkKeyType.SK_ECDSA_256,
    val allowedCredentialIds: List<ByteArray> = emptyList(),
)

sealed class Fido2ChallengeResponse {
    data class Signed(val signature: SkSshSignature) : Fido2ChallengeResponse()
    data object Cancelled : Fido2ChallengeResponse()
}

/**
 * Gère les connexions SSH actives.
 * - Connexion par mot de passe ou clé privée
 * - Keep-alive configurable
 * - Reconnexion automatique
 * - Isolation des erreurs via supervisorScope
 * - Vérification interactive des clés hôtes via [hostKeyVerificationCallback]
 */
@Singleton
class SshSessionManager @Inject constructor(
    private val knownHostsVerifier: KnownHostsVerifier,
    private val sshKeyManager: SshKeyManager,
    private val settingsDataStore: SettingsDataStore,
    @ApplicationContext private val context: Context,
) : fr.techtical.nextsh.shared.domain.ssh.SshSessionManager {

    private val _activeSessions = MutableStateFlow<Map<String, ActiveSession>>(emptyMap())

    /** Android-specific: map of sessionId → ActiveSession (includes SSHClient handle). */
    val activeSessions: StateFlow<Map<String, ActiveSession>> = _activeSessions

    /** Shared interface implementation: Flow<List<SshSession>> (platform-neutral). */
    override val sessions: Flow<List<SshSession>> = _activeSessions.map { map ->
        map.values.map { it.session }
    }

    private val clients = ConcurrentHashMap<String, SSHClient>()

    /**
     * Callback appelé lorsqu'une clé hôte inconnue est rencontrée.
     * Doit retourner true (accepter) ou false (refuser) après interaction utilisateur.
     *
     * Exécuté via runBlocking depuis le thread SSH (IO dispatcher) : le callback
     * suspend est résolu sur le scope de l'appelant (viewModelScope).
     */
    var hostKeyVerificationCallback: (suspend (HostKeyVerifyResult.Unknown) -> Boolean)? = null

    /**
     * Callback appelé lorsqu'une signature FIDO2 est nécessaire.
     * L'appelant (ViewModel) doit présenter l'UI de challenge et retourner la signature.
     *
     * Exécuté via runBlocking depuis le thread SSH (IO dispatcher).
     */
    var fido2ChallengeCallback: (suspend (Fido2ChallengeRequest) -> Fido2ChallengeResponse?)? = null

    /**
     * Connecte une session SSH par mot de passe.
     */
    override suspend fun connectWithPassword(
        host: Host,
        password: CharArray,
    ): SshResult<SshSession> = withContext(Dispatchers.IO) {
        val sessionId = UUID.randomUUID().toString()
        updateSessionStatus(sessionId, host, SessionStatus.CONNECTING)
        val timeoutSeconds = currentConnectTimeoutSeconds()

        return@withContext try {
            val client = createSshClient(host)
            // SECURITY: SSHJ 0.38.0 authPassword() n'accepte que String, la copie
            // immutable reste dans le heap JVM jusqu'au GC. Risque accepté (API limitation).
            client.authPassword(host.username, String(password))

            // Sécurité : wipe mdp immédiat
            password.fill('\u0000')

            clients[sessionId] = client
            val session = SshSession(
                id = sessionId,
                host = host,
                status = SessionStatus.CONNECTED,
                connectedAt = System.currentTimeMillis(),
            )
            updateSession(sessionId, session, client)

            Timber.i("SSH connected: ${host.label} (${host.hostname}:${host.port})")
            SshResult.Success(session)

        } catch (e: net.schmizz.sshj.userauth.UserAuthException) {
            password.fill('\u0000')
            cleanupSession(sessionId)
            Timber.w("Auth failed for ${host.label}: ${e.message}")
            SshResult.Error(SshErrorCode.AUTH_FAILED, context.getString(R.string.auth_error_failed))

        } catch (e: net.schmizz.sshj.transport.TransportException) {
            password.fill('\u0000')
            cleanupSession(sessionId)
            Timber.w("Transport error for ${host.label}: ${e.message}")
            val errorCode = if (e.message?.contains("host key", ignoreCase = true) == true ||
                e.message?.contains("verify", ignoreCase = true) == true
            ) {
                SshErrorCode.HOST_KEY_MISMATCH
            } else {
                SshErrorCode.HOST_UNREACHABLE
            }
            val message = if (errorCode == SshErrorCode.HOST_KEY_MISMATCH) {
                "Vérification de la clé hôte échouée : ${e.message}"
            } else {
                connectErrorMessage(context, e, timeoutSeconds)
            }
            SshResult.Error(errorCode, message)

        } catch (e: IOException) {
            password.fill('\u0000')
            cleanupSession(sessionId)
            Timber.w("Connection error for ${host.label}: ${e.message}")
            connectErrorResult(context, e, timeoutSeconds)

        } catch (e: Exception) {
            password.fill('\u0000')
            cleanupSession(sessionId)
            Timber.e(e, "SSH connection error for ${host.label}")
            SshResult.Error(SshErrorCode.UNKNOWN, e.message ?: "Erreur inconnue")
        }
    }

    /**
     * Connecte une session SSH par clé privée.
     */
    override suspend fun connectWithKey(
        host: Host,
        privateKeyPem: String,
        passphrase: CharArray?,
    ): SshResult<SshSession> = withContext(Dispatchers.IO) {
        val sessionId = UUID.randomUUID().toString()
        updateSessionStatus(sessionId, host, SessionStatus.CONNECTING)
        val timeoutSeconds = currentConnectTimeoutSeconds()

        return@withContext try {
            val client = createSshClient(host)

            Timber.d("SSH key auth: loading key")
            val keyProvider = sshKeyManager.loadKeyProviderFromString(privateKeyPem, passphrase)
            Timber.d("SSH key auth: key loaded OK")
            client.authPublickey(host.username, keyProvider)

            passphrase?.fill('\u0000')

            clients[sessionId] = client
            val session = SshSession(
                id = sessionId,
                host = host,
                status = SessionStatus.CONNECTED,
                connectedAt = System.currentTimeMillis(),
            )
            updateSession(sessionId, session, client)

            Timber.i("SSH connected (key): ${host.label} (${host.hostname}:${host.port})")
            SshResult.Success(session)

        } catch (e: Exception) {
            passphrase?.fill('\u0000')
            cleanupSession(sessionId)
            Timber.e(e, "SSH key auth error for ${host.label}")

            if (e is net.schmizz.sshj.userauth.UserAuthException) {
                SshResult.Error(SshErrorCode.AUTH_FAILED, e.message ?: "Erreur inconnue")
            } else if (e is IOException) {
                connectErrorResult(context, e, timeoutSeconds)
            } else {
                SshResult.Error(SshErrorCode.UNKNOWN, e.message ?: "Erreur inconnue")
            }
        }
    }

    /**
     * Connecte une session SSH par clé privée + certificat OpenSSH.
     */
    override suspend fun connectWithCertificate(
        host: Host,
        privateKeyPem: String,
        certificatePem: String,
        passphrase: CharArray?,
    ): SshResult<SshSession> = withContext(Dispatchers.IO) {
        val sessionId = UUID.randomUUID().toString()
        updateSessionStatus(sessionId, host, SessionStatus.CONNECTING)
        val timeoutSeconds = currentConnectTimeoutSeconds()

        return@withContext try {
            val client = createSshClient(host)

            Timber.d("SSH cert auth: loading key+cert for ${host.label}")
            val keyProvider = sshKeyManager.loadKeyProviderWithCertificate(
                privateKeyPem,
                certificatePem,
                passphrase,
            )
            client.authPublickey(host.username, keyProvider)

            passphrase?.fill('\u0000')

            clients[sessionId] = client
            val session = SshSession(
                id = sessionId,
                host = host,
                status = SessionStatus.CONNECTED,
                connectedAt = System.currentTimeMillis(),
            )
            updateSession(sessionId, session, client)

            Timber.i("SSH connected (cert): ${host.label} (${host.hostname}:${host.port})")
            SshResult.Success(session)

        } catch (e: Exception) {
            passphrase?.fill('\u0000')
            cleanupSession(sessionId)
            Timber.e(e, "SSH cert auth error for ${host.label}")

            if (e is net.schmizz.sshj.userauth.UserAuthException) {
                SshResult.Error(SshErrorCode.AUTH_FAILED, e.message ?: "Erreur inconnue")
            } else if (e is IOException) {
                connectErrorResult(context, e, timeoutSeconds)
            } else {
                SshResult.Error(SshErrorCode.UNKNOWN, e.message ?: "Erreur inconnue")
            }
        }
    }

    /**
     * Connecte une session SSH en utilisant un KeyProvider SSHJ.
     * Utilisé pour les clés biométriques dont la clé privée reste dans le Keystore.
     */
    suspend fun connectWithKeyProvider(
        host: Host,
        keyProvider: net.schmizz.sshj.userauth.keyprovider.KeyProvider,
    ): SshResult<SshSession> = withContext(Dispatchers.IO) {
        val sessionId = UUID.randomUUID().toString()
        updateSessionStatus(sessionId, host, SessionStatus.CONNECTING)
        val timeoutSeconds = currentConnectTimeoutSeconds()

        return@withContext try {
            val client = createSshClient(host)
            client.authPublickey(host.username, keyProvider)

            clients[sessionId] = client
            val session = SshSession(
                id = sessionId,
                host = host,
                status = SessionStatus.CONNECTED,
                connectedAt = System.currentTimeMillis(),
            )
            updateSession(sessionId, session, client)

            Timber.i("SSH connected (biometric key): ${host.label} (${host.hostname}:${host.port})")
            SshResult.Success(session)

        } catch (e: net.schmizz.sshj.userauth.UserAuthException) {
            cleanupSession(sessionId)
            Timber.w("Biometric key auth failed for ${host.label}: ${e.message}")
            SshResult.Error(SshErrorCode.AUTH_FAILED, context.getString(R.string.auth_error_failed_biometric))

        } catch (e: Exception) {
            cleanupSession(sessionId)
            Timber.e(e, "SSH biometric key auth error for ${host.label}")
            if (e is IOException) {
                connectErrorResult(context, e, timeoutSeconds)
            } else {
                SshResult.Error(SshErrorCode.UNKNOWN, e.message ?: "Erreur inconnue")
            }
        }
    }

    /**
     * Connecte une session SSH via clé de sécurité FIDO2 (sk-*).
     * La signature est obtenue via [fido2ChallengeCallback] pendant le handshake.
     */
    suspend fun connectWithSkKey(
        host: Host,
        skPublicKey: SkSshPublicKey,
    ): SshResult<SshSession> = withContext(Dispatchers.IO) {
        val sessionId = UUID.randomUUID().toString()
        updateSessionStatus(sessionId, host, SessionStatus.CONNECTING)
        val timeoutSeconds = currentConnectTimeoutSeconds()

        return@withContext try {
            val client = createSshClient(host)

            val challengeCallback = fido2ChallengeCallback
                ?: return@withContext SshResult.Error(
                    SshErrorCode.FIDO2_NO_KEY,
                    "Callback FIDO2 non configuré"
                )

            val skAuthMethod = SkAuthMethod(skPublicKey) { signingData ->
                val request = Fido2ChallengeRequest(
                    signingData = signingData,
                    rpId = skPublicKey.application,
                    keyType = skPublicKey.keyType,
                )
                when (val response = challengeCallback(request)) {
                    is Fido2ChallengeResponse.Signed -> response.signature
                    is Fido2ChallengeResponse.Cancelled -> null
                    null -> null
                }
            }

            client.auth(host.username, skAuthMethod)

            clients[sessionId] = client
            val session = SshSession(
                id = sessionId,
                host = host,
                status = SessionStatus.CONNECTED,
                connectedAt = System.currentTimeMillis(),
            )
            updateSession(sessionId, session, client)

            Timber.i("SSH connected (FIDO2 sk-key): ${host.label} (${host.hostname}:${host.port})")
            SshResult.Success(session)

        } catch (e: net.schmizz.sshj.userauth.UserAuthException) {
            cleanupSession(sessionId)
            Timber.w("FIDO2 auth failed for ${host.label}: ${e.message}")
            SshResult.Error(SshErrorCode.AUTH_FAILED, context.getString(R.string.auth_error_failed_fido2))

        } catch (e: net.schmizz.sshj.transport.TransportException) {
            cleanupSession(sessionId)
            val errorCode = if (e.message?.contains("host key", ignoreCase = true) == true) {
                SshErrorCode.HOST_KEY_MISMATCH
            } else {
                SshErrorCode.HOST_UNREACHABLE
            }
            val message = if (errorCode == SshErrorCode.HOST_KEY_MISMATCH) {
                "Vérification de la clé hôte échouée : ${e.message}"
            } else {
                connectErrorMessage(context, e, timeoutSeconds)
            }
            SshResult.Error(errorCode, message)

        } catch (e: IOException) {
            cleanupSession(sessionId)
            Timber.w("FIDO2 connection error for ${host.label}: ${e.message}")
            connectErrorResult(context, e, timeoutSeconds)

        } catch (e: Exception) {
            cleanupSession(sessionId)
            Timber.e(e, "FIDO2 connection error for ${host.label}")
            SshResult.Error(SshErrorCode.UNKNOWN, e.message ?: "Erreur inconnue")
        }
    }

    /** Déconnecte proprement une session */
    override suspend fun disconnect(sessionId: String): Unit = withContext(Dispatchers.IO) {
        try {
            cleanupSession(sessionId)
            Timber.i("Session $sessionId disconnected")
        } catch (e: Exception) {
            Timber.e(e, "Error disconnecting session $sessionId")
        }
    }

    override suspend fun getSession(sessionId: String): SshSession? =
        _activeSessions.value[sessionId]?.session

    override fun isConnected(sessionId: String): Boolean =
        clients[sessionId]?.isConnected == true

    /** Récupère le SSHClient brut pour lancer un shell ou un tunnel */
    fun getClient(sessionId: String): SSHClient? = clients[sessionId]

    /**
     * Ouvre un shell SSH sur une session existante.
     */
    suspend fun openShell(
        sessionId: String,
        columns: Int = 80,
        rows: Int = 24,
    ): SshResult<Session.Shell> = withContext(Dispatchers.IO) {
        val client = clients[sessionId]
            ?: return@withContext SshResult.Error(
                SshErrorCode.UNKNOWN,
                "Session introuvable : $sessionId"
            )

        return@withContext try {
            val session = client.startSession()
            session.allocatePTY(
                "xterm-256color",
                columns, rows,
                /* widthPixels  */ 0,
                /* heightPixels */ 0,
                /* modes        */ mapOf(),
            )
            val shell = session.startShell()
            Timber.i("Shell SSH ouvert pour session $sessionId (${columns}x$rows)")
            SshResult.Success(shell)
        } catch (e: Exception) {
            Timber.e(e, "Erreur ouverture shell pour session $sessionId")
            SshResult.Error(SshErrorCode.UNKNOWN, e.message ?: "Erreur shell")
        }
    }

    /** Déconnecte toutes les sessions */
    fun disconnectAll() {
        clients.keys.toList().forEach { id ->
            try {
                clients.remove(id)?.disconnect()
            } catch (e: Exception) {
                Timber.w("Error closing session $id: ${e.message}")
            }
        }
        _activeSessions.value = emptyMap()
    }

    // ── Private ──────────────────────────────────────────────────────────────────

    /**
     * Réglage utilisateur « Timeout de connexion » (Settings, slider 5-30 s), en
     * secondes : lu séparément de [createSshClient] pour rester disponible dans
     * les blocs `catch` (message [connectErrorMessage] du timeout).
     */
    private suspend fun currentConnectTimeoutSeconds(): Int =
        settingsDataStore.settingsFlow.first().connectionTimeout.coerceIn(5, 30)

    private suspend fun createSshClient(host: Host): SSHClient {
        val config = DefaultConfig()
        val client = SSHClient(config)

        // Vérificateur interactif : remplace knownHostsVerifier directement
        client.addHostKeyVerifier(InteractiveHostKeyVerifier())

        // Timeout d'établissement TCP piloté par le réglage utilisateur « Timeout de
        // connexion » (slider Settings, 5-30 s), équivalent `ssh -o ConnectTimeout`.
        // Distinct de host.keepAliveSeconds (keepalive post-connexion, plus bas).
        client.connectTimeout = resolveConnectTimeoutMs(settingsDataStore.settingsFlow.first().connectionTimeout)
        // Borne l'authentification/handshake : constante interne, indépendante du réglage.
        client.timeout = 30_000

        client.connect(host.hostname, host.port)

        if (host.keepAliveSeconds > 0) {
            client.connection.keepAlive.keepAliveInterval = host.keepAliveSeconds
        }

        return client
    }

    /**
     * Adaptateur HostKeyVerifier qui délègue à [KnownHostsVerifier.checkKey] et,
     * en cas de première connexion, invoque [hostKeyVerificationCallback] via runBlocking.
     *
     * Exécuté sur le thread SSH (IO) : runBlocking est acceptable ici car on attend
     * la décision de l'utilisateur avant de continuer le handshake.
     */
    private inner class InteractiveHostKeyVerifier : HostKeyVerifier {

        override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
            return when (val result = knownHostsVerifier.checkKey(hostname, port, key)) {
                is HostKeyVerifyResult.Trusted -> true

                is HostKeyVerifyResult.Unknown -> {
                    val callback = hostKeyVerificationCallback
                    if (callback == null) {
                        Timber.w("KnownHosts: hôte inconnu $hostname:$port, refus (pas de callback UI)")
                        return false
                    }
                    val accepted = runBlocking { callback(result) }
                    if (accepted) {
                        knownHostsVerifier.acceptHost(result.hostname, result.algorithm, key.encoded)
                    }
                    accepted
                }

                is HostKeyVerifyResult.Mismatch -> {
                    Timber.e(
                        "KnownHosts: MISMATCH pour $hostname:$port, " +
                        "stocké=${result.storedFingerprint} reçu=${result.receivedFingerprint}"
                    )
                    false
                }
            }
        }

        override fun findExistingAlgorithms(hostname: String, port: Int): List<String> =
            knownHostsVerifier.findExistingAlgorithms(hostname, port)
    }

    private fun updateSessionStatus(sessionId: String, host: Host, status: SessionStatus) {
        val session = SshSession(id = sessionId, host = host, status = status)
        _activeSessions.update { it + (sessionId to ActiveSession(session, SSHClient())) }
    }

    private fun updateSession(sessionId: String, session: SshSession, client: SSHClient) {
        _activeSessions.update { it + (sessionId to ActiveSession(session, client)) }
    }

    private fun cleanupSession(sessionId: String) {
        try {
            clients.remove(sessionId)?.disconnect()
        } catch (_: Exception) { }
        _activeSessions.update { it - sessionId }
    }
}

/**
 * Convertit le réglage utilisateur « Timeout de connexion » (secondes, slider
 * Settings 5-30 s) en délai TCP `SSHClient.connectTimeout` (ms), en clampant
 * une valeur potentiellement corrompue (DataStore éditable/migration ratée).
 *
 * Ne prend PAS `Host` en paramètre : par construction, ce calcul ne peut pas
 * dériver de `host.keepAliveSeconds` (bug historique côté Desktop, voir
 * `DesktopSshSessionManager.connectTimeoutMsProvider`).
 */
internal fun resolveConnectTimeoutMs(connectionTimeoutSeconds: Int): Int =
    connectionTimeoutSeconds.coerceIn(5, 30) * 1000
