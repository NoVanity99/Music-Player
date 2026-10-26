package com.novanity.musicplayer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.novanity.musicplayer.data.Song
import com.novanity.musicplayer.ui.Destination
import com.novanity.musicplayer.ui.PlayerUiState
import com.novanity.musicplayer.ui.PlayerViewModel
import com.novanity.musicplayer.ui.QueueEntry
import com.novanity.musicplayer.ui.SongActions
import com.novanity.musicplayer.ui.components.AlbumArt
import com.novanity.musicplayer.ui.components.MenuAction
import com.novanity.musicplayer.ui.components.OverflowMenu
import com.novanity.musicplayer.ui.components.formatDuration
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private fun PlayerUiState.song(songsById: Map<Long, Song>): Song? = current?.songId?.let(songsById::get)

@Composable
fun MiniPlayer(
    state: PlayerUiState,
    song: Song?,
    player: PlayerViewModel,
    onExpand: () -> Unit,
) {
    val position by player.positionMs.collectAsStateWithLifecycle()
    val current = state.current ?: return
    Surface(
        tonalElevation = 6.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onExpand)
            .pointerInput(Unit) {
                detectVerticalDragGestures { _, dragAmount -> if (dragAmount < -12f) onExpand() }
            },
    ) {
        Column(Modifier.navigationBarsPadding()) {
            LinearProgressIndicator(
                progress = { if (state.durationMs > 0) (position.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
            )
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AlbumArt(song, Modifier.size(44.dp), shape = RoundedCornerShape(6.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(current.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    Text(
                        current.artist,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = player::togglePlayPause) {
                    Icon(
                        if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                    )
                }
                IconButton(onClick = player::next) { Icon(Icons.Rounded.SkipNext, contentDescription = "Next") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    state: PlayerUiState,
    songsById: Map<Long, Song>,
    actions: SongActions,
    onCollapse: () -> Unit,
) {
    val player = actions.player
    val song = state.song(songsById)
    val current = state.current ?: return
    var showQueue by rememberSaveable { mutableStateOf(false) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCollapse) { Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Collapse") }
                Text(
                    "Now playing",
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                if (song != null) {
                    OverflowMenu(
                        listOf(
                            MenuAction("Add to playlist\u2026") { actions.library.requestAddToPlaylist(listOf(song)) },
                            MenuAction("Go to album") {
                                onCollapse()
                                actions.library.open(Destination.AlbumDetail(song.albumId))
                            },
                            MenuAction("Go to artist") {
                                onCollapse()
                                actions.library.open(Destination.ArtistDetail(song.artist))
                            },
                        )
                    )
                }
            }

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                AlbumArt(
                    song,
                    Modifier.aspectRatio(1f),
                    shape = RoundedCornerShape(20.dp),
                    large = true,
                )
            }

            Text(
                current.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .basicMarquee(),
            )
            Text(
                listOfNotNull(current.artist.ifEmpty { null }, song?.album).joinToString(" • "),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            SeekBar(state.durationMs, player)
            Spacer(Modifier.height(8.dp))
            Controls(state, player)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                IconButton(onClick = { showQueue = true }) {
                    Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = "Up next")
                }
            }
        }
    }

    if (showQueue) {
        ModalBottomSheet(
            onDismissRequest = { showQueue = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        ) {
            QueueSheetContent(state, player)
        }
    }
}

@Composable
private fun SeekBar(durationMs: Long, player: PlayerViewModel) {
    val position by player.positionMs.collectAsStateWithLifecycle()
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val duration = durationMs.coerceAtLeast(1L)
    val fraction = if (dragging) dragValue else (position.toFloat() / duration).coerceIn(0f, 1f)

    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = fraction,
            onValueChange = {
                dragging = true
                dragValue = it
            },
            onValueChangeFinished = {
                player.seekTo((dragValue * duration).toLong())
                dragging = false
            },
            enabled = durationMs > 0,
        )
        Row(Modifier.fillMaxWidth()) {
            Text(formatDuration((fraction * duration).toLong()), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            Text(formatDuration(durationMs), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun Controls(state: PlayerUiState, player: PlayerViewModel) {
    val active = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = player::toggleShuffle) {
            Icon(
                Icons.Rounded.Shuffle,
                contentDescription = if (state.shuffle) "Shuffle on" else "Shuffle off",
                tint = if (state.shuffle) active else inactive,
            )
        }
        IconButton(onClick = player::previous, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(36.dp))
        }
        FilledIconButton(
            onClick = player::togglePlayPause,
            modifier = Modifier.size(76.dp),
            colors = IconButtonDefaults.filledIconButtonColors(),
        ) {
            Icon(
                if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (state.isPlaying) "Pause" else "Play",
                modifier = Modifier.size(40.dp),
            )
        }
        IconButton(onClick = player::next, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Rounded.SkipNext, contentDescription = "Next", modifier = Modifier.size(36.dp))
        }
        IconButton(onClick = player::cycleRepeat) {
            val (icon, label) = when (state.repeatMode) {
                Player.REPEAT_MODE_ONE -> Icons.Rounded.RepeatOne to "Repeat one"
                Player.REPEAT_MODE_ALL -> Icons.Rounded.Repeat to "Repeat all"
                else -> Icons.Rounded.Repeat to "Repeat off"
            }
            Icon(icon, contentDescription = label, tint = if (state.repeatMode == Player.REPEAT_MODE_OFF) inactive else active)
        }
    }
}

@Composable
private fun QueueSheetContent(state: PlayerUiState, player: PlayerViewModel) {
    val upNext by player.upNext.collectAsStateWithLifecycle()

    // Local copy for smooth dragging; resynced from the player when idle.
    var items by remember { mutableStateOf(upNext) }
    var dragging by remember { mutableStateOf(false) }
    var dragKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(upNext, dragging) { if (!dragging) items = upNext }

    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val fromIdx = items.indexOfFirst { it.key == from.key }
        val toIdx = items.indexOfFirst { it.key == to.key }
        if (fromIdx >= 0 && toIdx >= 0) items = items.toMutableList().apply { add(toIdx, removeAt(fromIdx)) }
    }
    val canReorder = !state.shuffle

    Column(Modifier.fillMaxWidth()) {
        state.current?.let { current ->
            ListItem(
                overlineContent = { Text("Now playing") },
                headlineContent = { Text(current.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.primary) },
                supportingContent = { Text(current.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
        Text(
            if (canReorder) "Up next • drag to reorder, swipe to remove" else "Up next (shuffled) • swipe to remove, turn shuffle off to reorder",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (items.isEmpty()) {
            Text(
                "Nothing queued after this song.",
                modifier = Modifier.padding(16.dp).navigationBarsPadding(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
            items(items, key = { it.key }) { entry ->
                ReorderableItem(reorderState, key = entry.key, enabled = canReorder) { isDragging ->
                    QueueRow(
                        entry = entry,
                        isDragging = isDragging,
                        canReorder = canReorder,
                        onClick = { player.playQueueIndex(entry.index) },
                        onRemove = { player.removeQueueIndex(entry.index) },
                        handle = Modifier.draggableHandle(
                            enabled = canReorder,
                            onDragStarted = {
                                dragging = true
                                dragKey = entry.key
                            },
                            onDragStopped = {
                                val key = dragKey
                                val fromIndex = upNext.firstOrNull { it.key == key }?.index
                                val newPos = items.indexOfFirst { it.key == key }
                                // Target = the player index of whichever original entry sat at the new position.
                                val toIndex = if (newPos >= 0) upNext.getOrNull(newPos)?.index else null
                                if (fromIndex != null && toIndex != null) player.moveQueueItem(fromIndex, toIndex)
                                dragKey = null
                                dragging = false
                            },
                        ),
                    )
                }
            }
            item { Spacer(Modifier.navigationBarsPadding().height(16.dp)) }
        }
    }
}

@Composable
private fun QueueRow(
    entry: QueueEntry,
    isDragging: Boolean,
    canReorder: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    handle: Modifier,
) {
    SwipeToDismissBox(
        state = rememberSwipeToDismissBoxState(),
        backgroundContent = { DeleteBackground() },
        onDismiss = { onRemove() },
    ) {
        Surface(tonalElevation = if (isDragging) 8.dp else 1.dp) {
            ListItem(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable(onClick = onClick),
                headlineContent = { Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(entry.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingContent = {
                    if (canReorder) {
                        IconButton(onClick = {}, modifier = handle) {
                            Icon(Icons.Rounded.DragHandle, contentDescription = "Reorder")
                        }
                    }
                },
            )
        }
    }
}
