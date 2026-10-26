package com.novanity.musicplayer.desktop

import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.abs
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.novanity.musicplayer.AppGraph
import com.novanity.musicplayer.data.PathLabels
import com.novanity.musicplayer.data.ThemeMode
import com.novanity.musicplayer.data.db.AppDatabase
import com.novanity.musicplayer.data.db.AppDatabase.Companion.configure
import com.novanity.musicplayer.desktop.audio.FfmpegPlayerController
import com.novanity.musicplayer.lyrics.LyricsLoader
import com.novanity.musicplayer.update.UpdateManager
import com.novanity.musicplayer.platform.LocalPlatform
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
import org.jetbrains.skia.Image
import java.awt.Dimension
import java.io.File

/** Everything the desktop app needs, built once at startup. */
class DesktopApp {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val graph = AppGraph(
        databaseFactory = {
            Room.databaseBuilder<AppDatabase>(File(DesktopPaths.dataDir, AppDatabase.FILE_NAME).absolutePath)
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .configure()
                .build()
        },
        storeFactory = { name -> PropertiesStore(File(DesktopPaths.dataDir, "$name.properties")) },
        librarySource = FileLibrarySource(File(DesktopPaths.cacheDir, "library.json")),
        appScope = scope,
    )
    val library = LibraryViewModel(graph.musicRepository, graph.playlistRepository, graph.historyRepository, graph.favoritesRepository, graph.settings)
    val player = PlayerViewModel(FfmpegPlayerController(graph, scope) { message -> library.notify(message) }, graph.settings)
    val rootUi = RootUiState()

    /** Set by main(): closes the window so the updater can replace the app's files. */
    var exitApp: () -> Unit = {}
    val updates = UpdateManager(DesktopUpdatePlatform { exitApp() }, graph.settings, scope)

    init {
        ArtworkLoader.provider = DesktopArtworkProvider()
        LyricsLoader.provider = DesktopLyricsProvider()
        PathLabels.roots = desktopStorageRoots()
        updates.checkOnLaunch()
    }

    /** Discord Rich Presence; the client ID is baked in at build time (see gradle.properties). */
    private val presence = System.getProperty("discord.clientId")?.takeIf { it.isNotBlank() }?.let(::DiscordPresence)

    init {
        presence?.let(::showPresence)
    }

    private fun showPresence(presence: DiscordPresence) = scope.launch {
        var shown: PresenceActivity? = null
        combine(graph.settings.discordPresence, player.state, player.positionMs, player.speed) { enabled, s, pos, speed ->
            val entry = s.current
            if (!enabled || entry == null || !s.isPlaying) return@combine null // cleared while paused or stopped
            val rate = speed.coerceAtLeast(0.1f)
            val start = System.currentTimeMillis() - (pos / rate).toLong()
            PresenceActivity(
                title = entry.title,
                artist = entry.artist,
                album = entry.songId?.let { graph.musicRepository.songsById[it]?.album }.orEmpty(),
                startMs = start,
                endMs = if (s.durationMs > 0) start + (s.durationMs / rate).toLong() else null,
            )
        }.collect { next ->
            // The position ticks constantly; only resend for a new song, play/pause, or a real seek.
            val same = next != null && shown != null && next.copy(startMs = 0, endMs = null) == shown!!.copy(startMs = 0, endMs = null) &&
                abs(next.startMs - shown!!.startMs) < 2_000
            if (!same) {
                shown = next
                presence.update(next)
            }
        }
    }

    /** Stops playback and clears the Discord status; called before the app exits. */
    fun shutdown() {
        player.shutdown()
        presence?.close()
    }

    /** Keyboard shortcuts for the whole window (only keys the focused control didn't use). */
    fun onKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val ctrl = event.isCtrlPressed
        val shift = event.isShiftPressed
        when (event.key) {
            Key.Spacebar, Key.MediaPlayPause -> if (!MediaKeys.active || event.key == Key.Spacebar) player.togglePlayPause()
            Key.DirectionRight -> if (ctrl) player.nextTrack() else player.seekBy(if (shift) 30_000 else 5_000)
            Key.DirectionLeft -> if (ctrl) player.previousTrack() else player.seekBy(if (shift) -30_000 else -5_000)
            Key.DirectionUp -> player.changeVolume(+0.05f)
            Key.DirectionDown -> player.changeVolume(-0.05f)
            Key.M -> player.toggleMute()
            Key.S -> if (!ctrl) player.toggleShuffle() else return false
            Key.R -> player.cycleRepeat()
            Key.L -> library.currentLikeToggle(player)
            Key.F -> if (ctrl) library.selectTab(Tab.SEARCH) else return false
            Key.MediaNext -> if (!MediaKeys.active) player.nextTrack()
            Key.MediaPrevious -> if (!MediaKeys.active) player.previous()
            Key.Slash, Key.F1 -> rootUi.showShortcuts = true
            Key.Escape -> when {
                rootUi.showShortcuts -> rootUi.showShortcuts = false
                rootUi.nowPlayingOpen -> rootUi.nowPlayingOpen = false
                else -> return library.back()
            }
            else -> return false
        }
        return true
    }
}

private fun loadIcon(): BitmapPainter? = runCatching {
    val bytes = DesktopApp::class.java.getResourceAsStream("/icon.png")!!.readBytes()
    BitmapPainter(Image.makeFromEncoded(bytes).toComposeImageBitmap())
}.getOrNull()

fun main() {
    application {
        val app = remember { DesktopApp() }
        val icon = remember { loadIcon() }
        app.exitApp = {
            app.shutdown()
            exitApplication()
        }
        val windowState = rememberWindowState(size = DpSize(1280.dp, 820.dp), position = WindowPosition(Alignment.Center))

        DisposableEffect(Unit) {
            MediaKeys.start(
                onPlayPause = { app.player.togglePlayPause() },
                onNext = { app.player.nextTrack() },
                onPrevious = { app.player.previous() },
                onStop = { app.player.pause() },
            )
            onDispose { MediaKeys.stop() }
        }

        Window(
            onCloseRequest = {
                app.shutdown()
                exitApplication()
            },
            state = windowState,
            title = "Music Player",
            icon = icon,
            onKeyEvent = app::onKey,
        ) {
            window.minimumSize = Dimension(400, 640)
            val platform = remember { DesktopPlatformUi(window) }
            val themeMode by app.graph.settings.themeMode.collectAsStateWithLifecycle()
            val dark = when (themeMode) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            CompositionLocalProvider(LocalPlatform provides platform) {
                MusicPlayerTheme(darkTheme = dark, dynamicColor = false) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        MusicPlayerRoot(app.library, app.player, app.rootUi, app.updates)
                    }
                }
            }
        }
    }
}
