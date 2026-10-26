package com.novanity.musicplayer.desktop

import com.novanity.musicplayer.data.Song
import com.novanity.musicplayer.playback.PlayerController
import com.novanity.musicplayer.playback.PlayerUiState
import com.novanity.musicplayer.playback.QueueEntry
import com.novanity.musicplayer.playback.RepeatMode
import kotlinx.coroutines.flow.MutableStateFlow

class FakePlayer : PlayerController {
    override val state = MutableStateFlow(PlayerUiState())
    override val upNext = MutableStateFlow<List<QueueEntry>>(emptyList())
    override fun currentPositionMs() = 0L
    override fun setQueue(songs: List<Song>, startIndex: Int, shuffle: Boolean) {}
    override fun playNext(songs: List<Song>) {}
    override fun addToQueue(songs: List<Song>) {}
    override fun togglePlayPause() {}
    override fun pause() {}
    override fun previous() {}
    override fun next() {}
    override fun previousTrack() {}
    override fun nextTrack() {}
    override fun seekTo(positionMs: Long) {}
    override fun setShuffle(enabled: Boolean) {}
    override fun setRepeatMode(mode: RepeatMode) {}
    override fun playQueueIndex(index: Int) {}
    override fun removeQueueIndex(index: Int) {}
    override fun moveQueueItem(from: Int, to: Int) {}
    override fun clearUpNext() {}
    override fun release() {}
}
