// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.vault

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.TriangleAlert
import fr.techtical.nextsh.desktop.theme.Border1
import fr.techtical.nextsh.desktop.theme.Border2
import fr.techtical.nextsh.desktop.theme.Burgundy
import fr.techtical.nextsh.desktop.theme.GoldLight
import fr.techtical.nextsh.desktop.theme.JetBrainsMonoFamily
import fr.techtical.nextsh.desktop.theme.Radii
import fr.techtical.nextsh.desktop.theme.Spacing
import fr.techtical.nextsh.desktop.theme.SpaceGroteskFamily
import fr.techtical.nextsh.desktop.theme.Surface
import fr.techtical.nextsh.desktop.theme.SurfaceVariant
import fr.techtical.nextsh.desktop.theme.TextDisabled
import fr.techtical.nextsh.desktop.theme.TextPrimary
import fr.techtical.nextsh.desktop.theme.TextSecondary
import fr.techtical.nextsh.desktop.theme.WarningAmber
import fr.techtical.nextsh.desktop.theme.White
import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.recovery_acknowledge_label
import fr.techtical.nextsh.desktop.generated.resources.recovery_continue
import fr.techtical.nextsh.desktop.generated.resources.recovery_display_title
import fr.techtical.nextsh.desktop.generated.resources.recovery_display_warning
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Download
import fr.techtical.nextsh.desktop.generated.resources.action_copy
import fr.techtical.nextsh.desktop.generated.resources.recovery_copied
import fr.techtical.nextsh.desktop.generated.resources.recovery_download
import fr.techtical.nextsh.desktop.generated.resources.recovery_file_header
import java.io.File
import javax.swing.JFileChooser

/**
 * One-time display of a 12-word BIP39 recovery phrase, shared between vault
 * creation (FirstLaunchScreen) and recovery-phrase management (Settings).
 *
 * Renders a numbered 2-column grid of the words, a hard warning that the
 * phrase is shown only once and is the sole way to recover the vault if the
 * passphrase is forgotten, and a mandatory acknowledgement toggle that gates
 * the [onAcknowledged] continue action.
 *
 * The [words] are necessarily displayed in plaintext on screen: that is by
 * design for a recovery phrase. The list is never logged and is not retained
 * beyond the lifetime of this composable by the caller.
 *
 * @param titleRes optional override for the heading; defaults to the generic
 *   "your recovery phrase" title (used at creation and on regeneration).
 */
