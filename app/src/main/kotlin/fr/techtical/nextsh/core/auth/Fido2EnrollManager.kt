// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.auth

import android.app.Activity
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.yubico.yubikit.android.YubiKitManager
import com.yubico.yubikit.android.transport.nfc.NfcConfiguration
import com.yubico.yubikit.android.transport.nfc.NfcNotAvailable
import com.yubico.yubikit.android.transport.usb.UsbConfiguration
import com.yubico.yubikit.android.transport.usb.UsbYubiKeyDevice
import com.yubico.yubikit.core.YubiKeyDevice
import com.yubico.yubikit.core.fido.CtapException
import com.yubico.yubikit.core.fido.FidoConnection
import com.yubico.yubikit.core.smartcard.SmartCardConnection
import com.yubico.yubikit.fido.client.BasicWebAuthnClient
import com.yubico.yubikit.fido.client.ClientError
import com.yubico.yubikit.fido.client.PinInvalidClientError
import com.yubico.yubikit.fido.client.PinRequiredClientError
import com.yubico.yubikit.fido.ctap.Ctap2Session
import com.yubico.yubikit.fido.webauthn.AttestationConveyancePreference
import com.yubico.yubikit.fido.webauthn.AuthenticatorAttestationResponse
import com.yubico.yubikit.fido.webauthn.AuthenticatorSelectionCriteria
import com.yubico.yubikit.fido.webauthn.PublicKeyCredentialCreationOptions
import com.yubico.yubikit.fido.webauthn.PublicKeyCredentialParameters
import com.yubico.yubikit.fido.webauthn.PublicKeyCredentialRpEntity
import com.yubico.yubikit.fido.webauthn.PublicKeyCredentialType
import com.yubico.yubikit.fido.webauthn.PublicKeyCredentialUserEntity
import com.yubico.yubikit.fido.webauthn.ResidentKeyRequirement
import com.yubico.yubikit.fido.webauthn.UserVerificationRequirement
import fr.techtical.nextsh.shared.core.ssh.fido2.SkKeyType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.security.SecureRandom
import java.util.Arrays
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gestionnaire d'enrôlement FIDO2 via YubiKey (USB/NFC).
 *
 * Cycle de vie attendu (flow NFC-safe) :
 *   1. Appeler [startListening] depuis l'Activity de l'écran d'enrôlement.
 *   2. Recueillir le PIN optionnel dans le formulaire (avant toute connexion à la clé).
 *   3. Appeler [detectAndEnroll] : attend un device puis fait detect + enroll en
 *      une seule connexion (NFC-safe : le tag reste tenu pendant tout le round-trip CTAP).
 *   4. Appeler [stopListening] dans onPause / fermeture de l'écran.
 *
 * Sécurité : le PIN est wipé après usage via [Arrays.fill].
 */
@Singleton
class Fido2EnrollManager @Inject constructor() {

    // ── État interne ──────────────────────────────────────────────────────────

    private var yubiKitManager: YubiKitManager? = null
    private var currentActivity: Activity? = null
    private var pendingDevice: CompletableDeferred<Pair<YubiKeyDevice, Transport>>? = null

    /**
     * Device reçu via intent USB_DEVICE_ATTACHED après startListening mais avant
     * que detectAndEnroll soit démarré (ex. utilisateur branche la clé avant de
     * cliquer "Détecter la clé").
     * Consommé dès le prochain appel à detectAndEnroll.
     */
    private var deferredUsbDevice: YubiKeyDevice? = null

    // ── Transport detection ───────────────────────────────────────────────────

    enum class Transport { USB, NFC }

    // ── EnrollResult ──────────────────────────────────────────────────────────

    /**
     * Résultat d'un enrôlement FIDO2 réussi.
     *
     * @param credentialId  Identifiant de credential CTAP2 (opaque bytes).
     * @param application   Application string utilisée lors du MakeCredential (ex. "ssh:").
     * @param publicKey     Clé publique brute : 32 bytes pour Ed25519, 65 bytes SEC1 pour ECDSA P-256.
     * @param keyType       Type de clé effectivement enrôlé (SK_ED25519 ou SK_ECDSA_256).
     * @param transport     Transport utilisé lors de l'enrôlement.
     */
    data class EnrollResult(
        val credentialId: ByteArray,
        val application: String,
        val publicKey: ByteArray,
        val keyType: SkKeyType = SkKeyType.SK_ED25519,
        val transport: Transport = Transport.USB,
    ) {
        fun wipe() {
            credentialId.fill(0)
            publicKey.fill(0)
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as EnrollResult
            return credentialId.contentEquals(other.credentialId)
        }

        override fun hashCode(): Int = credentialId.contentHashCode()
    }

