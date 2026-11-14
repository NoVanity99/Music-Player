package com.novanity.musicplayer.data

import androidx.compose.runtime.Immutable
import com.novanity.musicplayer.data.db.FavoriteEntity
import com.novanity.musicplayer.data.db.FavoritesDao
import com.novanity.musicplayer.data.db.HistoryDao
import com.novanity.musicplayer.data.db.PlayHistoryEntity
import com.novanity.musicplayer.data.db.PlaylistDao
import com.novanity.musicplayer.data.db.PlaylistEntity
import com.novanity.musicplayer.platform.KeyValueStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Immutable
@Serializable
data class AccountUser(val id: String, val username: String)

sealed interface AccountState {
    data object SignedOut : AccountState
    data class SignedIn(val user: AccountUser) : AccountState
}

@Serializable
private data class Credentials(val username: String, val password: String)

@Serializable
private data class Session(val token: String, val user: AccountUser)

@Serializable
private data class ApiError(val error: String = "", val message: String = "")

/** A like, unlike or play waiting to be sent to the server. */
@Serializable
private data class Op(val type: String, val songId: String, val at: Long)

@Serializable
private data class OpsBody(val ops: List<Op>)

@Serializable
private data class Stamp(val songId: String, val at: Long)

@Serializable
private data class RemotePlaylist(val name: String = "", val createdAt: Long = 0, val songs: List<String> = emptyList())

@Serializable
private data class PlaylistsBody(val playlists: List<RemotePlaylist>)

@Serializable
private data class SyncResponse(
    val likes: List<Stamp> = emptyList(),
    val plays: List<Stamp> = emptyList(),
    val playlists: List<RemotePlaylist> = emptyList(),
)

/**
 * The signed-in account. Liked songs, play history (which the stats and mixes are built from) and
 * playlists belong to the account: they're kept on the server and mirrored into the local database,
 * so each account sees only its own, on any device. The song library itself is shared by everyone.
 *
 * Changes are applied locally first and sent in the background; what can't be sent (offline) is
 * queued and sent later. The server copy replaces the local one only after everything queued here
 * has been sent.
 */
