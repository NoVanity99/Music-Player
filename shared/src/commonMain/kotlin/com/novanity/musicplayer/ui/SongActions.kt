package com.novanity.musicplayer.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.SkipNext
import com.novanity.musicplayer.data.Song
import com.novanity.musicplayer.data.friendlyPath
import com.novanity.musicplayer.ui.components.MenuAction

/** Shared song/collection actions so every list behaves the same. */
class SongActions(
    val player: PlayerViewModel,
    val library: LibraryViewModel,
) {
    /** Shows a short message (a snackbar on every platform). */
    fun toast(msg: String) = library.notify(msg)

    fun play(songs: List<Song>, index: Int = 0, source: String? = null) =
        player.playSongs(songs, index, shuffle = false, source = source)

    fun shuffle(songs: List<Song>, source: String? = null) =
        player.playSongs(songs, shuffle = true, source = source)

    fun ignoreFolder(path: String) {
        library.ignoreFolder(path)
        toast("Ignoring ${friendlyPath(path)} \u2022 undo in Settings")
    }

    fun toggleLike(song: Song, liked: Boolean) {
        library.setLiked(song, !liked)
        toast(if (liked) "Removed from Liked Songs" else "Added to Liked Songs")
    }

    fun songMenu(song: Song, liked: Boolean, extra: List<MenuAction> = emptyList()): List<MenuAction> = listOf(
        MenuAction(if (liked) "Remove from Liked Songs" else "Like", if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder) {
            toggleLike(song, liked)
        },
        MenuAction("Play next", Icons.Rounded.SkipNext) {
            player.playNext(listOf(song))
            toast("Playing next")
        },
        MenuAction("Add to queue", Icons.AutoMirrored.Rounded.QueueMusic) {
            player.addToQueue(listOf(song))
            toast("Added to queue")
        },
        MenuAction("Add to playlist", Icons.AutoMirrored.Rounded.PlaylistAdd) { library.requestAddToPlaylist(listOf(song)) },
        MenuAction("Go to album", Icons.Rounded.Album) { library.open(Destination.AlbumDetail(song.albumId)) },
        MenuAction("Go to artist", Icons.Rounded.Person) { library.open(Destination.ArtistDetail(song.artist)) },
    ) + extra

    fun collectionMenu(songs: List<Song>, extra: List<MenuAction> = emptyList()): List<MenuAction> = listOf(
        MenuAction("Play next", Icons.Rounded.SkipNext) {
            player.playNext(songs)
            toast("Playing next")
        },
        MenuAction("Add to queue", Icons.AutoMirrored.Rounded.QueueMusic) {
            player.addToQueue(songs)
            toast("Added to queue")
        },
        MenuAction("Add to playlist", Icons.AutoMirrored.Rounded.PlaylistAdd) { library.requestAddToPlaylist(songs) },
    ) + extra
}