    // ── Erreurs ───────────────────────────────────────────────────────────────

    sealed class EnrollError : Exception() {
        /** Aucun appareil détecté dans le délai imparti. */
        data object Timeout : EnrollError() {
            override val message = "Timeout : aucune clé de sécurité détectée en 60 secondes"
        }

        /** L'utilisateur a annulé l'opération. */
        data object Cancelled : EnrollError() {
            override val message = "Enrôlement annulé"
        }

        /** La clé est bloquée suite à trop de tentatives PIN erronées. */
        data object PinBlocked : EnrollError() {
            override val message = "Clé bloquée, reset requis"
        }

        /** L'algorithme demandé n'est pas supporté par cette clé. */
        data object UnsupportedAlgorithm : EnrollError() {
            override val message = "Cette clé ne supporte ni Ed25519 ni ECDSA P-256"
        }

        /** PIN incorrect. */
        data object PinInvalid : EnrollError() {
            override val message = "PIN incorrect"
        }

        /**
         * La clé exige un PIN mais aucun n'a été fourni.
         * L'utilisateur doit saisir son PIN dans le formulaire et réessayer.
         */
        data object PinRequired : EnrollError() {
            override val message = "Cette clé nécessite un PIN. Saisissez votre PIN puis réessayez."
        }

        /** Erreur de communication avec la clé. */
        data class IoError(override val message: String) : EnrollError()

        /** Format de réponse CTAP2 inattendu. */
        data class ParseError(override val message: String) : EnrollError()

        /** Toute autre erreur CTAP2. */
        data class CtapError(val code: Byte, override val message: String) : EnrollError()
    }

    // ── Listening ────────────────────────────────────────────────────────────

    /**
     * Démarre la détection USB/NFC.
     * Doit être appelé depuis une Activity (NFC foreground dispatch).
     */
    fun startListening(activity: Activity) {
        val manager = YubiKitManager(activity)
        yubiKitManager = manager
        currentActivity = activity

        // handlePermissions(false) : le manifest USB intent-filter gère déjà la permission
        // Android côté OS. Laisser YubiKit envoyer un 2e dialog de permission provoque
        // un conflit : son BroadcastReceiver interne n'est jamais notifié car Android
        // délivre le grant à MainActivity.onNewIntent via le filter manifest.
        manager.startUsbDiscovery(UsbConfiguration().handlePermissions(false)) { device ->
            Timber.d("Fido2Enroll: USB key detected via YubiKit discovery")
            pendingDevice?.complete(device to Transport.USB)
        }

        try {
            manager.startNfcDiscovery(
                // MakeCredential with PIN requires 3 CTAP2 round-trips over NFC
                // (clientPin getKeyAgreement, getPinUvAuthTokenUsingPin, makeCredential).
                // Each round-trip can take 200–500 ms on NFC → total >1 s.
                // Default timeout is 1 000 ms which causes TagLostException mid-sequence.
                NfcConfiguration().timeout(10_000),
                activity,
            ) { device ->
                Timber.d("Fido2Enroll: NFC key tapped")
                pendingDevice?.complete(device to Transport.NFC)
            }
        } catch (e: NfcNotAvailable) {
            Timber.d("Fido2Enroll: NFC not available on this device")
        }
    }

    /** Arrête la détection USB/NFC. Appeler dans onPause ou à la fermeture. */
    fun stopListening() {
        yubiKitManager?.stopUsbDiscovery()
        currentActivity?.let { yubiKitManager?.stopNfcDiscovery(it) }
        // Ne toucher NI à cancel() NI à null sur pendingDevice ici.
        //
        // cancel() déclenchait "Job was cancelled" quand le dialog de permission
        // USB suspend momentanément l'Activity sans fermer l'écran.
        //
        // Le mettre à null avait le même défaut sous une autre forme : à la
        // rotation, l'écran appelle stopListening puis startListening, alors que
        // la coroutine d'enrôlement survit dans le ViewModel. Elle attendait
        // donc un Deferred devenu orphelin, que plus personne ne pouvait
        // compléter : la clé tapée après rotation ne faisait rien et
        // l'enrôlement échouait au bout de 60 secondes.
        //
        // detectAndEnroll remet la référence à null une fois son attente finie,
        // c'est le seul endroit qui doit le faire.
        currentActivity = null
        yubiKitManager = null
    }

