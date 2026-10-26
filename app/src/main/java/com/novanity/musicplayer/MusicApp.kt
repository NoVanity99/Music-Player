package com.novanity.musicplayer

import android.app.Application
import android.content.Context
import android.os.Environment
import androidx.room.Room
import com.novanity.musicplayer.data.PathLabels
import com.novanity.musicplayer.data.db.AppDatabase
import com.novanity.musicplayer.data.db.AppDatabase.Companion.configure
import com.novanity.musicplayer.platform.AndroidArtworkProvider
import com.novanity.musicplayer.platform.AndroidLibrarySource
import com.novanity.musicplayer.platform.SharedPrefsStore
import com.novanity.musicplayer.platform.StorageRoot
import com.novanity.musicplayer.ui.components.ArtworkLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class MusicApp : Application() {
    lateinit var container: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        val appContext = applicationContext
        container = AppGraph(
            databaseFactory = {
                Room.databaseBuilder<AppDatabase>(appContext, appContext.getDatabasePath(AppDatabase.FILE_NAME).absolutePath)
                    .configure()
                    .build()
            },
            storeFactory = { name -> SharedPrefsStore(appContext, name) },
            librarySource = AndroidLibrarySource(appContext),
            appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )
        ArtworkLoader.provider = AndroidArtworkProvider(appContext)
        PathLabels.roots = listOf(StorageRoot("Internal storage", Environment.getExternalStorageDirectory().absolutePath, false))
    }
}

val Context.appContainer: AppGraph
    get() = (applicationContext as MusicApp).container
