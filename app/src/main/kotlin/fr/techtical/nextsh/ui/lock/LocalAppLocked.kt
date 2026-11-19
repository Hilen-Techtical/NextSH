// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.lock

import androidx.compose.runtime.compositionLocalOf

/**
 * Whether the app is behind the lock screen (`AppLock`), for the screens that sit deep under the
 * navigation and whose dialogs must wait for the unlock.
 *
 * A dialog is a window of its own, drawn in the order of its creation: one that comes up after the
 * lock screen window is above it and stays tappable. A screen that opens such a dialog from an event
 * that outlives the lock (the answer of a server) composes it only while this is false. `NavGraph`
 * provides it for everything it hosts; the screens it composes itself get `locked` as a parameter, this
 * is for the ones that are several layers down (the SFTP pane of the split terminal).
 *
 * No default value: read outside `NavGraph` it fails loudly, a silent `false` would leave such a
 * dialog over the lock screen.
 */
internal val LocalAppLocked = compositionLocalOf<Boolean> {
    error("LocalAppLocked is provided by NavGraph: this composable is not under it")
}
