// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import fr.techtical.nextsh.ui.theme.Gold
import fr.techtical.nextsh.ui.theme.Radii

/** Mask glyph displayed in place of each entered PIN character. PINs never contain it. */
private const val MASK = '•' // BULLET

/**
 * Applies an edit of the visible text of the masked field to [pinChars], the buffer that holds the
 * PIN. The visible text is only bullets, so a text shorter than the buffer is a deletion (the
 * trailing characters go), and anything else that is not a bullet is a freshly typed (or pasted)
 * character, appended. The full PIN is never assembled into a String.
 */
internal fun applyMaskedPinEdit(pinChars: MutableList<Char>, newText: CharSequence) {
    if (newText.length < pinChars.size) {
        // A deletion/backspace removed bullets: drop matching trailing chars.
        repeat(pinChars.size - newText.length) {
            if (pinChars.isNotEmpty()) pinChars.removeAt(pinChars.lastIndex)
        }
    } else {
        for (c in newText) {
            if (c != MASK) pinChars.add(c)
        }
    }
}

/**
 * A text field for a PIN that never holds it in a String. Wipe-safe masked sink: the secret lives
 * in [pinChars], a mutable char buffer the caller owns (and must clear when it is done with the
 * PIN, when the screen is left included); the field only shows one bullet per character. The
 * secret-bearing materialization is the CharArray the caller builds once, when the PIN is
 * confirmed, and zeroes after use.
 *
 * What the buffer cannot promise: it holds boxed [Char]s. The JVM caches the boxes of the ASCII
 * range (digits, letters and the usual symbols), shared by the whole process, so such an entry is
 * not a copy of the secret. A character outside ASCII is boxed to an object of its own that cannot
 * be erased, and it waits for the garbage collector after the buffer is cleared.
 *
 * The field is a password for accessibility services: TalkBack does not read what is typed.
 * The keyboard is the full one, masked: a FIDO2 PIN is 4 to 63 characters and may hold letters and
 * symbols, the numeric pad would lock out every alphanumeric PIN. Enter validates nothing: a
 * YubiKey touched outside an operation types its OTP then Enter.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MaskedPinField(
    pinChars: SnapshotStateList<Char>,
    colors: TextFieldColors,
    textStyle: TextStyle,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    // The visible text is always a run of MASK bullets matching pinChars.size, cursor at end. It is
    // worked out from the buffer each time, so that clearing the buffer clears the field too.
    val fieldValue = TextFieldValue(
        text = MASK.toString().repeat(pinChars.size),
        selection = TextRange(pinChars.size),
    )
    BasicTextField(
        value = fieldValue,
        onValueChange = { applyMaskedPinEdit(pinChars, it.text) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false),
        textStyle = textStyle,
        cursorBrush = SolidColor(Gold),
        interactionSource = interactionSource,
        // The field has no PasswordVisualTransformation to make it one, so it says it is a password
        // itself: TalkBack then announces a password field and does not read its content aloud.
        modifier = modifier.semantics { password() },
    ) { innerTextField ->
        OutlinedTextFieldDefaults.DecorationBox(
            value = fieldValue.text,
            innerTextField = innerTextField,
            enabled = true,
            singleLine = true,
            visualTransformation = VisualTransformation.None,
            interactionSource = interactionSource,
            label = label,
            colors = colors,
            container = {
                OutlinedTextFieldDefaults.ContainerBox(
                    enabled = true,
                    isError = false,
                    interactionSource = interactionSource,
                    colors = colors,
                    shape = RoundedCornerShape(Radii.Md),
                )
            },
        )
    }
}
