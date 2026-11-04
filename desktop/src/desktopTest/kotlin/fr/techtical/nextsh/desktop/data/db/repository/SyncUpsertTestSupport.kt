// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.data.db.repository

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import fr.techtical.nextsh.desktop.db.NextShDatabase
import fr.techtical.nextsh.shared.core.sync.VectorClock
import fr.techtical.nextsh.shared.core.sync.VectorClockCodec

/** Values of the local-only columns, so a test can tell "kept" from "reset by REPLACE". */
internal const val SEED_CREATED_AT = 1_111L
internal const val SEED_LAST_CONNECTED_AT = 2_222L
internal const val SEED_UPDATED_AT_MS = 1_000L
internal const val SEED_OTHER_DEVICE = "other-device"
internal const val SEED_OTHER_COUNTER = 5L

internal fun seedClock(): VectorClock = VectorClock(mapOf(SEED_OTHER_DEVICE to SEED_OTHER_COUNTER))

/**
 * Turns foreign keys ON so that the `ON DELETE CASCADE` of `tunnels.hostId` is observable. Note that the
 * same pragma in DatabaseFactory does not currently take effect on the file-based driver.
 * Fails fast if the pragma did not take effect here.
 */
internal fun SqlDriver.enableForeignKeys() {
    execute(null, "PRAGMA foreign_keys = ON", 0)
    var enabled = 0L
    executeQuery(null, "PRAGMA foreign_keys", parameters = 0, mapper = { cursor ->
        if (cursor.next().value) enabled = cursor.getLong(0) ?: 0L
        QueryResult.Unit
    })
    check(enabled == 1L) { "PRAGMA foreign_keys must be ON for these tests to be meaningful" }
}

/** Inserts a host row with fully known local-only columns, bypassing the repository. */
internal fun NextShDatabase.insertHostRow(
    id: String,
    label: String = "Host $id",
    isFavorite: Boolean = false,
    clock: VectorClock = seedClock(),
) {
    hostQueries.insert(
        id = id,
        label = label,
        hostname = "$id.local",
        port = 22L,
        username = "user",
        authType = "PASSWORD",
        credentialId = "cred-$id",
        group = null,
        keepAliveSeconds = 30L,
        autoReconnect = 1L,
        terminalTheme = "TECHTICAL_DARK",
        fido2Mode = null,
        isFavorite = if (isFavorite) 1L else 0L,
        createdAt = SEED_CREATED_AT,
        lastConnectedAt = SEED_LAST_CONNECTED_AT,
        vectorClock = VectorClockCodec.encode(clock),
        deleted = 0L,
        deletedAt = null,
        updatedAt = SEED_UPDATED_AT_MS,
    )
}

/** Inserts a tunnel row with a known createdAt, bypassing the repository. */
internal fun NextShDatabase.insertTunnelRow(
    id: String,
    hostId: String,
    isFavorite: Boolean = false,
    clock: VectorClock = seedClock(),
) {
    tunnelQueries.insert(
        id = id,
        label = "Tunnel $id",
        hostId = hostId,
        type = "LOCAL_FORWARD",
        localPort = 8080L,
        remoteHost = "localhost",
        remotePort = 80L,
        autoStart = 0L,
        openBrowserOnConnect = 0L,
        keepAliveAfterBrowserClose = 1L,
        isFavorite = if (isFavorite) 1L else 0L,
        createdAt = SEED_CREATED_AT,
        vectorClock = VectorClockCodec.encode(clock),
        deleted = 0L,
        deletedAt = null,
        updatedAt = SEED_UPDATED_AT_MS,
    )
}
