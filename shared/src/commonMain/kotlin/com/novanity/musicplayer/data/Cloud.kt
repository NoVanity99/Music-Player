package com.novanity.musicplayer.data

import androidx.compose.runtime.Immutable
import com.novanity.musicplayer.data.db.DownloadEntity
import com.novanity.musicplayer.platform.KeyValueStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One song in the server's library.json. */
@Serializable
data class CloudSong(
    val id: String,
    val title: String = "",
    val artist: String = "",
    val albumArtist: String = "",
    val album: String = "",
    val track: Int = 0,
    val disc: Int = 1,
    val durationMs: Long = 0,
    val size: Long = 0,
    val mime: String = "",
    val ext: String = "",
    val coverId: String? = null,
    val addedAt: Long = 0,
    /** The file's path inside the uploaded folder. */
    val path: String = "",
    /** "custom" (default), "released", "ep" or "single". */
    val albumType: String = "",
    val year: Int = 0,
)

/** An album the owner marked for advanced transitions (config.json on the server). */
@Serializable
data class CloudAlbumRef(val artist: String = "", val album: String = "")

/** Settings the owner changes on the server, without an app update. */
@Serializable
data class CloudOwnerConfig(val advancedTransitions: List<CloudAlbumRef> = emptyList())

@Serializable
data class CloudLibraryVersion(val version: String = "")

@Serializable
data class CloudLibraryFile(val version: Int = 1, val songs: List<CloudSong> = emptyList())

@Immutable
data class CloudConfig(val serverUrl: String, val key: String) {
    val isSet: Boolean get() = serverUrl.isNotBlank() && key.isNotBlank()
}

sealed interface CloudStatus {
    data object NotConfigured : CloudStatus
    data object Loading : CloudStatus
    data class Online(val songs: Int) : CloudStatus

    /** The server can't be reached: only local and downloaded songs are shown. */
    data class Offline(val message: String) : CloudStatus

    /** The server answered, but not with a library (wrong key, wrong address, ...). */
    data class Error(val message: String) : CloudStatus
}

/** The server answered a download with an error status. */
class CloudHttpException(val status: Int) : Exception("HTTP $status")

/** The app's private storage for downloads and covers (each platform has its own folder). */
interface CloudFiles {
    /**
     * Downloads [url] to [relativePath] in private storage and returns the absolute path. Throws
     * [CloudHttpException] for an error status, [CancellationException] once [isCancelled] is
     * true, and any other exception when the network fails.
     */
    suspend fun download(
        url: String,
        headers: Map<String, String>,
        relativePath: String,
        expectedSize: Long,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ): String

    fun path(relativePath: String): String
    fun exists(path: String): Boolean
    fun size(path: String): Long
    fun delete(path: String)

    /** What the player opens for a file at [path] (a file:// URI on Android, the path on desktop). */
    fun playableUri(path: String): String

    /** Used where there's no storage (tests). */
    object None : CloudFiles {
        override suspend fun download(url: String, headers: Map<String, String>, relativePath: String, expectedSize: Long, onProgress: (Float) -> Unit, isCancelled: () -> Boolean): String =
            throw UnsupportedOperationException()
        override fun path(relativePath: String) = relativePath
        override fun exists(path: String) = false
        override fun size(path: String) = 0L
        override fun delete(path: String) = Unit
        override fun playableUri(path: String) = path
    }
}

/**
 * The cloud library: the user's own server (a Cloudflare Worker in front of R2). Holds the server
 * address and access key, fetches the song list, and turns it into [Song]s for the main library.
 * When the server can't be reached, only downloaded songs are offered.
 */
