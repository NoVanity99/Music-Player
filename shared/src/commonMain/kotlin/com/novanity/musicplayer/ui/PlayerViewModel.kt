package com.novanity.musicplayer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novanity.musicplayer.data.Song
import com.novanity.musicplayer.playback.PlayerController
import com.novanity.musicplayer.playback.PlayerUiState
import com.novanity.musicplayer.playback.QueueEntry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** UI-facing playback state and commands, backed by the platform [PlayerController]. */
class PlayerViewModel(private val controller: PlayerController) : ViewModel() {

    val state: StateFlow<PlayerUiState> = controller.state
    val upNext: StateFlow<List<QueueEntry>> = controller.upNext

    /** "Playing from" label for the Now Playing header (e.g. an album or playlist name). */
    private val _playingFrom = MutableStateFlow<String?>(null)
    val playingFrom: StateFlow<String?> = _playingFrom.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    /** Test/preview hook: overrides the reported position. */
    var positionOverride: Long? = null

    init {
        viewModelScope.launch {
            while (isActive) {
                _positionMs.value = positionOverride ?: controller.currentPositionMs().coerceAtLeast(0L)
                delay(if (state.value.isPlaying) 250 else 1_000)
            }
        }
    }

    override fun onCleared() {
        controller.release()
    }

    fun playSongs(songs: List<Song>, startIndex: Int = 0, shuffle: Boolean = false, source: String? = null) {
        if (songs.isEmpty()) return
        _playingFrom.value = source
        val start = if (shuffle) songs.indices.random() else startIndex.coerceIn(songs.indices)
        controller.setQueue(songs, start, shuffle)
    }

    fun setPlayingFrom(source: String?) {
        _playingFrom.value = source
    }

    fun playNext(songs: List<Song>) = controller.playNext(songs)
    fun addToQueue(songs: List<Song>) = controller.addToQueue(songs)
    fun togglePlayPause() = controller.togglePlayPause()
    fun pause() = controller.pause()
    fun next() = controller.next()
    fun previous() = controller.previous()

    /** Always moves to the previous track (unlike [previous], which restarts after 3 s). */
    fun previousTrack() = controller.previousTrack()
    fun nextTrack() = controller.nextTrack()

    fun seekTo(positionMs: Long) {
        controller.seekTo(positionMs)
        _positionMs.value = positionMs
    }

    fun seekBy(deltaMs: Long) {
        val duration = state.value.durationMs
        val target = (controller.currentPositionMs() + deltaMs).coerceIn(0L, if (duration > 0) duration else Long.MAX_VALUE)
        seekTo(target)
    }

    fun toggleShuffle() = controller.setShuffle(!state.value.shuffle)
    fun cycleRepeat() = controller.setRepeatMode(state.value.repeatMode.next())
    fun playQueueIndex(index: Int) = controller.playQueueIndex(index)
    fun removeQueueIndex(index: Int) = controller.removeQueueIndex(index)
    fun moveQueueItem(from: Int, to: Int) = controller.moveQueueItem(from, to)
    fun clearUpNext() = controller.clearUpNext()
}
