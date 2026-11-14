package com.novanity.musicplayer.data

import androidx.compose.runtime.Immutable

@Immutable
data class Song(
    val id: Long,
    /** Playable location: a content:// URI on Android, an absolute file path on desktop. */
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val track: Int,
    val disc: Int,
    val dateAdded: Long,
    val folderPath: String,
    /** Absolute path of the file, when MediaStore reports one. */
    val filePath: String = "",
    /** Where the song is: on this device, in the cloud, or a cloud song downloaded for offline play. */
    val source: SongSource = SongSource.LOCAL,
    /** The song's id on the cloud server (cloud and downloaded songs). */
    val cloudId: String? = null,
    /** The cover's id on the cloud server, if it has one. */
    val coverId: String? = null,
    val sizeBytes: Long = 0,
)

enum class SongSource { LOCAL, CLOUD, DOWNLOADED }

@Immutable
data class Album(
    val id: Long,
    val title: String,
    val artist: String,
    val songs: List<Song>,
    val dateAdded: Long,
)

@Immutable
data class Artist(
    val name: String,
    /** Songs credited to this artist alone. */
    val songs: List<Song>,
    val albumCount: Int,
    val dateAdded: Long,
    /** Collaborations: songs credited to this artist together with others ("A & B", "A feat. B"). */
    val appearsOn: List<Song> = emptyList(),
)

@Immutable
data class Folder(
    val path: String,
    val name: String,
    val songs: List<Song>,
    val dateAdded: Long,
)

enum class SortOrder(val label: String) {
    TITLE("Title"),
    ARTIST("Artist"),
    ALBUM("Album"),
    DATE_ADDED("Recently added"),
    DURATION("Duration"),
    MOST_PLAYED("Most played"),
}

enum class ThemeMode(val label: String) {
    DARK("Dark"),
    LIGHT("Light"),
    SYSTEM("Follow system"),
}
