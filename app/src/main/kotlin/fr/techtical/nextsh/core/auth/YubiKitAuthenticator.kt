// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.auth

import com.yubico.yubikit.core.application.ApplicationNotAvailableException
import com.yubico.yubikit.core.application.CommandState
import com.yubico.yubikit.core.fido.CtapException
import com.yubico.yubikit.fido.ctap.ClientPin
import com.yubico.yubikit.fido.ctap.Ctap2Session
import com.yubico.yubikit.fido.ctap.PinUvAuthProtocol
import com.yubico.yubikit.fido.ctap.PinUvAuthProtocolV1
import com.yubico.yubikit.fido.ctap.PinUvAuthProtocolV2
import com.yubico.yubikit.fido.webauthn.PublicKeyCredentialDescriptor
import com.yubico.yubikit.fido.webauthn.PublicKeyCredentialType
import com.yubico.yubikit.fido.webauthn.SerializationType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException
import java.util.Arrays
import javax.inject.Inject
import javax.inject.Singleton

/**
 * YubiKit-based FIDO2 hardware key authenticator.
 *
 * Handles USB and NFC security key communication for SSH sk-* authentication. Keys come from
 * [SecurityKeyDiscovery], which this holds a lease on for the whole exchange (waiting and talking).
 * There is no deadline here: the FIDO2 prompt cancels the coroutine when its own passes. The only
 * clock is [nanoTime], a monotonic one that tells a key that stopped waiting for the touch from a
 * key that answers so at once, over and over (see [TouchTimeoutGuard]).
 *
 * Security: all crypto ByteArrays are wiped after use via try/finally.
 */
