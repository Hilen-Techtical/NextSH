// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sync

import fr.techtical.nextsh.R
import fr.techtical.nextsh.core.sync.syncErrorLabelRes
import fr.techtical.nextsh.shared.core.sync.SyncError
import fr.techtical.nextsh.shared.core.sync.SyncableEntityType
import fr.techtical.nextsh.shared.util.RelativeTime
import fr.techtical.nextsh.testutil.AccentedLiteralScanner
import fr.techtical.nextsh.testutil.FrenchTuForm
import fr.techtical.nextsh.testutil.StringResourceFile
import fr.techtical.nextsh.ui.SourceGuardSupport
import fr.techtical.nextsh.ui.components.relativeTimeAgoRes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The words of the sync screens (conflicts, enrolled devices, the last sync error of the settings)
 * used to be literals in the code, in French: an English phone read "Détecté 5 minutes ago",
 * "LAST SYNC" between French lines, "Ignorer ce conflit". They come from resources now, in French
 * (the default) and in English, and this guards that they stay there.
 */
class SyncScreensStringsTest {

    private val french = StringResourceFile("values/strings.xml")
    private val english = StringResourceFile("values-en/strings.xml")

    private fun placeholders(text: String) = Regex("""%\d\$[sd]""").findAll(text).map { it.value }.toSet()

    /** An em dash (U+2014) or an en dash (U+2013), by code point so that this file holds none. */
    private fun holdsDash(text: String) = text.any { it.code == 0x2014 || it.code == 0x2013 }

    private val noArgument = emptySet<String>()
    private val stringArg = setOf("%1\$s")
    private val intArg = setOf("%1\$d")

    /** Name to the placeholders it takes. */
    private val newStrings: Map<String, Set<String>> = mapOf(
        // The last sync error, composed by AndroidSyncScheduler (%1$s is the device name).
        "sync_error_load_devices" to noArgument,
        // The vault cannot be read: not about one device, so no device name.
        "sync_error_vault_locked" to noArgument,
        "sync_error_device_unreachable" to stringArg,
        "sync_error_device_auth_failed" to stringArg,
        "sync_error_decrypt_failed" to stringArg,
        "sync_error_hmac_invalid" to stringArg,
        "sync_error_timestamp_drift" to stringArg,
        "sync_error_version_mismatch" to stringArg,
        "sync_error_network_timeout" to stringArg,
        "sync_error_server_error" to stringArg,
        "sync_error_unknown" to stringArg,
        // The conflict screen.
        "conflict_subtitle_resolved" to noArgument,
        "conflict_detected_at" to stringArg,
        // Past a week the time is a date: "Detected on <date>", not "Detected <date>".
        "conflict_detected_on" to stringArg,
        "conflict_corrupted" to noArgument,
        "action_ignore_conflict" to noArgument,
        "conflict_empty_title" to noArgument,
        "conflict_entity_host" to noArgument,
        "conflict_entity_tunnel" to noArgument,
        "conflict_entity_ssh_key" to noArgument,
        "conflict_entity_snippet" to noArgument,
        "conflict_entity_credential" to noArgument,
        "conflict_entity_custom_terminal_theme" to noArgument,
        // The language of a date written past a week (RelativeTimeText): the code of each resource file.
        "relative_date_language" to noArgument,
        // The card of an enrolled device.
        "enrolled_meta_host" to noArgument,
        "enrolled_meta_enrolled" to noArgument,
        "enrolled_meta_last_sync" to noArgument,
    )

    /** Strings that existed before and were corrected: still checked for the dash, the apostrophes, the "vous". */
    private val correctedStrings = listOf("dialog_revoke_message", "enrolled_status_error")

    private val newPlurals = listOf("conflict_subtitle_pending", "enrolled_devices_count")

    /** The ones that read the same in both languages: a product word, a protocol name. */
    private val sameInBothLanguages = setOf(
        "conflict_entity_tunnel",
        "conflict_entity_snippet",
        "conflict_entity_credential",
    )

    /**
     * Keys that nothing used any more (SyncBanner was never shown, the card labels became enrolled_meta_*,
     * a lost address says the sentence of an unreachable device).
     */
    private val removedKeys = listOf(
        "sync_error_host_unreachable",
        "conflict_banner_pending",
        "sync_pending_conflicts",
        "sync_banner_syncing",
        "sync_banner_error",
        "sync_banner_offline",
        "sync_banner_synced_relative",
        "enrolled_host_label",
        "enrolled_ecdh_label",
        "enrolled_tls_label",
        "enrolled_enrolled_label",
        "enrolled_last_sync_label",
    )

