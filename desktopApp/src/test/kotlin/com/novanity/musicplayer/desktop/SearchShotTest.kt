package com.novanity.musicplayer.desktop

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/** Search results, captured through the UI-test harness (it processes async state like the real app). */
@OptIn(ExperimentalTestApi::class)
class SearchShotTest {
    @Test
    fun searchResults() = runDesktopComposeUiTest(1280, 800) {
        val t = DesktopScreenshotTest()
        val f = t.fixtureForDebug()
        javax.swing.SwingUtilities.invokeAndWait { f.library.selectTab(com.novanity.musicplayer.ui.Tab.SEARCH); f.library.setSearchQuery("so") }
        setContent { t.Content(f) }
        repeat(20) { mainClock.advanceTimeBy(200); Thread.sleep(50); waitForIdle() }
        File("screenshots").mkdirs()
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File("screenshots/07b_search_results.png"))
    }
}
