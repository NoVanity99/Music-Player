package com.novanity.musicplayer.playback

import androidx.compose.runtime.Immutable
import com.novanity.musicplayer.data.Song
import kotlinx.coroutines.flow.StateFlow

enum class RepeatMode(val storedValue: Int) {
    OFF(0), ONE(1), ALL(2);

    fun next(): RepeatMode = when (this) {
        OFF -> ALL
        ALL -> ONE
        ONE -> OFF
    }

    companion object {
        fun fromStored(value: Int): RepeatMode = entries.firstOrNull { it.storedValue == value } ?: OFF
    }
}

@Immutable
data class QueueEntry(
    val index: Int,
    val key: String,
    val songId: Long?,
    val title: String,
    val artist: String,
)

@Immutable
data class PlayerUiState(
    val connected: Boolean = false,
    val current: QueueEntry? = null,
    val isPlaying: Boolean = false,
    val durationMs: Long = 0L,
    val shuffle: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
) {
    val hasMedia: Boolean get() = current != null
}

/**
 * Platform playback engine (Media3 on Android, FFmpeg on desktop). The shared UI only talks to
 * playback through this interface.
 */
interface PlayerController {
    val state: StateFlow<PlayerUiState>

    /** Upcoming entries in actual play order (accounts for shuffle). */
    val upNext: StateFlow<List<QueueEntry>>

    fun currentPositionMs(): Long

    fun setQueue(songs: List<Song>, startIndex: Int, shuffle: Boolean)
    fun playNext(songs: List<Song>)
    fun addToQueue(songs: List<Song>)

    fun togglePlayPause()
    fun pause()
    /** Previous: restarts the song if more than a few seconds in, else goes back. */
    fun previous()
    fun next()
    fun previousTrack()
    fun nextTrack()
    fun seekTo(positionMs: Long)
    fun setShuffle(enabled: Boolean)
    fun setRepeatMode(mode: RepeatMode)

    fun playQueueIndex(index: Int)
    fun removeQueueIndex(index: Int)
    fun moveQueueItem(from: Int, to: Int)
    fun clearUpNext()

    /** 0..1 */
    fun setVolume(volume: Float) {}
    fun setSpeed(speed: Float) {}

    fun release()
}
