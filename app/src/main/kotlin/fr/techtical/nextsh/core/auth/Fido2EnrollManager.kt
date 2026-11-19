// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.auth

import com.yubico.yubikit.core.application.ApplicationNotAvailableException
import com.yubico.yubikit.core.application.CommandState
import com.yubico.yubikit.core.fido.CtapException
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.IOException
import java.security.SecureRandom
import java.util.Arrays
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Why a FIDO2 enrollment failed. A reason, not a sentence: this layer has no [android.content.Context]
 * and so no language, the screen turns the reason into text (see `enrollFailureMessageRes`).
 * The same split as [HwKeyError], for the sign-in with a key.
 */
sealed interface EnrollFailure {
    /** No key was plugged in or tapped before the wait ran out. */
    data object NoKeyInTime : EnrollFailure

    /** The key is protected by a PIN and none was given. */
    data object PinRequired : EnrollFailure

    /** The PIN was refused, [triesRemaining] is null when the key did not say. */
    data class PinIncorrect(val triesRemaining: Int?) : EnrollFailure

    /** A PIN was typed but the key has none set: there is nothing for it to check the PIN against. */
    data object PinNotSet : EnrollFailure

    /** The PIN is locked for good: only a reset of the key unlocks it. */
    data object PinBlocked : EnrollFailure

    /** Too many wrong PINs in a row: the key must be unplugged and plugged back. */
    data object PinAuthBlocked : EnrollFailure

    /** Not a key an SSH key can be made on (no FIDO2 application, unsupported algorithm or option). */
    data object UnsupportedKey : EnrollFailure

    /** The key went away or the link broke (USB unplugged, NFC tag lost). */
    data object KeyRemoved : EnrollFailure

    /** The key gave up waiting for the touch on its own. */
    data object TouchTimeout : EnrollFailure

    /** The key refused the operation (CTAP2_ERR_OPERATION_DENIED, CTAP2_ERR_UP_REQUIRED). */
    data object Refused : EnrollFailure

    /** The form gave no name to the key. Raised by the ViewModel before any key is involved. */
    data object NameMissing : EnrollFailure

    /** The key made the credential but the vault could not keep it. Raised by the ViewModel. */
    data object NotSaved : EnrollFailure

    /** Anything else, including a malformed answer from the key. */
    data object Unexpected : EnrollFailure
}

/**
 * FIDO2 enrollment with a YubiKey (USB/NFC).
 *
 * Expected flow (NFC-safe):
 *   1. Collect the optional PIN in the form (before any connection to the key).
 *   2. Call [detectAndEnroll]: it waits for a key, then detects and enrolls in a single
 *      connection (NFC-safe: the tag stays held for the whole CTAP round trip).
 *
 * Keys are looked for by [SecurityKeyDiscovery], shared with the SSH sign-in request: two YubiKit
 * managers would fight over the same USB and NFC events. The lease on the discovery belongs to the
 * operation, not to the screen: it is taken by [detectAndEnroll] for the wait and the operation
 * only, and given back whatever the outcome. Nothing is watched (and the NFC reader is not on)
 * while the screen is idle, done, or showing an error, and two screens on top of each other cannot
 * take the lease from one another.
 *
 * The wait for the touch on the key stops with the operation: cancelling it tells the key to stop
 * waiting (CTAPHID_CANCEL over USB), so that the key is free again at once and does not keep
 * blinking until its own timeout.
 *
 * Security: the PIN is zeroed after use, whatever the outcome.
 */
