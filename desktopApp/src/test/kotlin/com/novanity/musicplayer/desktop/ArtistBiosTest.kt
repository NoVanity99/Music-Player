package com.novanity.musicplayer.desktop

import com.novanity.musicplayer.data.ArtistBios
import com.novanity.musicplayer.data.HttpText
import com.novanity.musicplayer.updatecore.UpdateCore
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtistBiosTest {
    private fun store() = PropertiesStore(File(Files.createTempDirectory("bios").toFile(), "bios.properties"))

    private fun summary(title: String, description: String, extract: String, type: String = "standard") = """
        {"type":"$type","title":"$title","description":"$description","extract":"$extract",
         "content_urls":{"desktop":{"page":"https://en.wikipedia.org/wiki/${title.replace(' ', '_')}"}}}
    """.trimIndent()

    /** A fake Wikipedia: "Drake" is a disambiguation page, "Drake (musician)" the rapper. */
    private val fakeWiki: (String) -> HttpText = { url ->
        when {
            url.contains("/page/summary/Drake_%28musician%29") ->
                HttpText(200, summary("Drake (musician)", "Canadian rapper and singer (born 1986)", "Aubrey Drake Graham is a Canadian rapper."))
            url.contains("/page/summary/Drake?") -> HttpText(200, summary("Drake", "Topics referred to by the same term", "Drake may refer to:", "disambiguation"))
            url.contains("/search/title") && url.contains("q=Drake") -> HttpText(
                200,
                """{"pages":[{"key":"Drake","title":"Drake","description":"Topics referred to by the same term"},
                   {"key":"Drake_(musician)","title":"Drake (musician)","description":"Canadian rapper and singer (born 1986)"}]}""",
            )
            url.contains("/page/summary/Sol_Vega") -> HttpText(404, "{}")
            url.contains("q=Sol%20Vega") -> HttpText(200, """{"pages":[{"key":"Sol_Gabetta","title":"Sol Gabetta","description":"Argentine cellist"}]}""")
            url.contains("/page/summary/Daft_Punk") -> HttpText(200, summary("Daft Punk", "French electronic music duo", "Daft Punk were a French duo."))
            else -> HttpText(404, "{}")
        }
    }

    @Test
    fun findsTheMusiciansPageAndSkipsNamesakes() = runBlocking {
        var calls = 0
        val bios = ArtistBios(store(), { url -> calls++; fakeWiki(url) })
        val drake = assertNotNull(bios.bio("Drake"))
        assertEquals("Aubrey Drake Graham is a Canadian rapper.", drake.text)
        assertEquals("https://en.wikipedia.org/wiki/Drake_(musician)", drake.sourceUrl)
        assertNotNull(bios.bio("Daft Punk"))
        // "Sol Gabetta" is a musician, but not this artist: no bio rather than a wrong one.
        assertNull(bios.bio("Sol Vega"))
        val before = calls
        bios.bio("Drake"); bios.bio("Sol Vega")
        assertEquals(before, calls, "answers are cached")
        Unit
    }

    @Test
    fun cachedAcrossSessionsButNetworkErrorsAreRetried() = runBlocking {
        val store = store()
        var offline = true
        val get: suspend (String) -> HttpText = { url -> if (offline) throw java.io.IOException("offline") else fakeWiki(url) }
        assertNull(ArtistBios(store, get).bio("Daft Punk"))
        offline = false
        assertNotNull(ArtistBios(store, get).bio("Daft Punk"), "retried after the network error")
        offline = true
        assertNotNull(ArtistBios(store, get).bio("Daft Punk"), "a new session reads the saved bio")
        // Rate limited (HTTP 429): no bio now, and nothing cached.
        assertNull(ArtistBios(store(), { HttpText(429, "slow down") }).bio("Drake"))
        Unit
    }

    @Test
    fun descriptionChecks() {
        assertTrue(ArtistBios.isMusical("American rapper (born 1983)"))
        assertTrue(ArtistBios.isMusical("British folk rock band"))
        assertTrue(!ArtistBios.isMusical("2020 studio album by Dua Lipa"))
        assertTrue(!ArtistBios.isMusical("Time after the present"))
        assertTrue(!ArtistBios.isMusical("Music streaming service"))
        assertTrue(ArtistBios.isMusical("French electronic music duo (1993–2021)"))
        assertTrue(ArtistBios.isMusical("Canadian singer and songwriter (born 1990)"))
        assertTrue(ArtistBios.sameName("Future (rapper)", "Future"))
        assertTrue(ArtistBios.sameName("Mumford_&_Sons", "Mumford & Sons"))
        assertTrue(!ArtistBios.sameName("Sol Gabetta", "Sol Vega"))
    }

    /** Against the real Wikipedia: `LIVE_WIKIPEDIA=1 ./gradlew :desktopApp:test --tests '*ArtistBiosTest*'`. */
    @Test
    fun liveWikipedia() = runBlocking {
        assumeTrue(System.getenv("LIVE_WIKIPEDIA") == "1")
        val bios = ArtistBios(store(), { url ->
            // Be gentle, and wait out rate limits: this tests the matching, not Wikipedia's throttle.
            var answer: HttpText
            var tries = 0
            do {
                Thread.sleep(if (tries == 0) 400L else 3_000L * tries)
                val (status, body) = UpdateCore.getText(url, "VanityPlayer-test (https://github.com/NoVanity99/Music-Player)")
                answer = HttpText(status, body)
            } while (answer.status == 429 && ++tries < 6)
            answer
        })
        for (name in listOf("Drake", "Future", "Daft Punk", "Mumford & Sons", "The Weeknd", "Sol Vega", "Tidal")) {
            val bio = bios.bio(name)
            println("$name -> ${bio?.sourceUrl} | ${bio?.text?.take(90)}")
        }
        assertTrue(bios.bio("Drake")!!.sourceUrl.endsWith("Drake_(musician)"))
        assertTrue(bios.bio("Future")!!.sourceUrl.endsWith("Future_(rapper)"))
        assertNotNull(bios.bio("Mumford & Sons"))
        assertNull(bios.bio("Sol Vega"))
        assertNull(bios.bio("Tidal"), "not the streaming service")
        assertNull(bios.bio("Tidal"), "not the streaming service")
        Unit
    }
}
