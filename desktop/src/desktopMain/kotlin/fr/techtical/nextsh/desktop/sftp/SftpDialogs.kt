// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sftp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.sftp_action_delete
import fr.techtical.nextsh.desktop.generated.resources.sftp_dialog_cancel
import fr.techtical.nextsh.desktop.generated.resources.sftp_dialog_chmod_apply
import fr.techtical.nextsh.desktop.generated.resources.sftp_dialog_chmod_execute
import fr.techtical.nextsh.desktop.generated.resources.sftp_dialog_chmod_group
import fr.techtical.nextsh.desktop.generated.resources.sftp_dialog_chmod_others
import fr.techtical.nextsh.desktop.generated.resources.sftp_dialog_chmod_owner
import fr.techtical.nextsh.desktop.generated.resources.sftp_dialog_chmod_read
import fr.techtical.nextsh.desktop.generated.resources.sftp_dialog_chmod_title
import fr.techtical.nextsh.desktop.generated.resources.sftp_dialog_chmod_write
import fr.techtical.nextsh.desktop.generated.resources.sftp_dialog_delete_confirm_title
import fr.techtical.nextsh.desktop.generated.resources.sftp_dialog_delete_multi_warning
import fr.techtical.nextsh.desktop.generated.resources.sftp_dialog_delete_single_file_warning
import fr.techtical.nextsh.desktop.generated.resources.sftp_dialog_delete_single_folder_warning
import fr.techtical.nextsh.desktop.generated.resources.sftp_leftover_delete_message_multi
import fr.techtical.nextsh.desktop.generated.resources.sftp_leftover_delete_message_single
import fr.techtical.nextsh.desktop.generated.resources.sftp_leftover_delete_title_many
import fr.techtical.nextsh.desktop.generated.resources.sftp_leftover_delete_title_one
import fr.techtical.nextsh.desktop.generated.resources.sftp_leftover_delete_warning
import fr.techtical.nextsh.desktop.generated.resources.sftp_dialog_new_file_create
import fr.techtical.nextsh.desktop.generated.resources.sftp_dialog_new_file_name_label
import fr.techtical.nextsh.desktop.generated.resources.sftp_dialog_new_file_title
import fr.techtical.nextsh.desktop.generated.resources.sftp_dialog_new_folder_create
import fr.techtical.nextsh.desktop.generated.resources.sftp_dialog_new_folder_name_label
import fr.techtical.nextsh.desktop.generated.resources.sftp_dialog_new_folder_title
import fr.techtical.nextsh.desktop.generated.resources.sftp_dialog_rename_new_name_label
import fr.techtical.nextsh.desktop.generated.resources.sftp_dialog_rename_title
import fr.techtical.nextsh.desktop.generated.resources.sftp_dialog_replace_confirm
import fr.techtical.nextsh.desktop.generated.resources.sftp_dialog_replace_download_warning
import fr.techtical.nextsh.desktop.generated.resources.sftp_dialog_replace_host
import fr.techtical.nextsh.desktop.generated.resources.sftp_dialog_replace_title
import fr.techtical.nextsh.desktop.generated.resources.sftp_dialog_replace_upload_warning
import fr.techtical.nextsh.desktop.generated.resources.sftp_dialog_replace_window_title
import fr.techtical.nextsh.desktop.theme.Burgundy
import fr.techtical.nextsh.desktop.theme.ErrorRed
import fr.techtical.nextsh.desktop.theme.Gold
import fr.techtical.nextsh.desktop.theme.GoldMuted
import fr.techtical.nextsh.desktop.theme.NearBlack
import fr.techtical.nextsh.desktop.theme.Surface
import fr.techtical.nextsh.desktop.theme.SurfaceVariant
import fr.techtical.nextsh.desktop.theme.TextPrimary
import fr.techtical.nextsh.desktop.theme.TextSecondary
import fr.techtical.nextsh.desktop.theme.White
import fr.techtical.nextsh.desktop.window.WindowCaptureProtection
import fr.techtical.nextsh.shared.domain.model.SftpFile
import org.jetbrains.compose.resources.stringResource

