package com.novanity.musicplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.novanity.musicplayer.data.Song
import com.novanity.musicplayer.playback.PlayerUiState
import com.novanity.musicplayer.playback.RepeatMode
import com.novanity.musicplayer.ui.components.AlbumArt
import com.novanity.musicplayer.ui.components.GradientArt
import com.novanity.musicplayer.ui.components.LikedArt
import com.novanity.musicplayer.ui.components.PlaylistArt
import com.novanity.musicplayer.ui.components.RecentGradient
import com.novanity.musicplayer.ui.components.SlimSlider
import com.novanity.musicplayer.ui.components.ShuffleGradient
import com.novanity.musicplayer.ui.components.TextInputDialog
import com.novanity.musicplayer.ui.components.rememberArtColor
import com.novanity.musicplayer.ui.components.songCount
import com.novanity.musicplayer.ui.screens.LyricsPanel
import com.novanity.musicplayer.ui.screens.QueueSheetContent
import com.novanity.musicplayer.ui.screens.SeekBar

/** Width from which the desktop-style layout (sidebar + player bar) replaces the phone layout. */
val WideLayoutMinWidth: Dp = 840.dp
private val SidePanelMinWidth: Dp = 1180.dp

/**
 * Desktop / large-window layout: sidebar on the left, content in the middle, optional queue or
 * lyrics panel on the right, and a full-width now-playing bar at the bottom.
 */
@Composable
fun WideLayout(
    library: LibraryViewModel,
    player: PlayerViewModel,
    actions: SongActions,
    uiState: RootUiState,
    windowWidth: Dp,
    content: @Composable (PaddingValues) -> Unit,
) {
    val state by player.state.collectAsStateWithLifecycle()
    val songs by library.songs.collectAsStateWithLifecycle()
    val songsById = remember(songs) { library.songsById }
    val currentSong = state.current?.songId?.let(songsById::get)
    val showPanel = uiState.sidePanel != null && windowWidth >= SidePanelMinWidth && state.hasMedia
    val gap = 8.dp
    val panelShape = RoundedCornerShape(12.dp)

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLowest)) {
        Row(Modifier.weight(1f).padding(start = gap, end = gap, top = gap)) {
            Sidebar(
                library = library,
                actions = actions,
                uiState = uiState,
                modifier = Modifier.width(if (windowWidth >= 1000.dp) 260.dp else 220.dp).fillMaxHeight().clip(panelShape),
            )
            Spacer(Modifier.width(gap))
            Box(Modifier.weight(1f).fillMaxHeight().clip(panelShape).background(MaterialTheme.colorScheme.background)) {
                content(PaddingValues(0.dp))
            }
            if (showPanel) {
                Spacer(Modifier.width(gap))
                Box(
                    Modifier.width(340.dp).fillMaxHeight().clip(panelShape).background(MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    when (uiState.sidePanel) {
                        SidePanel.LYRICS -> LyricsPanel(currentSong, player, onClose = { uiState.sidePanel = null })
                        else -> Column {
                            PanelHeader("Queue") { uiState.sidePanel = null }
                            QueueSheetContent(state, currentSong, songsById, player)
                        }
                    }
                }
            }
        }
        NowPlayingBar(state, currentSong, player, actions, uiState, showPanelToggles = windowWidth >= SidePanelMinWidth)
    }

    if (uiState.showShortcuts) ShortcutsDialog { uiState.showShortcuts = false }
}

@Composable
internal fun PanelHeader(title: String, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = "Close panel") }
    }
}

// ---------------- Sidebar ----------------

