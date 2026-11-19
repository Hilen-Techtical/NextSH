// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.techtical.nextsh.core.lock.AppLock
import fr.techtical.nextsh.shared.core.sync.DeviceIdentity
import fr.techtical.nextsh.shared.core.sync.EcdhHandshake
import fr.techtical.nextsh.shared.core.sync.EnrolledDevice
import fr.techtical.nextsh.shared.core.sync.EnrolledDeviceRepository
import fr.techtical.nextsh.shared.core.sync.EnrolledDeviceSecretStore
import fr.techtical.nextsh.shared.core.sync.Platform
import fr.techtical.nextsh.shared.domain.vault.VaultManager
import fr.techtical.nextsh.shared.util.LogId
import fr.techtical.nextsh.shared.util.errorClass
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Base64
import javax.inject.Inject

private const val TAG = "EnrollmentScanViewModel"

@HiltViewModel
class EnrollmentScanViewModel internal constructor(
    private val vaultManager: VaultManager,
    private val enrolledDeviceRepository: EnrolledDeviceRepository,
    private val client: EnrollmentClient,
    private val appLock: AppLock,
) : ViewModel() {

    /** Le constructeur de Hilt : le client HTTP est celui de l'app, les tests donnent le leur au constructeur ci-dessus. */
    @Inject
    constructor(
        vaultManager: VaultManager,
        enrolledDeviceRepository: EnrolledDeviceRepository,
        appLock: AppLock,
    ) : this(vaultManager, enrolledDeviceRepository, EnrollmentClient(), appLock)

    private val _state = MutableStateFlow<ScannedEnrollment>(ScannedEnrollment.Idle)
    val state: StateFlow<ScannedEnrollment> = _state.asStateFlow()

    private val secretStore = EnrolledDeviceSecretStore(vaultManager)

    /** QR parsé en attente de confirmation, stocké pour confirmFingerprint(). */
    private var pendingParsed: EnrollmentQrParser.Parsed? = null

    /** Clé publique brute du serveur Desktop reçue via GET /enroll. */
    private var serverPublicKeyBytes: ByteArray? = null

    /** TLS cert fingerprint du Desktop reçu via ServerHello, utilisé pour pinning Wave 3.3. */
    private var pendingTlsCertFingerprint: String? = null

    // ── Entrée caméra ────────────────────────────────────────────────────────

    /**
     * Appelé une seule fois par la caméra dès qu'un QR est lu.
     * Flow :
     * 1. Parse le QR → Error si invalide.
     * 2. GET /enroll → récupère ServerHello.
     * 3. Vérifie fingerprint(serverPubKey) == parsed.fingerprint.
     * 4. Si OK → FingerprintConfirm. Sinon → FingerprintMismatch.
     */
    fun onQrScanned(raw: String) {
        if (_state.value !is ScannedEnrollment.Scanning) return

        val parsed = EnrollmentQrParser.parse(raw)
        if (parsed == null) {
            _state.value = ScannedEnrollment.Error(EnrollmentError.InvalidQr)
            return
        }

        viewModelScope.launch {
            _state.value = ScannedEnrollment.FingerprintConfirm(parsed, "")

            val helloResult = client.fetchHello(parsed.host, parsed.port)

            helloResult.fold(
                onSuccess = { hello ->
                    val serverPubBytes = try {
                        Base64.getDecoder().decode(hello.publicKey)
                    } catch (e: IllegalArgumentException) {
                        Timber.tag(TAG).w("Desktop public key is not valid Base64")
                        _state.value = ScannedEnrollment.Error(EnrollmentError.InvalidResponse)
                        return@fold
                    }

                    val calculatedFp = EcdhHandshake.fingerprint(serverPubBytes)

                    if (calculatedFp != parsed.fingerprint) {
                        Timber.tag(TAG).w("Fingerprint mismatch: the key sent by the Desktop does not match the scanned QR")
                        serverPublicKeyBytes = null
                        pendingParsed = null
                        pendingTlsCertFingerprint = null
                        _state.value = ScannedEnrollment.FingerprintMismatch(
                            expected = parsed.fingerprint,
                            actual = calculatedFp,
                        )
                        return@fold
                    }

                    serverPublicKeyBytes = serverPubBytes
                    pendingParsed = parsed
                    pendingTlsCertFingerprint = hello.tlsCertFingerprint
                    Timber.tag(TAG).i("Desktop device detected, key matches the scanned QR")
                    _state.value = ScannedEnrollment.FingerprintConfirm(parsed, hello.deviceName)
                },
                onFailure = { e ->
                    Timber.tag(TAG).w("Desktop hello failed (${requestFailureLog(e)})")
                    _state.value = ScannedEnrollment.Error(helloFailure(e, parsed.host, parsed.port))
                },
            )
        }
    }

    // ── Confirmation utilisateur ─────────────────────────────────────────────

    /**
     * Appelé quand l'utilisateur confirme le fingerprint.
     * Flow :
     * 1. Génère keypair éphémère Android.
     * 2. Dérive sharedSecret ECDH.
     * 3. POST ClientEnroll.
     * 4. Stocke le secret dans le vault, wipe immédiat.
     * 5. Persiste l'EnrolledDevice (sans secret) dans le repository.
     */
    fun confirmFingerprint() {
        val parsed = pendingParsed ?: return
        val serverPubBytes = serverPublicKeyBytes ?: return
        val tlsCertFp = pendingTlsCertFingerprint

        _state.value = ScannedEnrollment.Submitting

        viewModelScope.launch {
            val keypair = EcdhHandshake.generateEphemeralKeypair()
            val androidPubB64 = Base64.getEncoder().encodeToString(keypair.publicKey)

            val sharedSecret = try {
                EcdhHandshake.deriveSharedSecret(
                    localPrivateKey = keypair.privateKey(),
                    remotePublicKey = serverPubBytes,
                    salt = parsed.fingerprint.toByteArray(Charsets.UTF_8),
                    info = "nextsh-lan-sync-v1",
                )
            } finally {
                keypair.wipePrivate()
                serverPublicKeyBytes?.fill(0)
                serverPublicKeyBytes = null
            }

            val payload = ClientEnroll(
                publicKey = androidPubB64,
                deviceId = DeviceIdentity.deviceId(),
                deviceName = DeviceIdentity.deviceName(),
                platform = "ANDROID",
            )

            val ackResult = client.submitEnroll(parsed.host, parsed.port, payload)
            pendingParsed = null

            ackResult.fold(
                onSuccess = { ack ->
                    try {
                        secretStore.store(ack.deviceId, sharedSecret)
                        sharedSecret.fill(0)

                        val device = EnrolledDevice(
                            deviceId = ack.deviceId,
                            deviceName = ack.deviceName,
                            platform = Platform.DESKTOP,
                            publicKeyFingerprint = parsed.fingerprint,
                            tlsCertFingerprint = tlsCertFp,
                            lastSyncAt = null,
                            enrolledAt = System.currentTimeMillis(),
                            lastKnownHost = parsed.host,
                        )
                        enrolledDeviceRepository.save(device)
                        Timber.tag(TAG).i("Enrolled device=${LogId.of(ack.deviceId)}: persisted")
                    } catch (e: fr.techtical.nextsh.shared.domain.vault.VaultAuthExpiredException) {
                        sharedSecret.fill(0)
                        // The lock screen covers the scanner: the user unlocks (the vault opens again),
                        // reads the message, and starts again with a new QR code.
                        appLock.lock()
                        _state.value = ScannedEnrollment.Error(EnrollmentError.VaultLocked)
                        return@fold
                    } catch (e: Exception) {
                        sharedSecret.fill(0)
                        Timber.tag(TAG).w("Enrollment failed (${errorClass(e)})")
                        _state.value = ScannedEnrollment.Error(EnrollmentError.NotSaved)
                        return@fold
                    }
                    _state.value = ScannedEnrollment.Success(ack.deviceName)
                },
                onFailure = { e ->
                    sharedSecret.fill(0)
                    Timber.tag(TAG).w("Enrollment request failed (${requestFailureLog(e)})")
                    _state.value = ScannedEnrollment.Error(submitFailure(e))
                },
            )
        }
    }

    // ── Reset ────────────────────────────────────────────────────────────────

    fun retry() {
        serverPublicKeyBytes?.fill(0)
        serverPublicKeyBytes = null
        pendingParsed = null
        pendingTlsCertFingerprint = null
        _state.value = ScannedEnrollment.Scanning
    }

    override fun onCleared() {
        super.onCleared()
        serverPublicKeyBytes?.fill(0)
        client.close()
    }
}

