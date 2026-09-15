// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.techtical.nextsh.core.vault.VaultManagerAdapter
import fr.techtical.nextsh.shared.domain.repository.HostRepository
import fr.techtical.nextsh.shared.domain.repository.SshKeyRepository
import fr.techtical.nextsh.shared.domain.repository.TunnelRepository
import fr.techtical.nextsh.shared.domain.ssh.SshKeyManager
import fr.techtical.nextsh.shared.domain.ssh.SshSessionManager
import fr.techtical.nextsh.shared.domain.ssh.SshTunnelManager
import fr.techtical.nextsh.shared.domain.usecase.ConnectSessionUseCase
import fr.techtical.nextsh.shared.domain.usecase.GenerateSshKeyUseCase
import fr.techtical.nextsh.shared.domain.usecase.StartTunnelUseCase
import fr.techtical.nextsh.shared.domain.usecase.StopTunnelUseCase
import fr.techtical.nextsh.shared.domain.vault.VaultManager
import javax.inject.Singleton

/**
 * Hilt module providing the 4 shared use cases from :shared.
 *
 * The shared use cases cannot be annotated with @Inject (they live in KMP commonMain,
 * not Android), so they are provided explicitly here.
 */
@Module
@InstallIn(SingletonComponent::class)
object SharedUseCaseModule {

    @Provides
    @Singleton
    fun provideConnectSessionUseCase(
        sessionManager: SshSessionManager,
        hostRepository: HostRepository,
        vaultManager: VaultManager,
        sshKeyRepository: SshKeyRepository,
    ): ConnectSessionUseCase = ConnectSessionUseCase(
        sessionManager, hostRepository, vaultManager, sshKeyRepository
    )

    @Provides
    @Singleton
    fun provideStartTunnelUseCase(
        tunnelManager: SshTunnelManager,
        sessionManager: SshSessionManager,
        tunnelRepository: TunnelRepository,
        hostRepository: HostRepository,
        connectSession: ConnectSessionUseCase,
    ): StartTunnelUseCase = StartTunnelUseCase(
        tunnelManager, sessionManager, tunnelRepository, hostRepository, connectSession
    )

    @Provides
    @Singleton
    fun provideStopTunnelUseCase(
        tunnelManager: SshTunnelManager,
    ): StopTunnelUseCase = StopTunnelUseCase(tunnelManager)

    @Provides
    @Singleton
    fun provideGenerateSshKeyUseCase(
        keyManager: SshKeyManager,
        keyRepository: SshKeyRepository,
        vaultManager: VaultManager,
    ): GenerateSshKeyUseCase = GenerateSshKeyUseCase(
        keyManager, keyRepository, vaultManager
    )
}

/**
 * Hilt @Binds module bridging Android manager implementations to their shared interfaces.
 * These bindings let the shared use cases (and future modules) receive the correct
 * platform implementations via dependency injection.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SharedManagerBindingsModule {

    @Binds
    @Singleton
    abstract fun bindSshSessionManager(
        impl: fr.techtical.nextsh.core.ssh.SshSessionManager,
    ): SshSessionManager

    @Binds
    @Singleton
    abstract fun bindSshTunnelManager(
        impl: fr.techtical.nextsh.core.ssh.SshTunnelManager,
    ): SshTunnelManager

    @Binds
    @Singleton
    abstract fun bindSshKeyManager(
        impl: fr.techtical.nextsh.core.ssh.SshKeyManager,
    ): SshKeyManager

    @Binds
    @Singleton
    abstract fun bindVaultManager(
        impl: VaultManagerAdapter,
    ): VaultManager
}
