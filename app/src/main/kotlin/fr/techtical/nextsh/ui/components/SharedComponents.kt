// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.techtical.nextsh.domain.model.SessionStatus
import fr.techtical.nextsh.domain.model.TunnelStatus
import fr.techtical.nextsh.ui.theme.*

// ── Status Indicator ─────────────────────────────────────────────────────────

@Composable
fun StatusDot(
    status: ConnectionStatus,
    modifier: Modifier = Modifier,
    size: Int = 8,
) {
    val color by animateColorAsState(
        targetValue = when (status) {
            ConnectionStatus.CONNECTED -> SuccessGreen
            ConnectionStatus.CONNECTING, ConnectionStatus.RECONNECTING -> WarningAmber
            ConnectionStatus.DISCONNECTED -> TextSecondary
            ConnectionStatus.ERROR -> ErrorRed
        },
        animationSpec = tween(300),
        label = "statusDotColor",
    )
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(color)
    )
}

enum class ConnectionStatus {
    CONNECTED, CONNECTING, RECONNECTING, DISCONNECTED, ERROR;

    companion object {
        fun from(session: SessionStatus) = when (session) {
            SessionStatus.CONNECTED -> CONNECTED
            SessionStatus.CONNECTING -> CONNECTING
            SessionStatus.RECONNECTING -> RECONNECTING
            SessionStatus.DISCONNECTED -> DISCONNECTED
            SessionStatus.ERROR -> ERROR
        }

        fun from(tunnel: TunnelStatus) = when (tunnel) {
            TunnelStatus.ACTIVE -> CONNECTED
            TunnelStatus.STARTING -> CONNECTING
            TunnelStatus.RECONNECTING -> RECONNECTING
            TunnelStatus.STOPPED -> DISCONNECTED
            TunnelStatus.ERROR -> ERROR
        }
    }
}

// ── Empty State ──────────────────────────────────────────────────────────────

@Composable
fun EmptyState(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        icon()
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (action != null) {
            Spacer(modifier = Modifier.height(24.dp))
            action()
        }
    }
}

// ── Section Header ───────────────────────────────────────────────────────────

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = Gold,
            letterSpacing = MaterialTheme.typography.labelMedium.letterSpacing,
        )
        if (action != null) {
            action()
        }
    }
}

// ── NextSH Button ────────────────────────────────────────────────────────────

@Composable
fun NextShButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isDestructive: Boolean = false,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isDestructive) ErrorRed else Burgundy,
            contentColor = White,
            disabledContainerColor = SurfaceVariant,
            disabledContentColor = TextSecondary,
        ),
        shape = MaterialTheme.shapes.small,
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

// ── NextSH Text Field ────────────────────────────────────────────────────────

@Composable
fun NextShTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    isError: Boolean = false,
    readOnly: Boolean = false,
    trailingIcon: @Composable (() -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        readOnly = readOnly,
        label = { Text(label) },
        placeholder = if (placeholder.isNotEmpty()) {{ Text(placeholder, color = TextSecondary) }} else null,
        modifier = modifier.fillMaxWidth(),
        singleLine = singleLine,
        isError = isError,
        trailingIcon = trailingIcon,
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Gold,
            unfocusedBorderColor = SurfaceVariant,
            cursorColor = Gold,
            focusedLabelColor = Gold,
        ),
        shape = MaterialTheme.shapes.small,
    )
}

// ── Info Row ─────────────────────────────────────────────────────────────────

@Composable
fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ── Bottom Navigation Bar ────────────────────────────────────────────────────

data class BottomNavDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

@Composable
fun NextShBottomBar(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    destinations: List<BottomNavDestination>,
) {
    NavigationBar(
        containerColor = Surface,
        tonalElevation = 0.dp,
    ) {
        destinations.forEach { dest ->
            val selected = currentRoute == dest.route
            NavigationBarItem(
                selected = selected,
                onClick = { onNavigate(dest.route) },
                icon = {
                    Icon(
                        imageVector = dest.icon,
                        contentDescription = dest.label,
                    )
                },
                label = {
                    Text(
                        text = dest.label,
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = White,
                    selectedTextColor = Gold,
                    indicatorColor = Burgundy,
                    unselectedIconColor = TextSecondary,
                    unselectedTextColor = TextSecondary,
                ),
            )
        }
    }
}
