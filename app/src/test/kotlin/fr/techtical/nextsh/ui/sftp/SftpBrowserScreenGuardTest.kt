// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sftp

import fr.techtical.nextsh.ui.SourceGuardSupport
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The screen cannot be composed on the JVM, and what it tells its view model is what decides who
 * announces the failure of a transfer: the view model of a split pane keeps collecting under the
 * full-screen browser of the same session, and must not say a failure to nobody. The behaviour of
 * the view model is tested in [SftpBrowserViewModelTest]; this guard reads the few lines of the
 * screen that feed it, so that a refactor cannot lose them silently.
 */
class SftpBrowserScreenGuardTest {

    private val source = SourceGuardSupport.mainSource("ui/sftp/SftpBrowserScreen.kt")

    /** Collapses the alignment spaces of the source, so that the guard does not depend on them. */
    private val flat = source.replace(Regex("""\s+"""), " ")

    @Test
    fun `the screen is on show from the start of its lifecycle and not on show from its stop`() {
        assertTrue(flat.contains("Lifecycle.Event.ON_START -> viewModel.setVisible(true)"), "ON_START does not show the screen")
        assertTrue(flat.contains("Lifecycle.Event.ON_STOP -> viewModel.setVisible(false)"), "ON_STOP does not hide the screen")
    }

    @Test
    fun `a screen that leaves the composition is not on show any more`() {
        // The pane of a split leaves the composition when the full-screen browser opens above the
        // terminal, while its view model stays in the navigation entry: the lifecycle alone would not say.
        val dispose = flat.substringAfter("onDispose {", "")
        assertTrue(dispose.isNotEmpty(), "the visibility effect has no onDispose")
        assertTrue(dispose.substringBefore("LaunchedEffect").contains("viewModel.setVisible(false)"), dispose)
        assertTrue(flat.contains("lifecycleOwner.lifecycle.removeObserver(observer)"))
    }

    @Test
    fun `the visibility is set before the session is initialised`() {
        assertTrue(flat.indexOf("viewModel.setVisible(true)") in 0 until flat.indexOf("viewModel.init(sessionId, hostLabel, ownsSession)"))
    }

    @Test
    fun `the screen no longer offers a back to the list, it goes back to where it came from`() {
        assertFalse(flat.contains("sftp_back_to_list"))
        assertTrue(flat.contains("ownsSession -> R.string.action_back"), "the full-screen button is the common back")
    }

    @Test
    fun `the upload callback asks the provider whether the picked document is virtual, then tells the view model`() {
        // The parameter of the view model has a default value (false): a refactor that dropped the
        // argument would compile, and a Google Docs file would start a transfer that cannot read it.
        val callback = source.substringAfter("val uploadLauncher = rememberLauncherForActivityResult(", "").substringBefore("Scaffold(")
        assertTrue(callback.isNotEmpty(), "the upload launcher is not found")

        val detection = Regex("""val isVirtual\s*=\s*isVirtualDocument\(context, sourceUri\)""").find(callback)
        assertNotNull(detection, "the callback does not ask isVirtualDocument(context, sourceUri) for the picked document")

        val call = SourceGuardSupport.calls(callback, "viewModel.uploadFile").singleOrNull()
        assertNotNull(call, "the callback does not call viewModel.uploadFile exactly once")
        assertTrue(
            call!!.replace(Regex("""\s+"""), " ").contains("isVirtual = isVirtual"),
            "the call to viewModel.uploadFile does not pass isVirtual = isVirtual: $call",
        )
        assertTrue(detection!!.range.first < callback.indexOf(call), "the detection must come before the call to the view model")
    }

    // ── The file that has the name of the upload ─────────────────────────────────

    @Test
    fun `the dialog of the replacement is shown from the state of the view model and answers it`() {
        // Nothing is sent until the user answers: the dialog is what asks, and its two answers are the
        // ones of the view model. A refactor that lost the wiring would leave the upload held for ever.
        val shown = flat.substringAfter("val uploadConflict = uiState.uploadConflict", "")
        assertTrue(shown.isNotEmpty(), "the dialog is not read from uiState.uploadConflict")
        val call = shown.substringBefore("val previewState")
        assertTrue(call.contains("UploadReplaceDialog("), call)
        assertTrue(call.contains("name = uploadConflict.name"), call)
        // The folder of the pick, read from the state of the conflict: not the folder on screen, the user
        // may have gone elsewhere during the check.
        assertTrue(call.contains("folder = uploadConflict.folder"), call)
        assertFalse(call.contains("currentPath"), call)
        assertTrue(call.contains("onDismiss = viewModel::dismissUploadConflict"), call)
        assertTrue(call.contains("onConfirm = viewModel::confirmUploadReplace"), call)
    }

