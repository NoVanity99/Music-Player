package com.novanity.musicplayer.ui.components

import android.content.Context
import android.util.LruCache
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
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import com.novanity.musicplayer.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Extracts a mood color from album art, tuned to sit behind white text. */
object ArtColors {
    private val cache = LruCache<Long, Int>(256)
    private const val NONE = 0

    fun cached(song: Song): Color? = cache.get(song.albumId)?.takeIf { it != NONE }?.let(::Color)

    suspend fun load(context: Context, song: Song): Color? {
        cache.get(song.albumId)?.let { return if (it == NONE) null else Color(it) }
        val bitmap = ArtworkLoader.load(context, song, ArtworkLoader.SMALL)
        val argb = if (bitmap == null) {
            NONE
        } else {
            withContext(Dispatchers.Default) {
                val palette = Palette.from(bitmap).maximumColorCount(16).generate()
                val swatch = palette.vibrantSwatch ?: palette.dominantSwatch ?: palette.mutedSwatch
                swatch?.rgb?.let(::tame) ?: NONE
            }
        }
        cache.put(song.albumId, argb)
        return if (argb == NONE) null else Color(argb)
    }

    /** Clamp saturation and brightness so the color reads as a rich, dark backdrop. */
    private fun tame(rgb: Int): Int {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(rgb, hsv)
        hsv[1] = hsv[1].coerceIn(0.25f, 0.75f)
        hsv[2] = hsv[2].coerceIn(0.32f, 0.55f)
        return android.graphics.Color.HSVToColor(hsv).let { if (it == NONE) it or 1 else it }
    }
}

/** Animated mood color for [song]'s artwork, falling back to [fallback]. */
@Composable
fun rememberArtColor(song: Song?, fallback: Color): State<Color> {
    val context = LocalContext.current
    var target by remember(song?.albumId) { mutableStateOf(song?.let(ArtColors::cached)) }
    LaunchedEffect(song?.albumId) {
        if (target == null && song != null) target = ArtColors.load(context, song)
    }
    return animateColorAsState(target ?: fallback, tween(600), label = "artColor")
}

