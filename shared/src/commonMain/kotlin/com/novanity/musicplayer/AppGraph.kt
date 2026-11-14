package com.novanity.musicplayer

import com.novanity.musicplayer.data.CloudFiles
import com.novanity.musicplayer.data.CloudRepository
import com.novanity.musicplayer.data.DownloadsRepository
import com.novanity.musicplayer.data.FavoritesRepository
import com.novanity.musicplayer.data.HistoryRepository
import com.novanity.musicplayer.data.MusicRepository
import com.novanity.musicplayer.data.PlaylistRepository
import com.novanity.musicplayer.data.SettingsRepository
import com.novanity.musicplayer.data.db.AppDatabase
import com.novanity.musicplayer.platform.KeyValueStore
import com.novanity.musicplayer.platform.LibrarySource
import com.novanity.musicplayer.playback.PlaybackStateStore
import kotlinx.coroutines.CoroutineScope

/** Repositories shared by the UI and playback, built once per process by each platform. */
class AppGraph(
    databaseFactory: () -> AppDatabase,
    storeFactory: (name: String) -> KeyValueStore,
    val appScope: CoroutineScope,
    /** Only tests scan local files; the apps play the cloud library (and downloads of it). */
    librarySource: LibrarySource = LibrarySource.None,
    /** HTTP GET (artist bios, the cloud library); null leaves both off (tests). */
    httpGet: (suspend (url: String, headers: Map<String, String>) -> com.novanity.musicplayer.data.HttpText)? = null,
    /** HTTP with a method and JSON body (accounts); null leaves accounts off (tests). */
    httpSend: (suspend (method: String, url: String, headers: Map<String, String>, body: String?) -> com.novanity.musicplayer.data.HttpText)? = null,
    /** Private storage for downloads and covers. */
    cloudFiles: CloudFiles = CloudFiles.None,
    /** The server and key this build ships with; when set, there's nothing for the user to connect. */
    builtInCloud: com.novanity.musicplayer.data.CloudConfig? = null,
) {
    private val database by lazy(databaseFactory)

    val settings = SettingsRepository(storeFactory("settings"))
    val downloads = DownloadsRepository({ database.downloadsDao() }, cloudFiles, appScope)

    /** Server address and key live in their own store, which each platform protects as well as it can. */
    val cloud = CloudRepository(
        secure = storeFactory("cloud_secure"),
        cache = storeFactory("cloud_cache"),
        httpGet = httpGet ?: { _, _ -> throw IllegalStateException("No network in this build") },
        files = cloudFiles,
        scope = appScope,
        downloads = downloads.rows,
        builtIn = builtInCloud?.takeIf { it.isSet },
    ).also { downloads.cloud = it }

    val musicRepository = MusicRepository(librarySource, appScope, settings.includedFolders, settings.excludedFolders, cloud.songs)
    val playlistRepository by lazy { PlaylistRepository(database.playlistDao()) }
    val historyRepository by lazy { HistoryRepository(database.historyDao()) }
    val favoritesRepository by lazy { FavoritesRepository(database.favoritesDao()) }
    val playbackStateStore = PlaybackStateStore(storeFactory("playback_state"))
    /** Sign-in and each account's own likes, history and playlists (kept on the server). */
    val account = httpSend?.let { send ->
        com.novanity.musicplayer.data.AccountRepository(
            secure = storeFactory("cloud_secure"),
            queueStore = storeFactory("account_sync"),
            send = send,
            cloud = cloud,
            favorites = favoritesRepository,
            history = historyRepository,
            playlistRepo = playlistRepository,
            favoritesDao = { database.favoritesDao() },
            historyDao = { database.historyDao() },
            playlistDao = { database.playlistDao() },
            scope = appScope,
        )
    }

    val artistBios = httpGet?.let { get -> com.novanity.musicplayer.data.ArtistBios(storeFactory("artist_bios"), { url -> get(url, emptyMap()) }) }
}
