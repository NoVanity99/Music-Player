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
)

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
    val songs: List<Song>,
    val albumCount: Int,
    val dateAdded: Long,
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
