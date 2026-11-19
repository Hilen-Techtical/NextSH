// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sessions

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.composables.icons.lucide.Fingerprint
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Nfc
import com.composables.icons.lucide.Usb
import fr.techtical.nextsh.R
import fr.techtical.nextsh.core.auth.HardwareKeyAuthenticator
import fr.techtical.nextsh.core.auth.HwKeyError
import fr.techtical.nextsh.core.auth.HwKeyResult
import fr.techtical.nextsh.core.auth.HwKeyStatus
import fr.techtical.nextsh.core.auth.PinPrompt
import fr.techtical.nextsh.core.ssh.Fido2Prompt
import fr.techtical.nextsh.core.ssh.Fido2PromptCoordinator
import fr.techtical.nextsh.core.ssh.hwKeyErrorMessageArgs
import fr.techtical.nextsh.core.ssh.hwKeyErrorMessageRes
import fr.techtical.nextsh.shared.core.ssh.fido2.SkKeyType
import fr.techtical.nextsh.shared.core.ssh.fido2.SkSshSignature
import fr.techtical.nextsh.ui.components.MaskedPinField
import fr.techtical.nextsh.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.Arrays

/** Holds a pending PIN request waiting for the user to confirm or cancel. */
internal data class PinRequest(
    val prompt: PinPrompt,
    val deferred: CompletableDeferred<CharArray?>,
) {
    /**
     * Hands the typed PIN to the authenticator, or null for a cancellation. A PIN that nobody takes
     * (the request is already over: the prompt ended while the dialog was up, or a second tap on
     * Confirm) would stay in memory as typed, so it is zeroed here.
     */
    fun answer(pin: CharArray?) {
        if (!deferred.complete(pin)) pin?.let { Arrays.fill(it, '\u0000') }
    }

    /**
     * Waits for the user to answer. [onConfirmed] runs when a PIN comes back (not on a
     * cancellation), before it is returned: the moment to give the prompt the time the touch that
     * follows needs.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun await(onConfirmed: () -> Unit): CharArray? {
        try {
            val pin = deferred.await()
            if (pin != null) onConfirmed()
            return pin
        } catch (e: CancellationException) {
            // The prompt ended while the dialog was up. A PIN confirmed in that very moment has
            // no taker: wipe it.
            if (deferred.isCompleted && !deferred.isCancelled) {
                deferred.getCompleted()?.let { Arrays.fill(it, '\u0000') }
            }
            throw e
        }
    }
}

/**
 * The SSH signature of the answer of a key, or null when the answer does not make one: SkSshSignature
 * refuses a signature without the user-present flag (an exception that, thrown here, would take the
 * app down with the connection). The answer is wiped in every case, and so is the copy of its
 * signature when no signature object takes it over.
 */
internal fun sshSignatureOf(keyType: SkKeyType, result: HwKeyResult.Success): SkSshSignature? {
    val raw = result.signature.copyOf()
    return try {
        SkSshSignature.create(
            keyType = keyType,
            rawSignature = raw,
            flags = result.flags,
            counter = result.counter,
        )
    } catch (e: IllegalArgumentException) {
        raw.fill(0)
        null
    } finally {
        result.wipe()
    }
}

/** The shortest PIN a FIDO2 key takes, in characters. */
internal const val FIDO_PIN_MIN_CHARS = 4

/** The longest PIN a FIDO2 key takes, in bytes of its UTF-8 encoding (not in characters). */
internal const val FIDO_PIN_MAX_BYTES = 63

/**
 * The number of bytes [pin] takes once encoded in UTF-8, the way the key will count it, worked out
 * from the characters so that the PIN is never built into a String. A surrogate pair is one
 * character of 4 bytes; a lone surrogate is replaced by "?" by the encoder, so it counts for 1.
 */
internal fun fidoPinUtf8Length(pin: List<Char>): Int {
    var bytes = 0
    var i = 0
    while (i < pin.size) {
        val c = pin[i]
        when {
            c.code < 0x80 -> bytes += 1
            c.code < 0x800 -> bytes += 2
            c.isHighSurrogate() && i + 1 < pin.size && pin[i + 1].isLowSurrogate() -> {
                bytes += 4
                i++
            }
            c.isSurrogate() -> bytes += 1
            else -> bytes += 3
        }
        i++
    }
    return bytes
}

/**
 * Whether [pin] is one a key can take: at least 4 characters and at most 63 bytes in UTF-8.
 * Anything else makes the library throw before the PIN is sent, which used to end the connection
 * as an unexpected error.
 */
internal fun isFidoPinAcceptable(pin: List<Char>): Boolean =
    pin.size >= FIDO_PIN_MIN_CHARS && fidoPinUtf8Length(pin) <= FIDO_PIN_MAX_BYTES

@OptIn(ExperimentalMaterial3Api::class, ExperimentalCoroutinesApi::class)
@Composable
fun Fido2ChallengeBottomSheet(
    prompt: Fido2Prompt,
    nowMs: () -> Long,
    hardwareKeyAuthenticator: HardwareKeyAuthenticator,
    onSigned: (SkSshSignature) -> Unit,
    onCancel: () -> Unit,
    onKeyError: (HwKeyError) -> Unit,
    onPinRequested: () -> Unit,
) {
    val request = prompt.request
    // The countdown is read off the coordinator's deadline, never counted here: the sheet is
    // recreated by a rotation, and the PIN entry moves the deadline. What it shows is what the
    // coordinator will do. It only reads: the coordinator ends the prompt at that deadline, and a
    // second ending decided here would race it (a timeout turned into a cancellation, or an
    // extended prompt cancelled on the stale deadline).
    var remainingMs by remember { mutableLongStateOf(prompt.remainingMs(nowMs())) }
    var errorReason by remember { mutableStateOf<HwKeyError?>(null) }
    var pinRequest by remember { mutableStateOf<PinRequest?>(null) }
    // Told by the authenticator from whatever thread it works on, hence a flow and not a plain
    // Compose state.
    val keyStatus = remember { MutableStateFlow(HwKeyStatus.WAITING_FOR_KEY) }
    val currentKeyStatus by keyStatus.collectAsState()

    LaunchedEffect(prompt.deadlineMs) {
        runFido2Countdown(prompt, nowMs) { remainingMs = it }
    }

    val pinProvider: suspend (PinPrompt) -> CharArray? = { pinPrompt ->
        // Typing a PIN takes time the countdown must not eat: ask for a fresh delay.
        onPinRequested()
        // The dialog state is Compose state: written on the main thread, whatever thread asks.
        withContext(Dispatchers.Main.immediate) {
            val request = PinRequest(pinPrompt, CompletableDeferred())
            pinRequest = request
            try {
                // The touch that comes after Confirm needs its time too: the delay is given again
                // at that moment, not only when the dialog opens (the typing may have used it up).
                request.await(onConfirmed = onPinRequested)
            } finally {
                pinRequest = null
            }
        }
    }

    LaunchedEffect(request) {
        val clientDataHash = MessageDigest.getInstance("SHA-256").digest(request.signingData)
        try {
            val result = hardwareKeyAuthenticator.getAssertion(
                rpId = request.rpId,
                clientDataHash = clientDataHash,
                allowedCredentials = request.allowedCredentialIds,
                requireUserVerification = false,
                onStatus = { status ->
                    keyStatus.value = status
                    // Taking the key away and bringing it back after the PIN takes time too.
                    if (status == HwKeyStatus.REMOVE_AND_REAPPROACH) onPinRequested()
                },
                pinProvider = pinProvider,
            )
            // The key failed: the error stays readable for a moment, then the connection fails as a
            // key error, carrying the reason: the message the user ends up reading is the one for
            // that reason, not a general one. Not as a cancellation, the user cancelled nothing.
            // A blocked PIN ends the same way: nothing more can be done with this key now.
            suspend fun failWith(reason: HwKeyError) {
                errorReason = reason
                delay(2000L)
                onKeyError(reason)
            }

            when (result) {
                is HwKeyResult.Success -> {
                    val signature = sshSignatureOf(request.keyType, result)
                    // No signature out of an answer that looked fine: a key that breaks the
                    // protocol must end the connection as a key error, not take the app down.
                    if (signature != null) onSigned(signature) else failWith(HwKeyError.Unexpected)
                }
                is HwKeyResult.UserCancelled -> onCancel()
                is HwKeyResult.Error -> failWith(result.reason)
            }
        } finally {
            clientDataHash.fill(0)
        }
    }

    val currentPinRequest = pinRequest
    if (currentPinRequest != null) {
        // One dialog per request: a retry after a wrong PIN starts from an empty field.
        key(currentPinRequest) {
            PinEntryDialog(
                prompt = currentPinRequest.prompt,
                onConfirm = { pin ->
                    // pin is a CharArray built by the wipe-safe masked-sink field below: the full
                    // PIN is never assembled into an immutable Kotlin String. The authenticator
                    // owns and zeroes this CharArray after use; if the request is already over,
                    // answer() zeroes it.
                    currentPinRequest.answer(pin)
                },
                onCancel = {
                    currentPinRequest.answer(null)
                },
            )
        }
    }

    val progress by animateFloatAsState(
        targetValue = (remainingMs.toFloat() / Fido2PromptCoordinator.PROMPT_TIMEOUT_MS).coerceIn(0f, 1f),
        animationSpec = tween(100, easing = LinearEasing),
        label = "fido2_countdown",
    )

    // On the sheet itself, two things cancel the prompt, and only two: the Cancel button and the
    // back gesture. (The PIN dialog above also cancels it, on back, or on its own Cancel.) A tap on
    // the scrim or a swipe down must not, because both are easy to trigger by accident while the
    // user handles the key, and a stray touch should not abort the connection. Refusing Hidden
    // here turns the scrim tap into a no-op and makes a released swipe settle back to Expanded.
    // Back still cancels: the sheet runs hide() and then calls onDismissRequest when it completes
    // or is interrupted, without asking confirmValueChange (hide() only vetoes the final state
    // change, so the slide-out animation still plays). That behavior was checked in Material3
    // 1.2.1 (bytecode) and 1.3.1 (sources): recheck it on a version bump, no test covers the
    // sheet. skipPartiallyExpanded drops the half-open state, which a sheet taller than half the
    // screen (landscape terminal) would otherwise have: there the first back would collapse the
    // sheet instead of cancelling.
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange    = { it != SheetValue.Hidden },
    )

    ModalBottomSheet(
        onDismissRequest = onCancel,
        sheetState       = sheetState,
        containerColor   = Surface,
        contentColor     = TextPrimary,
        // No drag handle: the sheet can no longer be swiped away, so the handle would suggest a
        // gesture that does nothing, and its accessibility "dismiss" action would do nothing too.
        dragHandle       = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = Spacing.Xl, vertical = Spacing.Lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ── Icon-wrap principal Lucide.Fingerprint sur fond Gold@0.10 ────
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(Radii.Lg))
                    .background(Gold.copy(alpha = 0.10f), RoundedCornerShape(Radii.Lg)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector        = Lucide.Fingerprint,
                    contentDescription = null,
                    tint               = Gold,
                    modifier           = Modifier.size(40.dp),
                )
            }

            Spacer(Modifier.height(Spacing.Md))

            Text(
                text       = stringResource(R.string.fido2_tap_key),
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize   = 16.sp,
                color      = TextPrimary,
                textAlign  = TextAlign.Center,
            )

            Spacer(Modifier.height(Spacing.Xs))

            // ── Where the exchange stands: what the user has to do now ──────
            Text(
                text       = stringResource(keyStatusMessageRes(currentKeyStatus)),
                fontFamily = SpaceGroteskFamily,
                fontSize   = 13.sp,
                color      = TextSecondary,
                textAlign  = TextAlign.Center,
            )

            Spacer(Modifier.height(Spacing.Sm))

            // ── Pictos USB / NFC ────────────────────────────────────────────
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.Lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TransportIcon(icon = Lucide.Usb, label = "USB")
                TransportIcon(icon = Lucide.Nfc, label = "NFC")
            }

            // ── Bandeau d'erreur DA ─────────────────────────────────────────
            errorReason?.let { reason ->
                Spacer(Modifier.height(Spacing.Md))
                ErrorBanner(text = hwKeyErrorText(reason))
            }

            Spacer(Modifier.height(Spacing.Lg))

            // ── Compte à rebours ────────────────────────────────────────────
            LinearProgressIndicator(
                progress   = { progress },
                modifier   = Modifier.fillMaxWidth(),
                color      = Burgundy,
                trackColor = SurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.Xs))
            Text(
                text       = "${remainingMs / 1000L}s",
                fontFamily = JetBrainsMonoFamily,
                fontSize   = 11.sp,
                color      = TextDisabled,
            )

            Spacer(Modifier.height(Spacing.Lg))

            // ── Bouton annuler outlined ─────────────────────────────────────
            // Never takes keyboard focus. A YubiKey touched outside a FIDO operation acts as a USB
            // keyboard: it types its OTP and ends with Enter. That Enter moved the focus onto this
            // button, the only focusable element of the sheet, and pressed it, cancelling the
            // connection. Touch and TalkBack still work: accessibility actions do not go through
            // keyboard focus. focusProperties must come before clickable to reach its focus target.
            // Trade-off: a physical keyboard user (DeX, Chromebook) no longer reaches the button
            // with Tab and Enter, and keeps the system back instead.
            // Now the only touch exit, so it is a Button for TalkBack and keeps a 48 dp target:
            // minimumInteractiveComponentSize comes first so the touch area surrounds the drawn
            // button, which keeps its look.
            Box(
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clip(RoundedCornerShape(Radii.Md))
                    .border(1.dp, Border2, RoundedCornerShape(Radii.Md))
                    .focusProperties { canFocus = false }
                    .clickable(role = Role.Button, onClick = onCancel)
                    .padding(horizontal = Spacing.Lg, vertical = Spacing.Sm),
            ) {
                Text(
                    text       = stringResource(R.string.action_cancel),
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize   = 13.sp,
                    color      = TextSecondary,
                )
            }
            Spacer(Modifier.height(Spacing.Sm))
        }
    }
}

