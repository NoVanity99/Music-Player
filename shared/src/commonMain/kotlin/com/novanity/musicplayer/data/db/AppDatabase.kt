package com.novanity.musicplayer.data.db

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

@Database(
    entities = [PlaylistEntity::class, PlaylistSongEntity::class, PlayHistoryEntity::class, FavoriteEntity::class],
    version = 2,
    exportSchema = true,
)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun historyDao(): HistoryDao
    abstract fun favoritesDao(): FavoritesDao

    companion object {
        const val FILE_NAME = "music.db"

        /** v2 adds "Liked Songs". */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `favorites` (`songId` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`songId`))"
                )
            }
        }

        /** Finishes a platform-specific builder with the shared configuration. */
        fun RoomDatabase.Builder<AppDatabase>.configure(): RoomDatabase.Builder<AppDatabase> =
            addMigrations(MIGRATION_1_2)
    }
}

// Room's compiler generates the platform implementations.
@Suppress("NO_ACTUAL_FOR_EXPECT", "KotlinNoActualForExpect")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}
