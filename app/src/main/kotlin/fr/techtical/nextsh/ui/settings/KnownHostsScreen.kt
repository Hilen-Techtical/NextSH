// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ShieldCheck
import com.composables.icons.lucide.Trash2
import fr.techtical.nextsh.R
import fr.techtical.nextsh.core.ssh.KnownHostEntry
import fr.techtical.nextsh.ui.theme.Border1
import fr.techtical.nextsh.ui.theme.ErrorRed
import fr.techtical.nextsh.ui.theme.Gold
import fr.techtical.nextsh.ui.theme.GoldLight
import fr.techtical.nextsh.ui.theme.GoldMuted
import fr.techtical.nextsh.ui.theme.JetBrainsMonoFamily
import fr.techtical.nextsh.ui.theme.NearBlack
import fr.techtical.nextsh.ui.theme.Radii
import fr.techtical.nextsh.ui.theme.SpaceGroteskFamily
import fr.techtical.nextsh.ui.theme.Spacing
import fr.techtical.nextsh.ui.theme.Surface
import fr.techtical.nextsh.ui.theme.TextDisabled
import fr.techtical.nextsh.ui.theme.TextPrimary
import fr.techtical.nextsh.ui.theme.TextSecondary
import fr.techtical.nextsh.ui.theme.White

/**
 * KnownHostsScreen : refonte Phase 3.1 (DA Techtical portée du Desktop).
 *
 * TopAppBar refondu (titre Space Grotesk + subtitle compteur), action
 * "Tout effacer" déplacée du bas dans la TopAppBar (icon-button danger
 * conditionnel), KnownHostCard refondue avec icon-wrap Gold ShieldCheck +
 * AlgorithmBadge + fingerprint mono + ajout date "Ajouté le …" (parité
 * Desktop). Empty state restylé.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnownHostsScreen(
    onBack: () -> Unit,
    viewModel: KnownHostsViewModel = hiltViewModel(),
) {
    val entries by viewModel.entries.collectAsState()
    var showClearAllDialog by remember { mutableStateOf(false) }
    var entryToDelete by remember { mutableStateOf<KnownHostEntry?>(null) }

    val subtitle = when {
        entries.isEmpty() -> stringResource(R.string.empty_no_known_hosts_title)
        entries.size == 1 -> "1 empreinte enregistrée"
        else -> "${entries.size} empreintes enregistrées"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.title_known_hosts),
                            color = TextPrimary,
                            fontFamily = SpaceGroteskFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 20.sp,
                        )
                        Text(
                            text = subtitle,
                            color = TextDisabled,
                            fontSize = 12.sp,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                            tint = TextPrimary,
                        )
                    }
                },
                actions = {
                    if (entries.isNotEmpty()) {
                        BtnDangerSm(
                            onClick = { showClearAllDialog = true },
                            icon = Lucide.Trash2,
                            label = stringResource(R.string.action_delete_all),
                        )
                        Spacer(Modifier.width(Spacing.Sm))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = NearBlack),
            )
        },
        containerColor = NearBlack,
    ) { padding ->
        if (entries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = Spacing.Lg, vertical = Spacing.Md),
                verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
            ) {
                items(entries, key = { it.hostPort }) { entry ->
                    KnownHostCard(entry = entry, onDelete = { entryToDelete = entry })
                }
                item { Spacer(Modifier.height(Spacing.Lg)) }
            }
        }
    }

    // ── Dialogue confirmation suppression d'une entrée ─────────────────
    entryToDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { entryToDelete = null },
            title = {
                Text(
                    text = stringResource(R.string.dialog_delete_known_host_title),
                    color = TextPrimary,
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.SemiBold,
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.dialog_delete_known_host_message, entry.hostPort),
                    color = TextSecondary,
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                BtnDangerSolid(
                    onClick = {
                        viewModel.removeEntry(entry.hostPort)
                        entryToDelete = null
                    },
                    label = stringResource(R.string.action_delete),
                )
            },
            dismissButton = {
                TextButton(onClick = { entryToDelete = null }) {
                    Text(stringResource(R.string.action_cancel), color = TextSecondary)
                }
            },
            containerColor = Surface,
            shape = RoundedCornerShape(Radii.Lg),
        )
    }

    // ── Dialogue confirmation tout effacer ─────────────────────────────
    if (showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.dialog_delete_all_known_hosts_title),
                    color = TextPrimary,
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.SemiBold,
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.dialog_delete_all_known_hosts_message),
                    color = TextSecondary,
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                BtnDangerSolid(
                    onClick = {
                        viewModel.clearAll()
                        showClearAllDialog = false
                    },
                    label = stringResource(R.string.action_delete_all),
                )
            },
            dismissButton = {
                TextButton(onClick = { showClearAllDialog = false }) {
                    Text(stringResource(R.string.action_cancel), color = TextSecondary)
                }
            },
            containerColor = Surface,
            shape = RoundedCornerShape(Radii.Lg),
        )
    }
}

// ── Card ─────────────────────────────────────────────────────────────────────

@Composable
private fun KnownHostCard(entry: KnownHostEntry, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface, RoundedCornerShape(Radii.Lg))
            .border(1.dp, Border1, RoundedCornerShape(Radii.Lg))
            .padding(horizontal = Spacing.Md, vertical = Spacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(Gold.copy(alpha = 0.08f), RoundedCornerShape(Radii.Md)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Lucide.ShieldCheck, contentDescription = null, tint = Gold, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(Spacing.Md))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.hostPort,
                    color = TextPrimary,
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(Spacing.Xs))
                AlgorithmBadge(entry.algorithm)
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = entry.fingerprint,
                color = TextSecondary,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(Spacing.Sm))
        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                Lucide.Trash2,
                contentDescription = stringResource(R.string.action_delete_known_host, entry.hostPort),
                tint = ErrorRed.copy(alpha = 0.8f),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun AlgorithmBadge(algorithm: String) {
    Box(
        modifier = Modifier
            .background(Gold.copy(alpha = 0.08f), RoundedCornerShape(3.dp))
            .border(1.dp, Gold.copy(alpha = 0.20f), RoundedCornerShape(3.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(
            text = algorithm,
            color = GoldLight,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.5.sp,
        )
    }
}

// ── Empty state ──────────────────────────────────────────────────────────────

@Composable
private fun EmptyState() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = Spacing.Xl),
    ) {
        Icon(
            Lucide.ShieldCheck,
            contentDescription = null,
            tint = GoldMuted,
            modifier = Modifier.size(64.dp),
        )
        Spacer(Modifier.height(Spacing.Md))
        Text(
            text = stringResource(R.string.empty_no_known_hosts_title),
            color = TextPrimary,
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp,
        )
        Spacer(Modifier.height(Spacing.Xs))
        Text(
            text = stringResource(R.string.empty_no_known_hosts_subtitle),
            color = TextSecondary,
            fontSize = 12.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

// ── Boutons ──────────────────────────────────────────────────────────────────

@Composable
private fun BtnDangerSm(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
) {
    OutlinedButton(
        onClick = onClick,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Color.Transparent,
            contentColor = ErrorRed,
        ),
        border = BorderStroke(1.dp, ErrorRed.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(Radii.Md),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = ErrorRed, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun BtnDangerSolid(onClick: () -> Unit, label: String) {
    OutlinedButton(
        onClick = onClick,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = ErrorRed,
            contentColor = White,
        ),
        border = BorderStroke(0.dp, Color.Transparent),
        shape = RoundedCornerShape(Radii.Md),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, color = White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

