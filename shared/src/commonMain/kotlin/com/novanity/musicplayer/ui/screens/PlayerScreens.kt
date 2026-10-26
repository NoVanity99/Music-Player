package com.novanity.musicplayer.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.novanity.musicplayer.playback.RepeatMode
import com.novanity.musicplayer.platform.LocalPlatform
import com.novanity.musicplayer.data.Song
import com.novanity.musicplayer.ui.Destination
import com.novanity.musicplayer.playback.PlayerUiState
import com.novanity.musicplayer.ui.PlayerViewModel
import com.novanity.musicplayer.playback.QueueEntry
import com.novanity.musicplayer.ui.SongActions
import com.novanity.musicplayer.ui.components.AlbumArt
import com.novanity.musicplayer.ui.components.MenuAction
import com.novanity.musicplayer.ui.components.OverflowMenu
import com.novanity.musicplayer.ui.components.dismissDrag
import com.novanity.musicplayer.ui.components.formatDuration
import com.novanity.musicplayer.ui.components.SleepTimerDialog
import com.novanity.musicplayer.ui.components.SpeedDialog
import com.novanity.musicplayer.ui.components.rememberSleepLabel
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Speed
import com.novanity.musicplayer.ui.components.rememberDismissDragState
import com.novanity.musicplayer.ui.components.rememberTrackSwipeState
import com.novanity.musicplayer.ui.components.trackSwipe
import com.novanity.musicplayer.ui.components.rememberArtColor
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.math.abs

private val Muted = Color.White.copy(alpha = 0.65f)
private val DefaultTint = Color(0xFF3A2E3F)

// ======================= Mini player =======================

/** Floating, art-tinted mini player that sits above the bottom navigation. */
@Composable
fun MiniPlayer(
    state: PlayerUiState,
    song: Song?,
    actions: SongActions,
    liked: Boolean,
    onExpand: () -> Unit,
) {
    val player = actions.player
    val position by player.positionMs.collectAsStateWithLifecycle()
    val current = state.current ?: return
    val tint by rememberArtColor(song, DefaultTint)
    val swipe = rememberTrackSwipeState(current.key)

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = lerp(tint, Color.Black, 0.35f),
        contentColor = Color.White,
        shadowElevation = 8.dp,
        modifier = Modifier
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .fillMaxWidth()
            .height(62.dp)
            .clickable(onClick = onExpand)
            // Swipe sideways to change track, swipe up to open the player.
            .trackSwipe(swipe, state.hasNext, state.hasPrevious, player::nextTrack, player::previousTrack)
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { dy -> if (dy < -8f) onExpand() },
            ),
    ) {
        Box {
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(start = 8.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AlbumArt(song, Modifier.size(44.dp).graphicsLayer { translationX = swipe.offset }, shape = RoundedCornerShape(6.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f).graphicsLayer { translationX = swipe.offset }) {
                    Text(current.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, color = Color.White)
                    Text(current.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = Muted)
                }
                if (song != null) {
                    IconButton(onClick = { actions.toggleLike(song, liked) }) {
                        Icon(
                            if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            contentDescription = if (liked) "Unlike" else "Like",
                            tint = if (liked) MaterialTheme.colorScheme.primary else Color.White,
                        )
                    }
                }
                IconButton(onClick = player::togglePlayPause) {
                    Icon(
                        if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(30.dp),
                    )
                }
            }
            // Hairline progress along the bottom edge.
            val fraction = if (state.durationMs > 0) (position.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 10.dp)
                    .fillMaxWidth()
                    .height(2.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.2f))
            ) {
                Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(Color.White))
            }
        }
    }
}

