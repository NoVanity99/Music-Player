package com.novanity.musicplayer.ui.components

import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/** Flick speed (dp/s) that counts as a deliberate swipe even over a short distance. */
private const val FLING_DP_PER_SECOND = 550f

/**
 * Horizontal "swipe to change track" state. A swipe past 30% of the width or a fast flick slides
 * the content fully off-screen, changes track, waits for the new track, then slides it in from the
 * other side. Anything weaker springs back. There is never a half-way resting state.
 */
@Stable
class TrackSwipeState internal constructor(private val currentKey: State<Any?>) {
    /** Horizontal offset to apply to the swiped content, in px. */
    var offset by mutableFloatStateOf(0f)
        internal set
    internal var width by mutableFloatStateOf(1f)
    internal var busy by mutableStateOf(false)
    internal var fling = FLING_DP_PER_SECOND

    internal fun drag(delta: Float, canNext: Boolean, canPrevious: Boolean) {
        val next = offset + delta
        // Rubber-band when there's nothing to swipe to in that direction.
        val blocked = (next < 0 && !canNext) || (next > 0 && !canPrevious)
        offset = if (blocked) offset + delta * 0.25f else next
    }

    internal suspend fun settle(velocity: Float, canNext: Boolean, canPrevious: Boolean, onNext: () -> Unit, onPrevious: () -> Unit) {
        val threshold = width * 0.3f
        val goNext = canNext && offset < 0 && (offset < -threshold || velocity < -fling) && velocity < fling
        val goPrevious = canPrevious && offset > 0 && (offset > threshold || velocity > fling) && velocity > -fling
        if (!goNext && !goPrevious) {
            animate(offset, 0f, velocity, spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)) { v, _ -> offset = v }
            return
        }
        busy = true
        try {
            val direction = if (goNext) -1f else 1f
            val speed = abs(velocity).coerceAtLeast(1f)
            // Out: finish the motion in the direction of the swipe.
            val remaining = abs(direction * width - offset)
            val outMillis = (remaining / speed * 1000f).toInt().coerceIn(90, 220)
            animate(offset, direction * width, velocity, tween(outMillis, easing = FastOutLinearInEasing)) { v, _ -> offset = v }

            val before = currentKey.value
            if (goNext) onNext() else onPrevious()
            // Wait for the player to report the new track so the right artwork slides in.
            withTimeoutOrNull(1_200) { snapshotFlow { currentKey.value }.first { it != before } }

            // In: enter from the opposite edge.
            offset = -direction * width
            animate(offset, 0f, 0f, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)) { v, _ -> offset = v }
        } finally {
            offset = 0f
            busy = false
        }
    }
}

@Composable
fun rememberTrackSwipeState(currentKey: Any?): TrackSwipeState {
    val key = rememberUpdatedState(currentKey)
    return remember { TrackSwipeState(key) }
}

/** Makes this element swipeable left/right to change track (works with touch and mouse drags). */
@Composable
fun Modifier.trackSwipe(
    state: TrackSwipeState,
    canNext: Boolean,
    canPrevious: Boolean,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
): Modifier {
    val latestNext by rememberUpdatedState(canNext)
    val latestPrevious by rememberUpdatedState(canPrevious)
    val next by rememberUpdatedState(onNext)
    val previous by rememberUpdatedState(onPrevious)
    state.fling = FLING_DP_PER_SECOND * LocalDensity.current.density
    return this
        .onSizeChanged { state.width = it.width.toFloat().coerceAtLeast(1f) }
        .draggable(
            orientation = Orientation.Horizontal,
            enabled = !state.busy,
            state = rememberDraggableState { delta -> state.drag(delta, latestNext, latestPrevious) },
            onDragStopped = { velocity -> state.settle(velocity, latestNext, latestPrevious, next, previous) },
        )
}

/**
 * Vertical "drag down to dismiss" state for full-screen sheets. Dismisses on a drag past 25% of
 * the height or a quick downward flick; otherwise springs smoothly back to the top.
 */
@Stable
class DismissDragState internal constructor() {
    var offset by mutableFloatStateOf(0f)
        internal set
    internal var height by mutableFloatStateOf(1f)
    internal var settling by mutableStateOf(false)
    internal var fling = FLING_DP_PER_SECOND

    /** 0 when resting, 1 when dragged a full screen down. */
    val progress: Float get() = (offset / height).coerceIn(0f, 1f)

    internal suspend fun settle(velocity: Float, onDismiss: () -> Unit) {
        settling = true
        try {
            settleInner(velocity, onDismiss)
        } finally {
            settling = false
        }
    }

    private suspend fun settleInner(velocity: Float, onDismiss: () -> Unit) {
        val dismiss = velocity > fling || (offset > height * 0.25f && velocity > -fling)
        if (dismiss) {
            val remaining = (height - offset).coerceAtLeast(0f)
            val millis = (remaining / velocity.coerceAtLeast(fling * 1.5f) * 1000f).toInt().coerceIn(120, 280)
            animate(offset, height, velocity, tween(millis, easing = FastOutLinearInEasing)) { v, _ -> offset = v }
            onDismiss()
        } else {
            animate(offset, 0f, velocity, spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)) { v, _ -> offset = v.coerceAtLeast(0f) }
        }
    }
}

@Composable
fun rememberDismissDragState(): DismissDragState = remember { DismissDragState() }

@Composable
fun Modifier.dismissDrag(state: DismissDragState, onDismiss: () -> Unit): Modifier {
    val dismiss by rememberUpdatedState(onDismiss)
    state.fling = FLING_DP_PER_SECOND * LocalDensity.current.density
    return this
        .onSizeChanged { state.height = it.height.toFloat().coerceAtLeast(1f) }
        .draggable(
            orientation = Orientation.Vertical,
            enabled = !state.settling,
            state = rememberDraggableState { delta -> state.offset = (state.offset + delta).coerceAtLeast(0f) },
            onDragStopped = { velocity -> state.settle(velocity, dismiss) },
        )
}
