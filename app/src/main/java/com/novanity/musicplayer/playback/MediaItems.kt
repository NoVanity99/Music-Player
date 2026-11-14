package com.novanity.musicplayer.playback

import com.novanity.musicplayer.data.SongSource
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.novanity.musicplayer.data.Song
import com.novanity.musicplayer.platform.albumArtUri
import java.util.concurrent.atomic.AtomicLong

private val queueEntryCounter = AtomicLong(0)

/**
 * Builds a queue entry for [this] song. The media id is "songId/uniqueEntry" so the same song can
 * appear in the queue more than once while every entry keeps a stable, unique key.
 */
fun Song.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId("$id/${queueEntryCounter.incrementAndGet()}")
    .setUri(Uri.parse(uri))
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setTrackNumber(track.takeIf { it > 0 })
            .setDurationMs(durationMs)
            .setArtworkUri(artworkUri)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .build()
    )
    .build()

/**
 * Art for the notification and lock screen: MediaStore's for local songs; for cloud songs the cover
 * already saved on the device if there is one, otherwise the server's (fetched with the key).
 */
private val Song.artworkUri: Uri?
    get() = when {
        source == SongSource.LOCAL -> albumArtUri
        coverId == null -> null
        else -> CloudCovers.localFile(coverId!!)?.let(Uri::fromFile) ?: CloudCovers.remoteUri(coverId!!)
    }

/** Set once by the app: how to find cloud covers. */
object CloudCovers {
    var localFile: (coverId: String) -> java.io.File? = { null }
    var remoteUri: (coverId: String) -> Uri? = { null }
}

val MediaItem.songId: Long?
    get() = mediaId.substringBefore('/').toLongOrNull()