@OptIn(FlowPreview::class)
class AccountRepository(
    private val secure: KeyValueStore,
    private val queueStore: KeyValueStore,
    private val send: suspend (method: String, url: String, headers: Map<String, String>, body: String?) -> HttpText,
    private val cloud: CloudRepository,
    private val favorites: FavoritesRepository,
    private val history: HistoryRepository,
    private val playlistRepo: PlaylistRepository,
    private val favoritesDao: () -> FavoritesDao,
    private val historyDao: () -> HistoryDao,
    private val playlistDao: () -> PlaylistDao,
    private val scope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private var token: String? = secure.getString(KEY_TOKEN)

    private val _state = MutableStateFlow<AccountState>(
        run {
            val id = secure.getString(KEY_USER_ID)
            val name = secure.getString(KEY_USERNAME)
            if (token != null && id != null && name != null) AccountState.SignedIn(AccountUser(id, name)) else AccountState.SignedOut
        }
    )
    val state: StateFlow<AccountState> = _state.asStateFlow()

    private var pending: List<Op> = queueStore.getString(KEY_PENDING)
        ?.let { runCatching { json.decodeFromString(OpsBody.serializer(), it).ops }.getOrNull() }.orEmpty()
    private val pendingLock = Mutex()
    private val syncLock = Mutex()
    private var flushJob: Job? = null

    /** The playlists as last sent to or received from the server (to skip needless uploads). */
    private var syncedPlaylists: String? = null

    init {
        favorites.onLike = { songId, liked, at -> enqueue(if (liked) "like" else "unlike", songId, at) }
        history.onPlay = { songId, at -> enqueue("play", songId, at) }
        scope.launch {
            if (_state.value is AccountState.SignedIn) sync()
        }
        // Playlist edits: upload the whole set a moment after the last change.
        scope.launch {
            combine(playlistRepo.playlists, playlistRepo.coverEntries) { _, _ -> }
                .debounce(2_500)
                .collect {
                    if (_state.value !is AccountState.SignedIn) return@collect
                    if (cloud.status.value is CloudStatus.Online) {
                        runCatching { pushPlaylistsIfChanged() }
                    } else if (syncedPlaylists != null) {
                        // Edited offline: remember to upload before the server copy is taken again.
                        queueStore.putBoolean(KEY_PLAYLISTS_DIRTY, true)
                    }
                }
        }
    }

    // ---- Signing in and out ----

    /** Creates an account. Returns null on success, or a message to show. */
    suspend fun signUp(username: String, password: String): String? = authenticate("/auth/signup", username, password, isNew = true)

    /** Signs in. Returns null on success, or a message to show. */
    suspend fun signIn(username: String, password: String): String? = authenticate("/auth/login", username, password, isNew = false)

    private suspend fun authenticate(path: String, username: String, password: String, isNew: Boolean): String? {
        val response = try {
            send("POST", cloud.config.value.serverUrl + path, cloud.authHeaders, json.encodeToString(Credentials.serializer(), Credentials(username.trim(), password)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return "Can't reach the server. Check your internet connection and try again."
        }
        if (response.status !in 200..299) {
            val error = runCatching { json.decodeFromString(ApiError.serializer(), response.body) }.getOrNull()
            return error?.message?.takeIf { it.isNotBlank() } ?: "The server had a problem (HTTP ${response.status}). Try again."
        }
        val session = runCatching { json.decodeFromString(Session.serializer(), response.body) }.getOrNull()
            ?: return "The server sent an unexpected answer. Try again."
        withContext(Dispatchers.IO) { adopt(session, isNew) }
        return null
    }

    private suspend fun adopt(session: Session, isNew: Boolean) {
        val previous = queueStore.getString(KEY_LAST_USER)
        if (isNew && previous == null) {
            // The first account on this device keeps what was already here.
            uploadLocalData()
        } else if (previous != session.user.id) {
            clearLocal()
        }
        token = session.token
        secure.putString(KEY_TOKEN, session.token)
        secure.putString(KEY_USER_ID, session.user.id)
        secure.putString(KEY_USERNAME, session.user.username)
        queueStore.putString(KEY_LAST_USER, session.user.id)
        _state.value = AccountState.SignedIn(session.user)
        scope.launch { sync() }
    }

    /** Signs out on this device: sends what's still queued if it can, then removes the account's data here. */
    suspend fun signOut() = withContext(Dispatchers.IO) {
        runCatching { flush() }
        runCatching { pushPlaylistsIfChanged() }
        runCatching { authed("POST", "/auth/logout", null) }
        forget()
    }

    /** Deletes the account and everything stored for it. Returns null on success, or a message. */
    suspend fun deleteAccount(): String? = withContext(Dispatchers.IO) {
        val response = try {
            authed("DELETE", "/me", null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext "Can't reach the server. Check your internet connection and try again."
        }
        if (response != null && response.status !in 200..299) return@withContext "The server had a problem (HTTP ${response.status}). Try again."
        forget()
        null
    }

    private suspend fun forget() {
        token = null
        secure.putString(KEY_TOKEN, null)
        secure.putString(KEY_USER_ID, null)
        secure.putString(KEY_USERNAME, null)
        clearLocal()
        _state.value = AccountState.SignedOut
    }

    private suspend fun clearLocal() {
        pendingLock.withLock { setPending(emptyList()) }
        syncedPlaylists = null
        queueStore.putBoolean(KEY_PLAYLISTS_DIRTY, false)
        favoritesDao().deleteAll()
        historyDao().deleteAll()
        playlistDao().replaceAll(emptyList())
    }

    // ---- Sending changes ----

    private fun enqueue(type: String, songId: Long, at: Long) {
        if (_state.value !is AccountState.SignedIn) return
        val cloudId = cloud.cloudIdOf(songId) ?: return
        scope.launch {
            pendingLock.withLock { setPending(pending + Op(type, cloudId, at)) }
            scheduleFlush()
        }
    }

    private fun setPending(ops: List<Op>) {
        pending = ops
        queueStore.putString(KEY_PENDING, if (ops.isEmpty()) null else json.encodeToString(OpsBody.serializer(), OpsBody(ops)))
    }

    private fun scheduleFlush() {
        flushJob?.cancel()
        flushJob = scope.launch {
            delay(2_000)
            runCatching { flush() }
        }
    }

    /** Sends queued likes and plays. Returns true when nothing is left to send. */
    private suspend fun flush(): Boolean {
        while (true) {
            val batch = pendingLock.withLock { pending.take(BATCH) }
            if (batch.isEmpty()) return true
            val response = authed("POST", "/me/ops", json.encodeToString(OpsBody.serializer(), OpsBody(batch))) ?: return false
            if (response.status !in 200..299) return false
            pendingLock.withLock { setPending(pending.drop(batch.size)) }
        }
    }

    /** A request with the session token. Null when signed out; signs out locally if the server says so. */
    private suspend fun authed(method: String, path: String, body: String?): HttpText? {
        val t = token ?: return null
        val response = send(method, cloud.config.value.serverUrl + path, cloud.authHeaders + ("Authorization" to "Bearer $t"), body)
        if (response.status == 401 && "signed_out" in response.body) {
            // The session ended on the server (signed out elsewhere, or the account was deleted).
            withContext(Dispatchers.IO) { forget() }
            return null
        }
        return response
    }

    // ---- Syncing ----

    /**
     * Sends what's queued, then replaces the local likes, history and playlists with the
     * account's. Does nothing destructive while offline. Never throws.
     */
    suspend fun sync() {
        if (_state.value !is AccountState.SignedIn) return
        try {
            withContext(Dispatchers.IO) {
                syncLock.withLock {
                    if (!flush()) return@withLock
                    if (queueStore.getBoolean(KEY_PLAYLISTS_DIRTY, false) && !pushPlaylists(force = true)) return@withLock
                    val response = authed("GET", "/me/sync", null) ?: return@withLock
                    if (response.status != 200) return@withLock
                    val remote = runCatching { json.decodeFromString(SyncResponse.serializer(), response.body) }.getOrNull() ?: return@withLock
                    // Something was liked or played while we were waiting: keep it, sync next time.
                    if (pendingLock.withLock { pending.isNotEmpty() }) return@withLock
                    favoritesDao().replaceAll(remote.likes.map { FavoriteEntity(CloudRepository.songIdFor(it.songId), it.at) })
                    historyDao().replaceAll(remote.plays.map { PlayHistoryEntity(songId = CloudRepository.songIdFor(it.songId), playedAt = it.at) })
                    playlistDao().replaceAll(
                        remote.playlists.map { p ->
                            PlaylistEntity(name = p.name, createdAt = p.createdAt) to p.songs.map(CloudRepository::songIdFor)
                        }
                    )
                    syncedPlaylists = json.encodeToString(PlaylistsBody.serializer(), PlaylistsBody(remote.playlists))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Offline or a server hiccup: the local copy stays as it is and we try again later.
        }
    }

    /** The local playlists in the server's format, or null while the song list isn't fully known. */
    private suspend fun localPlaylists(): List<RemotePlaylist>? {
        // Without the full library some songs can't be named, and uploading would drop them.
        if (cloud.status.value !is CloudStatus.Online) return null
        val entries = playlistDao().allEntries().groupBy { it.playlistId }
        return playlistDao().allPlaylists().map { p ->
            RemotePlaylist(p.name, p.createdAt, entries[p.id].orEmpty().mapNotNull { cloud.cloudIdOf(it.songId) })
        }
    }

    private suspend fun pushPlaylistsIfChanged() = withContext(Dispatchers.IO) { syncLock.withLock { pushPlaylists(force = false) } }

    /** Uploads the playlists if they differ from what the server has. Returns false if it couldn't. */
    private suspend fun pushPlaylists(force: Boolean): Boolean {
        val local = localPlaylists() ?: return false
        val body = json.encodeToString(PlaylistsBody.serializer(), PlaylistsBody(local))
        if (!force && (syncedPlaylists == null || body == syncedPlaylists)) return true
        queueStore.putBoolean(KEY_PLAYLISTS_DIRTY, true)
        val response = try {
            authed("PUT", "/me/playlists", body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (response == null || response.status !in 200..299) return false
        syncedPlaylists = body
        queueStore.putBoolean(KEY_PLAYLISTS_DIRTY, false)
        return true
    }

    /** First account on a device: queue the likes and plays already here, and upload the playlists. */
    private suspend fun uploadLocalData() {
        // The song list is needed to name the songs; give it a moment if the app just started.
        withTimeoutOrNull(15_000) { cloud.status.first { it is CloudStatus.Online } }
        val ops = favoritesDao().all().mapNotNull { f -> cloud.cloudIdOf(f.songId)?.let { Op("like", it, f.addedAt) } } +
            historyDao().all().mapNotNull { e -> cloud.cloudIdOf(e.songId)?.let { Op("play", it, e.playedAt) } }
        pendingLock.withLock { setPending(ops) }
        queueStore.putBoolean(KEY_PLAYLISTS_DIRTY, true)
    }

    private companion object {
        const val KEY_TOKEN = "session_token"
        const val KEY_USER_ID = "user_id"
        const val KEY_USERNAME = "username"
        const val KEY_PENDING = "pending_ops"
        const val KEY_LAST_USER = "last_user_id"
        const val KEY_PLAYLISTS_DIRTY = "playlists_dirty"
        const val BATCH = 500
    }
}
