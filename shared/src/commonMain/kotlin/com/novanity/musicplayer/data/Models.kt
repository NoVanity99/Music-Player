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
    /** Which row of the artist page the song's album belongs in. */
    /** The artist-page row the server filed this song's album in, or null to file it by size. */
    val albumKind: AlbumKind? = null,
    /** Release year from the file's tags, or 0 if unknown. */
    val year: Int = 0,
    /** The album's own artist from the file's tags ("album artist"); empty when the file has none. */
    val albumArtist: String = "",
)

/** The four kinds of release an artist page is organised by. */
enum class AlbumKind(val title: String, val empty: String) {
    RELEASED("Albums", "No albums yet"),
    EP("EPs", "No EPs yet"),
    SINGLE("Singles", "No singles yet"),
    ;

    companion object {
        /** From the server's "albumType"; null for anything else, which is then filed by size. */
        fun parse(value: String?): AlbumKind? = when (value?.lowercase()) {
            "released", "album" -> RELEASED
            "ep" -> EP
            "single" -> SINGLE
            else -> null
        }

        /** The row for an album nobody filed: 1 song is a single, 2-6 an EP, 7 or more an album. */
        fun forSize(songs: Int): AlbumKind = when {
            songs <= 1 -> SINGLE
            songs <= 6 -> EP
            else -> RELEASED
        }
    }
}

enum class SongSource { LOCAL, CLOUD, DOWNLOADED }

@Immutable
data class Album(
    val id: Long,
    val title: String,
    val artist: String,
    val songs: List<Song>,
    val dateAdded: Long,
    val kind: AlbumKind = AlbumKind.RELEASED,
    /** Release year, or 0 if unknown. */
    val year: Int = 0,
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

/** The app's highlight colour (buttons, the playing song, switches). */
enum class AccentColor(val label: String, val argb: Long) {
    ROSE("Rose", 0xFFFF375F),
    ORANGE("Orange", 0xFFFF9F0A),
    GREEN("Green", 0xFF30D158),
    TEAL("Teal", 0xFF40C8E0),
    BLUE("Blue", 0xFF0A84FF),
    PURPLE("Purple", 0xFFBF5AF2),
}

enum class ThemeMode(val label: String) {
    DARK("Dark"),
    LIGHT("Light"),
    SYSTEM("Follow system"),
}
