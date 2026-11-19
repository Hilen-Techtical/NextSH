// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sftp

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The dialogs are windows, they cannot be composed in a unit test. What they must do is small and easy to
 * lose in a refactor, so this guard reads the source: the two name dialogs (new folder or file, rename)
 * check the typed name with [checkEntryName] and show what is wrong, and the text field they share keeps
 * the room of the sentence and announces it to a screen reader. The rule itself is tested in
 * [SftpEntryNameCheckTest] and in `SftpEntryNameTest`.
 */
class SftpNameDialogsGuardTest {

    private fun desktopSrc(): File {
        val candidates = listOf("src", "desktop/src")
        return candidates.map { File(it) }.firstOrNull { File(it, "desktopMain/kotlin").isDirectory }
            ?: error(
                "desktop sources not found (working directory ${File(".").absoluteFile.normalize()}, " +
                    "tried $candidates)",
            )
    }

    private fun mainSource(relative: String): String {
        val file = File(desktopSrc(), "desktopMain/kotlin/fr/techtical/nextsh/desktop/$relative")
        assertTrue(file.isFile, "source not found: $file")
        return file.readText(Charsets.UTF_8)
    }

    private fun withoutComments(source: String): String =
        source.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("//[^\\n]*"), "")

    /** Collapses the alignment and the line breaks of the source, so that the guard does not depend on them. */
    private fun flat(text: String) = text.replace(Regex("""\s+"""), " ")

    private val dialogs = withoutComments(mainSource("sftp/SftpDialogs.kt"))

    /** The source of a composable of the file, from its signature to the next declaration. */
    private fun composable(name: String): String {
        val start = dialogs.indexOf("fun $name(")
        assertTrue(start >= 0, "$name is not found in SftpDialogs.kt")
        val end = Regex("""\n@Composable|\nprivate fun |\nfun |\nprivate data class """)
            .find(dialogs, start + 1)?.range?.first ?: dialogs.length
        return dialogs.substring(start, end)
    }

    private val nameDialogs = listOf("NewEntryNameDialog", "RenameDialog")

    @Test
    fun `each name dialog checks the typed name with checkEntryName`() {
        nameDialogs.forEach { name ->
            val body = composable(name)
            assertTrue(body.length > 200, "$name: the guard reads nothing")
            assertTrue(body.contains("checkEntryName("), "$name does not call checkEntryName(")
        }
    }

    @Test
    fun `each name dialog passes the sentence to the shared field and has no field of its own`() {
        nameDialogs.forEach { name ->
            val body = flat(composable(name))
            assertTrue(body.contains("EntryNameField("), "$name does not use EntryNameField")
            assertFalse(body.contains("OutlinedTextField("), "$name builds its own field")
            // The sentence of the check, not a bare `errorText = null` that would show nothing.
            assertTrue(
                body.contains("errorText = check.help?.let { stringResource(it) }"),
                "$name does not pass the sentence of the check (check.help) to EntryNameField",
            )
        }
    }

    @Test
    fun `each name dialog enables its button from the check, not from a blank test of its own`() {
        nameDialogs.forEach { name ->
            val body = flat(composable(name))
            assertTrue(body.contains("confirmEnabled = check.canConfirm"), "$name does not enable the button from check.canConfirm")
            assertFalse(body.contains("isNotBlank"), "$name still tests the name for blank itself")
            assertFalse(body.contains("isNotEmpty"), "$name still tests the name for empty itself")
        }
    }

    @Test
    fun `each name dialog confirms the name the check gives, not the text as typed`() {
        nameDialogs.forEach { name ->
            val body = flat(composable(name))
            val submit = body.substringAfter("val submit =", "")
            assertTrue(submit.contains("checkEntryName("), "$name does not check the name again when it confirms")
            assertTrue(submit.contains("it.name)"), "$name does not confirm the name of the check")
        }
    }

    @Test
    fun `the rename dialog keeps the button off while the typed text is the current name`() {
        val body = flat(composable("RenameDialog"))
        // The raw text has to change, not only its edge spaces: an entry called "notes " opens with the
        // button off, and one click does not rename it to "notes".
        assertTrue(
            body.contains("confirmEnabled = check.canConfirm && name != target.name && check.name != target.name"),
            body,
        )
        assertTrue(body.contains("it.canConfirm && name != target.name && it.name != target.name"), body)
    }

    @Test
    fun `the dialogs are tall enough for the two lines of the sentence`() {
        // The window has a fixed size: 40 dp more than before for the new folder and file dialog (220),
        // 40 dp more for the rename dialog (240). Lowering them would cut the footer.
        assertTrue(flat(composable("NewEntryNameDialog")).contains("DpSize(420.dp, 260.dp)"))
        assertTrue(flat(composable("RenameDialog")).contains("DpSize(420.dp, 280.dp)"))
    }

    @Test
    fun `the rename dialog shows the current name on one line so that a long name cannot push the footer out`() {
        // The window has a fixed size: a name of 100 characters on 2 or 3 lines would cut the footer.
        val body = flat(composable("RenameDialog"))
        val currentName = body.substringAfter("text = displayEntryName(target.name),", "").substringBefore(")")
        assertTrue(currentName.isNotEmpty(), "the current name is not shown by the rename dialog")
        assertTrue(currentName.contains("maxLines = 1"), "the current name can take several lines: $currentName")
        assertTrue(currentName.contains("overflow = TextOverflow.Ellipsis"), "the current name is not cut with an ellipsis: $currentName")
    }

    @Test
    fun `the names shown by the list and the delete dialog go through displayEntryName`() {
        val list = flat(withoutComments(mainSource("sftp/SftpBrowserScreen.kt")))
        assertTrue(list.contains("text = displayEntryName(file.name),"), "the list shows the raw name of an entry")

        val delete = flat(composable("DeleteConfirmDialog"))
        assertTrue(delete.length > 200, "DeleteConfirmDialog: the guard reads nothing")
        assertTrue(delete.contains("displayEntryName(single.name)"), "the delete title shows the raw name")
        assertTrue(delete.contains("text = displayEntryName(it.name),"), "the delete list shows the raw names")
        assertFalse(delete.contains("single.name}"), "the delete title still holds a raw name")
        assertFalse(delete.contains("text = it.name"), "the delete list still holds a raw name")
        assertFalse(list.contains("text = file.name,"), "the list still holds a raw name")
    }

    @Test
    fun `the leftover delete dialog shows display names and keeps the buttons out of the scroll`() {
        val leftovers = flat(composable("LeftoverDeleteDialog"))
        assertTrue(leftovers.length > 200, "LeftoverDeleteDialog: the guard reads nothing")
        assertTrue(leftovers.contains("text = displayEntryName(it.name),"), "the leftover list shows the raw names")
        assertFalse(leftovers.contains("text = it.name"), "the leftover list still holds a raw name")
        assertTrue(leftovers.contains("verticalScroll("), "the body of the leftover dialog does not scroll")
        // The names wrap: a cut name hides the marker and the identifier that tell two leftovers apart.
        assertFalse(leftovers.contains("maxLines = 1"), "a name of the leftover dialog is cut")
    }

    @Test
    fun `the transfers panel shows the names of the transfers through displayEntryName`() {
        val panel = flat(withoutComments(mainSource("transfers/TransfersScreen.kt")))
        assertTrue(panel.contains("displayEntryName(state.request.displayName)"), "the panel shows a raw name")
        assertTrue(panel.contains("displayEntryName(state.request.remotePath)"), "the panel shows a raw path")
    }

    @Test
    fun `copy path goes through copyablePath and never copies the raw path`() {
        val screen = flat(withoutComments(mainSource("sftp/SftpBrowserScreen.kt")))
        val start = screen.indexOf("private fun FileRow(")
        assertTrue(start >= 0, "FileRow is not found")
        val row = screen.substring(start, minOf(screen.length, start + 3000))
        assertTrue(row.contains("copyablePath(file.path)"), "FileRow does not copy through copyablePath")
        assertFalse(screen.contains("setText(AnnotatedString(file.path))"), "a raw path is copied to the clipboard")
    }

    @Test
    fun `the breadcrumb and the upload title show display names`() {
        val screen = flat(withoutComments(mainSource("sftp/SftpBrowserScreen.kt")))
        assertTrue(screen.contains("BreadcrumbChip(label = displayEntryName(segment),"), "the breadcrumb shows a raw segment")
        assertTrue(screen.contains("displayEntryName(loaded?.cwd ?: \"/\")"), "the upload title shows the raw folder")
    }

    @Test
    fun `the rename field keeps the raw name so that the check can see a hidden character`() {
        val body = flat(composable("RenameDialog"))
        assertTrue(body.contains("mutableStateOf(target.name)"), "the rename field no longer opens on the raw name")
        assertFalse(body.contains("mutableStateOf(displayEntryName("), "the rename field opens on a display name")
    }

    @Test
    fun `the text field shows the error and announces it as a polite live region`() {
        val body = flat(composable("EntryNameField"))
        assertTrue(body.contains("errorText: String?"), "errorText is not a parameter")
        assertTrue(body.contains("isError = errorText != null"), "the field is not marked as in error")
        assertTrue(body.contains("supportingText ="), "the sentence is not shown under the field")
        assertTrue(body.contains("liveRegion = LiveRegionMode.Polite"), "the sentence is not a live region")
    }

    @Test
    fun `the text field keeps the room of the sentence so that the dialog does not jump while typing`() {
        val body = flat(composable("EntryNameField"))
        // The slot is always composed, empty when nothing is wrong: an `if` around it would make the
        // field grow when ".." is typed and shrink at the next character.
        assertFalse(body.contains("supportingText = if"), "the supporting text is only there for an error")
        assertTrue(body.contains("text = errorText.orEmpty()"), "the slot does not take an empty text when nothing is wrong")
        assertTrue(body.contains("minLines = 2"), "the slot does not keep the height of two lines")
    }

    @Test
    fun `the field in error keeps its fill and turns red`() {
        val body = flat(composable("techticalFieldColors"))
        // The Material default for the container in error is transparent: the field would lose its fill.
        assertTrue(body.contains("errorContainerColor = SurfaceVariant"), "the field loses its fill in error")
        assertTrue(body.contains("errorBorderColor"), "the border has no color of its own for the error")
        assertTrue(body.contains("errorSupportingTextColor"), "the sentence has no color of its own for the error")
    }

    @Test
    fun `the check is the rule of the manager, not a copy of it`() {
        val check = flat(withoutComments(mainSource("sftp/SftpEntryNameCheck.kt")))
        assertTrue(check.contains("isValidEntryName("), "checkEntryName does not use isValidEntryName(")
        assertTrue(check.contains("sftpEntryNameProblem("), "checkEntryName does not use sftpEntryNameProblem(")
    }

    @Test
    fun `the manager no longer corrects a name`() {
        val manager = withoutComments(mainSource("core/ssh/DesktopSftpManager.kt"))
        assertFalse(manager.contains("sanitizeFilename"), "the manager still corrects names")
        // A member of the same name would hide the rule of SftpEntryName.kt, and calling it from itself loops.
        assertFalse(Regex("""fun\s+isValidEntryName\(""").containsMatchIn(manager), "a member shadows the rule")
    }
}
