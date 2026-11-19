// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sftp

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Archive
import com.composables.icons.lucide.Code
import com.composables.icons.lucide.File
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.Film
import com.composables.icons.lucide.Folder
import com.composables.icons.lucide.Image
import com.composables.icons.lucide.Link
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Music
import com.composables.icons.lucide.TriangleAlert
import fr.techtical.nextsh.R
import fr.techtical.nextsh.core.ssh.uploadLeftoverName
import fr.techtical.nextsh.domain.model.SftpFile
import fr.techtical.nextsh.ui.theme.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Carte de fichier/dossier SFTP : Phase 3.3 DA Techtical.
 *
 * Surface + Border1 + Radii.Md, sélection signalée par bg Burgundy@0.18 +
 * border Gold 1dp. Icône Lucide par extension dans un wrap SurfaceVariant
 * 36dp Radii.Sm + couleur sémantique. Filename Space Grotesk Medium 14sp,
 * meta line mono 11sp TextSecondary, taille mono 11sp TextDisabled à
 * droite. Symlink → overlay Lucide.Link InfoBlue 12dp en bas-droite.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SftpFileItem(
    file: SftpFile,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    /**
     * Non-null when the file is the leftover of an interrupted upload: the name of the file it was to
     * replace, ready to show (see [leftoverOriginalLabel]). The row then carries a warning icon and a
     * line that says so.
     */
    leftoverOf: String? = null,
    modifier: Modifier = Modifier,
) {
    val backgroundColor = if (isSelected) Burgundy.copy(alpha = 0.18f) else Surface
    val borderColor     = if (isSelected) Gold else Border1

    // The date follows the language of the loaded texts (resourceLocale), and is rebuilt when the
    // language changes: the formatter is not a top-level value that would keep the locale of the
    // first composition.
    val locale = resourceLocale()
    val dateFormatter = remember(locale) {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    }
    val formatSize = rememberSizeFormatter()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.Md))
            .background(backgroundColor, RoundedCornerShape(Radii.Md))
            .border(1.dp, borderColor, RoundedCornerShape(Radii.Md))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = Spacing.Md, vertical = Spacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
    ) {
        if (isSelectionMode) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = null,
                colors = CheckboxDefaults.colors(
                    checkedColor   = Burgundy,
                    checkmarkColor = White,
                    uncheckedColor = TextSecondary,
                ),
                modifier = Modifier.size(20.dp),
            )
        }

        // ── Icon-wrap 36dp, fond accent doux par type ─────────────────────────
        val iconColor = if (leftoverOf != null) WarningAmber else fileIconTint(file)
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(Radii.Sm))
                .background(iconColor.copy(alpha = 0.10f), RoundedCornerShape(Radii.Sm)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector        = if (leftoverOf != null) Lucide.TriangleAlert else fileIcon(file),
                contentDescription = null,
                tint               = iconColor,
                modifier           = Modifier.size(18.dp),
            )
            if (file.isSymlink) {
                Icon(
                    imageVector        = Lucide.Link,
                    contentDescription = null,
                    tint               = InfoBlue,
                    modifier           = Modifier
                        .align(Alignment.BottomEnd)
                        .size(10.dp),
                )
            }
        }

        // ── Nom + meta ────────────────────────────────────────────────────────
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text     = displayEntryName(file.name),
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.Medium,
                fontSize   = 14.sp,
                color    = if (isSelected) GoldLight else TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (leftoverOf != null) {
                // What the file is, said under its name on two lines of their own: the badge, then whose
                // incomplete copy it is. Side by side, the name of the original was cut off on a narrow
                // screen, and a large font let the badge take the whole width.
                Spacer(Modifier.height(2.dp))
                Text(
                    text       = stringResource(R.string.sftp_leftover_badge),
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize   = 11.sp,
                    color      = WarningAmber,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis,
                )
                Text(
                    text       = stringResource(R.string.sftp_leftover_detail, leftoverOf),
                    fontFamily = SpaceGroteskFamily,
                    fontSize   = 11.sp,
                    color      = WarningAmber,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text     = buildMeta(file, dateFormatter),
                fontFamily = JetBrainsMonoFamily,
                fontSize   = 11.sp,
                color      = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // ── Taille (fichiers uniquement) ──────────────────────────────────────
        if (!file.isDirectory) {
            Text(
                text       = formatSize(file.size),
                fontFamily = JetBrainsMonoFamily,
                fontSize   = 11.sp,
                color      = TextDisabled,
                textAlign  = TextAlign.End,
            )
        }
    }
}