// All the dialogs are separate OS windows (DialogWindow). Same reason as the
// host / theme pickers: they sit in a parent window that may embed a heavyweight
// JediTerm SwingPanel (adjacent tab), and the only reliable z-order above AWT
// is another top-level window.
//
// Sizing: `DialogWindow` needs an explicit initial size. Compose MP 1.7.x
// does NOT surface Compose content's intrinsic size to Swing's `pack()`, and
// mutating `DialogState.size` dynamically from `onSizeChanged` races with
// Compose's re-measure on resize. We tried both and neither produced tight
// dialogs. The pragmatic path is hand-tuned sizes per dialog, where each
// caller passes a `DpSize` that matches its content (header + spacers +
// fields + grid + footer + padding + Windows title bar ~32 dp).
//
// A full-window black `Box` behind the content covers any residual flash
// while the JDialog paints before Compose's first frame.

/**
 * Both keyboard options are opt-in, so the dialogs that never had them keep their behavior:
 *
 * - [onEscape]: the Escape key is only handled by a dialog that passes it. The key press is
 *   consumed when it is handled.
 * - [guardStrayKeyUp]: absorb the release of Enter or Space whose press this window never
 *   saw (see [StrayKeyUpGuard]). For a dialog that opens right after the user validated
 *   something else with that key, and whose focused button would otherwise be clicked by it.
 */
@Composable
private fun TechticalDialog(
    onCloseRequest: () -> Unit,
    title: String,
    size: DpSize,
    onEscape: (() -> Unit)? = null,
    guardStrayKeyUp: Boolean = false,
    content: @Composable () -> Unit,
) {
    val keyGuard = remember { StrayKeyUpGuard() }
    DialogWindow(
        onCloseRequest = onCloseRequest,
        state = rememberDialogState(size = size),
        title = title,
        resizable = false,
        onPreviewKeyEvent = { ev ->
            when {
                guardStrayKeyUp && keyGuard.shouldAbsorb(ev.type, ev.key) -> true
                onEscape != null && ev.type == KeyEventType.KeyDown && ev.key == Key.Escape -> {
                    onEscape()
                    true
                }
                else -> false
            }
        },
    ) {
        // Un seul point d'ancrage pour tous les dialogs SFTP : chemins et noms
        // de fichiers distants suivent le réglage « Cacher de la capture d'écran ».
        WindowCaptureProtection(window)
        Box(modifier = Modifier.fillMaxSize().background(NearBlack)) {
            content()
        }
    }
}

// ── NewFolderDialog ───────────────────────────────────────────────────────────

