package com.novanity.musicplayer.desktop

import java.io.File
import kotlin.test.Test

/** Dev helper: `DEMO_MUSIC_DIR=/some/dir ./gradlew :desktopApp:test --tests '*DemoMusicTest*'` writes demo tracks. */
class DemoMusicTest {
    @Test
    fun writeDemoMusic() {
        val target = System.getenv("DEMO_MUSIC_DIR")?.takeIf { it.isNotEmpty() } ?: return
        val tracks = listOf(
            Triple("Neon Harbor", "Midnight Drive", listOf("City Lights", "Overpass", "Afterglow")),
            Triple("Sol Vega", "Golden Hour", listOf("Sunrise Avenue", "Honey")),
            Triple("Tidal", "Deep Blue", listOf("Undertow", "Coral")),
        )
        tracks.forEachIndexed { a, (artist, album, titles) ->
            val dir = File(target, "$artist/$album").apply { mkdirs() }
            titles.forEachIndexed { i, t ->
                val ext = listOf("mp3", "flac", "opus")[(a + i) % 3]
                writeTestTrack(File(dir, "${i + 1} $t.$ext"), 31.0 + i, 220.0 + 110 * (a + i), t, artist, album, i + 1)
            }
        }
        File(target, "Sol Vega/Golden Hour/1 Sunrise Avenue.lrc").writeText(
            "[00:00.50]Morning light on the avenue\n[00:04.00]Coffee steam and a quiet tune\n[00:08.00]Every window gold and new\n[00:12.00]I keep on walking back to you\n",
        )
    }
}
