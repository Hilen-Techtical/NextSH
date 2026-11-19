// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sync

import androidx.compose.runtime.Composable
import fr.techtical.nextsh.desktop.components.RelativeTimeLabel
import fr.techtical.nextsh.desktop.components.relativeTimeLabel

/**
 * The time since [timestamp] as text ("il y a 5 min", or a date past 7 days), through the single
 * implementation shared by every Desktop screen (components/RelativeTimeText.kt).
 *
 * The typed model of a conflict card (TypedEntity, decodeTypedEntity, diffTypedFields) lives in
 * :shared under fr.techtical.nextsh.shared.ui.conflict. It holds no sentence: the Desktop turns
 * it into text in ConflictLabels.kt.
 */
@Composable
internal fun relativeTime(timestamp: Long): RelativeTimeLabel = relativeTimeLabel(timestamp)
