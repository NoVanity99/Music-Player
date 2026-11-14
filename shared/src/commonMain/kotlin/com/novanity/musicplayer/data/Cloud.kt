package com.novanity.musicplayer.data

import androidx.compose.runtime.Immutable
import com.novanity.musicplayer.data.db.DownloadEntity
import com.novanity.musicplayer.platform.KeyValueStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
)

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
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val _config = MutableStateFlow(CloudConfig(secure.getString(KEY_URL).orEmpty(), secure.getString(KEY_KEY).orEmpty()))
    val config: StateFlow<CloudConfig> = _config.asStateFlow()

    private val library = MutableStateFlow(
        cache.getString(KEY_LIBRARY)?.let { runCatching { json.decodeFromString(CloudLibraryFile.serializer(), it).songs }.getOrNull() }.orEmpty()
    )

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
            // Come back by itself when the connection does.
            while (true) {
                delay(RETRY_OFFLINE_MS)
                if (_status.value is CloudStatus.Offline) refresh()
            }
        }
    }

    fun cloudSong(cloudId: String): CloudSong? = library.value.firstOrNull { it.id == cloudId }

    val authHeaders: Map<String, String> get() = mapOf(KEY_HEADER to _config.value.key)

    /** True for URLs on the user's server: the access key is only ever sent there. */
    fun isOurs(url: String): Boolean = _config.value.let { it.isSet && url.startsWith(it.serverUrl + "/") }

    fun streamUrl(cloudId: String) = "${_config.value.serverUrl}/stream/$cloudId"

    /** Saves the server address and key, then loads the library. */
    fun connect(serverUrl: String, key: String) {
        val config = CloudConfig(normalizeUrl(serverUrl), key.trim())
        secure.putString(KEY_URL, config.serverUrl)
        secure.putString(KEY_KEY, config.key)
        if (config.serverUrl != _config.value.serverUrl) setLibrary(emptyList())
        _config.value = config
        _status.value = if (config.isSet) CloudStatus.Loading else CloudStatus.NotConfigured
        scope.launch { refresh() }
    }

    /** Forgets the server. Downloaded songs stay on the device. */
    fun disconnect() {
        secure.putString(KEY_URL, null)
        secure.putString(KEY_KEY, null)
        _config.value = CloudConfig("", "")
        setLibrary(emptyList())
        _status.value = CloudStatus.NotConfigured
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
                        setLibrary(parsed.songs)
                        CloudStatus.Online(parsed.songs.size)
                    }
                }
                401, 403 -> CloudStatus.Error("The server rejected the access key. Check it in Settings.")
                404 -> CloudStatus.Error("No Vanity Player server found at that address.")
                else -> CloudStatus.Error("The server had a problem (HTTP ${response.status}). Try again later.")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CloudStatus.Offline("Can't reach the server. Showing downloaded songs only.")
        }
        // The settings may have changed while we were waiting.
        if (_config.value == config) _status.value = result
    }

    private fun setLibrary(songs: List<CloudSong>) {
        library.value = songs
        cache.putString(KEY_LIBRARY, if (songs.isEmpty()) null else json.encodeToString(CloudLibraryFile.serializer(), CloudLibraryFile(songs = songs)))
    }

    private val coverLock = Mutex()
    private val coverJobs = HashMap<String, CompletableDeferred<String?>>()
    private val coverSlots = Semaphore(4)

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
        albumId = albumIdFor(albumArtist.ifBlank { artist }, album),
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
    )

    companion object {
        const val KEY_HEADER = "X-App-Key"
        const val CLOUD_FOLDER = "Cloud"
        private const val KEY_URL = "server_url"
        private const val KEY_KEY = "access_key"
        private const val KEY_LIBRARY = "library"
        private const val RETRY_OFFLINE_MS = 60_000L

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
