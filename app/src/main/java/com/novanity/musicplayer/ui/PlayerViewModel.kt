package com.novanity.musicplayer.ui

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.novanity.musicplayer.data.MusicRepository
import com.novanity.musicplayer.data.Song
import com.novanity.musicplayer.playback.PlaybackService
import com.novanity.musicplayer.playback.songId
import com.novanity.musicplayer.playback.toMediaItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
) {
    val hasMedia: Boolean get() = current != null
}

/** UI-side handle on the playback service, talking to it through a MediaController. */
class PlayerViewModel(
    private val context: Context,
    private val music: MusicRepository,
) : ViewModel() {

    private var controller: MediaController? = null

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    /** Upcoming entries in actual play order (accounts for shuffle). */
    private val _upNext = MutableStateFlow<List<QueueEntry>>(emptyList())
    val upNext: StateFlow<List<QueueEntry>> = _upNext.asStateFlow()

    /** "Playing from" label for the Now Playing header (e.g. an album or playlist name). */
    private val _playingFrom = MutableStateFlow<String?>(null)
    val playingFrom: StateFlow<String?> = _playingFrom.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            refreshState()
            if (events.containsAny(
                    Player.EVENT_TIMELINE_CHANGED,
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                )
            ) {
                refreshQueue()
            }
        }
    }

    init {
        viewModelScope.launch {
            try {
                val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
                val c = MediaController.Builder(context, token).buildAsync().await()
                controller = c
                c.addListener(listener)
                refreshState()
                refreshQueue()
            } catch (e: Exception) {
                Log.e("PlayerViewModel", "Could not connect to playback service", e)
            }
        }
        viewModelScope.launch {
            while (isActive) {
                controller?.let { _positionMs.value = it.currentPosition.coerceAtLeast(0L) }
                delay(if (_state.value.isPlaying) 250 else 1_000)
            }
        }
    }

    override fun onCleared() {
        controller?.removeListener(listener)
        controller?.release()
        controller = null
    }

    private fun MediaItem.toEntry(index: Int) = QueueEntry(
        index = index,
        key = mediaId,
        songId = songId,
        title = mediaMetadata.title?.toString().orEmpty(),
        artist = mediaMetadata.artist?.toString().orEmpty(),
    )

    private fun refreshState() {
        val c = controller ?: return
        val item = c.currentMediaItem
        val songDuration = item?.songId?.let { music.songsById[it]?.durationMs } ?: 0L
        _state.value = PlayerUiState(
            connected = true,
            current = item?.toEntry(c.currentMediaItemIndex),
            isPlaying = c.isPlaying,
            durationMs = c.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: songDuration,
            shuffle = c.shuffleModeEnabled,
            repeatMode = c.repeatMode,
        )
        _positionMs.value = c.currentPosition.coerceAtLeast(0L)
    }

    private fun refreshQueue() {
        val c = controller ?: return
        val timeline = c.currentTimeline
        if (timeline.isEmpty) {
            _upNext.value = emptyList()
            return
        }
        val shuffle = c.shuffleModeEnabled
        val result = ArrayList<QueueEntry>()
        var i = timeline.getNextWindowIndex(c.currentMediaItemIndex, Player.REPEAT_MODE_OFF, shuffle)
        while (i != C.INDEX_UNSET) {
            result += c.getMediaItemAt(i).toEntry(i)
            i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, shuffle)
        }
        _upNext.value = result
    }

    /** Test hook: shows a fixed player state without a playback service. */
    @VisibleForTesting
    internal fun showForTest(state: PlayerUiState, upNext: List<QueueEntry>, positionMs: Long, playingFrom: String?) {
        _state.value = state
        _upNext.value = upNext
        _positionMs.value = positionMs
        _playingFrom.value = playingFrom
    }

    // ---- Commands ----

    fun playSongs(songs: List<Song>, startIndex: Int = 0, shuffle: Boolean = false, source: String? = null) {
        val c = controller ?: return
        if (songs.isEmpty()) return
        _playingFrom.value = source
        c.shuffleModeEnabled = shuffle
        val start = if (shuffle) songs.indices.random() else startIndex.coerceIn(songs.indices)
        c.setMediaItems(songs.map { it.toMediaItem() }, start, 0L)
        c.prepare()
        c.play()
    }

    fun playNext(songs: List<Song>) {
        val c = controller ?: return
        if (songs.isEmpty()) return
        if (c.mediaItemCount == 0) {
            playSongs(songs)
        } else {
            c.addMediaItems(c.currentMediaItemIndex + 1, songs.map { it.toMediaItem() })
        }
    }

    fun addToQueue(songs: List<Song>) {
        val c = controller ?: return
        if (songs.isEmpty()) return
        if (c.mediaItemCount == 0) {
            playSongs(songs)
        } else {
            c.addMediaItems(songs.map { it.toMediaItem() })
        }
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) {
            c.pause()
            return
        }
        when (c.playbackState) {
            Player.STATE_IDLE -> c.prepare()
            Player.STATE_ENDED -> c.seekToDefaultPosition()
        }
        c.play()
    }

    fun next() {
        controller?.seekToNext()
    }

    fun previous() {
        controller?.seekToPrevious()
    }

    fun seekTo(positionMs: Long) {
        val c = controller ?: return
        c.seekTo(positionMs)
        _positionMs.value = positionMs
    }

    fun toggleShuffle() {
        controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    }

    fun cycleRepeat() {
        controller?.let {
            it.repeatMode = when (it.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }

    fun playQueueIndex(index: Int) {
        val c = controller ?: return
        if (index !in 0 until c.mediaItemCount) return
        c.seekToDefaultPosition(index)
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        c.play()
    }

    fun removeQueueIndex(index: Int) {
        val c = controller ?: return
        if (index in 0 until c.mediaItemCount) c.removeMediaItem(index)
    }

    /** Removes everything except the current song. */
    fun clearUpNext() {
        val c = controller ?: return
        val current = c.currentMediaItemIndex
        if (current < 0) return
        if (current + 1 < c.mediaItemCount) c.removeMediaItems(current + 1, c.mediaItemCount)
        if (current > 0) c.removeMediaItems(0, current)
    }

    fun moveQueueItem(from: Int, to: Int) {
        val c = controller ?: return
        if (from == to || from !in 0 until c.mediaItemCount || to !in 0 until c.mediaItemCount) return
        c.moveMediaItem(from, to)
    }
}
