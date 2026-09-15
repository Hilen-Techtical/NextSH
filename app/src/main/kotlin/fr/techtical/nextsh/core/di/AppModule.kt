// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.techtical.nextsh.core.auth.HardwareKeyAuthenticator
import fr.techtical.nextsh.core.auth.YubiKitAuthenticator
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    // All bindings are satisfied via @Singleton @Inject constructors on each class.
    // Add explicit @Provides here only for third-party types that cannot be annotated.

    @Provides
    @Singleton
    fun provideHardwareKeyAuthenticator(impl: YubiKitAuthenticator): HardwareKeyAuthenticator = impl
}