    private fun everyFrenchText(): List<Pair<String, String>> =
        newStrings.keys.map { it to french.strings.getValue(it) } +
            correctedStrings.map { it to french.strings.getValue(it) } +
            newPlurals.flatMap { name -> french.plurals.getValue(name).map { (quantity, text) -> "$name/$quantity" to text } }

    private fun everyEnglishText(): List<Pair<String, String>> =
        newStrings.keys.map { it to english.strings.getValue(it) } +
            correctedStrings.map { it to english.strings.getValue(it) } +
            newPlurals.flatMap { name -> english.plurals.getValue(name).map { (quantity, text) -> "$name/$quantity" to text } }

    // ── The resources ─────────────────────────────────────────────────────────

    @Test
    fun `every new string exists in French and in English`() {
        assertEquals(emptySet<String>(), newStrings.keys - french.strings.keys, "missing from French")
        assertEquals(emptySet<String>(), newStrings.keys - english.strings.keys, "missing from English")
        assertEquals(emptySet<String>(), newPlurals.toSet() - french.plurals.keys, "plurals missing from French")
        assertEquals(emptySet<String>(), newPlurals.toSet() - english.plurals.keys, "plurals missing from English")
    }

    @Test
    fun `a new string takes the arguments it is called with, in both languages`() {
        newStrings.forEach { (name, expected) ->
            assertEquals(expected, placeholders(french.strings.getValue(name)), "placeholders of $name in French")
            assertEquals(expected, placeholders(english.strings.getValue(name)), "placeholders of $name in English")
        }
    }

    @Test
    fun `a new string is not empty, and is translated unless it reads the same in both languages`() {
        newStrings.keys.forEach { name ->
            val fr = french.strings.getValue(name)
            val en = english.strings.getValue(name)
            assertFalse(fr.isBlank() || en.isBlank(), "$name must not be empty")
            if (name in sameInBothLanguages) {
                assertEquals(fr, en, "$name is listed as the same in both languages")
            } else {
                assertNotEquals(fr, en, "$name is not translated")
            }
        }
    }

    @Test
    fun `the plurals have one and other with the number as argument, and no number written in one`() {
        for ((language, file) in listOf("fr" to french, "en" to english)) {
            for (name in newPlurals) {
                val items = file.plurals.getValue(name)
                assertTrue("one" in items && "other" in items, "$name ($language) needs one and other: ${items.keys}")
                items.forEach { (quantity, text) ->
                    assertEquals(intArg, placeholders(text), "$name/$quantity ($language)")
                }
                // French reads 0 as "one" too: the zero case is handled by the code, and a number
                // written in "one" would be wrong for it.
                assertFalse(Regex("""(?<![\d%])1(?!\$)\b""").containsMatchIn(items.getValue("one")), "$name ($language): a 1 written in one")
            }
        }
    }

    @Test
    fun `the counters read right in French and in English`() {
        fun render(file: StringResourceFile, name: String, quantity: String, n: Int) =
            StringResourceFile.unescape(file.plurals.getValue(name).getValue(quantity)).replace("%1\$d", "$n")

        assertEquals("1 conflit à résoudre", render(french, "conflict_subtitle_pending", "one", 1))
        assertEquals("3 conflits à résoudre", render(french, "conflict_subtitle_pending", "other", 3))
        assertEquals("1 conflict to resolve", render(english, "conflict_subtitle_pending", "one", 1))
        assertEquals("3 conflicts to resolve", render(english, "conflict_subtitle_pending", "other", 3))

        assertEquals("1 appareil enrôlé", render(french, "enrolled_devices_count", "one", 1))
        assertEquals("2 appareils enrôlés", render(french, "enrolled_devices_count", "other", 2))
        assertEquals("1 enrolled device", render(english, "enrolled_devices_count", "one", 1))
        assertEquals("2 enrolled devices", render(english, "enrolled_devices_count", "other", 2))

        // The zero cases are sentences of their own.
        assertEquals("Tous les conflits sont résolus", french.strings.getValue("conflict_subtitle_resolved"))
        assertEquals("All conflicts are resolved", english.strings.getValue("conflict_subtitle_resolved"))
        assertEquals("Aucun appareil enrôlé", french.strings.getValue("enrolled_empty"))
        assertEquals("No enrolled devices", english.strings.getValue("enrolled_empty"))
    }

