package com.novanity.musicplayer.data

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Single source of truth for the on-device audio library. */
class MusicRepository(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val _songs = MutableStateFlow<List<Song>?>(null)

    /** All songs, or null until the first scan has finished. */
    val songs: StateFlow<List<Song>?> = _songs.asStateFlow()

    @Volatile
    var songsById: Map<Long, Song> = emptyMap()
        private set

    private val scanMutex = Mutex()
    private var observer: ContentObserver? = null
    private var pendingRescan: Job? = null

    suspend fun refresh(): List<Song> = scanMutex.withLock {
        val result = withContext(Dispatchers.IO) { MediaStoreScanner.scan(context) }
        songsById = result.associateBy { it.id }
        _songs.value = result
        result
    }

    /** Returns the library, scanning first if nothing has been loaded yet. */
    suspend fun ensureLoaded(): Map<Long, Song> {
        if (_songs.value == null) refresh()
        return songsById
    }

    /** Rescans (debounced) whenever MediaStore reports changes. */
    fun startObserving() {
        if (observer != null) return
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                pendingRescan?.cancel()
                pendingRescan = scope.launch {
                    delay(1_500)
                    refresh()
                }
            }
        }
        context.contentResolver.registerContentObserver(MediaStoreScanner.collection, true, obs)
        observer = obs
    }
}
