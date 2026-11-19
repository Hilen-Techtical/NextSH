// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guard against texts written straight into the code of the screens that were moved to the
 * string resources (French and English): the host key dialogs, the SFTP browser, the built-in
 * browser overlay, the FIDO2 enrollment, the vault, sync and tunnel forms and the errors of their
 * view models. A hard-coded French text was shown as it is in the English interface.
 *
 * A line is refused when it carries:
 * - a string literal with a French accented letter, or the literal "OK";
 * - a literal that starts a visible text (`Text("`, `text = "`, `title = "`, `contentDescription = "`,
 *   `message = "`, `error = "`, `Error("`) and holds a letter once its templates (`${...}`, `$name`) and
 *   format specifiers (`%04o`) are removed, so `"$count items"` is refused and `"$a / $b"` is not;
 * - a `label = "` or `placeholder = "` literal that looks like a text (a capitalized word or a space):
 *   animation labels and technical hints (`"iconAlpha"`, `"ssh:"`, `"ECDH"`) stay allowed;
 * - a list of capitalized words (`listOf("Read", "Write")`).
 * Comments and log lines are ignored: logs are not shown to the user.
 */
class HardcodedTextGuardTest {

    private val accentedLiteral = Regex("\"[^\"\\n]*[\\u00C0-\\u00D6\\u00D8-\\u00F6\\u00F8-\\u00FF][^\"\\n]*\"")
    private val okLiteral = Regex("\"OK\"")
    private val visibleLiteral = Regex(
        "(Text\\(|text = |title = |label = |placeholder = |contentDescription = |message = |error = |Error\\()\\s*\"([^\"\\n]*)\"",
    )
    private val template = Regex("\\$\\{[^}]*\\}|\\$[A-Za-z_]+")
    private val formatSpecifier = Regex("%[-#+ 0,(]*\\d*(\\.\\d+)?[a-zA-Z]")
    private val hasLetter = Regex("[A-Za-z\\u00C0-\\u00FF]")
    private val looksLikeText = Regex("^[A-Z][a-z].*|.* .*")
    private val capitalizedList = Regex("listOf\\(\\s*\"[A-Z][a-z]+\"\\s*,")

    /** `Text(if (x) "Yes" else "No")`: a literal after the condition or after `else`, on a visible text line. */
    private val ifElseLiteral = Regex(
        "(Text\\(|text = |title = |contentDescription = |message = |error = |label = |placeholder = )[^\\n]*?" +
            "(?:if\\s*\\([^)\\n]*\\)\\s*\"([^\"\\n]*)\"|\\belse\\s+\"([^\"\\n]*)\")",
    )

    /** The previous line opens a visible text (`Text(`, `text =`...) and this one is only a literal. */
    private val opensText = Regex("(Text\\(|text =|title =|contentDescription =|message =|error =|Error\\()\\s*$")
    private val aloneLiteral = Regex("^\"([^\"\\n]*)\"\\s*[,)+]?\\s*\\+?$")

    private fun carriesLetter(literal: String): Boolean =
        hasLetter.containsMatchIn(literal.replace(template, "").replace(formatSpecifier, ""))

    /** Technical literals that are not translated on purpose (middle dot written as an escape). */
    private val technical = setOf("SSH", "SFTP", "PBKDF2 \u00B7 AES-256-GCM")

    private fun isVisibleText(code: String): Boolean = visibleLiteral.findAll(code).any { match ->
        val prefix = match.groupValues[1]
        val literal = match.groupValues[2]
        if (literal in technical) return@any false
        val stripped = literal.replace(template, "").replace(formatSpecifier, "")
        if (!hasLetter.containsMatchIn(stripped)) return@any false
        if (prefix.startsWith("label") || prefix.startsWith("placeholder")) looksLikeText.matches(stripped) else true
    }

    private fun isBranchLiteral(code: String): Boolean = ifElseLiteral.findAll(code).any { match ->
        val literal = match.groupValues[2].ifEmpty { match.groupValues[3] }
        literal !in technical && carriesLetter(literal)
    }

    private fun violations(relative: String, source: String): List<String> {
        val lines = source.lines()
        return lines.mapIndexedNotNull { index, line ->
            val trimmed = line.trim()
            val ignored = trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*") ||
                line.contains("Logger.") || line.contains("Timber")
            val code = line.substringBefore("//")
            val previous = if (index > 0) lines[index - 1].trimEnd() else ""
            val alone = aloneLiteral.find(trimmed)?.let { opensText.containsMatchIn(previous) && carriesLetter(it.groupValues[1]) } ?: false
            val refused = accentedLiteral.containsMatchIn(code) || okLiteral.containsMatchIn(code) ||
                capitalizedList.containsMatchIn(code) || isVisibleText(code) || isBranchLiteral(code) || alone
            if (!ignored && refused) "$relative:${index + 1}: ${trimmed.take(120)}" else null
        }
    }

    // ── Self-check of the scanner ────────────────────────────────────────────

    @Test
    fun `the scanner refuses a French text, the OK literal and a literal text`() {
        assertEquals(1, violations("a.kt", "Text(\"Annuler\")").size)
        assertEquals(1, violations("a.kt", "val m = \"\u00C9chec de l envoi\"").size)
        assertEquals(1, violations("a.kt", "TextButton(onClick = x) { Text(\"OK\") }").size)
        assertEquals(1, violations("a.kt", "title = \"Hello\",").size)
        assertEquals(1, violations("a.kt", "_state.value = State.Error(\"Failed\")").size)
    }

