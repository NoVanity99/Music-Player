package com.novanity.musicplayer.desktop

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.novanity.musicplayer.AppGraph
import com.novanity.musicplayer.data.Song
import com.novanity.musicplayer.data.db.AppDatabase
import com.novanity.musicplayer.data.db.AppDatabase.Companion.configure
import com.novanity.musicplayer.desktop.audio.FfmpegPlayerController
import com.novanity.musicplayer.playback.RepeatMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EngineTest {
    private lateinit var dir: File
    private lateinit var graph: AppGraph
    private lateinit var songs: List<Song>
    private val errors = mutableListOf<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("engine").toFile()
        val music = File(dir, "Music/Band").apply { mkdirs() }
        writeTestTrack(File(music, "01 One.mp3"), 34.0, 440.0, "One", "The Band", "Tests", 1)
        writeTestTrack(File(music, "02 Two.flac"), 33.0, 550.0, "Two", "The Band", "Tests", 2)
        writeTestTrack(File(music, "03 Three.opus"), 32.0, 660.0, "Three", "The Band", "Tests", 3)
        File(music, "04 Broken.mp3").writeBytes(ByteArray(4096) { (it * 31).toByte() }) // not audio
        File(music, "short.wav").let { writeTestTrack(it, 3.0, 300.0, "Jingle", "X", "Y", 1) } // < 30 s: skipped

        graph = AppGraph(
            databaseFactory = {
                Room.databaseBuilder<AppDatabase>(File(dir, "music.db").absolutePath)
                    .setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).configure().build()
            },
            storeFactory = { name -> PropertiesStore(File(dir, "$name.properties")) },
            librarySource = FileLibrarySource(File(dir, "library.json")),
            appScope = scope,
        )
        graph.settings.setIncludedFolders(setOf(DesktopPaths.normalize(File(dir, "Music"))))
        songs = runBlocking { graph.musicRepository.refresh() }.sortedBy { it.track }
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun scannerReadsTagsAndSkipsShortAndBrokenFiles() {
        assertEquals(listOf("One", "Two", "Three"), songs.map { it.title })
        assertTrue(songs.all { it.artist == "The Band" && it.album == "Tests" })
        assertEquals(1, songs.map { it.albumId }.distinct().size, "same album groups together")
        assertTrue(songs[0].durationMs in 33_000..35_000, "duration ${songs[0].durationMs}")
        // A rescan is served from the cache and keeps ids stable.
        val again = runBlocking { graph.musicRepository.refresh() }.sortedBy { it.track }
        assertEquals(songs.map { it.id }, again.map { it.id })
    }

    private fun waitFor(timeoutMs: Long = 15_000, what: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (check()) return
            Thread.sleep(50)
        }
        error("Timed out waiting for: $what")
    }

    @Test
    fun playsSeeksAdvancesCrossfadesAndSkipsBrokenFiles() {
        val c = FfmpegPlayerController(graph, scope) { errors += it }
        try {
            val broken = songs[0].copy(id = 999, title = "Broken", uri = File(dir, "Music/Band/04 Broken.mp3").path)
            c.setQueue(listOf(songs[0], broken, songs[1], songs[2]), 0, shuffle = false)
            waitFor(what = "playing") { c.state.value.isPlaying && c.currentPositionMs() > 300 }
            assertEquals("One", c.state.value.current?.title)
            assertEquals(listOf("Broken", "Two", "Three"), c.upNext.value.map { it.title })

            // Real-time playback: position moves forward roughly with the clock.
            val p1 = c.currentPositionMs(); Thread.sleep(600); val p2 = c.currentPositionMs()
            assertTrue(p2 - p1 in 300..1200, "position advanced ${p2 - p1} ms")

            c.pause()
            waitFor(what = "paused") { !c.state.value.isPlaying }
            val paused = c.currentPositionMs(); Thread.sleep(400)
            assertTrue(c.currentPositionMs() - paused < 100, "frozen while paused")
            c.togglePlayPause()

            // Seek near the end: it advances, skips the broken file with a message, lands on "Two".
            c.seekTo(33_000)
            waitFor(what = "skip to Two") { c.state.value.current?.title == "Two" }
            assertTrue(errors.any { "Broken" in it }, "error reported: $errors")
            assertTrue(c.state.value.isPlaying)

            // Crossfade: with 2 s overlap, the switch to "Three" happens before "Two" ends.
            c.setCrossfadeMs(2_000)
            c.seekTo(29_500)
            waitFor(what = "crossfade into Three") { c.state.value.current?.title == "Three" }
            assertTrue(c.currentPositionMs() < 2_500, "new track started from the top")

            // End of queue with repeat off: stops.
            c.seekTo(31_000)
            waitFor(what = "stopped at end") { !c.state.value.isPlaying }

            // Repeat all wraps around; previousTrack goes back.
            c.setRepeatMode(RepeatMode.ALL)
            c.nextTrack()
            waitFor(what = "wrapped to One") { c.state.value.current?.title == "One" }
            c.previousTrack()
            waitFor(what = "back to Three") { c.state.value.current?.title == "Three" }
        } finally {
            c.release()
        }
    }

    @Test
    fun shuffleKeepsCurrentFirstAndPlayNextIsNext() {
        val c = FfmpegPlayerController(graph, scope) { errors += it }
        try {
            c.setQueue(songs, 1, shuffle = true)
            waitFor(what = "start") { c.state.value.current?.title == "Two" }
            assertEquals(2, c.upNext.value.size)
            c.playNext(listOf(songs[0]))
            waitFor(what = "queued") { c.upNext.value.size == 3 }
            assertEquals("One", c.upNext.value.first().title, "play next works in shuffle too")
            c.removeQueueIndex(c.upNext.value.first().index)
            waitFor(what = "removed") { c.upNext.value.size == 2 }
        } finally {
            c.release()
        }
    }
}
