package com.novanity.musicplayer.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.novanity.musicplayer.data.Song
import com.novanity.musicplayer.lyrics.LyricsLoader
import com.novanity.musicplayer.lyrics.LyricsResult
import com.novanity.musicplayer.platform.LocalPlatform
import com.novanity.musicplayer.ui.PanelHeader
import com.novanity.musicplayer.ui.PlayerViewModel
import com.novanity.musicplayer.ui.components.EmptyState

/** Desktop side panel version. */
@Composable
fun LyricsPanel(song: Song?, player: PlayerViewModel, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        PanelHeader("Lyrics", onClose)
        LyricsView(song, player, Modifier.weight(1f), MaterialTheme.colorScheme.onSurface, MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Lyrics for [song]. Synced (LRC) lyrics highlight and auto-scroll to the current line, and tapping
 * a line seeks there.
 */
@Composable
fun LyricsView(song: Song?, player: PlayerViewModel, modifier: Modifier, activeColor: Color, inactiveColor: Color) {
    var result by remember(song?.id) { mutableStateOf(song?.let(LyricsLoader::cached)) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(song?.id, reload) { if (song != null && (result == null || reload > 0)) result = LyricsLoader.load(song) }
    val platform = LocalPlatform.current
    val grantAccess = platform.rememberLyricsAccessRequest { LyricsLoader.invalidate(); reload++ }

    Box(modifier) {
        when (val r = result) {
            null -> if (song != null) CircularProgressIndicator(Modifier.align(Alignment.Center))
            LyricsResult.None -> EmptyState(
                Icons.Rounded.Lyrics,
                "No lyrics",
                "Put a .lrc file with the same name next to the song (for example “Song.lrc”), or embed lyrics in its tags.",
            )
            is LyricsResult.NeedsAccess -> EmptyState(
                Icons.Rounded.Lyrics,
                "Allow access to lyrics files",
                "Android only lets the app read .lrc files in folders you approve. Pick your music folder once and lyrics will show up.",
                action = { Button(onClick = { grantAccess?.invoke(r.folder) }) { Text("Choose folder") } },
            )
            is LyricsResult.Found -> {
                val lyrics = r.lyrics
                val position by player.positionMs.collectAsStateWithLifecycle()
                val currentIndex = lyrics.indexAt(position + 250)
                val listState = rememberLazyListState()
                LaunchedEffect(currentIndex) {
                    if (currentIndex >= 0) listState.animateScrollToItem((currentIndex - 2).coerceAtLeast(0))
                }
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(lyrics.lines) { i, line ->
                        val active = !lyrics.synced || i == currentIndex
                        val color by animateColorAsState(if (active) activeColor else inactiveColor.copy(alpha = 0.32f), label = "lyric")
                        Text(
                            line.text.ifEmpty { "♪" },
                            style = if (lyrics.synced) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge,
                            fontWeight = if (lyrics.synced) FontWeight.Bold else FontWeight.Normal,
                            color = color,
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(if (line.timeMs != null) Modifier.clickable { player.seekTo(line.timeMs) } else Modifier)
                                .padding(vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}
