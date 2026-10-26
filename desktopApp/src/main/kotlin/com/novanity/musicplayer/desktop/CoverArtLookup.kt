package com.novanity.musicplayer.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Finds a public image URL for an album cover, for Discord (which can only show images from the
 * web, not local files). Searches Apple's iTunes catalogue, then Deezer, by artist and album name.
 * Only a confident match is used; otherwise the presence keeps the app logo.
 */
class CoverArtLookup(private val get: (String) -> String? = ::httpGet) {
    private val cache = ConcurrentHashMap<String, String>()
    private val misses = ConcurrentHashMap.newKeySet<String>()

    /** Blocking; call off the main thread. Null when there's no good match or no connection. */
    fun find(artist: String, album: String): String? {
        if (artist.isBlank() || album.isBlank() || isUnknown(artist) || isUnknown(album)) return null
        val key = "${normalize(artist)}|${normalize(album)}"
        cache[key]?.let { return it }
        if (key in misses) return null
        val url = runCatching { fromItunes(artist, album) }.getOrNull() ?: runCatching { fromDeezer(artist, album) }.getOrNull()
        if (url != null) cache[key] = url else misses += key
        return url
    }

    private fun fromItunes(artist: String, album: String): String? {
        val body = get("https://itunes.apple.com/search?entity=album&limit=10&term=" + enc("$artist $album")) ?: return null
        return pickItunes(body, artist, album)
    }

    private fun fromDeezer(artist: String, album: String): String? {
        val body = get("https://api.deezer.com/search/album?limit=10&q=" + enc("artist:\"$artist\" album:\"$album\"")) ?: return null
        return pickDeezer(body, artist, album)
    }

    companion object {
        internal fun pickItunes(json: String, artist: String, album: String): String? =
            results(json, "results").best { score(it.str("artistName"), it.str("collectionName"), artist, album) }
                ?.str("artworkUrl100")
                // Ask Apple's image server for a bigger rendition of the same artwork.
                ?.replace("100x100bb", "512x512bb")

        internal fun pickDeezer(json: String, artist: String, album: String): String? =
            results(json, "data").best {
                score(it["artist"]?.jsonObject?.str("name"), it.str("title"), artist, album)
            }?.str("cover_xl")

        private fun results(json: String, field: String): List<JsonObject> =
            runCatching { Json.parseToJsonElement(json).jsonObject[field]!!.jsonArray.map { it.jsonObject } }.getOrDefault(emptyList())

        private fun JsonObject.str(name: String): String? = this[name]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() && it != "null" }

        /** Highest-scoring result, ignoring anything that isn't a match at all. */
        private fun List<JsonObject>.best(score: (JsonObject) -> Int): JsonObject? =
            map { it to score(it) }.filter { it.second > 0 }.maxByOrNull { it.second }?.first

        /**
         * 3: same artist and album. 2: same artist, album differs only by extra words ("Deluxe").
         * 1: same album, artist differs only by extra words ("feat. …"). 0: not this album.
         */
        private fun score(foundArtist: String?, foundAlbum: String?, artist: String, album: String): Int {
            if (foundArtist == null || foundAlbum == null) return 0
            val fa = normalize(foundArtist)
            val fb = normalize(foundAlbum)
            val a = normalize(artist)
            val b = normalize(album)
            return when {
                fa == a && fb == b -> 3
                fa == a && close(fb, b) -> 2
                fb == b && close(fa, a) -> 1
                else -> 0
            }
        }

        /** One contains the other ("Abbey Road" vs "Abbey Road Super Deluxe"). */
        private fun close(a: String, b: String) = a.isNotEmpty() && b.isNotEmpty() && (a.contains(b) || b.contains(a))

        /** Lower case, no edition tags in brackets, no " - Single"/" - EP", letters and digits only. */
        internal fun normalize(s: String): String = s.lowercase()
            .replace(Regex("""[(\[][^)\]]*[)\]]"""), " ")
            .replace(Regex("""\s+-\s+(single|ep)$"""), "")
            .replace("&", " and ")
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
            .trim()

        private fun isUnknown(s: String) = normalize(s) in setOf("unknown", "unknown artist", "unknown album", "")

        private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

        private val client: HttpClient by lazy {
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NORMAL).build()
        }

        private fun httpGet(url: String): String? {
            val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(8)).header("User-Agent", "VanityPlayer").GET().build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            return response.body().takeIf { response.statusCode() == 200 }
        }
    }
}
