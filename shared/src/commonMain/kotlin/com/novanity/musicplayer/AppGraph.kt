package com.novanity.musicplayer

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
    librarySource: LibrarySource,
    val appScope: CoroutineScope,
) {
    private val database by lazy(databaseFactory)

    val settings = SettingsRepository(storeFactory("settings"))
    val musicRepository = MusicRepository(librarySource, appScope, settings.includedFolders, settings.excludedFolders)
    val playlistRepository by lazy { PlaylistRepository(database.playlistDao()) }
    val historyRepository by lazy { HistoryRepository(database.historyDao()) }
    val favoritesRepository by lazy { FavoritesRepository(database.favoritesDao()) }
    val playbackStateStore = PlaybackStateStore(storeFactory("playback_state"))
}
