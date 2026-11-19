// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.vault

import android.content.res.Resources
import fr.techtical.nextsh.R
import fr.techtical.nextsh.testutil.StringResourceFile
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * The sentence of the dialog that confirms the deletion of a key, built by [keyDeletionMessage]
 * from the real resource files in French and in English: [Catalog] stands in for the Android
 * [Resources], reading `values/strings.xml` and `values-en/strings.xml` and picking the plural
 * form the way each language does (French counts 0 and 1 as "one", English only 1).
 */
class KeyDeletionMessageTest {

    private class Catalog(path: String, private val isOne: (Int) -> Boolean) {
        private val file = StringResourceFile(path)
        private val strings: Map<String, String> = file.strings
        private val plurals: Map<String, Map<String, String>> = file.plurals

        private fun unescape(text: String) = StringResourceFile.unescape(text)

        private fun nameOf(group: Class<*>, id: Int): String =
            group.fields.first { it.type == Int::class.javaPrimitiveType && it.getInt(null) == id }.name

        private fun format(template: String, args: List<Any?>): String =
            if (args.isEmpty()) template else String.format(Locale.ROOT, template, *args.toTypedArray())

        /** The varargs reach the answer as an array, or already spread: both are flattened. */
        private fun spread(raw: List<Any?>): List<Any?> = raw.flatMap { if (it is Array<*>) it.toList() else listOf(it) }

        val resources: Resources = mockk {
            every { getString(any<Int>()) } answers {
                val name = nameOf(R.string::class.java, firstArg())
                format(unescape(strings.getValue(name)), emptyList())
            }
            every { getString(any<Int>(), *anyVararg()) } answers {
                val name = nameOf(R.string::class.java, firstArg())
                format(unescape(strings.getValue(name)), spread(args.drop(1)))
            }
            every { getQuantityString(any<Int>(), any<Int>()) } answers {
                val name = nameOf(R.plurals::class.java, firstArg())
                val form = if (isOne(secondArg())) "one" else "other"
                format(unescape(plurals.getValue(name).getValue(form)), emptyList())
            }
            every { getQuantityString(any<Int>(), any<Int>(), *anyVararg()) } answers {
                val name = nameOf(R.plurals::class.java, firstArg())
                val form = if (isOne(secondArg())) "one" else "other"
                format(unescape(plurals.getValue(name).getValue(form)), spread(args.drop(2)))
            }
        }
    }

    private val french = Catalog("values/strings.xml") { it == 0 || it == 1 }
    private val english = Catalog("values-en/strings.xml") { it == 1 }

    private fun pending(
        hosts: List<String> = emptyList(),
        others: Int = 0,
        tunnels: Int = 0,
        label: String = "id_ed25519",
    ) = VaultViewModel.PendingKeyDeletion(
        keyId = "key-1",
        keyLabel = label,
        hostNames = hosts,
        otherHostCount = others,
        tunnelCount = tunnels,
    )

    @Test
    fun `a key nobody uses keeps the plain sentence, in both languages`() {
        assertEquals(
            "« id_ed25519 » sera supprimée définitivement du vault.",
            keyDeletionMessage(french.resources, pending()),
        )
        assertEquals(
            "\"id_ed25519\" will be permanently deleted from the vault.",
            keyDeletionMessage(english.resources, pending()),
        )
    }

    @Test
    fun `two hosts and their tunnels read like the example, in French`() {
        assertEquals(
            "« id_ed25519 » sera supprimée définitivement du vault. " +
                "Elle sert à 2 hôtes : db-02, web-01 (et à 3 tunnels qui passent par eux). " +
                "Ces hôtes ne pourront plus se connecter tant que vous ne leur aurez pas choisi une autre clé.",
            keyDeletionMessage(french.resources, pending(hosts = listOf("db-02", "web-01"), tunnels = 3)),
        )
    }

    @Test
    fun `two hosts and their tunnels, in English`() {
        assertEquals(
            "\"id_ed25519\" will be permanently deleted from the vault. " +
                "It is used by 2 hosts: db-02, web-01 (and 3 tunnels that go through them). " +
                "These hosts will no longer be able to connect until you choose another key for them.",
            keyDeletionMessage(english.resources, pending(hosts = listOf("db-02", "web-01"), tunnels = 3)),
        )
    }

    @Test
    fun `one host is singular all the way, the pronoun of the tunnels included`() {
        assertEquals(
            "« id_ed25519 » sera supprimée définitivement du vault. " +
                "Elle sert à 1 hôte : web-01 (et à 1 tunnel qui passe par lui). " +
                "Cet hôte ne pourra plus se connecter tant que vous ne lui aurez pas choisi une autre clé.",
            keyDeletionMessage(french.resources, pending(hosts = listOf("web-01"), tunnels = 1)),
        )
        assertEquals(
            "\"id_ed25519\" will be permanently deleted from the vault. " +
                "It is used by 1 host: web-01 (and 1 tunnel that goes through it). " +
                "This host will no longer be able to connect until you choose another key for it.",
            keyDeletionMessage(english.resources, pending(hosts = listOf("web-01"), tunnels = 1)),
        )
    }

