package com.novanity.musicplayer.ui

import com.novanity.musicplayer.data.AccentColor
import com.novanity.musicplayer.data.AccountState
import com.novanity.musicplayer.data.AccountRepository
import com.novanity.musicplayer.data.SongSource
import com.novanity.musicplayer.data.DownloadState
import com.novanity.musicplayer.data.CloudConfig
import com.novanity.musicplayer.data.CloudStatus
import com.novanity.musicplayer.data.DownloadsRepository
import com.novanity.musicplayer.data.CloudRepository
import com.novanity.musicplayer.data.ArtistBios
import com.novanity.musicplayer.data.ArtistBio
import com.novanity.musicplayer.data.ArtistPart
import com.novanity.musicplayer.data.ArtistIndex
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
    /** Splits "A & B" credits into library artists. */
    val artistIndex: ArtistIndex = ArtistIndex(emptyList()),
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
    data object Stats : Destination
    data object Downloads : Destination
    /** Your own account page. */
    data object Account : Destination
    data object Friends : Destination
    /** Your playlists and your hearted artists, as pages of their own (opened from Account). */
    data object MyPlaylists : Destination
    data object MyArtists : Destination
    /** The bug reports people sent (admin only). */
    data object BugReports : Destination
    /** Someone else's public profile. */
    data class Profile(val username: String) : Destination
    data class MixDetail(val kind: com.novanity.musicplayer.data.MixKind) : Destination
}