@Singleton
class YubiKitAuthenticator internal constructor(
    private val discovery: SecurityKeyDiscovery,
    private val connector: Ctap2Connector,
    private val ioDispatcher: CoroutineDispatcher,
    private val nanoTime: () -> Long = System::nanoTime,
) : HardwareKeyAuthenticator {

    @Inject
    constructor(discovery: SecurityKeyDiscovery) : this(discovery, YubiKitCtap2Connector(), Dispatchers.IO)

    override suspend fun getAssertion(
        rpId: String,
        clientDataHash: ByteArray,
        allowedCredentials: List<ByteArray>,
        requireUserVerification: Boolean,
        onStatus: (HwKeyStatus) -> Unit,
        pinProvider: (suspend (PinPrompt) -> CharArray?)?,
    ): HwKeyResult {
        val lease = discovery.acquire()
        try {
            return Exchange(rpId, clientDataHash, allowedCredentials, requireUserVerification, onStatus, pinProvider)
                .run()
        } catch (e: CancellationException) {
            // Whoever cancelled (the prompt reached its deadline, the user left) is not told that
            // the key failed or that the user cancelled: the cancellation goes on its way.
            throw e
        } catch (e: Exception) {
            // A failure that comes with our own cancellation is the end of that cancellation (the
            // key answers a cancelled command with an error, a link goes down with the request):
            // it is never a result.
            currentCoroutineContext().ensureActive()
            return HwKeyResult.Error(reasonOf(e))
        } finally {
            clientDataHash.fill(0)
            lease.close()
        }
    }

    /** The reason a failure of the exchange is told to the user. Logs a fixed text, never the failure's message. */
    private fun reasonOf(e: Exception): HwKeyError = when (e) {
        is CtapException -> {
            Timber.d("FIDO2 CTAP error, code=%d", e.ctapError.toInt())
            errorFor(e)
        }
        is ApplicationNotAvailableException -> {
            Timber.d("FIDO2 application not available on this key")
            HwKeyError.UnsupportedKey
        }
        is IOException -> {
            Timber.d("FIDO2 link to the key lost")
            HwKeyError.KeyRemoved
        }
        else -> {
            Timber.d("FIDO2 unexpected error of type %s", e.javaClass.simpleName)
            HwKeyError.Unexpected
        }
    }

    /**
     * Tells a key that gave up waiting for the touch from one that answers "timeout" at once.
     *
     * A key that really waited has waited for tens of seconds, and asking again is right: the touch
     * has not come yet, and the prompt on screen still counts. A key that answers that within
     * moments, turn after turn, is not waiting for anything, and asking again would only spin (a
     * transaction and a log line per turn) until the deadline of the prompt. After
     * [MAX_QUICK_TIMEOUTS] such answers in a row the timeout is given back as the error it is.
     */
    private class TouchTimeoutGuard(private val nanoTime: () -> Long) {
        private var quickInARow = 0
        private var sentAt = 0L

        /** Call right before the request goes to the key. */
        fun requestSent() {
            sentAt = nanoTime()
        }

        /** Call when the key says it stopped waiting for the touch: true when asking again is pointless. */
        fun shouldGiveUp(): Boolean {
            val waited = nanoTime() - sentAt
            quickInARow = if (waited < QUICK_TIMEOUT_NANOS) quickInARow + 1 else 0
            return quickInARow >= MAX_QUICK_TIMEOUTS
        }
    }

    /** How one contact with the key ends. */
    private sealed interface Turn {
        data class Done(val result: HwKeyResult) : Turn

        /** The key wants a PIN and the link cannot stay open while it is typed (NFC). */
        data class NeedPin(val triesRemaining: Int?) : Turn
    }

    /** How a try with a PIN ends. */
    private sealed interface PinTry {
        data class Done(val result: HwKeyResult) : PinTry

        /** The key refused the PIN: a try is spent. */
        data object Refused : PinTry

        /**
         * The PIN never reached the key, or the key did not take it into account, and no try is
         * spent: its format is not one a key accepts (the library refuses it before sending it).
         * Asked again, a few times at most.
         */
        data object Unusable : PinTry
    }

    /**
     * One request for an assertion, from the wait for a key to the signature.
     *
     * The PIN, when the key wants one:
     * - USB: asked on the open connection, as many times as the key allows within [MAX_PIN_TRIES].
     * - NFC: the connection is closed while the PIN is typed, then a NEW contact is awaited and
     *   the PIN token and the assertion come in that new connection. Holding the key against the
     *   phone during the typing is not realistic, and a tag that stays in the field is not
     *   reported again: the user has to take the key away and bring it close again.
     */
    private inner class Exchange(
        private val rpId: String,
        private val clientDataHash: ByteArray,
        private val allowedCredentials: List<ByteArray>,
        private val requireUserVerification: Boolean,
        private val onStatus: (HwKeyStatus) -> Unit,
        private val pinProvider: (suspend (PinPrompt) -> CharArray?)?,
    ) {
        private val descriptors = allowedCredentials.map {
            PublicKeyCredentialDescriptor(PublicKeyCredentialType.PUBLIC_KEY, it)
        }

        // Serialized to the CTAP2 wire format. Null (no allow list) lets the key choose.
        private val credentialMaps: List<Map<String, *>>? = descriptors
            .map { it.toMap(SerializationType.CBOR) }
            .ifEmpty { null }

        /** Tells the UI when the key asks for a touch. Cancelling it stops that wait on the key. */
        private val commandState = object : CommandState() {
            override fun onKeepAliveStatus(status: Byte) {
                if (status == CommandState.STATUS_UPNEEDED) onStatus(HwKeyStatus.TOUCH_KEY)
            }
        }

        /** The key asked for a PIN, on this contact or an earlier one. */
        private var pinNeeded = false

        /** PINs the key refused so far. */
        private var refusedPins = 0

        /** The tries the key reported after the last refusal: known without asking again. */
        private var knownTries: Int? = null

        /** PINs that could not be used (see [PinTry.Unusable]) so far. */
        private var unusablePins = 0

        suspend fun run(): HwKeyResult {
            // The PIN typed for the next contact, over NFC. Zeroed on every way out.
            var carriedPin: CharArray? = null
            try {
                var status = HwKeyStatus.WAITING_FOR_KEY
                while (true) {
                    // The key is this request's alone until the connection to it is closed: the
                    // enrollment screen may be waiting for the same key, and must not talk to it
                    // (or take its NFC tap) meanwhile.
                    val claim = awaitClaim(status)
                    val turn = try {
                        contact(claim.key) { carriedPin.also { carriedPin = null } }
                    } finally {
                        claim.close()
                    }

                    when (turn) {
                        is Turn.Done -> return turn.result
                        is Turn.NeedPin -> {
                            // The link is cut: said now, before the typing, not after it. "Do not remove
                            // it" would stay on screen while the dialog asks to remove the key.
                            onStatus(HwKeyStatus.PIN_THEN_REAPPROACH)
                            val typed = askPin(turn.triesRemaining, secondContact = true)
                                ?: return HwKeyResult.UserCancelled
                            carriedPin = typed
                            status = HwKeyStatus.REMOVE_AND_REAPPROACH
                        }
                    }
                }
            } finally {
                carriedPin?.let { wipe(it) }
            }
        }

        /**
         * Waits for a key and claims it, saying [waiting] meanwhile, or why no key can come when
         * the discovery knows: a USB key that Android refused to NextSH, NFC switched off. Those
         * two only replace the plain "waiting" sentence: after a PIN over NFC the user is being
         * told to bring the key back, which stays true whatever else is wrong.
         */
        private suspend fun awaitClaim(waiting: HwKeyStatus): KeyClaim {
            // Read from a variable, not from the scope's result: a claim granted just as the
            // caller is cancelled would otherwise be dropped with the result, and stay claimed.
            var claimed: KeyClaim? = null
            try {
                coroutineScope {
                    var shown: HwKeyStatus? = null
                    fun show(hint: KeyHint) {
                        val status = statusWhileWaiting(waiting, hint)
                        if (status != shown) {
                            shown = status
                            onStatus(status)
                        }
                    }
                    show(discovery.hint.value)
                    val watcher = launch { discovery.hint.collect { show(it) } }
                    try {
                        claimed = discovery.claimKey(KeyUse.SIGNATURE)
                    } finally {
                        watcher.cancel()
                    }
                }
            } catch (e: Throwable) {
                claimed?.close()
                throw e
            }
            return checkNotNull(claimed)
        }

        private fun statusWhileWaiting(waiting: HwKeyStatus, hint: KeyHint): HwKeyStatus = when {
            waiting != HwKeyStatus.WAITING_FOR_KEY -> waiting
            hint == KeyHint.USB_ACCESS_DENIED -> HwKeyStatus.USB_ACCESS_DENIED
            hint == KeyHint.NFC_DISABLED -> HwKeyStatus.NFC_DISABLED
            else -> waiting
        }

        /**
         * One contact with [key]: opens the connection, talks until the exchange ends or needs a
         * new contact, and always closes the connection.
         *
         * [takePin] hands over the PIN carried from the previous contact, to talk(), which zeroes it.
         * It is only called once the connection is up, so that a failure to connect leaves the PIN
         * with the caller, who zeroes it.
         */
        private suspend fun contact(key: SecurityKey, takePin: () -> CharArray?): Turn {
            onStatus(HwKeyStatus.TALKING_TO_KEY)
            // Opening cannot be abandoned half way: a cancellation that lands while the key is
            // being opened would drop the connection when it comes up, and nothing would ever
            // close it (the key stays busy for the next request). So it is opened whatever
            // happens, and the cancellation is honoured right after, inside the try that closes it.
            val link = withContext(NonCancellable) { io { connector.connect(key) } }
            try {
                currentCoroutineContext().ensureActive()
                // Taken here and not earlier: until then the PIN stays with the caller, who zeroes it.
                val pin = takePin()
                return talk(link.session, key.transport, pin)
            } finally {
                // Closing must happen even when cancelled: a connection left open keeps the key
                // busy for the next attempt. A failure to close (the key is already gone) changes
                // nothing to how the exchange ended, so it is only noted.
                withContext(NonCancellable) {
                    io {
                        try {
                            link.close()
                        } catch (e: IOException) {
                            Timber.d("FIDO2 connection already gone at close")
                        }
                    }
                }
            }
        }

        /** Talks to the key on an open session until it signs, fails, or needs a new contact. */
        private suspend fun talk(ctap: Ctap2Session, transport: KeyTransport, pinIn: CharArray?): Turn {
            var pin = pinIn
            try {
                while (true) {
                    if (pin == null) {
                        if (!pinNeeded) {
                            // First attempt, no PIN. The key asks for one with CTAP2_ERR_PIN_REQUIRED
                            // (or one of its cousins) when its policy demands user verification.
                            tryWithoutPin(ctap)?.let { return Turn.Done(it) }
                            pinNeeded = true
                        }
                        if (pinProvider == null) {
                            return Turn.Done(HwKeyResult.Error(HwKeyError.PinUiUnavailable))
                        }
                        val tries = knownTries ?: readPinRetries(ctap)
                        if (tries == 0) return Turn.Done(HwKeyResult.Error(HwKeyError.PinBlocked))
                        if (transport == KeyTransport.NFC) return Turn.NeedPin(tries)
                        pin = askPin(tries, secondContact = false)
                            ?: return Turn.Done(HwKeyResult.UserCancelled)
                    }

                    val attempt = pin
                    pin = null
                    val outcome = try {
                        tryWithPin(ctap, attempt)
                    } finally {
                        wipe(attempt)
                    }
                    when (outcome) {
                        is PinTry.Done -> return Turn.Done(outcome.result)
                        PinTry.Refused -> {
                            refusedPins++
                            val tries = readPinRetries(ctap)
                            knownTries = tries
                            if (tries == 0) return Turn.Done(HwKeyResult.Error(HwKeyError.PinBlocked))
                            if (refusedPins >= MAX_PIN_TRIES) {
                                return Turn.Done(HwKeyResult.Error(HwKeyError.PinIncorrect(tries)))
                            }
                            // Loops: the PIN is asked again, on this connection or on the next contact.
                        }
                        PinTry.Unusable -> {
                            // No try was spent, but a PIN that is never usable must not be asked for ever.
                            if (++unusablePins >= MAX_UNUSABLE_PINS) {
                                return Turn.Done(HwKeyResult.Error(HwKeyError.Unexpected))
                            }
                        }
                    }
                }
            } finally {
                pin?.let { wipe(it) }
            }
        }

        private suspend fun askPin(triesRemaining: Int?, secondContact: Boolean): CharArray? {
            val provider = pinProvider ?: return null
            return provider(
                PinPrompt(
                    triesRemaining = triesRemaining,
                    incorrect = refusedPins > 0,
                    secondContact = secondContact,
                ),
            )
        }

        /**
         * Asks for the assertion without PIN authentication. Returns the result, or null when the
         * key wants a PIN.
         *
         * A key gives up waiting for the touch on its own (a YubiKey after about 30 s) while the
         * prompt on screen counts 60 s: the request is sent again, and the key blinks again, for as
         * long as the caller does not cancel. The cancellation is what ends the wait. A key that
         * answers "timeout" at once, again and again, is not asked for ever ([TouchTimeoutGuard]).
         */
        private suspend fun tryWithoutPin(ctap: Ctap2Session): HwKeyResult? {
            val guard = TouchTimeoutGuard(nanoTime)
            while (true) {
                try {
                    guard.requestSent()
                    return io(commandState) {
                        // uv only when asked for: it means the key's own user verification, not a PIN.
                        val options = if (requireUserVerification) {
                            mapOf("up" to true, "uv" to true)
                        } else {
                            mapOf("up" to true)
                        }
                        try {
                            resultOf(
                                ctap.getAssertions(
                                    rpId, clientDataHash, credentialMaps, null, options, null, null, commandState,
                                ).firstOrNull(),
                            )
                        } catch (e: CtapException) {
                            when (e.ctapError) {
                                CtapException.ERR_PIN_REQUIRED,
                                CtapException.ERR_PIN_INVALID,
                                CtapException.ERR_PIN_AUTH_INVALID -> null
                                else -> throw e
                            }
                        }
                    }
                } catch (e: CtapException) {
                    if (e.ctapError != CtapException.ERR_USER_ACTION_TIMEOUT || guard.shouldGiveUp()) throw e
                    Timber.d("FIDO2 key stopped waiting for the touch, asking again")
                }
            }
        }

        /**
         * The CTAP2 PIN flow: pick the highest PIN/UV protocol the key supports, get a PIN token
         * scoped to getAssertion for [rpId], authenticate the client data hash with it, and send
         * the assertion again with that proof.
         *
         * When the key gives up waiting for the touch (see [tryWithoutPin]) the assertion is sent
         * again with the same token, so that the PIN is not asked for twice. If the key no longer
         * honours the token (it expired), the PIN is asked for again, and no try is spent.
         */
        private suspend fun tryWithPin(ctap: Ctap2Session, pin: CharArray): PinTry {
            var pinToken: ByteArray? = null
            var pinUvAuthParam: ByteArray? = null
            try {
                val protocol = io { protocolFor(ctap) }
                val token = try {
                    io(commandState) {
                        ClientPin(ctap, protocol).getPinToken(pin, ClientPin.PIN_PERMISSION_GA, rpId)
                    }
                } catch (e: IllegalArgumentException) {
                    // The library refuses a PIN of under 4 characters or over 63 bytes before it
                    // goes out: the key never saw it, so no try was spent. The dialog does not let
                    // such a PIN through, this is the net under it.
                    Timber.d("FIDO2 PIN refused by the library, asking again")
                    return PinTry.Unusable
                } catch (e: CtapException) {
                    if (e.ctapError == CtapException.ERR_PIN_INVALID) return PinTry.Refused
                    throw e
                }
                pinToken = token
                val param = io { protocol.authenticate(token, clientDataHash) }
                pinUvAuthParam = param
                val guard = TouchTimeoutGuard(nanoTime)
                while (true) {
                    try {
                        guard.requestSent()
                        // The PIN is the user verification: uv must not be sent next to a pinUvAuthParam.
                        val assertions = io(commandState) {
                            ctap.getAssertions(
                                rpId, clientDataHash, credentialMaps, null, mapOf("up" to true), param,
                                protocol.version, commandState,
                            )
                        }
                        return PinTry.Done(resultOf(assertions.firstOrNull()))
                    } catch (e: CtapException) {
                        when (e.ctapError) {
                            CtapException.ERR_USER_ACTION_TIMEOUT -> {
                                if (guard.shouldGiveUp()) throw e
                                Timber.d("FIDO2 key stopped waiting for the touch, asking again")
                            }
                            CtapException.ERR_PIN_INVALID -> return PinTry.Refused
                            CtapException.ERR_PIN_AUTH_INVALID,
                            CtapException.ERR_PIN_TOKEN_EXPIRED -> {
                                Timber.d("FIDO2 PIN token no longer honoured, asking for the PIN again")
                                return PinTry.Unusable
                            }
                            else -> throw e
                        }
                    }
                }
            } finally {
                pinToken?.let { Arrays.fill(it, 0.toByte()) }
                pinUvAuthParam?.let { Arrays.fill(it, 0.toByte()) }
            }
        }

        /** The tries the key says it has left, or null when it does not say (not fatal). */
        private suspend fun readPinRetries(ctap: Ctap2Session): Int? = io {
            try {
                ClientPin(ctap, protocolFor(ctap)).getPinRetries().count
            } catch (e: CtapException) {
                null
            }
        }

        private fun resultOf(assertion: Ctap2Session.AssertionData?): HwKeyResult =
            if (assertion == null) {
                HwKeyResult.Error(HwKeyError.Unexpected)
            } else {
                parseAssertionData(assertion, descriptors, allowedCredentials)
            }
    }

    private fun protocolFor(ctap: Ctap2Session): PinUvAuthProtocol =
        if (ctap.cachedInfo.pinUvAuthProtocols.contains(2)) PinUvAuthProtocolV2() else PinUvAuthProtocolV1()

    /** A blocking call of YubiKit that stops with the caller, see [blockingKeyCall]. */
    private suspend fun <T> io(state: CommandState? = null, block: () -> T): T =
        blockingKeyCall(ioDispatcher, state, block)

    private fun wipe(pin: CharArray) = Arrays.fill(pin, '\u0000')

    /** The reason behind a CTAP error code the key answered with. */
    internal fun errorFor(e: CtapException): HwKeyError = when (e.ctapError) {
        CtapException.ERR_NO_CREDENTIALS -> HwKeyError.NoMatchingCredential
        CtapException.ERR_PIN_BLOCKED -> HwKeyError.PinBlocked
        CtapException.ERR_PIN_AUTH_BLOCKED -> HwKeyError.PinAuthBlocked
        CtapException.ERR_PIN_INVALID -> HwKeyError.PinIncorrect(triesRemaining = null)
        CtapException.ERR_PIN_NOT_SET -> HwKeyError.PinNotSet
        CtapException.ERR_USER_ACTION_TIMEOUT,
        CtapException.ERR_ACTION_TIMEOUT -> HwKeyError.TouchTimeout
        CtapException.ERR_OPERATION_DENIED,
        CtapException.ERR_UP_REQUIRED -> HwKeyError.Refused
        CtapException.ERR_UNSUPPORTED_ALGORITHM,
        CtapException.ERR_UNSUPPORTED_EXTENSION,
        CtapException.ERR_UNSUPPORTED_OPTION,
        CtapException.ERR_INVALID_OPTION -> HwKeyError.UnsupportedKey
        else -> HwKeyError.Unexpected
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
            ?: return HwKeyResult.Error(HwKeyError.Unexpected)

        if (authData.size < 37) {
            return HwKeyResult.Error(HwKeyError.Unexpected)
        }

        val flags = authData[32]
        val counter = ((authData[33].toInt() and 0xFF).toUInt() shl 24) or
            ((authData[34].toInt() and 0xFF).toUInt() shl 16) or
            ((authData[35].toInt() and 0xFF).toUInt() shl 8) or
            (authData[36].toInt() and 0xFF).toUInt()

        // Wipe authData after extracting flags and counter (Issue 9)
        authData.fill(0)

        // The assertion was asked with up=true: a key that answers without the user-present bit is
        // not conforming, and a signature without it is refused by SkSshSignature (and by sshd).
        if (flags.toInt() and FLAG_USER_PRESENT == 0) return HwKeyResult.Error(HwKeyError.Unexpected)

        val signature = assertionData.signature
            ?: return HwKeyResult.Error(HwKeyError.Unexpected)

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

    private companion object {
        /** The key itself blocks the PIN after 3 wrong tries in a row (until it is power cycled). */
        const val MAX_PIN_TRIES = 3

        /** PINs that cannot be used and are asked for again before giving up (no try is spent on them). */
        const val MAX_UNUSABLE_PINS = 3

        /** A touch timeout that comes back sooner than this was not a wait for the touch. */
        const val QUICK_TIMEOUT_NANOS = 2_000_000_000L

        /** Quick touch timeouts in a row after which the key is not asked again. */
        const val MAX_QUICK_TIMEOUTS = 3

        /** The user-present bit of the flags byte of the authenticator data. */
        const val FLAG_USER_PRESENT = 0x01
    }
}
