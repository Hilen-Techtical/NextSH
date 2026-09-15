// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh

import android.content.Intent
import android.os.Bundle
import fr.techtical.nextsh.core.ScreenCapturePolicy
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.AndroidEntryPoint
import fr.techtical.nextsh.core.shortcut.ShortcutUpdater
import fr.techtical.nextsh.core.ssh.HostKeyPromptCoordinator
import fr.techtical.nextsh.ui.components.HostKeyPromptDialog
import fr.techtical.nextsh.ui.navigation.NavGraph
import fr.techtical.nextsh.ui.theme.TechticalTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var hostKeyPromptCoordinator: HostKeyPromptCoordinator

    private var pendingShortcutAction = mutableStateOf<String?>(null)
    private var pendingHostId = mutableStateOf<String?>(null)
    private var pendingTunnelId = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Install the AndroidX cold-start splash before super.onCreate. Dismisses
        // immediately to the normal UI (no keep-on-screen condition / artificial delay).
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ScreenCapturePolicy.protect(window)
        extractShortcutExtras(intent)

        setContent {
            val shortcutAction by pendingShortcutAction
            val hostId by pendingHostId
            val tunnelId by pendingTunnelId

            TechticalTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    NavGraph(
                        pendingShortcutAction = shortcutAction,
                        pendingHostId = hostId,
                        pendingTunnelId = tunnelId,
                        onShortcutConsumed = {
                            pendingShortcutAction.value = null
                            pendingHostId.value = null
                            pendingTunnelId.value = null
                        },
                    )
                    // Overlay global : TOFU host key prompt déclenché depuis n'importe
                    // quel écran (HostList, Tunnels, …) via le callback wiré App-side.
                    HostKeyPromptDialog(coordinator = hostKeyPromptCoordinator)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractShortcutExtras(intent)
    }

    private fun extractShortcutExtras(intent: Intent?) {
        pendingShortcutAction.value = intent?.getStringExtra(ShortcutUpdater.EXTRA_SHORTCUT_ACTION)
        pendingHostId.value = intent?.getStringExtra(ShortcutUpdater.EXTRA_HOST_ID)
        pendingTunnelId.value = intent?.getStringExtra(ShortcutUpdater.EXTRA_TUNNEL_ID)
    }
}
