package com.novanity.musicplayer.ui

import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import com.novanity.musicplayer.data.DownloadState
import com.novanity.musicplayer.data.SongSource
import com.novanity.musicplayer.ui.components.artistMenuLabels
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
    val updates: com.novanity.musicplayer.update.UpdateManager? = null,
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
    ) + artistMenu(song.artist) + downloadMenu(song) + extra

    /** Download / cancel / remove, depending on where a cloud song is. */
    private fun downloadMenu(song: Song): List<MenuAction> {
        if (song.source == SongSource.LOCAL) return emptyList()
        val state = library.downloadStates.value[song.cloudId]
        return listOf(
            when {
                song.source == SongSource.DOWNLOADED -> MenuAction("Remove download", Icons.Rounded.DeleteOutline) {
                    library.deleteDownload(song)
                    toast("Removed from this device")
                }
                state is DownloadState.Queued || state is DownloadState.Downloading -> MenuAction("Cancel download", Icons.Rounded.Close) {
                    library.cancelDownload(song)
                }
                else -> MenuAction(if (state is DownloadState.Failed) "Try download again" else "Download", Icons.Rounded.Download) {
                    library.download(listOf(song))
                    toast("Downloading “${song.title}”")
                }
            }
        )
    }

    /** The status icon on a cloud song was tapped. */
    fun onDownloadTap(song: Song, state: DownloadState?) {
        when (state) {
            is DownloadState.Queued, is DownloadState.Downloading -> {
                library.cancelDownload(song)
                toast("Download cancelled")
            }
            DownloadState.Done -> Unit
            else -> {
                library.download(listOf(song))
                toast("Downloading “${song.title}”")
            }
        }
    }

    /** Downloads every cloud song in [songs] that isn't on the device yet. */
    fun downloadAll(songs: List<Song>) {
        val n = library.download(songs)
        toast(
            when (n) {
                0 -> "Everything here is already downloaded"
                1 -> "Downloading 1 song"
                else -> "Downloading $n songs"
            }
        )
    }

    /** "Go to artist", or "Go to Drake" / "Go to Future" for a collaboration. */
    fun artistMenu(credit: String, before: () -> Unit = {}): List<MenuAction> =
        artistMenuLabels(library.artistParts(credit)).map { (label, part) ->
            MenuAction(label, Icons.Rounded.Person) { before(); library.openArtist(part) }
        }

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
    ) + (if (songs.any { it.source == SongSource.CLOUD }) listOf(MenuAction("Download", Icons.Rounded.Download) { downloadAll(songs) }) else emptyList()) + extra
}