@Composable
fun NewFolderDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    NewEntryNameDialog(
        icon = Icons.Default.CreateNewFolder,
        title = stringResource(Res.string.sftp_dialog_new_folder_title),
        fieldLabel = stringResource(Res.string.sftp_dialog_new_folder_name_label),
        createLabel = stringResource(Res.string.sftp_dialog_new_folder_create),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

// ── NewFileDialog ─────────────────────────────────────────────────────────────

@Composable
fun NewFileDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    NewEntryNameDialog(
        icon = Icons.AutoMirrored.Filled.NoteAdd,
        title = stringResource(Res.string.sftp_dialog_new_file_title),
        fieldLabel = stringResource(Res.string.sftp_dialog_new_file_name_label),
        createLabel = stringResource(Res.string.sftp_dialog_new_file_create),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/** Single-field "name your new entry" dialog shared by [NewFolderDialog] and [NewFileDialog]. */
@Composable
private fun NewEntryNameDialog(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    fieldLabel: String,
    createLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val check = checkEntryName(name)
    // The name is checked again at the moment of confirming (Enter in the field does not look at the
    // button), and the name sent is the one of the check: edge spaces removed, nothing else changed.
    val submit = { checkEntryName(name).let { if (it.canConfirm) onConfirm(it.name) } }
    TechticalDialog(
        onCloseRequest = onDismiss,
        title = title,
        // 16 pad + 24 header + 12 + 64 field (56 + 8 above the label) + 4 + 29 two-line sentence (bodySmall
        // has no lineHeight in the theme: 2 x ~14.5) + 16 + 48 footer (the button takes 48 for touch)
        // + 16 pad + ~32 title bar = ~261, set to 260: the last dp comes out of the bottom padding.
        // To be tuned on actual Windows rendering.
        size = DpSize(420.dp, 260.dp),
    ) {
        DialogShell {
            DialogHeader(icon = icon, title = title)
            Spacer(Modifier.height(12.dp))
            EntryNameField(
                value = name,
                onValueChange = { name = it },
                label = fieldLabel,
                errorText = check.help?.let { stringResource(it) },
                onDone = submit,
            )
            Spacer(Modifier.height(16.dp))
            DialogFooter(
                confirmLabel = createLabel,
                confirmEnabled = check.canConfirm,
                onConfirm = submit,
                onDismiss = onDismiss,
            )
        }
    }
}

// ── RenameDialog ──────────────────────────────────────────────────────────────

@Composable
fun RenameDialog(
    target: SftpFile,
    onConfirm: (oldPath: String, newName: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(target) { mutableStateOf(target.name) }
    val check = checkEntryName(name)
    // The raw text has to differ from the current name, and so does the name once trimmed: an entry
    // called "notes " opens with the button off, one click does not rename it to "notes".
    val submit = {
        checkEntryName(name).let {
            if (it.canConfirm && name != target.name && it.name != target.name) onConfirm(target.path, it.name)
        }
    }
    val renameTitle = stringResource(Res.string.sftp_dialog_rename_title)
    val newNameLabel = stringResource(Res.string.sftp_dialog_rename_new_name_label)
    TechticalDialog(
        onCloseRequest = onDismiss,
        title = renameTitle,
        // 16 pad + 24 header + 6 + ~14.5 current name (one line, see maxLines) + 12 + 64 field (56 + 8 above
        // the label) + 4 + 29 two-line sentence (2 x ~14.5) + 16 + 48 footer (the button takes 48 for
        // touch) + 16 pad + ~32 title bar = ~281.5, set to 280: the last dp and a half come out of the
        // bottom padding. To be tuned on actual Windows rendering.
        size = DpSize(420.dp, 280.dp),
    ) {
        DialogShell {
            DialogHeader(icon = Icons.Default.DriveFileRenameOutline, title = renameTitle)
            Spacer(Modifier.height(6.dp))
            // One line, cut with an ellipsis: a long name on 2 or 3 lines would push the footer out of
            // the fixed-size window.
            Text(
                text = displayEntryName(target.name),
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            EntryNameField(
                value = name,
                onValueChange = { name = it },
                label = newNameLabel,
                errorText = check.help?.let { stringResource(it) },
                onDone = submit,
            )
            Spacer(Modifier.height(16.dp))
            DialogFooter(
                confirmLabel = renameTitle,
                confirmEnabled = check.canConfirm && name != target.name && check.name != target.name,
                onConfirm = submit,
                onDismiss = onDismiss,
            )
        }
    }
}

// ── DeleteConfirmDialog ───────────────────────────────────────────────────────

@Composable
fun DeleteConfirmDialog(
    targets: List<SftpFile>,
    onConfirm: (List<SftpFile>) -> Unit,
    onDismiss: () -> Unit,
) {
    if (targets.isEmpty()) { onDismiss(); return }
    val single = targets.singleOrNull()
    val confirmTitle = stringResource(Res.string.sftp_dialog_delete_confirm_title)
    val deleteAction = stringResource(Res.string.sftp_action_delete)
    val cancelLabel = stringResource(Res.string.sftp_dialog_cancel)
    val title = if (single != null) "${deleteAction} ${displayEntryName(single.name)}" else "${deleteAction} ${targets.size}"
    val warning = when {
        single != null && single.isDirectory -> stringResource(Res.string.sftp_dialog_delete_single_folder_warning)
        single != null -> stringResource(Res.string.sftp_dialog_delete_single_file_warning)
        else -> stringResource(Res.string.sftp_dialog_delete_multi_warning)
    }
    // Tuned against actual Windows rendering (captures from user):
    //  - Single: warning fits on one line, so content is tighter than the
    //    naive "22 + 40" estimate: 160 leaves just enough breathing room.
    //  - Multi: adds the 80 dp targets list + 12 dp spacer.
    val size = if (targets.size > 1) DpSize(460.dp, 300.dp) else DpSize(440.dp, 160.dp)
    TechticalDialog(
        onCloseRequest = onDismiss,
        title = confirmTitle,
        size = size,
    ) {
        DialogShell {
            Text(
                text = title,
                color = ErrorRed,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = warning,
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (targets.size > 1) {
                Spacer(Modifier.height(12.dp))
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().height(80.dp)
                        .background(SurfaceVariant)
                        .padding(8.dp),
                ) {
                    items(targets, key = { it.path }) {
                        Text(
                            text = displayEntryName(it.name),
                            color = TextPrimary,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(cancelLabel, color = TextSecondary)
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onConfirm(targets) },
                    colors = ButtonDefaults.buttonColors(containerColor = ErrorRed, contentColor = White),
                ) {
                    Text(deleteAction)
                }
            }
        }
    }
}

// ── LeftoverDeleteDialog ──────────────────────────────────────────────────────

/**
 * Confirms the deletion of leftover temporary files of interrupted uploads. [targets] is the list frozen
 * when the dialog opened, and the names shown are those that are deleted, one by one, never a pattern.
 * Explains what the files are, says that deleting them leaves the original files alone, and warns that
 * an upload still running from another device would fail (without changing the original file).
 */
@Composable
fun LeftoverDeleteDialog(
    targets: List<SftpFile>,
    onConfirm: (List<SftpFile>) -> Unit,
    onDismiss: () -> Unit,
) {
    if (targets.isEmpty()) { onDismiss(); return }
    val single = targets.singleOrNull()
    val windowTitle = stringResource(Res.string.sftp_dialog_delete_confirm_title)
    val deleteAction = stringResource(Res.string.sftp_action_delete)
    val cancelLabel = stringResource(Res.string.sftp_dialog_cancel)
    val title = if (targets.size > 1) stringResource(Res.string.sftp_leftover_delete_title_many, targets.size)
    else stringResource(Res.string.sftp_leftover_delete_title_one, targets.size)
    val message = if (single != null) {
        stringResource(
            Res.string.sftp_leftover_delete_message_single,
            leftoverOriginalName(single.name) ?: displayEntryName(single.name),
        )
    } else {
        stringResource(Res.string.sftp_leftover_delete_message_multi)
    }
    val warning = stringResource(Res.string.sftp_leftover_delete_warning)
    // Same method as DeleteConfirmDialog: a fixed size, tuned on the Windows rendering. Single: title, a
    // message of up to 3 lines, a warning of 2 lines, the buttons. Multi: the 80 dp list of names more.
    val size = if (targets.size > 1) DpSize(480.dp, 350.dp) else DpSize(480.dp, 290.dp)
    TechticalDialog(
        onCloseRequest = onDismiss,
        title = windowTitle,
        size = size,
    ) {
        // Not DialogShell: the body scrolls (a long name, or one cut with an ellipsis, would push the
        // buttons out of a window that cannot be resized) while the buttons stay in place below it.
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Text(text = title, color = ErrorRed, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(text = message, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                if (targets.size > 1) {
                    Spacer(Modifier.height(12.dp))
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().height(80.dp)
                            .background(SurfaceVariant)
                            .padding(8.dp),
                    ) {
                        // The whole name wraps: the marker and the identifier tell two leftovers of the
                        // same file apart, which a cut name would make identical.
                        items(targets, key = { it.path }) {
                            Text(
                                text = displayEntryName(it.name),
                                color = TextPrimary,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(text = warning, color = ErrorRed, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(cancelLabel, color = TextSecondary)
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onConfirm(targets) },
                    colors = ButtonDefaults.buttonColors(containerColor = ErrorRed, contentColor = White),
                ) {
                    Text(deleteAction)
                }
            }
        }
    }
}

// ── ReplaceConfirmDialog ──────────────────────────────────────────────────────

/**
 * Asks before an upload or a download replaces a file that already exists.
 *
 * Built to be answered on purpose, not by reflex:
 * - Cancel is the default. It holds the focus when the window opens, so a stray Enter keeps
 *   the file; Escape cancels too; and a release of Enter or Space whose press this window
 *   never saw (the key that validated the file chooser) is absorbed.
 * - Replace is the destructive choice, red like the delete confirmation. It is usable only
 *   while THIS window has the focus, and [REPLACE_ARM_DELAY_MS] after the window got it
 *   (see [runReplaceArming]): the windows of two waiting questions are stacked one on the
 *   other, and a click meant for the top one must never reach the "Replace" of the other.
 * - The host is named under the title when it is known, since two questions of two servers
 *   look alike.
 * - The name has its own line, and the path is cut in its middle, so that the end of both
 *   stays readable.
 *
 * The caller wraps it in `key(prompt.id)`: each question gets its own window, with the focus
 * back on Cancel and the delay started again.
 */
@Composable
fun ReplaceConfirmDialog(
    prompt: ReplacePrompt,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val windowTitle = stringResource(Res.string.sftp_dialog_replace_window_title)
    val title = stringResource(Res.string.sftp_dialog_replace_title)
    val warning = when (prompt) {
        is ReplacePrompt.Upload -> stringResource(Res.string.sftp_dialog_replace_upload_warning)
        is ReplacePrompt.Download -> stringResource(Res.string.sftp_dialog_replace_download_warning)
    }
    val cancelLabel = stringResource(Res.string.sftp_dialog_cancel)
    val confirmLabel = stringResource(Res.string.sftp_dialog_replace_confirm)
    val path = remember(prompt.targetPath) { shortenPathInMiddle(prompt.targetPath, REPLACE_PATH_MAX_CHARS) }
    val hostLabel = prompt.hostLabel?.takeIf { it.isNotBlank() }
    val hostLine = hostLabel?.let { stringResource(Res.string.sftp_dialog_replace_host, it) }
    val cancelFocus = remember { FocusRequester() }
    TechticalDialog(
        onCloseRequest = onDismiss,
        title = windowTitle,
        // 16 pad + 24 title + [20 host line] + 8 + ~40 name (2 lines) + 8 + ~40 warning (2 lines)
        // + 12 + ~48 path (2 lines) + 16 + 40 footer + 16 pad + ~32 title bar = ~300 (~320 with the
        // host line), rounded up. To be tuned on actual Windows rendering.
        size = DpSize(480.dp, if (hostLine != null) 330.dp else 310.dp),
        onEscape = onDismiss,
        guardStrayKeyUp = true,
    ) {
        // Here, in the window's own composition, and not around it: "Replace" is armed by the
        // focus of THIS window and a delay that starts from its first frame (see
        // runReplaceArming), and disarmed the moment the window loses the focus.
        var replaceUsable by remember { mutableStateOf(false) }
        val windowInfo = LocalWindowInfo.current
        LaunchedEffect(Unit) { cancelFocus.requestFocus() }
        LaunchedEffect(windowInfo) {
            runReplaceArming(
                windowFocused = snapshotFlow { windowInfo.isWindowFocused },
                awaitFirstFrame = { withFrameNanos { } },
                delayMs = REPLACE_ARM_DELAY_MS,
                setArmed = { replaceUsable = it },
            )
        }
        DialogShell {
            Text(
                text = title,
                color = ErrorRed,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (hostLine != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = hostLine,
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = prompt.name,
                color = TextPrimary,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = warning,
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = path,
                color = TextPrimary,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().background(SurfaceVariant).padding(8.dp),
            )
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.focusRequester(cancelFocus),
                ) {
                    Text(cancelLabel, color = TextSecondary)
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onConfirm,
                    enabled = replaceUsable,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ErrorRed,
                        contentColor = White,
                        disabledContainerColor = ErrorRed.copy(alpha = 0.35f),
                        disabledContentColor = White.copy(alpha = 0.6f),
                    ),
                ) {
                    Text(confirmLabel)
                }
            }
        }
    }
}

// ── ChmodDialog ───────────────────────────────────────────────────────────────

private data class PermBits(
    var ur: Boolean, var uw: Boolean, var ux: Boolean,
    var gr: Boolean, var gw: Boolean, var gx: Boolean,
    var or: Boolean, var ow: Boolean, var ox: Boolean,
) {
    fun toInt(): Int = 0
        .or(if (ur) 0b100_000_000 else 0)
        .or(if (uw) 0b010_000_000 else 0)
        .or(if (ux) 0b001_000_000 else 0)
        .or(if (gr) 0b000_100_000 else 0)
        .or(if (gw) 0b000_010_000 else 0)
        .or(if (gx) 0b000_001_000 else 0)
        .or(if (or) 0b000_000_100 else 0)
        .or(if (ow) 0b000_000_010 else 0)
        .or(if (ox) 0b000_000_001 else 0)

    companion object {
        fun fromInt(p: Int) = PermBits(
            ur = p and 0b100_000_000 != 0, uw = p and 0b010_000_000 != 0, ux = p and 0b001_000_000 != 0,
            gr = p and 0b000_100_000 != 0, gw = p and 0b000_010_000 != 0, gx = p and 0b000_001_000 != 0,
            or = p and 0b000_000_100 != 0, ow = p and 0b000_000_010 != 0, ox = p and 0b000_000_001 != 0,
        )
    }
}

@Composable
fun ChmodDialog(
    target: SftpFile,
    onConfirm: (path: String, permissions: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var bits by remember(target) { mutableStateOf(PermBits.fromInt(target.permissions)) }
    val current = bits.toInt()

    val chmodTitle = stringResource(Res.string.sftp_dialog_chmod_title)
    val applyLabel = stringResource(Res.string.sftp_dialog_chmod_apply)
    TechticalDialog(
        onCloseRequest = onDismiss,
        title = chmodTitle,
        // Naïve dp count lands around 414 but Windows clips the footer buttons
        // at 420; +40 dp gives the descenders clean room.
        size = DpSize(440.dp, 460.dp),
    ) {
        DialogShell {
            DialogHeader(icon = Icons.Default.Lock, title = chmodTitle)
            Spacer(Modifier.height(4.dp))
            Text(
                text = target.name,
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(16.dp))

            // Live octal display, large, monospace, gold.
            Box(
                modifier = Modifier.fillMaxWidth().background(SurfaceVariant).padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "%04o".format(current),
                    color = Gold,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 28.sp,
                )
            }
            Spacer(Modifier.height(16.dp))

            PermGridHeader()
            Spacer(Modifier.height(4.dp))
            PermRow(
                label = stringResource(Res.string.sftp_dialog_chmod_owner),
                r = bits.ur, w = bits.uw, x = bits.ux,
                onR = { bits = bits.copy(ur = it) },
                onW = { bits = bits.copy(uw = it) },
                onX = { bits = bits.copy(ux = it) },
            )
            PermRow(
                label = stringResource(Res.string.sftp_dialog_chmod_group),
                r = bits.gr, w = bits.gw, x = bits.gx,
                onR = { bits = bits.copy(gr = it) },
                onW = { bits = bits.copy(gw = it) },
                onX = { bits = bits.copy(gx = it) },
            )
            PermRow(
                label = stringResource(Res.string.sftp_dialog_chmod_others),
                r = bits.or, w = bits.ow, x = bits.ox,
                onR = { bits = bits.copy(or = it) },
                onW = { bits = bits.copy(ow = it) },
                onX = { bits = bits.copy(ox = it) },
            )
            Spacer(Modifier.height(20.dp))
            DialogFooter(
                confirmLabel = applyLabel,
                confirmEnabled = true,
                onConfirm = { onConfirm(target.path, current) },
                onDismiss = onDismiss,
            )
        }
    }
}

@Composable
private fun PermGridHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(110.dp))
        listOf(
            stringResource(Res.string.sftp_dialog_chmod_read),
            stringResource(Res.string.sftp_dialog_chmod_write),
            stringResource(Res.string.sftp_dialog_chmod_execute),
        ).forEach {
            Text(
                text = it,
                color = GoldMuted,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.width(80.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun PermRow(
    label: String,
    r: Boolean, w: Boolean, x: Boolean,
    onR: (Boolean) -> Unit, onW: (Boolean) -> Unit, onX: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = TextPrimary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(110.dp),
        )
        listOf(r to onR, w to onW, x to onX).forEach { (checked, onChange) ->
            Box(
                modifier = Modifier.width(80.dp),
                contentAlignment = Alignment.Center,
            ) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = onChange,
                    colors = CheckboxDefaults.colors(
                        checkedColor = Burgundy,
                        uncheckedColor = TextSecondary,
                        checkmarkColor = Gold,
                    ),
                )
            }
        }
    }
}

// ── Shared shell ──────────────────────────────────────────────────────────────

@Composable
private fun DialogShell(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
    ) {
        content()
    }
}

@Composable
private fun DialogHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = Gold)
        Spacer(Modifier.width(8.dp))
        Text(text = title, color = TextPrimary, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun DialogFooter(
    confirmLabel: String,
    confirmEnabled: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cancelLabel = stringResource(Res.string.sftp_dialog_cancel)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(onClick = onDismiss) {
            Text(cancelLabel, color = TextSecondary)
        }
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = onConfirm,
            enabled = confirmEnabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = Burgundy,
                contentColor = White,
                disabledContainerColor = Surface,
                disabledContentColor = TextSecondary,
            ),
        ) {
            Text(confirmLabel)
        }
    }
}

