// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sessions

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Fingerprint
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Nfc
import com.composables.icons.lucide.Usb
import fr.techtical.nextsh.R
import fr.techtical.nextsh.core.auth.HardwareKeyAuthenticator
import fr.techtical.nextsh.core.auth.HwKeyResult
import fr.techtical.nextsh.core.auth.PinPrompt
import fr.techtical.nextsh.shared.core.ssh.fido2.SkSshSignature
import fr.techtical.nextsh.ui.theme.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import java.security.MessageDigest

/** Holds a pending PIN request waiting for the user to confirm or cancel. */
private data class PinRequest(
    val triesRemaining: Int?,
    val deferred: CompletableDeferred<CharArray?>,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Fido2ChallengeBottomSheet(
    state: Fido2ChallengeUiState,
    hardwareKeyAuthenticator: HardwareKeyAuthenticator,
    onSigned: (SkSshSignature) -> Unit,
    onCancel: () -> Unit,
) {
    var timeRemaining by remember { mutableFloatStateOf(60f) }
    var statusMessage by remember { mutableStateOf("") }
    var pinBlocked by remember { mutableStateOf(false) }
    var pinRequest by remember { mutableStateOf<PinRequest?>(null) }

    LaunchedEffect(pinRequest) {
        if (pinRequest != null) return@LaunchedEffect
        while (timeRemaining > 0f) {
            delay(100L)
            timeRemaining -= 0.1f
            if (pinRequest != null) return@LaunchedEffect
        }
        onCancel()
    }

    val pinProvider: suspend (PinPrompt) -> CharArray? = { prompt ->
        val deferred = CompletableDeferred<CharArray?>()
        pinRequest = PinRequest(prompt.triesRemaining, deferred)
        try {
            deferred.await()
        } finally {
            pinRequest = null
        }
    }

    LaunchedEffect(Unit) {
        val clientDataHash = MessageDigest.getInstance("SHA-256").digest(state.signingData)
        try {
            val result = hardwareKeyAuthenticator.getAssertion(
                rpId = state.rpId,
                clientDataHash = clientDataHash,
                allowedCredentials = state.allowedCredentialIds,
                requireUserVerification = false,
                pinProvider = pinProvider,
            )
            when (result) {
                is HwKeyResult.Success -> {
                    val signature = SkSshSignature.create(
                        keyType = state.keyType,
                        rawSignature = result.signature.copyOf(),
                        flags = result.flags,
                        counter = result.counter,
                    )
                    result.wipe()
                    onSigned(signature)
                }
                is HwKeyResult.UserCancelled -> onCancel()
                is HwKeyResult.PinBlocked -> {
                    pinBlocked = true
                    statusMessage = ""
                }
                is HwKeyResult.Error -> {
                    statusMessage = result.message
                    delay(2000L)
                    onCancel()
                }
            }
        } finally {
            clientDataHash.fill(0)
        }
    }

    val currentPinRequest = pinRequest
    if (currentPinRequest != null) {
        PinEntryDialog(
            triesRemaining = currentPinRequest.triesRemaining,
            onConfirm = { pin ->
                // pin is a CharArray built by the wipe-safe masked-sink field below: the full
                // PIN is never assembled into an immutable Kotlin String. The authenticator
                // owns and zeroes this CharArray after use.
                currentPinRequest.deferred.complete(pin)
            },
            onCancel = {
                currentPinRequest.deferred.complete(null)
            },
        )
    }

    val progress by animateFloatAsState(
        targetValue = timeRemaining / 60f,
        animationSpec = tween(100, easing = LinearEasing),
        label = "fido2_countdown",
    )

    ModalBottomSheet(
        onDismissRequest = onCancel,
        containerColor   = Surface,
        contentColor     = TextPrimary,
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
            if (pinBlocked) {
                Spacer(Modifier.height(Spacing.Md))
                ErrorBanner(text = stringResource(R.string.fido2_pin_blocked))
            } else if (statusMessage.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.Md))
                ErrorBanner(text = statusMessage)
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
                text       = "${timeRemaining.toInt()}s",
                fontFamily = JetBrainsMonoFamily,
                fontSize   = 11.sp,
                color      = TextDisabled,
            )

            Spacer(Modifier.height(Spacing.Lg))

            // ── Bouton annuler outlined ─────────────────────────────────────
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(Radii.Md))
                    .border(1.dp, Border2, RoundedCornerShape(Radii.Md))
                    .clickable(onClick = onCancel)
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