@Composable
private fun Sidebar(library: LibraryViewModel, actions: SongActions, uiState: RootUiState, modifier: Modifier) {
    val playlists by library.playlists.collectAsStateWithLifecycle()
    val liked by library.likedSongs.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    val top = library.backStack.lastOrNull()

    Column(modifier.background(MaterialTheme.colorScheme.surfaceContainerLow).padding(vertical = 12.dp)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            GradientArt(ShuffleGradient, Icons.Rounded.MusicNote, Modifier.size(32.dp), RoundedCornerShape(9.dp), iconFraction = 0.6f)
            Spacer(Modifier.width(10.dp))
            Text("Music", style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(8.dp))
        NavRow(Icons.Rounded.Home, Icons.Outlined.Home, "Home", library.tab == Tab.HOME && top == null) { library.selectTab(Tab.HOME); library.backStack.clear() }
        NavRow(Icons.Rounded.Search, Icons.Rounded.Search, "Search", library.tab == Tab.SEARCH && top == null) { library.selectTab(Tab.SEARCH); library.backStack.clear() }
        NavRow(Icons.Rounded.LibraryMusic, Icons.Outlined.LibraryMusic, "Your Library", library.tab == Tab.LIBRARY && top == null) { library.selectTab(Tab.LIBRARY); library.backStack.clear() }

        Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 20.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("PLAYLISTS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            IconButton(onClick = { creating = true }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Rounded.Add, contentDescription = "New playlist", modifier = Modifier.size(18.dp))
            }
        }
        LazyColumn(Modifier.weight(1f)) {
            item {
                SidebarItem(
                    art = { LikedArt(Modifier.size(40.dp)) },
                    title = "Liked Songs",
                    subtitle = songCount(liked.size),
                    selected = top == Destination.LikedSongs,
                ) { library.open(Destination.LikedSongs) }
            }
            item {
                SidebarItem(
                    art = { GradientArt(RecentGradient, Icons.Rounded.History, Modifier.size(40.dp)) },
                    title = "Recently played",
                    subtitle = null,
                    selected = top == Destination.RecentlyPlayed,
                ) { library.open(Destination.RecentlyPlayed) }
            }
            items(playlists, key = { it.id }) { p ->
                SidebarItem(
                    art = { PlaylistArt(p.covers, Modifier.size(40.dp)) },
                    title = p.name,
                    subtitle = songCount(p.songCount),
                    selected = top == Destination.PlaylistDetail(p.id),
                ) { library.open(Destination.PlaylistDetail(p.id)) }
            }
        }
        NavRow(Icons.Rounded.Keyboard, Icons.Rounded.Keyboard, "Shortcuts", false) { uiState.showShortcuts = true }
        NavRow(Icons.Rounded.Settings, Icons.Rounded.Settings, "Settings", top == Destination.Settings) { library.open(Destination.Settings) }
    }

    if (creating) {
        TextInputDialog("New playlist", "", "Create", onConfirm = {
            library.createPlaylist(it)
            creating = false
        }, onDismiss = { creating = false })
    }
}

@Composable
private fun NavRow(selectedIcon: ImageVector, icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (selected) selectedIcon else icon,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(14.dp))
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SidebarItem(art: @Composable () -> Unit, title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        art()
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

// ---------------- Now playing bar ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Tip(text: String, content: @Composable () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(text) } },
        state = rememberTooltipState(),
    ) { content() }
}

