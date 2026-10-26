package com.novanity.musicplayer.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * True crossfade for Media3: near the end of a song, a temporary second player keeps playing the
 * outgoing song's tail and fades it out while the main (session) player moves on to the next song
 * and fades in. The session, notification and queue only ever see the main player.
 */
@OptIn(UnstableApi::class)
class Crossfader(
    private val context: Context,
    private val main: ExoPlayer,
    private val scope: CoroutineScope,
    private val crossfadeMs: () -> Int,
) {
    private var watcher: Job? = null
    private var fade: Job? = null
    private var tail: ExoPlayer? = null

    fun start() {
        watcher?.cancel()
        watcher = scope.launch {
            while (isActive) {
                delay(150)
                maybeStart()
            }
        }
    }

    private fun maybeStart() {
        val ms = crossfadeMs()
        if (ms <= 0 || fade?.isActive == true || !main.isPlaying) return
        if (main.repeatMode == Player.REPEAT_MODE_ONE || !main.hasNextMediaItem()) return
        val duration = main.duration
        if (duration == C.TIME_UNSET || duration < ms * 2L) return
        if (duration - main.currentPosition > ms) return
        val item = main.currentMediaItem ?: return

        val baseVolume = main.volume.coerceIn(0f, 1f)
        val out = ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ false, // the main player owns audio focus
            )
            .build()
        out.setMediaItem(item, main.currentPosition)
        out.volume = baseVolume
        out.prepare()
        out.play()
        tail = out

        main.volume = 0f
        main.seekToNextMediaItem()

        fade = scope.launch {
            val steps = (ms / 40).coerceAtLeast(1)
            for (i in 1..steps) {
                val p = i / steps.toFloat()
                out.volume = baseVolume * cos(p * PI / 2).toFloat()
                main.volume = baseVolume * sin(p * PI / 2).toFloat()
                delay(40)
                // The user paused or skipped mid-fade: drop the tail right away.
                if (!main.playWhenReady) break
            }
            main.volume = baseVolume
            releaseTail()
        }
    }

    /** Call when the user seeks/skips/pauses so a fading tail never lingers. */
    fun cancel() {
        if (fade?.isActive == true) {
            fade?.cancel()
            // Jump straight to the full level the fade was heading for.
            main.volume = maxOf(main.volume, tail?.volume ?: 0f)
        }
        releaseTail()
    }

    private fun releaseTail() {
        tail?.run { stop(); release() }
        tail = null
    }

    fun release() {
        watcher?.cancel()
        fade?.cancel()
        releaseTail()
    }
}