    @Test
    fun `the dialog of the replacement is the red one of the deletions, and its answers are the ones it is given`() {
        val dialog = flat.substringAfter("private fun UploadReplaceDialog(", "").substringBefore("@Composable")
        assertTrue(dialog.isNotEmpty(), "the dialog of the replacement is not found")
        assertTrue(dialog.contains("confirmDanger = true"), "a replacement destroys a file: the confirm button is the red one")
        assertTrue(dialog.contains("onDismiss = onDismiss"), dialog)
        assertTrue(dialog.contains("onConfirm = onConfirm"), dialog)
        assertTrue(dialog.contains("R.string.sftp_upload_replace_title"), dialog)
        assertTrue(dialog.contains("R.string.sftp_upload_replace_message, name, folder"), dialog)
        assertTrue(dialog.contains("R.string.sftp_upload_replace_action"), dialog)
    }

    @Test
    fun `the dialog of the replacement puts the lost content on a line of its own, red, like the dialog of a deletion`() {
        val dialog = flat.substringAfter("private fun UploadReplaceDialog(", "").substringBefore("@Composable")
        assertTrue(dialog.contains("Column(verticalArrangement = Arrangement.spacedBy(Spacing.Sm))"), dialog)
        // The message names the file in the primary text, the warning follows in the red of the deletions.
        val message = dialog.indexOf("R.string.sftp_upload_replace_message, name")
        val warning = dialog.indexOf("R.string.sftp_upload_replace_warning")
        assertTrue(message in 0 until warning, "the warning comes after the message: $dialog")
        val warningText = dialog.substring(warning)
        assertTrue(warningText.substringBefore("confirmLabel").contains("fontSize = 12.sp"), warningText)
        assertTrue(warningText.substringBefore("confirmLabel").contains("color = ErrorRed"), warningText)
        // The same two lines as the confirmation of a deletion, which this one follows.
        val deletion = flat.substringAfter("private fun DeleteConfirmDialog(", "").substringBefore("private fun MultiDeleteConfirmDialog")
        assertTrue(deletion.contains("fontSize = 12.sp, color = ErrorRed"), "the model of the dialog is not found")
        assertTrue(dialog.contains("fontSize = 12.sp, color = ErrorRed"), dialog)
    }

    @Test
    fun `the conflict of an upload is not kept in the saved state of the screen, nor can it be`() {
        // It is found after the picker returned, on a view model that is alive, and nothing is sent if the
        // activity dies before the answer: a saved copy would offer a replacement the picker no longer backs.
        // The screen does not even name the type, so it cannot put it in a saver or a rememberSaveable.
        assertFalse(flat.contains("UploadConflictState"), "the screen names the state of the conflict")
        // And the type cannot be written to a Bundle by the view model: not Parcelable, nothing to save.
        val viewModel = SourceGuardSupport.mainSource("ui/sftp/SftpBrowserViewModel.kt")
        assertTrue(viewModel.contains("data class UploadConflictState("), "the state of the conflict is not found")
        assertFalse(viewModel.contains("Parcelable"), "the state of the conflict is Parcelable")
        assertFalse(viewModel.contains("Parcelize"), "the state of the conflict is Parcelize")
    }

    // ── The leftovers of interrupted uploads ─────────────────────────────────────

    @Test
    fun `the dialog of the leftovers is a red dialog with the warning, the names and the red confirm button`() {
        val dialog = flat.substringAfter("private fun UploadLeftoverDeleteDialog(", "").substringBefore("@Composable")
        assertTrue(dialog.isNotEmpty(), "the dialog of the leftovers is not found")
        assertTrue(dialog.contains("DialogShell("), dialog)
        assertTrue(dialog.contains("accent = ErrorRed"), "a deletion is red")
        assertTrue(dialog.contains("confirmDanger = true"), "the confirm button is the red one of the deletions")
        assertTrue(dialog.contains("R.string.sftp_leftover_delete_warning"), "the warning about a running upload is missing")
        assertTrue(dialog.contains("R.plurals.sftp_leftover_delete_title"), dialog)
        assertTrue(dialog.contains("R.string.sftp_leftover_delete_message_single"), dialog)
        assertTrue(dialog.contains("R.string.sftp_leftover_delete_message_multi"), dialog)
        // The exact names that will go, made readable, and nothing else than the targets fixed at the opening.
        assertTrue(dialog.contains("targets.forEach"), "the dialog does not list the files it deletes")
        assertTrue(dialog.contains("displayEntryName(file.name)"), dialog)
        val warning = dialog.indexOf("R.string.sftp_leftover_delete_warning")
        assertTrue(dialog.substring(warning).substringBefore("confirmLabel").contains("color = ErrorRed"), "the warning is not red")
    }

