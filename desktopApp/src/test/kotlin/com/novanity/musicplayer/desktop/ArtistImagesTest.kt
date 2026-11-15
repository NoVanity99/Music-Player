package com.novanity.musicplayer.desktop

import com.novanity.musicplayer.data.ArtistImages
import com.novanity.musicplayer.data.HttpText
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ArtistImagesTest {
    private fun store() = PropertiesStore(File(Files.createTempDirectory("pics").toFile(), "pics.properties"))

    private fun pic(id: String) = "https://cdn-images.dzcdn.net/images/artist/$id/1000x1000-000000-80-0-0.jpg"

    /** A fake catalogue: two artists called Drake, a band whose name is close to "slayr", one with no picture. */
    private val fake: (String) -> HttpText = { url ->
        when {
            url.contains("q=Drake") -> HttpText(200, """{"data":[
                {"name":"Drake","nb_fan":120,"picture_xl":"${pic("small")}"},
                {"name":"Drake","nb_fan":9000000,"picture_xl":"${pic("big")}"},
                {"name":"Drake Bell","nb_fan":50000000,"picture_xl":"${pic("bell")}"}]}""")
            url.contains("q=slayr") -> HttpText(200, """{"data":[
                {"name":"Slayer","nb_fan":3000000,"picture_xl":"${pic("slayer")}"},
                {"name":"SLAYR","nb_fan":4113,"picture_xl":"${pic("slayr")}"}]}""")
            url.contains("q=Nobody") -> HttpText(200, """{"data":[{"name":"Nobody","nb_fan":3,"picture_xl":"${pic("")}"}]}""")
            url.contains("q=Down") -> HttpText(500, "oops")
            else -> HttpText(200, """{"data":[]}""")
        }
    }

    @Test
    fun picksTheBestKnownArtistWithExactlyThatName() = runBlocking {
        var calls = 0
        val store = store()
        val images = ArtistImages(store, { url -> calls++; fake(url) })
        assertEquals(pic("big"), images.auto("Drake"))
        assertEquals(pic("slayr"), images.auto("slayr"), "a different artist with a similar name is not used")
        assertNull(images.auto("Nobody"), "an artist without a picture falls back to an album cover")
        assertNull(images.auto("Someone Else"))
        val before = calls
        // Answers (including "none") are remembered, also by a later run of the app.
        assertEquals(pic("big"), images.auto("drake"))
        assertNull(images.auto("Nobody"))
        assertEquals(pic("big"), ArtistImages(store, { url -> calls++; fake(url) }).auto("Drake"))
        assertEquals(before, calls)
        // A failed lookup is not remembered as "none".
        assertNull(images.auto("Down"))
        assertNull(images.knownAuto("Down"))
        assertNull(images.auto("Down"))
        assertEquals(before + 2, calls)
    }

    @Test
    fun searchListsPicturesAndChoicesAreKept() = runBlocking {
        val store = store()
        val images = ArtistImages(store, { url -> fake(url) })
        assertEquals(listOf("Drake Bell", "Drake", "Drake"), images.search("Drake").map { it.label })
        assertEquals(emptyList(), images.search("Nobody"))
        assertNull(images.choice("Drake", "banner"))
        val v = images.version.value
        images.setChoice("Drake", "banner", "album:42")
        assertEquals("album:42", ArtistImages(store, { url -> fake(url) }).choice("drake", "banner"))
        assertNull(images.choice("Drake", "avatar"))
        assertEquals(v + 1, images.version.value)
        images.setChoice("Drake", "banner", null)
        assertNull(images.choice("Drake", "banner"))
        assertEquals("https://x/images/artist/a/250x250-000000-80-0-0.jpg", ArtistImages.small("https://x/images/artist/a/1000x1000-000000-80-0-0.jpg"))
    }
}
