// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fr.techtical.nextsh.core.sync.AndroidSyncScheduler
import fr.techtical.nextsh.core.sync.LanSyncClient
import fr.techtical.nextsh.shared.core.sync.CredentialMetaStore
import fr.techtical.nextsh.shared.core.sync.CredentialSyncRepository
import fr.techtical.nextsh.shared.core.sync.DeviceIdentity
import fr.techtical.nextsh.shared.core.sync.EnrolledDeviceSecretStore
import fr.techtical.nextsh.shared.core.sync.FileCredentialMetaStore
import fr.techtical.nextsh.shared.core.sync.JvmCredentialSyncRepository
import fr.techtical.nextsh.shared.core.sync.PendingConflictRepository
import fr.techtical.nextsh.shared.core.sync.SyncProtocol
import fr.techtical.nextsh.shared.core.sync.SyncScheduler
import fr.techtical.nextsh.shared.domain.vault.VaultManager
import java.io.File
import javax.inject.Singleton

/**
 * Binds [LanSyncClient] as the [SyncProtocol] implementation and
 * [AndroidSyncScheduler] as the [SyncScheduler] implementation.
 *
 * Note: [fr.techtical.nextsh.shared.core.sync.SyncRepository] is already bound
 * in [RepositoryModule] via [fr.techtical.nextsh.core.sync.AndroidSyncRepository].
 *
 * [EnrolledDeviceSecretStore] is provided here because the class lives in `:shared`
 * and cannot carry an `@Inject` annotation (no Hilt dependency in the shared module).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SyncModule {

    @Binds
    @Singleton
    abstract fun bindSyncProtocol(impl: LanSyncClient): SyncProtocol

    @Binds
    @Singleton
    abstract fun bindSyncScheduler(impl: AndroidSyncScheduler): SyncScheduler

    companion object {
        @Provides
        @Singleton
        fun provideEnrolledDeviceSecretStore(vaultManager: VaultManager): EnrolledDeviceSecretStore =
            EnrolledDeviceSecretStore(vaultManager)

        @Provides
        @Singleton
        fun provideCredentialMetaStore(
            @ApplicationContext context: Context,
        ): CredentialMetaStore =
            FileCredentialMetaStore(File(context.filesDir, "credential-meta.json"))

        @Provides
        @Singleton
        fun provideCredentialSyncRepository(
            vaultManager: VaultManager,
            metaStore: CredentialMetaStore,
            pendingConflictRepository: PendingConflictRepository,
        ): CredentialSyncRepository = JvmCredentialSyncRepository(
            vault = vaultManager,
            metaStore = metaStore,
            pendingConflictRepository = pendingConflictRepository,
            localDeviceId = { DeviceIdentity.deviceId() },
        )
    }
}