@OptIn(FlowPreview::class)
class LibraryViewModel(
    private val music: MusicRepository,
    private val playlistRepo: PlaylistRepository,
    private val history: HistoryRepository,
    private val favorites: FavoritesRepository,
    private val settings: SettingsRepository,
    private val bios: ArtistBios? = null,
    private val cloud: CloudRepository? = null,
    private val downloads: DownloadsRepository? = null,
    private val account: AccountRepository? = null,
    private val social: com.novanity.musicplayer.data.SocialRepository? = null,
    private val artistImages: com.novanity.musicplayer.data.ArtistImages? = null,
) : ViewModel() {

    // ---- Artist pictures ----

    val artistPicturesEnabled: StateFlow<Boolean> = settings.artistPictures
    fun setArtistPicturesEnabled(enabled: Boolean) = settings.setArtistPictures(enabled)

    /** Changes when an artist's picture does, so pictures on screen refresh. */
    val artistPicturesVersion: StateFlow<Int> = artistImages?.version ?: MutableStateFlow(0)

    /**
     * The picture to show for an artist's [slot] ("avatar" or "banner") if it's known right now:
     * a web address, "album:<id>" for a library cover, or null to fall back to an album cover.
     */
    fun artistPictureNow(name: String, slot: String): String? =
        artistImages?.choice(name, slot) ?: if (settings.artistPictures.value) artistImages?.knownAuto(name) else null

    /** Like [artistPictureNow], but looks the artist up online first when that hasn't been done. */
    suspend fun artistPicture(name: String, slot: String): String? =
        artistImages?.choice(name, slot) ?: if (settings.artistPictures.value) artistImages?.auto(name) else null

    fun artistPictureChoice(name: String, slot: String): String? = artistImages?.choice(name, slot)
    fun setArtistPicture(name: String, slot: String, reference: String?) { artistImages?.setChoice(name, slot, reference) }
    suspend fun searchArtistPictures(query: String): List<com.novanity.musicplayer.data.ArtistImageOption> = artistImages?.search(query).orEmpty()
    val canChooseArtistPictures: Boolean get() = artistImages != null


    // ---- Friends and listening together ----

    val socialAvailable: Boolean get() = social != null
    val socialState: StateFlow<com.novanity.musicplayer.data.SocialState> = social?.state ?: MutableStateFlow(com.novanity.musicplayer.data.SocialState())
    fun addFriend(username: String) { social?.addFriend(username) }
    fun answerFriend(username: String, accept: Boolean) { social?.answerFriend(username, accept) }
    fun removeFriend(username: String) { social?.removeFriend(username) }
    fun askToListen(username: String) { social?.askToListen(username) }
    fun inviteToListen(username: String) { social?.invite(username) }
    fun answerListen(request: com.novanity.musicplayer.data.ListenRequest, accept: Boolean) { social?.answerListen(request, accept) }
    fun leaveSession() { social?.leave() }
    fun refreshSocial() { viewModelScope.launch { runCatching { social?.pulse() } } }
    val shareListening: StateFlow<Boolean> = settings.shareListening
    fun setShareListening(enabled: Boolean) = settings.setShareListening(enabled)
    /** The library's song for one of the server's song ids. */
    fun songForCloudId(id: String?): Song? = id?.let { music.songsById[com.novanity.musicplayer.data.CloudRepository.songIdFor(it)] }


    // ---- Account ----

    /** Accounts are part of this build: the app is used signed in. */
    val accountsAvailable: Boolean get() = account != null
    val accountState: StateFlow<AccountState> = account?.state ?: MutableStateFlow(AccountState.SignedOut)

    /** Signs in or creates an account; [onResult] gets null on success or a message to show. */
    fun authenticate(create: Boolean, username: String, password: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            onResult(if (create) account?.signUp(username, password) else account?.signIn(username, password))
        }
    }
    fun signOut() { viewModelScope.launch { account?.signOut() } }

    // ---- Customize ----

    val hiddenModules: StateFlow<Set<String>> = settings.hiddenModules
    fun setModuleShown(id: String, shown: Boolean) = settings.setModuleShown(id, shown)
    fun resetModules() = settings.resetModules()
    val startTab: StateFlow<String> = settings.startTab
    fun setStartTab(tab: String) = settings.setStartTab(tab)

    // ---- Profile ----

    private val noProfile = MutableStateFlow(com.novanity.musicplayer.data.AccountProfile())
    val profile: StateFlow<com.novanity.musicplayer.data.AccountProfile> = account?.profile ?: noProfile
    fun setProfile(value: com.novanity.musicplayer.data.AccountProfile) {
        if (account != null) account.setProfile(value) else noProfile.value = value
    }

    /** True for the owner's account: shows the Admin badge and the bug reports. */
    val isAdmin: StateFlow<Boolean> = account?.isAdmin ?: MutableStateFlow(false)
    suspend fun reportBug(message: String, app: String): String? = account?.reportBug(message, app) ?: "Sign in to send a report."
    suspend fun bugReports(): List<com.novanity.musicplayer.data.BugReport>? = account?.bugReports()
    suspend fun deleteBugReport(id: Long) { account?.deleteBugReport(id) }

    fun avatarUrl(token: String?): String? = account?.avatarUrl(token)
    fun setAvatar(jpeg: ByteArray) {
        viewModelScope.launch { notify(account?.setAvatar(jpeg) ?: "Profile picture updated") }
    }
    fun removeAvatar() { viewModelScope.launch { account?.removeAvatar() } }

    suspend fun lookUpProfile(username: String): Pair<com.novanity.musicplayer.data.PublicProfile?, String?> =
        account?.lookUpProfile(username) ?: (null to "Accounts aren't available.")

    /** The library's songs for a list of the server's song ids (ones no longer in the library are left out). */
    fun songsForCloudIds(ids: List<String>): List<Song> = ids.mapNotNull { music.songsById[com.novanity.musicplayer.data.CloudRepository.songIdFor(it)] }

    /**
     * Your Library shows what you've liked: liked songs, hearted albums and hearted artists
     * (playlists are always all yours). Browsing from Search shows everything instead.
     */
    var libraryLikedOnly by mutableStateOf(true)
    fun showFavoriteArtists() = showLibrary(LibraryFilter.ARTISTS)

    /** Albums this account has hearted (by album id). */
    private val localFavoriteAlbums = MutableStateFlow<Set<Long>>(emptySet())
    val favoriteAlbums: StateFlow<Set<Long>> = account?.favoriteAlbums ?: localFavoriteAlbums
    fun setFavoriteAlbum(album: Album, favorite: Boolean) {
        if (account != null) account.setFavoriteAlbum(album.id, favorite)
        else localFavoriteAlbums.value = if (favorite) localFavoriteAlbums.value + album.id else localFavoriteAlbums.value - album.id
        notify(if (favorite) "Added to your albums" else "Removed from your albums")
    }

    /** Artists this account has hearted; without accounts (tests) they're kept for the session. */
    private val localFavoriteArtists = MutableStateFlow<Set<String>>(emptySet())
    val favoriteArtists: StateFlow<Set<String>> = account?.favoriteArtists ?: localFavoriteArtists
    fun setFavoriteArtist(name: String, favorite: Boolean) {
        if (account != null) account.setFavoriteArtist(name, favorite)
        else localFavoriteArtists.value = if (favorite) localFavoriteArtists.value + name else localFavoriteArtists.value - name
        notify(if (favorite) "Added to Your artists" else "Removed from Your artists")
    }
    fun deleteAccount(onResult: (String?) -> Unit) { viewModelScope.launch { onResult(account?.deleteAccount()) } }


    // ---- Cloud library and offline downloads ----

    val cloudAvailable: Boolean get() = cloud != null
    /** The server and key ship with the app: nothing to set up or change. */
    val cloudBuiltIn: Boolean get() = cloud?.isBuiltIn == true
    val cloudStatus: StateFlow<CloudStatus> = cloud?.status ?: MutableStateFlow(CloudStatus.NotConfigured)
    val cloudConfig: StateFlow<CloudConfig> = cloud?.config ?: MutableStateFlow(CloudConfig("", ""))
    /** The cloud settings have worked at least once. */
    val cloudEverConnected: StateFlow<Boolean> = cloud?.everConnected ?: MutableStateFlow(false)
    fun connectCloud(serverUrl: String, key: String) { cloud?.connect(serverUrl, key) }
    fun disconnectCloud() { cloud?.disconnect() }
    fun refreshCloud() { viewModelScope.launch { cloud?.refresh(); account?.sync() } }

    /** Download state per cloud song id. */
    val downloadStates: StateFlow<Map<String, DownloadState>> = downloads?.states ?: MutableStateFlow(emptyMap())
    val downloadStorageUsed: StateFlow<Long> = downloads?.storageUsed ?: MutableStateFlow(0L)

    /** Queues the cloud songs among [songs] for download; returns how many were queued. */
    fun download(songs: List<Song>): Int {
        val wanted = songs.filter { it.source == SongSource.CLOUD && downloadStates.value[it.cloudId].let { s -> s == null || s is DownloadState.Failed } }
        if (wanted.isNotEmpty()) downloads?.enqueue(wanted)
        return wanted.size
    }
    fun cancelDownload(song: Song) { song.cloudId?.let { downloads?.cancel(it) } }
    fun deleteDownload(song: Song) { song.cloudId?.let { downloads?.delete(it) } }
    fun cancelAllDownloads() { downloads?.cancelAll() }
    fun deleteAllDownloads() { downloads?.deleteAll() }

    /** Songs tab: show only songs that play without internet (local and downloaded). */
    val offlineOnly = MutableStateFlow(false)


    // ---- Navigation: one back stack per bottom-bar tab ----

    // Opens on the tab chosen in Settings → Customize.
    var tab by mutableStateOf(when (settings.startTab.value) { "search" -> Tab.SEARCH; "library" -> Tab.LIBRARY; else -> Tab.HOME })
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

    /** The artists in a credit such as "Drake & Future", each with whether it has a page here. */
    fun artistParts(credit: String): List<ArtistPart> =
        grouped.value?.artistIndex?.resolve(credit) ?: listOf(ArtistPart(credit, credit, false))

    /** Opens the artist's page, or says they aren't in the library. */
    fun openArtist(part: ArtistPart) {
        // Someone credited only alongside others can have a page too (see group()).
        val page = grouped.value?.artistsByName?.let { byName -> byName[part.name]?.name ?: byName.keys.firstOrNull { it.equals(part.name, ignoreCase = true) } }
        if (page != null) {
            open(Destination.ArtistDetail(page))
        } else {
            notify("${part.text} isn't in your library")
        }
    }

    val visualizerEnabled: StateFlow<Boolean> = settings.visualizer
    fun setVisualizerEnabled(enabled: Boolean) = settings.setVisualizer(enabled)

    val artistBiosEnabled: StateFlow<Boolean> = settings.artistBios
    fun setArtistBiosEnabled(enabled: Boolean) = settings.setArtistBios(enabled)

    val advancedTransitions: StateFlow<Boolean> = settings.advancedTransitions
    fun setAdvancedTransitions(enabled: Boolean) = settings.setAdvancedTransitions(enabled)

    /** The owner's list of albums that play with advanced transitions; recomposes when it changes. */
    val ownerConfig: StateFlow<com.novanity.musicplayer.data.CloudOwnerConfig>? = cloud?.ownerConfig
    fun hasAdvancedTransitions(album: Album): Boolean = cloud?.hasAdvancedTransitions(album.title, album.songs.first().artist) == true
    val canShowBios: Boolean get() = bios != null

    /** A short bio for [artist] from Wikipedia, or null (none found, offline, or turned off). */
    /** The bio when it's already on the device, so the page can show it straight away. */
    fun cachedArtistBio(artist: String): ArtistBio? = if (settings.artistBios.value) bios?.peek(artist) else null

    suspend fun artistBio(artist: String): ArtistBio? =
        if (settings.artistBios.value) bios?.bio(artist) else null


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

    fun showLibrary(filter: LibraryFilter, everything: Boolean = false) {
        libraryLikedOnly = !everything
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
    val accent: StateFlow<AccentColor> = settings.accent
    fun setAccent(color: AccentColor) = settings.setAccent(color)
    val pureBlack: StateFlow<Boolean> = settings.pureBlack
    fun setPureBlack(enabled: Boolean) = settings.setPureBlack(enabled)
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

    /** Every remembered play, newest first. */
    private val playEvents = history.events.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val statsRange = MutableStateFlow(com.novanity.musicplayer.data.StatsRange.MONTH)

    /** Listening stats for [statsRange]; null until the library has loaded. */
    val stats: StateFlow<com.novanity.musicplayer.data.ListeningStats?> = combine(grouped, playEvents, statsRange) { lib, events, range ->
        lib?.let {
            com.novanity.musicplayer.data.StatsCalculator.compute(
                events, it.songs.associateBy { s -> s.id }, it.albumsById, it.artistIndex, range,
            )
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Today's automatic mixes ("Forgotten favorites", "Deep cuts", ...). */
    val mixes: StateFlow<List<com.novanity.musicplayer.data.Mix>> = combine(grouped, playEvents, likedIds) { lib, events, liked ->
        lib?.let { com.novanity.musicplayer.data.MixBuilder.build(it.songs, events, liked, it.artistIndex) }.orEmpty()
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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

    val home: StateFlow<HomeUi> = combine(grouped, recentlyPlayed, likedSongs, playlists, favoriteArtists) { lib, recent, liked, lists, favArtists ->
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
            // Only the artists you've hearted, in name order.
            topArtists = lib.artists.filter { it.name in favArtists }.sortedBy { it.name.lowercase() },
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
                folders = emptyList(), // folders aren't shown anywhere any more
            )
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchResults())

    fun setSearchQuery(q: String) {
        searchQuery.value = q
    }

    init {
        viewModelScope.launch { social?.messages?.collect { notify(it) } }
        // Songs that were added to the server while the app is open.
        viewModelScope.launch {
            cloud?.arrivals?.collect { added ->
                val albums = added.map { it.album }.distinct()
                notify(
                    when {
                        albums.size == 1 && added.size == 1 -> "New song added: ${added.first().title}"
                        albums.size == 1 -> "New music added: ${albums.first()}"
                        else -> "New music added: ${albums.first()} and ${albums.size - 1} more"
                    }
                )
            }
        }
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
        // Optional: liking a cloud song also saves it for offline play.
        if (liked && settings.downloadLiked.value) download(listOf(song))
    }

    val downloadLiked: StateFlow<Boolean> = settings.downloadLiked
    fun setDownloadLiked(enabled: Boolean) = settings.setDownloadLiked(enabled)

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

    /** True when [album] is this artist's own: they are its album artist, alone or as one of a named pair. */
    fun ownsAlbum(artistName: String, album: Album): Boolean =
        grouped.value?.artistIndex?.let { artistName.lowercase() in albumOwners(album, it) } ?: album.artist.equals(artistName, ignoreCase = true)

    /**
     * Whose album it is, in lower case: the album's artist, or each of them when the album itself
     * is credited to several ("A & B"). Someone who only features on a song is not an owner.
     */
    private fun albumOwners(album: Album, index: ArtistIndex): Set<String> =
        index.resolve(album.artist).mapTo(HashSet()) { it.name.lowercase() }

    private fun group(songs: List<Song>): GroupedLibrary {
        val inAlbumOrder = compareBy<Song>({ it.disc }, { it.track }).thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }

        val index = ArtistIndex(songs.map { it.artist }.distinct())

        val albums = songs.groupBy { it.albumId }.map { (id, list) ->
            val sorted = list.sortedWith(inAlbumOrder)
            val artists = sorted.map { it.artist }.distinct()
            // "Daft Punk" and "Daft Punk feat. Sol Vega" on one album: the album is Daft Punk's.
            val leads = artists.map { index.resolve(it).first().name }.distinct()
            val tagged = sorted.map { it.albumArtist }.filter { it.isNotBlank() }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            Album(
                id = id,
                title = sorted.first().album,
                // The album belongs to its album artist when the files name one; otherwise to the
                // one artist all its songs share (or lead with).
                artist = when {
                    tagged != null -> tagged
                    artists.size == 1 -> artists.first()
                    leads.size == 1 -> leads.first()
                    else -> "Various artists"
                },
                songs = sorted,
                dateAdded = sorted.maxOf { it.dateAdded },
                kind = sorted.first().albumKind ?: com.novanity.musicplayer.data.AlbumKind.forSize(sorted.size),
                year = sorted.maxOf { it.year },
            )
        }

        // Collaborations ("A & B") don't get their own artist: their songs appear on each member's page.
        val byArtist = compareBy(String.CASE_INSENSITIVE_ORDER) { s: Song -> s.album }.then(inAlbumOrder)
        val appearsOn = HashMap<String, MutableList<Song>>()
        for (song in songs) {
            val parts = index.resolve(song.artist)
            if (parts.size > 1) parts.filter { it.inLibrary }.forEach { appearsOn.getOrPut(it.name, ::ArrayList) += song }
        }
        val artists = songs.filter { !index.isCollaboration(it.artist) }.groupBy { it.artist }.map { (name, list) ->
            Artist(
                name = name,
                songs = list.sortedWith(byArtist),
                // Only albums that are theirs (see albumOwners), not ones they feature on.
                albumCount = albums.count { name.lowercase() in albumOwners(it, index) },
                dateAdded = list.maxOf { it.dateAdded },
                appearsOn = appearsOn[name].orEmpty().sortedWith(byArtist),
            )
        }

        // Singles belong to everyone credited on them. Anyone credited on a single who has no
        // page yet (they only ever appear alongside someone else) gets one, holding the songs
        // they're credited on.
        val known = artists.mapTo(HashSet()) { it.name.lowercase() }
        val guests = LinkedHashMap<String, Pair<String, MutableList<Song>>>()
        for (song in songs) {
            val parts = index.resolve(song.artist)
            if (parts.size < 2) continue
            for (part in parts) {
                val key = part.name.lowercase()
                if (key in known || key.isBlank()) continue
                guests.getOrPut(key) { part.name to ArrayList() }.second += song
            }
        }
        val singleIds = albums.filter { it.kind == com.novanity.musicplayer.data.AlbumKind.SINGLE }.mapTo(HashSet()) { it.id }
        val guestArtists = guests.values
            .filter { (_, credited) -> credited.any { it.albumId in singleIds } }
            .map { (name, credited) ->
                Artist(
                    name = name,
                    songs = credited.sortedWith(byArtist),
                    albumCount = albums.count { name.lowercase() in albumOwners(it, index) },
                    dateAdded = credited.maxOf { it.dateAdded },
                )
            }
        val allArtists = artists + guestArtists

        val folders = songs.groupBy { it.folderPath }.map { (path, list) ->
            Folder(
                path = path,
                name = path.trimEnd('/').substringAfterLast('/').ifEmpty { path },
                songs = list.sortedWith(compareBy<Song, String>(String.CASE_INSENSITIVE_ORDER) { it.title }),
                dateAdded = list.maxOf { it.dateAdded },
            )
        }

        return GroupedLibrary(songs, albums, allArtists, folders, index)
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
