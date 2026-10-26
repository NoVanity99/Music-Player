package com.novanity.musicplayer.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novanity.musicplayer.data.Album
import com.novanity.musicplayer.data.Artist
import com.novanity.musicplayer.data.Folder
import com.novanity.musicplayer.data.HistoryRepository
import com.novanity.musicplayer.data.MusicRepository
import com.novanity.musicplayer.data.PlaylistRepository
import com.novanity.musicplayer.data.SettingsRepository
import com.novanity.musicplayer.data.Song
import com.novanity.musicplayer.data.SortOrder
import com.novanity.musicplayer.data.ThemeMode
import com.novanity.musicplayer.data.db.PlaylistEntity
import com.novanity.musicplayer.data.db.PlaylistSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class GroupedLibrary(
    val songs: List<Song>,
    val albums: List<Album>,
    val artists: List<Artist>,
    val folders: List<Folder>,
) {
    val albumsById: Map<Long, Album> = albums.associateBy { it.id }
    val artistsByName: Map<String, Artist> = artists.associateBy { it.name }
    val foldersByPath: Map<String, Folder> = folders.associateBy { it.path }
}

@Immutable
data class LibraryUi(
    val loading: Boolean = true,
    val isEmpty: Boolean = false,
    val songs: List<Song> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val folders: List<Folder> = emptyList(),
)

@Immutable
data class PlaylistTrack(val entryId: Long, val song: Song)

sealed interface Destination {
    data class AlbumDetail(val albumId: Long) : Destination
    data class ArtistDetail(val name: String) : Destination
    data class FolderDetail(val path: String) : Destination
    data class PlaylistDetail(val playlistId: Long) : Destination
    data object RecentlyPlayed : Destination
}

