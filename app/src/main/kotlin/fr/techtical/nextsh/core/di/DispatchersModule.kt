// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.techtical.nextsh.shared.core.ssh.PublicKeyDeriver
import fr.techtical.nextsh.shared.core.ssh.SshPublicKeyDeriver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Qualifier

/**
 * Dispatcher des travaux bloquants (disque, Keystore). Injecté plutôt que codé en dur quand un
 * test doit le remplacer : un `withContext(Dispatchers.IO)` écrit en dur échappe au planificateur
 * des tests et rend les assertions dépendantes du hasard des threads.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/** Dispatcher des calculs coûteux (dérivation de clés), hors du fil principal. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

@Module
@InstallIn(SingletonComponent::class)
object DispatchersModule {

    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    fun providePublicKeyDeriver(): PublicKeyDeriver = SshPublicKeyDeriver


    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
}