@Composable
fun RecoveryPhraseDisplay(
    words: List<String>,
    onAcknowledged: () -> Unit,
    modifier: Modifier = Modifier,
    titleRes: StringResource = Res.string.recovery_display_title,
    continueLabelRes: StringResource = Res.string.recovery_continue,
) {
    var acknowledged by remember { mutableStateOf(false) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth(),
    ) {
        Icon(
            Lucide.TriangleAlert,
            contentDescription = null,
            tint = WarningAmber,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(Spacing.Md))
        Text(
            text = stringResource(titleRes),
            color = TextPrimary,
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 17.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.Sm))
        Text(
            text = stringResource(Res.string.recovery_display_warning),
            color = TextSecondary,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.Lg))

        RecoveryWordGrid(words)

        Spacer(Modifier.height(Spacing.Md))

        PhraseActionRow(words = words, dialogTitle = stringResource(titleRes))

        Spacer(Modifier.height(Spacing.Lg))

        AcknowledgeRow(
            checked = acknowledged,
            onToggle = { acknowledged = !acknowledged },
        )

        Spacer(Modifier.height(Spacing.Lg))

        Button(
            onClick = onAcknowledged,
            enabled = acknowledged,
            modifier = Modifier
                .fillMaxWidth()
                .pointerHoverIcon(PointerIcon.Hand),
            colors = ButtonDefaults.buttonColors(
                containerColor = Burgundy,
                contentColor = White,
                disabledContainerColor = Burgundy.copy(alpha = 0.4f),
                disabledContentColor = White.copy(alpha = 0.6f),
            ),
            shape = RoundedCornerShape(Radii.Md),
            contentPadding = PaddingValues(vertical = 10.dp),
        ) {
            Text(
                text = stringResource(continueLabelRes),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/**
 * Numbered 2-column grid of the mnemonic words. Each cell is a chip
 * "<index>. <word>" in the dark surface variant, monospaced for legibility.
 */
@Composable
private fun RecoveryWordGrid(words: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceVariant, RoundedCornerShape(Radii.Md))
            .border(BorderStroke(1.dp, Border2), RoundedCornerShape(Radii.Md))
            .padding(Spacing.Md),
        verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
    ) {
        // Two words per row → 6 rows for a 12-word phrase. chunked tolerates
        // any length defensively (e.g. an empty list renders no rows).
        words.chunked(2).forEachIndexed { rowIndex, pair ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
            ) {
                pair.forEachIndexed { colIndex, word ->
                    RecoveryWordCell(
                        index = rowIndex * 2 + colIndex + 1,
                        word = word,
                        modifier = Modifier.weight(1f),
                    )
                }
                // Pad an odd final row so the single cell keeps half-width.
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun RecoveryWordCell(index: Int, word: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(Surface, RoundedCornerShape(Radii.Sm))
            .border(BorderStroke(1.dp, Border1), RoundedCornerShape(Radii.Sm))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$index.",
            color = TextDisabled,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 11.sp,
            modifier = Modifier.width(22.dp),
        )
        Text(
            text = word,
            color = GoldLight,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * Mandatory acknowledgement toggle, "I have saved my recovery phrase". A
 * custom square check chip (no Material Checkbox) to match the Techtical Dark
 * DA. The whole row is the click target.
 */
@Composable
private fun AcknowledgeRow(checked: Boolean, onToggle: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interactionSource, indication = null) { onToggle() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .background(
                    if (checked) Burgundy else SurfaceVariant,
                    RoundedCornerShape(Radii.Sm),
                )
                .border(
                    BorderStroke(1.dp, if (checked) Burgundy else Border2),
                    RoundedCornerShape(Radii.Sm),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Icon(
                    Lucide.Check,
                    contentDescription = null,
                    tint = White,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
        Spacer(Modifier.width(Spacing.Sm))
        Text(
            text = stringResource(Res.string.recovery_acknowledge_label),
            color = TextPrimary,
            fontSize = 12.sp,
        )
    }
}

/**
 * "Copy" + "Download" actions for the recovery phrase. Copy puts the
 * space-joined 12 words on the clipboard; Download writes them to a plain
 * `.txt` file (with a short header) at a user-chosen location. Both are
 * user-initiated (the phrase is meant to be saved by the user) and neither
 * logs the phrase.
 */
@Composable
private fun PhraseActionRow(words: List<String>, dialogTitle: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    val phrase = remember(words) { words.joinToString(" ") }
    val fileHeader = stringResource(Res.string.recovery_file_header)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
    ) {
        PhraseActionButton(
            icon = Lucide.Copy,
            label = stringResource(if (copied) Res.string.recovery_copied else Res.string.action_copy),
            onClick = {
                clipboard.setText(AnnotatedString(phrase))
                copied = true
            },
            modifier = Modifier.weight(1f),
        )
        PhraseActionButton(
            icon = Lucide.Download,
            label = stringResource(Res.string.recovery_download),
            onClick = { savePhraseToFile("$fileHeader\n\n$phrase\n", dialogTitle) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PhraseActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.pointerHoverIcon(PointerIcon.Hand),
        shape = RoundedCornerShape(Radii.Md),
        border = BorderStroke(1.dp, Border2),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(text = label, fontSize = 12.sp)
    }
}

/**
 * Opens a native save dialog and writes the recovery phrase as a plain `.txt`
 * file. Writing the phrase to disk is the user's explicit choice (same intent
 * as writing it on paper); the content is never logged.
 */
private fun savePhraseToFile(content: String, dialogTitle: String) {
    val chooser = JFileChooser().apply {
        this.dialogTitle = dialogTitle
        selectedFile = File("nextsh-recovery-phrase.txt")
    }
    if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
        val chosen = chooser.selectedFile ?: return
        val target = if (chosen.name.lowercase().endsWith(".txt")) {
            chosen
        } else {
            File(chosen.absolutePath + ".txt")
        }
        runCatching { target.writeText(content) }
    }
}