    @Test
    fun `one host with several tunnels uses lui in French and it in English`() {
        assertTrue("(et à 2 tunnels qui passent par lui)" in keyDeletionMessage(french.resources, pending(hosts = listOf("web-01"), tunnels = 2)))
        assertTrue("(and 2 tunnels that go through it)" in keyDeletionMessage(english.resources, pending(hosts = listOf("web-01"), tunnels = 2)))
    }

    @Test
    fun `no tunnel means no parenthesis, and the sentence still ends with a full stop`() {
        val fr = keyDeletionMessage(french.resources, pending(hosts = listOf("web-01", "db-02")))
        val en = keyDeletionMessage(english.resources, pending(hosts = listOf("web-01", "db-02")))

        assertEquals(
            "« id_ed25519 » sera supprimée définitivement du vault. Elle sert à 2 hôtes : web-01, db-02. " +
                "Ces hôtes ne pourront plus se connecter tant que vous ne leur aurez pas choisi une autre clé.",
            fr,
        )
        assertFalse("(" in fr || "(" in en, "no parenthesis without a tunnel")
        assertTrue("It is used by 2 hosts: web-01, db-02. These hosts will" in en, en)
    }

    @Test
    fun `three hosts are named and no other is mentioned`() {
        val fr = keyDeletionMessage(french.resources, pending(hosts = listOf("a", "b", "c")))

        // The list ends at the full stop: no "et N autres" after the third name.
        assertTrue("Elle sert à 3 hôtes : a, b, c. Ces hôtes ne pourront" in fr, fr)
        assertTrue("It is used by 3 hosts: a, b, c. These hosts will" in keyDeletionMessage(english.resources, pending(hosts = listOf("a", "b", "c"))))
    }

    @Test
    fun `five hosts name three and count two others, in both languages`() {
        val fr = keyDeletionMessage(french.resources, pending(hosts = listOf("a", "b", "c"), others = 2, tunnels = 4))
        val en = keyDeletionMessage(english.resources, pending(hosts = listOf("a", "b", "c"), others = 2, tunnels = 4))

        assertTrue("Elle sert à 5 hôtes : a, b, c et 2 autres (et à 4 tunnels qui passent par eux)." in fr, fr)
        assertTrue("It is used by 5 hosts: a, b, c and 2 others (and 4 tunnels that go through them)." in en, en)
    }

    @Test
    fun `a single other host is singular`() {
        val fr = keyDeletionMessage(french.resources, pending(hosts = listOf("a", "b", "c"), others = 1))
        val en = keyDeletionMessage(english.resources, pending(hosts = listOf("a", "b", "c"), others = 1))

        assertTrue("Elle sert à 4 hôtes : a, b, c et 1 autre." in fr, fr)
        assertTrue("It is used by 4 hosts: a, b, c and 1 other." in en, en)
    }

    @Test
    fun `a failed check keeps the plain sentence and adds the line that says so, in both languages`() {
        val failed = VaultViewModel.PendingKeyDeletion(keyId = "key-1", keyLabel = "id_ed25519", usageCheckFailed = true)

        assertEquals(
            "« id_ed25519 » sera supprimée définitivement du vault. Impossible de vérifier si des hôtes utilisent cette clé.",
            keyDeletionMessage(french.resources, failed),
        )
        assertEquals(
            "\"id_ed25519\" will be permanently deleted from the vault. Could not check whether any hosts use this key.",
            keyDeletionMessage(english.resources, failed),
        )
    }

    @Test
    fun `a check that worked adds no such line`() {
        assertFalse("Impossible de vérifier" in keyDeletionMessage(french.resources, pending()))
        assertFalse("Impossible de vérifier" in keyDeletionMessage(french.resources, pending(hosts = listOf("a"))))
        assertFalse("Could not check" in keyDeletionMessage(english.resources, pending(hosts = listOf("a"), tunnels = 2)))
    }

    @Test
    fun `the label of the key and of the hosts is shown as it is`() {
        val text = keyDeletionMessage(
            english.resources,
            pending(hosts = listOf("Prod %1\$s <b>"), label = "100% \"my\" key"),
        )

        assertTrue("\"100% \"my\" key\" will be" in text, text)
        assertTrue("It is used by 1 host: Prod %1\$s <b>." in text, text)
    }

    @Test
    fun `the sentences never hold a dash or a raw escape`() {
        val samples = listOf(
            pending(),
            pending(hosts = listOf("a")),
            pending(hosts = listOf("a", "b"), tunnels = 2),
            pending(hosts = listOf("a", "b", "c"), others = 3, tunnels = 1),
        )
        samples.forEach { sample ->
            listOf(french, english).forEach { catalog ->
                val text = keyDeletionMessage(catalog.resources, sample)
                assertFalse(text.any { it.code == 0x2014 || it.code == 0x2013 }, text)
                assertFalse("\\" in text, text)
            }
        }
    }
}
