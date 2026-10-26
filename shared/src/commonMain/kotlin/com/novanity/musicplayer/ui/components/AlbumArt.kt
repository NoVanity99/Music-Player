package com.novanity.musicplayer.ui.components

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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.novanity.musicplayer.data.Song
import com.novanity.musicplayer.platform.ArtworkProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Memory-cached album art, keyed per album to share work across tracks. Decoding is delegated to
 * the platform [provider]; the cache itself is only touched from the UI thread.
 */
object ArtworkLoader {
    const val SMALL = 192
    const val LARGE = 1024

    /** Set by each platform at startup. */
    var provider: ArtworkProvider? = null

    private const val MAX_BYTES = 48L * 1024 * 1024
    private val cache = LinkedHashMap<String, ImageBitmap>()
    private var bytes = 0L
    private val misses = HashSet<String>()
    private val decodeSlots = Semaphore(4)

    private fun key(albumId: Long, px: Int) = "$albumId@$px"
    private fun ImageBitmap.byteSize() = width.toLong() * height * 4

    fun cached(song: Song, px: Int): ImageBitmap? {
        val k = key(song.albumId, px)
        val hit = cache.remove(k) ?: return null
        cache[k] = hit // most recently used goes last
        return hit
    }

    private fun put(k: String, bitmap: ImageBitmap) {
        cache.remove(k)?.let { bytes -= it.byteSize() }
        cache[k] = bitmap
        bytes += bitmap.byteSize()
        val it = cache.entries.iterator()
        while (bytes > MAX_BYTES && it.hasNext()) {
            val oldest = it.next()
            if (oldest.key == k) break
            bytes -= oldest.value.byteSize()
            it.remove()
        }
    }

    /** Test hook: pre-seeds artwork for an album at every size. */
    fun putForTest(albumId: Long, bitmap: ImageBitmap) {
        put(key(albumId, SMALL), bitmap)
        put(key(albumId, LARGE), bitmap)
    }

    suspend fun load(song: Song, px: Int): ImageBitmap? {
        val k = key(song.albumId, px)
        cached(song, px)?.let { return it }
        if (k in misses) return null
        val p = provider ?: return null
        val bitmap = decodeSlots.withPermit {
            withContext(Dispatchers.IO) { runCatching { p.load(song, px) }.getOrNull() }
        }
        if (bitmap != null) put(k, bitmap) else misses += k
        return bitmap
    }
}

@Composable
fun AlbumArt(
    song: Song?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(6.dp),
    large: Boolean = false,
) {
    val px = if (large) ArtworkLoader.LARGE else ArtworkLoader.SMALL
    var bitmap by remember(song?.id, px) { mutableStateOf(song?.let { ArtworkLoader.cached(it, px) }) }
    LaunchedEffect(song?.id, px) {
        if (bitmap == null && song != null) bitmap = ArtworkLoader.load(song, px)
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