    @Test
    fun `the scanner refuses labels, placeholders, lists of words and texts that start with a template`() {
        assertEquals(1, violations("a.kt", "label = \"Hello world\",").size)
        assertEquals(1, violations("a.kt", "placeholder = \"Search\",").size)
        assertEquals(1, violations("a.kt", "listOf(\"Read\", \"Write\", \"Execute\").forEach {").size)
        assertEquals(1, violations("a.kt", "text = \"\$count items\",").size)
        assertEquals(1, violations("a.kt", "text = \"\${items.size} files\",").size)
        assertEquals(1, violations("a.kt", "label = \"1 selected\",").size)
    }

    @Test
    fun `the scanner refuses a literal in a branch and a literal alone on its line`() {
        assertEquals(1, violations("a.kt", "Text(if (done) \"Yes\" else \"No\")").size)
        assertEquals(1, violations("a.kt", "text = if (done) stringResource(Res.string.a) else \"No\",").size)
        assertEquals(1, violations("a.kt", "Text(\n    \"Cancel\",\n)").size)
        assertEquals(1, violations("a.kt", "val t = Error(\n    \"Failed\"\n)").size)
        assertEquals(1, violations("a.kt", "text =\n    \"Hello\" +\n    x").size)
    }

    @Test
    fun `the scanner accepts branches and lone literals that carry no text`() {
        assertTrue(violations("a.kt", "Text(if (done) stringResource(Res.string.a) else stringResource(Res.string.b))").isEmpty())
        assertTrue(violations("a.kt", "color = if (armed) Gold else TextSecondary,").isEmpty())
        assertTrue(violations("a.kt", "Text(if (x) \"\${a}\" else \"%04o\")").isEmpty())
        assertTrue(violations("a.kt", "Text(\n    stringResource(Res.string.a),\n)").isEmpty())
        assertTrue(violations("a.kt", "listOf(\n    \"alpha\",\n)").isEmpty())
    }
    @Test
    fun `the scanner accepts resources, comments, logs and technical literals`() {
        assertTrue(violations("a.kt", "Text(stringResource(Res.string.action_ok))").isEmpty())
        assertTrue(violations("a.kt", "    // \"Fermer\" is the French text").isEmpty())
        assertTrue(violations("a.kt", "     * Shows \"Annuler\" in French").isEmpty())
        assertTrue(violations("a.kt", "Logger.w(TAG, \"Echec de la sync\")").isEmpty())
        assertTrue(violations("a.kt", "val dots = \"\u2026\"").isEmpty())
        assertTrue(violations("a.kt", "text = \"\$a / \$b\",").isEmpty())
        assertTrue(violations("a.kt", "text = \"\${stringResource(Res.string.x)} \u00B7 \$rest\",").isEmpty())
        assertTrue(violations("a.kt", "label = \"iconAlpha\",").isEmpty())
        assertTrue(violations("a.kt", "placeholder = \"ssh:\",").isEmpty())
        assertTrue(violations("a.kt", "MetaLine(label = \"ECDH\", value = v)").isEmpty())
        assertTrue(violations("a.kt", "text = \"%04o\".format(mode),").isEmpty())
        assertTrue(violations("a.kt", "Text(\"PBKDF2 \u00B7 AES-256-GCM\")").isEmpty())
    }

    // ── The real scan ────────────────────────────────────────────────────────

    private fun mainDir(): File {
        val candidates = listOf(
            "src/desktopMain/kotlin/fr/techtical/nextsh/desktop",
            "desktop/src/desktopMain/kotlin/fr/techtical/nextsh/desktop",
        )
        return candidates.map { File(it) }.firstOrNull { it.isDirectory }
            ?: error("desktopMain not found (working directory ${File(".").absoluteFile.normalize()}, tried $candidates)")
    }

    private val guardedFiles = listOf(
        "App.kt",
        "sessions/HostKeyPromptDialog.kt",
        "ui/browser/DesktopBrowserOverlay.kt",
        "core/browser/KcefInitializer.kt",
        "vault/Fido2EnrollDialog.kt",
        "vault/Fido2EnrollViewModel.kt",
        "vault/VaultViewModel.kt",
        "vault/VaultSetupViewModel.kt",
        "vault/VaultError.kt",
        "vault/VaultUnlockScreen.kt",
        "onboarding/FirstLaunchScreen.kt",
        "settings/SettingsViewModel.kt",
        "settings/SettingsScreen.kt",
        "core/auth/fido2/YubiKitFidoManager.kt",
        "hosts/HostDetailScreen.kt",
        "hosts/HostDetailViewModel.kt",
        "tunnels/TunnelConfigScreen.kt",
        "tunnels/TunnelConfigViewModel.kt",
        "tunnels/TunnelListScreen.kt",
        "settings/KnownHostsScreen.kt",
        "sync/ConflictResolutionScreen.kt",
        "sync/EnrolledDevicesScreen.kt",
        "sync/EnrolledDevicesViewModel.kt",
        "sync/EnrollmentServer.kt",
        "sync/LanSyncServer.kt",
        "components/TechticalDialog.kt",
        "components/FileSizeText.kt",
        "transfers/TransfersScreen.kt",
    )

    @Test
    fun `the guarded screens carry no hard-coded text`() {
        val root = mainDir()
        val sftp = File(root, "sftp").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(root).invariantSeparatorsPath }.toList()
        val files = (guardedFiles + sftp).sorted()
        assertTrue(sftp.size >= 5, "the sftp folder was not found: $sftp")
        val found = files.flatMap { relative ->
            val file = File(root, relative)
            assertTrue(file.isFile, "guarded file missing: $relative")
            violations(relative, file.readText(Charsets.UTF_8))
        }
        assertTrue(found.isEmpty(), "hard-coded texts, move them to the string resources:\n" + found.joinToString("\n"))
    }
}
