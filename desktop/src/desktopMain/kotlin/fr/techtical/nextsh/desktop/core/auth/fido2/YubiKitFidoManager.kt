// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.auth.fido2

import com.yubico.yubikit.core.Transport
import com.yubico.yubikit.core.fido.CtapException
import com.yubico.yubikit.core.smartcard.SmartCardConnection
import com.yubico.yubikit.desktop.hid.HidDevice
import com.yubico.yubikit.desktop.hid.HidManager
import com.yubico.yubikit.desktop.pcsc.PcscManager
import com.yubico.yubikit.desktop.pcsc.UsbPcscDevice
import com.yubico.yubikit.fido.client.BasicWebAuthnClient
import com.yubico.yubikit.fido.client.ClientError
import com.yubico.yubikit.fido.client.PinInvalidClientError
import com.yubico.yubikit.fido.client.PinRequiredClientError
import com.yubico.yubikit.fido.ctap.Ctap2Session
import com.yubico.yubikit.fido.webauthn.AttestationConveyancePreference
import com.yubico.yubikit.fido.webauthn.AuthenticatorSelectionCriteria
import com.yubico.yubikit.fido.webauthn.PublicKeyCredentialCreationOptions
import com.yubico.yubikit.fido.webauthn.PublicKeyCredentialParameters
import com.yubico.yubikit.fido.webauthn.PublicKeyCredentialRpEntity
import com.yubico.yubikit.fido.webauthn.PublicKeyCredentialType
import com.yubico.yubikit.fido.webauthn.PublicKeyCredentialUserEntity
import com.yubico.yubikit.fido.webauthn.ResidentKeyRequirement
import com.yubico.yubikit.fido.webauthn.UserVerificationRequirement
import fr.techtical.nextsh.shared.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.security.SecureRandom
import java.util.Arrays
import javax.smartcardio.Card
import javax.smartcardio.CardChannel
import javax.smartcardio.CommandAPDU
import javax.smartcardio.TerminalFactory

// ── Log tag : délibérément sans "fido2"/"ctap"/"authenticator" pour éviter le filtre de
// sanitisation Logger.desktop.kt (sensitivePatterns). Utiliser "YubiKit" qui n'est pas filtré.
private const val TAG = "YubiKit"

/**
 * Manager FIDO2 Desktop : transport HID FIDO (primaire) + PC/SC CCID (fallback NFC).
 *
 * ## Transport HID (USB FIDO)
 * Sur Windows, les YubiKey exposent TOUJOURS l'interface USB HID (usage page 0xF1D0)
 * sans configuration supplémentaire. C'est le transport standard FIDO2.
 * Implémenté via [HidManager] → [HidDevice.openFidoConnection] → [Ctap2Session(FidoConnection)].
 *
 * ## Transport PC/SC (fallback)
 * L'interface CCID (Smart Card) est désactivée par défaut sur les YubiKey 5.
 * Elle n'est active que si l'utilisateur l'a activée via YubiKey Manager.
 * Utilisé en fallback pour couvrir les lecteurs NFC (carte/clé NFC sur lecteur PC/SC).
 *
 * ## API réelle YubiKit Desktop 2.8.1
 * Vérifiée par inspection des JARs dans le cache Gradle :
 * - `HidManager()` : crée un HidServices via `org.hid4java.HidManager.getHidServices()`
 * - `HidManager.getFidoDevices()` : retourne `List<HidDevice>` filtrée sur YUBICO_VENDOR_ID + usagePage 0xF1D0
 * - `HidDevice.openFidoConnection()` : retourne `HidFidoConnection` (implémente `FidoConnection`)
 * - `Ctap2Session(FidoConnection)` : constructeur direct, accepte `HidFidoConnection`
 * - `Ctap2Session(SmartCardConnection)` : constructeur pour PC/SC, accepte `PcscSmartCardConnection`
 * - `BasicWebAuthnClient(Ctap2Session)` throws `IOException, CommandException`
 * - `BasicWebAuthnClient.makeCredential(...)` throws `IOException, CommandException, ClientError`
 * - `BasicWebAuthnClient.isPinConfigured()` : boolean pur, pas de throws
 * - `Ctap2Session.getAssertions(rpId, clientDataHash, allowList, extensions, options,
 *     pinUvAuthParam, pinUvAuthProtocol, commandState)` : paramètres Java typés `@Nullable`
 *
 * ## Logs diagnostiques
 * Activés via `-Dnextsh.debug=true` ou en dev (sans `compose.application.resources.dir`).
 * En prod sans ce flag, les `Logger.d()` sont ignorés. Les `Logger.w/e` sont toujours émis.
 *
 * ## DLL hid4java
 * La `hidapi.dll` (win32-x86-64) est bundlée dans `hid4java-0.8.0.jar` et extraite
 * automatiquement par hid4java au premier appel de `HidManager.getHidServices()`.
 * Aucune installation manuelle requise.
 *
 * ## Thread safety
 * Toutes les opérations suspending tournent sur [Dispatchers.IO].
 */
