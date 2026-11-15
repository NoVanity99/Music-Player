package com.novanity.musicplayer.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.unit.dp
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

    // Most recently used last. Artist pictures come through here too, so the cache is bounded.
    private val cache = LinkedHashMap<String, ImageBitmap>()
    private var bytes = 0L
    private const val MAX_BYTES = 72L * 1024 * 1024

    fun cached(url: String): ImageBitmap? {
        val image = cache.remove(url) ?: return null
        cache[url] = image
        return image
    }

    private fun keep(url: String, image: ImageBitmap) {
        cache.remove(url)?.let { bytes -= it.width.toLong() * it.height * 4 }
        cache[url] = image
        bytes += image.width.toLong() * image.height * 4
        val oldest = cache.keys.iterator()
        while (bytes > MAX_BYTES && cache.size > 1 && oldest.hasNext()) {
            val key = oldest.next()
            if (key == url) continue
            cache[key]?.let { bytes -= it.width.toLong() * it.height * 4 }
            oldest.remove()
        }
    }

    suspend fun load(url: String): ImageBitmap? {
        cached(url)?.let { return it }
        val f = fetch ?: return null
        val d = decode ?: return null
        val image = withContext(Dispatchers.IO) { runCatching { f(url)?.let(d) }.getOrNull() }
        if (image != null) keep(url, image) // a failure is tried again next time
        return image
    }

    /** Shows a picture that was just chosen, before (and without) downloading it again. */
    fun put(url: String, bytes: ByteArray) {
        decode?.let { d -> runCatching { d(bytes) }.getOrNull()?.let { keep(url, it) } }
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

/**
 * The "ADMIN" bubble shown next to the owner's name: a gold outline with a gold crown and gold
 * lettering, and a band of light that sweeps across it every few seconds so it catches the eye.
 */
@Composable
fun AdminBadge(modifier: Modifier = Modifier) {
    val gold = Color(0xFFF5C33B)
    val pale = Color(0xFFFFF3C4)
    val deep = Color(0xFFB8860B)
    val sweep by androidx.compose.animation.core.rememberInfiniteTransition(label = "admin").animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(2600, easing = androidx.compose.animation.core.LinearEasing),
        ),
        label = "shine",
    )
    // Gold that brightens where the light is passing.
    fun shine(width: Float) = Brush.linearGradient(
        colorStops = arrayOf(0f to deep, (sweep - 0.25f).coerceIn(0f, 1f) to gold, sweep.coerceIn(0f, 1f) to pale, (sweep + 0.25f).coerceIn(0f, 1f) to gold, 1f to deep),
        start = androidx.compose.ui.geometry.Offset(0f, 0f),
        end = androidx.compose.ui.geometry.Offset(width, width * 0.35f),
    )
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        androidx.compose.foundation.layout.Row(
            Modifier
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                .background(gold.copy(alpha = 0.10f))
                .drawWithContentBorder { w -> shine(w) }
                .padding(start = 7.dp, end = 9.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.Canvas(Modifier.size(width = 15.dp, height = 12.dp)) {
                val w = size.width
                val h = size.height
                // A crown: three points over a band.
                val crown = androidx.compose.ui.graphics.Path().apply {
                    moveTo(0f, h * 0.18f); lineTo(w * 0.27f, h * 0.55f); lineTo(w * 0.5f, 0f); lineTo(w * 0.73f, h * 0.55f); lineTo(w, h * 0.18f)
                    lineTo(w * 0.88f, h * 0.78f); lineTo(w * 0.12f, h * 0.78f); close()
                }
                drawPath(crown, shine(w * 4))
                drawRoundRect(shine(w * 4), topLeft = androidx.compose.ui.geometry.Offset(w * 0.12f, h * 0.86f), size = androidx.compose.ui.geometry.Size(w * 0.76f, h * 0.14f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(h * 0.07f))
            }
            androidx.compose.foundation.layout.Spacer(Modifier.size(5.dp))
            Text(
                "ADMIN",
                fontSize = 10.5.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.8.sp,
                maxLines = 1,
                softWrap = false,
                style = androidx.compose.ui.text.TextStyle(brush = shine(220f)),
            )
        }
    }
}

/** A thin rounded outline drawn with a brush that depends on the element's width. */
private fun Modifier.drawWithContentBorder(brush: (width: Float) -> Brush): Modifier = this.then(
    Modifier.drawWithContent {
        drawContent()
        val stroke = 1.4.dp.toPx()
        drawRoundRect(
            brush(size.width),
            topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
            size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2),
            style = androidx.compose.ui.graphics.drawscope.Stroke(stroke),
        )
    }
)
