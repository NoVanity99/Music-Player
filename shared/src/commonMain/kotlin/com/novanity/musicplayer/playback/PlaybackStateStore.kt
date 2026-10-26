package com.novanity.musicplayer.playback

import com.novanity.musicplayer.platform.KeyValueStore

/** Persists the queue, current position and modes so playback resumes where it left off. */
class PlaybackStateStore(private val prefs: KeyValueStore) {

    data class Saved(
        val songIds: List<Long>,
        val index: Int,
        val positionMs: Long,
        val shuffle: Boolean,
        val repeatMode: Int,
    )

    fun saveQueue(songIds: List<Long>) {
        prefs.putString(KEY_QUEUE, songIds.joinToString(","))
    }

    fun savePosition(index: Int, positionMs: Long) {
        prefs.putInt(KEY_INDEX, index)
        prefs.putLong(KEY_POSITION, positionMs)
    }

    fun saveModes(shuffle: Boolean, repeatMode: Int) {
        prefs.putBoolean(KEY_SHUFFLE, shuffle)
        prefs.putInt(KEY_REPEAT, repeatMode)
    }

    fun load(): Saved? {
        val ids = prefs.getString(KEY_QUEUE)
            ?.split(',')
            ?.mapNotNull { it.toLongOrNull() }
            .orEmpty()
        if (ids.isEmpty()) return null
        return Saved(
            songIds = ids,
            index = prefs.getInt(KEY_INDEX, 0).coerceIn(0, ids.lastIndex),
            positionMs = prefs.getLong(KEY_POSITION, 0L).coerceAtLeast(0L),
            shuffle = prefs.getBoolean(KEY_SHUFFLE, false),
            repeatMode = prefs.getInt(KEY_REPEAT, 0),
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
