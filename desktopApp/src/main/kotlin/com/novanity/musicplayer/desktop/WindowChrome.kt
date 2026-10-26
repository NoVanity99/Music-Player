package com.novanity.musicplayer.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CropSquare
import androidx.compose.material.icons.rounded.FilterNone
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.awt.Cursor
import java.awt.Frame
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.Window

/**
 * Maximize/restore for the undecorated window. Done by hand rather than with the OS maximized
 * state, which on Windows would cover the taskbar for a window without a native frame.
 */
class WindowFrame(private val window: Window) {
    var maximized by mutableStateOf(false)
        private set
    private var restoreBounds: Rectangle? = null

    /** The screen the window is mostly on, minus the taskbar. */
    fun usableBounds(): Rectangle {
        val gc = window.graphicsConfiguration
        val b = gc.bounds
        val i = Toolkit.getDefaultToolkit().getScreenInsets(gc)
        return Rectangle(b.x + i.left, b.y + i.top, b.width - i.left - i.right, b.height - i.top - i.bottom)
    }

    fun maximize() {
        if (maximized) return
        restoreBounds = window.bounds
        window.bounds = usableBounds()
        maximized = true
    }

    /** Back to the previous size; [anchor] keeps that screen point under the cursor while dragging. */
    fun restore(anchor: Point? = null) {
        if (!maximized) return
        val old = restoreBounds ?: Rectangle(window.x + 80, window.y + 80, 1280, 820)
        if (anchor != null) {
            val fraction = (anchor.x - window.x).toFloat() / window.width.coerceAtLeast(1)
            old.setLocation(anchor.x - (old.width * fraction).toInt(), anchor.y - 12)
        }
        window.bounds = old
        maximized = false
    }

    fun toggleMaximize() = if (maximized) restore() else maximize()

    fun minimize() {
        (window as? Frame)?.extendedState = Frame.ICONIFIED
    }
}

/**
 * The app-drawn title bar: a draggable strip (double-click to maximize, drag to the top of the
 * screen to maximize) with minimize, maximize and close buttons on the right.
 */
@Composable
fun TitleBar(frame: WindowFrame, window: Window, height: Dp, contentColor: Color, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(height)) {
        Box(Modifier.weight(1f).fillMaxHeight().windowDrag(frame, window))
        ChromeButton(Icons.Rounded.Remove, "Minimize", contentColor, height, onClick = frame::minimize)
        ChromeButton(
            if (frame.maximized) Icons.Rounded.FilterNone else Icons.Rounded.CropSquare,
            if (frame.maximized) "Restore" else "Maximize",
            contentColor,
            height,
            iconSize = 14.dp,
            onClick = frame::toggleMaximize,
        )
        ChromeButton(Icons.Rounded.Close, "Close", contentColor, height, hoverBackground = Color(0xFFE81123), hoverContent = Color.White, onClick = onClose)
    }
}

@Composable
private fun ChromeButton(
    icon: ImageVector,
    description: String,
    color: Color,
    height: Dp,
    iconSize: Dp = 16.dp,
    hoverBackground: Color = color.copy(alpha = 0.1f),
    hoverContent: Color = color,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        Modifier
            .width(46.dp)
            .height(height)
            .hoverable(interaction)
            .background(if (hovered) hoverBackground else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = if (hovered) hoverContent else color.copy(alpha = 0.85f), modifier = Modifier.size(iconSize))
    }
}

/** Moves the window with the mouse. Uses screen coordinates so the window doesn't jitter. */
private fun Modifier.windowDrag(frame: WindowFrame, window: Window): Modifier {
    var lastClick = 0L
    return pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            val now = System.currentTimeMillis()
            if (now - lastClick < 400) {
                lastClick = 0L
                frame.toggleMaximize()
                // Swallow the rest of the second click.
                while (awaitPointerEvent().changes.any { it.pressed }) Unit
                return@awaitEachGesture
            }
            lastClick = now
            val startMouse = MouseInfo.getPointerInfo()?.location ?: return@awaitEachGesture
            var startWindow = window.location
            var moved = false
            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.none { it.pressed }) break
                val mouse = MouseInfo.getPointerInfo()?.location ?: break
                val dx = mouse.x - startMouse.x
                val dy = mouse.y - startMouse.y
                if (!moved && dx * dx + dy * dy < 16) continue
                if (!moved && frame.maximized) {
                    // Dragging a maximized window pulls it back to its normal size under the cursor.
                    frame.restore(anchor = mouse)
                    startWindow = Point(window.x - dx, window.y - dy)
                }
                moved = true
                window.setLocation(startWindow.x + dx, startWindow.y + dy)
                event.changes.forEach { it.consume() }
            }
            if (moved) {
                lastClick = 0L
                val mouse = MouseInfo.getPointerInfo()?.location
                if (mouse != null && mouse.y <= frame.usableBounds().y + 1) frame.maximize()
            }
        }
    }
}

