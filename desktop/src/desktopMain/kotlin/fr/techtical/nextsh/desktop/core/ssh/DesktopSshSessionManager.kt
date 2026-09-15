// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.ssh

import fr.techtical.nextsh.desktop.core.auth.fido2.Fido2Signer
import fr.techtical.nextsh.desktop.core.auth.fido2.YubiKitFidoManager
import fr.techtical.nextsh.desktop.core.ssh.fido2.SkKeyAlgorithm
import fr.techtical.nextsh.shared.core.ssh.fido2.SkAuthMethod
import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.SessionStatus
import fr.techtical.nextsh.shared.domain.model.SshErrorCode
import fr.techtical.nextsh.shared.domain.model.SshKey
import fr.techtical.nextsh.shared.domain.model.SshKeyType
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.shared.domain.model.SshSession
import fr.techtical.nextsh.shared.domain.ssh.SshSessionManager
import fr.techtical.nextsh.shared.util.randomUuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.userauth.UserAuthException
import java.io.IOException
import java.util.Arrays
import java.util.concurrent.ConcurrentHashMap

private const val DEFAULT_COLUMNS = 120
private const val DEFAULT_ROWS = 40

class DesktopSshSessionManager(
    private val hostKeyVerifier: DesktopKnownHostsVerifier,
    private val fido2Signer: Fido2Signer? = null,
    /**
     * Délai (ms) alloué à l'établissement TCP (`SSHClient.connectTimeout`),
     * équivalent `ssh -o ConnectTimeout`. Câblé par [fr.techtical.nextsh.desktop.data.di.DesktopContainer]
     * sur le réglage « Timeout de connexion » (Settings, 5-30 s). Le défaut
     * ci-dessous ne sert qu'aux appels non câblés (tests, previews).
     *
     * Distinct de `host.keepAliveSeconds` : ce dernier pilote UNIQUEMENT le
     * `keepAliveInterval` post-connexion, pas le délai de connexion.
     */
    private val connectTimeoutMsProvider: () -> Int = { 10_000 },
) : SshSessionManager {

    private val terminals = ConcurrentHashMap<String, DesktopSshTerminalSession>()
    private val _sessions = MutableStateFlow<List<SshSession>>(emptyList())
    override val sessions: StateFlow<List<SshSession>> = _sessions.asStateFlow()

    fun getTerminalSession(sessionId: String): DesktopSshTerminalSession? = terminals[sessionId]

    override suspend fun connectWithPassword(
        host: Host,
        password: CharArray,
    ): SshResult<SshSession> = withContext(Dispatchers.IO) {
        try {
            val config = DefaultConfig().apply { keepAliveProvider = KeepAliveProvider.HEARTBEAT }
            val sshClient = SSHClient(config).apply {
                // Phase 1.x: fingerprint pinning / known_hosts not wired yet (Wave 4 target).
                addHostKeyVerifier(hostKeyVerifier)
                connectTimeout = connectTimeoutMsProvider()
                // Borne l'authentification/handshake: constante interne, miroir d'Android.
                timeout = 30_000
            }
            sshClient.connect(host.hostname, host.port)
            sshClient.connection.keepAlive.keepAliveInterval = host.keepAliveSeconds.coerceIn(5, 300)
            try {
                sshClient.authPassword(host.username, password)
            } finally {
                Arrays.fill(password, '\u0000')
            }
            val session = sshClient.startSession()
            session.allocatePTY(
                "xterm-256color",
                DEFAULT_COLUMNS, DEFAULT_ROWS,
                0, 0,
                emptyMap(),
            )
            val shell = session.startShell()

            val sessionId = randomUuid()
            val sshSession = SshSession(
                id = sessionId,
                host = host,
                status = SessionStatus.CONNECTED,
                connectedAt = System.currentTimeMillis(),
            )
            val terminal = DesktopSshTerminalSession(
                sshClient = sshClient,
                session = session,
                shell = shell,
                onExit = { removeSession(sessionId) },
            )
            terminals[sessionId] = terminal
            _sessions.value = _sessions.value + sshSession

            SshResult.Success(sshSession)
        } catch (e: UserAuthException) {
            SshResult.Error(SshErrorCode.AUTH_FAILED, authRefusedMessage(e.message))
        } catch (e: IOException) {
            connectErrorResult(e, connectTimeoutMsProvider())
        } catch (e: Exception) {
            SshResult.Error(SshErrorCode.UNKNOWN, unknownSshErrorMessage(e))
        } finally {
            if (password.isNotEmpty()) Arrays.fill(password, '\u0000')
        }
    }

    override suspend fun connectWithKey(
        host: Host,
        privateKeyPem: String,
        passphrase: CharArray?,
    ): SshResult<SshSession> = withContext(Dispatchers.IO) {
        connectInternal(host, passphrase) {
            DesktopSshKeyLoader.loadKeyProviderFromString(privateKeyPem, passphrase)
        }
    }

    override suspend fun connectWithCertificate(
        host: Host,
        privateKeyPem: String,
        certPem: String,
        passphrase: CharArray?,
    ): SshResult<SshSession> = withContext(Dispatchers.IO) {
        connectInternal(host, passphrase) {
            DesktopSshKeyLoader.loadKeyProviderWithCertificate(privateKeyPem, certPem, passphrase)
        }
    }

    /**
     * Connexion SSH via clé FIDO2 (sk-ssh-ed25519 ou sk-ecdsa-sha2-nistp256).
     *
     * Utilise [SkAuthMethod] (shared) directement via [SSHClient.auth]: bypass du
     * pipeline standard [KeyAlgorithm] de SSHJ pour contrôler l'assertion CTAP2.
     *
     * @param host  Hôte avec authType == FIDO2
     * @param skKey Entrée vault de la clé SK-* (doit avoir fido2CredentialId non null)
     */
    suspend fun connectWithFido2(
        host: Host,
        skKey: SshKey,
    ): SshResult<SshSession> = withContext(Dispatchers.IO) {
        val signer = fido2Signer
            ?: return@withContext SshResult.Error(
                SshErrorCode.FIDO2_NO_KEY,
                fido2UnavailableMessage(),
            )

        // Construire le SkKeyHandle depuis la clé vault
        val handle = try {
            DesktopSshKeyLoader.loadSkKeyHandle(skKey)
        } catch (e: IllegalArgumentException) {
            return@withContext SshResult.Error(SshErrorCode.AUTH_FAILED, fido2KeyInvalidMessage(e.message))
        }

        // SkAuthMethod utilise un challengeHandler suspend → appel Fido2Signer.signChallenge
        // (dispatché vers WindowsWebAuthnProvider ou YubiKitFido2Signer selon l'OS)
        val skAuthMethod = SkAuthMethod(
            publicKey = handle.skPublicKey,
            challengeHandler = { signingData ->
                // Le signingData de SkAuthMethod est déjà le buffer de signature RFC 4252.
                // Pour CTAP2 GetAssertion, on a besoin du clientDataHash = SHA-256(signingData).
                // SkAuthMethod passe les bytes bruts au handler ; on effectue le hachage ici.
                val clientDataHash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(signingData)
                val assertion = try {
                    signer.signChallenge(
                        rpId = handle.rpId,
                        clientDataHash = clientDataHash,
                        credentialId = handle.credentialId,
                    )
                } catch (e: YubiKitFidoManager.FidoError.NoDeviceFound) {
                    throw net.schmizz.sshj.userauth.UserAuthException("FIDO2_NO_KEY: ${e.message}", e)
                } catch (e: YubiKitFidoManager.FidoError.UserCancelled) {
                    throw net.schmizz.sshj.userauth.UserAuthException("FIDO2_USER_CANCELLED: ${e.message}", e)
                } catch (e: YubiKitFidoManager.FidoError.TouchTimeout) {
                    throw net.schmizz.sshj.userauth.UserAuthException("FIDO2_TIMEOUT: ${e.message}", e)
                } catch (e: YubiKitFidoManager.FidoError) {
                    throw net.schmizz.sshj.userauth.UserAuthException("FIDO2_ERROR: ${e.message}", e)
                }
                // Extraire flags et counter depuis authData CTAP2 / Windows WebAuthn
                val authData = assertion.authData
                if (authData.size < 37) {
                    throw net.schmizz.sshj.userauth.UserAuthException(
                        "FIDO2 authData trop court (${authData.size} octets, attendu >= 37)"
                    )
                }
                val flags = authData[32]
                val counter = ((authData[33].toInt() and 0xFF) shl 24) or
                    ((authData[34].toInt() and 0xFF) shl 16) or
                    ((authData[35].toInt() and 0xFF) shl 8) or
                    (authData[36].toInt() and 0xFF)
                fr.techtical.nextsh.shared.core.ssh.fido2.SkSshSignature.create(
                    keyType = handle.skPublicKey.keyType,
                    rawSignature = assertion.signature,
                    flags = flags,
                    counter = counter.toUInt(),
                )
            },
        )

        try {
            val config = DefaultConfig().apply {
                keepAliveProvider = KeepAliveProvider.HEARTBEAT
                // Enregistrer le KeyAlgorithm sk-* pour la négociation KEXINIT
                SkKeyAlgorithm.registerIn(this)
            }
            val sshClient = SSHClient(config).apply {
                addHostKeyVerifier(hostKeyVerifier)
                connectTimeout = connectTimeoutMsProvider()
                // Borne l'authentification/handshake: constante interne, miroir d'Android.
                timeout = 30_000
            }
            try {
                sshClient.connect(host.hostname, host.port)
            } catch (e: net.schmizz.sshj.transport.TransportException) {
                return@withContext SshResult.Error(
                    SshErrorCode.HOST_KEY_MISMATCH,
                    "Clé hôte non approuvée : ${e.message ?: "rejet utilisateur"}",
                )
            }
            sshClient.connection.keepAlive.keepAliveInterval = host.keepAliveSeconds.coerceIn(5, 300)

            // Auth via SkAuthMethod (pas authPublickey : on bypasse le KeyAlgorithm standard)
            sshClient.auth(host.username, listOf(skAuthMethod))

            val session = sshClient.startSession()
            session.allocatePTY("xterm-256color", DEFAULT_COLUMNS, DEFAULT_ROWS, 0, 0, emptyMap())
            val shell = session.startShell()

            val sessionId = randomUuid()
            val sshSession = SshSession(
                id = sessionId,
                host = host,
                status = SessionStatus.CONNECTED,
                connectedAt = System.currentTimeMillis(),
            )
            val terminal = DesktopSshTerminalSession(
                sshClient = sshClient,
                session = session,
                shell = shell,
                onExit = { removeSession(sessionId) },
            )
            terminals[sessionId] = terminal
            _sessions.value = _sessions.value + sshSession

            SshResult.Success(sshSession)
        } catch (e: UserAuthException) {
            // Erreurs FIDO2 encodées dans le message UserAuthException : décodage
            // + localisation dans le mapper partagé du package.
            fido2AuthErrorResult(e)
        } catch (e: IOException) {
            connectErrorResult(e, connectTimeoutMsProvider())
        } catch (e: Exception) {
            SshResult.Error(SshErrorCode.UNKNOWN, unknownSshErrorMessage(e))
        }
    }

    /**
     * Shared key/cert authentication flow. [loadKeyProvider] instantiates the
     * SSHJ provider and may throw if [passphrase] is wrong: we translate those
     * errors to [SshErrorCode.AUTH_FAILED] with a message the UI can surface
     * to prompt a passphrase re-entry.
     */
    private suspend fun connectInternal(
        host: Host,
        passphrase: CharArray?,
        loadKeyProvider: () -> net.schmizz.sshj.userauth.keyprovider.KeyProvider,
    ): SshResult<SshSession> {
        try {
            val keyProvider = try {
                loadKeyProvider()
            } catch (e: Exception) {
                return if (DesktopSshKeyLoader.isLikelyPassphraseError(e)) {
                    SshResult.Error(
                        SshErrorCode.AUTH_FAILED,
                        "Passphrase de clé invalide ou manquante",
                    )
                } else {
                    SshResult.Error(
                        SshErrorCode.AUTH_FAILED,
                        "Impossible de charger la clé : ${e.message ?: e::class.simpleName}",
                    )
                }
            }

            val config = DefaultConfig().apply { keepAliveProvider = KeepAliveProvider.HEARTBEAT }
            val sshClient = SSHClient(config).apply {
                addHostKeyVerifier(hostKeyVerifier)
                connectTimeout = connectTimeoutMsProvider()
                // Borne l'authentification/handshake: constante interne, miroir d'Android.
                timeout = 30_000
            }
            try {
                sshClient.connect(host.hostname, host.port)
            } catch (e: net.schmizz.sshj.transport.TransportException) {
                return SshResult.Error(
                    SshErrorCode.HOST_KEY_MISMATCH,
                    "Clé hôte non approuvée : ${e.message ?: "rejet utilisateur"}",
                )
            }
            sshClient.connection.keepAlive.keepAliveInterval = host.keepAliveSeconds.coerceIn(5, 300)
            sshClient.authPublickey(host.username, keyProvider)

            val session = sshClient.startSession()
            session.allocatePTY(
                "xterm-256color",
                DEFAULT_COLUMNS, DEFAULT_ROWS,
                0, 0,
                emptyMap(),
            )
            val shell = session.startShell()

            val sessionId = randomUuid()
            val sshSession = SshSession(
                id = sessionId,
                host = host,
                status = SessionStatus.CONNECTED,
                connectedAt = System.currentTimeMillis(),
            )
            val terminal = DesktopSshTerminalSession(
                sshClient = sshClient,
                session = session,
                shell = shell,
                onExit = { removeSession(sessionId) },
            )
            terminals[sessionId] = terminal
            _sessions.value = _sessions.value + sshSession

            return SshResult.Success(sshSession)
        } catch (e: UserAuthException) {
            // SSHJ wraps the real cause deeply; walk the chain to expose it.
            // Typical chains for Ed25519:
            //  - "Exhausted available authentication methods" → server rejected every
            //    publickey probe → either pubkey missing from authorized_keys or
            //    signing produced an invalid signature.
            //  - Cast errors / Buffer encoding failures live in the cause chain.
            val chain = generateSequence<Throwable>(e) { it.cause }
                .drop(1)
                .mapNotNull { it.message?.takeIf { m -> m.isNotBlank() } }
                .toList()
            val detail = if (chain.isEmpty()) e.message ?: e::class.simpleName ?: "?"
            else "${e.message} [cause: ${chain.joinToString(" → ")}]"
            return SshResult.Error(SshErrorCode.AUTH_FAILED, authRefusedMessage(detail))
        } catch (e: IOException) {
            return connectErrorResult(e, connectTimeoutMsProvider())
        } catch (e: Exception) {
            return SshResult.Error(SshErrorCode.UNKNOWN, unknownSshErrorMessage(e))
        } finally {
            passphrase?.let { Arrays.fill(it, '\u0000') }
        }
    }

    override suspend fun disconnect(sessionId: String) = withContext(Dispatchers.IO) {
        terminals.remove(sessionId)?.shutdown()
        removeSession(sessionId)
    }

    override suspend fun getSession(sessionId: String): SshSession? =
        _sessions.value.firstOrNull { it.id == sessionId }

    override fun isConnected(sessionId: String): Boolean = terminals.containsKey(sessionId)

    private fun removeSession(sessionId: String) {
        terminals.remove(sessionId)
        _sessions.value = _sessions.value.filterNot { it.id == sessionId }
    }
}