    @Test
    fun `the words that were written in the code are in both languages`() {
        assertEquals("Détecté %1\$s", french.strings.getValue("conflict_detected_at"))
        assertEquals("Detected %1\$s", english.strings.getValue("conflict_detected_at"))
        assertEquals("Détecté le %1\$s", french.strings.getValue("conflict_detected_on"))
        assertEquals("Detected on %1\$s", english.strings.getValue("conflict_detected_on"))
        assertEquals("Ignorer ce conflit", french.strings.getValue("action_ignore_conflict"))
        assertEquals("Ignore this conflict", english.strings.getValue("action_ignore_conflict"))
        assertEquals("Tout est synchronisé", french.strings.getValue("conflict_empty_title"))
        assertEquals("Everything is in sync", english.strings.getValue("conflict_empty_title"))
        assertEquals("Conflit corrompu : données illisibles", french.strings.getValue("conflict_corrupted"))
        assertEquals("Corrupted conflict: unreadable data", english.strings.getValue("conflict_corrupted"))
        assertEquals("Dernière sync", french.strings.getValue("enrolled_meta_last_sync"))
        assertEquals("Last sync", english.strings.getValue("enrolled_meta_last_sync"))
        assertEquals("Hôte", french.strings.getValue("enrolled_meta_host"))
        assertEquals("Host", english.strings.getValue("enrolled_meta_host"))
        assertEquals("Enrôlé", french.strings.getValue("enrolled_meta_enrolled"))
        assertEquals("Enrolled", english.strings.getValue("enrolled_meta_enrolled"))
    }

    @Test
    fun `the corrected strings are corrected`() {
        assertEquals("Échec de la révocation", french.strings.getValue("enrolled_status_error"))
        assertEquals(
            "Le secret partagé sera effacé. Vous devrez ré-enrôler cet appareil pour synchroniser à nouveau.",
            french.strings.getValue("dialog_revoke_message"),
        )
    }

    @Test
    fun `no sync string holds an em dash or an en dash`() {
        (everyFrenchText() + everyEnglishText()).forEach { (name, text) ->
            assertFalse(holdsDash(text), "$name holds a dash: $text")
        }
    }

    @Test
    fun `the French sync strings address the user as vous, never tu`() {
        everyFrenchText().forEach { (name, text) ->
            val tu = FrenchTuForm.find(text)
            assertFalse(tu != null, "$name uses the tu form (\"$tu\"): $text")
        }
    }

    @Test
    fun `the apostrophes of the sync strings are escaped for aapt2`() {
        listOf("fr" to everyFrenchText(), "en" to everyEnglishText()).forEach { (language, texts) ->
            texts.forEach { (name, text) ->
                assertFalse(Regex("""(?<!\\)'""").containsMatchIn(text), "$name ($language) has an unescaped apostrophe: $text")
            }
        }
    }

    @Test
    fun `the keys nothing used any more are gone from both languages`() {
        removedKeys.forEach { name ->
            assertFalse(name in french.strings, "$name is still in French")
            assertFalse(name in english.strings, "$name is still in English")
            assertFalse(name in french.plurals, "$name is still in French (plurals)")
            assertFalse(name in english.plurals, "$name is still in English (plurals)")
        }
        // The ones the screens still read stay.
        listOf("sync_status_error", "sync_last_sync_at", "enrolled_empty", "enrolled_last_sync_never", "conflict_empty")
            .forEach { name ->
                assertTrue(name in french.strings, "$name must stay in French")
                assertTrue(name in english.strings, "$name must stay in English")
            }
    }

    // ── The labels derived from the enums ─────────────────────────────────────

    private fun nameOfString(id: Int): String =
        R.string::class.java.fields.first { it.type == Int::class.javaPrimitiveType && it.getInt(null) == id }.name

    @Test
    fun `every type of conflicting item has a word of its own`() {
        val labels = SyncableEntityType.entries.associateWith { conflictEntityLabelRes(it) }

        assertEquals(labels.size, labels.values.toSet().size, "two types share a word: $labels")
        // The resource is the one named after the type, so a swap between two types is caught.
        labels.forEach { (type, id) ->
            assertEquals("conflict_entity_${type.name.lowercase()}", nameOfString(id))
            assertTrue(nameOfString(id) in french.strings && nameOfString(id) in english.strings)
        }
    }