private enum class Edge(val cursor: Int, val left: Boolean = false, val top: Boolean = false, val right: Boolean = false, val bottom: Boolean = false) {
    N(Cursor.N_RESIZE_CURSOR, top = true),
    S(Cursor.S_RESIZE_CURSOR, bottom = true),
    W(Cursor.W_RESIZE_CURSOR, left = true),
    E(Cursor.E_RESIZE_CURSOR, right = true),
    NW(Cursor.NW_RESIZE_CURSOR, left = true, top = true),
    NE(Cursor.NE_RESIZE_CURSOR, right = true, top = true),
    SW(Cursor.SW_RESIZE_CURSOR, left = true, bottom = true),
    SE(Cursor.SE_RESIZE_CURSOR, right = true, bottom = true),
}

/** Invisible borders for resizing, since an undecorated window has none of its own. */
@Composable
fun BoxScope.ResizeEdges(frame: WindowFrame, window: Window) {
    if (frame.maximized) return
    val t = 5.dp
    val c = 12.dp
    @Composable
    fun handle(edge: Edge, modifier: Modifier) = Box(modifier.resizeDrag(edge, window).pointerHoverIcon(PointerIcon(Cursor(edge.cursor))))
    handle(Edge.N, Modifier.align(Alignment.TopCenter).fillMaxWidth().height(t))
    handle(Edge.S, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(t))
    handle(Edge.W, Modifier.align(Alignment.CenterStart).fillMaxHeight().width(t))
    handle(Edge.E, Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(t))
    handle(Edge.NW, Modifier.align(Alignment.TopStart).size(c))
    handle(Edge.NE, Modifier.align(Alignment.TopEnd).size(c))
    handle(Edge.SW, Modifier.align(Alignment.BottomStart).size(c))
    handle(Edge.SE, Modifier.align(Alignment.BottomEnd).size(c))
}

private fun Modifier.resizeDrag(edge: Edge, window: Window): Modifier = pointerInput(edge) {
    awaitEachGesture {
        awaitFirstDown()
        val start = MouseInfo.getPointerInfo()?.location ?: return@awaitEachGesture
        val bounds = window.bounds
        val min = window.minimumSize
        while (true) {
            val event = awaitPointerEvent()
            if (event.changes.none { it.pressed }) break
            val mouse = MouseInfo.getPointerInfo()?.location ?: break
            val dx = mouse.x - start.x
            val dy = mouse.y - start.y
            val r = Rectangle(bounds)
            if (edge.right) r.width = (bounds.width + dx).coerceAtLeast(min.width)
            if (edge.bottom) r.height = (bounds.height + dy).coerceAtLeast(min.height)
            if (edge.left) {
                r.width = (bounds.width - dx).coerceAtLeast(min.width)
                r.x = bounds.x + bounds.width - r.width
            }
            if (edge.top) {
                r.height = (bounds.height - dy).coerceAtLeast(min.height)
                r.y = bounds.y + bounds.height - r.height
            }
            window.bounds = r
            event.changes.forEach { it.consume() }
        }
    }
}

/** Fills the window; draws [content], then the title bar and resize edges over it. */
@Composable
fun WindowChrome(
    frame: WindowFrame,
    window: Window,
    titleBarHeight: Dp,
    titleBarColor: Color,
    onClose: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        content()
        TitleBar(frame, window, titleBarHeight, titleBarColor, onClose)
        ResizeEdges(frame, window)
    }
}
