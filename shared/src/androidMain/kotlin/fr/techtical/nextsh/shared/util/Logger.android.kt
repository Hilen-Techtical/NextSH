// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.util

import timber.log.Timber

actual object Logger {
    actual fun d(tag: String, message: String) { Timber.tag(tag).d(message) }
    actual fun w(tag: String, message: String) { Timber.tag(tag).w(message) }
    actual fun e(tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) Timber.tag(tag).e(throwable, message)
        else Timber.tag(tag).e(message)
    }
}
