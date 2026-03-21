package com.example.kftgcs.database

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import android.content.Context
import com.example.kftgcs.database.offline.OfflineMessageDao
import com.example.kftgcs.database.offline.OfflineMessageEntity
import com.example.kftgcs.database.tlog.*

/**
 * Room database for Mission Plan Templates and Flight Logs
 */
@Database(
    entities = [
        MissionTemplateEntity::class,
        FlightEntity::class,
        TelemetryEntity::class,
        EventEntity::class,
        MapDataEntity::class,
        OfflineMessageEntity::class
    ],
    version = 8,
    exportSchema = false
)
@TypeConverters(MissionTemplateTypeConverters::class, TlogTypeConverters::class)
abstract class MissionTemplateDatabase : RoomDatabase() {

    abstract fun missionTemplateDao(): MissionTemplateDao
    abstract fun flightDao(): FlightDao
    abstract fun telemetryDao(): TelemetryDao
    abstract fun eventDao(): EventDao
    abstract fun mapDataDao(): MapDataDao
    abstract fun offlineMessageDao(): OfflineMessageDao

    companion object {
        @Volatile
        private var INSTANCE: MissionTemplateDatabase? = null

        // Migration from version 4 to 5 (added droneUid to flights table)
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE flights ADD COLUMN droneUid TEXT")
            }
        }

        // Migration from version 5 to 6 (no schema change, just version bump)
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // No schema changes needed
            }
        }

        // Migration from version 6 to 7 (no schema change, just version bump for hash fix)
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // No schema changes needed - fixing hash mismatch
            }
        }

        // Migration from version 7 to 8 (adds offline_messages table for offline sync)
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS offline_messages (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        messageType TEXT NOT NULL,
                        payload TEXT NOT NULL,
                        createdAt INTEGER NOT NULL
                    )"""
                )
            }
        }

        // Direct migration from version 4 to 6
        private val MIGRATION_4_6 = object : Migration(4, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE flights ADD COLUMN droneUid TEXT")
            }
        }

        fun getDatabase(context: Context): MissionTemplateDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MissionTemplateDatabase::class.java,
                    "mission_template_database"
                )
                .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_4_6, MIGRATION_7_8)
                .fallbackToDestructiveMigration()  // Fallback if migration fails
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
