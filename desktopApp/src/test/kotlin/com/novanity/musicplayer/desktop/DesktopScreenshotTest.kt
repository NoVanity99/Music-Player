package com.novanity.musicplayer.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Density
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.novanity.musicplayer.AppGraph
import com.novanity.musicplayer.data.PathLabels
import com.novanity.musicplayer.data.Song
import com.novanity.musicplayer.data.db.AppDatabase
import com.novanity.musicplayer.data.db.AppDatabase.Companion.configure
import com.novanity.musicplayer.platform.LibrarySource
import com.novanity.musicplayer.platform.LocalPlatform
import com.novanity.musicplayer.platform.StorageRoot
import com.novanity.musicplayer.playback.PlayerUiState
import com.novanity.musicplayer.playback.QueueEntry
import com.novanity.musicplayer.playback.RepeatMode
import com.novanity.musicplayer.ui.Destination
import com.novanity.musicplayer.ui.LibraryViewModel
import com.novanity.musicplayer.ui.MusicPlayerRoot
import com.novanity.musicplayer.ui.PlayerViewModel
import com.novanity.musicplayer.ui.RootUiState
import com.novanity.musicplayer.ui.Tab
import com.novanity.musicplayer.ui.components.ArtworkLoader
import com.novanity.musicplayer.ui.theme.MusicPlayerTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.nio.file.Files
import javax.swing.SwingUtilities
import kotlin.test.Test

/**
 * Renders the real desktop UI offscreen at several window sizes and Windows scale factors
 * (100/125/150 %). Output: desktopApp/screenshots/.
 */
class DesktopScreenshotTest {
    private val out = File("screenshots").apply { mkdirs() }

    private val albums = listOf(
        Triple("Midnight Drive", "Neon Harbor", intArrayOf(0xFFFF375F.toInt(), 0xFF5B21B6.toInt())) to listOf("City Lights", "Overpass", "Afterglow", "Night Swim", "Static Hearts"),
        Triple("Golden Hour", "Sol Vega", intArrayOf(0xFFFF9F0A.toInt(), 0xFFC2410C.toInt())) to listOf("Sunrise Avenue", "Honey", "Slow Burn", "Paper Planes"),
        Triple("Deep Blue", "Tidal", intArrayOf(0xFF0A84FF.toInt(), 0xFF0B1E4A.toInt())) to listOf("Undertow", "Coral", "Pressure", "Lighthouse"),
        Triple("Evergreen", "The Pines", intArrayOf(0xFF30B158.toInt(), 0xFF0F3D22.toInt())) to listOf("Moss", "Timberline", "Campfire Song"),
        Triple("Velvet", "Nina Rose", intArrayOf(0xFFBF5AF2.toInt(), 0xFF2A0E45.toInt())) to listOf("Velvet", "Rosewater", "Silk Road", "Petals"),
        Triple("Signals", "Kilo Echo", intArrayOf(0xFF64D2FF.toInt(), 0xFF0E7490.toInt())) to listOf("Morse", "Radio Silence", "Frequency"),
    )

    private val songs: List<Song> = run {
        var id = 1L
        albums.flatMapIndexed { a, (meta, tracks) ->
            tracks.mapIndexed { t, title ->
                val sid = id++
                Song(sid, "C:/Music/${meta.second}/$title.flac", title, meta.second, meta.first, 100L + a,
                    150_000L + sid * 7_919L % 120_000L, t + 1, 0, 1_700_000_000L + a * 86_400L,
                    "C:/Users/me/Music/${meta.second}", "C:/Users/me/Music/${meta.second}/$title.flac")
            }
        }
    }

    private fun art(colors: IntArray): androidx.compose.ui.graphics.ImageBitmap {
        val img = java.awt.image.BufferedImage(512, 512, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
        g.paint = java.awt.GradientPaint(0f, 0f, java.awt.Color(colors[0], true), 512f, 512f, java.awt.Color(colors[1], true))
        g.fillRect(0, 0, 512, 512)
        g.paint = java.awt.RadialGradientPaint(350f, 180f, 130f, floatArrayOf(0f, 1f),
            arrayOf(java.awt.Color(255, 255, 255, 136), java.awt.Color(255, 255, 255, 0)))
        g.fillOval(220, 50, 260, 260)
        g.color = java.awt.Color(255, 255, 255, 51)
        g.fillOval(90, 320, 120, 120)
        g.dispose()
        val bytes = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(img, "png", it) }.toByteArray()
        return org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
    }

