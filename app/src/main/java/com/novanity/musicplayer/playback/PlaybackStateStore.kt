package com.novanity.musicplayer.playback

import android.content.Context
import androidx.media3.common.Player

/** Persists the queue, current position and modes so playback resumes where it left off. */
class PlaybackStateStore(context: Context) {
    private val prefs = context.getSharedPreferences("playback_state", Context.MODE_PRIVATE)

    data class Saved(
        val songIds: List<Long>,
        val index: Int,
        val positionMs: Long,
        val shuffle: Boolean,
        val repeatMode: Int,
    )

    fun saveQueue(songIds: List<Long>) {
        prefs.edit().putString(KEY_QUEUE, songIds.joinToString(",")).apply()
    }

    fun savePosition(index: Int, positionMs: Long) {
        prefs.edit().putInt(KEY_INDEX, index).putLong(KEY_POSITION, positionMs).apply()
    }

    fun saveModes(shuffle: Boolean, repeatMode: Int) {
        prefs.edit().putBoolean(KEY_SHUFFLE, shuffle).putInt(KEY_REPEAT, repeatMode).apply()
    }

    fun load(): Saved? {
        val ids = prefs.getString(KEY_QUEUE, null)
            ?.split(',')
            ?.mapNotNull { it.toLongOrNull() }
            .orEmpty()
        if (ids.isEmpty()) return null
        return Saved(
            songIds = ids,
            index = prefs.getInt(KEY_INDEX, 0).coerceIn(0, ids.lastIndex),
            positionMs = prefs.getLong(KEY_POSITION, 0L).coerceAtLeast(0L),
            shuffle = prefs.getBoolean(KEY_SHUFFLE, false),
            repeatMode = prefs.getInt(KEY_REPEAT, Player.REPEAT_MODE_OFF),
        )
    }

    private companion object {
        const val KEY_QUEUE = "queue"
        const val KEY_INDEX = "index"
        const val KEY_POSITION = "position"
        const val KEY_SHUFFLE = "shuffle"
        const val KEY_REPEAT = "repeat"
    }
}
