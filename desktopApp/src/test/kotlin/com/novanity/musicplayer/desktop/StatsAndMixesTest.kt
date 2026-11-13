package com.novanity.musicplayer.desktop

import com.novanity.musicplayer.data.Album
import com.novanity.musicplayer.data.ArtistIndex
import com.novanity.musicplayer.data.MixBuilder
import com.novanity.musicplayer.data.MixKind
import com.novanity.musicplayer.data.Personality
import com.novanity.musicplayer.data.Song
import com.novanity.musicplayer.data.StatsCalculator
import com.novanity.musicplayer.data.StatsRange
import com.novanity.musicplayer.data.db.PlayEvent
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StatsAndMixesTest {
    private val zone = TimeZone.UTC
    private val day = 24L * 60 * 60 * 1000
    private val hour = 60L * 60 * 1000
    /** 2026-11-13 12:00 UTC. */
    private val now = 1_794_571_200_000L

    private fun song(id: Long, artist: String, album: Long = id, addedDaysAgo: Int = 1) =
        Song(id, "s$id", "Song $id", artist, "Album $album", album, 180_000, 1, 1, (now - addedDaysAgo * day) / 1000, "/m")

    private val songs = listOf(
        song(1, "Drake"), song(2, "Drake"), song(3, "Drake & Future"), song(4, "SZA"),
        song(5, "SZA", addedDaysAgo = 500), song(6, "Drake", addedDaysAgo = 400), song(7, "SZA", addedDaysAgo = 450),
    )
    private val index = ArtistIndex(songs.map { it.artist }.distinct())
    private val albums = songs.associate { it.albumId to Album(it.albumId, it.album, it.artist, listOf(it), 0) }

    @Test
    fun statsForTheLastWeek() {
        val events = listOf(
            PlayEvent(1, now - 1 * hour), PlayEvent(1, now - 2 * day), PlayEvent(3, now - 3 * day),
            PlayEvent(4, now - 2 * hour), PlayEvent(2, now - 20 * day), // outside the week
        )
        val s = StatsCalculator.compute(events, songs.associateBy { it.id }, albums, index, StatsRange.WEEK, now, zone)
        assertEquals(4, s.plays)
        assertEquals(12, s.minutes) // four 3-minute plays
        assertEquals(3, s.distinctSongs)
        // "Drake & Future" counts for Drake (Future isn't a library artist).
        assertEquals("Drake", s.topArtists.first().item)
        assertEquals(3, s.topArtists.first().plays)
        assertEquals(1L, s.topSongs.first().item.id)
        // Plays at 10:00 and 11:00 today and at noon on two other days: the peak hour is noon.
        assertEquals(12, s.byHour.indexOf(s.byHour.max()))
        assertEquals(Personality.DAYTIME, s.personality)
        assertEquals(1, s.streakDays, "played today, but not yesterday")
    }

    @Test
    fun streakCountsBackFromTodayOrYesterday() {
        val days = listOf(1L, 2, 3, 5).map { now - it * day } // yesterday, 2 and 3 days ago, then a gap
        assertEquals(3, StatsCalculator.streak(days, now, zone))
        assertEquals(4, StatsCalculator.streak(days + now, now, zone))
        assertEquals(0, StatsCalculator.streak(listOf(now - 4 * day), now, zone))
    }

    @Test
    fun mixes() {
        val events = (1..4).map { PlayEvent(1, now - (40 + it) * day) } + // loved, but not this month
            listOf(PlayEvent(2, now - hour), PlayEvent(4, now - hour), PlayEvent(4, now - 2 * hour))
        val mixes = MixBuilder.build(songs, events, liked = setOf(2L), artists = index, now = now).associateBy { it.kind }
        // Only song 1 is "forgotten" (song 2 is liked but was played today), and a one-song mix isn't shown.
        assertTrue(MixKind.FORGOTTEN !in mixes)
        assertEquals(setOf(3L, 5L, 6L, 7L), mixes.getValue(MixKind.NEVER_PLAYED).songs.map { it.id }.toSet())
        assertEquals(setOf(5L, 6L, 7L), mixes.getValue(MixKind.TIME_MACHINE).songs.map { it.id }.toSet())
        // Same day, same order; another day, reshuffled.
        assertEquals(
            mixes.getValue(MixKind.NEVER_PLAYED).songs,
            MixBuilder.build(songs, events, setOf(2L), index, now + hour).first { it.kind == MixKind.NEVER_PLAYED }.songs,
        )
    }
}
