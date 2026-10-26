package com.novanity.musicplayer.lyrics

import androidx.compose.runtime.Immutable
import com.novanity.musicplayer.data.Song

@Immutable
data class LyricLine(val timeMs: Long?, val text: String)

@Immutable
data class Lyrics(val lines: List<LyricLine>) {
    /** True when every line has a timestamp (LRC), so we can follow along. */
    val synced: Boolean get() = lines.isNotEmpty() && lines.all { it.timeMs != null }

    /** Index of the line being sung at [positionMs], or -1 before the first line. */
    fun indexAt(positionMs: Long): Int {
        if (!synced) return -1
        var lo = 0
        var hi = lines.lastIndex
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (lines[mid].timeMs!! <= positionMs) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        return found
    }

    companion object {
        private val TIME_TAG = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
        private val META_TAG = Regex("""^\[[a-zA-Z]+:.*]$""")

        /** Parses LRC (with timestamps, including several per line) or plain text. */
        fun parse(text: String): Lyrics? {
            val synced = ArrayList<LyricLine>()
            val plain = ArrayList<LyricLine>()
            var offsetMs = 0L
            text.lineSequence().forEach { raw ->
                val line = raw.trim().trimStart('﻿')
                if (line.startsWith("[offset:", ignoreCase = true)) {
                    offsetMs = line.substringAfter(':').substringBefore(']').trim().toLongOrNull() ?: 0L
                    return@forEach
                }
                val tags = TIME_TAG.findAll(line).toList()
                if (tags.isNotEmpty() && line.startsWith("[")) {
                    val lyric = line.substring(tags.last().range.last + 1).trim()
                    tags.forEach { m ->
                        val (min, sec, frac) = m.destructured
                        val fracMs = when (frac.length) { 0 -> 0; 1 -> frac.toInt() * 100; 2 -> frac.toInt() * 10; else -> frac.take(3).toInt() }
                        synced += LyricLine(min.toLong() * 60_000 + sec.toLong() * 1000 + fracMs, lyric)
                    }
                } else if (!META_TAG.matches(line)) {
                    plain += LyricLine(null, line)
                }
            }
            if (synced.isNotEmpty()) {
                return Lyrics(synced.map { it.copy(timeMs = (it.timeMs!! - offsetMs).coerceAtLeast(0)) }.sortedBy { it.timeMs })
            }
            val trimmed = plain.dropWhile { it.text.isEmpty() }.dropLastWhile { it.text.isEmpty() }
            return if (trimmed.isEmpty()) null else Lyrics(trimmed)
        }
    }
}

sealed interface LyricsResult {
    data class Found(val lyrics: Lyrics) : LyricsResult
    data object None : LyricsResult
    /** Android: lyrics files can't be read until the user grants access to [folder]. */
    data class NeedsAccess(val folder: String) : LyricsResult
}

/** Finds lyrics for a song: an .lrc/.txt file next to it, or lyrics embedded in its tags. */
fun interface LyricsProvider {
    suspend fun load(song: Song): LyricsResult
}

/** Memory-cached lyrics lookups. The platform sets [provider] at startup. */
object LyricsLoader {
    var provider: LyricsProvider? = null
    private val cache = HashMap<Long, LyricsResult>()

    fun cached(song: Song): LyricsResult? = cache[song.id]

    suspend fun load(song: Song): LyricsResult {
        cache[song.id]?.let { return it }
        val result = runCatching { provider?.load(song) }.getOrNull() ?: LyricsResult.None
        if (result !is LyricsResult.NeedsAccess) cache[song.id] = result
        return result
    }

    fun invalidate() = cache.clear()
}