    /**
     * Appelée par [Fido2UsbHandlerActivity] quand Android délivre un
     * [UsbManager.ACTION_USB_DEVICE_ATTACHED] via le filter manifest.
     *
     * Avec le filter manifest, Android affiche le dialog de permission OS et, si
     * accordé, envoie l'intent à [Fido2UsbHandlerActivity] : le BroadcastReceiver
     * interne de YubiKit ne le reçoit pas. On construit le [UsbYubiKeyDevice]
     * manuellement et on complète [pendingDevice].
     *
     * Si [yubiKitManager] est null (startListening jamais appelé = l'utilisateur
     * n'est pas sur l'écran d'enrôlement), on ignore silencieusement : l'activité
     * trampoline a déjà évité le cold-start de MainActivity.
     */
    fun handleUsbAttachIntent(context: Context, usbDevice: UsbDevice) {
        val mgr = yubiKitManager
        if (mgr == null) {
            // Personne n'écoute : pas d'écran d'enrôlement actif. Ignorer.
            Timber.d("Fido2Enroll: USB attach received but no listener active: ignoring")
            return
        }

        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val yubiKeyDevice = try {
            UsbYubiKeyDevice(usbManager, usbDevice)
        } catch (e: IllegalArgumentException) {
            Timber.w(e, "Fido2Enroll: not a YubiKey device, ignoring")
            return
        }

        Timber.d("Fido2Enroll: USB device delivered via manifest intent")
        val pending = pendingDevice
        if (pending != null) {
            pending.complete(yubiKeyDevice to Transport.USB)
        } else {
            // startListening actif mais detectAndEnroll pas encore démarré :
            // mémoriser pour usage ultérieur.
            deferredUsbDevice = yubiKeyDevice
            Timber.d("Fido2Enroll: no pending deferred: USB device stored for next detectAndEnroll call")
        }
    }

    // ── Helpers connexion ────────────────────────────────────────────────────

    /**
     * Ouvre la connexion CTAP2 adaptée au transport :
     * - USB  → [FidoConnection] (HID FIDO, pas CCID)
     * - NFC  → [SmartCardConnection] (ISO 7816 APDU sur NFC)
     *
     * Le mauvais choix (SmartCard sur USB FIDO) provoque l'erreur
     * "The application couldn't be selected" car la YubiKey en mode USB HID
     * n'expose pas l'applet CCID.
     */
    private inline fun <R> withCtap2Session(
        device: YubiKeyDevice,
        transport: Transport,
        block: (Ctap2Session) -> R,
    ): R = when (transport) {
        Transport.USB ->
            device.openConnection(FidoConnection::class.java).use { block(Ctap2Session(it)) }
        Transport.NFC ->
            device.openConnection(SmartCardConnection::class.java).use { block(Ctap2Session(it)) }
    }

    // ── detectAndEnroll ───────────────────────────────────────────────────────

