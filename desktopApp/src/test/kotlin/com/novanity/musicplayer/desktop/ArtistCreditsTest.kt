package com.novanity.musicplayer.desktop

import com.novanity.musicplayer.data.ArtistIndex
import com.novanity.musicplayer.data.ArtistPart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArtistCreditsTest {
    private val index = ArtistIndex(
        listOf(
            "Drake", "Drake & Future", "Tyler, The Creator", "Tyler, The Creator & Kali Uchis",
            "Mumford & Sons", "Earth, Wind & Fire", "The Weeknd feat. Daft Punk", "Daft Punk",
            "Simon & Garfunkel", "SZA", "drake ft. sza", "Florence + The Machine",
        )
    )

    private fun parts(credit: String) = index.resolve(credit).map { it.text to it.inLibrary }

    @Test
    fun collaborationsSplitIntoArtists() {
        assertEquals(listOf("Drake" to true, "Future" to false), parts("Drake & Future"))
        assertTrue(index.isCollaboration("Drake & Future"))
        // Case and spacing don't matter; the link opens the library's spelling.
        val p = index.resolve("drake ft. sza")
        assertEquals(listOf("drake", "sza"), p.map { it.text })
        assertEquals(listOf("Drake", "SZA"), p.map { it.name })
    }

    @Test
    fun namesWithSeparatorsStayTogetherWhenTheyAreArtists() {
        assertEquals(listOf("Tyler, The Creator" to true, "Kali Uchis" to false), parts("Tyler, The Creator & Kali Uchis"))
        assertEquals(listOf("Tyler, The Creator" to true), parts("Tyler, The Creator"))
        assertEquals(listOf("The Weeknd" to false, "Daft Punk" to true), parts("The Weeknd feat. Daft Punk"))
    }

    @Test
    fun bandsAreNotSplit() {
        for (band in listOf("Mumford & Sons", "Earth, Wind & Fire", "Simon & Garfunkel", "Florence + The Machine")) {
            assertEquals(listOf(band to true), parts(band), band)
            assertFalse(index.isCollaboration(band))
        }
    }

    @Test
    fun collaborationsAreNotArtistsThemselves() {
        assertEquals(null, index.libraryArtist("Drake & Future"))
        assertEquals("Drake", index.libraryArtist("DRAKE"))
        // A credit not in the library at all resolves on the fly.
        assertEquals(listOf(ArtistPart("SZA", "SZA", true), ArtistPart("Doja Cat", "Doja Cat", false)), index.resolve("SZA & Doja Cat"))
    }
}