/**
 * Name field shared by the new folder, new file and rename dialogs. [errorText] says why the name typed
 * so far is refused, or is null when there is nothing to say.
 *
 * The sentence slot is always composed and always two lines high, empty when nothing is wrong: the
 * dialog window has a fixed size, and a field that grew and shrank while "..config" is being typed
 * would push the footer out of it. The sentence is a polite live region, so that a screen reader
 * reads it out when it appears.
 */
@Composable
private fun EntryNameField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    errorText: String?,
    onDone: () -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        isError = errorText != null,
        supportingText = {
            Text(
                text = errorText.orEmpty(),
                color = ErrorRed,
                style = MaterialTheme.typography.bodySmall,
                minLines = 2,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = Modifier.fillMaxWidth(),
        colors = techticalFieldColors(),
    )
}

@Composable
private fun techticalFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Gold,
    unfocusedBorderColor = GoldMuted,
    focusedLabelColor = Gold,
    unfocusedLabelColor = TextSecondary,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    cursorColor = Gold,
    focusedContainerColor = SurfaceVariant,
    unfocusedContainerColor = SurfaceVariant,
    // In error the border, the label, the cursor and the sentence turn red; the text stays readable and
    // the container keeps its color (the Material default is transparent, the field would lose its fill
    // each time the name becomes refused).
    errorBorderColor = ErrorRed,
    errorLabelColor = ErrorRed,
    errorCursorColor = ErrorRed,
    errorTextColor = TextPrimary,
    errorContainerColor = SurfaceVariant,
    errorSupportingTextColor = ErrorRed,
)