// ======================= Now Playing =======================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    state: PlayerUiState,
    songsById: Map<Long, Song>,
    actions: SongActions,
    onCollapse: () -> Unit,
) {
    val player = actions.player
    val song = state.current?.songId?.let(songsById::get)
    val current = state.current ?: return
    val playingFrom by player.playingFrom.collectAsStateWithLifecycle()
    val likedIds by actions.library.likedIds.collectAsStateWithLifecycle()
    val liked = song != null && song.id in likedIds
    val tint by rememberArtColor(song, DefaultTint)
    var showQueue by rememberSaveable { mutableStateOf(false) }
    var showLyrics by rememberSaveable { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    val sleepTimer by player.sleepTimer.collectAsStateWithLifecycle()
    val sleepLabel = rememberSleepLabel(sleepTimer)

    // Drag the whole sheet down to dismiss (distance or flick), else spring back.
    val dismiss = rememberDismissDragState()

    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = dismiss.offset }
                .clip(RoundedCornerShape((dismiss.progress * 160f).coerceAtMost(28f).dp))
                .background(Color.Black)
                .dismissDrag(dismiss, onCollapse),
        ) {
            // Backdrop: blurred artwork (Android 12+) under a wash of the art's color.
            if (LocalPlatform.current.supportsBlur) {
                AlbumArt(
                    song,
                    Modifier
                        .fillMaxSize()
                        .scale(1.4f)
                        .blur(90.dp)
                        .alpha(0.55f),
                    shape = RoundedCornerShape(0.dp),
                )
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(tint.copy(alpha = 0.85f), lerp(tint, Color.Black, 0.6f).copy(alpha = 0.9f), Color.Black)
                        )
                    )
            )

            BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding()) {
                // Wide windows: artwork on the left, lyrics + controls on the right.
                val wide = maxWidth >= 900.dp && maxHeight >= 520.dp
                val artwork: @Composable (Modifier) -> Unit = { artModifier ->
                // Artwork: shrinks when paused, swipe sideways to change track.
                val artScale by animateFloatAsState(
                    if (state.isPlaying) 1f else 0.84f,
                    spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
                    label = "artScale",
                )
                val artSwipe = rememberTrackSwipeState(current.key)
                Box(
                    artModifier
                        .padding(vertical = 20.dp)
                        .trackSwipe(artSwipe, state.hasNext, state.hasPrevious, player::nextTrack, player::previousTrack),
                    contentAlignment = Alignment.Center,
                ) {
                    AlbumArt(
                        song,
                        Modifier
                            .aspectRatio(1f)
                            .graphicsLayer {
                                scaleX = artScale
                                scaleY = artScale
                                translationX = artSwipe.offset
                                alpha = 1f - (abs(artSwipe.offset) / size.width.coerceAtLeast(1f)).coerceIn(0f, 0.7f)
                            }
                            .shadow(30.dp, RoundedCornerShape(14.dp), ambientColor = Color.Black, spotColor = Color.Black),
                        shape = RoundedCornerShape(14.dp),
                        large = true,
                    )
                }

                }
                val details: @Composable () -> Unit = {
                // Title + like
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            current.title,
                            style = MaterialTheme.typography.headlineSmall,
                            maxLines = 1,
                            modifier = Modifier.basicMarquee(),
                        )
                        Text(
                            current.artist,
                            style = MaterialTheme.typography.titleMedium,
                            color = Muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable(enabled = song != null) {
                                song?.let {
                                    onCollapse()
                                    actions.library.open(Destination.ArtistDetail(it.artist))
                                }
                            },
                        )
                    }
                    if (song != null) {
                        IconButton(onClick = { actions.toggleLike(song, liked) }) {
                            Icon(
                                if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                contentDescription = if (liked) "Unlike" else "Like",
                                tint = if (liked) MaterialTheme.colorScheme.primary else Color.White,
                                modifier = Modifier.size(28.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                SeekBar(state.durationMs, player)
                Spacer(Modifier.height(12.dp))
                Controls(state, player)
                Spacer(Modifier.height(12.dp))
                }
                val bottomButtons: @Composable () -> Unit = {
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.Center) {
                        TextButton(onClick = { showQueue = true }) {
                            Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = null, tint = Color.White)
                            Spacer(Modifier.width(8.dp))
                            Text("Up next", color = Color.White, style = MaterialTheme.typography.labelLarge)
                        }
                        if (!wide) {
                            Spacer(Modifier.width(12.dp))
                            TextButton(onClick = { showLyrics = !showLyrics }) {
                                Icon(Icons.Rounded.Lyrics, contentDescription = null, tint = if (showLyrics) MaterialTheme.colorScheme.primary else Color.White)
                                Spacer(Modifier.width(8.dp))
                                Text("Lyrics", color = Color.White, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
                Column(Modifier.fillMaxSize().padding(horizontal = if (wide) 48.dp else 24.dp)) {
                // Grabber + header
                Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(width = 36.dp, height = 5.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.35f)))
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onCollapse) {
                        Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Collapse", modifier = Modifier.size(30.dp))
                    }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("PLAYING FROM", style = MaterialTheme.typography.labelSmall, color = Muted)
                        Text(
                            playingFrom ?: song?.album ?: "Your library",
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (sleepLabel != null) {
                            Row(
                                Modifier.padding(top = 4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f))
                                    .clickable { showSleep = true }.padding(horizontal = 10.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Rounded.Bedtime, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(sleepLabel, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    if (song != null) {
                        OverflowMenu(
                            listOf(
                                MenuAction("Add to playlist") { actions.library.requestAddToPlaylist(listOf(song)) },
                                MenuAction(if (sleepTimer == null) "Sleep timer" else "Sleep timer \u2022 $sleepLabel", Icons.Rounded.Bedtime) { showSleep = true },
                                MenuAction("Playback speed", Icons.Rounded.Speed) { showSpeed = true },
                                MenuAction("Go to album") {
                                    onCollapse()
                                    actions.library.open(Destination.AlbumDetail(song.albumId))
                                },
                                MenuAction("Go to artist") {
                                    onCollapse()
                                    actions.library.open(Destination.ArtistDetail(song.artist))
                                },
                            ),
                            icon = Icons.Rounded.MoreHoriz,
                            tint = Color.White,
                        )
                    } else {
                        Spacer(Modifier.size(48.dp))
                    }
                }

                    if (wide) {
                        Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            artwork(Modifier.weight(1f).fillMaxHeight())
                            Spacer(Modifier.width(56.dp))
                            Column(Modifier.weight(1f).fillMaxHeight().padding(vertical = 20.dp)) {
                                LyricsView(song, player, Modifier.weight(1f).fillMaxWidth(), Color.White, Muted)
                                details()
                            }
                        }
                        bottomButtons()
                    } else {
                        if (showLyrics) {
                            LyricsView(song, player, Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp), Color.White, Muted)
                        } else {
                            artwork(Modifier.weight(1f).fillMaxWidth())
                        }
                        details()
                        bottomButtons()
                    }
                }
            }
        }
    }

    if (showSleep) SleepTimerDialog(player) { showSleep = false }
    if (showSpeed) SpeedDialog(player) { showSpeed = false }

    if (showQueue) {
        ModalBottomSheet(
            onDismissRequest = { showQueue = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            QueueSheetContent(state, song, songsById, player)
        }
    }
}

/** Thin Apple-style scrubber that thickens while you drag it. */
@Composable
internal fun SeekBar(
    durationMs: Long,
    player: PlayerViewModel,
    activeColor: Color = Color.White,
    trackColor: Color = Color.White.copy(alpha = 0.25f),
    labelColor: Color = Muted,
    compact: Boolean = false,
) {
    val position by player.positionMs.collectAsStateWithLifecycle()
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val duration = durationMs.coerceAtLeast(1L)
    val fraction = if (dragging) dragFraction else (position.toFloat() / duration).coerceIn(0f, 1f)
    val barHeight by animateDpAsState(if (dragging) 10.dp else if (compact) 4.dp else 5.dp, label = "bar")

    Column(Modifier.fillMaxWidth()) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(24.dp)
                .pointerInput(duration) {
                    detectTapGestures { offset ->
                        player.seekTo(((offset.x / size.width).coerceIn(0f, 1f) * duration).toLong())
                    }
                }
                .pointerInput(duration) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            dragging = true
                            dragFraction = (offset.x / size.width).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            player.seekTo((dragFraction * duration).toLong())
                            dragging = false
                        },
                        onDragCancel = { dragging = false },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        },
                    )
                },
        ) {
            val h = barHeight.toPx()
            val top = (size.height - h) / 2
            val radius = CornerRadius(h / 2, h / 2)
            drawRoundRect(trackColor, Offset(0f, top), Size(size.width, h), radius)
            drawRoundRect(activeColor.copy(alpha = if (dragging) 1f else 0.9f), Offset(0f, top), Size(size.width * fraction, h), radius)
        }
        if (!compact) {
            Row(Modifier.fillMaxWidth()) {
                Text(formatDuration((fraction * duration).toLong()), style = MaterialTheme.typography.labelMedium, color = labelColor)
                Spacer(Modifier.weight(1f))
                Text("-" + formatDuration(((1f - fraction) * duration).toLong()), style = MaterialTheme.typography.labelMedium, color = labelColor)
            }
        }
    }
}

