package com.novanity.musicplayer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.novanity.musicplayer.ui.components.DismissDragState
import com.novanity.musicplayer.ui.components.TrackSwipeState
import com.novanity.musicplayer.ui.components.dismissDrag
import com.novanity.musicplayer.ui.components.rememberDismissDragState
import com.novanity.musicplayer.ui.components.rememberTrackSwipeState
import com.novanity.musicplayer.ui.components.trackSwipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The player's swipe gestures must always end fully in one state: changed track, or snapped back. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class GestureTest {
    @get:Rule
    val compose = createComposeRule()

    private var track by mutableIntStateOf(5)
    private var nexts = 0
    private var previouses = 0
    private var canPrevious by mutableStateOf(true)
    private lateinit var swipe: TrackSwipeState

    private fun swipeHost() {
        compose.setContent {
            swipe = rememberTrackSwipeState(track)
            Box(
                Modifier
                    .size(400.dp)
                    .testTag("art")
                    .trackSwipe(swipe, canNext = true, canPrevious = canPrevious,
                        onNext = { nexts++; track++ }, onPrevious = { previouses++; track-- })
            )
        }
    }

    @Test
    fun weakSwipeSnapsBackWithoutChangingTrack() {
        swipeHost()
        compose.onNodeWithTag("art").performTouchInput { swipeLeft(centerX, centerX - width * 0.15f, 700) }
        compose.waitForIdle()
        assertEquals(0, nexts)
        assertEquals(0f, swipe.offset)
        assertEquals(5, track)
    }

    @Test
    fun longSwipeChangesTrackAndSettles() {
        swipeHost()
        compose.onNodeWithTag("art").performTouchInput { swipeLeft(right - 5f, left + width * 0.2f, 700) }
        compose.waitForIdle()
        assertEquals(1, nexts)
        assertEquals(6, track)
        assertEquals(0f, swipe.offset)
    }

    @Test
    fun quickShortFlickChangesTrack() {
        swipeHost()
        compose.onNodeWithTag("art").performTouchInput { swipeRight(centerX, centerX + width * 0.18f, 40) }
        compose.waitForIdle()
        assertEquals(1, previouses)
        assertEquals(4, track)
        assertEquals(0f, swipe.offset)
    }

    @Test
    fun swipeTowardMissingTrackRubberBandsBack() {
        canPrevious = false
        swipeHost()
        compose.onNodeWithTag("art").performTouchInput { swipeRight(left + 5f, right - 5f, 300) }
        compose.waitForIdle()
        assertEquals(0, previouses)
        assertEquals(0f, swipe.offset)
    }

    @Test
    fun mouseDragAlsoChangesTrack() {
        swipeHost()
        compose.onNodeWithTag("art").performMouseInput {
            moveTo(centerRight.copy(x = right - 5f))
            press()
            repeat(20) { moveBy(androidx.compose.ui.geometry.Offset(-width * 0.035f, 0f)) }
            release()
        }
        compose.waitForIdle()
        assertEquals(1, nexts)
        assertEquals(0f, swipe.offset)
    }

    // ---- Dismiss ----

    private var dismissed = 0
    private lateinit var drag: DismissDragState

    private fun dismissHost() {
        compose.setContent {
            drag = rememberDismissDragState()
            Box(Modifier.size(400.dp, 800.dp).testTag("sheet").dismissDrag(drag) { dismissed++ })
        }
    }

    @Test
    fun shortSlowDragSpringsBackToTop() {
        dismissHost()
        compose.onNodeWithTag("sheet").performTouchInput { swipeDown(top + 10f, top + height * 0.15f, 800) }
        compose.waitForIdle()
        assertEquals(0, dismissed)
        assertEquals(0f, drag.offset)
    }

    @Test
    fun longDragDismisses() {
        dismissHost()
        compose.onNodeWithTag("sheet").performTouchInput { swipeDown(top + 10f, top + height * 0.5f, 800) }
        compose.waitForIdle()
        assertEquals(1, dismissed)
    }

    @Test
    fun quickShortFlickDismisses() {
        dismissHost()
        compose.onNodeWithTag("sheet").performTouchInput { swipeDown(top + 10f, top + height * 0.12f, 50) }
        compose.waitForIdle()
        assertEquals(1, dismissed)
        assertTrue(drag.offset > 0f)
    }
}
