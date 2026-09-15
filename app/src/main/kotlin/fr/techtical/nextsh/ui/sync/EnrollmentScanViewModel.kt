// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.techtical.nextsh.shared.core.sync.DeviceIdentity
import fr.techtical.nextsh.shared.core.sync.EcdhHandshake
import fr.techtical.nextsh.shared.core.sync.EnrolledDevice
import fr.techtical.nextsh.shared.core.sync.EnrolledDeviceRepository
import fr.techtical.nextsh.shared.core.sync.EnrolledDeviceSecretStore
import fr.techtical.nextsh.shared.core.sync.Platform
import fr.techtical.nextsh.shared.domain.vault.VaultManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Base64
import javax.inject.Inject

private const val TAG = "EnrollmentScanViewModel"

@HiltViewModel
class EnrollmentScanViewModel @Inject constructor(
    private val vaultManager: VaultManager,
    private val enrolledDeviceRepository: EnrolledDeviceRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<ScannedEnrollment>(ScannedEnrollment.Idle)
    val state: StateFlow<ScannedEnrollment> = _state.asStateFlow()

    private val client = EnrollmentClient()
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
            _state.value = ScannedEnrollment.Error("QR invalide ou format non reconnu")
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
                        _state.value = ScannedEnrollment.Error("Réponse serveur invalide : clé publique malformée")
                        return@fold
                    }

                    val calculatedFp = EcdhHandshake.fingerprint(serverPubBytes)

                    if (calculatedFp != parsed.fingerprint) {
                        Timber.w(TAG, "Fingerprint mismatch: expected=${parsed.fingerprint} actual=$calculatedFp")
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
                    Timber.i(TAG, "Desktop device detected: ${hello.deviceName} (fp=$calculatedFp)")
                    _state.value = ScannedEnrollment.FingerprintConfirm(parsed, hello.deviceName)
                },
                onFailure = { e ->
                    val msg = when (e) {
                        is EnrollmentClient.EnrollmentException -> when (e.reason) {
                            EnrollmentClient.Reason.TIMEOUT -> "Délai dépassé : le serveur Desktop ne répond pas"
                            EnrollmentClient.Reason.NETWORK -> "Impossible de joindre ${parsed.host}:${parsed.port}"
                            EnrollmentClient.Reason.HTTP_4XX -> "Erreur serveur (${e.message})"
                            EnrollmentClient.Reason.HTTP_5XX -> "Erreur interne serveur Desktop"
                            EnrollmentClient.Reason.INVALID_RESPONSE -> "Réponse serveur inattendue"
                        }
                        else -> "Erreur réseau : ${e.message}"
                    }
                    _state.value = ScannedEnrollment.Error(msg)
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
                        Timber.i(TAG, "Enrolled ${ack.deviceName}: persisted")
                    } catch (e: fr.techtical.nextsh.shared.domain.vault.VaultAuthExpiredException) {
                        sharedSecret.fill(0)
                        _state.value = ScannedEnrollment.Error("Vault verrouillé : déverrouillez le vault et réessayez")
                        return@fold
                    } catch (e: Exception) {
                        sharedSecret.fill(0)
                        _state.value = ScannedEnrollment.Error("Erreur lors de l'enrôlement : ${e.message}")
                        return@fold
                    }
                    _state.value = ScannedEnrollment.Success(ack.deviceName)
                },
                onFailure = { e ->
                    sharedSecret.fill(0)
                    val msg = when (e) {
                        is EnrollmentClient.EnrollmentException -> when (e.reason) {
                            EnrollmentClient.Reason.TIMEOUT -> "Délai dépassé lors de l'envoi"
                            EnrollmentClient.Reason.NETWORK -> "Connexion perdue avec le serveur Desktop"
                            EnrollmentClient.Reason.HTTP_4XX -> "Enrôlement refusé par le serveur (${e.message})"
                            EnrollmentClient.Reason.HTTP_5XX -> "Erreur interne serveur Desktop"
                            EnrollmentClient.Reason.INVALID_RESPONSE -> "Réponse serveur inattendue"
                        }
                        else -> "Erreur réseau : ${e.message}"
                    }
                    _state.value = ScannedEnrollment.Error(msg)
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