/** Mask glyph displayed in place of each entered PIN character. PINs never contain it. */
private const val MASK = '•' // '•' BULLET

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PinEntryDialog(
    triesRemaining: Int?,
    onConfirm: (CharArray) -> Unit,
    onCancel: () -> Unit,
) {
    // Wipe-safe masked sink: the secret lives in this mutable char buffer, never in an
    // immutable String. ASCII digit/letter chars are JVM-interned, so the entries here are
    // not secret-bearing copies; the only secret-bearing materialization is the CharArray
    // built at confirm, which downstream owns and zeroes.
    val pinChars: SnapshotStateList<Char> = remember { mutableStateListOf() }

    // The visible text is always a run of MASK bullets matching pinChars.size, cursor at end.
    var fieldValue by remember { mutableStateOf(TextFieldValue("", TextRange(0))) }

    // Clear any entered digits if the dialog is abandoned (cancel, dismiss, or leaving comp).
    DisposableEffect(Unit) {
        onDispose { pinChars.clear() }
    }

    AlertDialog(
        onDismissRequest = onCancel,
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
                if (triesRemaining != null && triesRemaining < 3) {
                    Text(
                        text       = stringResource(R.string.fido2_pin_tries_remaining, triesRemaining),
                        fontFamily = SpaceGroteskFamily,
                        fontSize   = 12.sp,
                        color      = WarningAmber,
                    )
                }
                val interactionSource = remember { MutableInteractionSource() }
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
                BasicTextField(
                    value         = fieldValue,
                    onValueChange = { newValue ->
                        val newText = newValue.text
                        if (newText.length < pinChars.size) {
                            // A deletion/backspace removed bullets: drop matching trailing chars.
                            repeat(pinChars.size - newText.length) {
                                if (pinChars.isNotEmpty()) pinChars.removeAt(pinChars.lastIndex)
                            }
                        } else {
                            // Anything that is not a mask bullet is a freshly typed (or pasted)
                            // character. Append the just-typed chars; the full PIN is never
                            // assembled into a String (only the transient single typed char is).
                            newText.filterNot { it == MASK }.forEach { pinChars.add(it) }
                        }
                        // Re-render the field as the correct number of bullets, cursor at end.
                        fieldValue = TextFieldValue(
                            text      = MASK.toString().repeat(pinChars.size),
                            selection = TextRange(pinChars.size),
                        )
                    },
                    singleLine      = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    textStyle       = textStyle,
                    cursorBrush     = SolidColor(Gold),
                    interactionSource = interactionSource,
                    modifier = Modifier.fillMaxWidth(),
                ) { innerTextField ->
                    OutlinedTextFieldDefaults.DecorationBox(
                        value             = fieldValue.text,
                        innerTextField    = innerTextField,
                        enabled           = true,
                        singleLine        = true,
                        visualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
                        interactionSource = interactionSource,
                        label = {
                            Text(
                                text       = stringResource(R.string.fido2_pin_hint),
                                fontFamily = JetBrainsMonoFamily,
                                fontSize   = 13.sp,
                            )
                        },
                        colors            = colors,
                        container = {
                            OutlinedTextFieldDefaults.ContainerBox(
                                enabled           = true,
                                isError           = false,
                                interactionSource = interactionSource,
                                colors            = colors,
                                shape             = RoundedCornerShape(Radii.Md),
                            )
                        },
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    // Materialize the secret exactly once; downstream owns and zeroes it.
                    val out = CharArray(pinChars.size) { pinChars[it] }
                    onConfirm(out)
                    // Clear our own buffer and reset the field; never call toString() on it.
                    pinChars.clear()
                    fieldValue = TextFieldValue("", TextRange(0))
                },
                enabled = pinChars.isNotEmpty(),
                colors  = ButtonDefaults.buttonColors(
                    containerColor = Burgundy,
                    contentColor   = White,
                    disabledContainerColor = BurgundyDark,
                    disabledContentColor   = TextSecondary,
                ),
                shape = RoundedCornerShape(Radii.Md),
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
            TextButton(onClick = onCancel) {
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
