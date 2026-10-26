package com.novanity.musicplayer.ui

import android.content.Context
import android.widget.Toast
import com.novanity.musicplayer.data.Song
import com.novanity.musicplayer.ui.components.MenuAction

/** Shared song/collection menu actions so every list behaves the same. */
class SongActions(
    private val context: Context,
    val player: PlayerViewModel,
    val library: LibraryViewModel,
) {
    private fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    fun songMenu(song: Song, extra: List<MenuAction> = emptyList()): List<MenuAction> = listOf(
        MenuAction("Play next") {
            player.playNext(listOf(song))
            toast("Playing next")
        },
        MenuAction("Add to queue") {
            player.addToQueue(listOf(song))
            toast("Added to queue")
        },
        MenuAction("Add to playlist…") { library.requestAddToPlaylist(listOf(song)) },
        MenuAction("Go to album") { library.open(Destination.AlbumDetail(song.albumId)) },
        MenuAction("Go to artist") { library.open(Destination.ArtistDetail(song.artist)) },
    ) + extra

    fun collectionMenu(songs: List<Song>, extra: List<MenuAction> = emptyList()): List<MenuAction> = listOf(
        MenuAction("Play next") {
            player.playNext(songs)
            toast("Playing next")
        },
        MenuAction("Add to queue") {
            player.addToQueue(songs)
            toast("Added to queue")
        },
        MenuAction("Add to playlist…") { library.requestAddToPlaylist(songs) },
    ) + extra
}
