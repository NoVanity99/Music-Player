package com.novanity.musicplayer.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.novanity.musicplayer.platform.LocalPlatform

/** Slim slider (volume): click or drag to set, scroll wheel to nudge, thumb appears on hover. */
@Composable
fun SlimSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    color: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var dragging by remember { mutableStateOf(false) }
    val thumb by animateDpAsState(if (hovered || dragging) 6.dp else 0.dp, label = "thumb")
    val latest by rememberUpdatedState(onValueChange)
    val current by rememberUpdatedState(value)
    Canvas(
        modifier
            .height(24.dp)
            .hoverable(interaction)
            .pointerInput(Unit) { detectTapGestures { latest((it.x / size.width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true; latest((it.x / size.width).coerceIn(0f, 1f)) },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { change, _ ->
                    change.consume()
                    latest((change.position.x / size.width).coerceIn(0f, 1f))
                }
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        if (e.type == PointerEventType.Scroll) {
                            val dy = e.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                            if (dy != 0f) latest((current + if (dy < 0) 0.05f else -0.05f).coerceIn(0f, 1f))
                            e.changes.forEach { it.consume() }
                        }
                    }
                }
            },
    ) {
        val h = 4.dp.toPx()
        val top = (size.height - h) / 2
        val r = CornerRadius(h / 2, h / 2)
        drawRoundRect(trackColor, Offset(0f, top), Size(size.width, h), r)
        drawRoundRect(if (hovered || dragging) color else color.copy(alpha = 0.85f), Offset(0f, top), Size(size.width * value, h), r)
        if (thumb > 0.dp) drawCircle(color, thumb.toPx(), Offset(size.width * value, size.height / 2))
    }
}

/**
 * A row of cards. On phones it scrolls sideways; with a mouse (desktop) it shows as many cards as
 * fit the width, sized evenly, since sideways scrolling is awkward with a mouse wheel.
 */
@Composable
fun <T> CardRow(
    items: List<T>,
    key: (T) -> Any,
    minCardWidth: Dp = CardWidth,
    card: @Composable (T, Modifier) -> Unit,
) {
    val gap = 14.dp
    if (!LocalPlatform.current.isDesktop) {
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(gap)) {
            items(items, key = key) { card(it, Modifier.width(minCardWidth)) }
        }
        return
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        val count = ((maxWidth + gap) / (minCardWidth + gap)).toInt().coerceAtLeast(1)
        val width = (maxWidth - gap * (count - 1)) / count
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            items.take(count).forEach { card(it, Modifier.width(width)) }
        }
    }
}
