package com.novanity.musicplayer.desktop

import com.novanity.musicplayer.lyrics.Lyrics
import com.novanity.musicplayer.playback.PlayerUiState
import com.novanity.musicplayer.playback.QueueEntry
import com.novanity.musicplayer.ui.PlayerViewModel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeatureTest {
    @Test
    fun parsesSyncedLyricsWithRepeatsAndOffset() {
        val lrc = """
            [ti:Song]
            [offset:500]
            [00:12.50][01:02.00]Chorus line
            [00:05.10]First line
            [00:08]Second line
        """.trimIndent()
        val lyrics = assertNotNull(Lyrics.parse(lrc))
        assertTrue(lyrics.synced)
        assertEquals(listOf("First line", "Second line", "Chorus line", "Chorus line"), lyrics.lines.map { it.text })
        assertEquals(listOf(4_600L, 7_500L, 12_000L, 61_500L), lyrics.lines.map { it.timeMs })
        assertEquals(-1, lyrics.indexAt(1_000))
        assertEquals(1, lyrics.indexAt(9_000))
        assertEquals(3, lyrics.indexAt(70_000))
    }

    @Test
    fun parsesPlainLyrics() {
        val lyrics = assertNotNull(Lyrics.parse("\nVerse one\nVerse two\n\n"))
        assertFalse(lyrics.synced)
        assertEquals(listOf("Verse one", "Verse two"), lyrics.lines.map { it.text })
        assertNull(Lyrics.parse("  \n "))
    }

    private class RecordingPlayer : com.novanity.musicplayer.playback.PlayerController by FakePlayer() {
        val fake = FakePlayer()
        override val state = fake.state
        override val upNext = fake.upNext
        var paused = 0
        var lastVolume = 1f
        override fun pause() { paused++ }
        override fun setVolume(volume: Float) { lastVolume = volume }
    }

    private fun waitFor(what: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < end) { if (check()) return; Thread.sleep(25) }
        error("Timed out: $what")
    }

    @Test
    fun sleepTimerPausesAtEndOfSongAndAfterDeadline() {
        val p = RecordingPlayer()
        lateinit var vm: PlayerViewModel
        SwingUtilities.invokeAndWait {
            p.state.value = PlayerUiState(true, QueueEntry(0, "a", 1, "A", "X"), true, 200_000)
            vm = PlayerViewModel(p)
            vm.sleepAtEndOfTrack()
        }
        Thread.sleep(400)
        assertEquals(0, p.paused, "still playing the same song")
        SwingUtilities.invokeAndWait { p.state.value = p.state.value.copy(current = QueueEntry(1, "b", 2, "B", "X")) }
        waitFor("paused when the song changed") { p.paused == 1 }
        waitFor("timer cleared") { vm.sleepTimer.value == null }

        SwingUtilities.invokeAndWait { vm.startSleepTimer(0) }
        waitFor("paused at deadline") { p.paused == 2 }
        waitFor("volume restored after fade") { p.lastVolume == 1f && vm.sleepTimer.value == null }
    }
}
