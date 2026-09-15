// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.auth

import android.app.Activity
import com.yubico.yubikit.android.YubiKitManager
import com.yubico.yubikit.android.transport.nfc.NfcConfiguration
import com.yubico.yubikit.android.transport.nfc.NfcNotAvailable
import com.yubico.yubikit.android.transport.usb.UsbConfiguration
import com.yubico.yubikit.core.YubiKeyDevice
import com.yubico.yubikit.core.fido.CtapException
import com.yubico.yubikit.core.smartcard.SmartCardConnection
import com.yubico.yubikit.fido.ctap.ClientPin
import com.yubico.yubikit.fido.ctap.Ctap2Session
import com.yubico.yubikit.fido.ctap.PinUvAuthProtocolV1
import com.yubico.yubikit.fido.ctap.PinUvAuthProtocolV2
import com.yubico.yubikit.fido.webauthn.PublicKeyCredentialDescriptor
import com.yubico.yubikit.fido.webauthn.PublicKeyCredentialType
import com.yubico.yubikit.fido.webauthn.SerializationType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.Arrays
import javax.inject.Inject
import javax.inject.Singleton

/**
 * YubiKit-based FIDO2 hardware key authenticator.
 *
 * Handles USB and NFC security key communication for SSH sk-* authentication.
 * The [startListening] method must be called with an Activity to detect USB/NFC devices.
 *
 * Security: all crypto ByteArrays are wiped after use via try/finally.
 */
@Singleton
class YubiKitAuthenticator @Inject constructor() : HardwareKeyAuthenticator {