class LibraryViewModel(
    private val music: MusicRepository,
    private val playlistRepo: PlaylistRepository,
    private val history: HistoryRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    val query = MutableStateFlow("")
    val sortOrder: StateFlow<SortOrder> = settings.sortOrder
    val themeMode: StateFlow<ThemeMode> = settings.themeMode
    val dynamicColor: StateFlow<Boolean> = settings.dynamicColor

    /** Simple in-memory navigation stack (survives rotation via the ViewModel). */
    val backStack = mutableStateListOf<Destination>()

    /** Songs waiting for the "add to playlist" picker, if open. */
    val pendingPlaylistAdd = mutableStateOf<List<Song>?>(null)

    val grouped: StateFlow<GroupedLibrary?> = music.songs
        .map { songs -> songs?.let(::group) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val library: StateFlow<LibraryUi> = combine(grouped, query, sortOrder) { lib, q, sort ->
        if (lib == null) return@combine LibraryUi(loading = true)
        filterAndSort(lib, q.trim(), sort)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUi())

    val playlists: StateFlow<List<PlaylistSummary>> = combine(playlistRepo.playlists, query) { all, q ->
        val t = q.trim()
        if (t.isEmpty()) all else all.filter { it.name.contains(t, ignoreCase = true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val allPlaylists: StateFlow<List<PlaylistSummary>> = playlistRepo.playlists
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recentlyPlayed: StateFlow<List<Song>> = combine(history.recentSongIds(), music.songs) { ids, _ ->
        val byId = music.songsById
        ids.mapNotNull(byId::get)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        music.startObserving()
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { music.refresh() }
    }

    fun setQuery(q: String) {
        query.value = q
    }

    fun setSortOrder(order: SortOrder) = settings.setSortOrder(order)
    fun setThemeMode(mode: ThemeMode) = settings.setThemeMode(mode)
    fun setDynamicColor(enabled: Boolean) = settings.setDynamicColor(enabled)

    fun open(destination: Destination) {
        backStack.add(destination)
    }

    fun pop() {
        backStack.removeLastOrNull()
    }

    // ---- Playlists ----

    fun playlist(id: Long): Flow<PlaylistEntity?> = playlistRepo.playlist(id)

    fun playlistTracks(id: Long): Flow<List<PlaylistTrack>> =
        combine(playlistRepo.entries(id), music.songs) { entries, _ ->
            val byId = music.songsById
            // Songs deleted from the device simply drop out of the playlist view.
            entries.mapNotNull { e -> byId[e.songId]?.let { PlaylistTrack(e.entryId, it) } }
        }

    fun createPlaylist(name: String, thenAdd: List<Song>? = null) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val id = playlistRepo.create(name)
            if (!thenAdd.isNullOrEmpty()) playlistRepo.addSongs(id, thenAdd.map { it.id })
        }
    }

    fun renamePlaylist(id: Long, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { playlistRepo.rename(id, name) }
    }

    fun deletePlaylist(id: Long) {
        viewModelScope.launch { playlistRepo.delete(id) }
        backStack.removeAll { it is Destination.PlaylistDetail && it.playlistId == id }
    }

    fun requestAddToPlaylist(songs: List<Song>) {
        if (songs.isNotEmpty()) pendingPlaylistAdd.value = songs
    }

    fun addToPlaylist(playlistId: Long, songs: List<Song>) {
        viewModelScope.launch { playlistRepo.addSongs(playlistId, songs.map { it.id }) }
    }

    fun removeFromPlaylist(entryId: Long) {
        viewModelScope.launch { playlistRepo.removeEntry(entryId) }
    }

    fun reorderPlaylist(entryIdsInOrder: List<Long>) {
        viewModelScope.launch { playlistRepo.reorder(entryIdsInOrder) }
    }

    // ---- Grouping / filtering ----

    private fun group(songs: List<Song>): GroupedLibrary {
        val inAlbumOrder = compareBy<Song>({ it.disc }, { it.track }).thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }

        val albums = songs.groupBy { it.albumId }.map { (id, list) ->
            val sorted = list.sortedWith(inAlbumOrder)
            val artists = sorted.map { it.artist }.distinct()
            Album(
                id = id,
                title = sorted.first().album,
                artist = if (artists.size == 1) artists.first() else "Various artists",
                songs = sorted,
                dateAdded = sorted.maxOf { it.dateAdded },
            )
        }

        val artists = songs.groupBy { it.artist }.map { (name, list) ->
            val sorted = list.sortedWith(
                compareBy(String.CASE_INSENSITIVE_ORDER) { s: Song -> s.album }.then(inAlbumOrder)
            )
            Artist(
                name = name,
                songs = sorted,
                albumCount = list.distinctBy { it.albumId }.size,
                dateAdded = list.maxOf { it.dateAdded },
            )
        }

        val folders = songs.groupBy { it.folderPath }.map { (path, list) ->
            Folder(
                path = path,
                name = path.trimEnd('/').substringAfterLast('/').ifEmpty { path },
                songs = list.sortedWith(compareBy<Song, String>(String.CASE_INSENSITIVE_ORDER) { it.title }),
                dateAdded = list.maxOf { it.dateAdded },
            )
        }

        return GroupedLibrary(songs, albums, artists, folders)
    }

    private fun filterAndSort(lib: GroupedLibrary, q: String, sort: SortOrder): LibraryUi {
        fun String.hit() = contains(q, ignoreCase = true)
        val filtering = q.isNotEmpty()

        val songs = (if (filtering) lib.songs.filter { it.title.hit() || it.artist.hit() || it.album.hit() } else lib.songs)
            .sortedWith(
                when (sort) {
                    SortOrder.TITLE -> compareBy<Song, String>(String.CASE_INSENSITIVE_ORDER) { it.title }
                    SortOrder.ARTIST -> compareBy<Song, String>(String.CASE_INSENSITIVE_ORDER) { it.artist }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.album }
                        .thenBy { it.disc }
                        .thenBy { it.track }
                    SortOrder.DATE_ADDED -> compareByDescending<Song> { it.dateAdded }
                }
            )

        val albums = (if (filtering) lib.albums.filter { it.title.hit() || it.artist.hit() } else lib.albums)
            .sortedWith(
                when (sort) {
                    SortOrder.TITLE -> compareBy<Album, String>(String.CASE_INSENSITIVE_ORDER) { it.title }
                    SortOrder.ARTIST -> compareBy<Album, String>(String.CASE_INSENSITIVE_ORDER) { it.artist }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
                    SortOrder.DATE_ADDED -> compareByDescending<Album> { it.dateAdded }
                }
            )

        val artists = (if (filtering) lib.artists.filter { it.name.hit() } else lib.artists)
            .sortedWith(
                when (sort) {
                    SortOrder.DATE_ADDED -> compareByDescending<Artist> { it.dateAdded }
                    else -> compareBy<Artist, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
                }
            )

        val folders = (if (filtering) lib.folders.filter { it.name.hit() || it.path.hit() } else lib.folders)
            .sortedWith(
                when (sort) {
                    SortOrder.DATE_ADDED -> compareByDescending<Folder> { it.dateAdded }
                    else -> compareBy<Folder, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
                }
            )

        return LibraryUi(
            loading = false,
            isEmpty = lib.songs.isEmpty(),
            songs = songs,
            albums = albums,
            artists = artists,
            folders = folders,
        )
    }
}
