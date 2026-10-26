package com.novanity.musicplayer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.novanity.musicplayer.data.Song
import com.novanity.musicplayer.data.ThemeMode
import com.novanity.musicplayer.ui.Destination
import com.novanity.musicplayer.ui.LibraryFilter
import com.novanity.musicplayer.ui.LibraryViewModel
import com.novanity.musicplayer.ui.MusicPlayerRoot
import com.novanity.musicplayer.ui.PlayerUiState
import com.novanity.musicplayer.ui.PlayerViewModel
import com.novanity.musicplayer.ui.QueueEntry
import com.novanity.musicplayer.ui.RootUiState
import com.novanity.musicplayer.ui.Tab
import com.novanity.musicplayer.ui.components.ArtworkLoader
import com.novanity.musicplayer.ui.theme.MusicPlayerTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the main screens with a fake library so the design can be reviewed without a device.
 * Run with ./gradlew testDebugUnitTest; images land in app/screenshots/.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = RobolectricDeviceQualifiers.Pixel7)
class ScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val container get() = ApplicationProvider.getApplicationContext<MusicApp>().container

    private data class FakeAlbum(val title: String, val artist: String, val colors: IntArray, val tracks: List<String>)

    private val albums = listOf(
        FakeAlbum("Midnight Drive", "Neon Harbor", intArrayOf(0xFFFF375F.toInt(), 0xFF5B21B6.toInt()), listOf("City Lights", "Overpass", "Afterglow", "Night Swim", "Static Hearts")),
        FakeAlbum("Golden Hour", "Sol Vega", intArrayOf(0xFFFF9F0A.toInt(), 0xFFC2410C.toInt()), listOf("Sunrise Avenue", "Honey", "Slow Burn", "Paper Planes")),
        FakeAlbum("Deep Blue", "Tidal", intArrayOf(0xFF0A84FF.toInt(), 0xFF0B1E4A.toInt()), listOf("Undertow", "Coral", "Pressure", "Lighthouse")),
        FakeAlbum("Evergreen", "The Pines", intArrayOf(0xFF30B158.toInt(), 0xFF0F3D22.toInt()), listOf("Moss", "Timberline", "Campfire Song")),
        FakeAlbum("Velvet", "Nina Rose", intArrayOf(0xFFBF5AF2.toInt(), 0xFF2A0E45.toInt()), listOf("Velvet", "Rosewater", "Silk Road", "Petals")),
        FakeAlbum("Signals", "Kilo Echo", intArrayOf(0xFF64D2FF.toInt(), 0xFF0E7490.toInt()), listOf("Morse", "Radio Silence", "Frequency")),
        FakeAlbum("Analog Dreams", "Neon Harbor", intArrayOf(0xFFFF6482.toInt(), 0xFF1F2937.toInt()), listOf("Tape Hiss", "VHS", "Rewind")),
        FakeAlbum("Desert Bloom", "Sol Vega", intArrayOf(0xFFFFD60A.toInt(), 0xFFB45309.toInt()), listOf("Mirage", "Cactus Flower", "Dune")),
    )

    private val songs: List<Song> by lazy {
        var id = 1L
        albums.flatMapIndexed { a, album ->
            album.tracks.mapIndexed { t, title ->
                val songId = id++
                Song(
                    id = songId,
                    uri = Uri.parse("content://media/external/audio/media/$songId"),
                    title = title,
                    artist = album.artist,
                    album = album.title,
                    albumId = 100L + a,
                    durationMs = 150_000L + (songId * 7_919L % 120_000L),
                    track = t + 1,
                    disc = 0,
                    dateAdded = 1_700_000_000L + a * 86_400L,
                    folderPath = if (a % 3 == 0) "/storage/emulated/0/Music/${album.artist}" else "/storage/emulated/0/Download",
                )
            }
        }
    }

    private fun art(colors: IntArray): Bitmap {
        val size = 512
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), colors[0], colors[1], Shader.TileMode.CLAMP)
        })
        c.drawCircle(size * 0.68f, size * 0.36f, size * 0.26f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(size * 0.68f, size * 0.36f, size * 0.26f, 0x88FFFFFF.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP)
        })
        c.drawCircle(size * 0.3f, size * 0.75f, size * 0.12f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF })
        return bmp
    }

    private fun seed() {
        // No real playback service under Robolectric; the player state is injected below.
        Shadows.shadowOf(ApplicationProvider.getApplicationContext<MusicApp>())
            .declareActionUnbindable("androidx.media3.session.MediaSessionService")
        albums.forEachIndexed { a, album -> ArtworkLoader.putForTest(100L + a, art(album.colors)) }
        container.musicRepository.scanOverride = { songs }
        container.settings.setThemeMode(ThemeMode.DARK)
        container.settings.setIncludedFolders(setOf("/storage/emulated/0/Music", "/storage/emulated/0/Download"))
        runBlocking {
            container.musicRepository.refresh()
            listOf(1L, 6L, 12L, 18L, 22L).forEach { container.favoritesRepository.setLiked(it, true) }
            listOf(3L, 9L, 14L, 20L, 1L, 25L, 17L).forEach { container.historyRepository.record(it) }
            val road = container.playlistRepository.create("Road Trip")
            container.playlistRepository.addSongs(road, listOf(1L, 6L, 10L, 14L, 19L, 23L))
            val focus = container.playlistRepository.create("Late Night Focus")
            container.playlistRepository.addSongs(focus, listOf(12L, 13L, 26L))
        }
    }

    private val library get() = ViewModelProvider(compose.activity)[LibraryViewModel::class.java]
    private val player get() = ViewModelProvider(compose.activity)[PlayerViewModel::class.java]
    private val rootUi = RootUiState()

    private fun settle() {
        compose.waitUntil(10_000) { !library.home.value.loading }
        repeat(6) {
            compose.mainClock.advanceTimeBy(400)
            Thread.sleep(150) // let art colors / Room flows land
            compose.waitForIdle()
        }
    }

    private fun shot(name: String) {
        settle()
        captureScreenRoboImage("screenshots/$name.png")
    }

    private fun showPlaying() {
        val song = songs[5]
        val upNext = songs.drop(6).take(6).mapIndexed { i, s -> QueueEntry(6 + i, "${s.id}/q", s.id, s.title, s.artist) }
        compose.runOnUiThread {
            player.showForTest(
                PlayerUiState(
                    connected = true,
                    current = QueueEntry(5, "${song.id}/q", song.id, song.title, song.artist),
                    isPlaying = true,
                    durationMs = song.durationMs,
                    shuffle = false,
                    repeatMode = Player.REPEAT_MODE_ALL,
                ),
                upNext,
                positionMs = song.durationMs / 3,
                playingFrom = song.album,
            )
        }
    }

    private fun launch(dark: Boolean = true) {
        seed()
        if (!dark) container.settings.setThemeMode(ThemeMode.LIGHT)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MusicPlayerTheme(darkTheme = dark, dynamicColor = false) {
                androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background) {
                    MusicPlayerRoot(container, rootUi)
                }
            }
        }
        settle()
        showPlaying()
    }

    @Test
    fun screens() {
        launch()
        shot("01_home")

        compose.runOnUiThread { library.selectTab(Tab.SEARCH) }
        shot("02_search")
        compose.runOnUiThread { library.setSearchQuery("so") }
        shot("03_search_results")

        compose.runOnUiThread { library.showLibrary(LibraryFilter.PLAYLISTS) }
        shot("04_library_playlists")
        compose.runOnUiThread { library.libraryFilter = LibraryFilter.ALBUMS }
        shot("05_library_albums")
        compose.runOnUiThread { library.libraryFilter = LibraryFilter.SONGS }
        shot("06_library_songs")

        compose.runOnUiThread { library.open(Destination.AlbumDetail(100L)) }
        shot("07_album")
        compose.runOnUiThread { library.open(Destination.ArtistDetail("Sol Vega")) }
        shot("08_artist")
        compose.runOnUiThread { library.open(Destination.LikedSongs) }
        shot("09_liked")
        compose.runOnUiThread { library.open(Destination.PlaylistDetail(1L)) }
        shot("10_playlist")
        compose.runOnUiThread {
            container.settings.setExcludedFolders(setOf("/storage/emulated/0/Music/The Pines"))
            library.open(Destination.Settings)
        }
        shot("11_settings")
        compose.runOnUiThread { container.settings.setExcludedFolders(emptySet()) }

        compose.runOnUiThread { rootUi.nowPlayingOpen = true }
        shot("12_now_playing")
    }

    @Test
    fun firstRunAsksForAFolder() {
        launch()
        compose.runOnUiThread { container.settings.setIncludedFolders(emptySet()) }
        shot("00_choose_folder")
    }

    @Test
    fun lightTheme() {
        launch(dark = false)
        shot("13_home_light")
        compose.runOnUiThread { library.open(Destination.AlbumDetail(102L)) }
        shot("14_album_light")
    }
}
