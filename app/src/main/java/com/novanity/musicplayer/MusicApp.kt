package com.novanity.musicplayer

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import android.net.Uri
import java.io.File
import com.novanity.musicplayer.downloads.DownloadWorker
import com.novanity.musicplayer.playback.CloudCovers
import com.novanity.musicplayer.platform.AndroidCloudFiles
import com.novanity.musicplayer.platform.SecurePrefsStore
import android.app.Application
import android.content.Context
import android.os.Environment
import androidx.room.Room
import com.novanity.musicplayer.data.PathLabels
import com.novanity.musicplayer.data.db.AppDatabase
import com.novanity.musicplayer.data.db.AppDatabase.Companion.configure
import com.novanity.musicplayer.platform.AndroidArtworkProvider
import com.novanity.musicplayer.platform.AndroidUpdatePlatform
import com.novanity.musicplayer.update.UpdateManager
import com.novanity.musicplayer.platform.AndroidLyricsProvider
import com.novanity.musicplayer.lyrics.LyricsLoader
import com.novanity.musicplayer.platform.SharedPrefsStore
import com.novanity.musicplayer.platform.StorageRoot
import com.novanity.musicplayer.ui.components.ArtworkLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class MusicApp : Application() {
    lateinit var container: AppGraph
        private set

    /** App-wide so an in-progress update survives the activity being recreated. */
    val updates by lazy {
        UpdateManager(AndroidUpdatePlatform(this), container.settings, container.appScope)
    }

    override fun onCreate() {
        super.onCreate()
        val appContext = applicationContext
        container = AppGraph(
            databaseFactory = {
                Room.databaseBuilder<AppDatabase>(appContext, appContext.getDatabasePath(AppDatabase.FILE_NAME).absolutePath)
                    .configure()
                    .build()
            },
            // The cloud server address and access key are encrypted with a Keystore key.
            storeFactory = { name -> if (name == "cloud_secure") SecurePrefsStore(appContext, name) else SharedPrefsStore(appContext, name) },
            appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            httpGet = { url, headers ->
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    // The song list of a big library can be several megabytes.
                    val (status, body) = com.novanity.musicplayer.updatecore.UpdateCore.getText(url, BIO_USER_AGENT, 32_000_000, headers)
                    com.novanity.musicplayer.data.HttpText(status, body)
                }
            },
            cloudFiles = AndroidCloudFiles(appContext.filesDir),
        )
        val cloud = container.cloud
        ArtworkLoader.provider = AndroidArtworkProvider(appContext) { coverId -> cloud.coverFile(coverId) }
        CloudCovers.localFile = { coverId -> File(appContext.filesDir, "covers/$coverId.jpg").takeIf { it.isFile } }
        CloudCovers.remoteUri = { coverId -> cloud.config.value.takeIf { it.isSet }?.let { Uri.parse("${it.serverUrl}/cover/$coverId") } }

        // Downloads run in a WorkManager job; pick the queue back up if the app was closed mid-way.
        container.downloads.startRunner = { DownloadWorker.start(appContext) }
        container.appScope.launch {
            container.downloads.rows.first { it.isNotEmpty() }
            if (container.downloads.hasQueued) DownloadWorker.start(appContext)
        }
        LyricsLoader.provider = AndroidLyricsProvider(appContext)
        PathLabels.roots = listOf(StorageRoot("Internal storage", Environment.getExternalStorageDirectory().absolutePath, false))
    }
}

val Context.appContainer: AppGraph
    get() = (applicationContext as MusicApp).container

/** Wikipedia asks API clients to identify themselves. */
private const val BIO_USER_AGENT = "VanityPlayer/Android (https://github.com/NoVanity99/Music-Player)"
