package com.novanity.musicplayer.data

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.novanity.musicplayer.data.db.PlayEvent
import kotlin.random.Random

/** Automatic playlists built from your library and listening history. */
enum class MixKind(val title: String, val description: String, val colors: List<Color>) {
    FORGOTTEN(
        "Forgotten favorites",
        "Songs you loved that you haven't played in a month",
        listOf(Color(0xFFFF6B6B), Color(0xFF8E2DE2)),
    ),
    NEVER_PLAYED(
        "Never played",
        "Songs in your library you've never listened to",
        listOf(Color(0xFF11998E), Color(0xFF38EF7D)),
    ),
    DEEP_CUTS(
        "Deep cuts",
        "Lesser-played songs from your top artists",
        listOf(Color(0xFF2C3E91), Color(0xFF00C6FF)),
    ),
    TIME_MACHINE(
        "Time machine",
        "Songs you added over a year ago",
        listOf(Color(0xFFF7971E), Color(0xFFFFD200)),
    ),
}

@Immutable
data class Mix(val kind: MixKind, val songs: List<Song>)

object MixBuilder {
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val SIZE = 50

    /**
     * Today's mixes; a mix with fewer than 3 songs is left out. The shuffled ones use the date as
     * the seed, so they stay the same all day and change the next.
     */
    fun build(
        songs: List<Song>,
        events: List<PlayEvent>,
        liked: Set<Long>,
        artists: ArtistIndex,
        now: Long = nowMillis(),
    ): List<Mix> {
        val plays = events.groupingBy { it.songId }.eachCount()
        val lastPlayed = HashMap<Long, Long>()
        for (e in events) if (e.playedAt > (lastPlayed[e.songId] ?: 0L)) lastPlayed[e.songId] = e.playedAt
        val random = Random(now / DAY_MS)
        val monthAgo = now - 30 * DAY_MS

        val forgotten = songs
            .filter { s -> val n = plays[s.id] ?: 0; ((s.id in liked && n >= 1) || n >= 3) && (lastPlayed[s.id] ?: 0L) < monthAgo }
            .sortedByDescending { plays[it.id] ?: 0 }
            .take(SIZE)

        val neverPlayed = songs.filter { it.id !in plays }.shuffled(random).take(SIZE)

        // Top artists by plays (collaborations count for each library artist in them).
        val byId = songs.associateBy { it.id }
        val artistPlays = HashMap<String, Int>()
        for (e in events) {
            val song = byId[e.songId] ?: continue
            artists.resolve(song.artist).filter { it.inLibrary }.forEach { artistPlays[it.name] = (artistPlays[it.name] ?: 0) + 1 }
        }
        val topArtists = artistPlays.entries.sortedByDescending { it.value }.take(5).map { it.key }.toSet()
        val deepCuts = songs
            .filter { s -> (plays[s.id] ?: 0) <= 1 && artists.resolve(s.artist).any { it.name in topArtists } }
            .shuffled(random)
            .take(SIZE)

        val yearAgoSeconds = (now - 365 * DAY_MS) / 1000
        val timeMachine = songs.filter { it.dateAdded in 1 until yearAgoSeconds }.shuffled(random).take(SIZE)

        return listOf(
            Mix(MixKind.FORGOTTEN, forgotten),
            Mix(MixKind.DEEP_CUTS, deepCuts),
            Mix(MixKind.TIME_MACHINE, timeMachine),
            Mix(MixKind.NEVER_PLAYED, neverPlayed),
        ).filter { it.songs.size >= 3 }
    }
}
