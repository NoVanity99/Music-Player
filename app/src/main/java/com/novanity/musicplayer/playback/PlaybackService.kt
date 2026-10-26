package com.novanity.musicplayer.playback

import android.app.PendingIntent
import android.content.Intent
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.novanity.musicplayer.MainActivity
import com.novanity.musicplayer.appContainer
import com.novanity.musicplayer.platform.AndroidEqualizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the ExoPlayer and exposes it through a MediaSession. This gives us the media notification,
 * lock-screen and Bluetooth/headset controls, and keeps music playing with the UI gone.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val container by lazy { appContainer }

    private var positionSaver: Job? = null
    private var crossfader: Crossfader? = null
    private var consecutiveErrors = 0
    private var lastHistoryMediaId: String? = null

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true, // pauses for calls, ducks for notifications
            )
            .setHandleAudioBecomingNoisy(true) // pause when headphones are unplugged
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player.addListener(PlayerListener())

        // Crossfade (settings-driven) and the system equalizer on our audio session.
        crossfader = Crossfader(this, player, scope) { container.settings.crossfadeSeconds.value * 1000 }.also { it.start() }
        scope.launch {
            kotlinx.coroutines.flow.combine(container.settings.eqEnabled, container.settings.eqPreset, container.settings.eqBands) { e, p, b -> Triple(e, p, b) }
                .collect { (e, p, b) -> AndroidEqualizer.apply(e, p, b) }
        }

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_NOW_PLAYING, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        mediaSession = MediaSession.Builder(this, player)
            .setCallback(SessionCallback())
            .setSessionActivity(openApp)
            .build()

        restoreLastSession()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        saveEverything()
        val p = mediaSession?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0 || p.playbackState == Player.STATE_ENDED) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        crossfader?.release()
        AndroidEqualizer.release()
        saveEverything()
        scope.cancel()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    // ---- State restore / persistence ----

    private data class RestoredQueue(val items: List<MediaItem>, val startIndex: Int, val positionMs: Long)

    private suspend fun buildRestoredQueue(): RestoredQueue? {
        val saved = container.playbackStateStore.load() ?: return null
        val byId = container.musicRepository.ensureLoaded()
        val items = ArrayList<MediaItem>(saved.songIds.size)
        var startIndex = 0
        var currentFound = false
        saved.songIds.forEachIndexed { i, id ->
            val song = byId[id] ?: return@forEachIndexed // file was deleted since last time
            if (i == saved.index) {
                startIndex = items.size
                currentFound = true
            }
            items += song.toMediaItem()
        }
        if (items.isEmpty()) return null
        return RestoredQueue(items, startIndex, if (currentFound) saved.positionMs else 0L)
    }

    private fun restoreLastSession() {
        scope.launch {
            val restored = buildRestoredQueue() ?: return@launch
            // The UI may already have started something while we were scanning.
            if (player.mediaItemCount > 0) return@launch
            container.playbackStateStore.load()?.let {
                player.shuffleModeEnabled = it.shuffle
                player.repeatMode = it.repeatMode
            }
            player.setMediaItems(restored.items, restored.startIndex, restored.positionMs)
            player.prepare()
        }
    }

    private fun saveQueue() {
        val ids = ArrayList<Long>(player.mediaItemCount)
        for (i in 0 until player.mediaItemCount) {
            player.getMediaItemAt(i).songId?.let(ids::add)
        }
        container.playbackStateStore.saveQueue(ids)
    }

    private fun savePosition() {
        if (player.mediaItemCount == 0) return
        container.playbackStateStore.savePosition(player.currentMediaItemIndex, player.currentPosition)
    }

    private fun saveEverything() {
        if (!::player.isInitialized) return
        saveQueue()
        savePosition()
        container.playbackStateStore.saveModes(player.shuffleModeEnabled, player.repeatMode)
    }

    private fun recordHistory() {
        if (!player.isPlaying) return
        val item = player.currentMediaItem ?: return
        if (item.mediaId == lastHistoryMediaId) return
        lastHistoryMediaId = item.mediaId
        val songId = item.songId ?: return
        scope.launch(Dispatchers.IO) { container.historyRepository.record(songId) }
    }

    private inner class PlayerListener : Player.Listener {
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            AndroidEqualizer.attach(audioSessionId)
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            // A user seek/skip during a crossfade ends the fade immediately.
            if (reason == Player.DISCONTINUITY_REASON_SEEK && newPosition.mediaItemIndex == oldPosition.mediaItemIndex) crossfader?.cancel()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            savePosition()
            recordHistory()
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                saveQueue()
                savePosition()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            positionSaver?.cancel()
            if (isPlaying) {
                recordHistory()
                positionSaver = scope.launch {
                    while (isActive) {
                        delay(5_000)
                        savePosition()
                    }
                }
            } else {
                savePosition()
            }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            container.playbackStateStore.saveModes(shuffleModeEnabled, player.repeatMode)
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            container.playbackStateStore.saveModes(player.shuffleModeEnabled, repeatMode)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) consecutiveErrors = 0
        }

        override fun onPlayerError(error: PlaybackException) {
            // Corrupt or unsupported file: tell the user and move on to the next track.
            val title = player.currentMediaItem?.mediaMetadata?.title ?: "this track"
            Toast.makeText(this@PlaybackService, "Can't play “$title” — skipping", Toast.LENGTH_SHORT).show()
            consecutiveErrors++
            if (consecutiveErrors >= player.mediaItemCount || !player.hasNextMediaItem()) {
                // Nothing playable left; stop instead of looping forever.
                consecutiveErrors = 0
                player.pause()
                return
            }
            player.seekToNextMediaItem()
            player.prepare()
            player.play()
        }
    }

    private inner class SessionCallback : MediaSession.Callback {
        /** Controllers don't send playable URIs over IPC, so resolve them from our library. */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val byId = container.musicRepository.songsById
            val resolved = mediaItems.mapNotNullTo(ArrayList()) { item ->
                if (item.localConfiguration != null) {
                    item
                } else {
                    val song = item.songId?.let(byId::get) ?: return@mapNotNullTo null
                    item.buildUpon().setUri(android.net.Uri.parse(song.uri)).build()
                }
            }
            return Futures.immediateFuture<MutableList<MediaItem>>(resolved)
        }

        /** Lets the system "resume" button (e.g. after a reboot) pick up the last queue. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val restored = buildRestoredQueue() ?: error("Nothing to resume")
            MediaSession.MediaItemsWithStartPosition(restored.items, restored.startIndex, restored.positionMs)
        }
    }
}
