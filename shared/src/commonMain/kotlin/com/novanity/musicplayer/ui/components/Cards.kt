package com.novanity.musicplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.novanity.musicplayer.data.Album
import com.novanity.musicplayer.data.Artist
import com.novanity.musicplayer.data.Song
import com.novanity.musicplayer.ui.PlaylistCard

val LikedGradient = listOf(Color(0xFF5B2BFF), Color(0xFFC2B5FF))
val ShuffleGradient = listOf(Color(0xFFFF375F), Color(0xFFFF9F0A))
val RecentGradient = listOf(Color(0xFF0A84FF), Color(0xFF64D2FF))
val FolderGradient = listOf(Color(0xFF30B158), Color(0xFFA8E063))

/** A square tile filled with a diagonal gradient and a centered icon. */
@Composable
fun GradientArt(
    colors: List<Color>,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(6.dp),
    iconFraction: Float = 0.42f,
) {
    Box(
        modifier
            .clip(shape)
            .background(Brush.linearGradient(colors)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.fillMaxSize(iconFraction))
    }
}

@Composable
fun LikedArt(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(6.dp)) =
    GradientArt(LikedGradient, Icons.Rounded.Favorite, modifier, shape)

/** Playlist cover: a 2x2 mosaic when there are enough distinct albums, else a single cover. */
@Composable
fun PlaylistArt(covers: List<Song>, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(6.dp)) {
    when {
        covers.size >= 4 -> Column(modifier.clip(shape)) {
            for (row in 0 until 2) {
                Row(Modifier.weight(1f)) {
                    for (col in 0 until 2) {
                        AlbumArt(
                            covers[row * 2 + col],
                            Modifier.weight(1f).fillMaxHeight(),
                            shape = RoundedCornerShape(0.dp),
                        )
                    }
                }
            }
        }
        covers.isNotEmpty() -> AlbumArt(covers.first(), modifier, shape = shape)
        else -> Box(
            modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Rounded.QueueMusic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxSize(0.42f),
            )
        }
    }
}

/** Big round play/pause button in the accent color. */
@Composable
fun PlayCircleButton(
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    container: Color = MaterialTheme.colorScheme.primary,
    content: Color = MaterialTheme.colorScheme.onPrimary,
) {
    Surface(onClick = onClick, shape = CircleShape, color = container, modifier = modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (playing) "Pause" else "Play",
                tint = content,
                modifier = Modifier.size(size * 0.55f),
            )
        }
    }
}

@Composable
private fun CardText(title: String, subtitle: String?, center: Boolean = false) {
    Spacer(Modifier.height(8.dp))
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = if (center) TextAlign.Center else TextAlign.Start,
        modifier = Modifier.fillMaxWidth(),
    )
    if (subtitle != null) {
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (center) TextAlign.Center else TextAlign.Start,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun AlbumCard(album: Album, onClick: () -> Unit, modifier: Modifier = Modifier.width(CardWidth)) {
    Column(modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)) {
        AlbumArt(album.songs.first(), Modifier.fillMaxWidth().aspectRatio(1f), shape = RoundedCornerShape(8.dp))
        CardText(album.title, album.artist)
    }
}

@Composable
fun SongCard(song: Song, onClick: () -> Unit, modifier: Modifier = Modifier.width(CardWidth)) {
    Column(modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)) {
        AlbumArt(song, Modifier.fillMaxWidth().aspectRatio(1f), shape = RoundedCornerShape(8.dp))
        CardText(song.title, song.artist)
    }
}

@Composable
fun ArtistCard(artist: Artist, onClick: () -> Unit, modifier: Modifier = Modifier.width(CardWidth - 16.dp)) {
    Column(modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        AlbumArt(artist.songs.first(), Modifier.fillMaxWidth().aspectRatio(1f), shape = CircleShape)
        CardText(artist.name, "Artist", center = true)
    }
}

@Composable
fun PlaylistTile(playlist: PlaylistCard, onClick: () -> Unit, modifier: Modifier = Modifier.width(CardWidth)) {
    Column(modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)) {
        PlaylistArt(playlist.covers, Modifier.fillMaxWidth().aspectRatio(1f), shape = RoundedCornerShape(8.dp))
        CardText(playlist.name, "Playlist • ${songCount(playlist.songCount)}")
    }
}

/** Compact "quick pick" tile used in the Home grid (art on the left, title on the right). */
@Composable
fun QuickPickTile(title: String, art: @Composable (Modifier) -> Unit, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.height(56.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            art(Modifier.size(56.dp))
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 10.dp),
            )
        }
    }
}

/** List row for collections (albums, artists, playlists, folders) in Library and Search. */
@Composable
fun CollectionRow(
    title: String,
    subtitle: String,
    art: @Composable (Modifier) -> Unit,
    onClick: () -> Unit,
    menu: List<MenuAction> = emptyList(),
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        art(Modifier.size(56.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        OverflowMenu(menu)
    }
}
