// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.techtical.nextsh.R
import fr.techtical.nextsh.core.auth.HwKeyError
import fr.techtical.nextsh.core.vault.VaultWipeGate
import fr.techtical.nextsh.data.preferences.SettingsDataStore
import fr.techtical.nextsh.shared.core.backup.BackupValidator
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.shared.util.LogId
import fr.techtical.nextsh.shared.util.errorClass
import fr.techtical.nextsh.shared.core.ssh.fido2.SkAuthMethod
import fr.techtical.nextsh.shared.core.ssh.fido2.SkSshPublicKey
import fr.techtical.nextsh.shared.core.ssh.fido2.SkSshSignature
import fr.techtical.nextsh.domain.model.Host
import fr.techtical.nextsh.domain.model.SshErrorCode
import fr.techtical.nextsh.domain.model.SshSession
import fr.techtical.nextsh.domain.model.SessionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.DisconnectListener
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.method.AuthMethod
import timber.log.Timber
import java.io.IOException
import java.security.PublicKey
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

data class ActiveSession(
    val session: SshSession,
    /** Null while the session is still CONNECTING: there is no authenticated client to hold yet. */
    val client: SSHClient?,
)

data class Fido2ChallengeRequest(
    val signingData: ByteArray,
    val rpId: String,
    val keyType: fr.techtical.nextsh.shared.core.ssh.fido2.SkKeyType = fr.techtical.nextsh.shared.core.ssh.fido2.SkKeyType.SK_ECDSA_256,
    val allowedCredentialIds: List<ByteArray> = emptyList(),
)

sealed class Fido2ChallengeResponse {
    data class Signed(val signature: SkSshSignature) : Fido2ChallengeResponse()

    /** The user cancelled the prompt. */
    data object Cancelled : Fido2ChallengeResponse()

    /**
     * The security key failed (communication error, key removed, PIN blocked, refused by the key;
     * a key that never shows up is not this, the deadline of the prompt ends that wait): the user
     * did not cancel anything, and the server did not refuse anything. [reason] says why, for the
     * message the user ends up reading.
     */
    data class KeyError(val reason: HwKeyError) : Fido2ChallengeResponse()

    /**
     * The app was not on screen when the prompt was asked for, so no sheet was shown and the turn
     * was not taken: nobody could have answered (see [Fido2PromptCoordinator.prompt]).
     */
    data object NoScreen : Fido2ChallengeResponse()
}

/**
 * Gère les connexions SSH actives.
 * - Connexion par mot de passe ou clé privée
 * - Keepalive du transport : toutes les sessions envoient `keepalive@openssh.com` au rythme du
 *   `keepAliveSeconds` de l'hôte ([transportKeepAliveSeconds], 15 à 60 s, 0 désactive). Le fournisseur
 *   est KEEP_ALIVE (jamais HEARTBEAT, dont le `SSH_MSG_IGNORE` partirait pendant la poignée de main
 *   et casserait le strict KEX) et l'intervalle est posé AVANT `connect`, car SSHJ ne démarre son
 *   fil qu'à la connexion et seulement si l'intervalle est déjà positif. La vivacité des sessions
 *   qui portent un tunnel est en plus surveillée par [TunnelLivenessWatchdog], au même rythme
 *   ([livenessCadenceSeconds])
 * - Isolation des erreurs via supervisorScope
 * - Vérification interactive des clés hôtes via [hostKeyVerificationCallback]
 *
 * Pas de reconnexion automatique ici : celle des tunnels est dans `ReconnectTunnelsUseCase`, que
 * `SshTunnelManager` prévient quand un tunnel s'arrête de lui-même, par exemple à la fin de sa
 * session ([addSessionEndListener]).
 *
 * Ownership of a session. A session is shared: a terminal tab, the SFTP browser, a transfer in the
 * background and the tunnels may all run on the same one, and none of them may close it under the
 * others. So it is counted:
 * - a successful `connect*` returns a session that holds ONE reference, owned by the caller;
 * - whoever else wants to use it first takes its own with [acquire], which fails (false) when the
 *   session is unknown or no longer alive, and the caller then does without;
 * - every holder gives its reference back with [release], once, when it is done. The last release
 *   closes the client and takes the session out of the registry. [release] is idempotent: an unknown
 *   id is a no-op and the count never goes below zero, so a holder may release a session that died
 *   under it, or release twice, without harm;
 * - a holder that hands its reference to another (the host list to the terminal, a tunnel provider
 *   to the tunnel manager) says so in its own contract, and the receiver releases it. A reference
 *   is never "shared" between two holders: each owns exactly one.
 *
 * When the transport dies, the session leaves the registry whatever its count, and the
 * [SessionEndListener]s are told ([addSessionEndListener]): the holders do not have to notice, and
 * their late [release] is a no-op. The one SSHJ transport listener of a session is installed here,
 * when it is registered: nobody else sets it, or they would overwrite each other.
 *
 * [disconnect] is the forced way, kept for the shared interface: it closes the session for everyone,
 * and no code of the app uses it any more.
 */
