// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sftp

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.sftp_preview_close
import fr.techtical.nextsh.desktop.generated.resources.sftp_preview_collapse
import fr.techtical.nextsh.desktop.generated.resources.sftp_preview_expand
import fr.techtical.nextsh.desktop.generated.resources.sftp_preview_image_unsupported
import fr.techtical.nextsh.desktop.generated.resources.sftp_preview_unavailable_title
import fr.techtical.nextsh.desktop.theme.ErrorRed
import fr.techtical.nextsh.desktop.theme.Gold
import fr.techtical.nextsh.desktop.theme.GoldMuted
import fr.techtical.nextsh.desktop.theme.NearBlack
import fr.techtical.nextsh.desktop.theme.Surface
import fr.techtical.nextsh.desktop.theme.TextPrimary
import fr.techtical.nextsh.desktop.theme.TextSecondary
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.skia.Image as SkiaImage

/**
 * Right-side preview panel, sized entirely by the caller through [modifier].
 * [fr.techtical.nextsh.desktop.sftp.SftpBrowserScreen] wraps this composable
 * in a weighted [Box] whose weight is a locally-owned, draggable split ratio
 * (see `SftpSplitRow`), mirroring the local-ratio pattern used by
 * [fr.techtical.nextsh.desktop.sessions.SplitLayout] for terminal panes. This
 * composable itself never fixes a width, so it can be embedded at any size.
 *
 * The close icon dismisses the preview; the expand icon toggles a
 * caller-driven "expanded" mode where the file list is hidden and the panel
 * takes the full row width: [expanded] and [onToggleExpand] are plumbed
 * through from that same caller-owned state.
 *
 * Text: JetBrains Mono, Gold line numbers, soft-wrapped, vertical scrollbar,
 * selectable, 512 KB cap (enforced in the VM).
 * Image: Skia-decoded, mouse-wheel zoom 0.5× → 8×, 2 MB cap.
 *
 * The VM remains the single source of truth for the preview *content*
 * ([PreviewState]); this composable is a pure projection of it plus the
 * caller-owned geometry/expansion flags.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SftpPreviewPanel(
    state: PreviewState,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Surface),
    ) {
        PreviewHeader(
            fileName = state.file.name,
            expanded = expanded,
            onToggleExpand = onToggleExpand,
            onClose = onClose,
        )
        Box(modifier = Modifier.weight(1f).fillMaxWidth().background(NearBlack)) {
            when (state) {
                is PreviewState.Loading -> PreviewLoading()
                is PreviewState.Text -> TextPreview(content = state.content)
                is PreviewState.Image -> ImagePreview(bytes = state.bytes)
                is PreviewState.Error -> PreviewError(message = state.message)
            }
        }
    }
}

@Composable
private fun PreviewHeader(
    fileName: String,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = fileName,
            color = TextPrimary,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
        IconButton(onClick = onToggleExpand, modifier = Modifier.size(32.dp)) {
            Icon(
                imageVector = if (expanded) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                contentDescription = stringResource(
                    if (expanded) Res.string.sftp_preview_collapse else Res.string.sftp_preview_expand,
                ),
                tint = Gold,
            )
        }
        IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Close, contentDescription = stringResource(Res.string.sftp_preview_close), tint = Gold)
        }
    }
}

@Composable
private fun PreviewLoading() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Gold)
    }
}

@Composable
private fun PreviewError(message: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(Res.string.sftp_preview_unavailable_title),
            color = ErrorRed,
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            color = TextSecondary,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun TextPreview(content: String) {
    // `split('\n')` is a conservative substitute for a proper line splitter:
    // it keeps CR intact in mixed CRLF content, which we render as-is.
    val lines = remember(content) { content.split('\n') }
    val listState = rememberLazyListState()
    val digitWidth = (lines.size.toString().length * 8).dp.coerceAtLeast(28.dp)
    // Selectable + soft-wrapped: no `horizontalScroll` here on purpose, a
    // fixed-width panel with unwrapped lines forced a horizontal scrollbar on
    // every file, which is what this replaces. Lines now wrap to the
    // available panel width and a vertical scrollbar (same style as the
    // terminal's, see ComposeTerminalRenderer) takes over for navigation.
    SelectionContainer {
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(vertical = 4.dp, horizontal = 4.dp),
            ) {
                itemsIndexed(lines) { index, line ->
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            text = (index + 1).toString(),
                            color = Gold,
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                            ),
                            modifier = Modifier
                                .width(digitWidth)
                                .padding(end = 8.dp),
                            textAlign = TextAlign.End,
                        )
                        Text(
                            text = line,
                            color = TextPrimary,
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                            ),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(listState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                style = ScrollbarStyle(
                    minimalHeight = 16.dp,
                    thickness = 8.dp,
                    shape = RoundedCornerShape(4.dp),
                    hoverDurationMillis = 300,
                    unhoverColor = TextSecondary.copy(alpha = 0.25f),
                    hoverColor = TextSecondary.copy(alpha = 0.45f),
                ),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun ImagePreview(bytes: ByteArray) {
    val bitmap = remember(bytes) {
        try {
            SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
        } catch (_: Exception) {
            null
        }
    }
    if (bitmap == null) {
        PreviewError(message = stringResource(Res.string.sftp_preview_image_unsupported))
        return
    }
    var zoom by remember { mutableStateOf(1f) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPointerEvent(PointerEventType.Scroll) { event ->
                val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                // Scroll up (negative y in Compose) → zoom in.
                zoom = (zoom - delta * 0.1f).coerceIn(MIN_ZOOM, MAX_ZOOM)
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier.scale(zoom),
        )
        Text(
            text = "%.1f×".format(zoom),
            color = GoldMuted,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp)
                .background(NearBlack.copy(alpha = 0.7f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

private const val MIN_ZOOM = 0.5f
private const val MAX_ZOOM = 8f
