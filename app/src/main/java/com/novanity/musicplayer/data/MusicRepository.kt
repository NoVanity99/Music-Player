package com.novanity.musicplayer.data

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Single source of truth for the audio library, limited to the folders the user chose. */
class MusicRepository(
    private val context: Context,
    private val scope: CoroutineScope,
    private val includedFolders: StateFlow<Set<String>>,
    private val excludedFolders: StateFlow<Set<String>>,
) {
    private var rawSongs: List<Song>? = null

    private val _songs = MutableStateFlow<List<Song>?>(null)

    /** Songs in the chosen folders (minus ignored ones), or null until the first scan finishes. */
    val songs: StateFlow<List<Song>?> = _songs.asStateFlow()

    @Volatile
    var songsById: Map<Long, Song> = emptyMap()
        private set

    private val _syncing = MutableStateFlow(false)

    /** True while the chosen folders are being checked on disk for new files. */
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _newSongsFound = MutableSharedFlow<Int>(extraBufferCapacity = 4)

    /** Emits how many songs a folder check added, when it added any. */
    val newSongsFound: SharedFlow<Int> = _newSongsFound.asSharedFlow()

    /** Test hook: replaces the MediaStore scan with fixed data. */
    @VisibleForTesting
    internal var scanOverride: (() -> List<Song>)? = null

    private val scanMutex = Mutex()
    private val syncMutex = Mutex()
    private var observer: ContentObserver? = null
    private var pendingRescan: Job? = null

    init {
        // Re-apply the filter whenever the folder choices change; no rescan needed.
        scope.launch {
            combine(includedFolders, excludedFolders) { _, _ -> }.drop(1).collect { scanMutex.withLock { publish() } }
        }
    }

    suspend fun refresh(): List<Song> = scanMutex.withLock {
        rawSongs = withContext(Dispatchers.IO) { scanOverride?.invoke() ?: MediaStoreScanner.scan(context) }
        publish()
    }

    private suspend fun publish(): List<Song> {
        val raw = rawSongs ?: return emptyList()
        val included = includedFolders.value
        val excluded = excludedFolders.value
        val filtered = withContext(Dispatchers.Default) {
            raw.filter { song -> included.any { song.isIn(it) } && excluded.none { song.isIn(it) } }
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

    /**
     * Looks through the chosen folders on disk for audio files Android hasn't indexed yet, indexes
     * them, and refreshes the library. Runs on every app start and whenever a folder is added.
     */
    suspend fun syncFolders() = syncMutex.withLock {
        val included = includedFolders.value
        if (included.isEmpty()) return@withLock
        _syncing.value = true
        try {
            val missing = FolderSync.findUnindexed(context, included, excludedFolders.value)
            if (missing.isNotEmpty()) {
                val before = songsById.keys
                FolderSync.index(context, missing)
                refresh()
                val added = songsById.keys.count { it !in before }
                if (added > 0) _newSongsFound.tryEmit(added)
            }
        } finally {
            _syncing.value = false
        }
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
