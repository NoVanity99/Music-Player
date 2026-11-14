package com.novanity.musicplayer.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow

/**
 * The music as moving light: bars that grow up and down from a centre line (bass in the middle,
 * treble towards the edges, mirrored left and right), each glowing softly, with a small cap that
 * hangs at the peak and drifts back down. Every bar eases towards the sound instead of jumping,
 * so it flows. The bars settle to a thin line when [playing] is false.
 */
@Composable
fun Visualizer(spectrum: StateFlow<FloatArray>, playing: Boolean, low: Color, high: Color, modifier: Modifier = Modifier) {
    val levels by spectrum.collectAsStateWithLifecycle()
    val target by rememberUpdatedState(levels)
    val presence by animateFloatAsState(if (playing) 1f else 0f, label = "visualizer")
    // Smoothed heights and falling peak caps, advanced once per frame.
    val shown = remember { FloatArray(64) }
    val peaks = remember { FloatArray(64) }
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceIn(0f, 0.1f)
                last = now
                val t = target
                for (i in 0 until minOf(t.size, shown.size)) {
                    val goal = t[i].coerceIn(0f, 1f)
                    // Quick to rise, slower to fall: lively without flicker.
                    val speed = if (goal > shown[i]) 22f else 9f
                    shown[i] += (goal - shown[i]) * (speed * dt).coerceAtMost(1f)
                    peaks[i] = maxOf(shown[i], peaks[i] - 0.45f * dt)
                }
                frame++
            }
        }
    }
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") frame // redraw every frame
        val n = minOf(levels.size, shown.size)
        if (n == 0) return@Canvas
        val bars = n * 2 // mirrored: treble ... bass | bass ... treble
        val gap = (size.width / bars * 0.28f).coerceIn(1.5.dp.toPx(), 5.dp.toPx())
        val barWidth = ((size.width - gap * (bars - 1)) / bars).coerceAtLeast(1f)
        val mid = size.height / 2
        val reach = mid - 3.dp.toPx()
        val round = CornerRadius(barWidth / 2)
        for (b in 0 until bars) {
            val i = if (b < n) n - 1 - b else b - n // index into the spectrum
            val level = shown[i] * presence
            val h = (reach * level).coerceAtLeast(1.2.dp.toPx())
            val x = b * (barWidth + gap)
            val color = lerp(low, high, i / (n - 1f).coerceAtLeast(1f))
            // Glow: a wider, faint copy behind the bar.
            drawRoundRect(color.copy(alpha = 0.16f * presence), Offset(x - barWidth * 0.6f, mid - h * 1.12f), Size(barWidth * 2.2f, h * 2.24f), CornerRadius(barWidth * 1.1f))
            // The bar itself: brightest at the centre line, fading towards its tips.
            drawRoundRect(
                Brush.verticalGradient(listOf(color.copy(alpha = 0.55f), lerp(color, Color.White, 0.25f), color.copy(alpha = 0.55f)), mid - h, mid + h),
                Offset(x, mid - h), Size(barWidth, h * 2), round,
            )
            // Peak caps, above and below.
            val p = reach * peaks[i] * presence
            if (p > h + 2.dp.toPx()) {
                val cap = Size(barWidth, 2.dp.toPx())
                drawRoundRect(Color.White.copy(alpha = 0.7f * presence), Offset(x, mid - p - cap.height), cap, CornerRadius(1.dp.toPx()))
                drawRoundRect(Color.White.copy(alpha = 0.7f * presence), Offset(x, mid + p), cap, CornerRadius(1.dp.toPx()))
            }
        }
    }
}