@Composable
private fun Controls(state: PlayerUiState, player: PlayerViewModel) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = player::toggleShuffle) {
            Icon(
                Icons.Rounded.Shuffle,
                contentDescription = if (state.shuffle) "Shuffle on" else "Shuffle off",
                tint = if (state.shuffle) accent else Muted,
            )
        }
        IconButton(onClick = player::previous, modifier = Modifier.size(64.dp)) {
            Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(44.dp))
        }
        Surface(
            onClick = player::togglePlayPause,
            shape = CircleShape,
            color = Color.White,
            contentColor = Color.Black,
            modifier = Modifier.size(76.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (state.isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(44.dp),
                )
            }
        }
        IconButton(onClick = player::next, modifier = Modifier.size(64.dp)) {
            Icon(Icons.Rounded.SkipNext, contentDescription = "Next", modifier = Modifier.size(44.dp))
        }
        IconButton(onClick = player::cycleRepeat) {
            val (icon, label) = when (state.repeatMode) {
                RepeatMode.ONE -> Icons.Rounded.RepeatOne to "Repeat one"
                RepeatMode.ALL -> Icons.Rounded.Repeat to "Repeat all"
                else -> Icons.Rounded.Repeat to "Repeat off"
            }
            Icon(icon, contentDescription = label, tint = if (state.repeatMode == RepeatMode.OFF) Muted else accent)
        }
    }
}