    @Test
    fun `every reason of a sync failure has a sentence of its own, a lost address having the one of an unreachable device`() {
        val sentences = SyncError.entries.associateWith { syncErrorLabelRes(it) }

        assertEquals(sentences.size, sentences.values.toSet().size, "two reasons share a sentence: $sentences")
        sentences.forEach { (reason, id) ->
            // HOST_UNREACHABLE has no sentence of its own: it is the one of SyncResult.DeviceUnreachable.
            val expectedName = if (reason == SyncError.HOST_UNREACHABLE) {
                "sync_error_device_unreachable"
            } else {
                "sync_error_${reason.name.lowercase()}"
            }
            assertEquals(expectedName, nameOfString(id))
            // Each takes the device name, in both languages.
            assertEquals(stringArg, placeholders(french.strings.getValue(nameOfString(id))), "$reason (fr)")
            assertEquals(stringArg, placeholders(english.strings.getValue(nameOfString(id))), "$reason (en)")
        }
    }

    @Test
    fun `a sync error follows the colon of the status line without adding another one`() {
        // "Erreur : %1$s" shows the sentence: the device name is inside it, never followed by a colon
        // (it used to read "Erreur : PC : inaccessible").
        val syncErrors = newStrings.keys.filter { it.startsWith("sync_error_") }
        assertTrue(syncErrors.size >= 10, "the sync errors are not found: $syncErrors")
        for ((language, file) in listOf("fr" to french, "en" to english)) {
            val status = StringResourceFile.unescape(file.strings.getValue("sync_status_error"))
            syncErrors.forEach { name ->
                val sentence = StringResourceFile.unescape(file.strings.getValue(name))
                assertFalse(sentence.contains(':'), "$name ($language) holds a colon: $sentence")
                val shown = status.replace("%1\$s", sentence)
                assertEquals(1, shown.count { it == ':' }, "$name ($language): $shown")
            }
        }
    }

    @Test
    fun `the sentences that can be acted on say what to do`() {
        assertEquals(
            "%1\$s injoignable. Vérifiez que le Desktop est ouvert et sur le même réseau.",
            StringResourceFile.unescape(french.strings.getValue("sync_error_device_unreachable")),
        )
        assertEquals(
            "%1\$s unreachable. Check that the Desktop is open and on the same network.",
            StringResourceFile.unescape(english.strings.getValue("sync_error_device_unreachable")),
        )
        assertEquals(
            "%1\$s a refusé la connexion. Vérifiez l'heure des deux appareils, sinon ré-enrôlez-le.",
            StringResourceFile.unescape(french.strings.getValue("sync_error_device_auth_failed")),
        )
        assertEquals(
            "%1\$s refused the connection. Check the time on both devices, otherwise re-enroll it.",
            StringResourceFile.unescape(english.strings.getValue("sync_error_device_auth_failed")),
        )
        assertEquals("%1\$s ne répond pas", StringResourceFile.unescape(french.strings.getValue("sync_error_network_timeout")))
        assertEquals("%1\$s is not responding", StringResourceFile.unescape(english.strings.getValue("sync_error_network_timeout")))
        assertEquals("données illisibles reçues de %1\$s", StringResourceFile.unescape(french.strings.getValue("sync_error_decrypt_failed")))
        assertEquals("unreadable data received from %1\$s", StringResourceFile.unescape(english.strings.getValue("sync_error_decrypt_failed")))
        assertEquals("échec de la synchronisation avec %1\$s", StringResourceFile.unescape(french.strings.getValue("sync_error_unknown")))
        assertEquals("sync with %1\$s failed", StringResourceFile.unescape(english.strings.getValue("sync_error_unknown")))
        // The clocks and the version point at the two devices, not at the network.
        assertTrue(StringResourceFile.unescape(french.strings.getValue("sync_error_timestamp_drift")).contains("date et l'heure des deux appareils"))
        assertTrue(english.strings.getValue("sync_error_timestamp_drift").contains("date and time on both devices"))
        assertTrue(french.strings.getValue("sync_error_version_mismatch").contains("Mettez NextSH à jour"))
        assertTrue(english.strings.getValue("sync_error_version_mismatch").contains("Update NextSH"))
    }

    @Test
    fun `every unit of the relative time has its own resource, said the same way in both languages`() {
        val units = RelativeTime.Unit.entries.associateWith { relativeTimeAgoRes(it) }

        assertEquals(units.size, units.values.toSet().size, "two units share a resource: $units")
        units.forEach { (unit, id) ->
            val name = nameOfString(id)
            assertEquals("relative_${unit.name.lowercase()}_ago", name)
            assertEquals(intArg, placeholders(french.strings.getValue(name)), "$name (fr)")
            assertEquals(intArg, placeholders(english.strings.getValue(name)), "$name (en)")
            assertTrue(french.strings.getValue(name).startsWith("il y a "), "$name (fr): ${french.strings.getValue(name)}")
            assertTrue(english.strings.getValue(name).endsWith(" ago"), "$name (en): ${english.strings.getValue(name)}")
        }
    }