@Singleton
class SshSessionManager @Inject constructor(
    private val knownHostsVerifier: KnownHostsVerifier,
    private val sshKeyManager: SshKeyManager,
    private val settingsDataStore: SettingsDataStore,
    @ApplicationContext private val context: Context,
    /**
     * The wipe of the vault: while the app is closed no session is registered, see [registerSession], and
     * [closeAllAndWait] is the closing that comes before the erase.
     */
    private val wipeGate: VaultWipeGate,
) : fr.techtical.nextsh.shared.domain.ssh.SshSessionManager {

    /** Without the wipe gate of the app (tests). Not an injection point. */
    constructor(
        knownHostsVerifier: KnownHostsVerifier,
        sshKeyManager: SshKeyManager,
        settingsDataStore: SettingsDataStore,
        context: Context,
    ) : this(knownHostsVerifier, sshKeyManager, settingsDataStore, context, VaultWipeGate())

    private val _activeSessions = MutableStateFlow<Map<String, ActiveSession>>(emptyMap())

    /** Android-specific: map of sessionId → ActiveSession (includes SSHClient handle). */
    val activeSessions: StateFlow<Map<String, ActiveSession>> = _activeSessions

    /** Shared interface implementation: Flow<List<SshSession>> (platform-neutral). */
    override val sessions: Flow<List<SshSession>> = _activeSessions.map { map ->
        map.values.map { it.session }
    }

    private val clients = ConcurrentHashMap<String, SSHClient>()

    /**
     * Guards [references] and keeps [clients] and [_activeSessions] in step with it: a session
     * enters and leaves the three together, and a count never outlives its session.
     */
    private val registryLock = Any()

    /** References held on each registered session, see the class comment. Guarded by [registryLock]. */
    private val references = HashMap<String, Int>()

    /**
     * Bumped by [closeAllAndWait]. A connection reads it when it starts and [registerSession] compares it
     * under [registryLock]: a connection that began before the closing and succeeds after it is closed
     * and fails, instead of entering a registry that was just emptied. Guarded by [registryLock].
     */
    private var sessionEpoch = 0

    /**
     * The `keepAliveSeconds` of the host of each registered session, taken when it was registered
     * (read by [livenessCadenceSeconds]). Kept in step with [clients] by [forget].
     */
    private val cadences = ConcurrentHashMap<String, Int>()

    private val endListeners = CopyOnWriteArrayList<SessionEndListener>()

    /**
     * Where a client is closed once its last reference is gone: closing writes to the socket, which
     * must not happen on the thread of a caller that may be the main one. Replaceable in tests,
     * which need to see the client closed when [release] returns.
     */
    @androidx.annotation.VisibleForTesting
    internal var closeScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Callback appelé lorsqu'une clé hôte inconnue est rencontrée.
     * Doit rendre la réponse de l'utilisateur (confiance ou refus), ou dire que personne n'a pu ou
     * su répondre ([HostKeyPromptResponse.NoScreen], [HostKeyPromptResponse.TimedOut]) : la
     * connexion échoue alors avec son propre message et non comme une clé qui a changé.
     *
     * Installé une fois par `App.onCreate` ([HostKeyPromptCoordinator.promptUnknown]). Exécuté via
     * runBlocking depuis le thread de lecture du transport SSH, en enfant de la coroutine qui
     * connecte (voir [InteractiveHostKeyVerifier]).
     */
    var hostKeyVerificationCallback: (suspend (HostKeyVerifyResult.Unknown) -> HostKeyPromptResponse)? = null

    /**
     * Callback appelé lorsqu'une signature FIDO2 est nécessaire.
     * L'appelant (ViewModel) doit présenter l'UI de challenge et retourner la signature.
     *
     * Exécuté via runBlocking depuis le thread SSH (IO dispatcher).
     */
    var fido2ChallengeCallback: (suspend (Fido2ChallengeRequest) -> Fido2ChallengeResponse?)? = null

    /**
     * Called when a fingerprint must authorize the signature of a biometric key (Android Keystore).
     * Installed once by `App.onCreate` ([BiometricPromptCoordinator.install]), like
     * [fido2ChallengeCallback]: the system dialog is started at `MainActivity` level. Returns null
     * when nobody answered in time.
     *
     * Executed through runBlocking from the SSH thread (IO dispatcher).
     */
    var biometricSignatureCallback: (suspend (BiometricSignRequest) -> BiometricPromptResponse?)? = null

    /**
     * Construit le client SSH connecté (TCP + handshake) d'un hôte, avant l'authentification.
     * Remplaçable dans les tests : il n'y a pas de serveur SSH dans un test unitaire, et les
     * chemins d'échec (classement des erreurs, déconnexion du client) se vérifient avec un faux client.
     */
    @androidx.annotation.VisibleForTesting
    internal var clientFactory: suspend (Host) -> SSHClient = { host -> createSshClient(host) }

    /**
     * The one point every connect function goes through before [clientFactory]: an address with a
     * blank, a control or an invisible format character is refused here, before any DNS lookup or
     * socket, with [InvalidHostAddressException] (which [connectErrorResult] turns into a readable
     * message). The same rule as the known hosts file, which could not store such an address.
     */
    private suspend fun newClient(host: Host): SSHClient {
        if (!BackupValidator.isValidHostName(host.hostname)) throw InvalidHostAddressException()
        return clientFactory(host)
    }

    /**
     * Combien de temps le serveur peut rester muet pendant une authentification interactive (clé FIDO2
     * ou empreinte) : avant qu'il accepte la clé, puis après l'envoi de la signature. Passé ce délai
     * [AuthWatchdog] coupe la connexion. Le temps de l'utilisateur avec sa clé ou son doigt n'est pas
     * compté (c'est l'échéance de la demande). Le nom date de la seule clé FIDO2, il est resté.
     * Modifiable dans les tests, où 30 s seraient trop longues.
     */
    @androidx.annotation.VisibleForTesting
    internal var fido2ServerReplyTimeoutMs: Long = TRANSPORT_TIMEOUT_MS.toLong()

    /**
     * Connecte une session SSH par mot de passe.
     */
    override suspend fun connectWithPassword(
        host: Host,
        password: CharArray,
    ): SshResult<SshSession> = connecting(secret = password) { registration ->
        // Before the session is announced: a cancellation here has nothing to forget, and the
        // password is wiped by connecting.
        val timeoutSeconds = currentConnectTimeoutSeconds()
        val sessionId = UUID.randomUUID().toString()
        updateSessionStatus(sessionId, host, SessionStatus.CONNECTING)

        // The client of this attempt, once built: a failure closes it (see [abandon]).
        var attempt: SSHClient? = null

        return@connecting try {
            val client = newClient(host)
            attempt = client
            // SECURITY: SSHJ 0.38.0 authPassword() n'accepte que String, la copie
            // immutable reste dans le heap JVM jusqu'au GC. Risque accepté (API limitation).
            authenticatingOnConnection(client) { client.authPassword(host.username, String(password)) }

            // Sécurité : wipe mdp immédiat
            password.fill('\u0000')

            // Cancelled while the authentication blocked a thread: no session for a caller gone.
            currentCoroutineContext().ensureActive()
            val session = registerSession(sessionId, host, client, registration)

            Timber.i("SSH connected (password) hostId=${LogId.of(host.id)}")
            SshResult.Success(session)

        } catch (e: CancellationException) {
            password.fill('\u0000')
            abandon(sessionId, attempt)
            throw e

        } catch (e: net.schmizz.sshj.userauth.UserAuthException) {
            password.fill('\u0000')
            abandon(sessionId, attempt)
            Timber.w("Auth failed (password) hostId=${LogId.of(host.id)} (${errorClass(e)})")
            authFailureResult(e, refusedMessage = context.getString(R.string.auth_error_failed))

        } catch (e: net.schmizz.sshj.transport.TransportException) {
            password.fill('\u0000')
            abandon(sessionId, attempt)
            Timber.w("Transport error (password) hostId=${LogId.of(host.id)} (${errorClass(e)})")
            // The host key refused (by its disconnect reason, not by wording) or a transport that broke.
            hostKeyResultOrNull(e)
                ?: SshResult.Error(SshErrorCode.HOST_UNREACHABLE, connectErrorMessage(context, e, timeoutSeconds))

        } catch (e: IOException) {
            password.fill('\u0000')
            abandon(sessionId, attempt)
            Timber.w("Connection error (password) hostId=${LogId.of(host.id)} (${errorClass(e)})")
            hostKeyResultOrNull(e) ?: connectErrorResult(context, e, timeoutSeconds)

        } catch (e: Exception) {
            password.fill('\u0000')
            abandon(sessionId, attempt)
            Timber.e("SSH connection error (password) hostId=${LogId.of(host.id)} (${errorClass(e)})")
            hostKeyResultOrNull(e) ?: unexpectedResult()
        }
    }

    /**
     * Connecte une session SSH par clé privée.
     */
    override suspend fun connectWithKey(
        host: Host,
        privateKeyPem: String,
        passphrase: CharArray?,
    ): SshResult<SshSession> = connecting(secret = passphrase) { registration ->
        // Before the session is announced: see connectWithPassword.
        val timeoutSeconds = currentConnectTimeoutSeconds()
        val sessionId = UUID.randomUUID().toString()
        updateSessionStatus(sessionId, host, SessionStatus.CONNECTING)

        // The client of this attempt, once built: a failure closes it (see [abandon]).
        var attempt: SSHClient? = null

        return@connecting try {
            val client = newClient(host)
            attempt = client

            Timber.d("SSH key auth: loading key")
            val keyProvider = loadingKey { sshKeyManager.loadKeyProviderFromString(privateKeyPem, passphrase) }
            Timber.d("SSH key auth: key loaded OK")
            authenticatingWithKey(client) { client.authPublickey(host.username, keyProvider) }

            passphrase?.fill('\u0000')

            currentCoroutineContext().ensureActive()
            val session = registerSession(sessionId, host, client, registration)

            Timber.i("SSH connected (key) hostId=${LogId.of(host.id)}")
            SshResult.Success(session)

        } catch (e: CancellationException) {
            passphrase?.fill('\u0000')
            abandon(sessionId, attempt)
            throw e

        } catch (e: Exception) {
            passphrase?.fill('\u0000')
            abandon(sessionId, attempt)
            Timber.e("SSH key auth error hostId=${LogId.of(host.id)} (${errorClass(e)})")

            val hostKey = hostKeyResultOrNull(e)
            if (hostKey != null) {
                hostKey
            } else if (e is KeyFailure) {
                localKeyResult(e.failure, keystoreKey = false)
            } else if (e is net.schmizz.sshj.userauth.UserAuthException) {
                authFailureResult(e, refusedMessage = context.getString(R.string.auth_error_failed))
            } else if (e is IOException) {
                connectErrorResult(context, e, timeoutSeconds)
            } else {
                unexpectedResult()
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
    ): SshResult<SshSession> = connecting(secret = passphrase) { registration ->
        // Before the session is announced: see connectWithPassword.
        val timeoutSeconds = currentConnectTimeoutSeconds()
        val sessionId = UUID.randomUUID().toString()
        updateSessionStatus(sessionId, host, SessionStatus.CONNECTING)

        // The client of this attempt, once built: a failure closes it (see [abandon]).
        var attempt: SSHClient? = null

        return@connecting try {
            val client = newClient(host)
            attempt = client

            Timber.d("SSH cert auth: loading key+cert hostId=${LogId.of(host.id)}")
            val keyProvider = loadingKey {
                sshKeyManager.loadKeyProviderWithCertificate(
                    privateKeyPem,
                    certificatePem,
                    passphrase,
                )
            }
            authenticatingWithKey(client) { client.authPublickey(host.username, keyProvider) }

            passphrase?.fill('\u0000')

            currentCoroutineContext().ensureActive()
            val session = registerSession(sessionId, host, client, registration)

            Timber.i("SSH connected (cert) hostId=${LogId.of(host.id)}")
            SshResult.Success(session)

        } catch (e: CancellationException) {
            passphrase?.fill('\u0000')
            abandon(sessionId, attempt)
            throw e

        } catch (e: Exception) {
            passphrase?.fill('\u0000')
            abandon(sessionId, attempt)
            Timber.e("SSH cert auth error hostId=${LogId.of(host.id)} (${errorClass(e)})")

            val hostKey = hostKeyResultOrNull(e)
            if (hostKey != null) {
                hostKey
            } else if (e is KeyFailure) {
                localKeyResult(e.failure, keystoreKey = false)
            } else if (e is net.schmizz.sshj.userauth.UserAuthException) {
                authFailureResult(e, refusedMessage = context.getString(R.string.auth_error_failed))
            } else if (e is IOException) {
                connectErrorResult(context, e, timeoutSeconds)
            } else {
                unexpectedResult()
            }
        }
    }

    /**
     * Connects an SSH session with a biometric key: an EC P-256 key of the Android Keystore whose
     * signature is released by the fingerprint. An SSHJ `KeyProvider` does not fit: SSHJ signs by
     * itself, with a `Signature` that no `BiometricPrompt` ever authorized.
     *
     * One fingerprint per connection, asked once the server has accepted the key (PK_OK) and never
     * for a key it refuses, like for the FIDO2 security key ([connectWithSkKey]):
     * 1. [BiometricAuthMethod] sends the public key, without a signature;
     * 2. on PK_OK, [openSignature] opens the Keystore `Signature` (`initSign`, which asks for
     *    nothing yet). If the Keystore refuses there (key invalidated by a new enrollment, key
     *    deleted), it is a local failure of the key, classified by [classifyLocalKeyFailure], and no
     *    prompt was shown;
     * 3. the prompt is asked of [biometricSignatureCallback]: the opened `Signature` is authorized
     *    by the `CryptoObject` of the `BiometricPrompt`;
     * 4. once the prompt is back, here, on the SSH side, the `Signature` signs the data of the
     *    request, which the prompt never sees.
     *
     * The skeleton is the one of [connectWithSkKey] ([connectInteractively]): the user's time is not
     * the server's silence, and cancelling the connection removes the prompt.
     *
     * How the prompt ended without an authorization decides the result: cancelled,
     * `FIDO2_USER_CANCELLED`; deadline, `FIDO2_TIMEOUT`; sensor locked or missing, `AUTH_FAILED` with
     * its own sentence ([biometricAuthFailureCode]); otherwise it is the exchange with the server,
     * whose refusal reads `auth_error_failed_biometric`.
     *
     * [keyLabel] and the label of [host] are for the prompt to display, never for the log.
     *
     * @param openSignature opens the `SHA256withECDSA` `Signature` of the Keystore key, at PK_OK
     * (`KeystoreManager.getSignatureForBiometricKey` in production)
     */
    suspend fun connectWithBiometricKey(
        host: Host,
        publicKey: PublicKey,
        keyLabel: String,
        openSignature: () -> java.security.Signature,
    ): SshResult<SshSession> {
        // The key comes from the Keystore through a database that a backup or a sync can fill:
        // anything but an EC P-256 key is refused before the server is asked about it.
        if (!isBiometricKeyAlgorithm(publicKey)) {
            Timber.w("Biometric key refused: not an EC P-256 key hostId=${LogId.of(host.id)}")
            // A key of the wrong kind never becomes a good one: regenerating it is the way out.
            return localKeyResult(LocalKeyFailure.BIOMETRIC_KEY_INVALID, keystoreKey = true)
        }
        // How the prompt ended, when it ended without an authorization. Written by the SSH
        // transport thread (BiometricAuthMethod runs the signer there), read by the connecting one
        // after the failure.
        val promptEnd = AtomicReference<BiometricPromptEnd?>(null)
        // What the Keystore threw, when it refused to open or to use the signature.
        val keyFailure = AtomicReference<Throwable?>(null)

        return connectInteractively(
            host = host,
            kind = "biometric key",
            refusedMessage = context.getString(R.string.auth_error_failed_biometric),
            keystoreKey = true,
            notConfigured = SshResult.Error(
                SshErrorCode.AUTH_FAILED,
                context.getString(R.string.auth_error_prompt_unavailable),
            ),
            buildMethod = { auth ->
                val promptCallback = biometricSignatureCallback
                if (promptCallback == null) {
                    null
                } else {
                    BiometricAuthMethod(publicKey) { signingData ->
                        // Only now, with the server's PK_OK: a key it refuses never gets a prompt.
                        val signature = try {
                            openSignature()
                        } catch (e: Exception) {
                            keyFailure.set(e)
                            throw e
                        }
                        val request = BiometricSignRequest(signature, host.label, keyLabel)
                        val authorized = auth.prompting { connection ->
                            requestBiometricAuthorization(promptCallback, request, connection) { end ->
                                promptEnd.set(end)
                            }
                        }
                        if (!authorized) {
                            null
                        } else {
                            try {
                                signature.update(signingData)
                                signature.sign()
                            } catch (e: Exception) {
                                keyFailure.set(e)
                                throw e
                            }
                        }
                    }
                }
            },
            promptFailure = { failure ->
                val end = promptEnd.get()
                val keyProblem = keyFailure.get()
                val signing = causeChain(failure).firstOrNull { it is BiometricSigningException }
                when {
                    end != null -> {
                        Timber.w("Biometric prompt ended without an authorization hostId=${LogId.of(host.id)} ($end)")
                        SshResult.Error(
                            biometricAuthFailureCode(end),
                            context.getString(biometricAuthFailureMessageRes(end)),
                        )
                    }
                    keyProblem != null -> {
                        // The Keystore threw: only what proves the key is gone says to regenerate it,
                        // everything else (provider error, Keystore restarted, operation pruned) says
                        // to try again. The root cause class tells which, in the log.
                        Timber.w("Biometric key unusable hostId=${LogId.of(host.id)} (${keyProblemLogDescription(keyProblem)})")
                        localKeyResult(classifyLocalKeyFailure(keyProblem) ?: LocalKeyFailure.UNREADABLE, keystoreKey = true)
                    }
                    signing != null && classifyUserAuthFailure(failure) == UserAuthFailure.REFUSED -> {
                        // The signer or the building of the request failed (a malformed signature, say)
                        // and the Keystore is not accused: that is no refusal by the server, so it is
                        // not said to be one. A broken transport or a late answer keeps its own reading.
                        Timber.w("Biometric signing failed hostId=${LogId.of(host.id)} (${errorClass(rootCause(signing))})")
                        localKeyResult(LocalKeyFailure.UNREADABLE, keystoreKey = true)
                    }
                    else -> null
                }
            },
        )
    }

    /**
     * Connecte une session SSH via clé de sécurité FIDO2 (sk-*).
     * La signature est obtenue via [fido2ChallengeCallback] pendant le handshake.
     *
     * [allowedCredentialIds] is the credential id of the enrolled key. A key that is not resident
     * (the enrolment asks for a non-resident one) only answers when it is given that id, so it goes
     * into the request the prompt hands to the key. Empty for a resident key, which finds its own.
     */
    suspend fun connectWithSkKey(
        host: Host,
        skPublicKey: SkSshPublicKey,
        allowedCredentialIds: List<ByteArray> = emptyList(),
    ): SshResult<SshSession> {
        // How the prompt ended, when it ended without a signature. Written by the SSH transport
        // thread (SkAuthMethod runs the handler there), read by the connecting one after the failure.
        val promptEnd = AtomicReference(Fido2PromptEnd.NONE)
        // Why the key failed, when the prompt ended on a key error.
        val keyError = AtomicReference<HwKeyError?>(null)

        return connectInteractively(
            host = host,
            kind = "FIDO2 sk-key",
            refusedMessage = context.getString(R.string.auth_error_failed_fido2),
            keystoreKey = false,
            notConfigured = SshResult.Error(
                SshErrorCode.FIDO2_NO_KEY,
                context.getString(R.string.auth_error_prompt_unavailable),
            ),
            buildMethod = { auth ->
                val challengeCallback = fido2ChallengeCallback
                if (challengeCallback == null) {
                    null
                } else {
                    SkAuthMethod(skPublicKey) { signingData ->
                        val request = Fido2ChallengeRequest(
                            signingData = signingData,
                            rpId = skPublicKey.application,
                            keyType = skPublicKey.keyType,
                            allowedCredentialIds = allowedCredentialIds,
                        )
                        auth.prompting { connection ->
                            requestFido2Signature(challengeCallback, request, connection) { end, reason ->
                                promptEnd.set(end)
                                keyError.set(reason)
                            }
                        }
                    }
                }
            },
            promptFailure = { _ ->
                // Cancelled and timed out are not the server refusing the key: said as such, so the
                // user reads "connection cancelled" after Cancel and nothing retries for them.
                val end = promptEnd.get()
                if (end == Fido2PromptEnd.NONE) {
                    null
                } else {
                    Timber.w("FIDO2 prompt ended without a signature hostId=${LogId.of(host.id)} ($end)")
                    SshResult.Error(fido2AuthFailureCode(end), fido2AuthFailureMessage(context, end, keyError.get()))
                }
            },
        )
    }

    /**
     * The connection of an authentication that stops in the middle of `client.auth` to ask the user
     * for something: the FIDO2 key ([connectWithSkKey]) and the fingerprint
     * ([connectWithBiometricKey]). What they share is the delicate part, and it is written once:
     * the prompt is a child of the connecting coroutine, the user's time is not the server's
     * silence, the transport wait is raised above the longest prompt, and cancelling the connection
     * ends a `client.auth` that blocks a thread.
     *
     * - [kind] names the method in the log, nothing else: fixed text, never user data.
     * - [refusedMessage] is what the user reads when the server refuses the key.
     * - [keystoreKey] says the key lives in the Android Keystore: its messages never mention a
     *   passphrase, and a runtime exception of the authentication is the key's cryptography (see
     *   [authenticatingWithKey]). Without it, a runtime exception is left alone, unless the
     *   connection was lost before the authentication (see [authenticatingOnConnection]).
     * - [notConfigured] is the result when the callback of the method is not installed, which
     *   [buildMethod] tells by returning null; the client is closed then.
     * - [buildMethod] makes the SSHJ method from the [InteractiveAuth] of this attempt.
     * - [promptFailure] is asked, with the [UserAuthException] the authentication failed with, when
     *   it did: the result of a prompt that ended without a signature (cancelled, timed out, the
     *   key or the sensor failed), or of a signer that failed, or null if none of that is what
     *   failed and the exchange with the server is.
     */
    private suspend fun connectInteractively(
        host: Host,
        kind: String,
        refusedMessage: String,
        keystoreKey: Boolean,
        notConfigured: SshResult.Error,
        buildMethod: (InteractiveAuth) -> AuthMethod?,
        promptFailure: (UserAuthException) -> SshResult.Error?,
    ): SshResult<SshSession> = connecting { registration ->
        // Before the session is announced: a cancellation of this read has nothing to forget.
        val timeoutSeconds = currentConnectTimeoutSeconds()
        val sessionId = UUID.randomUUID().toString()
        updateSessionStatus(sessionId, host, SessionStatus.CONNECTING)
        // The handlers of the methods call their prompt from a runBlocking of their own, blind to
        // this coroutine: the prompt is made a child of it, so cancelling the connection (screen
        // left, ViewModel cleared, tunnel start cancelled) removes the prompt that is shown or
        // queued for it.
        val connection = currentCoroutineContext()[Job]

        // The client of this attempt, once built: a failure closes it (see [abandon]).
        var attempt: SSHClient? = null
        // Bounds the server's silence (before the key is asked for, after the signature is sent);
        // closing the connection is what wakes client.auth up. See AuthWatchdog. Its timers run in
        // a scope of their own, not in this coroutine's: client.auth blocks a thread and cannot be
        // cancelled, so when the connection is cancelled (screen left) this coroutine stays
        // cancelled-but-suspended until auth returns, and a timer that died with it would leave a
        // silent server holding the thread, the socket and the card for the full 330 s.
        val watchdogScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val watchdog = AuthWatchdog(watchdogScope, fido2ServerReplyTimeoutMs) { runCatching { attempt?.disconnect() } }
        // True once client.auth has returned: from then on the client is the session's, or is
        // being abandoned by the failure path, and the cancellation guard below must leave it alone.
        val authReturned = AtomicBoolean(false)
        var cancellationGuard: Job? = null

        return@connecting try {
            val client = newClient(host)
            attempt = client

            // The time the user takes is the prompt's own clock (its deadline), not the server's:
            // the watchdog sleeps during the prompt ([InteractiveAuth.prompting]) and wakes up when
            // it ends, with a signature that is on its way to the server or with none (the
            // authentication then fails at once).
            val method = buildMethod(InteractiveAuth(connection, watchdog))
                ?: run {
                    abandon(sessionId, client)
                    return@connecting notConfigured
                }

            // The prompt has its own clock (60 s for a fingerprint, 60 s and more for a PIN on a
            // FIDO2 key, see FIDO2_AUTH_TRANSPORT_TIMEOUT_MS), and SSHJ's wait for the answer, 30 s,
            // would cut it short: raised above the longest prompt for this authentication only, and
            // put back before the session is used. That is a backstop. What bounds a silent server
            // is the watchdog, armed from here: SSHJ's reader ignores the socket timeout, so a
            // network that drops without a FIN would otherwise pin this thread for ever.
            //
            // Cancelling the connection must also end a blocked client.auth: the cancellation
            // reaches a child of this coroutine at once (the coroutine itself is stuck in the
            // blocking call), and that child closes the client, which wakes the call up with a
            // transport exception. Without it, a silent server kept a cancelled connection, its
            // card and its thread alive until the transport timeout.
            //
            // UNDISPATCHED: the guard must be waiting for the cancellation before the blocking call
            // starts. A coroutine that is cancelled before it was ever dispatched never runs its
            // body, and the cancel would then close nothing.
            cancellationGuard = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } catch (e: CancellationException) {
                    if (!authReturned.get()) runCatching { client.disconnect() }
                    throw e
                }
            }
            watchdog.arm()
            try {
                client.withTransportTimeout(FIDO2_AUTH_TRANSPORT_TIMEOUT_MS, TRANSPORT_TIMEOUT_MS) {
                    guardingKeystoreKey(client, keystoreKey) { client.auth(host.username, method) }
                }
            } finally {
                authReturned.set(true)
            }
            // The timer may have fired just as the authentication came back successfully: the
            // connection is then being closed under it, and a session must not be registered on a
            // client that is going away. A cut attempt is a TIMEOUT, retried like any other.
            if (watchdog.close()) {
                abandon(sessionId, client)
                return@connecting serverSilentResult()
            }

            // Cancelled while the authentication blocked a thread: no session for a caller gone.
            currentCoroutineContext().ensureActive()
            val session = registerSession(sessionId, host, client, registration)

            Timber.i("SSH connected ($kind) hostId=${LogId.of(host.id)}")
            SshResult.Success(session)

        } catch (e: CancellationException) {
            abandon(sessionId, attempt)
            throw e

        } catch (e: net.schmizz.sshj.userauth.UserAuthException) {
            abandon(sessionId, attempt)
            Timber.w("$kind auth failed hostId=${LogId.of(host.id)} (${errorClass(e)})")
            // A prompt that ended without a signature is not the server refusing the key. Without
            // one, this is the exchange with the server: a refusal, or a transport that broke or a
            // wait that ran out (which the reconnection may retry).
            promptFailure(e)
                ?: if (watchdog.expired) {
                    serverSilentResult()
                } else {
                    authFailureResult(
                        e,
                        refusedMessage = refusedMessage,
                        timeoutSeconds = FIDO2_AUTH_TRANSPORT_TIMEOUT_MS / 1000,
                        keystoreKey = keystoreKey,
                    )
                }

        } catch (e: net.schmizz.sshj.transport.TransportException) {
            abandon(sessionId, attempt)
            if (watchdog.expired) {
                serverSilentResult()
            } else {
                hostKeyResultOrNull(e)
                    ?: SshResult.Error(SshErrorCode.HOST_UNREACHABLE, connectErrorMessage(context, e, timeoutSeconds))
            }

        } catch (e: IOException) {
            abandon(sessionId, attempt)
            Timber.w("$kind connection error hostId=${LogId.of(host.id)} (${errorClass(e)})")
            if (watchdog.expired) {
                serverSilentResult()
            } else {
                hostKeyResultOrNull(e) ?: connectErrorResult(context, e, timeoutSeconds)
            }

        } catch (e: Exception) {
            abandon(sessionId, attempt)
            if (keystoreKey) {
                // The message of a Keystore exception may name the alias of the key, and the
                // throwable carries it into the log with its causes: the class of the root cause
                // is all that is said, as everywhere else for these keys (see keyProblemLogDescription).
                Timber.e("$kind connection error hostId=${LogId.of(host.id)} (${errorClass(rootCause(e))})")
            } else {
                Timber.e("$kind connection error hostId=${LogId.of(host.id)} (${errorClass(e)})")
            }
            hostKeyResultOrNull(e)
                // The cryptography of an Android Keystore key failed (see [authenticatingWithKey]).
                ?: (e as? KeyFailure)?.let { localKeyResult(it.failure, keystoreKey) }
                ?: unexpectedResult()
        } finally {
            // The authentication has returned, one way or the other: no timer may outlive it, and
            // the guard must end or this coroutine would wait for it.
            watchdog.close()
            watchdogScope.cancel()
            cancellationGuard?.cancel()
        }
    }

    /**
     * Takes one more reference on [sessionId], for a user of the session that did not connect it
     * (see the class comment). True only if the session is registered and alive: its client is still
     * connected and authenticated. The caller then owns that reference and must [release] it.
     * False for an unknown session and for one whose client died without the registry knowing yet
     * (that one is dropped from the registry on the spot, and its listeners are told).
     */
    fun acquire(sessionId: String): Boolean {
        // The client is asked OUTSIDE the registry lock. SSHJ's isConnected takes the lock of the
        // transport's close promise, which die() holds while it calls our disconnect listener,
        // and that listener takes the registry lock (sessionEnded): asking under the lock would
        // deadlock against a session that is dying.
        val client = synchronized(registryLock) {
            if (references[sessionId] == null) return false
            clients[sessionId] ?: return false
        }
        val alive = client.isConnected && client.isAuthenticated
        if (alive) {
            synchronized(registryLock) {
                // The session may have left, or been replaced by another client, between the two.
                val count = references[sessionId]
                if (clients[sessionId] === client && count != null) {
                    references[sessionId] = count + 1
                    return true
                }
            }
            // Not the client that was checked any more: the session ended or changed hands in the
            // meantime, and whoever did that has told the listeners.
            return false
        }
        sessionEnded(sessionId, client, SessionEnd.Lost(DisconnectReason.CONNECTION_LOST.name))
        return false
    }

    /**
     * Gives back one reference on [sessionId], the one its holder owns (see the class comment). The
     * last one closes the client and removes the session from the registry. Idempotent: an unknown
     * session (never registered, already closed, dead) is a no-op, and the count never goes below
     * zero. Does not block: the client is closed in the background, see [closeScope].
     */
    fun release(sessionId: String) {
        val client: SSHClient?
        synchronized(registryLock) {
            val count = references[sessionId] ?: return
            if (count > 1) {
                references[sessionId] = count - 1
                return
            }
            client = forget(sessionId)
        }
        Timber.i("Session ${LogId.of(sessionId)} released by its last holder")
        notifyEnded(sessionId, SessionEnd.Closed)
        client?.let { closeInBackground(it) }
    }

    /**
     * Closes [sessionId] for everyone, whatever the references held on it. Its holders are not
     * asked: the listeners are told ([SessionEnd.Closed]) and the tunnels on it end in error. Kept
     * because the shared interface requires it, and for no caller of the app: each user releases
     * its own reference, see [release].
     */
    @Deprecated(
        "Forced close, for everyone: kept only because the shared interface requires it. " +
            "Each user gives back its own reference with release.",
    )
    override suspend fun disconnect(sessionId: String): Unit = withContext(Dispatchers.IO) {
        try {
            val client = cleanupSession(sessionId)
            if (client != null) notifyEnded(sessionId, SessionEnd.Closed)
            Timber.i("Session ${LogId.of(sessionId)} disconnected")
        } catch (e: Exception) {
            Timber.e("Error disconnecting session ${LogId.of(sessionId)} (${errorClass(e)})")
        }
    }

    /**
     * Closes every session for everyone, whoever holds a reference on it (tabs, SFTP browsers, offers,
     * holds, transfers, tunnels), and waits at most [timeoutMs] for the clients to close. The closing
     * that comes before the erase of the vault ("Wipe the vault").
     *
     * Under [registryLock]: every session is forgotten whatever its count, and [sessionEpoch] moves, so
     * that a connection that began before and succeeds after is closed and fails ([registerSession]). The
     * listeners are told once each ([SessionEnd.Closed]): a late [release] on a forgotten session is
     * already a no-op.
     *
     * The clients are closed in parallel, in [closeScope] and not in the scope of the caller: a disconnect
     * writes to the socket and can block, and [withTimeoutOrNull] does not bound blocking children of its
     * own scope (it would wait for them to end before it could report the timeout). Only the WAIT is
     * bounded here. At the deadline the raw socket of every client that is still closing is closed
     * (a writer stuck on a dead link is released by it, see [declareUnresponsive]) and false is returned.
     *
     * Returns true when every client closed in time.
     */
    suspend fun closeAllAndWait(timeoutMs: Long): Boolean {
        val closing = synchronized(registryLock) {
            sessionEpoch++
            val snapshot = clients.entries.map { it.key to it.value }
            snapshot.forEach { (sessionId, _) -> forget(sessionId) }
            snapshot
        }
        for ((sessionId, _) in closing) notifyEnded(sessionId, SessionEnd.Closed)
        val count = closing.size
        Timber.i("All sessions forgotten ($count), closing their clients")

        val jobs = closing.map { (_, client) ->
            closeScope.async {
                try {
                    client.disconnect()
                } catch (e: Exception) {
                    Timber.w("Error closing a session (${errorClass(e)})")
                }
            }
        }
        val closed = withTimeoutOrNull(timeoutMs) {
            jobs.awaitAll()
            true
        } ?: false
        if (!closed) {
            Timber.w("Some sessions did not close in time, closing their sockets")
            jobs.forEachIndexed { index, job ->
                if (!job.isCompleted) {
                    try {
                        closing[index].second.socket?.close()
                    } catch (e: Exception) {
                        Timber.w("Error closing the socket of a session (${errorClass(e)})")
                    }
                }
            }
        }
        return closed
    }

    /**
     * Declares [sessionId] dead because the liveness watchdog heard nothing from its server: the
     * session leaves the registry whatever its references, the listeners are told
     * ([SessionEnd.Unresponsive]), then the raw socket and the client are closed. No-op when the
     * session is unknown or now runs on another client than [client] (it already ended, or was
     * replaced). Never blocks: a writer stuck on the dead link is released by the socket close.
     */
    fun declareUnresponsive(sessionId: String, client: SSHClient) {
        val forgotten = synchronized(registryLock) {
            if (clients[sessionId] !== client) false else { forget(sessionId); true }
        }
        if (!forgotten) return
        Timber.i("Session ${LogId.of(sessionId)} ended (${SessionEnd.Unresponsive.reasonName})")
        notifyEnded(sessionId, SessionEnd.Unresponsive)
        // The raw socket first: SSHJ's disconnect writes under the write lock, which a writer stuck
        // on the dead link holds. Closing the socket frees it (and kills SSHJ's reader, whose
        // transport listener then finds the session forgotten and says nothing).
        try {
            client.socket?.close()
        } catch (e: Exception) {
            Timber.w("Error closing the socket of an unresponsive session (${errorClass(e)})")
        }
        closeInBackground(client)
    }

    /**
     * The keepalive of the host of [sessionId], in seconds, as it was when the session was
     * registered: the cadence of the liveness watchdog ([TunnelLivenessWatchdog]). Null for an unknown
     * session, 0 or less when the host does not want its sessions watched. Never blocks.
     */
    fun livenessCadenceSeconds(sessionId: String): Int? = cadences[sessionId]

    /**
     * Subscribes [listener] to the end of every session, whichever way it ends. For the long-lived
     * holders that must drop what they built on a session (the tunnels, the SFTP clients): a
     * session that dies does not wait for them to release it.
     */
    fun addSessionEndListener(listener: SessionEndListener) {
        endListeners += listener
    }

    fun removeSessionEndListener(listener: SessionEndListener) {
        endListeners -= listener
    }

    override suspend fun getSession(sessionId: String): SshSession? =
        _activeSessions.value[sessionId]?.session

    override fun isConnected(sessionId: String): Boolean =
        clients[sessionId]?.isConnected == true

    /**
     * True when the client of [sessionId] reports itself authenticated. Read-only, like
     * [isConnected], and asked together with it. A cheap defensive check, not a case that happens
     * today: a client only enters [clients] once its authentication succeeded, and SSHJ never
     * resets that flag afterwards, so it adds nothing for a registered client. It guards against a
     * future path that would register a client before authenticating it.
     */
    fun isAuthenticated(sessionId: String): Boolean =
        clients[sessionId]?.isAuthenticated == true

    /** Récupère le SSHClient brut pour lancer un shell ou un tunnel */
    fun getClient(sessionId: String): SSHClient? = clients[sessionId]

    /**
     * Ouvre un shell SSH sur une session existante. Le PTY s'ouvre à la taille donnée, sans
     * dimension en pixels : l'émulateur de `SshTerminalSession` démarre à la même taille.
     */
    suspend fun openShell(
        sessionId: String,
        columns: Int = DEFAULT_PTY_COLUMNS,
        rows: Int = DEFAULT_PTY_ROWS,
    ): SshResult<Session.Shell> = withContext(Dispatchers.IO) {
        val client = clients[sessionId]
            ?: return@withContext SshResult.Error(
                SshErrorCode.UNKNOWN,
                context.getString(R.string.shell_error_session_not_found)
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
            Timber.i("SSH shell opened for session ${LogId.of(sessionId)} (${columns}x$rows)")
            SshResult.Success(shell)
        } catch (e: Exception) {
            Timber.e("Error opening the SSH shell for session ${LogId.of(sessionId)} (${errorClass(e)})")
            SshResult.Error(SshErrorCode.UNKNOWN, context.getString(R.string.shell_error_open_failed))
        }
    }

    // ── Private ──────────────────────────────────────────────────────────────────

    /**
     * Réglage utilisateur « Timeout de connexion » (Settings, slider 5-30 s), en
     * secondes : lu séparément de [createSshClient] pour rester disponible dans
     * les blocs `catch` (message [connectErrorMessage] du timeout).
     */
    private suspend fun currentConnectTimeoutSeconds(): Int =
        settingsDataStore.settingsFlow.first().connectionTimeout.coerceIn(5, 30)

    /**
     * A client with its transport keepalive armed, not connected yet. KEEP_ALIVE is chosen in the
     * config (the provider is fixed when the client is built) and the interval is set now: SSHJ
     * starts the keepalive thread in `connect`, and only when the interval is already positive.
     */
    internal fun newConfiguredClient(host: Host): SSHClient {
        val config = DefaultConfig().apply { keepAliveProvider = KeepAliveProvider.KEEP_ALIVE }
        val client = SSHClient(config)
        transportKeepAliveSeconds(host.keepAliveSeconds)?.let {
            client.connection.keepAlive.keepAliveInterval = it
        }
        return client
    }

    private suspend fun createSshClient(host: Host): SSHClient {
        val client = newConfiguredClient(host)

        // Vérificateur interactif : remplace knownHostsVerifier directement. Sa demande est
        // l'enfant de la coroutine qui connecte, voir InteractiveHostKeyVerifier.
        val verifier = InteractiveHostKeyVerifier(
            knownHosts = knownHostsVerifier,
            callback = { hostKeyVerificationCallback },
            connection = currentCoroutineContext()[Job],
        )
        client.addHostKeyVerifier(verifier)

        // A socket that dates the bytes it receives: the proof of life of the liveness watchdog
        // (TunnelLivenessWatchdog). Set before the connection, which is when SSHJ asks for it.
        client.socketFactory = InboundActivitySocketFactory()

        // Timeout d'établissement TCP piloté par le réglage utilisateur « Timeout de
        // connexion » (slider Settings, 5-30 s), équivalent `ssh -o ConnectTimeout`.
        // Distinct de host.keepAliveSeconds (newConfiguredClient).
        client.connectTimeout = resolveConnectTimeoutMs(settingsDataStore.settingsFlow.first().connectionTimeout)
        // Borne l'authentification/handshake : constante interne, indépendante du réglage.
        client.timeout = TRANSPORT_TIMEOUT_MS

        try {
            // La première connexion à un hôte demande de confirmer sa clé au milieu de l'échange de
            // clés, et SSHJ n'attend la fin de cet échange que `Transport.timeoutMs` (30 s).
            // L'attente est donc élevée au-delà de la demande, pour cette connexion seulement et
            // remise ensuite, voir keyExchangeTimeoutMs.
            val keyExchangeTimeoutMs = keyExchangeTimeoutMs(
                hostKeyStored = knownHostsVerifier.findExistingAlgorithms(host.hostname, host.port).isNotEmpty(),
            )
            client.withTransportTimeout(keyExchangeTimeoutMs, TRANSPORT_TIMEOUT_MS) {
                client.connect(host.hostname, host.port)
            }
        } catch (e: Throwable) {
            // The client never reaches the caller, which could not close it: a handshake that
            // fails (host key refused, server gone) must not leave its socket and thread behind.
            runCatching { client.disconnect() }
            // A prompt that nobody could or did answer ends in SSHJ's refusal of the key, which
            // would be read as a key that changed: the connection says how it really ended.
            val promptEnd = verifier.promptEnd
            // And one still up or queued, for a connection that is over, goes away (after the line
            // above: it then ends as cancelled, which is not why the connection failed).
            verifier.abandonPrompt()
            throw if (promptEnd != null && e !is CancellationException) HostKeyPromptEndedException(promptEnd, e) else e
        }

        return client
    }

    /**
     * A failure that no branch of the connect functions names: `UNKNOWN`, with a sentence of ours.
     * What the exception says never reaches the screen (technical, in English, it may quote a path
     * or an identifier): its class and its causes are in the log, written where it was caught.
     */
    private fun unexpectedResult(): SshResult.Error =
        SshResult.Error(SshErrorCode.UNKNOWN, context.getString(R.string.connect_error_unexpected))

    /**
     * The host key was refused: `HOST_KEY_MISMATCH` with its message. A security event, which the
     * reconnection never retries (see [isNonRetryable]).
     */
    private fun hostKeyResult(): SshResult.Error =
        SshResult.Error(SshErrorCode.HOST_KEY_MISMATCH, context.getString(R.string.auth_error_host_key))

    /**
     * The result of a connection whose host-key prompt ended without the user's answer (nobody on
     * screen, deadline, connection cancelled): what to do, with the code that keeps the automatic
     * reconnection from opening the same prompt again, and not `HOST_KEY_MISMATCH` (see
     * [hostKeyPromptEndCode]).
     */
    private fun hostKeyPromptEndResult(end: HostKeyPromptEnd): SshResult.Error =
        SshResult.Error(hostKeyPromptEndCode(end), context.getString(hostKeyPromptEndMessageRes(end)))

    /**
     * [hostKeyPromptEndResult] if the host-key prompt of the connection ended without an answer (a
     * [HostKeyPromptEndedException] in the cause chain), else [hostKeyResult] if [e], or something in
     * its cause chain, is SSHJ's refusal of the host key (decided by its disconnect reason, see
     * [isHostKeyFailure]), else null. SSHJ raises it from `client.connect()`, which runs in the
     * client factory and so before any authentication, on every connect path, and again on a re-key
     * inside the authentication.
     */
    private fun hostKeyResultOrNull(e: Throwable): SshResult.Error? =
        hostKeyPromptEnd(e)?.let { hostKeyPromptEndResult(it) }
            ?: if (isHostKeyFailure(e)) hostKeyResult() else null

    /**
     * The result of a FIDO2 authentication that the [AuthWatchdog] cut: the server stayed silent
     * for [fido2ServerReplyTimeoutMs]. A timeout, which the automatic reconnection retries.
     */
    private fun serverSilentResult(): SshResult.Error = SshResult.Error(
        SshErrorCode.TIMEOUT,
        context.getString(R.string.connect_error_timeout, (fido2ServerReplyTimeoutMs / 1000).toInt()),
    )

    /**
     * The result of a key problem of this device (see [classifyLocalKeyFailure]), all decided before
     * the server judged anything. None is a refusal by the server, and none is a network failure.
     *
     * - An unreadable key is `AUTH_FAILED`: for a key file the likely cause is the passphrase, for a
     *   Keystore key ([keystoreKey]) there is no passphrase (the "passphrase ?" hint would send the
     *   user the wrong way) and nothing proves the key is gone, so the message is neutral: the
     *   phone could not use the key, try again. It must not say to regenerate it, which would cut
     *   the access to every server the key is authorized on.
     * - An invalidated or deleted Keystore key is `AUTH_FAILED` as well: regenerating is the only
     *   way out, and it is said only for the signs that prove it (see [LocalKeyFailure]).
     * - A Keystore key that was not authorized when it signed (the biometric window ran out) is
     *   `AUTH_EXPIRED`: the user simply tries again, which asks for the fingerprint again.
     */
    private fun localKeyResult(failure: LocalKeyFailure, keystoreKey: Boolean): SshResult.Error =
        SshResult.Error(
            localKeyFailureCode(failure),
            context.getString(
                when (failure) {
                    LocalKeyFailure.UNREADABLE ->
                        if (keystoreKey) R.string.auth_error_biometric_key_unusable else R.string.auth_error_key_unreadable
                    LocalKeyFailure.BIOMETRIC_KEY_INVALID -> R.string.auth_error_biometric_key_invalid
                    LocalKeyFailure.BIOMETRIC_AUTH_EXPIRED -> R.string.auth_error_biometric_expired
                },
            ),
        )

    /**
     * The result of a connection whose authentication threw [e]. SSHJ uses [UserAuthException] for
     * a refusal and also for a transport that broke or a wait that ran out during the
     * authentication: only a refusal is `AUTH_FAILED` (with [refusedMessage]); the other two are
     * network failures that the automatic reconnection must keep retrying, see
     * [classifyUserAuthFailure].
     */
    private fun authFailureResult(
        e: UserAuthException,
        refusedMessage: String,
        timeoutSeconds: Int = TRANSPORT_TIMEOUT_MS / 1000,
        keystoreKey: Boolean = false,
    ): SshResult.Error =
        when (val failure = classifyUserAuthFailure(e)) {
            UserAuthFailure.REFUSED -> SshResult.Error(userAuthFailureCode(failure), refusedMessage)
            // The key file is read and decrypted during the authentication: a wrong or missing
            // passphrase surfaces here, as a local failure that no retry fixes.
            UserAuthFailure.KEY_UNREADABLE -> localKeyResult(LocalKeyFailure.UNREADABLE, keystoreKey)
            UserAuthFailure.BIOMETRIC_KEY_INVALID -> localKeyResult(LocalKeyFailure.BIOMETRIC_KEY_INVALID, keystoreKey)
            UserAuthFailure.BIOMETRIC_AUTH_EXPIRED -> localKeyResult(LocalKeyFailure.BIOMETRIC_AUTH_EXPIRED, keystoreKey)
            UserAuthFailure.HOST_KEY -> hostKeyResult()
            UserAuthFailure.TIMED_OUT -> SshResult.Error(
                userAuthFailureCode(failure),
                // The wait that ran out: the one the caller armed, not always the 30 s default.
                context.getString(R.string.connect_error_timeout, timeoutSeconds),
            )
            UserAuthFailure.TRANSPORT -> SshResult.Error(
                userAuthFailureCode(failure),
                context.getString(R.string.connect_error_generic),
            )
        }

    /** The CONNECTING entry of an attempt: visible in the registry, with no client to hold yet. */
    private fun updateSessionStatus(sessionId: String, host: Host, status: SessionStatus) {
        val session = SshSession(id = sessionId, host = host, status = status)
        _activeSessions.update { it + (sessionId to ActiveSession(session, client = null)) }
    }

    /**
     * What a connect function noted of its attempt: the id of the session it registered, set as
     * soon as it is. The function around it ([connecting]) reads it if the connection is cancelled
     * before the result reaches the caller.
     */
    private class Registration(
        /** The [sessionEpoch] the connection started in, see [registerSession]. */
        val epoch: Int,
    ) {
        @Volatile
        var sessionId: String? = null
    }

    /**
     * A connection that succeeded after the wipe of the vault closed the app, or after [closeAllAndWait]
     * emptied the registry: its client is closed by the connect function (as for any failure) and the
     * caller gets an error. Fixed text: nothing of the host.
     */
    private class ClosedByWipeException : IOException("Connection closed by the wipe of the vault")

    /**
     * Runs the connection [block] on the IO dispatcher, and makes sure that a session it registered
     * does not outlive a caller that left. `withContext` drops the result of a block that completed
     * while its caller was being cancelled and throws the cancellation instead: a session registered
     * by then would be in the registry with one reference and nobody to own it. Its reference is
     * released here, so the session closes.
     *
     * A cancellation that arrives before the registration is handled by the block itself, which
     * abandons its client and rethrows (see the connect functions).
     */
    private suspend fun connecting(
        secret: CharArray? = null,
        block: suspend CoroutineScope.(Registration) -> SshResult<SshSession>,
    ): SshResult<SshSession> {
        val registration = Registration(synchronized(registryLock) { sessionEpoch })
        try {
            return withContext(Dispatchers.IO) { block(registration) }
        } catch (e: Throwable) {
            // The password or the passphrase of the attempt is wiped here too, whatever the way
            // out: a caller that is already cancelled when `withContext` is entered never runs the
            // block, which is where the secret is otherwise wiped, and an exception that leaves the
            // block before its own `try` (reading the timeout) leaves it unwiped as well. Only a way
            // out by an exception comes through here: a connection that succeeds returns, and the
            // secret is wiped by the block right after the authentication, as before.
            secret?.fill('\u0000')
            // A session registered for a caller that gets an exception instead of it has no owner.
            registration.sessionId?.let { release(it) }
            throw e
        }
    }

    /**
     * Registers the authenticated [client] of an attempt as a CONNECTED session holding one
     * reference, which the caller of the connection owns (see the class comment), and watches its
     * transport. The session, the client and the count enter the registry together. The id is noted
     * in [registration] before anything else can happen to the coroutine.
     */
    private fun registerSession(
        sessionId: String,
        host: Host,
        client: SSHClient,
        registration: Registration,
    ): SshSession {
        val session = SshSession(
            id = sessionId,
            host = host,
            status = SessionStatus.CONNECTED,
            connectedAt = System.currentTimeMillis(),
        )
        val refused = synchronized(registryLock) {
            // Under the lock the registry is emptied under: a connection that began before the closing, or
            // that comes while the wipe of the vault has the app closed, does not enter it.
            if (sessionEpoch != registration.epoch || wipeGate.isClosed()) {
                true
            } else {
                clients[sessionId] = client
                references[sessionId] = 1
                cadences[sessionId] = host.keepAliveSeconds
                _activeSessions.update { it + (sessionId to ActiveSession(session, client)) }
                false
            }
        }
        // Nothing registered: the connect function closes the client of its attempt and forgets the id.
        if (refused) throw ClosedByWipeException()
        registration.sessionId = sessionId
        if (!watchTransport(sessionId, client)) {
            // The link was already gone when the session entered the registry: it left it again at
            // once (listeners told, client closed). The caller must not be handed a session that no
            // longer exists. It is a connection lost like any other, read the way every connect path
            // reads one that dies around its authentication: retried by the reconnection.
            throw connectionLostBeforeAuthentication(IllegalStateException("Link lost before the session was registered"))
        }
        return session
    }

    /**
     * Installs the SSHJ transport listener of [client]: the one place that does, so that a lost
     * connection reaches every holder of the session through [addSessionEndListener]. A network
     * cut closes no local socket, so without it a tunnel would stay ACTIVE on a dead transport and
     * nothing would reconnect it.
     *
     * A listener only hears of a death that comes after it: if the transport died between the end
     * of the authentication and here, it would never fire and the session would stay in the
     * registry, dead. So the client is asked once the listener is in place, and a client that is
     * gone ends the session on the spot ([sessionEnded]). Returns false then.
     */
    private fun watchTransport(sessionId: String, client: SSHClient): Boolean {
        try {
            client.transport.disconnectListener = DisconnectListener { reason, _ ->
                sessionEnded(sessionId, client, SessionEnd.Lost(reason.name))
            }
        } catch (e: Exception) {
            Timber.w("Cannot watch the transport of session ${LogId.of(sessionId)} (${errorClass(e)})")
        }
        if (!client.isConnected) {
            sessionEnded(sessionId, client, SessionEnd.Lost(DisconnectReason.CONNECTION_LOST.name))
            return false
        }
        return true
    }

    /**
     * [client], registered as [sessionId], is gone without the app having closed it: forgets the
     * session whatever its count, closes what is left of the client and tells the listeners. A
     * no-op if the session already left the registry (a release closes the client, which makes
     * SSHJ report the disconnection again), or if the id now belongs to another client.
     */
    private fun sessionEnded(sessionId: String, client: SSHClient, end: SessionEnd) {
        val forgotten = synchronized(registryLock) {
            if (clients[sessionId] !== client) false else { forget(sessionId); true }
        }
        if (!forgotten) return
        Timber.i("Session ${LogId.of(sessionId)} ended (${end.reasonName})")
        notifyEnded(sessionId, end)
        if (end is SessionEnd.Lost) {
            // The raw socket first, as declareUnresponsive does: the keepalive thread ends a mute
            // link through die(), and a writer stuck on it would hold the close lock until TCP gives up.
            try {
                client.socket?.close()
            } catch (e: Exception) {
                Timber.w("Error closing the socket of a lost session (${errorClass(e)})")
            }
        }
        closeInBackground(client)
    }

    private fun notifyEnded(sessionId: String, end: SessionEnd) {
        for (listener in endListeners) {
            try {
                listener.onSessionEnded(sessionId, end)
            } catch (e: Exception) {
                Timber.w("A session end listener failed (${errorClass(e)})")
            }
        }
    }

    private fun closeInBackground(client: SSHClient) {
        closeScope.launch {
            try {
                client.disconnect()
            } catch (e: Exception) {
                Timber.w("Error closing a session (${errorClass(e)})")
            }
        }
    }

    /**
     * Ends a connection attempt that failed or was cancelled: closes [client] (null if it was never
     * built), then forgets the session. The client of an attempt is only registered in [clients]
     * once authenticated, so [cleanupSession] alone left a failed one open: its socket and its
     * reader thread lived until the server's login grace time (120 s by default), and after a
     * wait that ran out the server could still authenticate it as an orphan.
     */
    private fun abandon(sessionId: String, client: SSHClient?) {
        runCatching { client?.disconnect() }
        cleanupSession(sessionId)
    }

    /**
     * Forgets [sessionId] and closes its client, if it had one. Returns that client: null for a
     * session that never got one (a connection attempt) or that is not registered.
     */
    private fun cleanupSession(sessionId: String): SSHClient? {
        val client = synchronized(registryLock) { forget(sessionId) }
        try {
            client?.disconnect()
        } catch (_: Exception) { }
        return client
    }

    /**
     * Takes [sessionId] out of the registry, its count and its client together. Called with
     * [registryLock] held. Returns the client, null if there was none.
     */
    private fun forget(sessionId: String): SSHClient? {
        references.remove(sessionId)
        cadences.remove(sessionId)
        _activeSessions.update { it - sessionId }
        return clients.remove(sessionId)
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

/**
 * Délai (ms) d'attente du transport SSHJ (`Transport.timeoutMs`) pendant le handshake, l'authentification
 * et ensuite (ouverture de canaux, redirections). Posé aussi comme timeout de lecture du socket,
 * que le thread de lecture de SSHJ ignore (il attrape `SocketTimeoutException` et relit) : ce
 * délai ne borne donc pas un serveur muet, seule l'attente des promesses (`Transport.timeoutMs`) le fait.
 */
internal const val TRANSPORT_TIMEOUT_MS = 30_000

/**
 * Taille du PTY qu'ouvre [SshSessionManager.openShell] quand l'appelant n'en donne pas, et taille de
 * départ de l'émulateur de [SshTerminalSession] : tant qu'aucune vue n'a mesuré le terminal, les
 * deux côtés doivent s'accorder, sinon le serveur et l'émulateur n'envoient pas leurs lignes aux
 * mêmes colonnes.
 */
internal const val DEFAULT_PTY_COLUMNS = 80
internal const val DEFAULT_PTY_ROWS = 24

/**
 * Délai d'attente de la réponse d'authentification pour une clé de sécurité FIDO2 :
 * [Fido2PromptCoordinator.MAX_PROMPT_TOTAL_MS] (la demande la plus longue possible) plus
 * [TRANSPORT_TIMEOUT_MS] (la réponse du serveur), soit 5 min 30 s.
 *
 * `SSHClient.auth` attend la réponse du serveur pendant `Transport.timeoutMs`, et cette attente
 * court pendant que l'utilisateur touche sa clé ou saisit son PIN (le handler de `SkAuthMethod`
 * s'exécute dans le thread de lecture du transport, qui tient le verrou de l'attente : le délai
 * n'est constaté qu'une fois la demande terminée). Avec 30 s, un contact à 35 s échouait quand même
 * alors que la demande, elle, affichait encore le temps restant (60 s, prolongé pour le PIN), et le
 * serveur pouvait authentifier un client déjà abandonné.
 *
 * C'est aussi le délai de l'authentification par empreinte ([connectWithBiometricKey]), dont la demande
 * ([BiometricPromptCoordinator.PROMPT_TIMEOUT_MS], 60 s) est plus courte que la plus longue demande
 * FIDO2 : les deux partagent le même tour ([InteractivePromptTurn]) et le même squelette, dimensionné
 * sur la plus longue des deux.
 *
 * Ce n'est qu'un filet. Ni 0 (SSHJ attend alors sans borne) ni une longue valeur ne bornent un serveur
 * muet, puisque le socket ne le fait pas non plus : c'est le rôle de [AuthWatchdog], qui coupe la
 * connexion si le serveur ne répond pas dans [TRANSPORT_TIMEOUT_MS] avant la demande ou après la
 * signature. Le temps passé en file derrière une autre demande de clé compte dans ce délai : au
 * pire, deux demandes de 5 min en file font expirer la seconde (TIMEOUT, qui est retentée).
 */
internal const val FIDO2_AUTH_TRANSPORT_TIMEOUT_MS =
    (Fido2PromptCoordinator.MAX_PROMPT_TOTAL_MS + TRANSPORT_TIMEOUT_MS).toInt()

/**
 * Délai d'attente de la fin de l'échange de clés quand la clé de l'hôte doit être confirmée par
 * l'utilisateur : [HostKeyPromptCoordinator.PROMPT_TIMEOUT_MS] (la demande) plus
 * [TRANSPORT_TIMEOUT_MS] (le serveur), soit 90 s.
 *
 * Même raison que pour [FIDO2_AUTH_TRANSPORT_TIMEOUT_MS] : `SSHClient.connect` attend la fin de
 * l'échange de clés `Transport.timeoutMs` (30 s) à partir de son début, et la clé de l'hôte est
 * vérifiée, donc le dialogue affiché, DANS cet échange, sur le thread de lecture du transport. Avec
 * 30 s, un utilisateur qui lit l'empreinte pendant 35 s voyait la connexion échouer sous son
 * dialogue, qui affichait encore un délai de 60 s. Le temps passé en file derrière une autre demande
 * (clé de sécurité, empreinte, autre hôte) est pris sur ce délai : au pire la connexion expire
 * avant son tour (`TIMEOUT`, qui est retentée).
 *
 * Ce n'est qu'un filet, et seulement quand une demande est certaine (voir [keyExchangeTimeoutMs]) :
 * un serveur qui se tait pendant l'échange de clés fait attendre ce délai plutôt que 30 s.
 */
internal const val HOST_KEY_KEX_TRANSPORT_TIMEOUT_MS =
    (HostKeyPromptCoordinator.PROMPT_TIMEOUT_MS + TRANSPORT_TIMEOUT_MS).toInt()

/**
 * Combien de temps `client.connect` attend la fin de l'échange de clés. [HOST_KEY_KEX_TRANSPORT_TIMEOUT_MS]
 * quand aucune clé n'est enregistrée pour l'hôte ([hostKeyStored] faux) : la clé sera inconnue, donc
 * confirmée par l'utilisateur au milieu de l'échange. [TRANSPORT_TIMEOUT_MS] sinon : une clé connue
 * est reconnue sans demande, et un serveur muet pendant l'échange ne retient pas la connexion (ni les
 * cinq tentatives d'une reconnexion) plus de 30 s. Le cas d'un hôte connu qui présente une clé d'un
 * autre algorithme, donc inconnue, garde les 30 s : rare, et si la confirmation dure plus longtemps
 * la connexion échoue en `TIMEOUT`, que la reconnexion retente.
 */
internal fun keyExchangeTimeoutMs(hostKeyStored: Boolean): Int =
    if (hostKeyStored) TRANSPORT_TIMEOUT_MS else HOST_KEY_KEX_TRANSPORT_TIMEOUT_MS

/**
 * Runs [block], the authentication of [client]. When [keystoreKey] is true its runtime exceptions
 * are those of the cryptography of an Android Keystore key (see [authenticatingWithKey]); otherwise
 * they are not those of a local key (a FIDO2 key) and are left alone, except for a connection that
 * was lost before the authentication (see [authenticatingOnConnection]), which both kinds report
 * the same way.
 */
private inline fun <T> guardingKeystoreKey(client: SSHClient, keystoreKey: Boolean, block: () -> T): T =
    if (keystoreKey) authenticatingWithKey(client, block) else authenticatingOnConnection(client, block)

/**
 * Exécute [block] avec `Transport.timeoutMs` à [timeoutMs], puis le remet à [restoreMs], même si
 * [block] échoue. Ne touche pas au timeout de lecture du socket.
 */
internal inline fun <T> SSHClient.withTransportTimeout(timeoutMs: Int, restoreMs: Int, block: () -> T): T {
    transport.timeoutMs = timeoutMs
    try {
        return block()
    } finally {
        transport.timeoutMs = restoreMs
    }
}
