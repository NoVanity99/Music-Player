package com.novanity.musicplayer.desktop

import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
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

/** Room the floating window buttons take in the top-right corner (their width plus margins). */
val WindowControlsWidth = 132.dp

/** Height of the strip along the top of the window that drags it (there's no title bar). */
private val DragZone = 40.dp

/**
 * Minimize, maximize and close as a small floating pill in the top-right corner. Always shown,
 * above everything else in the window.
 */
@Composable
private fun WindowButtons(frame: WindowFrame, contentColor: Color, backing: Color, onClose: () -> Unit, modifier: Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            // Solid enough to stay readable over artwork and bright banners.
            .background(backing.copy(alpha = 0.72f))
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChromeButton(Icons.Rounded.Remove, "Minimize", contentColor, onClick = frame::minimize)
        ChromeButton(
            if (frame.maximized) Icons.Rounded.FilterNone else Icons.Rounded.CropSquare,
            if (frame.maximized) "Restore" else "Maximize",
            contentColor,
            iconSize = 13.dp,
            onClick = frame::toggleMaximize,
        )
        ChromeButton(Icons.Rounded.Close, "Close", contentColor, hoverBackground = Color(0xFFE81123), hoverContent = Color.White, onClick = onClose)
    }
}

@Composable
private fun ChromeButton(
    icon: ImageVector,
    description: String,
    color: Color,
    iconSize: Dp = 15.dp,
    hoverBackground: Color = color.copy(alpha = 0.14f),
    hoverContent: Color = color,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(if (hovered) hoverBackground else Color.Transparent, tween(140), label = "chromeBg")
    val tint by animateColorAsState(if (hovered) hoverContent else color.copy(alpha = 0.8f), tween(140), label = "chromeTint")
    Box(
        Modifier
            .padding(vertical = 3.dp)
            .size(width = 36.dp, height = 26.dp)
            .clip(RoundedCornerShape(50))
            .hoverable(interaction)
            .background(background)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/**
 * Lets the top strip of the window drag it, even though app content is drawn there. A press that
 * turns into a drag moves the window (and never clicks what was under it); a plain click goes to
 * the content as usual. Double-clicking an empty spot maximizes or restores.
 */
private fun Modifier.windowDragZone(frame: WindowFrame, window: Window): Modifier = this
    .pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            // Not in the strip, or on the window buttons themselves.
            if (down.position.y > DragZone.toPx() || down.position.x > size.width - WindowControlsWidth.toPx()) return@awaitEachGesture
            val startMouse = MouseInfo.getPointerInfo()?.location ?: return@awaitEachGesture
            var startWindow = window.location
            var dragging = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.none { it.pressed }) {
                    if (dragging) event.changes.forEach { it.consume() } // the release isn't a click
                    break
                }
                val mouse = MouseInfo.getPointerInfo()?.location ?: break
                val dx = mouse.x - startMouse.x
                val dy = mouse.y - startMouse.y
                if (!dragging && dx * dx + dy * dy < 36) continue
                if (!dragging && frame.maximized) {
                    // Dragging a maximized window pulls it back to its normal size under the cursor.
                    frame.restore(anchor = mouse)
                    startWindow = Point(window.x - dx, window.y - dy)
                }
                dragging = true
                window.setLocation(startWindow.x + dx, startWindow.y + dy)
                event.changes.forEach { it.consume() }
            }
            if (dragging) {
                val mouse = MouseInfo.getPointerInfo()?.location
                if (mouse != null && mouse.y <= frame.usableBounds().y + 1) frame.maximize()
            }
        }
    }
    .pointerInput(Unit) {
        var lastTap = 0L
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
            if (down.position.y > DragZone.toPx() || down.position.x > size.width - WindowControlsWidth.toPx()) return@awaitEachGesture
            val up = waitForUpOrCancellation(PointerEventPass.Final) ?: return@awaitEachGesture
            // Only clicks nothing else used (not a button, not a drag) count towards a double-click.
            if (down.isConsumed || up.isConsumed) {
                lastTap = 0L
                return@awaitEachGesture
            }
            val now = System.currentTimeMillis()
            if (now - lastTap < 400) {
                lastTap = 0L
                frame.toggleMaximize()
            } else {
                lastTap = now
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

/**
 * Fills the window with [content] and adds what an undecorated window needs: dragging from the
 * top strip, the floating window buttons and resize edges.
 */
@Composable
fun WindowChrome(
    frame: WindowFrame,
    window: Window,
    controlsColor: Color,
    controlsBacking: Color,
    onClose: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize().windowDragZone(frame, window)) {
        content()
        WindowButtons(frame, controlsColor, controlsBacking, onClose, Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 12.dp))
        ResizeEdges(frame, window)
    }
}
