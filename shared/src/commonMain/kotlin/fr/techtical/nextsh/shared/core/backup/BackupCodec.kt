// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Outcome of [BackupCodec.decode]. No case carries a message or a cause. */
sealed interface BackupDecodeResult {

    /**
     * The document was decoded. [unknownTopLevelKeys] are the top-level keys this reader does not know
     * (data of a newer additive version); the rest of the file is decoded normally. The key names come
     * from the file: use them as a flag only, never show or log them.
     */
    class Decoded(
        val document: BackupDocument,
        val unknownTopLevelKeys: Set<String>,
    ) : BackupDecodeResult {
        val hasUnknownSections: Boolean get() = unknownTopLevelKeys.isNotEmpty()

        // Hand written: the default one would print the whole document, secrets included.
        override fun toString(): String = "Decoded"
    }

    /** Not UTF-8, not JSON, not an object, wrong structure or a version of 0 or less. */
    data object Malformed : BackupDecodeResult

    /** `version` is 3 or more: the file comes from a newer NextSH ("update the application"). */
    class UnsupportedVersion(val version: Int) : BackupDecodeResult {
        override fun toString(): String = "UnsupportedVersion"
    }
}

/**
 * Encodes and decodes the backup JSON.
 *
 * Decoding is in two steps: the text is parsed to a JSON object first, the `version` and the
 * top-level keys are read from it, and only then the DTO is decoded. This way a file of a newer
 * version is reported as such even when its sections no longer have the v2 structure.
 *
 * SECURITY: the messages of the kotlinx.serialization exceptions copy the input JSON, so the
 * decrypted passwords. Every exception is swallowed here and replaced by a content-free result.
 */
object BackupCodec {

    // encodeDefaults stays off, like the 1.0.x writers: only `version` is forced (see BackupDocument).
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    /** Encodes [document]; the `version` key is ALWAYS written and always equals [BackupFormat.CURRENT_VERSION]. */
    fun encode(document: BackupDocument): String =
        json.encodeToString(BackupDocument.serializer(), document.copy(version = BackupFormat.CURRENT_VERSION))

    /** Same as [encode], as UTF-8 bytes (wipe the result after encrypting it). */
    fun encodeToBytes(document: BackupDocument): ByteArray = encode(document).encodeToByteArray()

    /** Decodes UTF-8 [bytes]. Invalid UTF-8 is [BackupDecodeResult.Malformed]. */
    fun decode(bytes: ByteArray): BackupDecodeResult {
        val text = try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (e: Exception) {
            return BackupDecodeResult.Malformed
        } catch (e: Error) {
            // OutOfMemoryError on a huge input.
            return BackupDecodeResult.Malformed
        }
        return decode(text)
    }

    /** Decodes [text]. */
    fun decode(text: String): BackupDecodeResult {
        val root = try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            return BackupDecodeResult.Malformed
        } catch (e: Error) {
            // OutOfMemoryError or StackOverflowError (common code has no names for them): a hostile
            // file can ask for more memory than there is while the JSON tree is built.
            return BackupDecodeResult.Malformed
        }
        if (root !is JsonObject) return BackupDecodeResult.Malformed

        val version = when (val raw = root["version"]) {
            null -> BackupFormat.DEFAULT_VERSION
            is JsonPrimitive -> if (raw.isString) null else raw.intOrNull
            else -> null
        } ?: return BackupDecodeResult.Malformed
        if (version <= 0) return BackupDecodeResult.Malformed
        if (version > BackupFormat.MAX_READABLE_VERSION) return BackupDecodeResult.UnsupportedVersion(version)

        val document = try {
            json.decodeFromJsonElement(BackupDocument.serializer(), root)
        } catch (e: Exception) {
            return BackupDecodeResult.Malformed
        } catch (e: Error) {
            return BackupDecodeResult.Malformed
        }
        val unknown = root.keys.filterTo(LinkedHashSet<String>()) { it !in BackupFormat.KNOWN_TOP_LEVEL_KEYS }
        return BackupDecodeResult.Decoded(document, unknown)
    }
}
