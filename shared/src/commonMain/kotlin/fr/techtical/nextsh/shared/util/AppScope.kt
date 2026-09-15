// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.util

import kotlinx.coroutines.CoroutineScope

/**
 * Application-wide coroutine scope managed by the platform container.
 * ViewModels receive this scope instead of owning their own, so cancellation
 * is centralised and tied to the app lifecycle.
 */
interface AppScope {
    val coroutineScope: CoroutineScope
    fun onDestroy()
}