/**
 * Ce que le journal dit d'une requête d'enrôlement qui a échoué : la classe de l'exception et, pour
 * celles du client, le nom fixe de sa raison (`reason=HTTP_4XX`, `INVALID_RESPONSE`...), qui permet
 * de distinguer un QR déjà utilisé (4xx), un Desktop en erreur (5xx) et une réponse illisible. Jamais
 * le message de l'exception : [EnrollmentClient] y met le texte du JDK, avec l'adresse jointe.
 */
internal fun requestFailureLog(e: Throwable): String =
    if (e is EnrollmentClient.EnrollmentException) "${errorClass(e)}, reason=${e.reason.name}" else errorClass(e)

/**
 * Pourquoi la première requête (GET /enroll) a échoué. Seule la cause compte, jamais le message de
 * l'exception : [EnrollmentClient] y met le texte du JDK, avec l'adresse jointe.
 */
internal fun helloFailure(e: Throwable, host: String, port: Int): EnrollmentError =
    if (e is EnrollmentClient.EnrollmentException) {
        when (e.reason) {
            EnrollmentClient.Reason.TIMEOUT -> EnrollmentError.ConnectTimeout
            EnrollmentClient.Reason.NETWORK -> EnrollmentError.Unreachable(host, port)
            EnrollmentClient.Reason.HTTP_4XX -> EnrollmentError.Refused
            EnrollmentClient.Reason.HTTP_5XX -> EnrollmentError.ServerFailure
            EnrollmentClient.Reason.INVALID_RESPONSE -> EnrollmentError.InvalidResponse
        }
    } else {
        EnrollmentError.Network
    }

/** Pourquoi l'envoi de l'enrôlement (POST /enroll) a échoué, comme [helloFailure]. */
internal fun submitFailure(e: Throwable): EnrollmentError =
    if (e is EnrollmentClient.EnrollmentException) {
        when (e.reason) {
            EnrollmentClient.Reason.TIMEOUT -> EnrollmentError.SubmitTimeout
            EnrollmentClient.Reason.NETWORK -> EnrollmentError.ConnectionLost
            EnrollmentClient.Reason.HTTP_4XX -> EnrollmentError.Refused
            EnrollmentClient.Reason.HTTP_5XX -> EnrollmentError.ServerFailure
            EnrollmentClient.Reason.INVALID_RESPONSE -> EnrollmentError.InvalidResponse
        }
    } else {
        EnrollmentError.Network
    }
