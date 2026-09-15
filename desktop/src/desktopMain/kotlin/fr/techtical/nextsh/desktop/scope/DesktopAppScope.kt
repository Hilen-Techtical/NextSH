// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.scope

import fr.techtical.nextsh.shared.util.AppScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class DesktopAppScope : AppScope {
    private val job = SupervisorJob()
    override val coroutineScope: CoroutineScope = CoroutineScope(job + Dispatchers.Default)

    override fun onDestroy() {
        coroutineScope.cancel()
    }
}
