// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.sync

sealed class MergeResult<T> {
    data class Clean<T>(val entry: SyncEntry<T>) : MergeResult<T>()
    data class Conflict<T>(val local: SyncEntry<T>, val remote: SyncEntry<T>) : MergeResult<T>()
}
