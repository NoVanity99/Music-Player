package com.novanity.musicplayer.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = com.novanity.musicplayer.data.nowMillis(),
)

@Entity(
    tableName = "playlist_songs",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("playlistId")],
)
data class PlaylistSongEntity(
    @PrimaryKey(autoGenerate = true) val entryId: Long = 0,
    val playlistId: Long,
    /** MediaStore audio id. */
    val songId: Long,
    val position: Int,
)

@Entity(tableName = "play_history", indices = [Index("songId"), Index("playedAt")])
data class PlayHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: Long,
    val playedAt: Long,
)

data class PlaylistSummary(
    val id: Long,
    val name: String,
    val songCount: Int,
)

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val songId: Long,
    val addedAt: Long = com.novanity.musicplayer.data.nowMillis(),
)

data class PlaylistCoverEntry(
    val playlistId: Long,
    val songId: Long,
)

/**
 * A cloud song saved (or queued to be saved) for offline play. [metaJson] is the song's cloud
 * metadata, so downloads still show up when the server can't be reached.
 */
@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val cloudId: String,
    /** Absolute path in the app's private storage; empty until the download finishes. */
    val filePath: String,
    val size: Long,
    /** When the download finished (or was queued), unix ms. */
    val downloadedAt: Long,
    /** [STATUS_QUEUED], [STATUS_DONE] or [STATUS_FAILED]. */
    val status: Int,
    val metaJson: String,
    val error: String? = null,
) {
    companion object {
        const val STATUS_QUEUED = 0
        const val STATUS_DONE = 1
        const val STATUS_FAILED = 2
    }
}

data class PlayCount(val songId: Long, val plays: Int)

/** One play from the history: which song and when (unix ms). */
data class PlayEvent(val songId: Long, val playedAt: Long)