    private val _connectionState = MutableStateFlow(HwKeyConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<HwKeyConnectionState> = _connectionState

    private var yubiKitManager: YubiKitManager? = null
    private var pendingDevice: CompletableDeferred<YubiKeyDevice>? = null
    private var currentActivity: Activity? = null

    /**
     * Start listening for USB/NFC security key devices.
     * Must be called from an Activity (needed for NFC foreground dispatch).
     */
    fun startListening(activity: Activity) {
        val manager = YubiKitManager(activity)
        yubiKitManager = manager
        currentActivity = activity

        manager.startUsbDiscovery(UsbConfiguration()) { device ->
            Timber.d("FIDO2: USB security key detected")
            _connectionState.value = HwKeyConnectionState.USB_CONNECTED
            pendingDevice?.complete(device)
        }

        try {
            manager.startNfcDiscovery(
                // GetAssertion with PIN requires 3 CTAP2 round-trips over NFC.
                // Default timeout of 1 000 ms is insufficient: raise to 10 s.
                NfcConfiguration().timeout(10_000),
                activity,
            ) { device ->
                Timber.d("FIDO2: NFC security key tapped")
                _connectionState.value = HwKeyConnectionState.NFC_READY
                pendingDevice?.complete(device)
            }
        } catch (e: NfcNotAvailable) {
            Timber.d("FIDO2: NFC not available on this device")
        }
    }

    /** Stop listening for devices. Call in Activity.onPause or when done. */
    fun stopListening() {
        yubiKitManager?.stopUsbDiscovery()
        currentActivity?.let { activity ->
            yubiKitManager?.stopNfcDiscovery(activity)
        }
        _connectionState.value = HwKeyConnectionState.DISCONNECTED
        pendingDevice?.cancel()
        pendingDevice = null
        currentActivity = null
        yubiKitManager = null
    }

    override suspend fun getAssertion(
        rpId: String,
        clientDataHash: ByteArray,
        allowedCredentials: List<ByteArray>,
        requireUserVerification: Boolean,
        pinProvider: (suspend (PinPrompt) -> CharArray?)?,
    ): HwKeyResult = withContext(Dispatchers.IO) {
        try {
            // Wait for a device (USB plug or NFC tap) with 60s timeout
            val deferred = CompletableDeferred<YubiKeyDevice>()
            pendingDevice = deferred

            val device = withTimeoutOrNull(60_000L) { deferred.await() }
                ?: return@withContext HwKeyResult.Error(
                    "Timeout : aucune clé de sécurité détectée en 60 secondes"
                )

            pendingDevice = null

            // Open CTAP2 session
            device.openConnection(SmartCardConnection::class.java).use { connection ->
                val ctap = Ctap2Session(connection)

                // Build allowed credential descriptors
                val credentialDescriptors = allowedCredentials.map { credId ->
                    PublicKeyCredentialDescriptor(
                        PublicKeyCredentialType.PUBLIC_KEY,
                        credId,
                    )
                }

                // Serialize credentials to maps for CTAP2 wire format
                val credentialMaps: List<Map<String, *>>? = credentialDescriptors
                    .map { it.toMap(SerializationType.CBOR) }
                    .ifEmpty { null }

                val options: Map<String, Boolean> = if (requireUserVerification) {
                    mapOf("up" to true, "uv" to true)
                } else {
                    mapOf("up" to true)
                }

                // First attempt: no PIN auth. If the key requires a PIN it will respond with
                // CTAP2_ERR_PIN_REQUIRED (0x36) and we retry with a user-supplied PIN token.
                // The PIN flow uses ClientPin with the highest protocol version supported by the key.
                val assertionData = try {
                    ctap.getAssertions(
                        rpId,
                        clientDataHash,
                        credentialMaps,
                        null, // extensions
                        options,
                        null, // pinUvAuthParam, none on first attempt
                        null, // pinUvAuthProtocol
                        null, // commandState
                    ).firstOrNull()
                } catch (e: CtapException) {
                    when (e.ctapError) {
                        CtapException.ERR_PIN_REQUIRED,
                        CtapException.ERR_PIN_INVALID,
                        CtapException.ERR_PIN_AUTH_INVALID -> {
                            // Key requires PIN: attempt PIN-authenticated assertion
                            return@withContext performPinAssertion(
                                ctap = ctap,
                                rpId = rpId,
                                clientDataHash = clientDataHash,
                                credentialMaps = credentialMaps,
                                options = options,
                                pinProvider = pinProvider,
                                credentialDescriptors = credentialDescriptors,
                                allowedCredentials = allowedCredentials,
                            )
                        }
                        else -> throw e
                    }
                }

                if (assertionData == null) {
                    return@withContext HwKeyResult.Error(
                        "Réponse FIDO2 invalide : aucune assertion retournée"
                    )
                }

                parseAssertionData(assertionData, credentialDescriptors, allowedCredentials)
            }
        } catch (e: com.yubico.yubikit.core.application.CommandException) {
            Timber.d(e, "FIDO2 CTAP error")
            HwKeyResult.Error("Erreur CTAP2 lors de la communication avec la clé de sécurité")
        } catch (e: java.io.IOException) {
            Timber.d(e, "FIDO2 IO error")
            HwKeyResult.Error("Erreur de communication avec la clé de sécurité")
        } catch (e: kotlinx.coroutines.CancellationException) {
            HwKeyResult.UserCancelled
        } catch (e: Exception) {
            Timber.d(e, "FIDO2 unexpected error")
            HwKeyResult.Error("Erreur inattendue lors de l'authentification FIDO2")
        } finally {
            clientDataHash.fill(0)
        }
    }

    /**
     * Retry the getAssertion CTAP2 command with PIN authentication.
     *
     * The CTAP2 PIN flow requires:
     *   1. Selecting a PinUvAuthProtocol (prefer V2 if advertised, fall back to V1).
     *   2. Creating a ClientPin session to exchange an ephemeral ECDH key with the authenticator
     *      and derive a shared secret.
     *   3. Getting a pinToken from the authenticator (bound to the rpId permission scope GA).
     *   4. Using the pinToken to compute pinUvAuthParam = protocol.authenticate(token, clientDataHash).
     *   5. Sending getAssertions again with pinUvAuthParam and protocol version.
     *
     * We loop up to 3 times to handle wrong-PIN retries, re-prompting the user each time
     * with the updated retry count.
     */
    internal suspend fun performPinAssertion(
        ctap: Ctap2Session,
        rpId: String,
        clientDataHash: ByteArray,
        credentialMaps: List<Map<String, *>>?,
        options: Map<String, Boolean>,
        pinProvider: (suspend (PinPrompt) -> CharArray?)?,
        credentialDescriptors: List<PublicKeyCredentialDescriptor>,
        allowedCredentials: List<ByteArray>,
    ): HwKeyResult {
        if (pinProvider == null) {
            // i18n constraint: no Context in the business layer to resolve string resources.
            // fido2_pin_required_no_ui exists in strings.xml for any UI that wants to replace
            // this message with its own localised version downstream.
            return HwKeyResult.Error("Cette clé exige un PIN mais l'interface PIN n'est pas disponible.")
        }

        // Choose the highest PIN/UV protocol version the key supports (prefer V2)
        val protocol = if (ctap.cachedInfo.pinUvAuthProtocols.contains(2)) {
            PinUvAuthProtocolV2()
        } else {
            PinUvAuthProtocolV1()
        }

        repeat(3) { attempt ->
            // Fetch remaining retries before each prompt so the user sees an up-to-date count.
            // On the first attempt we may already be here because of ERR_PIN_INVALID, so
            // always read the retries to give accurate feedback.
            val triesRemaining: Int? = try {
                val clientPinForRetries = ClientPin(ctap, protocol)
                clientPinForRetries.getPinRetries().count
            } catch (_: Exception) {
                null // Non-fatal: show prompt without a count
            }

            Timber.d("FIDO2 PIN prompt, attempt=${attempt + 1}, triesRemaining=$triesRemaining")

            val pin: CharArray = pinProvider(PinPrompt(triesRemaining))
                ?: return HwKeyResult.UserCancelled

            var pinToken: ByteArray? = null
            var pinUvAuthParam: ByteArray? = null
            try {
                val clientPin = ClientPin(ctap, protocol)
                val token = clientPin.getPinToken(
                    pin,
                    ClientPin.PIN_PERMISSION_GA,
                    rpId,
                )
                pinToken = token
                pinUvAuthParam = protocol.authenticate(token, clientDataHash)

                val assertions = ctap.getAssertions(
                    rpId,
                    clientDataHash,
                    credentialMaps,
                    null,
                    options,
                    pinUvAuthParam,
                    protocol.version,
                    null,
                )

                val assertionData = assertions.firstOrNull()
                    ?: return HwKeyResult.Error("Réponse FIDO2 invalide : aucune assertion retournée")

                return parseAssertionData(assertionData, credentialDescriptors, allowedCredentials)
            } catch (e: CtapException) {
                when (e.ctapError) {
                    CtapException.ERR_PIN_BLOCKED -> return HwKeyResult.PinBlocked
                    CtapException.ERR_PIN_INVALID -> { /* loop: wrong PIN, prompt again */ }
                    else -> throw e
                }
            } finally {
                Arrays.fill(pin, '\u0000')
                pinToken?.let { Arrays.fill(it, 0.toByte()) }
                pinUvAuthParam?.let { Arrays.fill(it, 0.toByte()) }
            }
        }

        // Exhausted all retry attempts without success
        return HwKeyResult.Error("Nombre maximal d'essais PIN atteint")
    }

    /**
     * Extract flags, counter, signature and credential ID from a raw AssertionData object
     * and return a [HwKeyResult].
     */
    internal fun parseAssertionData(
        assertionData: Ctap2Session.AssertionData,
        credentialDescriptors: List<PublicKeyCredentialDescriptor>,
        allowedCredentials: List<ByteArray>,
    ): HwKeyResult {
        // authenticatorData format: rpIdHash(32) + flags(1) + counter(4) + ...
        val authData = assertionData.authenticatorData
            ?: return HwKeyResult.Error("Réponse FIDO2 invalide : authenticatorData manquant")

        if (authData.size < 37) {
            return HwKeyResult.Error("authenticatorData trop court")
        }

        val flags = authData[32]
        val counter = ((authData[33].toInt() and 0xFF).toUInt() shl 24) or
            ((authData[34].toInt() and 0xFF).toUInt() shl 16) or
            ((authData[35].toInt() and 0xFF).toUInt() shl 8) or
            (authData[36].toInt() and 0xFF).toUInt()

        // Wipe authData after extracting flags and counter (Issue 9)
        authData.fill(0)

        val signature = assertionData.signature
            ?: return HwKeyResult.Error("Réponse FIDO2 invalide : signature manquante")

        val credentialId = assertionData.getCredentialId(credentialDescriptors)
            ?: allowedCredentials.firstOrNull()
            ?: ByteArray(0)

        return HwKeyResult.Success(
            flags = flags,
            counter = counter,
            signature = signature.copyOf(),
            credentialId = credentialId.copyOf(),
        )
    }
}