// ======================= Up Next =======================

@Composable
internal fun QueueSheetContent(
    state: PlayerUiState,
    song: Song?,
    songsById: Map<Long, Song>,
    player: PlayerViewModel,
    background: Color = MaterialTheme.colorScheme.surfaceContainerLow,
) {
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
            Text(
                "Now playing",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                AlbumArt(song, Modifier.size(52.dp), shape = RoundedCornerShape(8.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(current.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(current.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Up next", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (canReorder) "Drag to reorder • swipe to remove" else "Shuffled • turn shuffle off to reorder",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (items.isNotEmpty()) TextButton(onClick = player::clearUpNext) { Text("Clear") }
        }
        if (items.isEmpty()) {
            Text(
                "Nothing queued after this song.",
                modifier = Modifier.padding(20.dp).navigationBarsPadding(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
            items(items, key = { it.key }) { entry ->
                ReorderableItem(reorderState, key = entry.key, enabled = canReorder) { isDragging ->
                    QueueRow(
                        entry = entry,
                        song = entry.songId?.let(songsById::get),
                        isDragging = isDragging,
                        canReorder = canReorder,
                        onClick = { player.playQueueIndex(entry.index) },
                        onRemove = { player.removeQueueIndex(entry.index) },
                        background = background,
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
    song: Song?,
    isDragging: Boolean,
    canReorder: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    handle: Modifier,
    background: Color,
) {
    SwipeToDismissBox(
        state = rememberSwipeToDismissBoxState(),
        backgroundContent = { SwipeBackground(Icons.Rounded.Remove) },
        onDismiss = { onRemove() },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (isDragging) MaterialTheme.colorScheme.surfaceContainerHighest else background)
                .clickable(onClick = onClick)
                .padding(start = 20.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AlbumArt(song, Modifier.size(44.dp), shape = RoundedCornerShape(6.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(entry.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (canReorder) {
                IconButton(onClick = {}, modifier = handle) {
                    Icon(Icons.Rounded.DragHandle, contentDescription = "Reorder", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