    @Test
    fun `the dialog of the leftovers is shown from the fixed list of the view model and answers it`() {
        val shown = flat.substringAfter("val leftoverDeleteState = uiState.leftoverDeleteConfirmation", "")
        assertTrue(shown.isNotEmpty(), "the dialog is not read from uiState.leftoverDeleteConfirmation")
        val call = shown.substringBefore("if (uiState.multiDeleteConfirmation)")
        assertTrue(call.contains("UploadLeftoverDeleteDialog("), call)
        assertTrue(call.contains("targets = leftoverDeleteState"), call)
        assertTrue(call.contains("onDismiss = viewModel::dismissLeftoverDeleteConfirmation"), call)
        assertTrue(call.contains("onConfirm = viewModel::confirmLeftoverDelete"), call)
    }

    @Test
    fun `the banner of the leftovers is an element of the list, hidden in selection mode or once dismissed`() {
        val list = flat.substringAfter("LazyColumn(", "").substringBefore("items(items = uiState.files")
        assertTrue(list.contains("item(key = \"upload-leftovers\")"), "the banner is not an element of the LazyColumn")
        assertTrue(list.contains("uiState.uploadLeftovers.isNotEmpty()"), list)
        assertTrue(list.contains("!uiState.leftoverBannerDismissed"), list)
        assertTrue(list.contains("!uiState.isSelectionMode"), list)
        assertTrue(list.contains("onDismiss = viewModel::dismissLeftoverBanner"), list)
        // It opens the dialog (a confirmation that lists the files), it never deletes by itself.
        assertTrue(list.contains("viewModel.showLeftoverDeleteConfirmation(uiState.uploadLeftovers)"), list)
        assertFalse(list.contains("confirmLeftoverDelete"), "the banner deletes without the dialog")
    }

    @Test
    fun `the delete action of a leftover opens the dialog of the leftovers, the others the usual one`() {
        val action = flat.substringAfter("onDelete = { endAction()", "").substringBefore("if (uiState.mkdirDialog)")
        assertTrue(action.contains("if (current.path in leftoverLabels)"), action)
        assertTrue(action.contains("viewModel.showLeftoverDeleteConfirmation(listOf(current))"), action)
        assertTrue(action.contains("viewModel.showDeleteConfirmation(current)"), action)
    }

    @Test
    fun `the deletion of the leftovers is never recursive`() {
        val viewModel = SourceGuardSupport.mainSource("ui/sftp/SftpBrowserViewModel.kt").replace(Regex("""\s+"""), " ")
        val confirm = viewModel.substringAfter("fun confirmLeftoverDelete() {", "").substringBefore("fun clearMessage")
        assertTrue(confirm.isNotEmpty(), "confirmLeftoverDelete is not found")
        assertTrue(confirm.contains("deleteFileUseCase(sessionId, file.path, isDirectory = false)"), confirm)
        assertFalse(confirm.contains("isDirectory = true"), confirm)
        assertFalse(confirm.contains("file.isDirectory"), "the deletion of a leftover follows the type of the entry")
        assertFalse(confirm.contains("deleteRecursive"), confirm)
        // No name and no path in the logs.
        assertFalse(Regex("""Timber\.\w\([^)]*(file|path|name)""").containsMatchIn(confirm), confirm)
    }

    @Test
    fun `an upload button is held back while the name is being checked`() {
        val topBar = flat.substringAfter("private fun SftpTopBar(", "").substringBefore("private fun BreadcrumbAndAddBar")
        assertTrue(topBar.contains("uploadEnabled: Boolean"), "the top bar takes whether the upload is enabled")
        assertTrue(topBar.contains("IconButton(onClick = onUpload, enabled = uploadEnabled)"), topBar)
        assertTrue(flat.contains("uploadEnabled = !uiState.uploadCheckInProgress"), "the screen does not feed it")
    }
}
