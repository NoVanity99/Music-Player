package com.novanity.musicplayer.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow

/**
 * Spectrum bars of what's playing, coloured from [low] (bass) to [high] (treble), with a faint
 * reflection underneath. The bars sink away smoothly when [playing] is false.
 */
@Composable
fun Visualizer(spectrum: StateFlow<FloatArray>, playing: Boolean, low: Color, high: Color, modifier: Modifier = Modifier) {
    val levels by spectrum.collectAsStateWithLifecycle()
    val presence by animateFloatAsState(if (playing) 1f else 0f, label = "visualizer")
    val colors = remember(low, high) { listOf(low, high) }
    Canvas(modifier) {
        val n = levels.size
        if (n == 0) return@Canvas
        val gap = 3.dp.toPx()
        val barWidth = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
        val floor = size.height * 0.78f // bars above, reflection below
        val brush = Brush.horizontalGradient(colors, 0f, size.width)
        for (i in 0 until n) {
            val h = (floor * levels[i] * presence).coerceAtLeast(2.dp.toPx() * presence)
            val x = i * (barWidth + gap)
            drawRoundRect(brush, Offset(x, floor - h), Size(barWidth, h), CornerRadius(barWidth / 2))
            val reflection = (h * 0.28f).coerceAtMost(size.height - floor)
            drawRoundRect(brush, Offset(x, floor + gap), Size(barWidth, reflection), CornerRadius(barWidth / 2), alpha = 0.18f)
        }
    }
}