@Composable
private fun NowPlayingBar(
    state: PlayerUiState,
    song: Song?,
    player: PlayerViewModel,
    actions: SongActions,
    uiState: RootUiState,
    showPanelToggles: Boolean,
) {
    val likedIds by actions.library.likedIds.collectAsStateWithLifecycle()
    val volume by player.volume.collectAsStateWithLifecycle()
    val current = state.current
    val tint by rememberArtColor(song, MaterialTheme.colorScheme.surfaceContainerLow)
    val barColor = lerp(MaterialTheme.colorScheme.surfaceContainerLowest, tint, 0.18f)
    val onBar = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary

    Row(
        Modifier.fillMaxWidth().height(92.dp).background(barColor).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left: what's playing
        Row(Modifier.weight(0.3f).widthIn(min = 200.dp), verticalAlignment = Alignment.CenterVertically) {
            if (current != null) {
                AlbumArt(
                    song,
                    Modifier.size(60.dp).clip(RoundedCornerShape(8.dp)).clickable { uiState.nowPlayingOpen = true },
                    shape = RoundedCornerShape(8.dp),
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f, fill = false)) {
                    Text(current.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { song?.let { actions.library.open(Destination.AlbumDetail(it.albumId)) } })
                    Text(current.artist, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { song?.let { actions.library.open(Destination.ArtistDetail(it.artist)) } })
                }
                if (song != null) {
                    val liked = song.id in likedIds
                    Tip(if (liked) "Remove from Liked Songs (L)" else "Save to Liked Songs (L)") {
                        IconButton(onClick = { actions.toggleLike(song, liked) }) {
                            Icon(if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, contentDescription = null,
                                tint = if (liked) accent else muted)
                        }
                    }
                }
            } else {
                Text("Nothing playing", style = MaterialTheme.typography.bodyMedium, color = muted)
            }
        }

        // Center: transport + seek
        Column(Modifier.weight(0.4f).widthIn(min = 320.dp, max = 720.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Tip("Shuffle (S)") {
                    IconButton(onClick = player::toggleShuffle) {
                        Icon(Icons.Rounded.Shuffle, contentDescription = "Shuffle", tint = if (state.shuffle) accent else muted)
                    }
                }
                Tip("Previous (Ctrl+←)") {
                    IconButton(onClick = player::previous, enabled = current != null) { Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous") }
                }
                Tip(if (state.isPlaying) "Pause (Space)" else "Play (Space)") {
                    Surface(onClick = player::togglePlayPause, enabled = current != null, shape = CircleShape, color = onBar, contentColor = MaterialTheme.colorScheme.background, modifier = Modifier.size(40.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = if (state.isPlaying) "Pause" else "Play")
                        }
                    }
                }
                Tip("Next (Ctrl+→)") {
                    IconButton(onClick = player::nextTrack, enabled = state.hasNext) { Icon(Icons.Rounded.SkipNext, contentDescription = "Next") }
                }
                Tip("Repeat (R)") {
                    IconButton(onClick = player::cycleRepeat) {
                        Icon(if (state.repeatMode == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, contentDescription = "Repeat",
                            tint = if (state.repeatMode == RepeatMode.OFF) muted else accent)
                    }
                }
            }
            val position by player.positionMs.collectAsStateWithLifecycle()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(com.novanity.musicplayer.ui.components.formatDuration(position), style = MaterialTheme.typography.labelSmall, color = muted)
                Box(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    SeekBar(state.durationMs, player, activeColor = onBar, trackColor = onBar.copy(alpha = 0.2f), labelColor = muted, compact = true)
                }
                Text(com.novanity.musicplayer.ui.components.formatDuration(state.durationMs), style = MaterialTheme.typography.labelSmall, color = muted)
            }
        }

        // Right: panels + volume
        Row(Modifier.weight(0.3f).widthIn(min = 200.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (showPanelToggles) {
                Tip("Lyrics") {
                    IconButton(onClick = { uiState.sidePanel = if (uiState.sidePanel == SidePanel.LYRICS) null else SidePanel.LYRICS }) {
                        Icon(Icons.Rounded.Lyrics, contentDescription = "Lyrics", tint = if (uiState.sidePanel == SidePanel.LYRICS) accent else muted)
                    }
                }
                Tip("Queue") {
                    IconButton(onClick = { uiState.sidePanel = if (uiState.sidePanel == SidePanel.QUEUE) null else SidePanel.QUEUE }) {
                        Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = "Queue", tint = if (uiState.sidePanel == SidePanel.QUEUE) accent else muted)
                    }
                }
            }
            Tip("Mute (M)") {
                IconButton(onClick = player::toggleMute) {
                    Icon(
                        when {
                            volume == 0f -> Icons.AutoMirrored.Rounded.VolumeOff
                            volume < 0.5f -> Icons.AutoMirrored.Rounded.VolumeDown
                            else -> Icons.AutoMirrored.Rounded.VolumeUp
                        },
                        contentDescription = "Volume",
                        tint = muted,
                    )
                }
            }
            SlimSlider(volume, player::setVolume, color = onBar, trackColor = onBar.copy(alpha = 0.2f), modifier = Modifier.width(110.dp))
            Spacer(Modifier.width(4.dp))
            Tip("Full-screen player") {
                IconButton(onClick = { uiState.nowPlayingOpen = true }, enabled = current != null) {
                    Icon(Icons.Rounded.OpenInFull, contentDescription = "Open player", tint = muted)
                }
            }
        }
    }
}

// ---------------- Keyboard shortcuts ----------------

@Composable
fun ShortcutsDialog(onDismiss: () -> Unit) {
    val rows = listOf(
        "Space" to "Play / pause",
        "← / →" to "Seek 5 s (Shift: 30 s)",
        "Ctrl + ← / →" to "Previous / next song",
        "↑ / ↓" to "Volume up / down",
        "M" to "Mute",
        "S" to "Shuffle",
        "R" to "Repeat mode",
        "L" to "Like the current song",
        "Ctrl + F" to "Search",
        "Esc" to "Close player / go back",
        "Media keys" to "Play/pause, next, previous (also in the background)",
        "Right-click" to "Song and playlist menus",
        "? or F1" to "This list",
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Keyboard, contentDescription = null) },
        title = { Text("Keyboard shortcuts") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rows.forEach { (key, what) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.width(150.dp),
                        ) {
                            Text(
                                key,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                        Text(what, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
