// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.techtical.nextsh.core.sync.AndroidSyncRepository
import fr.techtical.nextsh.data.repository.CustomTerminalThemeRepositoryImpl
import fr.techtical.nextsh.data.repository.EnrolledDeviceRepositoryImpl
import fr.techtical.nextsh.data.repository.HostRepositoryImpl
import fr.techtical.nextsh.data.repository.PendingConflictRepositoryImpl
import fr.techtical.nextsh.data.repository.SnippetRepositoryImpl
import fr.techtical.nextsh.data.repository.SshKeyRepositoryImpl
import fr.techtical.nextsh.data.repository.TunnelRepositoryImpl
import fr.techtical.nextsh.shared.core.sync.EnrolledDeviceRepository
import fr.techtical.nextsh.shared.core.sync.PendingConflictRepository
import fr.techtical.nextsh.shared.core.sync.SyncRepository
import fr.techtical.nextsh.shared.domain.repository.CustomTerminalThemeRepository
import fr.techtical.nextsh.shared.domain.repository.HostRepository
import fr.techtical.nextsh.shared.domain.repository.SnippetRepository
import fr.techtical.nextsh.shared.domain.repository.SshKeyRepository
import fr.techtical.nextsh.shared.domain.repository.TunnelRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindHostRepository(impl: HostRepositoryImpl): HostRepository

    @Binds
    @Singleton
    abstract fun bindTunnelRepository(impl: TunnelRepositoryImpl): TunnelRepository

    @Binds
    @Singleton
    abstract fun bindSshKeyRepository(impl: SshKeyRepositoryImpl): SshKeyRepository

    @Binds
    @Singleton
    abstract fun bindSnippetRepository(impl: SnippetRepositoryImpl): SnippetRepository

    @Binds
    @Singleton
    abstract fun bindCustomTerminalThemeRepository(
        impl: CustomTerminalThemeRepositoryImpl,
    ): CustomTerminalThemeRepository

    @Binds
    @Singleton
    abstract fun bindEnrolledDeviceRepository(impl: EnrolledDeviceRepositoryImpl): EnrolledDeviceRepository

    @Binds
    @Singleton
    abstract fun bindSyncRepository(impl: AndroidSyncRepository): SyncRepository

    @Binds
    @Singleton
    abstract fun bindPendingConflictRepository(impl: PendingConflictRepositoryImpl): PendingConflictRepository
}
