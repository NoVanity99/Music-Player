package com.novanity.musicplayer.data

import com.novanity.musicplayer.playback.PlayerController
import com.novanity.musicplayer.playback.PlayerUiState
import com.novanity.musicplayer.playback.QueueEntry
import com.novanity.musicplayer.playback.RepeatMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs

@Serializable
data class Friend(val username: String = "", val admin: Boolean = false, val avatar: String? = null, val look: ProfileLook = ProfileLook(), val online: Boolean = false, val songId: String? = null, val playing: Boolean = false)

/** A friend asking to listen along with you. */
@Serializable
data class ListenRequest(val id: Long = 0, val from: String = "", /** "ask": they want to join you. "invite": they're inviting you to join them. */ val kind: String = "ask") {
    val isInvite: Boolean get() = kind == "invite"
}

/**
 * The shared listening session you're in. As "host" it's yours and [members] are listening along;
 * as "guest" you follow [host]'s playback ([songId] at [positionMs], reported [ageMs] ago).
 */
@Serializable
data class ListenSession(
    val role: String = "",
    val host: String = "",
    val members: List<String> = emptyList(),
    val songId: String? = null,
    val nextSongId: String? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val ageMs: Long = 0,
) {
    val isGuest: Boolean get() = role == "guest"
    val isHost: Boolean get() = role == "host"
}

@Serializable
data class QueuedAdd(val songId: String = "", val from: String = "")

/** Music shared in a message: a song, album, EP, single or playlist, as the cloud songs it holds. */
@Serializable
data class SharedMusic(
    /** "song", "album", "ep", "single" or "playlist". */
    val kind: String = "song",
    val title: String = "",
    val subtitle: String = "",
    val songIds: List<String> = emptyList(),
) {
    val label: String get() = when (kind) {
        "album" -> "Album"
        "ep" -> "EP"
        "single" -> "Single"
        "playlist" -> "Playlist"
        else -> "Song"
    }
}

@Serializable
data class ChatMessage(val id: Long = 0, val mine: Boolean = false, val text: String = "", val share: SharedMusic? = null, val at: Long = 0)

/** A conversation as the server returns it; [seenId] is the newest of your messages the friend has read. */
@Serializable
data class ChatPage(val messages: List<ChatMessage> = emptyList(), val seenId: Long = 0)

/** Unread messages from one friend. */
@Serializable
data class Unread(val from: String = "", val count: Int = 0, val lastId: Long = 0)

@Serializable
data class SocialState(
    val friends: List<Friend> = emptyList(),
    /** People who asked to be your friend. */
    val requests: List<String> = emptyList(),
    /** People you asked. */
    val sent: List<String> = emptyList(),
    val listenRequests: List<ListenRequest> = emptyList(),
    val session: ListenSession? = null,
    val adds: List<QueuedAdd> = emptyList(),
    val unread: List<Unread> = emptyList(),
) {
    fun unreadFrom(username: String): Int = unread.firstOrNull { it.from.equals(username, ignoreCase = true) }?.count ?: 0
    val unreadTotal: Int get() = unread.sumOf { it.count }
}

@Serializable
private data class Pulse(val songId: String?, val nextSongId: String?, val positionMs: Long, val playing: Boolean, val share: Boolean)

@Serializable
private data class NameBody(val username: String, val accept: Boolean? = null)

@Serializable
private data class ListenAnswer(val id: Long, val accept: Boolean)

@Serializable
private data class AddBody(val songId: String)

@Serializable
private data class ServerMessage(val message: String = "")

@Serializable
private data class SendBody(val to: String, val text: String, val share: SharedMusic?)

/**
 * Friends, what they're playing, and listening together. The app reports what it's playing every
 * few seconds and gets everything back in the same request. In a session one person hosts: guests'
 * players follow the host's song and position, and a guest can only add songs to the host's queue.
 */
