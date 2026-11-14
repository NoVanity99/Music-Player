package com.novanity.musicplayer

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.novanity.musicplayer.data.db.AppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** Upgrading from 1.0 (schema v1) must keep playlists and add Liked Songs and downloads without crashing. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MigrationTest {
    @Test
    fun migrate1To3() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<MusicApp>()
        val dbFile = context.getDatabasePath("migration-test.db").apply /* not the app's own, which is open */ { parentFile?.mkdirs(); delete() }

        // Recreate exactly what version 1.0 shipped, from its exported schema.
        val schema = JSONObject(File("../shared/schemas/com.novanity.musicplayer.data.db.AppDatabase/1.json").readText())
            .getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                val table = e.getString("tableName")
                db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
                e.optJSONArray("indices")?.let { idx ->
                    for (j in 0 until idx.length()) db.execSQL(idx.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.execSQL("INSERT INTO playlists (id, name, createdAt) VALUES (1, 'Old mix', 0)")
            db.execSQL("INSERT INTO playlist_songs (playlistId, songId, position) VALUES (1, 42, 0)")
            db.version = 1
        }

        val room = androidx.room.Room.databaseBuilder<AppDatabase>(context, dbFile.absolutePath)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
            .build()
        assertEquals(listOf("Old mix"), room.playlistDao().observePlaylists().first().map { it.name })
        room.favoritesDao().insert(com.novanity.musicplayer.data.db.FavoriteEntity(42))
        assertEquals(listOf(42L), room.favoritesDao().observeSongIds().first())
        // v3: the downloads table (Room also checks it matches the entity exactly).
        room.downloadsDao().upsert(com.novanity.musicplayer.data.db.DownloadEntity("abc", "/x", 5, 1, 1, "{}"))
        assertEquals(listOf("abc"), room.downloadsDao().all().map { it.cloudId })
        room.close()
    }
}
