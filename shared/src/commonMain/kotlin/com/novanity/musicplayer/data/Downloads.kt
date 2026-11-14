package com.novanity.musicplayer.data

import androidx.compose.runtime.Immutable
import com.novanity.musicplayer.data.db.DownloadEntity
import com.novanity.musicplayer.data.db.DownloadsDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

@Immutable
sealed interface DownloadState {
    data object Queued : DownloadState
    data class Downloading(val progress: Float) : DownloadState
    data object Done : DownloadState
    data class Failed(val message: String) : DownloadState
}

/** What the queue is doing, for a progress notification. */
@Immutable
data class DownloadProgress(val title: String, val remaining: Int, val progress: Float)

/**
 * Offline copies of cloud songs. The queue lives in the database, so it survives the app closing;
 * [processQueue] works through it (from a WorkManager job on Android, from the app on desktop).
 */
class DownloadsRepository(
    private val dao: () -> DownloadsDao,
    private val files: CloudFiles,
    private val scope: CoroutineScope,
) {
    /** Set by the app graph right after construction. */
    lateinit var cloud: CloudRepository

    /** Starts whatever runs [processQueue] on this platform. */
    var startRunner: () -> Unit = {}

    private val json = Json { ignoreUnknownKeys = true }

    val rows: StateFlow<List<DownloadEntity>> = flow { emitAll(dao().observeAll()) }
        .catch { emit(emptyList()) }
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val active = MutableStateFlow<Pair<String, Float>?>(null)
    private val cancelled = MutableStateFlow<Set<String>>(emptySet())

    /** Download state per cloud song id (songs that were never queued aren't in the map). */
    val states: StateFlow<Map<String, DownloadState>> = combine(rows, active) { rows, active ->
        rows.associate { row ->
            row.cloudId to when {
                row.status == DownloadEntity.STATUS_DONE -> DownloadState.Done
                row.status == DownloadEntity.STATUS_FAILED -> DownloadState.Failed(row.error ?: "Download failed")
                active?.first == row.cloudId -> DownloadState.Downloading(active.second)
                else -> DownloadState.Queued
            }
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** Bytes used by finished downloads. */
    val storageUsed: StateFlow<Long> = rows
        .map { list -> list.filter { it.status == DownloadEntity.STATUS_DONE }.sumOf { it.size } }
        .stateIn(scope, SharingStarted.Eagerly, 0L)

    val hasQueued: Boolean get() = rows.value.any { it.status == DownloadEntity.STATUS_QUEUED }

    /** Queues cloud songs for download (already downloaded or queued ones are skipped). */
    fun enqueue(songs: List<Song>) {
        scope.launch(Dispatchers.IO) {
            val known = dao().all().associateBy { it.cloudId }
            val now = nowMillis()
            val rows = songs.filter { it.source == SongSource.CLOUD }.distinctBy { it.cloudId }.mapIndexedNotNull { i, song ->
                val id = song.cloudId ?: return@mapIndexedNotNull null
                val existing = known[id]
                if (existing != null && existing.status != DownloadEntity.STATUS_FAILED) return@mapIndexedNotNull null
                val meta = cloud.cloudSong(id) ?: return@mapIndexedNotNull null
                DownloadEntity(id, "", meta.size, now + i, DownloadEntity.STATUS_QUEUED, json.encodeToString(CloudSong.serializer(), meta))
            }
            if (rows.isEmpty()) return@launch
            cancelled.update { it - rows.map { r -> r.cloudId }.toSet() }
            dao().upsertAll(rows)
            startRunner()
        }
    }

    /** Stops a queued or running download. */
    fun cancel(cloudId: String) {
        scope.launch(Dispatchers.IO) {
            val row = dao().get(cloudId) ?: return@launch
            if (row.status == DownloadEntity.STATUS_DONE) return@launch
            cancelled.update { it + cloudId }
            dao().delete(cloudId)
        }
    }

    /** Stops everything that hasn't finished. Finished downloads are kept. */
    fun cancelAll() {
        scope.launch(Dispatchers.IO) {
            cancelled.update { it + dao().all().filter { r -> r.status != DownloadEntity.STATUS_DONE }.map { r -> r.cloudId } }
            dao().deleteUnfinished()
        }
    }

    /** Removes a downloaded song from the device (it stays in the cloud). */
    fun delete(cloudId: String) {
        scope.launch(Dispatchers.IO) {
            val row = dao().get(cloudId) ?: return@launch
            if (row.status != DownloadEntity.STATUS_DONE) cancelled.update { it + cloudId }
            dao().delete(cloudId)
            if (row.filePath.isNotEmpty()) files.delete(row.filePath)
        }
    }

    fun deleteAll() {
        scope.launch(Dispatchers.IO) {
            val all = dao().all()
            cancelled.update { it + all.map { r -> r.cloudId } }
            dao().deleteAll()
            all.forEach { if (it.filePath.isNotEmpty()) files.delete(it.filePath) }
        }
    }

    enum class Outcome {
        /** Nothing left in the queue. */
        FINISHED,

        /** The network failed; what's left stays queued for another try. */
        RETRY_LATER,
    }

    private val runMutex = Mutex()

    /**
     * Downloads everything that's queued, one song at a time. Safe to call again while it runs
     * (the second call waits, then finds the queue empty).
     */
    suspend fun processQueue(onProgress: (DownloadProgress) -> Unit = {}): Outcome = runMutex.withLock {
        withContext(Dispatchers.IO) {
            while (true) {
                val row = dao().nextQueued() ?: break
                val id = row.cloudId
                val meta = runCatching { json.decodeFromString(CloudSong.serializer(), row.metaJson) }.getOrNull()
                val config = cloud.config.value
                if (meta == null || !config.isSet) {
                    dao().upsert(row.copy(status = DownloadEntity.STATUS_FAILED, error = "The cloud library isn't connected."))
                    continue
                }
                val remaining = { rows.value.count { it.status == DownloadEntity.STATUS_QUEUED }.coerceAtLeast(1) }
                active.value = id to 0f
                onProgress(DownloadProgress(meta.title, remaining(), 0f))
                var lastShown = 0f
                try {
                    val ext = meta.ext.ifBlank { "audio" }.filter { it.isLetterOrDigit() }
                    val path = files.download(
                        url = cloud.streamUrl(id),
                        headers = cloud.authHeaders,
                        relativePath = "downloads/$id.$ext",
                        expectedSize = meta.size,
                        onProgress = { p ->
                            // Throttled: every 2% is plenty for a progress ring.
                            if (p - lastShown >= 0.02f || p >= 1f) {
                                lastShown = p
                                active.value = id to p
                                onProgress(DownloadProgress(meta.title, remaining(), p))
                            }
                        },
                        isCancelled = { id in cancelled.value },
                    )
                    meta.coverId?.let { runCatching { cloud.coverFile(it) } }
                    if (id in cancelled.value || dao().get(id) == null) {
                        files.delete(path) // cancelled just as it finished
                    } else {
                        dao().upsert(row.copy(filePath = path, size = files.size(path), downloadedAt = nowMillis(), status = DownloadEntity.STATUS_DONE, error = null))
                    }
                } catch (e: CancellationException) {
                    if (id !in cancelled.value) throw e // the whole job was stopped: the song stays queued
                } catch (e: CloudHttpException) {
                    val message = when (e.status) {
                        401, 403 -> "The server rejected the access key."
                        404 -> "This song is no longer on the server."
                        else -> "The server had a problem (HTTP ${e.status})."
                    }
                    if (dao().get(id) != null) dao().upsert(row.copy(status = DownloadEntity.STATUS_FAILED, error = message))
                } catch (e: Exception) {
                    return@withContext Outcome.RETRY_LATER
                } finally {
                    active.value = null
                    cancelled.update { it - id }
                }
            }
            Outcome.FINISHED
        }
    }
}