class CloudRepository(
    private val secure: KeyValueStore,
    private val cache: KeyValueStore,
    private val httpGet: suspend (url: String, headers: Map<String, String>) -> HttpText,
    private val files: CloudFiles,
    private val scope: CoroutineScope,
    downloads: Flow<List<DownloadEntity>>,
    /** A server and key built into the app: used as they are, and can't be changed or removed. */
    private val builtIn: CloudConfig? = null,
    /** How often to look for songs added to the server. */
    private val pollMs: Long = POLL_MS,
) {
    val isBuiltIn: Boolean get() = builtIn != null

    private val json = Json { ignoreUnknownKeys = true }

    private val _config = MutableStateFlow(
        builtIn?.let { CloudConfig(normalizeUrl(it.serverUrl), it.key.trim()) }
            ?: CloudConfig(secure.getString(KEY_URL).orEmpty(), secure.getString(KEY_KEY).orEmpty())
    )
    val config: StateFlow<CloudConfig> = _config.asStateFlow()

    private val library = MutableStateFlow(
        cache.getString(KEY_LIBRARY)?.let { runCatching { json.decodeFromString(CloudLibraryFile.serializer(), it).songs }.getOrNull() }.orEmpty()
    )

    private val _ownerConfig = MutableStateFlow(
        cache.getString(KEY_OWNER_CONFIG)?.let { runCatching { json.decodeFromString(CloudOwnerConfig.serializer(), it) }.getOrNull() } ?: CloudOwnerConfig()
    )

    /** The owner's server-side settings (kept from the last time the server was reached). */
    val ownerConfig: StateFlow<CloudOwnerConfig> = _ownerConfig.asStateFlow()

    /** True when the owner marked this song's album for advanced transitions. */
    fun hasAdvancedTransitions(song: Song): Boolean = hasAdvancedTransitions(song.album, song.artist)

    fun hasAdvancedTransitions(album: String, artist: String): Boolean = _ownerConfig.value.advancedTransitions.any {
        it.album.trim().equals(album.trim(), ignoreCase = true) && (it.artist.isBlank() || artist.contains(it.artist.trim(), ignoreCase = true))
    }

    private suspend fun loadOwnerConfig(config: CloudConfig) {
        try {
            val response = httpGet("${config.serverUrl}/config", mapOf(KEY_HEADER to config.key))
            if (response.status != 200) return
            val parsed = runCatching { json.decodeFromString(CloudOwnerConfig.serializer(), response.body) }.getOrNull() ?: return
            _ownerConfig.value = parsed
            cache.putString(KEY_OWNER_CONFIG, json.encodeToString(CloudOwnerConfig.serializer(), parsed))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // keep what we had
        }
    }

    private val _everConnected = MutableStateFlow(builtIn != null || (_config.value.isSet && cache.getBoolean(KEY_CONNECTED, false)))

    /** True once these settings have worked at least once (so a first-time typo isn't treated as set up). */
    val everConnected: StateFlow<Boolean> = _everConnected.asStateFlow()

    private fun setEverConnected(value: Boolean) {
        _everConnected.value = value
        cache.putBoolean(KEY_CONNECTED, value)
    }

    private val _status = MutableStateFlow<CloudStatus>(if (_config.value.isSet) CloudStatus.Loading else CloudStatus.NotConfigured)
    val status: StateFlow<CloudStatus> = _status.asStateFlow()

    /** Cloud songs for the library: everything when online, only downloads otherwise. */
    val songs: StateFlow<List<Song>> = combine(library, _status, downloads, _config) { all, status, rows, config ->
        val done = rows.filter { it.status == DownloadEntity.STATUS_DONE && files.exists(it.filePath) }.associateBy { it.cloudId }
        val reachable = config.isSet && (status is CloudStatus.Online || status is CloudStatus.Loading)
        val listed = if (reachable) all else emptyList()
        val listedIds = listed.mapTo(HashSet()) { it.id }
        val base = config.serverUrl
        listed.map { it.toSong(base, done[it.id]) } +
            // Downloads stay playable without the server (offline, or removed from the cloud since).
            done.values.filter { it.cloudId !in listedIds }.mapNotNull { row ->
                runCatching { json.decodeFromString(CloudSong.serializer(), row.metaJson) }.getOrNull()?.toSong(base, row)
            }
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val refreshMutex = Mutex()

    init {
        scope.launch {
            refresh()
            // Every minute: come back by itself when the connection does, and pick up songs
            // that were added to the server, so new music appears without an app update.
            while (true) {
                delay(pollMs)
                when (_status.value) {
                    is CloudStatus.Offline -> refresh()
                    is CloudStatus.Online -> checkForChanges()
                    else -> Unit
                }
            }
        }
    }

    fun cloudSong(cloudId: String): CloudSong? = library.value.firstOrNull { it.id == cloudId }

    private var idMapFor: List<Song>? = null
    private var idMap: Map<Long, String> = emptyMap()

    /** The server's id for a song in the library, or null if it isn't a (currently listed) cloud song. */
    fun cloudIdOf(songId: Long): String? {
        val current = songs.value
        if (idMapFor !== current) {
            idMap = current.mapNotNull { s -> s.cloudId?.let { s.id to it } }.toMap()
            idMapFor = current
        }
        return idMap[songId]
    }

    val authHeaders: Map<String, String> get() = mapOf(KEY_HEADER to _config.value.key)

    /** True for URLs on the user's server: the access key is only ever sent there. */
    fun isOurs(url: String): Boolean = _config.value.let { it.isSet && url.startsWith(it.serverUrl + "/") }

    fun streamUrl(cloudId: String) = "${_config.value.serverUrl}/stream/$cloudId"

    /** Saves the server address and key, then loads the library. */
    fun connect(serverUrl: String, key: String) {
        if (builtIn != null) return
        val config = CloudConfig(normalizeUrl(serverUrl), key.trim())
        secure.putString(KEY_URL, config.serverUrl)
        secure.putString(KEY_KEY, config.key)
        if (config.serverUrl != _config.value.serverUrl) setLibrary(emptyList())
        if (config != _config.value) setEverConnected(false)
        _config.value = config
        _status.value = if (config.isSet) CloudStatus.Loading else CloudStatus.NotConfigured
        scope.launch { refresh() }
    }

    /** Forgets the server. Downloaded songs stay on the device. */
    fun disconnect() {
        if (builtIn != null) return
        secure.putString(KEY_URL, null)
        secure.putString(KEY_KEY, null)
        _config.value = CloudConfig("", "")
        setEverConnected(false)
        setLibrary(emptyList())
        _status.value = CloudStatus.NotConfigured
    }

    /** The server's library version when the song list was last fetched after asking for it. */
    private var seenVersion: String? = null

    /**
     * Asks the server whether the song list changed (a tiny request) and fetches it again if so.
     * A failure here changes nothing: playback and [refresh] decide when we're offline.
     */
    suspend fun checkForChanges() {
        val config = _config.value
        if (!config.isSet) return
        val version = try {
            val response = httpGet("${config.serverUrl}/library/version", mapOf(KEY_HEADER to config.key))
            if (response.status != 200) return
            json.decodeFromString(CloudLibraryVersion.serializer(), response.body).version
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        if (version == seenVersion) return
        refresh()
        if (_status.value is CloudStatus.Online && _config.value == config) seenVersion = version
    }

    /** Fetches the song list. Never throws; the outcome is in [status]. */
    suspend fun refresh() = refreshMutex.withLock {
        val config = _config.value
        if (!config.isSet) {
            _status.value = CloudStatus.NotConfigured
            return@withLock
        }
        if (_status.value !is CloudStatus.Online) _status.value = CloudStatus.Loading
        val result = try {
            val response = httpGet("${config.serverUrl}/library", mapOf(KEY_HEADER to config.key))
            when (response.status) {
                200 -> {
                    val parsed = runCatching { json.decodeFromString(CloudLibraryFile.serializer(), response.body) }.getOrNull()
                    if (parsed == null) {
                        CloudStatus.Error("That address isn't a Vanity Player server.")
                    } else {
                        if (parsed.songs != library.value) {
                            val before = library.value.mapTo(HashSet()) { it.id }
                            setLibrary(parsed.songs)
                            // Not on first load: only what turned up since.
                            if (before.isNotEmpty()) parsed.songs.filter { it.id !in before }.takeIf { it.isNotEmpty() }?.let { _arrivals.tryEmit(it) }
                        }
                        prefetchCovers(parsed.songs)
                        loadOwnerConfig(config)
                        CloudStatus.Online(parsed.songs.size)
                    }
                }
                401, 403 -> CloudStatus.Error(
                    if (builtIn != null) "The music server rejected this version of the app. Update to the latest version."
                    else "The server rejected the access key. Check the key and try again."
                )
                404 -> CloudStatus.Error("No Vanity Player server found at that address.")
                else -> CloudStatus.Error("The server had a problem (HTTP ${response.status}). Try again later.")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CloudStatus.Offline("Can't reach the server. Showing downloaded songs only.")
        }
        // The settings may have changed while we were waiting.
        if (_config.value == config) {
            if (result is CloudStatus.Online && !_everConnected.value) setEverConnected(true)
            _status.value = result
        }
    }

    private fun setLibrary(songs: List<CloudSong>) {
        library.value = songs
        cache.putString(KEY_LIBRARY, if (songs.isEmpty()) null else json.encodeToString(CloudLibraryFile.serializer(), CloudLibraryFile(songs = songs)))
    }

    private val _arrivals = MutableSharedFlow<List<CloudSong>>(extraBufferCapacity = 4)

    /** Songs that appeared on the server since the list was last read. */
    val arrivals: SharedFlow<List<CloudSong>> = _arrivals.asSharedFlow()

    private var prefetch: Job? = null

    /** Fetches the covers that aren't on the device yet, in the background, so pages open with their artwork. */
    private fun prefetchCovers(songs: List<CloudSong>) {
        val ids = songs.mapNotNull { it.coverId }.distinct()
        prefetch?.cancel()
        prefetch = scope.launch(Dispatchers.Default) {
            for (group in ids.chunked(6)) coroutineScope { group.forEach { id -> launch { runCatching { coverFile(id) } } } }
        }
    }

    private val coverLock = Mutex()
    private val coverJobs = HashMap<String, CompletableDeferred<String?>>()
    private val coverSlots = Semaphore(6)

    /**
     * The local file for a cover, downloading it first if needed (once, kept for offline use).
     * Null when it isn't on the device and can't be fetched right now.
     */
    suspend fun coverFile(coverId: String): String? {
        val path = files.path("covers/$coverId.jpg")
        if (files.exists(path)) return path
        val config = _config.value
        if (!config.isSet || _status.value is CloudStatus.Offline) return null
        val (job, mine) = coverLock.withLock {
            coverJobs[coverId]?.let { it to false } ?: (CompletableDeferred<String?>().also { coverJobs[coverId] = it } to true)
        }
        if (mine) {
            val result = try {
                coverSlots.withPermit {
                    files.download("${config.serverUrl}/cover/$coverId", mapOf(KEY_HEADER to config.key), "covers/$coverId.jpg", 0, {}, { false })
                }
            } catch (e: CancellationException) {
                coverLock.withLock { coverJobs.remove(coverId) }
                job.complete(null)
                throw e
            } catch (e: Exception) {
                null
            }
            coverLock.withLock { coverJobs.remove(coverId) }
            job.complete(result)
        }
        return job.await()
    }

    private fun CloudSong.toSong(base: String, downloaded: DownloadEntity?): Song = Song(
        id = songIdFor(id),
        uri = if (downloaded != null) files.playableUri(downloaded.filePath) else "$base/stream/$id",
        title = title.ifBlank { "Unknown title" },
        artist = artist.ifBlank { "Unknown artist" },
        album = album.ifBlank { "Unknown album" },
        // Without an album-artist tag, songs in the same folder with the same album name are one
        // album, so a "feat." track doesn't split off into its own copy.
        albumId = albumIdFor(albumArtist.ifBlank { path.substringBeforeLast('/', "").ifEmpty { artist } }, album),
        durationMs = durationMs,
        track = track,
        disc = disc,
        dateAdded = addedAt / 1000,
        folderPath = CLOUD_FOLDER,
        filePath = downloaded?.filePath.orEmpty(),
        source = if (downloaded != null) SongSource.DOWNLOADED else SongSource.CLOUD,
        cloudId = id,
        coverId = coverId,
        sizeBytes = size,
        albumKind = AlbumKind.parse(albumType),
        year = year,
    )

    companion object {
        const val KEY_HEADER = "X-App-Key"
        const val CLOUD_FOLDER = "Cloud"
        private const val KEY_URL = "server_url"
        private const val KEY_KEY = "access_key"
        private const val KEY_LIBRARY = "library"
        private const val KEY_OWNER_CONFIG = "owner_config"
        private const val KEY_CONNECTED = "connected"
        private const val POLL_MS = 60_000L

        /** Cloud songs get negative ids, so they can never collide with a local song's id. */
        fun songIdFor(cloudId: String): Long =
            -((cloudId.take(15).toLongOrNull(16) ?: fnv(cloudId)) and Long.MAX_VALUE) - 1

        fun albumIdFor(artist: String, album: String): Long = -(fnv("${artist.lowercase()}|${album.lowercase()}") and Long.MAX_VALUE) - 1

        private fun fnv(s: String): Long {
            var h = -0x340d631b7bdddcdbL // FNV-1a 64-bit offset basis
            for (c in s) {
                h = h xor c.code.toLong()
                h *= 0x100000001b3L
            }
            return h
        }

        /** "my-worker.workers.dev/" becomes "https://my-worker.workers.dev". */
        fun normalizeUrl(input: String): String {
            val t = input.trim().trimEnd('/')
            if (t.isEmpty()) return ""
            return if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
        }
    }
}