/**
 * The name of the file the leftover [fileName] was to replace, ready to show: invisible characters made
 * visible ([displayEntryName]) and a cut mark when the start may be shorter than the real name. Null when
 * [fileName] is not the name of a leftover.
 */
internal fun leftoverOriginalLabel(fileName: String): String? {
    val leftover = uploadLeftoverName(fileName) ?: return null
    return displayEntryName(leftover.original) + if (leftover.cut) "…" else ""
}

private fun buildMeta(file: SftpFile, dateFormatter: DateTimeFormatter): String = buildString {
    append(file.permissionsString)
    if (file.modifiedAt > 0L) {
        append("  ")
        append(formatDate(file.modifiedAt, dateFormatter))
    }
    if (file.owner.isNotEmpty()) {
        append("  ")
        append(file.owner)
    }
}

// ── Icônes Lucide par type/extension ──────────────────────────────────────────

private fun fileIcon(file: SftpFile): ImageVector = when {
    file.isDirectory                -> Lucide.Folder
    file.extension in IMAGE_EXTS    -> Lucide.Image
    file.extension in ARCHIVE_EXTS  -> Lucide.Archive
    file.extension in CODE_EXTS     -> Lucide.Code
    file.extension in VIDEO_EXTS    -> Lucide.Film
    file.extension in AUDIO_EXTS    -> Lucide.Music
    file.extension in PDF_EXTS      -> Lucide.FileText
    file.isTextFile                 -> Lucide.FileText
    else                            -> Lucide.File
}

private fun fileIconTint(file: SftpFile): Color = when {
    file.isDirectory                -> Gold
    file.extension in IMAGE_EXTS    -> InfoBlue
    file.extension in CODE_EXTS     -> SuccessGreen
    file.extension in ARCHIVE_EXTS  -> WarningAmber
    file.extension in VIDEO_EXTS    -> BurgundyLight
    file.extension in AUDIO_EXTS    -> BioViolet
    file.extension in PDF_EXTS      -> ErrorRed
    file.isTextFile                 -> GoldLight
    else                            -> TextSecondary
}

private val IMAGE_EXTS   = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp", "svg", "ico")
private val ARCHIVE_EXTS = setOf("zip", "tar", "gz", "bz2", "xz", "7z", "rar", "zst")
private val CODE_EXTS    = setOf(
    "sh", "bash", "zsh", "fish", "py", "rb", "js", "ts", "kt", "kts", "java",
    "c", "cpp", "h", "hpp", "go", "rs", "swift", "php", "pl", "lua", "r", "sql",
    "html", "css", "scss", "less", "json", "xml", "yml", "yaml", "toml",
    "makefile", "dockerfile",
)
private val VIDEO_EXTS = setOf("mp4", "mkv", "avi", "mov", "webm", "flv")
private val AUDIO_EXTS = setOf("mp3", "flac", "ogg", "aac", "wav", "m4a")
private val PDF_EXTS   = setOf("pdf")

// ── Formatage ─────────────────────────────────────────────────────────────────

private fun formatDate(epochMillis: Long, formatter: DateTimeFormatter): String =
    runCatching {
        Instant.ofEpochMilli(epochMillis)
            .atZone(ZoneId.systemDefault())
            .format(formatter)
    }.getOrDefault("")
