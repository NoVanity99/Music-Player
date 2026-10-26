package com.novanity.musicplayer.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import com.novanity.musicplayer.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Extracts a mood color from album art, tuned to sit behind white text. */
object ArtColors {
    private val cache = HashMap<Long, Color?>()

    fun cached(song: Song): Color? = cache[song.albumId]

    suspend fun load(song: Song): Color? {
        if (cache.containsKey(song.albumId)) return cache[song.albumId]
        val bitmap = ArtworkLoader.load(song, ArtworkLoader.SMALL)
        val color = bitmap?.let { withContext(Dispatchers.Default) { moodColor(it) } }
        cache[song.albumId] = color
        return color
    }

    /**
     * Picks the most prominent saturated hue (a small hue histogram weighted by saturation and
     * brightness), then clamps it into a rich, dark range.
     */
    fun moodColor(bitmap: ImageBitmap): Color? {
        val pixels = bitmap.toPixelMap()
        val step = max(1, min(pixels.width, pixels.height) / 32)
        val buckets = 18
        val weight = FloatArray(buckets)
        val sumH = FloatArray(buckets)
        val sumS = FloatArray(buckets)
        val sumV = FloatArray(buckets)
        var y = 0
        while (y < pixels.height) {
            var x = 0
            while (x < pixels.width) {
                val c = pixels[x, y]
                val (h, s, v) = hsv(c)
                if (v > 0.12f) {
                    val w = s * s * v + 0.02f
                    val b = ((h / 360f) * buckets).toInt().coerceIn(0, buckets - 1)
                    weight[b] += w; sumH[b] += h * w; sumS[b] += s * w; sumV[b] += v * w
                }
                x += step
            }
            y += step
        }
        val best = weight.indices.maxByOrNull { weight[it] } ?: return null
        if (weight[best] <= 0f) return null
        val h = sumH[best] / weight[best]
        val s = (sumS[best] / weight[best]).coerceIn(0.25f, 0.75f)
        val v = (sumV[best] / weight[best]).coerceIn(0.32f, 0.55f)
        return Color.hsv(h.coerceIn(0f, 359.9f), s, v)
    }

    private fun hsv(c: Color): Triple<Float, Float, Float> {
        val r = c.red; val g = c.green; val b = c.blue
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val d = mx - mn
        val h = when {
            d == 0f -> 0f
            mx == r -> 60f * (((g - b) / d) % 6f)
            mx == g -> 60f * (((b - r) / d) + 2f)
            else -> 60f * (((r - g) / d) + 4f)
        }.let { if (it < 0f) it + 360f else it }
        val s = if (mx == 0f) 0f else d / mx
        return Triple(abs(h) % 360f, s, mx)
    }
}

/** Animated mood color for [song]'s artwork, falling back to [fallback]. */
@Composable
fun rememberArtColor(song: Song?, fallback: Color): State<Color> {
    var target by remember(song?.albumId) { mutableStateOf(song?.let(ArtColors::cached)) }
    LaunchedEffect(song?.albumId) {
        if (target == null && song != null) target = ArtColors.load(song)
    }
    return animateColorAsState(target ?: fallback, tween(600), label = "artColor")
}
