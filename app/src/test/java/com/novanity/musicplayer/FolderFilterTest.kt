package com.novanity.musicplayer

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.novanity.musicplayer.platform.FolderSync
import com.novanity.musicplayer.data.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class FolderFilterTest {
    private val app get() = ApplicationProvider.getApplicationContext<MusicApp>()

    private fun song(id: Long, folder: String) = Song(
        id = id, uri = "", title = "t$id", artist = "a", album = "b", albumId = 1, durationMs = 60_000,
        track = 1, disc = 0, dateAdded = 0, folderPath = folder,
    )

    @Test
    fun libraryIsEmptyUntilAFolderIsChosen_thenHonorsIgnoredFolders() = runBlocking {
        // Own graph on a background scope so nothing here waits on the (blocked) main thread.
        val c = AppGraph(
            databaseFactory = { error("unused") },
            storeFactory = { name -> com.novanity.musicplayer.platform.SharedPrefsStore(app, "test-$name") },
            librarySource = com.novanity.musicplayer.platform.AndroidLibrarySource(app),
            appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
        )
        c.musicRepository.scanOverride = {
            listOf(
                song(1, "/sd/Music"),
                song(2, "/sd/Music/Rock"),
                song(3, "/sd/Music/Voice Memos"),
                song(4, "/sd/Download"),
                song(5, "/sd/MusicVideos"), // prefix of "/sd/Music" but not inside it
            )
        }
        c.musicRepository.refresh()
        assertEquals(emptyList<Long>(), c.musicRepository.songs.value!!.map { it.id })

        c.settings.setIncludedFolders(setOf("/sd/Music"))
        c.musicRepository.refresh()
        assertEquals(listOf(1L, 2L, 3L), c.musicRepository.songs.value!!.map { it.id })

        c.settings.setExcludedFolders(setOf("/sd/Music/Voice Memos"))
        c.musicRepository.refresh()
        assertEquals(listOf(1L, 2L), c.musicRepository.songs.value!!.map { it.id })
    }

    @Test
    fun folderCheckFindsNewAudioButSkipsIgnoredHiddenAndNomedia() = runBlocking {
        val root = Files.createTempDirectory("music").toFile()
        fun touch(rel: String) = File(root, rel).apply { parentFile!!.mkdirs(); writeText("x") }
        touch("song.mp3")
        touch("Rock/track.FLAC")
        touch("Rock/cover.jpg")
        touch("Memos/memo.m4a")
        touch(".hidden/secret.mp3")
        touch("Ringtones/.nomedia")
        touch("Ringtones/ring.ogg")

        val found = FolderSync.findUnindexed(app, setOf(root.path), setOf(File(root, "Memos").path))
            .map { it.removePrefix(root.path + "/") }
            .sorted()
        assertEquals(listOf("Rock/track.FLAC", "song.mp3"), found)
        root.deleteRecursively()
        Unit
    }
}