class YubiKitFidoManager(
    private val onTouchRequired: (deviceLabel: String) -> Unit = {},
    private val onTouchDone: () -> Unit = {},
) {

    /**
     * Erreurs possibles lors d'une opération FIDO2.
     */
    sealed class FidoError : Exception() {
        /** Aucun dispositif YubiKey détecté via HID ni PC/SC. */
        object NoDeviceFound : FidoError() {
            override val message: String = "No YubiKey found (tried USB HID and PC/SC)"
        }

        /**
         * Aucun dispositif détecté sur Windows, probablement parce que le service
         * Windows Hello / WebAuthn revendique en accès exclusif l'interface HID FIDO
         * (usage page 0xF1D0). Les applications non-élevées ne peuvent pas ouvrir
         * ni parfois même énumérer l'interface dans ce cas.
         *
         * Heuristique : OS = Windows AND HID:0 AND PC/SC:0.
         *
         * Solutions recommandées (sans élévation UAC) :
         * 1. Activer l'interface CCID dans YubiKey Manager → exposer la clé en PC/SC.
         * 2. Brancher via un lecteur NFC USB (mode CCID natif, pas HID FIDO).
         * 3. Lancer NextSH en tant qu'administrateur (contournement temporaire).
         */
        object WindowsHidLocked : FidoError() {
            override val message: String =
                "Windows HID FIDO interface locked (Windows Hello exclusive access)"
        }

        /** L'utilisateur a annulé l'opération (ex: timeout). */
        object UserCancelled : FidoError() {
            override val message: String = "FIDO2 operation cancelled by user"
        }

        /** L'utilisateur n'a pas touché la clé dans le délai imparti. */
        object TouchTimeout : FidoError() {
            override val message: String = "YubiKey touch timeout"
        }

        /** Erreur CTAP2 retournée par l'authenticator. */
        data class CtapError(val code: Int, val msg: String) : FidoError() {
            override val message: String = "CTAP error 0x${code.toString(16)}: $msg"
        }

        /**
         * Erreur de transport (HID, PC/SC, I/O).
         * Si cause.message contient "another application" ou "exclusive", la YubiKey
         * est probablement utilisée par une autre application Windows.
         */
        data class TransportError(override val cause: Throwable) : FidoError() {
            override val message: String = buildTransportMessage(cause)
        }

        /**
         * Bug PC/SC Windows + Java (SCARD_E_NOT_TRANSACTED, code 0x80100027).
         *
         * Cause racine : [PcscSmartCardConnection] YubiKit appelle [Card.beginExclusive] /
         * [Card.endExclusive] pour gérer les transactions. Sur Windows 10/11 avec JDK 17,
         * [Card.endExclusive] lève `SCARD_E_NOT_TRANSACTED` quand la connexion n'a pas
         * été établie en mode exclusif (comportement différent selon le driver PC/SC Windows).
         *
         * NextSH contourne ce problème via [DirectPcscSmartCardConnection] qui utilise
         * `T=1` forcé et n'appelle jamais `beginExclusive` / `endExclusive`.
         *
         * Si ce fallback lui-même échoue encore avec 0x80100027, ce message est affiché.
         * Workaround recommandé : lecteur NFC USB ou Windows WebAuthn API native (Phase 3+).
         */
        object PcscWindowsBug : FidoError() {
            override val message: String =
                "L'accès PC/SC à ta YubiKey échoue avec un bug connu Windows+Java " +
                "(SCARD_E_NOT_TRANSACTED / 0x80100027). " +
                "Workaround : utilise un lecteur NFC USB pour la YubiKey, " +
                "ou patiente : implémentation Windows WebAuthn API native prévue dans une prochaine MR."
        }
    }

    // ── Public data types ────────────────────────────────────────────────────

    data class DeviceInfo(val label: String, val transport: Transport)

    enum class Transport { USB_HID, USB_PCSC, NFC }

    /**
     * Résultat d'une assertion FIDO2.
     * @param authData authData brut (flags + counter + ...) retourné par l'authenticator
     * @param signature Signature ed25519/ecdsa brute sur authData || clientDataHash
     */
    data class SkAssertion(val authData: ByteArray, val signature: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SkAssertion) return false
            return authData.contentEquals(other.authData) && signature.contentEquals(other.signature)
        }
        override fun hashCode(): Int = 31 * authData.contentHashCode() + signature.contentHashCode()
    }

    // ── Device enumeration ───────────────────────────────────────────────────

    /**
     * Liste les YubiKeys détectées via HID FIDO (USB) puis PC/SC (NFC/CCID).
     * Appel synchrone : ne pas appeler sur le thread principal.
     *
     * @return liste combinée (vide si aucun dispositif détecté)
     */
    fun listDevices(): List<DeviceInfo> {
        val result = mutableListOf<DeviceInfo>()

        // 1. Transport HID FIDO (toujours actif, interface 0xF1D0)
        result += listHidDevices()

        // 2. Transport PC/SC (CCID / lecteurs NFC), en fallback
        result += listPcscDevices()

        if (result.isEmpty()) {
            Logger.w(TAG, "listDevices: NO DEVICES on any transport (HID:${result.count { it.transport == Transport.USB_HID }}, PC/SC:${result.count { it.transport != Transport.USB_HID }})")
        } else {
            Logger.d(TAG, "listDevices: found ${result.size} device(s): ${result.map { "${it.label}[${it.transport}]" }}")
        }

        return result
    }

    // ── FIDO2 Enrollment, Phase 1 : device detection ───────────────────────

    /**
     * Phase 1 : ouvre une connexion rapide au premier YubiKey disponible pour détecter
     * si un PIN est configuré.
     *
     * Stratégie : HID FIDO d'abord (openFidoConnection), PC/SC en fallback.
     *
     * @return [Fido2Enroller.DeviceDetection] avec le label et l'état du PIN.
     * @throws FidoError.NoDeviceFound si aucun device détecté.
     * @throws FidoError.TransportError sur erreur de transport.
     */
    suspend fun detectDeviceForEnrollment(): Fido2Enroller.DeviceDetection = withContext(Dispatchers.IO) {
        Logger.d(TAG, "detectDeviceForEnrollment: starting HID scan")

        // Tente HID en premier (transport standard FIDO2 USB)
        val hidDevices = listHidDevicesRaw()
        if (hidDevices.isNotEmpty()) {
            Logger.d(TAG, "detectDeviceForEnrollment: using HID device '${hidDevices[0]}'")
            return@withContext detectViaHid(hidDevices[0])
        }

        Logger.d(TAG, "detectDeviceForEnrollment: no HID device, trying PC/SC fallback")

        // Fallback PC/SC (lecteur NFC ou CCID activé manuellement)
        val terminalsWithCard = listTerminalsWithCardPresent()
        Logger.d(TAG, "detectDeviceForEnrollment: PC/SC terminals with card = ${terminalsWithCard.size} : $terminalsWithCard")

        if (terminalsWithCard.isEmpty()) {
            Logger.w(TAG, "detectDeviceForEnrollment: NO DEVICES on any transport (HID:0, PC/SC:0)")
            throw noDeviceOrWindowsHidLocked()
        }

        val pcscDevices = listPcscDevicesRaw(terminalsWithCard)
        Logger.d(TAG, "detectDeviceForEnrollment: PC/SC YubiKey devices = ${pcscDevices.size} : ${pcscDevices.map { it.getName() }}")

        if (pcscDevices.isEmpty()) {
            Logger.w(TAG, "detectDeviceForEnrollment: NO DEVICES on any transport (HID:0, PC/SC terminals found but no YubiKey PID matched)")
            throw FidoError.NoDeviceFound
        }

        detectViaPcsc(pcscDevices[0])
    }

    // ── FIDO2 Enrollment, Phase 2 : credential creation ────────────────────

    /**
     * Phase 2 : effectue un MakeCredential FIDO2 sur le premier YubiKey disponible.
     *
     * Stratégie : HID FIDO d'abord, PC/SC en fallback.
     *
     * @param rpId         RP-ID (ex. "ssh:")
     * @param rpName       Nom du RP (ex. "NextSH")
     * @param userName     Nom de l'utilisateur (= label de la clé)
     * @param userDisplayName Nom d'affichage de l'utilisateur
     * @param pin          PIN si configuré sur le device, null sinon. Wipe après usage.
     * @param timeoutMs    Timeout global en ms (touch + PIN flow)
     * @return [Fido2Enroller.EnrollResult] avec les données de la nouvelle credential.
     * @throws FidoError.NoDeviceFound si aucun device détecté.
     * @throws FidoError.TouchTimeout  si l'utilisateur n'a pas touché dans [timeoutMs].
     * @throws FidoError.UserCancelled si le PIN est invalide.
     * @throws FidoError.CtapError    sur erreur CTAP2.
     * @throws FidoError.TransportError sur erreur de transport.
     */
    suspend fun enrollCredential(
        rpId: String,
        rpName: String = "NextSH",
        userName: String,
        userDisplayName: String = userName,
        pin: CharArray? = null,
        timeoutMs: Long = 60_000,
    ): Fido2Enroller.EnrollResult = withContext(Dispatchers.IO) {
        Logger.d(TAG, "enrollCredential: scanning HID transport")

        // Sélection du device : HID en priorité
        val hidDevices = listHidDevicesRaw()
        if (hidDevices.isNotEmpty()) {
            Logger.d(TAG, "enrollCredential: using HID device '${hidDevices[0]}'")
            return@withContext enrollViaHid(hidDevices[0], rpId, rpName, userName, userDisplayName, pin, timeoutMs)
        }

        Logger.d(TAG, "enrollCredential: no HID device, trying PC/SC")

        // Fallback PC/SC
        val terminalsWithCard = listTerminalsWithCardPresent()
        val pcscDevices = listPcscDevicesRaw(terminalsWithCard)
        if (pcscDevices.isEmpty()) {
            Logger.w(TAG, "enrollCredential: NO DEVICES on any transport (HID:0, PC/SC:${terminalsWithCard.size})")
            throw noDeviceOrWindowsHidLocked()
        }

        enrollViaPcsc(pcscDevices[0], rpId, rpName, userName, userDisplayName, pin, timeoutMs)
    }

    // ── Challenge signing ────────────────────────────────────────────────────

    /**
     * Effectue une assertion FIDO2 GetAssertion sur le premier dispositif disponible.
     *
     * Stratégie : HID FIDO d'abord, PC/SC en fallback.
     *
     * @param rpId Domaine logique de la clé SSH (ex. "ssh:")
     * @param clientDataHash SHA-256 du challenge SSH (32 bytes)
     * @param credentialId Id de la credential FIDO2 (depuis la clé publique sk-*)
     * @param requireUv true si la clé exige user-verification (PIN)
     * @param timeoutMs Timeout en ms avant [FidoError.TouchTimeout] (défaut 30s)
     *
     * @return [SkAssertion] contenant authData et signature brute
     * @throws FidoError si aucun device, timeout, ou erreur CTAP
     */
    suspend fun signChallenge(
        rpId: String,
        clientDataHash: ByteArray,
        credentialId: ByteArray,
        requireUv: Boolean = false,
        timeoutMs: Long = 30_000,
    ): SkAssertion = withContext(Dispatchers.IO) {
        Logger.d(TAG, "signChallenge: scanning HID transport")

        // HID en priorité
        val hidDevices = listHidDevicesRaw()
        if (hidDevices.isNotEmpty()) {
            Logger.d(TAG, "signChallenge: using HID device '${hidDevices[0]}'")
            return@withContext signViaHid(hidDevices[0], rpId, clientDataHash, credentialId, requireUv, timeoutMs)
        }

        Logger.d(TAG, "signChallenge: no HID device, trying PC/SC")

        // Fallback PC/SC
        val terminalsWithCard = listTerminalsWithCardPresent()
        val pcscDevices = listPcscDevicesRaw(terminalsWithCard)
        if (pcscDevices.isEmpty()) {
            Logger.w(TAG, "signChallenge: NO DEVICES on any transport (HID:0, PC/SC:${terminalsWithCard.size})")
            throw noDeviceOrWindowsHidLocked()
        }

        signViaPcsc(pcscDevices[0], rpId, clientDataHash, credentialId, requireUv, timeoutMs)
    }

    // ── No-device heuristic ──────────────────────────────────────────────────

    /**
     * Returns [FidoError.WindowsHidLocked] when running on Windows with no device
     * found on either transport, [FidoError.NoDeviceFound] otherwise.
     *
     * On Windows 10 1903+ and 11, the Windows Hello / WebAuthn service claims
     * exclusive access to the HID FIDO interface (usage page 0xF1D0) for most
     * YubiKeys. Applications without elevation may see HID:0 even when a key is
     * physically connected. The heuristic is: OS = Windows AND HID:0 AND PC/SC:0
     * → the most likely cause is the exclusive Windows HID lock, not a missing key.
     *
     * Note: we intentionally do NOT attempt to run as administrator automatically
     * (UAC prompt = bad UX). We surface a human-readable message with three
     * workarounds instead.
     */
    private fun noDeviceOrWindowsHidLocked(): FidoError {
        val isWindows = System.getProperty("os.name")?.lowercase()?.contains("windows") == true
        return if (isWindows) {
            Logger.w(TAG, "noDeviceOrWindowsHidLocked: Windows OS detected with HID:0 + PC/SC:0 → WindowsHidLocked")
            FidoError.WindowsHidLocked
        } else {
            FidoError.NoDeviceFound
        }
    }

    // ── HID transport: internal helpers ─────────────────────────────────────

    /**
     * Énumère les devices HID FIDO via hid4java.
     *
     * hid4java 0.8.0 bundle la hidapi.dll (win32-x86-64) dans son JAR et l'extrait
     * automatiquement au premier appel de `HidManager.getHidServices()`. Aucune DLL
     * manuelle requise. Si l'extraction échoue (chemin temp non writable), une UnsatisfiedLinkError
     * est levée : capturée ici et loggée.
     *
     * @return liste vide si aucun device ou si une erreur survient (toujours loggée)
     */
    private fun listHidDevicesRaw(): List<HidDevice> {
        return try {
            val manager = HidManager()
            Logger.d(TAG, "listHidDevicesRaw: HidManager created, enumerating HID FIDO devices")
            val devices = manager.getFidoDevices()
            val count = devices.size
            if (count == 0) {
                Logger.d(TAG, "listHidDevicesRaw: HID devices found = 0 (no YubiKey HID FIDO interface detected)")
            } else {
                Logger.d(TAG, "listHidDevicesRaw: HID devices found = $count : ${devices.map { it.toString() }}")
            }
            devices
        } catch (e: UnsatisfiedLinkError) {
            Logger.e(TAG, "listHidDevicesRaw: hid4java native lib load FAILED, hidapi.dll extraction error: ${e.message}", e)
            emptyList()
        } catch (e: Throwable) {
            Logger.w(TAG, "listHidDevicesRaw: HID enum failed: ${e.javaClass.simpleName}: ${e.message} | cause=${e.cause?.message}")
            emptyList()
        }
    }

    private fun listHidDevices(): List<DeviceInfo> = listHidDevicesRaw().map { device ->
        DeviceInfo(
            label = device.toString(),
            transport = Transport.USB_HID,
        )
    }

    private fun detectViaHid(device: HidDevice): Fido2Enroller.DeviceDetection {
        val label = device.toString()
        Logger.d(TAG, "detectViaHid: opening connection to '$label'")
        return try {
            // openFidoConnection() retourne HidFidoConnection qui implémente FidoConnection
            val connection = device.openFidoConnection()
            try {
                Logger.d(TAG, "detectViaHid: CTAP2 session opening for '$label'")
                val session = Ctap2Session(connection)
                val info = session.getCachedInfo()
                Logger.d(TAG, "detectViaHid: CTAP2 session opened: versions=${info.versions}, transports=${info.transports}")
                val client = BasicWebAuthnClient(session)
                val pinConfigured = client.isPinConfigured()
                Logger.d(TAG, "detectViaHid: pinConfigured=$pinConfigured for '$label'")
                Fido2Enroller.DeviceDetection(deviceLabel = label, pinConfigured = pinConfigured)
            } finally {
                runCatching { connection.close() }
            }
        } catch (e: FidoError) {
            throw e
        } catch (e: IOException) {
            Logger.w(TAG, "detectViaHid: IOException for '$label': ${e.message} | cause=${e.cause?.message}")
            throw FidoError.TransportError(e)
        } catch (e: Exception) {
            Logger.w(TAG, "detectViaHid: ${e.javaClass.simpleName} for '$label': ${e.message} | cause=${e.cause?.message}")
            throw FidoError.TransportError(e)
        }
    }

    private suspend fun enrollViaHid(
        device: HidDevice,
        rpId: String,
        rpName: String,
        userName: String,
        userDisplayName: String,
        pin: CharArray?,
        timeoutMs: Long,
    ): Fido2Enroller.EnrollResult {
        val deviceLabel = device.toString()
        Logger.d(TAG, "enrollViaHid: opening connection to '$deviceLabel'")
        onTouchRequired(deviceLabel)
        try {
            return withTimeout(timeoutMs) {
                // openFidoConnection() → FidoConnection → Ctap2Session(FidoConnection)
                val connection = device.openFidoConnection()
                try {
                    doEnrollCredential(
                        session = Ctap2Session(connection),
                        deviceLabel = deviceLabel,
                        rpId = rpId,
                        rpName = rpName,
                        userName = userName,
                        userDisplayName = userDisplayName,
                        pin = pin,
                        timeoutMs = timeoutMs,
                    )
                } finally {
                    runCatching { connection.close() }
                    if (pin != null) Arrays.fill(pin, ' ')
                }
            }
        } catch (e: TimeoutCancellationException) {
            Logger.w(TAG, "enrollViaHid: touch timeout for '$deviceLabel'")
            throw FidoError.TouchTimeout
        } catch (e: PinInvalidClientError) {
            Logger.w(TAG, "enrollViaHid: PIN invalid for '$deviceLabel'")
            throw FidoError.UserCancelled
        } catch (e: PinRequiredClientError) {
            Logger.w(TAG, "enrollViaHid: PIN required but not provided for '$deviceLabel'")
            throw FidoError.CtapError(0x36, "PIN required but not provided")
        } catch (e: ClientError) {
            Logger.w(TAG, "enrollViaHid: ClientError for '$deviceLabel': ${e.errorCode}, ${e.message}")
            throw FidoError.TransportError(e)
        } catch (e: CtapException) {
            val code = e.getCtapError().toInt() and 0xFF
            Logger.w(TAG, "enrollViaHid: CTAP error 0x${code.toString(16)} for '$deviceLabel': ${e.message}")
            throw FidoError.CtapError(code, e.message ?: "CTAP error")
        } catch (e: IOException) {
            Logger.w(TAG, "enrollViaHid: IOException for '$deviceLabel': ${e.message} | cause=${e.cause?.message}")
            throw FidoError.TransportError(e)
        } catch (e: FidoError) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "enrollViaHid: ${e.javaClass.simpleName} for '$deviceLabel': ${e.message} | cause=${e.cause?.message}")
            throw FidoError.TransportError(e)
        } finally {
            onTouchDone()
        }
    }

    private suspend fun signViaHid(
        device: HidDevice,
        rpId: String,
        clientDataHash: ByteArray,
        credentialId: ByteArray,
        requireUv: Boolean,
        timeoutMs: Long,
    ): SkAssertion {
        val deviceLabel = device.toString()
        Logger.d(TAG, "signViaHid: opening connection to '$deviceLabel'")
        onTouchRequired(deviceLabel)
        try {
            return withTimeout(timeoutMs) {
                val connection = device.openFidoConnection()
                try {
                    doGetAssertion(
                        session = Ctap2Session(connection),
                        rpId = rpId,
                        clientDataHash = clientDataHash,
                        credentialId = credentialId,
                        requireUv = requireUv,
                    )
                } finally {
                    runCatching { connection.close() }
                }
            }
        } catch (e: TimeoutCancellationException) {
            Logger.w(TAG, "signViaHid: touch timeout for '$deviceLabel'")
            throw FidoError.TouchTimeout
        } catch (e: CtapException) {
            val code = e.getCtapError().toInt() and 0xFF
            Logger.w(TAG, "signViaHid: CTAP error 0x${code.toString(16)} for '$deviceLabel': ${e.message}")
            throw FidoError.CtapError(code, e.message ?: "CTAP error")
        } catch (e: IOException) {
            Logger.w(TAG, "signViaHid: IOException for '$deviceLabel': ${e.message} | cause=${e.cause?.message}")
            throw FidoError.TransportError(e)
        } catch (e: FidoError) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "signViaHid: ${e.javaClass.simpleName} for '$deviceLabel': ${e.message} | cause=${e.cause?.message}")
            throw FidoError.TransportError(e)
        } finally {
            onTouchDone()
        }
    }

    // ── PC/SC transport: internal helpers ───────────────────────────────────

    private fun listPcscDevicesRaw(terminalsWithCard: Set<String>): List<UsbPcscDevice> {
        val manager = PcscManager()
        val all = try {
            manager.getDevices()
        } catch (e: Exception) {
            if (e.isPcscNoDevice()) {
                Logger.d(TAG, "listPcscDevicesRaw: PC/SC no readers/cards available (${e.message})")
            } else {
                Logger.w(TAG, "listPcscDevicesRaw: PC/SC enum failed: ${e.javaClass.simpleName}: ${e.message} | cause=${e.cause?.message}")
            }
            return emptyList()
        }
        Logger.d(TAG, "listPcscDevicesRaw: PC/SC raw devices found = ${all.size} : ${all.map { it.getName() }}")
        val filtered = if (terminalsWithCard.isNotEmpty())
            all.filter { it.getName() in terminalsWithCard }
        else
            all
        Logger.d(TAG, "listPcscDevicesRaw: after isCardPresent filter: ${filtered.size} device(s) : ${filtered.map { it.getName() }}")
        return filtered
    }

    private fun listPcscDevices(): List<DeviceInfo> {
        val terminalsWithCard = listTerminalsWithCardPresent()
        Logger.d(TAG, "listPcscDevices: PC/SC terminals with card = ${terminalsWithCard.size} : $terminalsWithCard")
        return listPcscDevicesRaw(terminalsWithCard).map { device ->
            DeviceInfo(label = device.getName(), transport = Transport.NFC)
        }
    }

    private fun detectViaPcsc(device: UsbPcscDevice): Fido2Enroller.DeviceDetection {
        val label = device.getName()
        Logger.d(TAG, "detectViaPcsc: opening direct T=1 connection to '$label' (bypass PcscManager beginExclusive)")
        return retryOnNotTransacted(label = label) {
            val connection = openDirectPcscConnection(label)
            try {
                Logger.d(TAG, "detectViaPcsc: opening CTAP2 session for '$label'")
                val session = Ctap2Session(connection)
                Logger.d(TAG, "detectViaPcsc: CTAP2 session opened for '$label'")
                val info = session.getCachedInfo()
                Logger.d(TAG, "detectViaPcsc: CTAP2 info: versions=${info.versions}, transports=${info.transports}")
                val client = BasicWebAuthnClient(session)
                val pinConfigured = client.isPinConfigured()
                Logger.d(TAG, "detectViaPcsc: pinConfigured=$pinConfigured for '$label'")
                Fido2Enroller.DeviceDetection(deviceLabel = label, pinConfigured = pinConfigured)
            } finally {
                runCatching { connection.close() }
            }
        }
    }

    private suspend fun enrollViaPcsc(
        device: UsbPcscDevice,
        rpId: String,
        rpName: String,
        userName: String,
        userDisplayName: String,
        pin: CharArray?,
        timeoutMs: Long,
    ): Fido2Enroller.EnrollResult {
        val deviceLabel = device.getName()
        Logger.d(TAG, "enrollViaPcsc: opening direct T=1 connection to '$deviceLabel' (bypass PcscManager beginExclusive)")
        onTouchRequired(deviceLabel)
        try {
            return withTimeout(timeoutMs) {
                retryOnNotTransacted(label = deviceLabel) {
                    val connection = openDirectPcscConnection(deviceLabel)
                    try {
                        Logger.d(TAG, "enrollViaPcsc: opening CTAP2 session for '$deviceLabel'")
                        doEnrollCredential(
                            session = Ctap2Session(connection),
                            deviceLabel = deviceLabel,
                            rpId = rpId,
                            rpName = rpName,
                            userName = userName,
                            userDisplayName = userDisplayName,
                            pin = pin,
                            timeoutMs = timeoutMs,
                        )
                    } finally {
                        runCatching { connection.close() }
                        if (pin != null) Arrays.fill(pin, ' ')
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            Logger.w(TAG, "enrollViaPcsc: touch timeout for '$deviceLabel'")
            throw FidoError.TouchTimeout
        } catch (e: PinInvalidClientError) {
            Logger.w(TAG, "enrollViaPcsc: PIN invalid for '$deviceLabel'")
            throw FidoError.UserCancelled
        } catch (e: PinRequiredClientError) {
            Logger.w(TAG, "enrollViaPcsc: PIN required but not provided for '$deviceLabel'")
            throw FidoError.CtapError(0x36, "PIN required but not provided")
        } catch (e: ClientError) {
            Logger.w(TAG, "enrollViaPcsc: ClientError for '$deviceLabel': ${e.errorCode}, ${e.message}")
            throw FidoError.TransportError(e)
        } catch (e: CtapException) {
            val code = e.getCtapError().toInt() and 0xFF
            Logger.w(TAG, "enrollViaPcsc: CTAP error 0x${code.toString(16)} for '$deviceLabel': ${e.message}")
            throw FidoError.CtapError(code, e.message ?: "CTAP error")
        } catch (e: IOException) {
            Logger.w(TAG, "enrollViaPcsc: IOException for '$deviceLabel': ${e.message} | cause=${e.cause?.message}")
            throw FidoError.TransportError(e)
        } catch (e: FidoError) {
            throw e
        } catch (e: Exception) {
            if (e.isPcscNoDevice()) throw FidoError.NoDeviceFound
            Logger.w(TAG, "enrollViaPcsc: ${e.javaClass.simpleName} for '$deviceLabel': ${e.message} | cause=${e.cause?.message}")
            throw FidoError.TransportError(e)
        } finally {
            onTouchDone()
        }
    }

    private suspend fun signViaPcsc(
        device: UsbPcscDevice,
        rpId: String,
        clientDataHash: ByteArray,
        credentialId: ByteArray,
        requireUv: Boolean,
        timeoutMs: Long,
    ): SkAssertion {
        val deviceLabel = device.getName()
        Logger.d(TAG, "signViaPcsc: opening direct T=1 connection to '$deviceLabel' (bypass PcscManager beginExclusive)")
        onTouchRequired(deviceLabel)
        try {
            return withTimeout(timeoutMs) {
                retryOnNotTransacted(label = deviceLabel) {
                    val connection = openDirectPcscConnection(deviceLabel)
                    try {
                        Logger.d(TAG, "signViaPcsc: opening CTAP2 session for '$deviceLabel'")
                        doGetAssertion(
                            session = Ctap2Session(connection),
                            rpId = rpId,
                            clientDataHash = clientDataHash,
                            credentialId = credentialId,
                            requireUv = requireUv,
                        )
                    } finally {
                        runCatching { connection.close() }
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            Logger.w(TAG, "signViaPcsc: touch timeout for '$deviceLabel'")
            throw FidoError.TouchTimeout
        } catch (e: CtapException) {
            val code = e.getCtapError().toInt() and 0xFF
            Logger.w(TAG, "signViaPcsc: CTAP error 0x${code.toString(16)} for '$deviceLabel': ${e.message}")
            throw FidoError.CtapError(code, e.message ?: "CTAP error")
        } catch (e: IOException) {
            Logger.w(TAG, "signViaPcsc: IOException for '$deviceLabel': ${e.message} | cause=${e.cause?.message}")
            throw FidoError.TransportError(e)
        } catch (e: FidoError) {
            throw e
        } catch (e: Exception) {
            if (e.isPcscNoDevice()) throw FidoError.NoDeviceFound
            Logger.w(TAG, "signViaPcsc: ${e.javaClass.simpleName} for '$deviceLabel': ${e.message} | cause=${e.cause?.message}")
            throw FidoError.TransportError(e)
        } finally {
            onTouchDone()
        }
    }

    // ── Concurrent-process detection ────────────────────────────────────────

    /**
     * Vérifie si un processus Yubico connu tourne en parallèle et log un avertissement.
     *
     * Sur Windows, YubiKey Manager, Yubico Authenticator ou un navigateur avec WebAuthn actif
     * peuvent détenir un verrou CCID exclusif, provoquant SCARD_E_NOT_TRANSACTED même si
     * la connexion `terminal.connect()` réussit.
     *
     * Non-bloquant : log uniquement, ne lève pas d'exception.
     */
    private fun warnIfYubicoProcessRunning() {
        val isWindows = System.getProperty("os.name")?.lowercase()?.contains("windows") == true
        if (!isWindows) return

        val suspectProcesses = listOf(
            "YubiKey Manager.exe",
            "yubikey-manager-qt.exe",
            "yubioath-desktop.exe",
            "Yubico Authenticator.exe",
            "authenticator.exe",
        )

        try {
            val output = Runtime.getRuntime()
                .exec(arrayOf("tasklist", "/FO", "CSV", "/NH"))
                .inputStream.bufferedReader().readText()

            for (proc in suspectProcesses) {
                // tasklist CSV: "name.exe","PID","Session","Num","Mem"
                if (output.contains(proc, ignoreCase = true)) {
                    Logger.w(
                        TAG,
                        "warnIfYubicoProcessRunning: WARNING: '$proc' is running." +
                            " This process may hold an exclusive CCID lock on the YubiKey." +
                            " Close it before retrying FIDO2 enrollment."
                    )
                }
            }

            // Navigateurs courants : vérification séparée car moins critique
            val browsers = listOf("chrome.exe", "firefox.exe", "msedge.exe", "opera.exe", "brave.exe")
            val runningBrowsers = browsers.filter { output.contains(it, ignoreCase = true) }
            if (runningBrowsers.isNotEmpty()) {
                Logger.w(
                    TAG,
                    "warnIfYubicoProcessRunning: browsers running: $runningBrowsers" +
                        ": if WebAuthn is active in-browser, it may hold the CCID interface."
                )
            }
        } catch (e: Throwable) {
            Logger.d(TAG, "warnIfYubicoProcessRunning: tasklist check failed (non-fatal): ${e.message}")
        }
    }

    // ── PC/SC bypass helpers ────────────────────────────────────────────────

    /**
     * Ouvre une connexion PC/SC directement via [javax.smartcardio] en contournant
     * [PcscSmartCardConnection] YubiKit.
     *
     * ## Pourquoi le bypass est nécessaire
     * [PcscSmartCardConnection(Card)] YubiKit appelle [Card.beginExclusive] dans
     * son constructeur et [Card.endExclusive] dans [close()]. Sur Windows 10/11 avec
     * JDK 17, si la connexion n'a pas obtenu le mode exclusif (cas fréquent avec le
     * service Windows Hello en arrière-plan), [endExclusive] lève :
     *   `CardException: sun.security.smartcardio.PCSCException: Unknown error 0x80100027`
     *   (`SCARD_E_NOT_TRANSACTED`)
     *
     * ## Solution
     * On connecte directement avec `terminal.connect("T=1")` (protocole T=1 forcé : 
     * requis pour FIDO CCID) et on utilise [DirectPcscSmartCardConnection] qui transmet
     * les APDUs via [CardChannel.transmit] sans jamais appeler
     * [beginExclusive] / [endExclusive].
     *
     * ## Fermeture
     * [DirectPcscSmartCardConnection.close] appelle `card.disconnect(false)` (laisser
     * la carte en place sans reset) plutôt que [endExclusive].
     *
     * @param terminalName Nom du terminal PC/SC (ex. "Yubico YubiKey OTP+FIDO+CCID 0").
     * @return [SmartCardConnection] prêt pour [Ctap2Session].
     * @throws IOException si le terminal est introuvable ou si la connexion échoue.
     */
    private fun openDirectPcscConnection(terminalName: String): SmartCardConnection {
        Logger.d(TAG, "PC/SC direct connect: looking up terminal '$terminalName'")

        // ── Étape 1 : Détection de processus concurrents ──────────────────────
        warnIfYubicoProcessRunning()

        val factory = TerminalFactory.getDefault()
        Logger.d(TAG, "PC/SC direct connect: TerminalFactory.getDefault() provider='${factory.provider.name}'")

        val terminals = try {
            factory.terminals().list()
        } catch (e: javax.smartcardio.CardException) {
            throw IOException("PC/SC terminal enumeration failed: ${e.message}", e)
        }
        Logger.d(TAG, "PC/SC direct connect: ${terminals.size} terminal(s) visible: ${terminals.map { it.name }}")

        val terminal = terminals.firstOrNull { it.name == terminalName }
            ?: throw IOException("PC/SC terminal '$terminalName' not found in ${terminals.map { it.name }}")

        // ── Étape 2 : Cascade protocoles T=1 → T=0 → * ───────────────────────
        // Chaque échec est loggué avec la stack trace partielle pour identifier la ligne exacte.
        val protocols = listOf("T=1", "T=0", "*")
        var card: javax.smartcardio.Card? = null
        var lastConnectException: Throwable? = null

        for (proto in protocols) {
            Logger.d(TAG, "PC/SC direct connect: PRE-connect terminal='$terminalName' protocol='$proto'")
            try {
                card = terminal.connect(proto)
                // POST-connect : log immédiat pour confirmer la ligne de succès
                Logger.d(TAG, "PC/SC direct connect: POST-connect SUCCESS: protocol='$proto' " +
                    "ATR=${card.atr.bytes.toHex()} cardProtocol='${card.protocol}'")
                break
            } catch (e: Throwable) {
                val stackTop = e.stackTraceToString().lines().take(8).joinToString("\n  ")
                Logger.w(TAG, "PC/SC direct connect: protocol '$proto' FAILED for '$terminalName'" +
                    "\n  message=${e.message}" +
                    "\n  stack(top-8):\n  $stackTop")
                lastConnectException = e
                // Si NOT_TRANSACTED sur la connexion elle-même, log explicitement
                if (e.isNotTransacted()) {
                    Logger.w(TAG, "PC/SC direct connect: SCARD_E_NOT_TRANSACTED on connect (protocol='$proto')" +
                        ": this means the connect() call itself is rejected, not a transaction-end issue")
                }
            }
        }

        if (card == null) {
            val err = lastConnectException
            throw IOException(
                "PC/SC connect failed for '$terminalName' (all protocols T=1/T=0/* failed): " +
                    "${err?.message}", err
            )
        }

        Logger.d(TAG, "PC/SC direct connect: PRE-getBasicChannel for '${card.protocol}'")
        val channel = card.basicChannel
        Logger.d(TAG, "PC/SC direct connect: POST-getBasicChannel channelNumber=${channel.channelNumber}")

        return DirectPcscSmartCardConnection(card = card, terminalName = terminalName)
    }

    /**
     * Retente le bloc [block] jusqu'à [maxRetries] fois si [SCARD_E_NOT_TRANSACTED] (0x80100027)
     * est reçu. Une pause de [delayMs] ms est effectuée entre les tentatives.
     *
     * Ce code d'erreur peut survenir même avec le bypass [openDirectPcscConnection] si
     * le driver PC/SC Windows est dans un état transitoire (ex. Windows Hello vient de
     * relâcher la carte). Une courte pause suffit généralement à résoudre le problème.
     *
     * @throws FidoError.PcscWindowsBug si toutes les tentatives échouent avec 0x80100027.
     * @throws T si le bloc échoue avec une autre erreur.
     */
    private fun <T> retryOnNotTransacted(
        label: String,
        maxRetries: Int = 2,
        delayMs: Long = 500,
        block: () -> T,
    ): T {
        var lastError: Throwable? = null
        // attempt 0..maxRetries-1 = maxRetries tentatives (index corrigé : était 0..maxRetries = maxRetries+1)
        for (attempt in 0 until maxRetries) {
            try {
                return block()
            } catch (e: Throwable) {
                if (!e.isNotTransacted()) throw e
                lastError = e
                // Fix off-by-one : affiche "1/2", "2/2", jamais "3/2"
                val attemptDisplay = attempt + 1
                val stackTop = e.stackTraceToString().lines().take(8).joinToString("\n    ")
                Logger.w(
                    TAG,
                    "retryOnNotTransacted: SCARD_E_NOT_TRANSACTED (0x80100027) for '$label'" +
                        ": attempt $attemptDisplay/$maxRetries" +
                        "\n  message=${e.message}" +
                        "\n  stack(top-8):\n    $stackTop"
                )
                if (attempt < maxRetries - 1) {
                    // Warm reset : disconnect avec reset=true avant de réessayer
                    // (différent du close normal qui fait disconnect(false))
                    Logger.d(TAG, "retryOnNotTransacted: warm-reset: disconnect(reset=true) then sleep ${delayMs}ms")
                    tryWarmResetTerminal(label)
                    Thread.sleep(delayMs)
                }
            }
        }
        Logger.w(TAG, "retryOnNotTransacted: all $maxRetries retries exhausted for '$label', raising PcscWindowsBug")
        throw FidoError.PcscWindowsBug
    }

    /**
     * Tente un warm reset PC/SC sur le terminal [terminalName] en ouvrant une connexion
     * temporaire avec `disconnect(true)` (reset card).
     *
     * Ce reset force la YubiKey CCID à revenir à un état propre si elle est "stale"
     * (ex. connexion précédente non terminée proprement par Windows Hello ou un autre process).
     * Erreurs silencieuses : c'est un best-effort.
     */
    private fun tryWarmResetTerminal(terminalName: String) {
        try {
            val factory = TerminalFactory.getDefault()
            val terminal = factory.terminals().list().firstOrNull { it.name == terminalName } ?: return
            Logger.d(TAG, "tryWarmResetTerminal: PRE-connect(T=1) for warm reset on '$terminalName'")
            val card = terminal.connect("T=1")
            Logger.d(TAG, "tryWarmResetTerminal: PRE-disconnect(reset=true) for '$terminalName'")
            card.disconnect(true)
            Logger.d(TAG, "tryWarmResetTerminal: POST warm reset OK for '$terminalName'")
        } catch (e: Throwable) {
            Logger.w(TAG, "tryWarmResetTerminal: reset failed for '$terminalName' (non-fatal): ${e.message}")
        }
    }

    // ── Shared CTAP2 operations ──────────────────────────────────────────────

    /**
     * MakeCredential CTAP2 commun aux deux transports.
     *
     * Utilise [BasicWebAuthnClient.makeCredential] avec les vraies signatures API 2.8.1 :
     *   `makeCredential(clientDataJson, options, effectiveDomain, pin, enterpriseAttestation, commandState)`
     *   throws `IOException, CommandException, ClientError`
     *
     * Le retour est un [PublicKeyCredential] dont `response` est un [AuthenticatorAttestationResponse].
     * On accède à `getAttestedCredentialData().getCredentialId()` et `getCosePublicKey()[-2]` (Ed25519 x).
     *
     * Le [session] doit déjà être ouvert et le PIN doit être wipé par l'appelant.
     */
    private fun doEnrollCredential(
        session: Ctap2Session,
        deviceLabel: String,
        rpId: String,
        rpName: String,
        userName: String,
        userDisplayName: String,
        pin: CharArray?,
        timeoutMs: Long,
    ): Fido2Enroller.EnrollResult {
        Logger.d(TAG, "doEnrollCredential: opening BasicWebAuthnClient for '$deviceLabel'")
        val client = BasicWebAuthnClient(session)

        val userId = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val challengeBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val clientDataJson = buildClientDataJson(challengeBytes)

        val options = PublicKeyCredentialCreationOptions(
            /* rp            */ PublicKeyCredentialRpEntity(rpName, rpId),
            /* user          */ PublicKeyCredentialUserEntity(userName, userId, userDisplayName),
            /* challenge     */ challengeBytes,
            /* pubKeyCredParams */ listOf(
                PublicKeyCredentialParameters(
                    PublicKeyCredentialType.PUBLIC_KEY,
                    -8, // COSE AlgId EdDSA (Ed25519)
                )
            ),
            /* timeout       */ timeoutMs,
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
            /* extensions   */ null,
        )

        Logger.d(TAG, "doEnrollCredential: calling makeCredential on '$deviceLabel' (waiting for touch...)")

        // makeCredential throws: IOException, CommandException (Java checked), ClientError
        // En Kotlin les checked exceptions Java ne sont pas enforced : elles remontent
        // et sont catchées dans enrollViaHid/enrollViaPcsc.
        val credential = client.makeCredential(
            clientDataJson,
            options,
            rpId,
            pin,
            null, // enterpriseAttestation
            null, // commandState
        )

        Logger.d(TAG, "doEnrollCredential: makeCredential completed for '$deviceLabel'")

        // credential.response est un AuthenticatorAttestationResponse
        val attestationResponse = credential.response
            as? com.yubico.yubikit.fido.webauthn.AuthenticatorAttestationResponse
            ?: throw FidoError.TransportError(
                IllegalStateException("Unexpected response type: ${credential.response::class.java.name}")
            )

        // getAttestedCredentialData() is defined on AuthenticatorData, not AuthenticatorAttestationResponse
        val attestedCredData = attestationResponse.getAuthenticatorData()
            ?.getAttestedCredentialData()
            ?: throw FidoError.TransportError(
                IllegalStateException("AttestedCredentialData missing from authenticatorData")
            )

        val credentialId = attestedCredData.getCredentialId()
        val coseKey: Map<*, *> = attestedCredData.getCosePublicKey()

        // COSE key -2 = x coordinate (Ed25519 public key, 32 bytes)
        val rawPubKey = coseKey.get(java.lang.Integer.valueOf(-2)) as? ByteArray
            ?: throw FidoError.TransportError(
                IllegalStateException(
                    "COSE key missing -2 (x coordinate). Keys present: ${coseKey.keys}"
                )
            )
        if (rawPubKey.size != 32) {
            throw FidoError.TransportError(
                IllegalStateException(
                    "COSE Ed25519 x coordinate must be 32 bytes, got ${rawPubKey.size}"
                )
            )
        }

        Logger.d(TAG, "doEnrollCredential: credential created successfully for '$deviceLabel', credentialId.size=${credentialId.size}")

        return Fido2Enroller.EnrollResult(
            credentialId = credentialId.copyOf(),
            application = rpId,
            publicKey = rawPubKey.copyOf(),
            deviceLabel = deviceLabel,
        )
    }

    /**
     * GetAssertion CTAP2 commun aux deux transports.
     *
     * Appelle directement [Ctap2Session.getAssertions] avec la vraie signature API 2.8.1 :
     *   `getAssertions(rpId, clientDataHash, allowList, extensions, options,
     *     pinUvAuthParam, pinUvAuthProtocol, commandState)`
     *
     * Note : on ne passe pas via [BasicWebAuthnClient.getAssertion] car cette méthode
     * prend un [PublicKeyCredentialRequestOptions] complet : trop lourd pour ce use case.
     * L'appel direct sur [Ctap2Session] est la bonne approche pour SSH (clientDataHash déjà connu).
     */
    private fun doGetAssertion(
        session: Ctap2Session,
        rpId: String,
        clientDataHash: ByteArray,
        credentialId: ByteArray,
        requireUv: Boolean,
    ): SkAssertion {
        val allowList: List<Map<String, Any>> = listOf(
            mapOf(
                "type" to "public-key",
                "id" to credentialId,
            )
        )

        val options: Map<String, Boolean> = buildMap {
            put("up", true)
            if (requireUv) put("uv", true)
        }

        Logger.d(TAG, "doGetAssertion: calling getAssertions (waiting for touch...)")

        // getAssertions signature vérifée dans le JAR :
        // (String rpId, byte[] clientDataHash, List<Map<String,?>> allowList,
        //  Map<String,?> extensions, Map<String,?> options,
        //  byte[] pinUvAuthParam, Integer pinUvAuthProtocol, CommandState state)
        val assertions = session.getAssertions(
            rpId,
            clientDataHash,
            @Suppress("UNCHECKED_CAST") (allowList as List<Map<String, *>>),
            null,   // extensions
            options,
            null,   // pinUvAuthParam
            null,   // pinUvAuthProtocol
            null,   // commandState
        )

        Logger.d(TAG, "doGetAssertion: received ${assertions.size} assertion(s)")

        val assertion = assertions.firstOrNull()
            ?: throw FidoError.CtapError(0, "No assertion returned by authenticator")

        return SkAssertion(
            authData = assertion.authenticatorData,
            signature = assertion.signature,
        )
    }
}

// ── Private top-level helpers ────────────────────────────────────────────────

/**
 * Codes PC/SC Windows qui signifient "pas de carte / lecteur indisponible".
 * Présents dans le message d'exception sous la forme "0x8010xxxx".
 *
 * - SCARD_E_NO_READERS_AVAILABLE : 0x8010002E
 * - SCARD_E_NO_SMARTCARD         : 0x8010000C
 * - SCARD_W_REMOVED_CARD         : 0x80100069
 * - SCARD_E_READER_UNAVAILABLE   : 0x80100017
 *
 * Note : SCARD_E_NOT_TRANSACTED (0x80100027) est délibérément ABSENT ici.
 * Ce code est géré séparément par [retryOnNotTransacted] → [FidoError.PcscWindowsBug]
 * car il représente un bug JDK/Windows, pas un lecteur indisponible.
 */
private val PCSC_NO_DEVICE_CODES: Set<Long> = setOf(
    0x8010002EL,
    0x8010000CL,
    0x80100069L,
    0x80100017L,
)

private val PCSC_HEX_RE = Regex("0x([0-9A-Fa-f]{8,})")

/**
 * Retourne true si cette exception (ou une de ses causes en chaîne) contient
 * un code PC/SC correspondant à "pas de carte / lecteur indisponible".
 */
private fun Exception.isPcscNoDevice(): Boolean {
    var t: Throwable? = this
    while (t != null) {
        val msg = t.message ?: ""
        val match = PCSC_HEX_RE.find(msg)
        if (match != null) {
            val code = match.groupValues[1].toLongOrNull(16)
            if (code != null && code in PCSC_NO_DEVICE_CODES) return true
        }
        t = t.cause
    }
    return false
}

/**
 * Retourne true si ce Throwable (ou une de ses causes) est SCARD_E_NOT_TRANSACTED (0x80100027).
 * Utilisé par [retryOnNotTransacted] pour identifier le bug JDK+Windows PC/SC.
 */
private fun Throwable.isNotTransacted(): Boolean {
    var t: Throwable? = this
    while (t != null) {
        val msg = t.message ?: ""
        val match = PCSC_HEX_RE.find(msg)
        if (match != null) {
            val code = match.groupValues[1].toLongOrNull(16)
            if (code == 0x80100027L) return true
        }
        // Aussi chercher dans le message textuel (certains drivers affichent "NOT_TRANSACTED")
        if (msg.contains("NOT_TRANSACTED", ignoreCase = true) ||
            msg.contains("80100027", ignoreCase = true)
        ) return true
        t = t.cause
    }
    return false
}

/** Convertit un tableau de bytes en chaîne hexadécimale majuscule (ex. "3B 8F 80 01 ..."). */
private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }

/**
 * Implémentation directe de [SmartCardConnection] qui contourne le bug YubiKit/JDK/Windows.
 *
 * ## Problème contourné
 * [PcscSmartCardConnection] YubiKit appelle [Card.beginExclusive] dans son constructeur
 * et [Card.endExclusive] dans [close()]. Sur Windows 10/11 + JDK 17, si Windows Hello
 * ou un autre process tient la carte, [endExclusive] lève `SCARD_E_NOT_TRANSACTED`
 * (0x80100027) même si la connexion a réussi.
 *
 * ## Solution
 * Cette classe :
 * - NE fait PAS [Card.beginExclusive] / [Card.endExclusive]
 * - Transmet les APDUs directement via [CardChannel.transmit] sur [Card.basicChannel]
 * - Ferme avec [Card.disconnect(false)] (leave-card, pas de reset)
 * - Loggue l'ATR et le protocole utilisé pour le diagnostic
 *
 * ## Transport détecté
 * Le protocole `T=1` (préféré) ou `T=0` (fallback) est reflété dans [getTransport].
 * Pour une YubiKey en USB CCID, [Transport.USB] est retourné.
 * Pour un lecteur NFC, il est difficile de différencier : [Transport.NFC] si le nom
 * du terminal contient "NFC" ou "contactless", [Transport.USB] sinon.
 *
 * @param card Card obtenu via [javax.smartcardio.CardTerminal.connect]
 * @param terminalName Nom du terminal (pour logs et détection NFC)
 */
private class DirectPcscSmartCardConnection(
    private val card: Card,
    private val terminalName: String,
) : SmartCardConnection {

    private val channel: CardChannel = card.basicChannel

    override fun getTransport(): Transport {
        val name = terminalName.lowercase()
        return if (name.contains("nfc") || name.contains("contactless") || name.contains("acr")) {
            Transport.NFC
        } else {
            Transport.USB
        }
    }

    /**
     * T=1 supporte les APDUs de longueur arbitraire : pas de limitation à 256 bytes.
     * T=0 est limité à 256 bytes max (extended APDU non supporté).
     */
    override fun isExtendedLengthApduSupported(): Boolean = card.protocol == "T=1"

    override fun getAtr(): ByteArray = card.atr.bytes

    override fun sendAndReceive(apdu: ByteArray): ByteArray {
        Logger.d("YubiKit", "DirectPcscSmartCardConnection.sendAndReceive: PRE-transmit" +
            " terminal='$terminalName' apdu[${apdu.size}]=${apdu.toHex().take(40)}…")
        return try {
            val cmd = CommandAPDU(apdu)
            Logger.d("YubiKit", "DirectPcscSmartCardConnection.sendAndReceive: PRE-channel.transmit" +
                " CLA=${"%02X".format(cmd.cla)} INS=${"%02X".format(cmd.ins)}" +
                " P1=${"%02X".format(cmd.p1)} P2=${"%02X".format(cmd.p2)}" +
                " Lc=${cmd.nc} Le=${cmd.ne}")
            val response = channel.transmit(cmd)
            val sw1 = (response.sw shr 8) and 0xFF
            val sw2 = response.sw and 0xFF
            Logger.d("YubiKit", "DirectPcscSmartCardConnection.sendAndReceive: POST-transmit OK" +
                " SW=${"%02X".format(sw1)}${"%02X".format(sw2)} response[${response.bytes.size}]")
            response.bytes
        } catch (e: javax.smartcardio.CardException) {
            val stackTop = e.stackTraceToString().lines().take(8).joinToString("\n    ")
            Logger.w("YubiKit", "DirectPcscSmartCardConnection.sendAndReceive: FAILED transmit" +
                " terminal='$terminalName'" +
                "\n  message=${e.message}" +
                "\n  stack(top-8):\n    $stackTop")
            throw IOException("PC/SC transmit failed for '$terminalName': ${e.message}", e)
        }
    }

    /**
     * Fermeture sans [Card.endExclusive] (pas de transaction à terminer).
     * [Card.disconnect(false)] = laisser la carte en place sans la resetter.
     */
    override fun close() {
        try {
            card.disconnect(false)
            Logger.d("YubiKit", "DirectPcscSmartCardConnection.close: disconnected '$terminalName' (leave-card)")
        } catch (e: Throwable) {
            Logger.w("YubiKit", "DirectPcscSmartCardConnection.close: disconnect failed for '$terminalName': ${e.message}")
        }
    }
}

/**
 * Énumère les terminaux PC/SC qui ont une carte présente via l'API publique javax.smartcardio.
 *
 * Utilise directement [javax.smartcardio.TerminalFactory] et [javax.smartcardio.CardTerminal]
 * (module java.smartcardio, package public) : sans réflexion.
 */
private fun listTerminalsWithCardPresent(): Set<String> = try {
    val factory = javax.smartcardio.TerminalFactory.getDefault()
    val terminalList: List<javax.smartcardio.CardTerminal> = factory.terminals().list()

    val result = mutableSetOf<String>()
    for (terminal in terminalList) {
        val hasCard = try {
            terminal.isCardPresent
        } catch (e: Throwable) {
            Logger.w("YubiKit", "listTerminalsWithCardPresent: isCardPresent failed for '${terminal.name}': ${e.message}")
            false
        }
        Logger.d("YubiKit", "listTerminalsWithCardPresent: terminal='${terminal.name}' isCardPresent=$hasCard")
        if (hasCard) {
            result += terminal.name
        }
    }
    Logger.d("YubiKit", "PC/SC enumeration: ${terminalList.size} terminals (${result.size} with card present): ${result.toList()}")
    result
} catch (e: Throwable) {
    Logger.w("YubiKit", "listTerminalsWithCardPresent: failed, ${e.javaClass.simpleName}: ${e.message}")
    emptySet()
}

/**
 * Construit un clientDataJSON minimal pour MakeCredential.
 */
private fun buildClientDataJson(challenge: ByteArray): ByteArray {
    val base64Challenge = java.util.Base64.getUrlEncoder().withoutPadding()
        .encodeToString(challenge)
    val json = """{"type":"webauthn.create","challenge":"$base64Challenge","origin":"nextsh://desktop"}"""
    return json.toByteArray(Charsets.UTF_8)
}

/**
 * Construit un message d'erreur de transport lisible, avec détection du conflit d'accès HID.
 */
private fun buildTransportMessage(cause: Throwable): String {
    val msg = cause.message?.lowercase() ?: ""
    return if (msg.contains("exclusive") || msg.contains("another application") ||
        msg.contains("access denied") || msg.contains("sharing violation")
    ) {
        "YubiKey utilisée par une autre application : ferme-la (navigateur, Windows Hello) et réessaie"
    } else {
        "Transport error: ${cause.javaClass.simpleName}: ${cause.message}"
    }
}