@Composable
private fun TransportIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.Xs),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(Radii.Sm))
                .background(SurfaceVariant, RoundedCornerShape(Radii.Sm)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector        = icon,
                contentDescription = label,
                tint               = GoldMuted,
                modifier           = Modifier.size(20.dp),
            )
        }
        Text(
            text       = label,
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize   = 9.sp,
            color      = TextDisabled,
        )
    }
}

@Composable
private fun ErrorBanner(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(ErrorRed.copy(alpha = 0.12f), RoundedCornerShape(Radii.Sm))
            .border(1.dp, ErrorRed.copy(alpha = 0.30f), RoundedCornerShape(Radii.Sm))
            .padding(horizontal = Spacing.Md, vertical = Spacing.Sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text       = text,
            fontFamily = SpaceGroteskFamily,
            fontSize   = 12.sp,
            color      = ErrorRed,
            textAlign  = TextAlign.Center,
        )
    }
}

/** The sentence for the status line of the sheet. */
@androidx.annotation.StringRes
internal fun keyStatusMessageRes(status: HwKeyStatus): Int = when (status) {
    HwKeyStatus.WAITING_FOR_KEY -> R.string.fido2_status_waiting_key
    HwKeyStatus.USB_ACCESS_DENIED -> R.string.fido2_status_usb_denied
    HwKeyStatus.NFC_DISABLED -> R.string.fido2_status_nfc_off
    HwKeyStatus.TALKING_TO_KEY -> R.string.fido2_status_talking
    HwKeyStatus.TOUCH_KEY -> R.string.fido2_status_touch
    HwKeyStatus.PIN_THEN_REAPPROACH -> R.string.fido2_status_pin_reapproach
    HwKeyStatus.REMOVE_AND_REAPPROACH -> R.string.fido2_status_reapproach
}