    /**
     * Détecte un device puis enrôle en une seule connexion (NFC-safe).
     *
     * Sur USB : attend le device → ouvre FidoConnection → enrôle.
     * Sur NFC : attend le tap → ouvre SmartCardConnection → enrôle dans la même connexion
     *           (tag tenu pendant tout le round-trip CTAP, évitant "Tag was lost").
     *
     * Le PIN est passé en argument depuis le formulaire (peut être null si la clé n'en a pas).
     * Si la clé exige un PIN et qu'aucun n'est fourni → [EnrollError.PinRequired].
     * Si le PIN est incorrect → [EnrollError.PinInvalid].
     *
     * @param label   Label utilisateur affiché dans le credential (user.name).
     * @param rpId    Relying party ID (par défaut "ssh:").
     * @param rpName  Nom lisible du RP (par défaut "NextSH").
     * @param pin     PIN saisi par l'utilisateur. Si null/vide, l'enroll tente sans PIN.
     *
     * @return [EnrollResult] avec credentialId, publicKey, keyType et transport.
     * @throws EnrollError si l'enrôlement échoue.
     */
    suspend fun detectAndEnroll(
        label: String,
        rpId: String = "ssh:",
        rpName: String = "NextSH",
        pin: CharArray? = null,
    ): EnrollResult = withContext(Dispatchers.IO) {
        // Si un device a été reçu via intent avant ce call (clé branchée avant le tap
        // sur "Détecter"), l'utiliser directement.
        val alreadyReceived = deferredUsbDevice?.also { deferredUsbDevice = null }

        val (device, transport) = if (alreadyReceived != null) {
            Timber.d("Fido2Enroll: consuming pre-received USB device from manifest intent")
            alreadyReceived to Transport.USB
        } else {
            val deferred = CompletableDeferred<Pair<YubiKeyDevice, Transport>>()
            pendingDevice = deferred

            val result = withTimeoutOrNull(60_000L) { deferred.await() }
            pendingDevice = null
            result ?: throw EnrollError.Timeout
        }

        Timber.i("Fido2Enroll: device received transport=$transport: opening session for detectAndEnroll")

        try {
            withCtap2Session(device, transport) { ctap ->
                doEnroll(
                    ctap = ctap,
                    label = label,
                    rpId = rpId,
                    rpName = rpName,
                    pin = pin,
                    transport = transport,
                )
            }
        } catch (e: EnrollError) {
            throw e
        } catch (e: PinRequiredClientError) {
            Timber.w(e, "Fido2Enroll: PIN required but not provided")
            throw EnrollError.PinRequired
        } catch (e: PinInvalidClientError) {
            Timber.w(e, "Fido2Enroll: PIN invalid")
            throw EnrollError.PinInvalid
        } catch (e: ClientError) {
            Timber.e(e, "Fido2Enroll: ClientError errorCode=${e.errorCode}")
            throw EnrollError.IoError("Erreur client FIDO2 : ${e.message}")
        } catch (e: CtapException) {
            Timber.e(e, "Fido2Enroll: CTAP error ctapError=0x%02X", e.ctapError)
            when (e.ctapError) {
                CtapException.ERR_PIN_BLOCKED -> throw EnrollError.PinBlocked
                CtapException.ERR_UNSUPPORTED_ALGORITHM -> throw EnrollError.UnsupportedAlgorithm
                else -> throw EnrollError.CtapError(
                    e.ctapError,
                    "Erreur CTAP2 0x${e.ctapError.toString(16)}: ${e.message}",
                )
            }
        } catch (e: java.io.IOException) {
            Timber.e(e, "Fido2Enroll: IO error during enroll")
            throw EnrollError.IoError("Erreur de communication : ${e.message}")
        } catch (e: com.yubico.yubikit.core.application.CommandException) {
            Timber.e(e, "Fido2Enroll: command error during enroll")
            throw EnrollError.IoError("Erreur commande CTAP2 : ${e.message}")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e  // Re-throw, ne pas convertir en EnrollError
        } finally {
            pin?.let { Arrays.fill(it, ' ') }
        }
    }

    // ── doEnroll ──────────────────────────────────────────────────────────────

