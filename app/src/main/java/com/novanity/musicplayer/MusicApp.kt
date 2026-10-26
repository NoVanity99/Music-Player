package com.novanity.musicplayer

import android.app.Application
import android.content.Context
import com.novanity.musicplayer.data.FavoritesRepository
import com.novanity.musicplayer.data.HistoryRepository
import com.novanity.musicplayer.data.MusicRepository
import com.novanity.musicplayer.data.PlaylistRepository
import com.novanity.musicplayer.data.SettingsRepository
import com.novanity.musicplayer.data.db.AppDatabase
import com.novanity.musicplayer.playback.PlaybackStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class MusicApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Manual dependency container shared by the UI and the playback service. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val database by lazy { AppDatabase.build(appContext) }

    val settings = SettingsRepository(appContext)
    val musicRepository = MusicRepository(appContext, appScope, settings.includedFolders, settings.excludedFolders)
    val playlistRepository by lazy { PlaylistRepository(database.playlistDao()) }
    val historyRepository by lazy { HistoryRepository(database.historyDao()) }
    val favoritesRepository by lazy { FavoritesRepository(database.favoritesDao()) }
    val playbackStateStore = PlaybackStateStore(appContext)
}

val Context.appContainer: AppContainer
    get() = (applicationContext as MusicApp).container