    private fun graph(dir: File): AppGraph = AppGraph(
        databaseFactory = {
            Room.databaseBuilder<AppDatabase>(File(dir, "music.db").absolutePath)
                .setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).configure().build()
        },
        storeFactory = { name -> PropertiesStore(File(dir, "$name.properties")) },
        librarySource = object : LibrarySource {
            override suspend fun scan(included: Set<String>, excluded: Set<String>) = songs
            override suspend fun indexNewFiles(included: Set<String>, excluded: Set<String>) = false
        },
        appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    )

    class Fixture(val library: LibraryViewModel, val player: PlayerViewModel, val fake: FakePlayer, val ui: RootUiState)

    fun fixtureForDebug() = fixture()

    @androidx.compose.runtime.Composable
    fun Content(f: Fixture) {
        CompositionLocalProvider(LocalPlatform provides DesktopPlatformUi(null)) {
            MusicPlayerTheme(darkTheme = true, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.background) { MusicPlayerRoot(f.library, f.player, f.ui) }
            }
        }
    }

    private fun fixture(): Fixture {
        val dir = Files.createTempDirectory("shots").toFile()
        albums.forEachIndexed { a, (meta, _) -> ArtworkLoader.putForTest(100L + a, art(meta.third)) }
        PathLabels.roots = listOf(StorageRoot("Home", "C:/Users/me", false))
        com.novanity.musicplayer.lyrics.LyricsLoader.provider = com.novanity.musicplayer.lyrics.LyricsProvider {
            com.novanity.musicplayer.lyrics.LyricsResult.Found(com.novanity.musicplayer.lyrics.Lyrics.parse(
                (0 until 24).joinToString("\n") { i -> "[00:${(i * 4).toString().padStart(2, '0')}.00] " + listOf(
                    "Morning light on the avenue", "Coffee steam and a quiet tune", "Every window gold and new",
                    "I keep on walking back to you", "", "Sunrise, sunrise, carry me home",
                )[i % 6] }
            )!!)
        }
        val g = graph(dir)
        g.settings.setIncludedFolders(setOf("C:/Users/me/Music"))
        runBlocking {
            listOf(1L, 6L, 12L).forEach { g.favoritesRepository.setLiked(it, true) }
            listOf(3L, 9L, 14L, 20L, 1L).forEach { g.historyRepository.record(it) }
            val p = g.playlistRepository.create("Road Trip")
            g.playlistRepository.addSongs(p, listOf(1L, 6L, 10L, 14L, 19L))
            g.playlistRepository.create("Late Night Focus").let { g.playlistRepository.addSongs(it, listOf(12L, 13L)) }
        }
        lateinit var f: Fixture
        SwingUtilities.invokeAndWait {
            val lib = LibraryViewModel(g.musicRepository, g.playlistRepository, g.historyRepository, g.favoritesRepository, g.settings)
            val fake = FakePlayer()
            val pvm = PlayerViewModel(fake, g.settings)
            val song = songs[5]
            fake.upNext.value = songs.drop(6).take(8).mapIndexed { i, s -> QueueEntry(6 + i, "${s.id}/q", s.id, s.title, s.artist) }
            fake.state.value = PlayerUiState(true, QueueEntry(5, "${song.id}/q", song.id, song.title, song.artist), true, song.durationMs, false, RepeatMode.ALL, true, true)
            pvm.positionOverride = song.durationMs / 3
            pvm.setPlayingFrom(song.album)
            f = Fixture(lib, pvm, fake, RootUiState())
        }
        return f
    }

    private fun shoot(name: String, widthPx: Int, heightPx: Int, scale: Float, f: Fixture, setup: (Fixture) -> Unit = {}) {
        SwingUtilities.invokeAndWait { setup(f) }
        val scene = ImageComposeScene(widthPx, heightPx, Density(scale)) {
            CompositionLocalProvider(LocalPlatform provides DesktopPlatformUi(null)) {
                MusicPlayerTheme(darkTheme = true, dynamicColor = false) {
                    Surface(color = MaterialTheme.colorScheme.background) { MusicPlayerRoot(f.library, f.player, f.ui) }
                }
            }
        }
        var t = 0L
        repeat(45) {
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            scene.render(t)
            t += 50_000_000L
            Thread.sleep(40)
            SwingUtilities.invokeAndWait { }
        }
        val img = scene.render(t + 2_000_000_000L)
        File(out, "$name.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
    }

    @Test
    fun screens() {
        val f = fixture()
        shoot("01_home_1440x900_100", 1440, 900, 1f, f)
        shoot("02_home_1600x1000_125", 1600, 1000, 1.25f, f)
        shoot("03_home_1920x1080_150", 1920, 1080, 1.5f, f)
        shoot("04_album_1440x900_100", 1440, 900, 1f, f) { it.library.open(Destination.AlbumDetail(100L)) }
        shoot("05_lyrics_panel_1440x900", 1440, 900, 1f, f) { it.ui.sidePanel = com.novanity.musicplayer.ui.SidePanel.LYRICS }
        shoot("06_artist_1000x760_100", 1000, 760, 1f, f) { it.ui.sidePanel = com.novanity.musicplayer.ui.SidePanel.QUEUE; it.library.open(Destination.ArtistDetail("Sol Vega")) }
        shoot("08_library_albums_1280x800", 1280, 800, 1f, f) {
            it.library.selectTab(Tab.LIBRARY); it.library.libraryFilter = com.novanity.musicplayer.ui.LibraryFilter.ALBUMS
        }
        shoot("09_settings_1280x800", 1280, 800, 1f, f) { it.library.open(Destination.Settings) }
        shoot("10_now_playing_1440x900", 1440, 900, 1f, f) { it.ui.nowPlayingOpen = true }
        shoot("11_narrow_480x900", 480, 900, 1f, f) { it.ui.nowPlayingOpen = false; it.library.selectTab(Tab.HOME); it.library.backStack.clear() }
        shoot("12_shortcuts", 1280, 800, 1f, f) { it.ui.showShortcuts = true }
    }
}