    /**
     * Exécute MakeCredential via BasicWebAuthnClient.
     *
     * pubKeyCredParams : Ed25519 (-8) préféré, fallback ECDSA P-256 (-7).
     * Le client YubiKit négocie automatiquement si Ed25519 n'est pas supporté.
     */
    private fun doEnroll(
        ctap: Ctap2Session,
        label: String,
        rpId: String,
        rpName: String,
        pin: CharArray?,
        transport: Transport,
    ): EnrollResult {
        val client = BasicWebAuthnClient(ctap)

        val userId = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val challengeBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val clientDataJson = buildClientDataJson(challengeBytes, rpId)

        val options = PublicKeyCredentialCreationOptions(
            /* rp            */ PublicKeyCredentialRpEntity(rpName, rpId),
            /* user          */ PublicKeyCredentialUserEntity(label, userId, label),
            /* challenge     */ challengeBytes,
            /* pubKeyCredParams */ listOf(
                PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, -8), // Ed25519
                PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, -7), // ECDSA P-256
            ),
            /* timeout       */ 60_000L,
            /* excludeCredentials */ emptyList(),
            /* authenticatorSelection */ AuthenticatorSelectionCriteria(
                /* authenticatorAttachment */ null,
                /* residentKey */ ResidentKeyRequirement.DISCOURAGED,
                /* userVerification */ if (pin != null)
                    UserVerificationRequirement.PREFERRED
                else
                    UserVerificationRequirement.DISCOURAGED,
            ),
            /* attestation   */ AttestationConveyancePreference.NONE,
            /* extensions    */ null,
        )

        Timber.d("Fido2Enroll: calling makeCredential (waiting for touch...)")

        val credential = client.makeCredential(
            clientDataJson,
            options,
            rpId,
            pin,
            null, // enterpriseAttestation
            null, // commandState
        )

        Timber.d("Fido2Enroll: makeCredential completed")

        val attestationResponse = credential.response as? AuthenticatorAttestationResponse
            ?: throw EnrollError.ParseError("Type de réponse inattendu : ${credential.response::class.java.name}")

        val attestedCredData = attestationResponse.getAuthenticatorData()
            ?.getAttestedCredentialData()
            ?: throw EnrollError.ParseError("AttestedCredentialData absent de authenticatorData")

        val credentialId = attestedCredData.getCredentialId()
        val coseKey: Map<*, *> = attestedCredData.getCosePublicKey()

        // Détermine le type de clé depuis l'algorithme COSE (clé 3)
        val algValue = coseKey[java.lang.Integer.valueOf(3)]
        val alg = when (algValue) {
            is Int -> algValue
            is Long -> algValue.toInt()
            is Number -> algValue.toInt()
            else -> null
        }

        Timber.d("Fido2Enroll: COSE alg=$alg")

        return when (alg) {
            -7 -> {
                // ECDSA P-256 : clés COSE -2 (x, 32B) et -3 (y, 32B)
                val xBytes = coseKey[java.lang.Integer.valueOf(-2)] as? ByteArray
                    ?: throw EnrollError.ParseError("COSE P-256 : clé x (-2) manquante")
                val yBytes = coseKey[java.lang.Integer.valueOf(-3)] as? ByteArray
                    ?: throw EnrollError.ParseError("COSE P-256 : clé y (-3) manquante")
                if (xBytes.size != 32 || yBytes.size != 32) {
                    throw EnrollError.ParseError("COSE P-256 : x ou y ≠ 32 bytes (x=${xBytes.size}, y=${yBytes.size})")
                }
                // Construit le point SEC1 uncompressed : 0x04 || x || y (65 bytes)
                val sec1Key = ByteArray(65).apply {
                    this[0] = 0x04
                    xBytes.copyInto(this, 1)
                    yBytes.copyInto(this, 33)
                }
                Timber.i("Fido2Enroll: enrolled SK_ECDSA_256 credentialId=${credentialId.size}B")
                EnrollResult(
                    credentialId = credentialId.copyOf(),
                    application = rpId,
                    publicKey = sec1Key,
                    keyType = SkKeyType.SK_ECDSA_256,
                    transport = transport,
                )
            }
            else -> {
                // Ed25519 (alg -8) ou inconnu → tente le parsing Ed25519
                // COSE OKP : clé -2 (x, 32B)
                val ed25519Key = coseKey[java.lang.Integer.valueOf(-2)] as? ByteArray
                    ?: throw EnrollError.ParseError("COSE Ed25519 : clé x (-2) manquante. Alg=$alg, clés=${coseKey.keys}")
                if (ed25519Key.size != 32) {
                    throw EnrollError.ParseError("COSE Ed25519 x doit être 32 bytes, reçu ${ed25519Key.size}")
                }
                Timber.i("Fido2Enroll: enrolled SK_ED25519 credentialId=${credentialId.size}B")
                EnrollResult(
                    credentialId = credentialId.copyOf(),
                    application = rpId,
                    publicKey = ed25519Key.copyOf(),
                    keyType = SkKeyType.SK_ED25519,
                    transport = transport,
                )
            }
        }
    }

    // ── clientDataJson ────────────────────────────────────────────────────────

    /**
     * Construit un clientDataJSON minimal pour MakeCredential.
     * Parité Desktop YubiKitFidoManager.kt:1287-1291.
     */
    private fun buildClientDataJson(challenge: ByteArray, origin: String = "ssh:"): ByteArray {
        val challengeB64u = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(challenge)
        return """{"type":"webauthn.create","challenge":"$challengeB64u","origin":"$origin"}"""
            .toByteArray(Charsets.UTF_8)
    }
}