class SocialRepository(
    private val account: AccountRepository,
    private val cloud: CloudRepository,
    private val music: MusicRepository,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }

    private val _state = MutableStateFlow(SocialState())
    val state: StateFlow<SocialState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** The real player (set by [wrap]); the session moves it directly. */
    private var player: PlayerController? = null

    val isGuest: Boolean get() = _state.value.session?.isGuest == true

    /** Gives back a player that a guest can't steer; everything else passes straight through. */
    fun wrap(inner: PlayerController): PlayerController {
        player = inner
        return SessionPlayer(inner, this)
    }

    private val lock = Mutex()

    init {
        scope.launch {
            while (true) {
                delay(if (_state.value.session != null) IN_SESSION_MS else IDLE_MS)
                if (account.state.value is AccountState.SignedIn) runCatching { pulse() }
            }
        }
    }

    /** Reports what's playing and applies what comes back. Safe to call any time. */
    suspend fun pulse() = lock.withLock {
        val p = player
        val now = p?.state?.value
        val body = Pulse(
            songId = now?.current?.songId?.let(cloud::cloudIdOf),
            nextSongId = p?.upNext?.value?.firstOrNull()?.songId?.let(cloud::cloudIdOf),
            positionMs = p?.currentPositionMs() ?: 0,
            playing = now?.isPlaying == true,
            share = settings.shareListening.value,
        )
        val started = nowMillis()
        val response = try {
            withContext(Dispatchers.IO) { account.call("POST", "/me/pulse", json.encodeToString(Pulse.serializer(), body)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (response == null || response.status != 200) return@withLock
        val fresh = runCatching { json.decodeFromString(SocialState.serializer(), response.body) }.getOrNull() ?: return@withLock
        apply(fresh, nowMillis() - started)
    }

    // ---- Following a host ----

    private class Snapshot(val songs: List<Song>, val positionMs: Long)
    private var snapshot: Snapshot? = null
    private var leaving = false
    private var seekPending = false

    private fun apply(fresh: SocialState, roundTripMs: Long) {
        val before = _state.value.session
        val session = fresh.session
        val p = player
        // Songs guests added to our queue.
        if (p != null && fresh.adds.isNotEmpty()) {
            val songs = fresh.adds.mapNotNull { a -> songFor(a.songId) }
            if (songs.isNotEmpty()) {
                p.addToQueue(songs)
                _messages.tryEmit(if (songs.size == 1) "${fresh.adds.first().from} added “${songs.first().title}” to the queue" else "${songs.size} songs were added to the queue")
            }
        }
        if (session?.isHost == true) {
            val joined = session.members - (before?.takeIf { it.isHost }?.members.orEmpty().toSet())
            joined.forEach { _messages.tryEmit("$it is listening with you") }
        }
        if (session?.isGuest == true && !leaving) {
            if (before?.isGuest != true) _messages.tryEmit("You're listening with ${session.host}")
            if (p != null) follow(p, session, roundTripMs)
        } else if (before?.isGuest == true || snapshot != null) {
            if (!leaving && before?.isGuest == true) _messages.tryEmit("The listening session ended")
            restore()
        }
        if (session?.isGuest != true) leaving = false
        // A new message from a friend (unless their conversation is open on screen).
        for (u in fresh.unread) {
            val known = lastNotified[u.from.lowercase()] ?: 0
            if (u.lastId > known) {
                lastNotified[u.from.lowercase()] = u.lastId
                if (!u.from.equals(openChat, ignoreCase = true)) _messages.tryEmit("New message from ${u.from}")
            }
        }
        _state.value = fresh.copy(adds = emptyList(), session = if (leaving) null else session)
    }

    private fun songFor(cloudId: String?): Song? = cloudId?.let { music.songsById[CloudRepository.songIdFor(it)] }

    /** Moves our player to where the host is. */
    private fun follow(p: PlayerController, s: ListenSession, roundTripMs: Long) {
        if (snapshot == null) {
            // Remember what we were playing, to come back to it when we leave.
            val st = p.state.value
            val ids = listOfNotNull(st.current?.songId) + p.upNext.value.mapNotNull { it.songId }
            snapshot = Snapshot(ids.mapNotNull { music.songsById[it] }, if (st.current != null) p.currentPositionMs() else 0)
        }
        val target = songFor(s.songId)
        val next = songFor(s.nextSongId)
        val st = p.state.value
        if (target == null) {
            if (st.isPlaying) p.pause()
            return
        }
        val current = st.current?.songId
        if (current != target.id) {
            // We reached the host's next song a moment before they reported it: stay put.
            if (next != null && current == next.id && p.currentPositionMs() < 8_000) return
            p.setQueue(listOfNotNull(target, next?.takeIf { it.id != target.id }), 0, false)
            seekPending = true
            return
        }
        val expected = s.positionMs + if (s.playing) s.ageMs + roundTripMs / 2 else 0
        if (seekPending || abs(p.currentPositionMs() - expected) > DRIFT_MS) {
            p.seekTo(expected)
            seekPending = false
        }
        if (s.playing != st.isPlaying) {
            if (s.playing) p.togglePlayPause() else p.pause()
        }
        // Keep the host's next song lined up so the change-over has no pause.
        val queued = p.upNext.value.firstOrNull()?.songId
        if (next != null && next.id != target.id && queued != next.id) {
            p.clearUpNext()
            p.addToQueue(listOf(next))
        }
    }

    /** Back to what we were listening to before the session, paused where we left it. */
    private fun restore() {
        val snap = snapshot ?: return
        snapshot = null
        seekPending = false
        val p = player ?: return
        if (snap.songs.isEmpty()) {
            p.pause()
            return
        }
        p.setQueue(snap.songs, 0, false)
        scope.launch {
            delay(600)
            p.seekTo(snap.positionMs)
            p.pause()
        }
    }

    // ---- Messages ----

    /** The friend whose conversation is on screen, so their new messages don't also pop up a notice. */
    @kotlin.concurrent.Volatile var openChat: String? = null
    private val lastNotified = HashMap<String, Long>()

    /** The conversation with [friend]: the latest messages, or only those after [afterId]. Null if it couldn't load. */
    suspend fun conversation(friend: String, afterId: Long = 0): ChatPage? {
        val path = "/me/messages?with=" + ArtistBios.encode(friend.trim()) + if (afterId > 0) "&after=$afterId" else ""
        val response = try {
            withContext(Dispatchers.IO) { account.call("GET", path, null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null
        if (response.status != 200) return null
        val page = runCatching { json.decodeFromString(ChatPage.serializer(), response.body) }.getOrNull() ?: return null
        // Reading them clears the unread count straight away, not on the next pulse.
        _state.value = _state.value.copy(unread = _state.value.unread.filterNot { it.from.equals(friend, ignoreCase = true) })
        return page
    }

    /** Sends [friend] a message, with music attached if [share] is given. Returns a problem to show, or null when sent. */
    suspend fun send(friend: String, text: String, share: SharedMusic? = null): String? {
        val body = json.encodeToString(SendBody.serializer(), SendBody(friend.trim(), text.trim().take(1000), share?.copy(songIds = share.songIds.take(500))))
        return post("/me/messages", body).second
    }

    /** The cloud ids of songs, for sharing (songs that only exist on this device can't be shared). */
    fun cloudIds(songs: List<Song>): List<String> = songs.mapNotNull { cloud.cloudIdOf(it.id) }.distinct()

    /** The songs in a shared item that are in the library here. */
    fun songsOf(share: SharedMusic): List<Song> = share.songIds.mapNotNull(::songFor)

    // ---- Actions ----

    private suspend fun post(path: String, body: String): Pair<Boolean, String?> {
        val response = try {
            withContext(Dispatchers.IO) { account.call("POST", path, body) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return false to "Can't reach the server. Check your connection."
        if (response.status in 200..299) return true to null
        return false to (runCatching { json.decodeFromString(ServerMessage.serializer(), response.body).message }.getOrNull()?.takeIf { it.isNotBlank() } ?: "That didn't work. Try again.")
    }

    private fun act(done: String?, path: String, body: String) {
        scope.launch {
            val (ok, problem) = post(path, body)
            (if (ok) done else problem)?.let { _messages.tryEmit(it) }
            runCatching { pulse() }
        }
    }

    private fun name(username: String, accept: Boolean? = null) = json.encodeToString(NameBody.serializer(), NameBody(username.trim(), accept))

    fun addFriend(username: String) = act("Friend request sent to ${username.trim()}", "/me/friends/request", name(username))
    fun answerFriend(username: String, accept: Boolean) = act(if (accept) "You and $username are now friends" else null, "/me/friends/respond", name(username, accept))
    fun removeFriend(username: String) = act("Removed $username", "/me/friends/remove", name(username))
    fun invite(username: String) = act("Invited $username to listen with you", "/me/listen/invite", name(username))
    fun askToListen(username: String) = act("Asked $username to listen along", "/me/listen/request", name(username))

    fun answerListen(request: ListenRequest, accept: Boolean) {
        _state.value = _state.value.copy(listenRequests = _state.value.listenRequests - request)
        act(null, "/me/listen/respond", json.encodeToString(ListenAnswer.serializer(), ListenAnswer(request.id, accept)))
    }

    /** Leaves the session and goes back to what was playing before. */
    fun leave() {
        if (!isGuest) return
        leaving = true
        _state.value = _state.value.copy(session = null)
        restore()
        act("You left the session", "/me/listen/leave", "{}")
    }

    /** A guest's "add to queue": sent to the host, whose app adds it. */
    fun guestAdd(songs: List<Song>) {
        val ids = songs.mapNotNull { cloud.cloudIdOf(it.id) }.take(25)
        if (ids.isEmpty()) return
        scope.launch {
            var sent = 0
            for (id in ids) if (post("/me/listen/add", json.encodeToString(AddBody.serializer(), AddBody(id))).first) sent++
            _messages.tryEmit(
                when {
                    sent == 0 -> "Couldn't add that to the queue"
                    sent == 1 -> "Added to ${_state.value.session?.host ?: "the host"}'s queue"
                    else -> "Added $sent songs to the queue"
                }
            )
        }
    }

    internal fun blocked() {
        _messages.tryEmit("Only ${_state.value.session?.host ?: "the host"} controls playback. You can add songs to the queue, or leave the session.")
    }

    private companion object {
        const val IDLE_MS = 6_000L
        const val IN_SESSION_MS = 2_500L
        const val DRIFT_MS = 2_500L
    }
}

/**
 * The player the UI talks to. Normally it just passes everything on. While you're a guest in
 * someone's session, the controls do nothing (the host decides what plays) and adding to the
 * queue goes to the host instead.
 */
private class SessionPlayer(private val inner: PlayerController, private val social: SocialRepository) : PlayerController by inner {
    private inline fun own(action: () -> Unit) {
        if (social.isGuest) social.blocked() else action()
    }

    override fun setQueue(songs: List<Song>, startIndex: Int, shuffle: Boolean) {
        // Picking something to play while a guest means "add it".
        if (social.isGuest) social.guestAdd(songs.drop(startIndex.coerceIn(0, (songs.size - 1).coerceAtLeast(0))).take(1)) else inner.setQueue(songs, startIndex, shuffle)
    }
    override fun playNext(songs: List<Song>) { if (social.isGuest) social.guestAdd(songs) else inner.playNext(songs) }
    override fun addToQueue(songs: List<Song>) { if (social.isGuest) social.guestAdd(songs) else inner.addToQueue(songs) }
    override fun togglePlayPause() = own { inner.togglePlayPause() }
    override fun pause() = own { inner.pause() }
    override fun previous() = own { inner.previous() }
    override fun next() = own { inner.next() }
    override fun previousTrack() = own { inner.previousTrack() }
    override fun nextTrack() = own { inner.nextTrack() }
    override fun seekTo(positionMs: Long) = own { inner.seekTo(positionMs) }
    override fun setShuffle(enabled: Boolean) = own { inner.setShuffle(enabled) }
    override fun setRepeatMode(mode: RepeatMode) = own { inner.setRepeatMode(mode) }
    override fun playQueueIndex(index: Int) = own { inner.playQueueIndex(index) }
    override fun removeQueueIndex(index: Int) = own { inner.removeQueueIndex(index) }
    override fun moveQueueItem(from: Int, to: Int) = own { inner.moveQueueItem(from, to) }
    override fun clearUpNext() = own { inner.clearUpNext() }
    override val state: StateFlow<PlayerUiState> get() = inner.state
    override val upNext: StateFlow<List<QueueEntry>> get() = inner.upNext
}
