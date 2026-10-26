package com.novanity.musicplayer.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novanity.musicplayer.data.Album
import com.novanity.musicplayer.data.Artist
import com.novanity.musicplayer.data.FavoritesRepository
import com.novanity.musicplayer.data.Folder
import com.novanity.musicplayer.data.HistoryRepository
import com.novanity.musicplayer.data.MusicRepository
import com.novanity.musicplayer.data.PlaylistRepository
import com.novanity.musicplayer.data.SettingsRepository
import com.novanity.musicplayer.data.Song
import com.novanity.musicplayer.data.SortOrder
import com.novanity.musicplayer.data.ThemeMode
import com.novanity.musicplayer.data.pathIsIn
import com.novanity.musicplayer.data.db.PlaylistEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
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
data class PlaylistCard(val id: Long, val name: String, val songCount: Int, val covers: List<Song>)

@Immutable
data class PlaylistTrack(val entryId: Long, val song: Song)

sealed interface QuickPick {
    data class Liked(val count: Int) : QuickPick
    data class ShuffleAll(val count: Int) : QuickPick
    data class AlbumPick(val album: Album) : QuickPick
    data class PlaylistPick(val playlist: PlaylistCard) : QuickPick
}

@Immutable
data class HomeUi(
    val loading: Boolean = true,
    val isEmpty: Boolean = false,
    val quickPicks: List<QuickPick> = emptyList(),
    val recentlyPlayed: List<Song> = emptyList(),
    val recentlyAdded: List<Album> = emptyList(),
    val topArtists: List<Artist> = emptyList(),
    val playlists: List<PlaylistCard> = emptyList(),
)

@Immutable
data class SearchResults(
    val query: String = "",
    val songs: List<Song> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val playlists: List<PlaylistCard> = emptyList(),
    val folders: List<Folder> = emptyList(),
) {
    val isEmpty get() = songs.isEmpty() && albums.isEmpty() && artists.isEmpty() && playlists.isEmpty() && folders.isEmpty()
}

enum class Tab(val label: String) { HOME("Home"), SEARCH("Search"), LIBRARY("Library") }

enum class LibraryFilter(val label: String) {
    PLAYLISTS("Playlists"), ALBUMS("Albums"), ARTISTS("Artists"), SONGS("Songs"), FOLDERS("Folders")
}

sealed interface Destination {
    data class AlbumDetail(val albumId: Long) : Destination
    data class ArtistDetail(val name: String) : Destination
    data class FolderDetail(val path: String) : Destination
    data class PlaylistDetail(val playlistId: Long) : Destination
    data object RecentlyPlayed : Destination
    data object LikedSongs : Destination
    data object MostPlayed : Destination
    data object Settings : Destination
    data object Equalizer : Destination
}

