// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import fr.techtical.nextsh.data.db.dao.CustomTerminalThemeDao
import fr.techtical.nextsh.data.db.dao.EnrolledDeviceDao
import fr.techtical.nextsh.data.db.dao.HostDao
import fr.techtical.nextsh.data.db.dao.PendingConflictDao
import fr.techtical.nextsh.data.db.dao.SnippetDao
import fr.techtical.nextsh.data.db.dao.SshKeyDao
import fr.techtical.nextsh.data.db.dao.TunnelDao
import fr.techtical.nextsh.data.db.entity.CustomTerminalThemeEntity
import fr.techtical.nextsh.data.db.entity.EnrolledDeviceEntity
import fr.techtical.nextsh.data.db.entity.HostEntity
import fr.techtical.nextsh.data.db.entity.PendingConflictEntity
import fr.techtical.nextsh.data.db.entity.SnippetEntity
import fr.techtical.nextsh.data.db.entity.SshKeyEntity
import fr.techtical.nextsh.data.db.entity.TunnelEntity

@Database(
    entities = [
        HostEntity::class,
        TunnelEntity::class,
        SshKeyEntity::class,
        SnippetEntity::class,
        EnrolledDeviceEntity::class,
        PendingConflictEntity::class,
        CustomTerminalThemeEntity::class,
    ],
    version = 12,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun hostDao(): HostDao
    abstract fun tunnelDao(): TunnelDao
    abstract fun sshKeyDao(): SshKeyDao
    abstract fun snippetDao(): SnippetDao
    abstract fun enrolledDeviceDao(): EnrolledDeviceDao
    abstract fun pendingConflictDao(): PendingConflictDao
    abstract fun customTerminalThemeDao(): CustomTerminalThemeDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tunnels ADD COLUMN openBrowserOnConnect INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tunnels ADD COLUMN keepAliveAfterBrowserClose INTEGER NOT NULL DEFAULT 1")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE hosts ADD COLUMN terminalTheme TEXT NOT NULL DEFAULT 'TECHTICAL_DARK'")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS snippets (
                        id TEXT NOT NULL PRIMARY KEY,
                        label TEXT NOT NULL,
                        command TEXT NOT NULL,
                        category TEXT,
                        hostId TEXT,
                        createdAt INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ssh_keys ADD COLUMN fido2CredentialId TEXT")
                db.execSQL("ALTER TABLE ssh_keys ADD COLUMN fido2RpId TEXT")
                db.execSQL("ALTER TABLE hosts ADD COLUMN fido2Mode TEXT")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE hosts ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tunnels ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS enrolled_devices (
                        deviceId TEXT NOT NULL PRIMARY KEY,
                        deviceName TEXT NOT NULL,
                        platform TEXT NOT NULL,
                        publicKeyFingerprint TEXT NOT NULL,
                        lastSyncAt INTEGER,
                        enrolledAt INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE enrolled_devices ADD COLUMN tlsCertFingerprint TEXT")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (table in listOf("hosts", "tunnels", "ssh_keys", "snippets")) {
                    db.execSQL("ALTER TABLE $table ADD COLUMN vectorClock TEXT NOT NULL DEFAULT '{}'")
                    db.execSQL("ALTER TABLE $table ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE $table ADD COLUMN deletedAt INTEGER")
                    db.execSQL("ALTER TABLE $table ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                }
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE enrolled_devices ADD COLUMN lastKnownHost TEXT")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS pending_conflicts (
                        id TEXT NOT NULL PRIMARY KEY,
                        entityType TEXT NOT NULL,
                        entityId TEXT NOT NULL,
                        localJson TEXT NOT NULL,
                        remoteJson TEXT NOT NULL,
                        detectedAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_pending_conflicts_entityType_entityId " +
                    "ON pending_conflicts(entityType, entityId)"
                )
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS custom_terminal_themes (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        background INTEGER NOT NULL,
                        foreground INTEGER NOT NULL,
                        cursor INTEGER NOT NULL,
                        selectionBg INTEGER NOT NULL,
                        ansi TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        vectorClock TEXT NOT NULL DEFAULT '{}',
                        deleted INTEGER NOT NULL DEFAULT 0,
                        deletedAt INTEGER,
                        updatedAt INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }
    }
}
