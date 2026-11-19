// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.data.db

/**
 * Test double for [TransactionRunner]: runs the block inline and records whether it is inside a
 * transaction, so a test can assert that a DAO write happened within one.
 */
class FakeTransactionRunner : TransactionRunner {

    /** True only while a block passed to [run] is executing. */
    var active: Boolean = false
        private set

    /** How many times [run] was entered. */
    var runs: Int = 0
        private set

    override suspend fun <T> run(block: suspend () -> T): T {
        runs++
        active = true
        try {
            return block()
        } finally {
            active = false
        }
    }
}
