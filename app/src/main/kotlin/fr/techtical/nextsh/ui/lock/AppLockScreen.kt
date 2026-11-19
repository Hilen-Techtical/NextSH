// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.lock

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import fr.techtical.nextsh.ui.vault.VaultUnlockPanel
import fr.techtical.nextsh.ui.vault.VaultUnlockViewModel

/**
 * The lock screen: the unlock panel over the whole app, in a window of its own.
 *
 * A window and not a layer of the composition of the activity, on purpose. A dialog window created
 * after another one is drawn above it, so this one also covers the dialogs and sheets that were open
 * when the app locked ("Delete this key?" must not stay tappable under the lock). A layer inside the
 * window of the activity would pass under them.
 *
 * Nothing under it moves: the navigation, the forms, the terminal tabs, the split and the tunnel
 * browser stay as they were, and sessions and tunnels keep running (see `AppLock`). `MainActivity`
 * composes it at the very end of its content, after the hosts of the prompts, so that its window is
 * created after theirs; `NavGraph`, which knows which screen is up, says whether it is needed, and does
 * not ask for it over the unlock screen of the launch, a screen of its own. The prompts that need a
 * person do not show while the app is locked, and the ones that were up end when it locks
 * (`PromptsEndedByLock`), so none is created above it. The dialogs of the tunnel browser wait for the
 * unlock for the same reason.
 *
 * - Full screen and opaque, system bars included (`usePlatformDefaultWidth` and `decorFitsSystemWindows`
 *   off, the panel paints its whole background), and no tap outside dismisses it.
 * - Back leaves the app (`moveTaskToBack`), it never uncovers the content: the dialog is not removed by
 *   its dismiss request, only `AppLock.unlock()` removes it.
 * - The secure flag is the default `securePolicy`: inherited from the window of the activity, which
 *   `ScreenCapturePolicy` protects for good. This file does not touch `FLAG_SECURE`, see the rule in
 *   CLAUDE.md.
 *
 * It uses the same view model as the unlock screen of the launch, so the unlock is the same steps:
 * the system dialog of the coordinator, then the opening of the vault, then `AppLock.unlock()`.
 */
@Composable
fun AppLockScreen(viewModel: VaultUnlockViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val activity = LocalContext.current.findActivity()

    // What the phone can do is asked again each time the screen is resumed, not only when it comes up
    // (the user may have set up a lock in the settings of the phone and come back). When the lock
    // screen goes, the last error goes with it.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, viewModel) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { viewModel.refresh() }
    }
    DisposableEffect(viewModel) {
        onDispose { viewModel.dismissError() }
    }

    Dialog(
        onDismissRequest = { activity?.moveTaskToBack(true) },
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        LightSystemBarIcons()
        VaultUnlockPanel(
            biometricAvailable = state.biometricAvailable,
            isAuthenticating = state.isAuthenticating,
            errorRes = state.errorRes,
            onUnlock = viewModel::authenticate,
        )
    }
}

/**
 * The window of the dialog has its own system bar appearance: on the dark panel the icons have to be
 * light (the "light bars" flags off), whatever the theme of the phone says.
 */
@Composable
private fun LightSystemBarIcons() {
    val view = LocalView.current
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
    }
}

/** The [Activity] behind this context, through any wrappers, or null. */
private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
