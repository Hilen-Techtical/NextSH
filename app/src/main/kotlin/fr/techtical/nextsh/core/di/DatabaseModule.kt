// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fr.techtical.nextsh.data.db.AppDatabase
import fr.techtical.nextsh.data.db.dao.CustomTerminalThemeDao
import fr.techtical.nextsh.data.db.dao.EnrolledDeviceDao
import fr.techtical.nextsh.data.db.dao.HostDao
import fr.techtical.nextsh.data.db.dao.PendingConflictDao
import fr.techtical.nextsh.data.db.dao.SnippetDao
import fr.techtical.nextsh.data.db.dao.SshKeyDao
import fr.techtical.nextsh.data.db.dao.TunnelDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context
    ): AppDatabase = Room.databaseBuilder(
        context,
        AppDatabase::class.java,
        "nextsh.db"
    )
        .addMigrations(
            AppDatabase.MIGRATION_1_2,
            AppDatabase.MIGRATION_2_3,
            AppDatabase.MIGRATION_3_4,
            AppDatabase.MIGRATION_4_5,
            AppDatabase.MIGRATION_5_6,
            AppDatabase.MIGRATION_6_7,
            AppDatabase.MIGRATION_7_8,
            AppDatabase.MIGRATION_8_9,
            AppDatabase.MIGRATION_9_10,
            AppDatabase.MIGRATION_10_11,
            AppDatabase.MIGRATION_11_12,
        )
        .fallbackToDestructiveMigration()
        .build()

    @Provides
    fun provideHostDao(db: AppDatabase): HostDao = db.hostDao()

    @Provides
    fun provideTunnelDao(db: AppDatabase): TunnelDao = db.tunnelDao()

    @Provides
    fun provideSshKeyDao(db: AppDatabase): SshKeyDao = db.sshKeyDao()

    @Provides
    fun provideSnippetDao(db: AppDatabase): SnippetDao = db.snippetDao()

    @Provides
    fun provideEnrolledDeviceDao(db: AppDatabase): EnrolledDeviceDao = db.enrolledDeviceDao()

    @Provides
    fun providePendingConflictDao(db: AppDatabase): PendingConflictDao = db.pendingConflictDao()

    @Provides
    fun provideCustomTerminalThemeDao(db: AppDatabase): CustomTerminalThemeDao =
        db.customTerminalThemeDao()
}
