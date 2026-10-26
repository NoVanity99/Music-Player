package com.novanity.musicplayer.playback

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.novanity.musicplayer.data.MusicRepository
import com.novanity.musicplayer.data.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

/** [PlayerController] that drives [PlaybackService] through a Media3 MediaController. */
class Media3PlayerController(
    private val context: Context,
    private val music: MusicRepository,
    private val scope: CoroutineScope,
) : PlayerController {

    private var controller: MediaController? = null

    private val _state = MutableStateFlow(PlayerUiState())
    override val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val _upNext = MutableStateFlow<List<QueueEntry>>(emptyList())
    override val upNext: StateFlow<List<QueueEntry>> = _upNext.asStateFlow()

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
        scope.launch {
            try {
                val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
                val c = MediaController.Builder(context, token).buildAsync().await()
                controller = c
                c.addListener(listener)
                refreshState()
                refreshQueue()
            } catch (e: Exception) {
                Log.e("Media3PlayerController", "Could not connect to playback service", e)
            }
        }
    }

    override fun release() {
        controller?.removeListener(listener)
        controller?.release()
        controller = null
        scope.cancel()
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
            repeatMode = RepeatMode.fromStored(c.repeatMode),
            hasNext = c.hasNextMediaItem(),
            hasPrevious = c.hasPreviousMediaItem(),
        )
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

    override fun currentPositionMs(): Long = controller?.currentPosition ?: 0L

    override fun setQueue(songs: List<Song>, startIndex: Int, shuffle: Boolean) {
        val c = controller ?: return
        c.shuffleModeEnabled = shuffle
        c.setMediaItems(songs.map { it.toMediaItem() }, startIndex, 0L)
        c.prepare()
        c.play()
    }

    override fun playNext(songs: List<Song>) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) setQueue(songs, 0, false)
        else c.addMediaItems(c.currentMediaItemIndex + 1, songs.map { it.toMediaItem() })
    }

    override fun addToQueue(songs: List<Song>) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) setQueue(songs, 0, false)
        else c.addMediaItems(songs.map { it.toMediaItem() })
    }

    override fun togglePlayPause() {
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

    override fun pause() {
        controller?.pause()
    }

    override fun next() {
        controller?.seekToNext()
    }

    override fun previous() {
        controller?.seekToPrevious()
    }

    override fun nextTrack() {
        controller?.seekToNextMediaItem()
    }

    override fun previousTrack() {
        controller?.seekToPreviousMediaItem()
    }

    override fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
    }

    override fun setShuffle(enabled: Boolean) {
        controller?.shuffleModeEnabled = enabled
    }

    override fun setRepeatMode(mode: RepeatMode) {
        controller?.repeatMode = mode.storedValue // RepeatMode values match Media3's constants
    }

    override fun playQueueIndex(index: Int) {
        val c = controller ?: return
        if (index !in 0 until c.mediaItemCount) return
        c.seekToDefaultPosition(index)
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        c.play()
    }

    override fun removeQueueIndex(index: Int) {
        val c = controller ?: return
        if (index in 0 until c.mediaItemCount) c.removeMediaItem(index)
    }

    override fun moveQueueItem(from: Int, to: Int) {
        val c = controller ?: return
        if (from == to || from !in 0 until c.mediaItemCount || to !in 0 until c.mediaItemCount) return
        c.moveMediaItem(from, to)
    }

    override fun clearUpNext() {
        val c = controller ?: return
        val current = c.currentMediaItemIndex
        if (current < 0) return
        if (current + 1 < c.mediaItemCount) c.removeMediaItems(current + 1, c.mediaItemCount)
        if (current > 0) c.removeMediaItems(0, current)
    }

    override fun setVolume(volume: Float) {
        controller?.volume = volume.coerceIn(0f, 1f)
    }

    override fun setSpeed(speed: Float) {
        controller?.setPlaybackSpeed(speed.coerceIn(0.5f, 2f))
    }
}