@Singleton
class Fido2EnrollManager internal constructor(
    private val discovery: SecurityKeyDiscovery,
    private val ioDispatcher: CoroutineDispatcher,
    private val keyWaitMs: Long,
    // Seams for the tests, which have no key: the CTAP2 session and the WebAuthn client on it.
    private val connector: Ctap2Connector = YubiKitCtap2Connector(),
    private val newClient: (Ctap2Session) -> BasicWebAuthnClient = ::BasicWebAuthnClient,
) {

    @Inject
    constructor(discovery: SecurityKeyDiscovery) : this(discovery, Dispatchers.IO, KEY_WAIT_MS)

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

    /**
     * How [detectAndEnroll] fails (the cancellation aside, which goes on its way as such): with the
     * [failure] that tells why. It carries no sentence, the message is for the logs and fixed.
     */
    class EnrollError(val failure: EnrollFailure) : Exception("FIDO2 enrollment failed: $failure")

    // ── detectAndEnroll ───────────────────────────────────────────────────────

    /**
     * Détecte un device puis enrôle en une seule connexion (NFC-safe).
     *
     * Sur USB : attend le device → ouvre FidoConnection → enrôle.
     * Sur NFC : attend le tap → ouvre SmartCardConnection → enrôle dans la même connexion
     *           (tag tenu pendant tout le round-trip CTAP, évitant "Tag was lost").
     *
     * Le PIN est passé en argument depuis le formulaire (peut être null si la clé n'en a pas).
     * Si la clé exige un PIN et qu'aucun n'est fourni → [EnrollFailure.PinRequired].
     * Si le PIN est incorrect → [EnrollFailure.PinIncorrect].
     * Si un PIN est fourni alors que la clé n'en a pas → [EnrollFailure.PinNotSet], avant tout toucher.
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
    ): EnrollResult {
        try {
            return withContext(ioDispatcher) {
                // The lease on the discovery is this operation's: taken here, for the wait and the
                // operation only, and given back in every case. Taken by the screen, it was held
                // through the idle, done and error states (the NFC reader kept running), and a
                // second screen pushed on top of the first took it from it.
                val lease = discovery.acquire()
                try {
                    enrollOnKey(label, rpId, rpName, pin)
                } finally {
                    lease.close()
                }
            }
        } finally {
            // Zeroed on every way out, the wait for a key that never comes and a cancellation
            // included. Outside the block: a cancellation that lands before the block starts
            // skips it altogether, and the PIN would stay in memory.
            pin?.let { Arrays.fill(it, '\u0000') }
        }
    }

    /** Waits for a key, claims it, and enrolls on it: the part of [detectAndEnroll] that holds a lease. */
    private suspend fun enrollOnKey(
        label: String,
        rpId: String,
        rpName: String,
        pin: CharArray?,
    ): EnrollResult {
        // A USB key already plugged in is handed over at once by the discovery; otherwise we wait
        // for the next plug or NFC tap. The delay only counts this wait.
        // The key is this enrollment's alone until the claim is closed: a sign-in request waiting
        // for the same key goes first, and does not share it. The claim is read from a variable,
        // not from the result of the timeout: a claim granted at the last moment must not be lost
        // if the timeout fires right after.
        var claimed: KeyClaim? = null
        try {
            withTimeoutOrNull(keyWaitMs) { claimed = discovery.claimKey(KeyUse.ENROLLMENT) }
        } catch (e: Throwable) {
            // A cancellation that lands once the claim is granted makes the timeout block throw even
            // though it was filled: the claim is not the caller's yet, so nothing else would close
            // it, and the key would stay taken until the process restarts.
            claimed?.close()
            throw e
        }
        val claim = claimed ?: throw EnrollError(EnrollFailure.NoKeyInTime)
        try {
            val key = claim.key
            val transport = when (key.transport) {
                KeyTransport.USB -> Transport.USB
                KeyTransport.NFC -> Transport.NFC
            }

            Timber.i("Fido2Enroll: device received transport=$transport: opening session for detectAndEnroll")

            // The wait for the touch is a blocking read that a cancellation of the coroutine cannot
            // interrupt: the state is what tells the key to stop waiting when the caller leaves. The
            // connection is opened, used and closed inside the call, which is waited for.
            val state = CommandState()
            return try {
                blockingKeyCall(ioDispatcher, state) {
                    connector.connect(key).use { link ->
                        doEnroll(
                            ctap = link.session,
                            label = label,
                            rpId = rpId,
                            rpName = rpName,
                            pin = pin,
                            transport = transport,
                            state = state,
                        )
                    }
                }
            } catch (e: CancellationException) {
                // The cancellation goes on its way, it is not a failure of the key. First of the
                // list: it is an exception like the others, and the last branch takes them all.
                throw e
            } catch (e: EnrollError) {
                throw e
            } catch (e: Exception) {
                throw EnrollError(enrollFailureOf(e))
            }
        } finally {
            // After the connection is closed (the `use` on the link): the key is then free for the
            // next request.
            claim.close()
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
        state: CommandState,
    ): EnrollResult {
        val client = newClient(ctap)

        // An empty array is no PIN: the client would take it for one.
        val givenPin = pin?.takeIf { it.isNotEmpty() }
        // A PIN typed for a key that has none is refused by the client before it asks anything of the
        // key (a BAD_REQUEST with no CTAP error to read): said here, before the wait for the touch.
        if (givenPin != null && !client.isPinConfigured) {
            Timber.w("Fido2Enroll: a PIN was given, the key has none")
            throw EnrollError(EnrollFailure.PinNotSet)
        }

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
                /* userVerification */ if (givenPin != null)
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
            givenPin,
            null, // enterpriseAttestation
            state,
        )

        Timber.d("Fido2Enroll: makeCredential completed")

        val attestationResponse = credential.response as? AuthenticatorAttestationResponse
            ?: throw malformedAnswer("unexpected type of response")

        val attestedCredData = attestationResponse.getAuthenticatorData()
            ?.getAttestedCredentialData()
            ?: throw malformedAnswer("no attested credential data")

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
        // Only the length of the credential id is logged, never the id itself.
        val idLength = credentialId.size

        return when (alg) {
            -7 -> {
                // ECDSA P-256 : clés COSE -2 (x, 32B) et -3 (y, 32B)
                val xBytes = coseKey[java.lang.Integer.valueOf(-2)] as? ByteArray
                    ?: throw malformedAnswer("COSE P-256 key without x")
                val yBytes = coseKey[java.lang.Integer.valueOf(-3)] as? ByteArray
                    ?: throw malformedAnswer("COSE P-256 key without y")
                if (xBytes.size != 32 || yBytes.size != 32) {
                    // Sizes only, never the coordinates.
                    throw malformedAnswer("COSE P-256 key of wrong size, x=${xBytes.size} y=${yBytes.size}")
                }
                // Construit le point SEC1 uncompressed : 0x04 || x || y (65 bytes)
                val sec1Key = ByteArray(65).apply {
                    this[0] = 0x04
                    xBytes.copyInto(this, 1)
                    yBytes.copyInto(this, 33)
                }
                Timber.i("Fido2Enroll: enrolled SK_ECDSA_256, credential id of $idLength bytes")
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
                    ?: throw malformedAnswer("COSE Ed25519 key without x, alg=$alg")
                if (ed25519Key.size != 32) {
                    throw malformedAnswer("COSE Ed25519 key of wrong size, x=${ed25519Key.size}")
                }
                Timber.i("Fido2Enroll: enrolled SK_ED25519, credential id of $idLength bytes")
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

    /**
     * The failure of an answer of the key that is not what a credential looks like. [what] is a
     * fixed text for the log: sizes and algorithm numbers at most, never the key material.
     */
    private fun malformedAnswer(what: String): EnrollError {
        Timber.e("Fido2Enroll: malformed answer from the key: %s", what)
        return EnrollError(EnrollFailure.Unexpected)
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

    private companion object {
        /** How long the wait for a key lasts, in milliseconds. */
        const val KEY_WAIT_MS = 60_000L
    }
}

/**
 * The reason an enrollment on a key failed with [e]. Logs a fixed text (codes and kinds), never
 * the failure's message.
 *
 * YubiKit's WebAuthn client wraps the CTAP error of a key in a [ClientError] (the PIN ones in a
 * [PinInvalidClientError] or a [PinRequiredClientError]): the CTAP error is read from its cause.
 */
internal fun enrollFailureOf(e: Throwable): EnrollFailure {
    val failure = when (e) {
        is PinRequiredClientError -> EnrollFailure.PinRequired
        // A key that has no try left is blocked: asking again would only say "incorrect PIN".
        is PinInvalidClientError ->
            if (e.pinRetries <= 0) EnrollFailure.PinBlocked else EnrollFailure.PinIncorrect(e.pinRetries)
        is ClientError -> {
            val ctap = e.cause as? CtapException
            val ofCtap = ctap?.let(::failureOfCtap)
            when {
                // The CTAP error is what the key said: it goes first.
                ofCtap != null && ofCtap != EnrollFailure.Unexpected -> ofCtap
                // YubiKit files a full key (KEY_STORE_FULL) under CONFIGURATION_UNSUPPORTED, but a key
                // that is full is not a key that cannot do SSH: it stays an unexpected failure.
                ctap?.ctapError == CtapException.ERR_KEY_STORE_FULL -> EnrollFailure.Unexpected
                // No CTAP error to read, or one this table does not know (ERR_TIMEOUT, wrapped as a
                // TIMEOUT): the code the client sorted it under says as much as it did without a cause.
                else -> failureOfClientCode(e.errorCode)
            }
        }
        is CtapException -> failureOfCtap(e)
        is ApplicationNotAvailableException -> EnrollFailure.UnsupportedKey
        is IOException -> EnrollFailure.KeyRemoved
        else -> EnrollFailure.Unexpected
    }
    Timber.w("Fido2Enroll: failed on %s, reason %s", describeForLog(e), failure.javaClass.simpleName)
    return failure
}

/** The reason behind a CTAP error code the key answered with. */
private fun failureOfCtap(e: CtapException): EnrollFailure = when (e.ctapError) {
    CtapException.ERR_PIN_REQUIRED -> EnrollFailure.PinRequired
    CtapException.ERR_PIN_INVALID -> EnrollFailure.PinIncorrect(triesRemaining = null)
    CtapException.ERR_PIN_NOT_SET -> EnrollFailure.PinNotSet
    CtapException.ERR_PIN_BLOCKED -> EnrollFailure.PinBlocked
    CtapException.ERR_PIN_AUTH_BLOCKED -> EnrollFailure.PinAuthBlocked
    CtapException.ERR_USER_ACTION_TIMEOUT,
    CtapException.ERR_ACTION_TIMEOUT -> EnrollFailure.TouchTimeout
    CtapException.ERR_OPERATION_DENIED,
    CtapException.ERR_UP_REQUIRED -> EnrollFailure.Refused
    CtapException.ERR_UNSUPPORTED_ALGORITHM,
    CtapException.ERR_UNSUPPORTED_EXTENSION,
    CtapException.ERR_UNSUPPORTED_OPTION,
    CtapException.ERR_INVALID_OPTION -> EnrollFailure.UnsupportedKey
    else -> EnrollFailure.Unexpected
}

/** The reason behind the code a [ClientError] of YubiKit's WebAuthn client carries. */
private fun failureOfClientCode(code: ClientError.Code): EnrollFailure = when (code) {
    ClientError.Code.TIMEOUT -> EnrollFailure.TouchTimeout
    ClientError.Code.CONFIGURATION_UNSUPPORTED,
    ClientError.Code.DEVICE_INELIGIBLE -> EnrollFailure.UnsupportedKey
    else -> EnrollFailure.Unexpected
}

/** What the log says of a failure: its kind and its codes, never its message. */
private fun describeForLog(e: Throwable): String = when (e) {
    is CtapException -> "CTAP error 0x%02X".format(e.ctapError)
    is ClientError -> {
        val ctap = e.cause as? CtapException
        "client error ${e.errorCode.name}" + if (ctap != null) " (${describeForLog(ctap)})" else ""
    }
    else -> e.javaClass.simpleName
}