    @Test
    fun `each resource file says its own language, which the date is written in`() {
        // Not a text to translate but the code of the file: the date follows the resources shown, not the phone.
        assertEquals("fr", french.strings.getValue("relative_date_language"))
        assertEquals("en", english.strings.getValue("relative_date_language"))
    }

    // ── No French left in the code of the screens ─────────────────────────────

    @Test
    fun `no French word is written in the code of the sync screens`() {
        assertEquals(emptyList<String>(), AccentedLiteralScanner.scan("ui/sync"), "a French literal in a sync screen")
    }

    @Test
    fun `no French word is written in the sync scheduler nor in the settings screen`() {
        assertEquals(
            emptyList<String>(),
            AccentedLiteralScanner.scanFile("core/sync/AndroidSyncScheduler.kt"),
            "a French literal in the scheduler: it shows in the settings as the last error",
        )
        assertEquals(
            emptyList<String>(),
            AccentedLiteralScanner.scanFile("ui/settings/SettingsScreen.kt"),
            "a French literal in the settings screen",
        )
    }

    /** The scanner only sees accents: the words that carry none are checked by name. */
    @Test
    fun `the words the sync screens used to write are not in their code any more`() {
        val screens = SourceGuardSupport.mainSources("ui/sync")
        val scheduler = SourceGuardSupport.mainSource("core/sync/AndroidSyncScheduler.kt")
        val settings = SourceGuardSupport.mainSource("ui/settings/SettingsScreen.kt")

        // SyncBanner was never shown and said "erreur inconnue" in code: it is gone.
        assertFalse("ui/sync/SyncBanner.kt" in screens, "SyncBanner.kt must stay deleted")
        screens.forEach { (path, source) ->
            assertFalse(source.contains("erreur inconnue"), "$path")
            assertFalse(source.contains("conflit(s)"), "$path")
            assertFalse(source.contains("DateUtils"), "$path reads the time with the system language")
        }
        val conflictScreen = screens.getValue("ui/sync/ConflictResolutionScreen.kt")
        listOf("Ignorer ce conflit", "Détecté", "Conflit corrompu", "Tout est synchronisé", "displayName")
            .forEach { assertFalse(conflictScreen.contains(it), "ConflictResolutionScreen still writes $it") }
        // A date reads "Detected on <date>": the screen picks that sentence when the time is a date.
        assertTrue(
            conflictScreen.contains("R.string.conflict_detected_on") && conflictScreen.contains("R.string.conflict_detected_at"),
            "the conflict screen does not tell a date from a time ago",
        )
        // Both versions unreadable: the card is titled by the kind of item, never by a piece of its identifier.
        assertFalse(conflictScreen.contains("entityId.take"), "the card is titled with a fragment of an identifier")
        assertTrue(
            conflictScreen.contains("?: stringResource(conflictEntityLabelRes(conflict.entityType))"),
            "the card of an unreadable conflict is not titled by the kind of item",
        )

        val enrolledScreen = screens.getValue("ui/sync/EnrolledDevicesScreen.kt")
        listOf("\"LAST SYNC\"", "\"HOST\"", "\"ENROLLED\"", "Aucun appareil enrôlé")
            .forEach { assertFalse(enrolledScreen.contains(it), "EnrolledDevicesScreen still writes $it") }
        // The label column is a floor in dp, never a width in sp converted to dp (it does not follow the
        // font on Android 14+ and cut "DERNIÈRE SYNC" at 130 %), and a long label pushes the value.
        assertFalse(enrolledScreen.contains("MetaLabelWidth"), "the label width in sp is back")
        assertFalse(Regex("""\b\d+\.sp\.toDp|MetaLabel\w*\.toDp""").containsMatchIn(enrolledScreen), "an sp is converted to dp")
        assertTrue(enrolledScreen.contains("widthIn(min = MetaLabelMinWidth)"), "the label column has no floor")
        assertTrue(enrolledScreen.contains("softWrap = false"), "a long label wraps or is cut instead of pushing the value")

        assertFalse(settings.contains("conflit(s)"))
        assertFalse(settings.contains("conflits à résoudre"))
        assertFalse(settings.contains("formatRelativeBucket"), "the relative time is the shared component")
        assertFalse(settings.contains("DateUtils"))

        // The scheduler resolves its texts from resources and never shows the technical message.
        listOf("inaccessible", "authentification échouée", "Impossible de lire", "result.message", "reason.name")
            .forEach { assertFalse(scheduler.contains(it), "AndroidSyncScheduler still has $it") }
    }
}
