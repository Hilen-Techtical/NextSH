// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.data.db

import androidx.room.withTransaction

/**
 * Runs several DAO calls as one SQLite transaction: they all commit, or none does.
 *
 * Exists so repositories and importers that write several tables (a host and its tunnels, a
 * whole backup) can be unit-tested with mocked DAOs: `RoomDatabase.withTransaction` is an
 * extension function that a test cannot replace without static mocking.
 */
interface TransactionRunner {
    suspend fun <T> run(block: suspend () -> T): T
}

/** Production implementation, backed by the app database. */
class RoomTransactionRunner(private val database: AppDatabase) : TransactionRunner {
    override suspend fun <T> run(block: suspend () -> T): T = database.withTransaction { block() }
}
