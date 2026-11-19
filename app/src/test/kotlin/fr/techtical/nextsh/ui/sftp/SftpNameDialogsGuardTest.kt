// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sftp

import fr.techtical.nextsh.ui.SourceGuardSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The dialogs cannot be composed on the JVM. What they must do is small and easy to lose in a
 * refactor, so this guard reads the source: the three name dialogs (new folder, new file, rename)
 * check the typed name with [checkEntryName] and show what is wrong, and the text field they share
 * announces the sentence to a screen reader. The rule itself is tested in [SftpEntryNameCheckTest]
 * and in `SftpEntryNameTest`.
 */
class SftpNameDialogsGuardTest {

    private val screen = SourceGuardSupport.mainSource("ui/sftp/SftpBrowserScreen.kt")

    /** Collapses the alignment spaces of the source, so that the guard does not depend on them. */
    private fun flat(text: String) = text.replace(Regex("""\s+"""), " ")

    /** The source of a private composable of the screen, from its signature to the next declaration. */
    private fun composable(name: String): String {
        val start = screen.indexOf("private fun $name(")
        assertTrue(start >= 0, "$name is not found in SftpBrowserScreen.kt")
        val end = Regex("""\n@Composable|\nprivate fun |\nfun |\n// ──""").find(screen, start + 1)?.range?.first ?: screen.length
        return screen.substring(start, end)
    }

    private val nameDialogs = listOf("MkdirDialog", "NewFileDialog", "RenameDialog")

    @Test
    fun `each name dialog checks the typed name with checkEntryName`() {
        nameDialogs.forEach { name ->
            val body = composable(name)
            assertTrue(body.length > 200, "$name: the guard reads nothing")
            assertTrue(body.contains("checkEntryName("), "$name does not call checkEntryName(")
        }
    }

    @Test
    fun `each name dialog passes the sentence to its text field`() {
        nameDialogs.forEach { name ->
            val calls = SourceGuardSupport.calls(composable(name), "DialogTextField")
            assertEquals(1, calls.size, "$name does not call DialogTextField exactly once")
            // The sentence of the check, not a bare `errorText = null` that would show nothing.
            assertTrue(
                flat(calls.single()).contains("errorText = check.helpRes"),
                "$name does not pass the sentence of the check (check.helpRes) to DialogTextField",
            )
        }
    }

    @Test
    fun `each name dialog enables its button from the check, not from a blank test of its own`() {
        nameDialogs.forEach { name ->
            val body = flat(composable(name))
            assertTrue(body.contains("confirmEnabled = check.canConfirm"), "$name does not enable the button from check.canConfirm")
            assertFalse(body.contains("isNotBlank"), "$name still tests the name for blank itself")
        }
    }

    @Test
    fun `each name dialog confirms the name the check gives, not the text as typed`() {
        nameDialogs.forEach { name ->
            val body = flat(composable(name))
            val confirm = body.substringAfter("onConfirm = {", "")
            assertTrue(confirm.contains("checkEntryName("), "$name does not check the name again when it confirms")
            assertTrue(confirm.contains("onConfirm(it.name)"), "$name does not confirm the name of the check")
        }
    }

    @Test
    fun `the rename dialog keeps the button off while the typed text is the current name`() {
        val body = flat(composable("RenameDialog"))
        // The raw text has to change, not only its edge spaces: an entry called "notes " opens with the
        // button off, and one tap does not rename it to "notes".
        assertTrue(
            body.contains("confirmEnabled = check.canConfirm && name != file.name && check.name != file.name"),
            body,
        )
        assertTrue(body.contains("it.canConfirm && name != file.name && it.name != file.name"), body)
    }

    @Test
    fun `the text field shows the error and announces it as a polite live region`() {
        val body = flat(composable("DialogTextField"))
        assertTrue(body.contains("errorText: String? = null"), "errorText is not an optional parameter")
        assertTrue(body.contains("isError = errorText != null"), "the field is not marked as in error")
        assertTrue(body.contains("supportingText ="), "the sentence is not shown under the field")
        assertTrue(body.contains("liveRegion = LiveRegionMode.Polite"), "the sentence is not a live region")
        assertTrue(body.contains("errorBorderColor"), "the border has no color of its own for the error")
    }

