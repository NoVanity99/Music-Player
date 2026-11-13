package com.novanity.musicplayer.data

import androidx.compose.runtime.Immutable
import com.novanity.musicplayer.data.db.PlayEvent
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

enum class StatsRange(val label: String, val days: Int?) {
    WEEK("7 days", 7),
    MONTH("30 days", 30),
    ALL("All time", null),
}

@Immutable
data class Ranked<T>(val item: T, val plays: Int)

@Immutable
data class ListeningStats(
    val plays: Int,
    /** Approximate: the length of every song played. */
    val minutes: Long,
    val distinctSongs: Int,
    val topArtists: List<Ranked<String>>,
    val topSongs: List<Ranked<Song>>,
    val topAlbums: List<Ranked<Album>>,
    /** Plays per hour of the day, 0..23, in local time. */
    val byHour: List<Int>,
    /** Days in a row, up to today (or yesterday), with at least one play. */
    val streakDays: Int,
    val busiestWeekday: DayOfWeek?,
    val personality: Personality?,
)

/** A light-hearted label from the hour you listen most. */
enum class Personality(val title: String, val blurb: String) {
    EARLY_BIRD("Early bird", "Most of your listening happens in the morning."),
    DAYTIME("Daytime listener", "Afternoons are your prime listening time."),
    EVENING("Evening unwinder", "You save your music for the evening."),
    NIGHT_OWL("Night owl", "Your library comes alive after dark."),
    ;

    companion object {
        fun forHour(hour: Int): Personality = when (hour) {
            in 5..11 -> EARLY_BIRD
            in 12..17 -> DAYTIME
            in 18..22 -> EVENING
            else -> NIGHT_OWL
        }
    }
}

object StatsCalculator {
    /**
     * Stats for [range] from the play history. Songs no longer in the library are skipped.
     * Collaborations count for every library artist in them ("A & B" counts for A and for B).
     */
    fun compute(
        events: List<PlayEvent>,
        songsById: Map<Long, Song>,
        albumsById: Map<Long, Album>,
        artists: ArtistIndex,
        range: StatsRange,
        now: Long = nowMillis(),
        zone: TimeZone = TimeZone.currentSystemDefault(),
    ): ListeningStats {
        val since = range.days?.let { now - it * DAY_MS } ?: Long.MIN_VALUE
        val inRange = events.filter { it.playedAt in since..now && it.songId in songsById }

        val songPlays = inRange.groupingBy { it.songId }.eachCount()
        val artistPlays = HashMap<String, Int>()
        val albumPlays = HashMap<Long, Int>()
        val byHour = IntArray(24)
        val byWeekday = IntArray(7)
        var ms = 0L
        for (e in inRange) {
            val song = songsById.getValue(e.songId)
            ms += song.durationMs
            val parts = artists.resolve(song.artist)
            val names = parts.filter { it.inLibrary }.map { it.name }.ifEmpty { listOf(song.artist) }
            names.forEach { artistPlays[it] = (artistPlays[it] ?: 0) + 1 }
            albumPlays[song.albumId] = (albumPlays[song.albumId] ?: 0) + 1
            val local = Instant.fromEpochMilliseconds(e.playedAt).toLocalDateTime(zone)
            byHour[local.hour]++
            byWeekday[local.dayOfWeek.ordinal]++
        }

        fun <K> top(counts: Map<K, Int>, limit: Int) = counts.entries
            .sortedWith(compareByDescending<Map.Entry<K, Int>> { it.value })
            .take(limit)

        val peakHour = byHour.indices.maxByOrNull { byHour[it] }?.takeIf { byHour[it] > 0 }
        val busiest = byWeekday.indices.maxByOrNull { byWeekday[it] }?.takeIf { byWeekday[it] > 0 }
        return ListeningStats(
            plays = inRange.size,
            minutes = ms / 60_000,
            distinctSongs = songPlays.size,
            topArtists = top(artistPlays, 10).map { Ranked(it.key, it.value) },
            topSongs = top(songPlays, 10).map { Ranked(songsById.getValue(it.key), it.value) },
            topAlbums = top(albumPlays, 10).mapNotNull { e -> albumsById[e.key]?.let { Ranked(it, e.value) } },
            byHour = byHour.toList(),
            streakDays = streak(events.map { it.playedAt }, now, zone),
            busiestWeekday = busiest?.let { DayOfWeek.entries[it] },
            personality = peakHour?.let(Personality::forHour),
        )
    }

    /** Consecutive days with a play, ending today, or yesterday if nothing's been played yet today. */
    fun streak(playTimes: List<Long>, now: Long, zone: TimeZone): Int {
        val days: Set<LocalDate> = playTimes.mapTo(HashSet()) { Instant.fromEpochMilliseconds(it).toLocalDateTime(zone).date }
        var day = Instant.fromEpochMilliseconds(now).toLocalDateTime(zone).date
        if (day !in days) day = day.minus(DatePeriod(days = 1))
        var count = 0
        while (day in days) {
            count++
            day = day.minus(DatePeriod(days = 1))
        }
        return count
    }

    private const val DAY_MS = 24L * 60 * 60 * 1000
}
