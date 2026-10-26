package com.novanity.musicplayer.data

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
import kotlinx.coroutines.IO
import kotlin.concurrent.Volatile
import com.novanity.musicplayer.platform.LibrarySource

/** Single source of truth for the audio library, limited to the folders the user chose. */
class MusicRepository(
    private val source: LibrarySource,
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

    /** Test hook: replaces the platform scan with fixed data. */
    var scanOverride: (() -> List<Song>)? = null

    private val scanMutex = Mutex()
    private val syncMutex = Mutex()
    private var observing = false
    private var pendingRescan: Job? = null

    init {
        // Folder choices changed: rescan (desktop only scans chosen folders) and re-filter.
        scope.launch {
            combine(includedFolders, excludedFolders) { _, _ -> }.drop(1).collect { refresh() }
        }
    }

    suspend fun refresh(): List<Song> = scanMutex.withLock {
        val included = includedFolders.value
        val excluded = excludedFolders.value
        rawSongs = withContext(Dispatchers.IO) { scanOverride?.invoke() ?: source.scan(included, excluded) }
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
            if (source.indexNewFiles(included, excludedFolders.value)) {
                val before = songsById.keys
                refresh()
                val added = songsById.keys.count { it !in before }
                if (added > 0) _newSongsFound.tryEmit(added)
            }
        } finally {
            _syncing.value = false
        }
    }

    /** Rescans (debounced) whenever the platform reports library changes. */
    fun startObserving() {
        if (observing) return
        observing = true
        source.observeChanges {
            pendingRescan?.cancel()
            pendingRescan = scope.launch {
                delay(1_500)
                refresh()
            }
        }
    }
}
