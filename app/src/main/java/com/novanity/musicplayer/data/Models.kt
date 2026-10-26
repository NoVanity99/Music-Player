package com.novanity.musicplayer.data

import android.net.Uri
import androidx.compose.runtime.Immutable

@Immutable
data class Song(
    val id: Long,
    val uri: Uri,
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
) {
    val albumArtUri: Uri get() = Uri.parse("content://media/external/audio/albumart/$albumId")
}

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
    DATE_ADDED("Date added"),
}

enum class ThemeMode(val label: String) {
    DARK("Dark"),
    LIGHT("Light"),
    SYSTEM("Follow system"),
}