@OptIn(FlowPreview::class)
class LibraryViewModel(
    private val music: MusicRepository,
    private val playlistRepo: PlaylistRepository,
    private val history: HistoryRepository,
    private val favorites: FavoritesRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    // ---- Navigation: one back stack per bottom-bar tab ----

    var tab by mutableStateOf(Tab.HOME)
        private set
    private val stacks: Map<Tab, SnapshotStateList<Destination>> = Tab.entries.associateWith { mutableStateListOf() }
    val backStack: SnapshotStateList<Destination> get() = stacks.getValue(tab)
    var libraryFilter by mutableStateOf(LibraryFilter.PLAYLISTS)

    fun selectTab(t: Tab) {
        if (t == tab) stacks.getValue(t).clear() else tab = t
    }

    fun open(destination: Destination) {
        backStack.add(destination)
    }

    /** Returns false when there was nothing to go back to. */
    fun back(): Boolean {
        if (backStack.isNotEmpty()) {
            backStack.removeAt(backStack.lastIndex)
            return true
        }
        if (tab != Tab.HOME) {
            tab = Tab.HOME
            return true
        }
        return false
    }

    fun showLibrary(filter: LibraryFilter) {
        libraryFilter = filter
        stacks.getValue(Tab.LIBRARY).clear()
        tab = Tab.LIBRARY
    }

    // ---- Settings ----

    val sortOrder: StateFlow<SortOrder> = settings.sortOrder
    val themeMode: StateFlow<ThemeMode> = settings.themeMode
    val dynamicColor: StateFlow<Boolean> = settings.dynamicColor
    val includedFolders: StateFlow<Set<String>> = settings.includedFolders
    val excludedFolders: StateFlow<Set<String>> = settings.excludedFolders
    val syncing: StateFlow<Boolean> = music.syncing
    val songs: StateFlow<List<Song>?> = music.songs
    val songsById: Map<Long, Song> get() = music.songsById
    val newSongsFound = music.newSongsFound

    fun setSortOrder(order: SortOrder) = settings.setSortOrder(order)

    val discordPresence: StateFlow<Boolean> = settings.discordPresence
    fun setDiscordPresence(enabled: Boolean) = settings.setDiscordPresence(enabled)

    val crossfadeSeconds: StateFlow<Int> = settings.crossfadeSeconds
    fun setCrossfadeSeconds(value: Int) = settings.setCrossfadeSeconds(value)
    val eqEnabled: StateFlow<Boolean> = settings.eqEnabled
    val eqPreset: StateFlow<Int> = settings.eqPreset
    val eqBands: StateFlow<List<Int>> = settings.eqBands
    fun setEqEnabled(enabled: Boolean) = settings.setEqEnabled(enabled)
    fun setEqPreset(index: Int) = settings.setEqPreset(index)
    fun setEqBands(levels: List<Int>) = settings.setEqBands(levels)
    fun setThemeMode(mode: ThemeMode) = settings.setThemeMode(mode)
    fun setDynamicColor(enabled: Boolean) = settings.setDynamicColor(enabled)

    /** Start pulling music from [path] (and check it for new files right away). */
    fun addFolder(path: String) {
        settings.setExcludedFolders(excludedFolders.value - path)
        // A parent folder already covers its subfolders; a new parent replaces them.
        val current = includedFolders.value
        if (current.any { pathIsIn(path, it) }) return
        settings.setIncludedFolders(current.filterNot { pathIsIn(it, path) }.toSet() + path)
        checkForNewSongs()
    }

    fun removeFolder(path: String) = settings.setIncludedFolders(includedFolders.value - path)

    /** Leave [path] and its subfolders out of the library. */
    fun ignoreFolder(path: String) = settings.setExcludedFolders(excludedFolders.value + path)

    fun unignoreFolder(path: String) {
        settings.setExcludedFolders(excludedFolders.value - path)
        checkForNewSongs()
    }

    fun checkForNewSongs() {
        viewModelScope.launch { music.syncFolders() }
    }

    private val _messages = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Short user-facing messages, shown as snackbars. */
    val messages: kotlinx.coroutines.flow.SharedFlow<String> = _messages

    fun notify(message: String) {
        _messages.tryEmit(message)
    }

    /** Songs waiting for the "add to playlist" picker, if open. */
    var pendingPlaylistAdd by mutableStateOf<List<Song>?>(null)

    // ---- Library data ----

    val grouped: StateFlow<GroupedLibrary?> = music.songs
        .map { songs -> songs?.let(::group) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Plays per song id (from play history). */
    val playCounts: StateFlow<Map<Long, Int>> = history.playCounts
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Songs tab: show only liked songs. */
    val likedOnly = MutableStateFlow(false)

    val library: StateFlow<LibraryUi> = combine(grouped, sortOrder, playCounts) { lib, sort, plays ->
        if (lib == null) LibraryUi(loading = true) else sorted(lib, sort, plays)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUi())

    val playlists: StateFlow<List<PlaylistCard>> =
        combine(playlistRepo.playlists, playlistRepo.coverEntries, music.songs) { all, entries, _ ->
            val byId = music.songsById
            val byPlaylist = entries.groupBy { it.playlistId }
            all.map { p ->
                val covers = byPlaylist[p.id].orEmpty().asSequence()
                    .mapNotNull { byId[it.songId] }
                    .distinctBy { it.albumId }
                    .take(4)
                    .toList()
                PlaylistCard(p.id, p.name, p.songCount, covers)
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val likedIds: StateFlow<Set<Long>> = favorites.songIds.map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val likedSongs: StateFlow<List<Song>> = combine(favorites.songIds, music.songs) { ids, _ ->
        val byId = music.songsById
        ids.mapNotNull(byId::get)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Most played songs, most first. */
    val mostPlayed: StateFlow<List<Song>> = combine(playCounts, music.songs) { plays, _ ->
        val byId = music.songsById
        plays.entries.sortedByDescending { it.value }.take(100).mapNotNull { byId[it.key] }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val recentlyPlayed: StateFlow<List<Song>> = combine(history.recentSongIds(), music.songs) { ids, _ ->
        val byId = music.songsById
        ids.mapNotNull(byId::get)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val home: StateFlow<HomeUi> = combine(grouped, recentlyPlayed, likedSongs, playlists) { lib, recent, liked, lists ->
        if (lib == null) return@combine HomeUi(loading = true)
        val recentAlbums = recent.asSequence().map { it.albumId }.distinct()
            .mapNotNull(lib.albumsById::get).take(6).toList()
        val recentlyAdded = lib.albums.sortedByDescending { it.dateAdded }.take(12)

        val picks = buildList {
            if (liked.isNotEmpty()) add(QuickPick.Liked(liked.size))
            if (lib.songs.isNotEmpty()) add(QuickPick.ShuffleAll(lib.songs.size))
            lists.take(2).forEach { add(QuickPick.PlaylistPick(it)) }
            (recentAlbums + recentlyAdded).distinctBy { it.id }.forEach {
                if (size < 6) add(QuickPick.AlbumPick(it))
            }
        }.take(6)

        HomeUi(
            loading = false,
            isEmpty = lib.songs.isEmpty(),
            quickPicks = picks,
            recentlyPlayed = recent.take(20),
            recentlyAdded = recentlyAdded,
            topArtists = lib.artists.sortedByDescending { it.songs.size }.take(12),
            playlists = lists,
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUi())

    // ---- Search ----

    val searchQuery = MutableStateFlow("")

    val searchResults: StateFlow<SearchResults> =
        combine(grouped, searchQuery.debounce(120), playlists) { lib, raw, lists ->
            val q = raw.trim()
            if (lib == null || q.isEmpty()) return@combine SearchResults(query = q)
            fun String.hit() = contains(q, ignoreCase = true)
            fun String.rank() = when {
                equals(q, ignoreCase = true) -> 0
                startsWith(q, ignoreCase = true) -> 1
                else -> 2
            }
            SearchResults(
                query = q,
                songs = lib.songs.filter { it.title.hit() || it.artist.hit() || it.album.hit() }
                    .sortedWith(compareBy<Song> { it.title.rank() }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }),
                albums = lib.albums.filter { it.title.hit() || it.artist.hit() }
                    .sortedWith(compareBy<Album> { it.title.rank() }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }),
                artists = lib.artists.filter { it.name.hit() }
                    .sortedWith(compareBy<Artist> { it.name.rank() }.thenByDescending { it.songs.size }),
                playlists = lists.filter { it.name.hit() },
                folders = lib.folders.filter { it.name.hit() },
            )
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchResults())

    fun setSearchQuery(q: String) {
        searchQuery.value = q
    }

    init {
        music.startObserving()
        // Every app start: load what Android already knows, then check the folders for new files.
        viewModelScope.launch {
            music.refresh()
            music.syncFolders()
        }
    }

    fun refresh() {
        viewModelScope.launch { music.refresh() }
    }

    // ---- Favorites ----

    /** Keyboard shortcut: like/unlike whatever is playing. */
    fun currentLikeToggle(player: PlayerViewModel) {
        val song = player.state.value.current?.songId?.let(music.songsById::get) ?: return
        val liked = song.id in likedIds.value
        setLiked(song, !liked)
        notify(if (liked) "Removed from Liked Songs" else "Added to Liked Songs")
    }

    fun setLiked(song: Song, liked: Boolean) {
        viewModelScope.launch { favorites.setLiked(song.id, liked) }
    }

    // ---- Playlists ----

    fun playlist(id: Long): Flow<PlaylistEntity?> = playlistRepo.playlist(id)

    fun playlistTracks(id: Long): Flow<List<PlaylistTrack>> =
        combine(playlistRepo.entries(id), music.songs) { entries, _ ->
            val byId = music.songsById
            // Songs deleted from the device (or outside the chosen folders) drop out of the view.
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
        stacks.values.forEach { stack -> stack.removeAll { it is Destination.PlaylistDetail && it.playlistId == id } }
    }

    fun requestAddToPlaylist(songs: List<Song>) {
        if (songs.isNotEmpty()) pendingPlaylistAdd = songs
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

    // ---- Grouping / sorting ----

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

    private fun sorted(lib: GroupedLibrary, sort: SortOrder, plays: Map<Long, Int>): LibraryUi {
        val byTitle = compareBy<Song, String>(String.CASE_INSENSITIVE_ORDER) { it.title }
        val songOrder: Comparator<Song> = when (sort) {
            SortOrder.TITLE -> byTitle
            SortOrder.ARTIST -> compareBy<Song, String>(String.CASE_INSENSITIVE_ORDER) { it.artist }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.album }.thenBy { it.disc }.thenBy { it.track }
            SortOrder.ALBUM -> compareBy<Song, String>(String.CASE_INSENSITIVE_ORDER) { it.album }
                .thenBy { it.disc }.thenBy { it.track }
            SortOrder.DATE_ADDED -> compareByDescending<Song> { it.dateAdded }.then(byTitle)
            SortOrder.DURATION -> compareByDescending<Song> { it.durationMs }
            SortOrder.MOST_PLAYED -> compareByDescending<Song> { plays[it.id] ?: 0 }.then(byTitle)
        }
        fun List<Song>.plays() = sumOf { plays[it.id] ?: 0 }
        val albumTitle = compareBy<Album, String>(String.CASE_INSENSITIVE_ORDER) { it.title }
        val albumOrder: Comparator<Album> = when (sort) {
            SortOrder.TITLE, SortOrder.ALBUM -> albumTitle
            SortOrder.ARTIST -> compareBy<Album, String>(String.CASE_INSENSITIVE_ORDER) { it.artist }.then(albumTitle)
            SortOrder.DATE_ADDED -> compareByDescending<Album> { it.dateAdded }.then(albumTitle)
            SortOrder.DURATION -> compareByDescending<Album> { a -> a.songs.sumOf { it.durationMs } }
            SortOrder.MOST_PLAYED -> compareByDescending<Album> { it.songs.plays() }.then(albumTitle)
        }
        val artistName = compareBy<Artist, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
        val artistOrder: Comparator<Artist> = when (sort) {
            SortOrder.DATE_ADDED -> compareByDescending<Artist> { it.dateAdded }.then(artistName)
            SortOrder.MOST_PLAYED -> compareByDescending<Artist> { it.songs.plays() }.then(artistName)
            SortOrder.DURATION -> compareByDescending<Artist> { it.songs.size }.then(artistName)
            else -> artistName
        }
        val folderName = compareBy<Folder, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
        val folderOrder: Comparator<Folder> = when (sort) {
            SortOrder.DATE_ADDED -> compareByDescending<Folder> { it.dateAdded }.then(folderName)
            SortOrder.MOST_PLAYED -> compareByDescending<Folder> { it.songs.plays() }.then(folderName)
            else -> folderName
        }
        return LibraryUi(
            loading = false,
            isEmpty = lib.songs.isEmpty(),
            songs = lib.songs.sortedWith(songOrder),
            albums = lib.albums.sortedWith(albumOrder),
            artists = lib.artists.sortedWith(artistOrder),
            folders = lib.folders.sortedWith(folderOrder),
        )
    }
}
