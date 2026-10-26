package com.novanity.musicplayer.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CoverArtLookupTest {
    private val itunes = """
        {"resultCount":3,"results":[
          {"artistName":"Daft Punk Tribute Band","collectionName":"Discovery Live","artworkUrl100":"https://is1.example/wrong/100x100bb.jpg"},
          {"artistName":"Daft Punk","collectionName":"Discovery","artworkUrl100":"https://is1-ssl.mzstatic.com/image/thumb/Music/ab/cd/source/100x100bb.jpg"},
          {"artistName":"Daft Punk","collectionName":"Homework","artworkUrl100":"https://is1.example/homework/100x100bb.jpg"}
        ]}
    """.trimIndent()

    private val deezer = """
        {"data":[{"title":"Abbey Road (Remastered)","artist":{"name":"The Beatles"},"cover_xl":"https://cdn-images.dzcdn.net/images/cover/xyz/1000x1000-000000-80-0-0.jpg"}],"total":1}
    """.trimIndent()

    @Test
    fun picksTheMatchingItunesAlbumAtFullSize() {
        assertEquals(
            "https://is1-ssl.mzstatic.com/image/thumb/Music/ab/cd/source/512x512bb.jpg",
            CoverArtLookup.pickItunes(itunes, "Daft Punk", "Discovery"),
        )
    }

    @Test
    fun ignoresEditionTagsAndPunctuation() {
        assertEquals(
            "https://cdn-images.dzcdn.net/images/cover/xyz/1000x1000-000000-80-0-0.jpg",
            CoverArtLookup.pickDeezer(deezer, "the beatles", "Abbey Road"),
        )
        assertEquals("abbey road", CoverArtLookup.normalize("Abbey Road (2019 Mix) - Single"))
    }

    @Test
    fun noConfidentMatchMeansNoCover() {
        assertNull(CoverArtLookup.pickItunes(itunes, "Justice", "Cross"))
        assertNull(CoverArtLookup.pickItunes("not json", "Daft Punk", "Discovery"))
    }

    @Test
    fun fallsBackToDeezerAndCachesResults() {
        var calls = 0
        val lookup = CoverArtLookup { url ->
            calls++
            when {
                "itunes" in url -> """{"results":[]}"""
                "deezer" in url -> deezer
                else -> null
            }
        }
        val url = lookup.find("The Beatles", "Abbey Road")
        assertEquals("https://cdn-images.dzcdn.net/images/cover/xyz/1000x1000-000000-80-0-0.jpg", url)
        assertEquals(url, lookup.find("The Beatles", "Abbey Road"))
        assertEquals(2, calls) // second call came from the cache
        assertNull(lookup.find("Unknown artist", "Abbey Road")) // no lookups for placeholder tags
        assertEquals(2, calls)
    }
}
