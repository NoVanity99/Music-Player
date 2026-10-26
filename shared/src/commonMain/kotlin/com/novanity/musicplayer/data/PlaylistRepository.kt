package com.novanity.musicplayer.data

import com.novanity.musicplayer.data.db.HistoryDao
import com.novanity.musicplayer.data.db.PlayHistoryEntity
import com.novanity.musicplayer.data.db.PlaylistDao
import com.novanity.musicplayer.data.db.FavoriteEntity
import com.novanity.musicplayer.data.db.FavoritesDao
import com.novanity.musicplayer.data.db.PlaylistCoverEntry
import com.novanity.musicplayer.data.db.PlaylistEntity
import com.novanity.musicplayer.data.db.PlaylistSongEntity
import com.novanity.musicplayer.data.db.PlaylistSummary
import kotlinx.coroutines.flow.Flow

class PlaylistRepository(private val dao: PlaylistDao) {
    val playlists: Flow<List<PlaylistSummary>> = dao.observePlaylists()
    val coverEntries: Flow<List<PlaylistCoverEntry>> = dao.observeCoverEntries()

    fun playlist(id: Long): Flow<PlaylistEntity?> = dao.observePlaylist(id)
    fun entries(id: Long): Flow<List<PlaylistSongEntity>> = dao.observeEntries(id)

    suspend fun create(name: String): Long = dao.insertPlaylist(PlaylistEntity(name = name.trim()))
    suspend fun rename(id: Long, name: String) = dao.rename(id, name.trim())
    suspend fun delete(id: Long) = dao.deletePlaylist(id)
    suspend fun addSongs(id: Long, songIds: List<Long>) = dao.addSongs(id, songIds)
    suspend fun removeEntry(entryId: Long) = dao.deleteEntry(entryId)
    suspend fun reorder(entryIdsInOrder: List<Long>) = dao.reorder(entryIdsInOrder)
}

class HistoryRepository(private val dao: HistoryDao) {
    fun recentSongIds(limit: Int = 100): Flow<List<Long>> = dao.observeRecentSongIds(limit)

    suspend fun record(songId: Long) {
        dao.insert(PlayHistoryEntity(songId = songId, playedAt = nowMillis()))
        dao.prune(keep = 1_000)
    }
}

class FavoritesRepository(private val dao: FavoritesDao) {
    /** Liked song ids, most recently liked first. */
    val songIds: Flow<List<Long>> = dao.observeSongIds()

    suspend fun setLiked(songId: Long, liked: Boolean) {
        if (liked) dao.insert(FavoriteEntity(songId)) else dao.delete(songId)
    }
}