    @Test
    fun `the text field keeps the room of the sentence so that the dialog does not jump while typing`() {
        val body = flat(composable("DialogTextField"))
        // The slot is always composed, empty when nothing is wrong: an `if` around it would make the
        // field grow when ".." is typed and shrink at the next character.
        assertFalse(body.contains("supportingText = if"), "the supporting text is only there for an error")
        assertTrue(body.contains("text = errorText.orEmpty()"), "the slot does not take an empty text when nothing is wrong")
        assertTrue(body.contains("minLines = 2"), "the slot does not keep the height of two lines")
    }

    @Test
    fun `the names shown by the list, the actions and the delete dialog go through displayEntryName`() {
        val item = flat(SourceGuardSupport.mainSource("ui/sftp/SftpFileItem.kt"))
        assertTrue(item.contains("text = displayEntryName(file.name),"), "the list shows the raw name")
        assertFalse(item.contains("text = file.name"), "the list still shows a raw name")

        val sheet = flat(composable("SftpFileActionsSheet"))
        assertTrue(sheet.contains("text = displayEntryName(file.name),"), "the header of the actions shows the raw name")
        assertFalse(sheet.contains("text = file.name"), "the header of the actions still shows a raw name")

        val delete = flat(composable("DeleteConfirmDialog"))
        assertTrue(
            delete.contains("R.string.sftp_dialog_delete_message, displayEntryName(file.name)"),
            "the deletion names the file with its raw name",
        )

        val rename = flat(composable("RenameDialog"))
        assertTrue(rename.contains("placeholder = displayEntryName(file.name),"), "the hint of the rename shows the raw name")
        // The field itself starts from the raw name, so that the check sees the invisible character and a
        // rename cannot keep a replacement character in the name sent to the server.
        assertTrue(rename.contains("mutableStateOf(file.name)"), "the field of the rename does not start from the raw name")
        assertFalse(rename.contains("mutableStateOf(displayEntryName"), "the field of the rename starts from the display name")
    }

    @Test
    fun `the name offered for the downloaded file goes through downloadEntryName, the transfer keeps the real one`() {
        assertTrue(
            flat(screen).contains("downloadLauncher.launch(downloadEntryName(current.name))"),
            "the destination picker is offered the raw name",
        )
        assertTrue(
            flat(screen).contains("PendingDownload(current.path, current.name, current.size)"),
            "the transfer must keep the real name of the file",
        )
    }

    @Test
    fun `the name of a leftover is shown readable, with the cut mark and without invisible characters`() {
        assertEquals("report.pdf", leftoverOriginalLabel(".report.pdf.nextsh-part-1a2b3c4d"))
        // Built from code points: an invisible character is never written raw in a source file.
        val rlo = 0x202E.toChar()
        val replacement = 0xFFFD.toChar()
        assertEquals("a${replacement}b", leftoverOriginalLabel(".a${rlo}b.nextsh-part-1a2b3c4d"))
        assertEquals("a".repeat(116), leftoverOriginalLabel("." + "a".repeat(116) + ".nextsh-part-1a2b3c4d"))
        assertEquals("a".repeat(120) + "…", leftoverOriginalLabel("." + "a".repeat(120) + ".nextsh-part-1a2b3c4d"))
        assertEquals(null, leftoverOriginalLabel("report.pdf"))
    }

    @Test
    fun `the check is the rule of the manager and of the use cases, not a copy of it`() {
        val check = flat(SourceGuardSupport.mainSource("ui/sftp/SftpEntryNameCheck.kt"))
        assertTrue(check.contains("isValidEntryName("), "checkEntryName does not use isValidEntryName(")
        assertTrue(check.contains("sftpEntryNameProblem("), "checkEntryName does not use sftpEntryNameProblem(")
    }
}
