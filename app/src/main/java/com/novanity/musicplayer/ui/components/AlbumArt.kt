package com.novanity.musicplayer.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.novanity.musicplayer.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.Collections

/** Small memory-cached album-art loader. Art is keyed per album to share work across tracks. */
object ArtworkLoader {
    const val SMALL = 192
    const val LARGE = 1024

    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val misses: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val decodeSlots = Semaphore(4)

    private fun key(song: Song, px: Int) = "${song.albumId}@$px"

    fun cached(song: Song, px: Int): Bitmap? = cache.get(key(song, px))

    suspend fun load(context: Context, song: Song, px: Int): Bitmap? {
        val key = key(song, px)
        cache.get(key)?.let { return it }
        if (key in misses) return null
        return decodeSlots.withPermit {
            withContext(Dispatchers.IO) {
                cache.get(key) ?: decode(context, song, px).also {
                    if (it != null) cache.put(key, it) else misses.add(key)
                }
            }
        }
    }

    private fun decode(context: Context, song: Song, px: Int): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.loadThumbnail(song.uri, Size(px, px), null)
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

@Composable
fun AlbumArt(
    song: Song?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
    large: Boolean = false,
) {
    val context = LocalContext.current
    val px = if (large) ArtworkLoader.LARGE else ArtworkLoader.SMALL
    var bitmap by remember(song?.id, px) {
        mutableStateOf<ImageBitmap?>(song?.let { ArtworkLoader.cached(it, px)?.asImageBitmap() })
    }
    LaunchedEffect(song?.id, px) {
        if (bitmap == null && song != null) {
            bitmap = ArtworkLoader.load(context, song, px)?.asImageBitmap()
        }
    }
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val b = bitmap
        if (b != null) {
            Image(bitmap = b, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(
                Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxSize(0.5f),
            )
        }
    }
}
