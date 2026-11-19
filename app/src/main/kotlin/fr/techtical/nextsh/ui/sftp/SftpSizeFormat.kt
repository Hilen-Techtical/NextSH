// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sftp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import fr.techtical.nextsh.R
import fr.techtical.nextsh.ui.components.dateLocale
import java.util.Locale

/**
 * The four templates that write a size, each with one `%1$s` for the number (`%1$s Ko`, `%1$s KB`).
 * They come from the string resources: the unit is French (o, Ko, Mo, Go) or English (B, KB, MB, GB)
 * as the language of the user.
 */
internal data class SizeUnits(
    val bytes: String,
    val kilobytes: String,
    val megabytes: String,
    val gigabytes: String,
)

/** Largest step of the scale: a size is never written in a unit above gigabytes. */
private const val LAST_UNIT = 3

private const val STEP = 1024.0

/**
 * A size in bytes as a short text, the one rule of the whole browser (list, preview, messages).
 *
 * The base is 1024. Bytes are whole numbers, the other units have one decimal, and the number is
 * written the way [locale] does ("1,5 Ko" in French, "1.5 KB" in English) without a grouping
 * separator. A size whose rounded value would read 1024.0 of a unit moves up to the next one, so
 * that 1048575 bytes is "1,0 Mo" and not "1024,0 Ko".
 *
 * Pure: no `Context`, the templates and the locale are arguments, so that the unit tests of the JVM
 * check it.
 */
internal fun formatFileSize(bytes: Long, units: SizeUnits, locale: Locale): String {
    val size = bytes.coerceAtLeast(0L)
    if (size < STEP) {
        return String.format(locale, units.bytes, String.format(locale, "%d", size))
    }
    var value = size.toDouble()
    var step = 0
    while (value >= STEP && step < LAST_UNIT) {
        value /= STEP
        step++
    }
    // 1048575 bytes is 1023.999 KB, which one decimal rounds to 1024.0: the next unit says it better.
    if (step < LAST_UNIT && Math.round(value * 10) >= (STEP * 10).toLong()) {
        value /= STEP
        step++
    }
    val template = when (step) {
        1 -> units.kilobytes
        2 -> units.megabytes
        else -> units.gigabytes
    }
    return String.format(locale, template, String.format(locale, "%.1f", value))
}

/**
 * The locale of the texts really loaded (see [dateLocale]), not the one of the phone: a phone in
 * German shows the French texts, and its sizes and dates must not be written in German beside them.
 * Read at each composition, so that a change of language is followed.
 */
@Composable
internal fun resourceLocale(): Locale = dateLocale(
    configLocale = LocalConfiguration.current.locales[0],
    resourceLanguage = stringResource(R.string.relative_date_language),
)

/**
 * The size formatter of the screen: reads the unit templates and the locale of the loaded texts, so
 * that a change of language rewrites every size.
 */
@Composable
internal fun rememberSizeFormatter(): (Long) -> String {
    val locale = resourceLocale()
    val units = SizeUnits(
        bytes = stringResource(R.string.sftp_size_bytes),
        kilobytes = stringResource(R.string.sftp_size_kilobytes),
        megabytes = stringResource(R.string.sftp_size_megabytes),
        gigabytes = stringResource(R.string.sftp_size_gigabytes),
    )
    return remember(locale, units) { { bytes: Long -> formatFileSize(bytes, units, locale) } }
}
