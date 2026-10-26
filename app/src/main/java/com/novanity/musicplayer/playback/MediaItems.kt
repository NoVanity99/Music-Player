package com.novanity.musicplayer.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.novanity.musicplayer.data.Song
import java.util.concurrent.atomic.AtomicLong

private val queueEntryCounter = AtomicLong(0)

/**
 * Builds a queue entry for [this] song. The media id is "songId/uniqueEntry" so the same song can
 * appear in the queue more than once while every entry keeps a stable, unique key.
 */
fun Song.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId("$id/${queueEntryCounter.incrementAndGet()}")
    .setUri(uri)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setTrackNumber(track.takeIf { it > 0 })
            .setDurationMs(durationMs)
            .setArtworkUri(albumArtUri)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .build()
    )
    .build()

val MediaItem.songId: Long?
    get() = mediaId.substringBefore('/').toLongOrNull()
