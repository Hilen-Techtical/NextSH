// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.components

import androidx.compose.runtime.Composable
import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.file_size_bytes
import fr.techtical.nextsh.desktop.generated.resources.file_size_gb
import fr.techtical.nextsh.desktop.generated.resources.file_size_kb
import fr.techtical.nextsh.desktop.generated.resources.file_size_mb
import java.util.Locale
import org.jetbrains.compose.resources.stringResource

/**
 * Human readable size of a file or a transfer, with the unit of the language of the app
 * (Ko / Mo / Go in French, KB / MB / GB in English). Single source for the SFTP browser and the
 * transfers screen: the number is formatted here, the unit comes from the string resources.
 */
internal enum class FileSizeUnit { BYTES, KB, MB, GB }

/** Unit and number text of [bytes], the decimal separator being the one of [locale]. */
internal fun fileSizeParts(bytes: Long, locale: Locale): Pair<FileSizeUnit, String> {
    if (bytes < 1024) return FileSizeUnit.BYTES to bytes.toString()
    val kb = bytes / 1024.0
    if (kb < 1024) return FileSizeUnit.KB to "%.1f".format(locale, kb)
    val mb = kb / 1024.0
    if (mb < 1024) return FileSizeUnit.MB to "%.1f".format(locale, mb)
    return FileSizeUnit.GB to "%.2f".format(locale, mb / 1024.0)
}

@Composable
internal fun formatFileSize(bytes: Long): String {
    // The locale of the texts really loaded, not the one of the JVM (same rule as the dates).
    val (unit, number) = fileSizeParts(bytes, appDateLocale())
    return stringResource(
        when (unit) {
            FileSizeUnit.BYTES -> Res.string.file_size_bytes
            FileSizeUnit.KB -> Res.string.file_size_kb
            FileSizeUnit.MB -> Res.string.file_size_mb
            FileSizeUnit.GB -> Res.string.file_size_gb
        },
        number,
    )
}
