package com.novanity.musicplayer.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.novanity.musicplayer.platform.LocalPlatform
import com.novanity.musicplayer.ui.components.AlbumArt
import com.novanity.musicplayer.ui.components.rememberArtColor
import com.novanity.musicplayer.ui.theme.MusicPlayerTheme
import java.awt.Toolkit

private val MiniSize = DpSize(420.dp, 112.dp)

/**
 * The mini player: a small always-on-top window with the cover, song, controls and progress.
 * Drag it anywhere; Space plays or pauses; the expand button (or Esc) brings back the full app.
 */
@Composable
fun MiniPlayerWindow(app: DesktopApp, icon: Painter?, onExpand: () -> Unit) {
    val state = rememberWindowState(size = MiniSize, position = bottomRightOfScreen())
    Window(
        onCloseRequest = onExpand,
        state = state,
        title = "Vanity Player",
        icon = icon,
        undecorated = true,
        alwaysOnTop = true,
        resizable = false,
        onKeyEvent = { e ->
            if (e.type != KeyEventType.KeyDown) return@Window false
            when (e.key) {
                Key.Spacebar -> app.player.togglePlayPause()
                Key.Escape -> onExpand()
                else -> return@Window false
            }
            true
        },
    ) {
        val platform = remember { DesktopPlatformUi(window) }
        CompositionLocalProvider(LocalPlatform provides platform) {
            val accent by app.graph.settings.accent.collectAsStateWithLifecycle()
            MusicPlayerTheme(darkTheme = true, dynamicColor = false, accent = accent) {
                WindowDraggableArea {
                    MiniPlayerContent(app, onExpand)
                }
            }
        }
    }
}

@Composable
private fun MiniPlayerContent(app: DesktopApp, onExpand: () -> Unit) {
    val player = app.player
    val ui by player.state.collectAsStateWithLifecycle()
    val position by player.positionMs.collectAsStateWithLifecycle()
    val likedIds by app.library.likedIds.collectAsStateWithLifecycle()
    val songs by app.library.songs.collectAsStateWithLifecycle()
    val song = remember(songs, ui.current?.songId) { ui.current?.songId?.let { app.library.songsById[it] } }
    val tint by rememberArtColor(song, Color(0xFF3A1C2E))
    val liked = song != null && song.id in likedIds

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.horizontalGradient(listOf(tint, Color(0xFF121212)))),
    ) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            AlbumArt(song, Modifier.size(MiniSize.height).padding(10.dp).clip(RoundedCornerShape(8.dp)), shape = RoundedCornerShape(8.dp))
            Column(Modifier.weight(1f).fillMaxHeight().padding(vertical = 12.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(ui.current?.title ?: "Nothing playing", color = Color.White, style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(ui.current?.artist.orEmpty(), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = onExpand, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Rounded.OpenInFull, contentDescription = "Back to the full player", tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { song?.let { app.library.currentLikeToggle(player) } }, enabled = song != null, modifier = Modifier.size(32.dp)) {
                        Icon(if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, contentDescription = if (liked) "Unlike" else "Like",
                            tint = if (liked) MaterialTheme.colorScheme.primary else Color.White, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = player::previous, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous", tint = Color.White)
                    }
                    IconButton(
                        onClick = player::togglePlayPause,
                        modifier = Modifier.size(38.dp).clip(RoundedCornerShape(50)).background(Color.White),
                    ) {
                        Icon(if (ui.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (ui.isPlaying) "Pause" else "Play", tint = Color.Black)
                    }
                    IconButton(onClick = player::nextTrack, enabled = ui.hasNext, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Rounded.SkipNext, contentDescription = "Next", tint = Color.White)
                    }
                    Spacer(Modifier.width(14.dp))
                }
            }
        }
        // Progress along the bottom edge.
        val progress = if (ui.durationMs > 0) (position.toFloat() / ui.durationMs).coerceIn(0f, 1f) else 0f
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.15f))) {
            Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
        }
    }
}

/** Just above the taskbar in the bottom-right corner of the main screen. */
private fun bottomRightOfScreen(): WindowPosition = runCatching {
    val gc = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
    val b = gc.bounds
    val i = Toolkit.getDefaultToolkit().getScreenInsets(gc)
    val x = b.x + b.width - i.right - MiniSize.width.value.toInt() - 16
    val y = b.y + b.height - i.bottom - MiniSize.height.value.toInt() - 16
    WindowPosition(x.dp, y.dp)
}.getOrDefault(WindowPosition(Alignment.BottomEnd))
