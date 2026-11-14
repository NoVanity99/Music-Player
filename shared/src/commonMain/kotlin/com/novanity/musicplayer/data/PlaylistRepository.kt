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
import kotlinx.coroutines.flow.map

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

    /** How many times each song was played (from the last few thousand plays). */
    val playCounts: Flow<Map<Long, Int>> = dao.observePlayCounts().map { rows -> rows.associate { it.songId to it.plays } }

    /** Every remembered play, newest first (the last 5,000). */
    val events: Flow<List<com.novanity.musicplayer.data.db.PlayEvent>> = dao.observeEvents()

    /** Told about every play, so the signed-in account can remember it. */
    var onPlay: ((songId: Long, at: Long) -> Unit)? = null

    suspend fun record(songId: Long) {
        val at = nowMillis()
        dao.insert(PlayHistoryEntity(songId = songId, playedAt = at))
        dao.prune(keep = 5_000)
        onPlay?.invoke(songId, at)
    }
}

class FavoritesRepository(private val dao: FavoritesDao) {
    /** Liked song ids, most recently liked first. */
    val songIds: Flow<List<Long>> = dao.observeSongIds()

    /** Told about every like and unlike, so the signed-in account can remember it. */
    var onLike: ((songId: Long, liked: Boolean, at: Long) -> Unit)? = null

    suspend fun setLiked(songId: Long, liked: Boolean) {
        val at = nowMillis()
        if (liked) dao.insert(FavoriteEntity(songId, at)) else dao.delete(songId)
        onLike?.invoke(songId, liked, at)
    }
}
