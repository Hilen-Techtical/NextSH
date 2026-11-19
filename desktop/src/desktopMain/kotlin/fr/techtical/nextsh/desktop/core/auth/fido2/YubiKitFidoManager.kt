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
import fr.techtical.nextsh.shared.util.errorClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.security.SecureRandom
import java.util.Arrays
import javax.smartcardio.Card
import javax.smartcardio.CardChannel
import javax.smartcardio.CardTerminal
import javax.smartcardio.CommandAPDU
import javax.smartcardio.TerminalFactory

// ── Log tag. Le Logger Desktop passe chaque ligne (tag compris) par LogScrubber, qui ne
// filtre pas les mots "fido2" ou "ctap" : le tag n'a aucune contrainte de nom.
private const val TAG = "YubiKit"

// ── Règle des journaux de ce fichier ────────────────────────────────────────
// Texte fixe, nom de transport (HID, PC/SC), compteurs, codes CTAP / SW / SCARD et classes
// d'exception uniquement. Jamais : nom de lecteur PC/SC, libellé matériel (HidDevice.toString),
// nom de processus, octets d'APDU ou d'ATR, rpId, libellé de clé, message ou pile d'exception.
// Les noms de lecteur servent à ouvrir la connexion et à l'interface, pas au journal.

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
    /**
     * Enumeration seams. Production uses the defaults (hid4java, the YubiKit PC/SC manager and
     * `javax.smartcardio`); a test replaces them so that it loads no native library and reaches
     * no reader. Each one may throw: the callers log the failure and carry on with no device.
     */
    private val hidDeviceSource: () -> List<HidDevice> = { HidManager().getFidoDevices() },
    private val pcscDeviceSource: () -> List<UsbPcscDevice> = { PcscManager().getDevices() },
    private val terminalSource: () -> List<CardTerminal> = { TerminalFactory.getDefault().terminals().list() },
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
         * Sur Windows, ce chemin PC/SC n'est emprunté que si l'API WebAuthn native n'est pas
         * disponible (voir Fido2ProviderDispatcher). Solution recommandée : lecteur NFC USB.
         */
        object PcscWindowsBug : FidoError() {
            override val message: String =
                "PC/SC access to the YubiKey failed with a known Windows + Java bug " +
                "(SCARD_E_NOT_TRANSACTED / 0x80100027). Use a USB NFC reader."
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
            Logger.d(TAG, "listDevices: found ${result.size} device(s) (HID:${result.count { it.transport == Transport.USB_HID }}, PC/SC:${result.count { it.transport != Transport.USB_HID }})")
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
            Logger.d(TAG, "detectDeviceForEnrollment: using the first of ${hidDevices.size} HID device(s)")
            return@withContext detectViaHid(hidDevices[0])
        }

        Logger.d(TAG, "detectDeviceForEnrollment: no HID device, trying PC/SC fallback")

        // Fallback PC/SC (lecteur NFC ou CCID activé manuellement)
        val terminalsWithCard = listTerminalsWithCardPresent(terminalSource)
        Logger.d(TAG, "detectDeviceForEnrollment: PC/SC terminals with card = ${terminalsWithCard.size}")

        if (terminalsWithCard.isEmpty()) {
            Logger.w(TAG, "detectDeviceForEnrollment: NO DEVICES on any transport (HID:0, PC/SC:0)")
            throw noDeviceOrWindowsHidLocked()
        }

        val pcscDevices = listPcscDevicesRaw(terminalsWithCard)
        Logger.d(TAG, "detectDeviceForEnrollment: PC/SC YubiKey devices = ${pcscDevices.size}")

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
            Logger.d(TAG, "enrollCredential: using the first of ${hidDevices.size} HID device(s)")
            return@withContext enrollViaHid(hidDevices[0], rpId, rpName, userName, userDisplayName, pin, timeoutMs)
        }

        Logger.d(TAG, "enrollCredential: no HID device, trying PC/SC")

        // Fallback PC/SC
        val terminalsWithCard = listTerminalsWithCardPresent(terminalSource)
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
     * @param clientDataHash clientDataHash CTAP2 déjà calculé (32 bytes), voir [Fido2Signer.signChallenge]
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
            Logger.d(TAG, "signChallenge: using the first of ${hidDevices.size} HID device(s)")
            return@withContext signViaHid(hidDevices[0], rpId, clientDataHash, credentialId, requireUv, timeoutMs)
        }

        Logger.d(TAG, "signChallenge: no HID device, trying PC/SC")

        // Fallback PC/SC
        val terminalsWithCard = listTerminalsWithCardPresent(terminalSource)
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
            Logger.d(TAG, "listHidDevicesRaw: enumerating HID FIDO devices")
            val devices = hidDeviceSource()
            val count = devices.size
            if (count == 0) {
                Logger.d(TAG, "listHidDevicesRaw: HID devices found = 0 (no YubiKey HID FIDO interface detected)")
            } else {
                Logger.d(TAG, "listHidDevicesRaw: HID devices found = $count")
            }
            devices
        } catch (e: UnsatisfiedLinkError) {
            Logger.e(TAG, "listHidDevicesRaw: hid4java native lib load FAILED (hidapi.dll extraction error)", e)
            emptyList()
        } catch (e: Throwable) {
            Logger.w(TAG, "listHidDevicesRaw: HID enum failed (${describeFailure(e)})")
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
        Logger.d(TAG, "detectViaHid: opening connection")
        return try {
            // openFidoConnection() retourne HidFidoConnection qui implémente FidoConnection
            val connection = device.openFidoConnection()
            try {
                Logger.d(TAG, "detectViaHid: CTAP2 session opening")
                val session = Ctap2Session(connection)
                val info = session.getCachedInfo()
                // The version and transport lists come from the authenticator: counts and one known flag only.
                Logger.d(TAG, "detectViaHid: CTAP2 session opened: ${describeCtapInfo(info.versions, info.transports)}")
                val client = BasicWebAuthnClient(session)
                val pinConfigured = client.isPinConfigured()
                Logger.d(TAG, "detectViaHid: pinConfigured=$pinConfigured")
                Fido2Enroller.DeviceDetection(deviceLabel = label, pinConfigured = pinConfigured)
            } finally {
                runCatching { connection.close() }
            }
        } catch (e: FidoError) {
            throw e
        } catch (e: IOException) {
            Logger.w(TAG, "detectViaHid: IOException (${describeFailure(e)})")
            throw FidoError.TransportError(e)
        } catch (e: Exception) {
            Logger.w(TAG, "detectViaHid: failed (${describeFailure(e)})")
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
        Logger.d(TAG, "enrollViaHid: opening connection")
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
            Logger.w(TAG, "enrollViaHid: touch timeout")
            throw FidoError.TouchTimeout
        } catch (e: PinInvalidClientError) {
            Logger.w(TAG, "enrollViaHid: PIN invalid")
            throw FidoError.UserCancelled
        } catch (e: PinRequiredClientError) {
            Logger.w(TAG, "enrollViaHid: PIN required but not provided")
            throw FidoError.CtapError(0x36, "PIN required but not provided")
        } catch (e: ClientError) {
            Logger.w(TAG, "enrollViaHid: ClientError code=${e.errorCode}")
            throw FidoError.TransportError(e)
        } catch (e: CtapException) {
            val code = e.getCtapError().toInt() and 0xFF
            Logger.w(TAG, "enrollViaHid: CTAP error 0x${code.toString(16)}")
            throw FidoError.CtapError(code, e.message ?: "CTAP error")
        } catch (e: IOException) {
            Logger.w(TAG, "enrollViaHid: IOException (${describeFailure(e)})")
            throw FidoError.TransportError(e)
        } catch (e: FidoError) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "enrollViaHid: failed (${describeFailure(e)})")
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
        Logger.d(TAG, "signViaHid: opening connection")
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
            Logger.w(TAG, "signViaHid: touch timeout")
            throw FidoError.TouchTimeout
        } catch (e: CtapException) {
            val code = e.getCtapError().toInt() and 0xFF
            Logger.w(TAG, "signViaHid: CTAP error 0x${code.toString(16)}")
            throw FidoError.CtapError(code, e.message ?: "CTAP error")
        } catch (e: IOException) {
            Logger.w(TAG, "signViaHid: IOException (${describeFailure(e)})")
            throw FidoError.TransportError(e)
        } catch (e: FidoError) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "signViaHid: failed (${describeFailure(e)})")
            throw FidoError.TransportError(e)
        } finally {
            onTouchDone()
        }
    }

    // ── PC/SC transport: internal helpers ───────────────────────────────────

    private fun listPcscDevicesRaw(terminalsWithCard: Set<String>): List<UsbPcscDevice> {
        val all = try {
            pcscDeviceSource()
        } catch (e: Exception) {
            if (e.isPcscNoDevice()) {
                Logger.d(TAG, "listPcscDevicesRaw: PC/SC no readers/cards available (${describeFailure(e)})")
            } else {
                Logger.w(TAG, "listPcscDevicesRaw: PC/SC enum failed (${describeFailure(e)})")
            }
            return emptyList()
        }
        Logger.d(TAG, "listPcscDevicesRaw: PC/SC raw devices found = ${all.size}")
        val filtered = if (terminalsWithCard.isNotEmpty())
            all.filter { it.getName() in terminalsWithCard }
        else
            all
        Logger.d(TAG, "listPcscDevicesRaw: after isCardPresent filter: ${filtered.size} device(s)")
        return filtered
    }

    private fun listPcscDevices(): List<DeviceInfo> {
        val terminalsWithCard = listTerminalsWithCardPresent(terminalSource)
        Logger.d(TAG, "listPcscDevices: PC/SC terminals with card = ${terminalsWithCard.size}")
        return listPcscDevicesRaw(terminalsWithCard).map { device ->
            DeviceInfo(label = device.getName(), transport = Transport.NFC)
        }
    }

    private fun detectViaPcsc(device: UsbPcscDevice): Fido2Enroller.DeviceDetection {
        val label = device.getName()
        Logger.d(TAG, "detectViaPcsc: opening direct T=1 connection (bypass PcscManager beginExclusive)")
        return retryOnNotTransacted(label = label) {
            val connection = openDirectPcscConnection(label)
            try {
                Logger.d(TAG, "detectViaPcsc: opening CTAP2 session")
                val session = Ctap2Session(connection)
                Logger.d(TAG, "detectViaPcsc: CTAP2 session opened")
                val info = session.getCachedInfo()
                Logger.d(TAG, "detectViaPcsc: CTAP2 info: ${describeCtapInfo(info.versions, info.transports)}")
                val client = BasicWebAuthnClient(session)
                val pinConfigured = client.isPinConfigured()
                Logger.d(TAG, "detectViaPcsc: pinConfigured=$pinConfigured")
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
        Logger.d(TAG, "enrollViaPcsc: opening direct T=1 connection (bypass PcscManager beginExclusive)")
        onTouchRequired(deviceLabel)
        try {
            return withTimeout(timeoutMs) {
                retryOnNotTransacted(label = deviceLabel) {
                    val connection = openDirectPcscConnection(deviceLabel)
                    try {
                        Logger.d(TAG, "enrollViaPcsc: opening CTAP2 session")
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
            Logger.w(TAG, "enrollViaPcsc: touch timeout")
            throw FidoError.TouchTimeout
        } catch (e: PinInvalidClientError) {
            Logger.w(TAG, "enrollViaPcsc: PIN invalid")
            throw FidoError.UserCancelled
        } catch (e: PinRequiredClientError) {
            Logger.w(TAG, "enrollViaPcsc: PIN required but not provided")
            throw FidoError.CtapError(0x36, "PIN required but not provided")
        } catch (e: ClientError) {
            Logger.w(TAG, "enrollViaPcsc: ClientError code=${e.errorCode}")
            throw FidoError.TransportError(e)
        } catch (e: CtapException) {
            val code = e.getCtapError().toInt() and 0xFF
            Logger.w(TAG, "enrollViaPcsc: CTAP error 0x${code.toString(16)}")
            throw FidoError.CtapError(code, e.message ?: "CTAP error")
        } catch (e: IOException) {
            Logger.w(TAG, "enrollViaPcsc: IOException (${describeFailure(e)})")
            throw FidoError.TransportError(e)
        } catch (e: FidoError) {
            throw e
        } catch (e: Exception) {
            if (e.isPcscNoDevice()) throw FidoError.NoDeviceFound
            Logger.w(TAG, "enrollViaPcsc: failed (${describeFailure(e)})")
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
        Logger.d(TAG, "signViaPcsc: opening direct T=1 connection (bypass PcscManager beginExclusive)")
        onTouchRequired(deviceLabel)
        try {
            return withTimeout(timeoutMs) {
                retryOnNotTransacted(label = deviceLabel) {
                    val connection = openDirectPcscConnection(deviceLabel)
                    try {
                        Logger.d(TAG, "signViaPcsc: opening CTAP2 session")
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
            Logger.w(TAG, "signViaPcsc: touch timeout")
            throw FidoError.TouchTimeout
        } catch (e: CtapException) {
            val code = e.getCtapError().toInt() and 0xFF
            Logger.w(TAG, "signViaPcsc: CTAP error 0x${code.toString(16)}")
            throw FidoError.CtapError(code, e.message ?: "CTAP error")
        } catch (e: IOException) {
            Logger.w(TAG, "signViaPcsc: IOException (${describeFailure(e)})")
            throw FidoError.TransportError(e)
        } catch (e: FidoError) {
            throw e
        } catch (e: Exception) {
            if (e.isPcscNoDevice()) throw FidoError.NoDeviceFound
            Logger.w(TAG, "signViaPcsc: failed (${describeFailure(e)})")
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

            // tasklist CSV: "name.exe","PID","Session","Num","Mem"
            // Process names are never logged (they show what the user runs): only how many.
            val suspectsRunning = suspectProcesses.count { output.contains(it, ignoreCase = true) }
            if (suspectsRunning > 0) {
                Logger.w(
                    TAG,
                    "warnIfYubicoProcessRunning: WARNING: $suspectsRunning Yubico related process(es) running." +
                        " They may hold an exclusive CCID lock on the YubiKey." +
                        " Close them before retrying FIDO2 enrollment."
                )
            }

            // Navigateurs courants : vérification séparée car moins critique
            val browsers = listOf("chrome.exe", "firefox.exe", "msedge.exe", "opera.exe", "brave.exe")
            val browsersRunning = browsers.count { output.contains(it, ignoreCase = true) }
            if (browsersRunning > 0) {
                Logger.w(
                    TAG,
                    "warnIfYubicoProcessRunning: $browsersRunning browser(s) running" +
                        ": if WebAuthn is active in-browser, it may hold the CCID interface."
                )
            }
        } catch (e: Throwable) {
            Logger.d(TAG, "warnIfYubicoProcessRunning: tasklist check failed (non-fatal, ${errorClass(e)})")
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
        Logger.d(TAG, "PC/SC direct connect: looking up the requested terminal")

        // ── Étape 1 : Détection de processus concurrents ──────────────────────
        warnIfYubicoProcessRunning()

        val factory = TerminalFactory.getDefault()
        Logger.d(TAG, "PC/SC direct connect: TerminalFactory.getDefault() obtained")

        val terminals = try {
            factory.terminals().list()
        } catch (e: javax.smartcardio.CardException) {
            Logger.w(TAG, "PC/SC direct connect: terminal enumeration failed (${describeFailure(e)})")
            throw IOException("PC/SC terminal enumeration failed: ${e.message}", e)
        }
        Logger.d(TAG, "PC/SC direct connect: ${terminals.size} terminal(s) visible")

        val terminal = terminals.firstOrNull { it.name == terminalName }
            ?: throw IOException("PC/SC terminal '$terminalName' not found in ${terminals.map { it.name }}")

        // ── Étape 2 : Cascade protocoles T=1 → T=0 → * ───────────────────────
        // Chaque échec est loggué avec sa classe d'exception et son code SCARD (jamais le message ni la pile).
        val protocols = listOf("T=1", "T=0", "*")
        var card: javax.smartcardio.Card? = null
        var lastConnectException: Throwable? = null

        for (proto in protocols) {
            Logger.d(TAG, "PC/SC direct connect: PRE-connect protocol='$proto'")
            try {
                card = terminal.connect(proto)
                // POST-connect : log immédiat pour confirmer la ligne de succès.
                // L'ATR est un identifiant de carte : seule sa longueur est journalisée.
                Logger.d(TAG, "PC/SC direct connect: POST-connect SUCCESS: protocol='$proto' " +
                    "atrLength=${card.atr.bytes.size} cardProtocol='${card.protocol}'")
                break
            } catch (e: Throwable) {
                Logger.w(TAG, "PC/SC direct connect: protocol '$proto' FAILED (${describeFailure(e)})")
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
                Logger.w(
                    TAG,
                    "retryOnNotTransacted: SCARD_E_NOT_TRANSACTED (0x80100027)" +
                        ": attempt $attemptDisplay/$maxRetries (${errorClass(e)})"
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
        Logger.w(TAG, "retryOnNotTransacted: all $maxRetries retries exhausted, raising PcscWindowsBug")
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
            Logger.d(TAG, "tryWarmResetTerminal: PRE-connect(T=1) for warm reset")
            val card = terminal.connect("T=1")
            Logger.d(TAG, "tryWarmResetTerminal: PRE-disconnect(reset=true)")
            card.disconnect(true)
            Logger.d(TAG, "tryWarmResetTerminal: POST warm reset OK")
        } catch (e: Throwable) {
            Logger.w(TAG, "tryWarmResetTerminal: reset failed (non-fatal, ${describeFailure(e)})")
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
        Logger.d(TAG, "doEnrollCredential: opening BasicWebAuthnClient")
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

        Logger.d(TAG, "doEnrollCredential: calling makeCredential (waiting for touch...)")

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

        Logger.d(TAG, "doEnrollCredential: makeCredential completed")

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

        Logger.d(TAG, "doEnrollCredential: credential created successfully, credentialId.size=${credentialId.size}")

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

/** Plage des codes d'état PC/SC Windows (SCARD_*) : seul un code de cette plage est journalisé. */
private val SCARD_CODE_RANGE: LongRange = 0x80100000L..0x8010FFFFL

/** Nom symbolique d'un statut PC/SC (`SCARD_E_NOT_TRANSACTED`) : un vocabulaire fixe de Windows. */
private val SCARD_NAME_RE = Regex("""SCARD_[EWFS]_[A-Z0-9_]{3,40}""")

/** Nombre maximal de causes parcourues pour chercher un statut SCARD. */
private const val SCARD_MAX_CAUSE_DEPTH = 8

/**
 * Tous les statuts SCARD trouvés dans le message de [t] et de ses causes : codes numériques
 * 0x8010xxxx (reformatés ici, sur 8 chiffres) et noms symboliques `SCARD_*`, sans doublon,
 * dans l'ordre de découverte. Le message lui-même n'est jamais journalisé : seuls ces codes
 * et ces noms, qui forment un vocabulaire fixe, le sont.
 */
internal fun scardStatuses(t: Throwable): List<String> {
    val found = LinkedHashSet<String>()
    var current: Throwable? = t
    var depth = 0
    while (current != null && depth < SCARD_MAX_CAUSE_DEPTH) {
        val message = current.message ?: ""
        for (match in PCSC_HEX_RE.findAll(message)) {
            val code = match.groupValues[1].toLongOrNull(16)
            if (code != null && code in SCARD_CODE_RANGE) found += "0x%08X".format(code)
        }
        for (match in SCARD_NAME_RE.findAll(message)) found += match.value
        current = current.cause
        depth++
    }
    return found.toList()
}

/**
 * Description d'un échec pour le journal : chaîne de classes de l'exception, plus les statuts
 * SCARD (codes et noms) quand le message en porte. Jamais le message, la pile ni un nom de lecteur.
 */
internal fun describeFailure(t: Throwable): String {
    val statuses = scardStatuses(t)
    if (statuses.isEmpty()) return errorClass(t)
    return "${errorClass(t)}, scard=${statuses.joinToString(" ")}"
}

/**
 * Résumé de l'identité CTAP2 d'un authenticator pour le journal. Les listes de versions et de
 * transports viennent de la clé : seuls leurs effectifs et un drapeau connu sont journalisés.
 */
internal fun describeCtapInfo(versions: Collection<*>?, transports: Collection<*>?): String =
    "versionCount=${versions?.size ?: 0} transportCount=${transports?.size ?: 0} " +
        "fido21=${versions?.contains("FIDO_2_1") == true}"

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
 * - Journalise le protocole utilisé et la longueur de l'ATR (jamais ses octets)
 *
 * ## Transport détecté
 * Le protocole `T=1` (préféré) ou `T=0` (fallback) est reflété dans [getTransport].
 * Pour une YubiKey en USB CCID, [Transport.USB] est retourné.
 * Pour un lecteur NFC, il est difficile de différencier : [Transport.NFC] si le nom
 * du terminal contient "NFC" ou "contactless", [Transport.USB] sinon.
 *
 * @param card Card obtenu via [javax.smartcardio.CardTerminal.connect]
 * @param terminalName Nom du terminal (détection NFC et message d'exception ; jamais journalisé)
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
        // The APDU bytes (a CTAP command, possibly carrying a PIN protocol payload) are never
        // logged, only the length, the instruction byte and the status word of the reply.
        Logger.d("YubiKit", "DirectPcscSmartCardConnection.sendAndReceive: PRE-transmit apduLength=${apdu.size}")
        return try {
            val cmd = CommandAPDU(apdu)
            Logger.d("YubiKit", "DirectPcscSmartCardConnection.sendAndReceive: PRE-channel.transmit" +
                " INS=${"%02X".format(cmd.ins)} Lc=${cmd.nc} Le=${cmd.ne}")
            val response = channel.transmit(cmd)
            val sw1 = (response.sw shr 8) and 0xFF
            val sw2 = response.sw and 0xFF
            Logger.d("YubiKit", "DirectPcscSmartCardConnection.sendAndReceive: POST-transmit OK" +
                " SW=${"%02X".format(sw1)}${"%02X".format(sw2)} responseLength=${response.bytes.size}")
            response.bytes
        } catch (e: javax.smartcardio.CardException) {
            Logger.w("YubiKit", "DirectPcscSmartCardConnection.sendAndReceive: FAILED transmit (${describeFailure(e)})")
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
            Logger.d("YubiKit", "DirectPcscSmartCardConnection.close: disconnected (leave-card)")
        } catch (e: Throwable) {
            Logger.w("YubiKit", "DirectPcscSmartCardConnection.close: disconnect failed (${describeFailure(e)})")
        }
    }
}

/**
 * Énumère les terminaux PC/SC qui ont une carte présente via l'API publique javax.smartcardio.
 *
 * Utilise directement [javax.smartcardio.TerminalFactory] et [javax.smartcardio.CardTerminal]
 * (module java.smartcardio, package public) : sans réflexion.
 */
private fun listTerminalsWithCardPresent(terminals: () -> List<CardTerminal>): Set<String> = try {
    val terminalList: List<CardTerminal> = terminals()

    val result = mutableSetOf<String>()
    // Terminals are named by ordinal in the log (PC/SC#0): the reader name is hardware the user owns.
    for ((index, terminal) in terminalList.withIndex()) {
        val hasCard = try {
            terminal.isCardPresent
        } catch (e: Throwable) {
            Logger.w("YubiKit", "listTerminalsWithCardPresent: isCardPresent failed for PC/SC#$index (${describeFailure(e)})")
            false
        }
        Logger.d("YubiKit", "listTerminalsWithCardPresent: PC/SC#$index isCardPresent=$hasCard")
        if (hasCard) {
            result += terminal.name
        }
    }
    Logger.d("YubiKit", "PC/SC enumeration: ${terminalList.size} terminals (${result.size} with card present)")
    result
} catch (e: Throwable) {
    Logger.w("YubiKit", "listTerminalsWithCardPresent: failed (${describeFailure(e)})")
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
        "YubiKey in use by another application (browser, Windows Hello): close it and try again"
    } else {
        "Transport error: ${cause.javaClass.simpleName}: ${cause.message}"
    }
}
