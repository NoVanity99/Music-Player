package com.novanity.musicplayer.data

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A folder that contains audio, as found on the device (before the user's folder filter). */
@Immutable
data class DeviceFolder(val path: String, val songCount: Int)

/** Single source of truth for the on-device audio library. */
class MusicRepository(
    private val context: Context,
    private val scope: CoroutineScope,
    private val includedFolders: StateFlow<Set<String>>,
) {
    private var rawSongs: List<Song>? = null

    private val _songs = MutableStateFlow<List<Song>?>(null)

    /** Songs in the folders the user chose (or all songs), or null until the first scan finishes. */
    val songs: StateFlow<List<Song>?> = _songs.asStateFlow()

    private val _deviceFolders = MutableStateFlow<List<DeviceFolder>>(emptyList())

    /** Every folder on the device that holds audio, regardless of the folder filter. */
    val deviceFolders: StateFlow<List<DeviceFolder>> = _deviceFolders.asStateFlow()

    @Volatile
    var songsById: Map<Long, Song> = emptyMap()
        private set

    /** Test hook: replaces the MediaStore scan with fixed data. */
    @VisibleForTesting
    internal var scanOverride: (() -> List<Song>)? = null

    private val scanMutex = Mutex()
    private var observer: ContentObserver? = null
    private var pendingRescan: Job? = null

    init {
        // Re-apply the filter whenever the chosen folders change; no rescan needed.
        scope.launch {
            includedFolders.drop(1).collect { scanMutex.withLock { publish() } }
        }
    }

    suspend fun refresh(): List<Song> = scanMutex.withLock {
        val result = withContext(Dispatchers.IO) { scanOverride?.invoke() ?: MediaStoreScanner.scan(context) }
        rawSongs = result
        _deviceFolders.value = result.groupingBy { it.folderPath }.eachCount()
            .map { (path, count) -> DeviceFolder(path, count) }
            .sortedBy { it.path.lowercase() }
        publish()
    }

    private suspend fun publish(): List<Song> {
        val raw = rawSongs ?: return emptyList()
        val folders = includedFolders.value
        val filtered = if (folders.isEmpty()) {
            raw
        } else {
            withContext(Dispatchers.Default) { raw.filter { song -> folders.any { song.isIn(it) } } }
        }
        songsById = filtered.associateBy { it.id }
        _songs.value = filtered
        return filtered
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

/** True if [path] is [folder] or one of its subfolders. */
fun pathIsIn(path: String, folder: String): Boolean = path == folder || path.startsWith("$folder/")

fun Song.isIn(folder: String): Boolean = pathIsIn(folderPath, folder)