/** The sentence the user reads when the security key failed, for a non-technician. */
@Composable
private fun hwKeyErrorText(reason: HwKeyError): String =
    stringResource(hwKeyErrorMessageRes(reason), *hwKeyErrorMessageArgs(reason))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PinEntryDialog(
    prompt: PinPrompt,
    onConfirm: (CharArray) -> Unit,
    onCancel: () -> Unit,
) {
    val triesRemaining = prompt.triesRemaining
    // Wipe-safe masked sink (see MaskedPinField, which also says what the buffer cannot promise):
    // the secret lives in this mutable char buffer, never in an immutable String. The CharArray
    // built at confirm is the copy downstream owns and zeroes.
    val pinChars: SnapshotStateList<Char> = remember { mutableStateListOf() }

    // Clear any entered digits if the dialog is abandoned (cancel, dismiss, or leaving comp).
    DisposableEffect(Unit) {
        onDispose { pinChars.clear() }
    }

    AlertDialog(
        // Back cancels. A touch outside does not: the key is handled with the other hand and a
        // stray touch must not throw the PIN away.
        onDismissRequest = onCancel,
        properties       = DialogProperties(dismissOnClickOutside = false),
        containerColor   = Surface,
        shape            = RoundedCornerShape(Radii.Lg),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(Radii.Sm))
                        .background(Gold.copy(alpha = 0.12f), RoundedCornerShape(Radii.Sm)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Lucide.Fingerprint,
                        contentDescription = null,
                        tint     = Gold,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Text(
                    text       = stringResource(R.string.fido2_pin_title),
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize   = 16.sp,
                    color      = TextPrimary,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
                if (prompt.incorrect) {
                    Text(
                        text       = stringResource(R.string.fido2_error_pin_incorrect),
                        fontFamily = SpaceGroteskFamily,
                        fontSize   = 12.sp,
                        color      = ErrorRed,
                    )
                }
                if (triesRemaining != null && (prompt.incorrect || triesRemaining < 3)) {
                    Text(
                        text       = stringResource(R.string.fido2_pin_tries_remaining, triesRemaining),
                        fontFamily = SpaceGroteskFamily,
                        fontSize   = 12.sp,
                        color      = WarningAmber,
                    )
                }
                if (prompt.secondContact) {
                    // Over NFC the link is cut while the PIN is typed.
                    Text(
                        text       = stringResource(R.string.fido2_pin_nfc_hint),
                        fontFamily = SpaceGroteskFamily,
                        fontSize   = 12.sp,
                        color      = TextSecondary,
                    )
                }
                val textStyle = TextStyle(
                    fontFamily = JetBrainsMonoFamily,
                    fontSize   = 14.sp,
                    color      = TextPrimary,
                )
                val colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = Gold,
                    unfocusedBorderColor = Border2,
                    focusedLabelColor    = Gold,
                    cursorColor          = Gold,
                    focusedContainerColor   = SurfaceVariant,
                    unfocusedContainerColor = SurfaceVariant,
                )
                MaskedPinField(
                    pinChars  = pinChars,
                    colors    = colors,
                    textStyle = textStyle,
                    label = {
                        Text(
                            text       = stringResource(R.string.fido2_pin_hint),
                            fontFamily = JetBrainsMonoFamily,
                            fontSize   = 13.sp,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    // Materialize the secret exactly once; downstream owns and zeroes it.
                    val out = CharArray(pinChars.size) { pinChars[it] }
                    onConfirm(out)
                    // Clear our own buffer (the field shows it, so it empties too); never call
                    // toString() on it.
                    pinChars.clear()
                },
                // Only a PIN a key can take: the library refuses a shorter or longer one before it
                // is sent, and the connection used to end as an unexpected error.
                enabled = isFidoPinAcceptable(pinChars),
                colors  = ButtonDefaults.buttonColors(
                    containerColor = Burgundy,
                    contentColor   = White,
                    disabledContainerColor = BurgundyDark,
                    disabledContentColor   = TextSecondary,
                ),
                shape = RoundedCornerShape(Radii.Md),
                // Never takes keyboard focus, like the Cancel button of the sheet: a YubiKey
                // touched outside an operation types its OTP and ends with Enter, which would press
                // the one focusable button and send a PIN nobody confirmed. focusProperties comes
                // first, ahead of the clickable the button adds, to reach its focus target.
                modifier = Modifier.focusProperties { canFocus = false },
            ) {
                Text(
                    text       = stringResource(R.string.action_confirm),
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize   = 13.sp,
                )
            }
        },
        dismissButton = {
            // Not focusable either, for the same reason as Confirm.
            TextButton(onClick = onCancel, modifier = Modifier.focusProperties { canFocus = false }) {
                Text(
                    text       = stringResource(R.string.action_cancel),
                    fontFamily = SpaceGroteskFamily,
                    fontSize   = 13.sp,
                    color      = TextSecondary,
                )
            }
        },
    )
}
