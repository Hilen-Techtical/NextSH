// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.util

actual fun randomUuid(): String = java.util.UUID.randomUUID().toString()
