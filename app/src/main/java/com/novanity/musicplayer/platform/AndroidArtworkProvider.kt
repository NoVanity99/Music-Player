package com.novanity.musicplayer.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.novanity.musicplayer.data.Song

/** MediaStore album art for a song. */
val Song.albumArtUri: Uri get() = Uri.parse("content://media/external/audio/albumart/$albumId")

class AndroidArtworkProvider(private val context: Context) : ArtworkProvider {
    override suspend fun load(song: Song, sizePx: Int): ImageBitmap? = decode(song, sizePx)?.asImageBitmap()

    private fun decode(song: Song, px: Int): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.loadThumbnail(Uri.parse(song.uri), Size(px, px), null)
        } else {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(song.albumArtUri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0) {
                null
            } else {
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= px && bounds.outHeight / (sample * 2) >= px) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                resolver.openInputStream(song.albumArtUri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            }
        }
    } catch (_: Exception) {
        null // no embedded art, unreadable file, etc.
    }
}
