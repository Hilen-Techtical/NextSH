// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.vault

import fr.techtical.nextsh.shared.util.errorClass
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Result of reading a private key file: its text, a file past the bound, or a read that failed. */
internal sealed interface KeyFileRead {
    data class Content(val text: String) : KeyFileRead
    data object TooLarge : KeyFileRead
    data object Failed : KeyFileRead
}

/**
 * Reads [stream] as UTF-8 text, counting the bytes as they come: the size announced by a document
 * provider is never trusted. Stops with [KeyFileRead.TooLarge] at the first byte past [maxBytes], so
 * a huge file is never held in memory. A null stream or an I/O failure is [KeyFileRead.Failed]
 * (class only in the log: the message of an I/O failure can quote the path).
 * Blocking: call it off the main thread.
 */
internal fun readKeyFile(stream: InputStream?, maxBytes: Int): KeyFileRead {
    if (stream == null) return KeyFileRead.Failed
    return try {
        stream.use { input ->
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(KEY_FILE_CHUNK_BYTES)
            var total = 0
            while (true) {
                val read = input.read(chunk)
                if (read == -1) break
                total += read
                if (total > maxBytes) return KeyFileRead.TooLarge
                buffer.write(chunk, 0, read)
            }
            KeyFileRead.Content(buffer.toString(Charsets.UTF_8.name()))
        }
    } catch (e: java.io.IOException) {
        Timber.e("Key file read failed (${errorClass(e)})")
        KeyFileRead.Failed
    } catch (e: SecurityException) {
        Timber.e("Key file read failed (${errorClass(e)})")
        KeyFileRead.Failed
    }
}

private const val KEY_FILE_CHUNK_BYTES = 8192
