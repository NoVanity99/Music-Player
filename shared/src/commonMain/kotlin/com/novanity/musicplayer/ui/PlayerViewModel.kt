package com.novanity.musicplayer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novanity.musicplayer.data.Song
import com.novanity.musicplayer.data.SettingsRepository
import com.novanity.musicplayer.playback.PlayerController
import com.novanity.musicplayer.playback.PlayerUiState
import com.novanity.musicplayer.playback.QueueEntry
import com.novanity.musicplayer.data.nowMillis
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** UI-facing playback state and commands, backed by the platform [PlayerController]. */
class PlayerViewModel(
    private val controller: PlayerController,
    private val settings: SettingsRepository? = null,
) : ViewModel() {

    val state: StateFlow<PlayerUiState> = controller.state
    val upNext: StateFlow<List<QueueEntry>> = controller.upNext

    /** "Playing from" label for the Now Playing header (e.g. an album or playlist name). */
    private val _playingFrom = MutableStateFlow<String?>(null)
    val playingFrom: StateFlow<String?> = _playingFrom.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    /** Test/preview hook: overrides the reported position. */
    var positionOverride: Long? = null

    /** Active sleep timer, if any. */
    private val _sleepTimer = MutableStateFlow<SleepTimer?>(null)
    val sleepTimer: StateFlow<SleepTimer?> = _sleepTimer.asStateFlow()
    private var sleepJob: Job? = null

    val speed: StateFlow<Float> = settings?.speed ?: MutableStateFlow(1f)

    private val _volume = MutableStateFlow(settings?.volume?.value ?: 1f)
    val volume: StateFlow<Float> = _volume.asStateFlow()
    private var volumeBeforeMute = 1f

    init {
        settings?.let { s ->
            controller.setVolume(s.volume.value)
            viewModelScope.launch { s.speed.collect { controller.setSpeed(it) } }
            viewModelScope.launch { s.crossfadeSeconds.collect { controller.setCrossfadeMs(it * 1000) } }
        }
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

    /** For platforms that don't use a ViewModelStore (desktop): stop playback and free the engine. */
    fun shutdown() = controller.release()

    fun setVolume(value: Float) {
        val v = value.coerceIn(0f, 1f)
        _volume.value = v
        controller.setVolume(v)
        settings?.setVolume(v)
    }

    fun changeVolume(delta: Float) = setVolume(_volume.value + delta)

    fun setSpeed(value: Float) {
        settings?.setSpeed(value) ?: controller.setSpeed(value)
    }

    // ---- Sleep timer ----

    /** Pause after [minutes], fading out over the last 20 seconds. */
    fun startSleepTimer(minutes: Int) = startSleep(SleepTimer(endsAtMillis = nowMillis() + minutes * 60_000L, endOfTrack = false))

    /** Pause when the current song finishes. */
    fun sleepAtEndOfTrack() = startSleep(SleepTimer(endsAtMillis = null, endOfTrack = true))

    fun cancelSleepTimer() {
        sleepJob?.cancel()
        sleepJob = null
        _sleepTimer.value = null
        controller.setVolume(_volume.value)
    }

    private fun startSleep(timer: SleepTimer) {
        sleepJob?.cancel()
        _sleepTimer.value = timer
        val startKey = state.value.current?.key
        sleepJob = viewModelScope.launch {
            while (isActive) {
                delay(250)
                val remaining = when {
                    timer.endOfTrack -> {
                        val s = state.value
                        // The song changed (it finished) or is about to end.
                        if (s.current?.key != startKey) 0L else (s.durationMs - controller.currentPositionMs()).coerceAtLeast(0)
                    }
                    else -> (timer.endsAtMillis!! - nowMillis()).coerceAtLeast(0)
                }
                if (!timer.endOfTrack) {
                    // Gentle fade over the last 20 s.
                    val fade = (remaining / FADE_MS.toFloat()).coerceIn(0f, 1f)
                    controller.setVolume(_volume.value * fade)
                }
                if (remaining <= if (timer.endOfTrack) 350L else 0L) {
                    controller.pause()
                    break
                }
            }
            delay(400)
            controller.setVolume(_volume.value)
            _sleepTimer.value = null
        }
    }

    fun toggleMute() {
        if (_volume.value > 0f) {
            volumeBeforeMute = _volume.value
            setVolume(0f)
        } else {
            setVolume(volumeBeforeMute.takeIf { it > 0f } ?: 0.7f)
        }
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

private const val FADE_MS = 20_000L

/** A sleep timer: a wall-clock deadline, or "when this song ends". */
data class SleepTimer(val endsAtMillis: Long?, val endOfTrack: Boolean)
