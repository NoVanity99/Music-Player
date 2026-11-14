package com.novanity.musicplayer.desktop

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.novanity.musicplayer.AppGraph
import com.novanity.musicplayer.data.CloudStatus
import com.novanity.musicplayer.data.DownloadState
import com.novanity.musicplayer.data.DownloadsRepository
import com.novanity.musicplayer.data.HttpText
import com.novanity.musicplayer.data.SongSource
import com.novanity.musicplayer.data.db.AppDatabase
import com.novanity.musicplayer.data.db.AppDatabase.Companion.configure
import com.novanity.musicplayer.desktop.audio.FfmpegPlayerController
import com.novanity.musicplayer.updatecore.UpdateCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The whole cloud flow against a running Worker (`npm run dev` in /cloud with test songs uploaded):
 * `CLOUD_TEST_URL=http://127.0.0.1:8787 CLOUD_TEST_KEY=dev-key ./gradlew :desktopApp:test --tests '*CloudFlowTest*'`
 */
class CloudFlowTest {
    private fun waitFor(what: String, timeoutMs: Long = 20_000, check: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (check()) return
            Thread.sleep(50)
        }
        error("Timed out waiting for: $what")
    }

    @Test
    fun connectStreamSeekDownloadAndPlayOffline() {
        val url = System.getenv("CLOUD_TEST_URL")
        assumeTrue("CLOUD_TEST_URL not set", !url.isNullOrBlank())
        val key = System.getenv("CLOUD_TEST_KEY") ?: "dev-key"
        val dir = Files.createTempDirectory("cloudflow").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var offline = false
        val errors = mutableListOf<String>()
        val graph = AppGraph(
            databaseFactory = {
                Room.databaseBuilder<AppDatabase>(File(dir, "music.db").absolutePath)
                    .setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).configure().build()
            },
            storeFactory = { name -> PropertiesStore(File(dir, "$name.properties")) },
            librarySource = FileLibrarySource(File(dir, "library.json")),
            appScope = scope,
            httpGet = { u, headers ->
                if (offline) throw java.io.IOException("offline")
                val (status, body) = UpdateCore.getText(u, "VanityPlayer-test", 32_000_000, headers)
                HttpText(status, body)
            },
            cloudFiles = DesktopCloudFiles(dir),
        )
        graph.downloads.startRunner = { scope.launch { graph.downloads.processQueue() } }
        val cloud = graph.cloud
        assertEquals(CloudStatus.NotConfigured, cloud.status.value)
        runBlocking { graph.musicRepository.refresh() }

        // A wrong key is reported as such, and nothing is listed.
        cloud.connect(url!!, "wrong-key")
        waitFor("bad key error") { cloud.status.value is CloudStatus.Error }
        assertTrue("access key" in (cloud.status.value as CloudStatus.Error).message)
        assertTrue(cloud.songs.value.isEmpty())

        // The right key: cloud songs join the library.
        cloud.connect("$url/", key)
        waitFor("online") { cloud.status.value is CloudStatus.Online }
        waitFor("songs merged") { graph.musicRepository.songs.value.orEmpty().any { it.source == SongSource.CLOUD } }
        val songs = graph.musicRepository.songs.value!!
        println("Cloud songs: " + songs.map { "${it.title} (${it.durationMs / 1000}s)" })
        assertTrue(songs.all { it.source == SongSource.CLOUD && it.id < 0 && it.uri.startsWith(url) })
        val song = songs.first { it.coverId != null }

        // The cover comes down with the key and is kept on disk.
        val cover = runBlocking { cloud.coverFile(song.coverId!!) }
        assertTrue(File(assertNotNull(cover)).length() > 100)

        // Stream it, then skip ahead: FFmpeg has to make a Range request with the key.
        val player = FfmpegPlayerController(graph, scope) { errors += it }
        player.setQueue(listOf(song), 0, shuffle = false)
        waitFor("stream playing") { player.state.value.isPlaying && player.currentPositionMs() > 1_000 }
        assertEquals(song.durationMs / 1000, player.state.value.durationMs / 1000)
        player.seekTo(25_000)
        waitFor("playing after seeking to 0:25") { player.currentPositionMs() in 26_000..32_000 }
        player.seekTo(3_000)
        waitFor("playing after seeking back to 0:03") { player.currentPositionMs() in 4_000..9_000 }
        player.pause()
        assertTrue(errors.isEmpty(), "errors: $errors")

        // Download it.
        graph.downloads.enqueue(listOf(song))
        waitFor("download finished") { graph.downloads.states.value[song.cloudId] == DownloadState.Done }
        waitFor("song marked downloaded") { graph.musicRepository.songsById[song.id]?.source == SongSource.DOWNLOADED }
        val local = graph.musicRepository.songsById.getValue(song.id)
        assertTrue(File(local.uri).isFile && File(local.uri).length() == song.sizeBytes, "file ${local.uri}")
        assertEquals(song.sizeBytes, graph.downloads.storageUsed.value)
        assertTrue(File(local.uri).canonicalPath.startsWith(dir.canonicalPath), "saved in the app's own folder")

        // "Airplane mode": only the download is listed, and it plays from the file.
        offline = true
        runBlocking { cloud.refresh() }
        assertIs<CloudStatus.Offline>(cloud.status.value)
        waitFor("only downloads listed offline") { graph.musicRepository.songs.value!!.map { it.id } == listOf(song.id) }
        player.setQueue(listOf(graph.musicRepository.songsById.getValue(song.id)), 0, shuffle = false)
        waitFor("offline playback") { player.state.value.isPlaying && player.currentPositionMs() > 1_000 }
        player.pause()
        assertTrue(errors.isEmpty(), "errors: $errors")

        // Back online, a second song: cancel it mid-queue, then delete the first download.
        offline = false
        runBlocking { cloud.refresh() }
        waitFor("online again") { graph.musicRepository.songs.value!!.size == songs.size }
        val other = graph.musicRepository.songs.value!!.first { it.source == SongSource.CLOUD }
        graph.downloads.enqueue(listOf(other))
        graph.downloads.cancel(other.cloudId!!)
        graph.downloads.delete(song.cloudId!!)
        waitFor("download deleted") { graph.downloads.rows.value.none { it.cloudId == song.cloudId } && !File(local.uri).exists() }
        waitFor("back to streaming") { graph.musicRepository.songsById[song.id]?.source == SongSource.CLOUD }
        // Whether the cancel won the race or not, the queue ends up consistent.
        runBlocking { assertEquals(DownloadsRepository.Outcome.FINISHED, graph.downloads.processQueue()) }
        player.release()
        dir.deleteRecursively()
    }
}
