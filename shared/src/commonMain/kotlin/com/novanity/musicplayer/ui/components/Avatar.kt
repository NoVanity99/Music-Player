package com.novanity.musicplayer.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/** Profile pictures: fetched once per address and kept in memory. The cache is only touched from the UI thread. */
object Avatars {
    /** Set by each platform at startup: downloads an address, and turns picture bytes into an image. */
    var fetch: (suspend (url: String) -> ByteArray?)? = null
    var decode: ((ByteArray) -> ImageBitmap?)? = null

    private val cache = HashMap<String, ImageBitmap?>()

    fun cached(url: String): ImageBitmap? = cache[url]

    suspend fun load(url: String): ImageBitmap? {
        if (url in cache) return cache[url]
        val f = fetch ?: return null
        val d = decode ?: return null
        val image = withContext(Dispatchers.IO) { runCatching { f(url)?.let(d) }.getOrNull() }
        if (image != null) cache[url] = image // a failure is tried again next time
        return image
    }

    /** Shows a picture that was just chosen, before (and without) downloading it again. */
    fun put(url: String, bytes: ByteArray) {
        decode?.let { d -> runCatching { d(bytes) }.getOrNull()?.let { cache[url] = it } }
    }
}

/** Someone's round profile picture, or their initial on a gradient when they have none. */
@Composable
fun Avatar(name: String, url: String?, size: Dp, modifier: Modifier = Modifier) {
    var image by remember(url) { mutableStateOf(url?.let(Avatars::cached)) }
    LaunchedEffect(url) { if (image == null && url != null) image = Avatars.load(url) }
    Box(
        modifier.size(size).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFF7C3AED), MaterialTheme.colorScheme.primary))),
        contentAlignment = Alignment.Center,
    ) {
        val shown = image
        if (shown != null) {
            Image(bitmap = shown, contentDescription = null, modifier = Modifier.size(size), contentScale = ContentScale.Crop)
        } else {
            Text(name.take(1).uppercase(), fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}
