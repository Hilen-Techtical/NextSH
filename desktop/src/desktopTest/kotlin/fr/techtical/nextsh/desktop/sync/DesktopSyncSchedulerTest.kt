// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sync

import fr.techtical.nextsh.desktop.core.sync.DesktopSyncScheduler
import fr.techtical.nextsh.shared.core.sync.PendingConflict
import fr.techtical.nextsh.shared.core.sync.PendingConflictRepository
import fr.techtical.nextsh.shared.core.sync.SyncableEntityType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit test for [DesktopSyncScheduler].
 *
 * Verifies that the pendingConflicts counter in [SyncState] tracks the
 * [PendingConflictRepository.observeCount] Flow, surviving app restarts.
 *
 * The scheduler's [CoroutineScope] is replaced by [TestScope] so that
 * [advanceUntilIdle] can drain the collector coroutine synchronously.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopSyncSchedulerTest {

    @Test
    fun `scheduler updates pendingConflicts from repo Flow`() = runTest {
        val countFlow = MutableStateFlow(0)

        val fakeRepo = object : PendingConflictRepository {
            override fun observeCount(): Flow<Int> = countFlow.asStateFlow()
            override suspend fun getAll(): List<PendingConflict> = emptyList()
            override fun observeAll(): Flow<List<PendingConflict>> = MutableStateFlow(emptyList())
            override suspend fun save(conflict: PendingConflict) = Unit
            override suspend fun deleteById(id: String) = Unit
            override suspend fun deleteByEntity(entityType: SyncableEntityType, entityId: String) = Unit
            override suspend fun deleteAll() = Unit
        }

        // Pass the TestScope so the collector coroutine runs on the test dispatcher
        val scheduler = DesktopSyncScheduler(fakeRepo, externalScope = this)

        // Initial state: count is 0
        advanceUntilIdle()
        assertEquals(0, scheduler.syncState.value.pendingConflicts)

        // Emit 3 conflicts from the repo
        countFlow.value = 3
        advanceUntilIdle()
        assertEquals(3, scheduler.syncState.value.pendingConflicts)

        // Resolve all conflicts
        countFlow.value = 0
        advanceUntilIdle()
        assertEquals(0, scheduler.syncState.value.pendingConflicts)

        scheduler.stop()
    }
}
