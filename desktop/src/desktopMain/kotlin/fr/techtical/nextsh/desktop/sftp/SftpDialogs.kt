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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.sftp_action_delete
import fr.techtical.nextsh.desktop.generated.resources.sftp_dialog_cancel
import fr.techtical.nextsh.desktop.generated.resources.sftp_dialog_chmod_apply
import fr.techtical.nextsh.desktop.generated.resources.sftp_dialog_chmod_title
import fr.techtical.nextsh.desktop.generated.resources.sftp_dialog_delete_confirm_title
import fr.techtical.nextsh.desktop.generated.resources.sftp_dialog_delete_multi_warning
import fr.techtical.nextsh.desktop.generated.resources.sftp_dialog_delete_single_file_warning
import fr.techtical.nextsh.desktop.generated.resources.sftp_dialog_delete_single_folder_warning
import fr.techtical.nextsh.desktop.generated.resources.sftp_dialog_new_folder_create
import fr.techtical.nextsh.desktop.generated.resources.sftp_dialog_new_folder_name_label
import fr.techtical.nextsh.desktop.generated.resources.sftp_dialog_new_folder_title
import fr.techtical.nextsh.desktop.generated.resources.sftp_dialog_rename_new_name_label
import fr.techtical.nextsh.desktop.generated.resources.sftp_dialog_rename_title
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

// All four dialogs are separate OS windows (DialogWindow). Same reason as the
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

@Composable
private fun TechticalDialog(
    onCloseRequest: () -> Unit,
    title: String,
    size: DpSize,
    content: @Composable () -> Unit,
) {
    DialogWindow(
        onCloseRequest = onCloseRequest,
        state = rememberDialogState(size = size),
        title = title,
        resizable = false,
    ) {
        // Un seul point d'ancrage pour les quatre dialogs SFTP : chemins et noms
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
    var name by remember { mutableStateOf("") }
    val submit = {
        val trimmed = name.trim()
        if (trimmed.isNotEmpty()) onConfirm(trimmed)
    }
    val dialogTitle = stringResource(Res.string.sftp_dialog_new_folder_title)
    val fieldLabel = stringResource(Res.string.sftp_dialog_new_folder_name_label)
    val createLabel = stringResource(Res.string.sftp_dialog_new_folder_create)
    TechticalDialog(
        onCloseRequest = onDismiss,
        title = dialogTitle,
        // 16 pad + 24 header + 12 + 56 field + 16 + 40 footer + 16 pad + ~32 title bar = 212
        size = DpSize(420.dp, 220.dp),
    ) {
        DialogShell {
            DialogHeader(icon = Icons.Default.CreateNewFolder, title = dialogTitle)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(fieldLabel) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth(),
                colors = techticalFieldColors(),
            )
            Spacer(Modifier.height(16.dp))
            DialogFooter(
                confirmLabel = createLabel,
                confirmEnabled = name.trim().isNotEmpty(),
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
    val submit = {
        val trimmed = name.trim()
        if (trimmed.isNotEmpty() && trimmed != target.name) onConfirm(target.path, trimmed)
    }
    val renameTitle = stringResource(Res.string.sftp_dialog_rename_title)
    val newNameLabel = stringResource(Res.string.sftp_dialog_rename_new_name_label)
    TechticalDialog(
        onCloseRequest = onDismiss,
        title = renameTitle,
        // 16 + 24 + 6 + 16 name + 12 + 56 field + 16 + 40 + 16 + 32 title bar = 234
        size = DpSize(420.dp, 240.dp),
    ) {
        DialogShell {
            DialogHeader(icon = Icons.Default.DriveFileRenameOutline, title = renameTitle)
            Spacer(Modifier.height(6.dp))
            Text(
                text = target.name,
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(newNameLabel) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth(),
                colors = techticalFieldColors(),
            )
            Spacer(Modifier.height(16.dp))
            DialogFooter(
                confirmLabel = renameTitle,
                confirmEnabled = name.trim().isNotEmpty() && name.trim() != target.name,
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
    val title = if (single != null) "${deleteAction} ${single.name}" else "${deleteAction} ${targets.size}"
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
                            text = it.name,
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
                label = "User",
                r = bits.ur, w = bits.uw, x = bits.ux,
                onR = { bits = bits.copy(ur = it) },
                onW = { bits = bits.copy(uw = it) },
                onX = { bits = bits.copy(ux = it) },
            )
            PermRow(
                label = "Group",
                r = bits.gr, w = bits.gw, x = bits.gx,
                onR = { bits = bits.copy(gr = it) },
                onW = { bits = bits.copy(gw = it) },
                onX = { bits = bits.copy(gx = it) },
            )
            PermRow(
                label = "Other",
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
        Box(modifier = Modifier.width(80.dp))
        listOf("Read", "Write", "Execute").forEach {
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
            modifier = Modifier.width(80.dp),
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
)
