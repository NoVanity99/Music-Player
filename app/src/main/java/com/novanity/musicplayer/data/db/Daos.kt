package com.novanity.musicplayer.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class PlaylistDao {
    @Query(
        """
        SELECT p.id AS id, p.name AS name, COUNT(s.entryId) AS songCount
        FROM playlists p LEFT JOIN playlist_songs s ON s.playlistId = p.id
        GROUP BY p.id ORDER BY p.name COLLATE NOCASE
        """
    )
    abstract fun observePlaylists(): Flow<List<PlaylistSummary>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    abstract fun observePlaylist(id: Long): Flow<PlaylistEntity?>

    @Query("SELECT * FROM playlist_songs WHERE playlistId = :playlistId ORDER BY position")
    abstract fun observeEntries(playlistId: Long): Flow<List<PlaylistSongEntity>>

    @Insert
    abstract suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    abstract suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM playlists WHERE id = :id")
    abstract suspend fun deletePlaylistRow(id: Long)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId")
    abstract suspend fun deleteEntriesOf(playlistId: Long)

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_songs WHERE playlistId = :playlistId")
    abstract suspend fun maxPosition(playlistId: Long): Int

    @Insert
    abstract suspend fun insertEntries(entries: List<PlaylistSongEntity>)

    @Query("DELETE FROM playlist_songs WHERE entryId = :entryId")
    abstract suspend fun deleteEntry(entryId: Long)

    @Query("UPDATE playlist_songs SET position = :position WHERE entryId = :entryId")
    abstract suspend fun setPosition(entryId: Long, position: Int)

    @Transaction
    open suspend fun deletePlaylist(id: Long) {
        deleteEntriesOf(id)
        deletePlaylistRow(id)
    }

    @Transaction
    open suspend fun addSongs(playlistId: Long, songIds: List<Long>) {
        val start = maxPosition(playlistId) + 1
        insertEntries(songIds.mapIndexed { i, songId -> PlaylistSongEntity(playlistId = playlistId, songId = songId, position = start + i) })
    }

    @Transaction
    open suspend fun reorder(entryIdsInOrder: List<Long>) {
        entryIdsInOrder.forEachIndexed { index, entryId -> setPosition(entryId, index) }
    }
}

@Dao
abstract class HistoryDao {
    @Insert
    abstract suspend fun insert(entry: PlayHistoryEntity)

    @Query("SELECT songId FROM play_history GROUP BY songId ORDER BY MAX(playedAt) DESC LIMIT :limit")
    abstract fun observeRecentSongIds(limit: Int): Flow<List<Long>>

    @Query("DELETE FROM play_history WHERE id NOT IN (SELECT id FROM play_history ORDER BY playedAt DESC LIMIT :keep)")
    abstract suspend fun prune(keep: Int)
}
