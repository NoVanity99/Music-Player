package com.novanity.musicplayer.data

import androidx.compose.runtime.Immutable
import com.novanity.musicplayer.platform.KeyValueStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** A short artist bio and the page it came from. */
@Immutable
@Serializable
data class ArtistBio(
    val text: String,
    val sourceUrl: String,
)

/** Result of an HTTP GET: the status code and the response body. */
class HttpText(val status: Int, val body: String)

/**
 * Artist bios from Wikipedia: the summary of the artist's English Wikipedia article. Only a page
 * whose title is the artist's name (allowing a "(rapper)"-style suffix) and whose description says
 * it's a musician or band is used, so a namesake never shows up. Results, including "no article",
 * are cached; network errors and rate limits aren't, so the next visit tries again.
 *
 * [get] does the request (blocking is fine: it's called on [ioContext]); it throws on network errors.
 */
class ArtistBios(
    private val store: KeyValueStore,
    private val get: suspend (url: String) -> HttpText,
    private val now: () -> Long = ::nowMillis,
) {
    private val memory = HashMap<String, ArtistBio?>()
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    /** The bio for [artist], or null when there isn't one (or it can't be loaded right now). */
    suspend fun bio(artist: String): ArtistBio? {
        val key = artist.trim().lowercase()
        if (key.isEmpty() || key in UNKNOWN) return null
        lock.withLock { if (key in memory) return memory[key] }
        store.getString(STORE_PREFIX + key)?.let { saved ->
            val cached = decode(saved)
            if (cached != null) {
                remember(key, cached.getOrNull())
                return cached.getOrNull()
            }
        }
        val found = try {
            lookUp(artist.trim())
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return null // offline or rate-limited: try again next time
        }
        remember(key, found.bio)
        store.putString(STORE_PREFIX + key, encode(found))
        return found.bio
    }

    /** A lookup's answer; a missing bio is checked again after [retryAfterMs]. */
    private class Found(val bio: ArtistBio?, val retryAfterMs: Long = RETRY_NONE_MS)

    private suspend fun lookUp(name: String): Found {
        // 1. The article with exactly this name, then the usual music disambiguations:
        //    "Drake" → "Drake (musician)", "Future" → "Future (rapper)". Page summaries are
        //    cached by Wikipedia and rarely rate-limited, unlike its search APIs.
        for (title in listOf(name) + QUALIFIERS.map { "$name ($it)" }) {
            summary(title)?.let { return Found(it) }
        }
        // 2. Last resort: search for a title like "Name (American band)".
        val search = try {
            get("$API/w/rest.php/v1/search/title?limit=10&q=" + encode(name))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        }
        if (search == null || search.status != 200) return Found(null, RETRY_UNSURE_MS) // search unavailable: ask again tomorrow
        val pages = runCatching { json.parseToJsonElement(search.body).jsonObject["pages"]!!.jsonArray.map { it.jsonObject } }
            .getOrDefault(emptyList())
        val match = pages.firstOrNull { page ->
            val title = page.str("title") ?: return@firstOrNull false
            sameName(title, name) && isMusical(page.str("description"))
        }
        return Found(match?.str("key")?.let { summary(it) })
    }

    /** The page summary for [title], if it exists, isn't a disambiguation page and is about music. */
    private suspend fun summary(title: String): ArtistBio? {
        val response = get("$API/api/rest_v1/page/summary/" + encode(title.replace(' ', '_')) + "?redirect=true")
        // Rate limited: give up for now (nothing is cached). Any other error: no such page.
        if (response.status == 429) throw IllegalStateException("Rate limited by Wikipedia")
        if (response.status != 200) return null
        val page = runCatching { json.parseToJsonElement(response.body).jsonObject }.getOrNull() ?: return null
        if (page.str("type") != "standard") return null
        val extract = page.str("extract")?.trim().orEmpty()
        if (extract.isEmpty()) return null
        if (!sameName(page.str("title").orEmpty(), title)) return null // redirected somewhere else
        if (!isMusical(page.str("description")) && !isMusical(extract.take(200))) return null
        val url = page["content_urls"]?.jsonObject?.get("desktop")?.jsonObject?.str("page")
            ?: "https://en.wikipedia.org/wiki/" + encode(title.replace(' ', '_'))
        return ArtistBio(extract, url)
    }

    private suspend fun remember(key: String, bio: ArtistBio?) = lock.withLock { memory[key] = bio }

    /** Stored as JSON, or "none|<time to look again>" when there's no article. */
    private fun encode(found: Found) =
        found.bio?.let { json.encodeToString(ArtistBio.serializer(), it) } ?: "none|${now() + found.retryAfterMs}"

    private fun decode(saved: String): Result<ArtistBio?>? {
        if (saved.startsWith("none|")) {
            val retryAt = saved.substringAfter('|').toLongOrNull() ?: return null
            return if (now() < retryAt) Result.success(null) else null
        }
        return runCatching { json.decodeFromString(ArtistBio.serializer(), saved) }.getOrNull()?.let { Result.success(it) }
    }

    companion object {
        private const val API = "https://en.wikipedia.org"
        private const val STORE_PREFIX = "bio2:"
        private const val RETRY_NONE_MS = 30L * 24 * 60 * 60 * 1000
        private const val RETRY_UNSURE_MS = 24L * 60 * 60 * 1000

        /** How Wikipedia titles musicians who share a name with something else. */
        private val QUALIFIERS = listOf("musician", "rapper", "singer", "band", "group", "DJ", "record producer", "singer-songwriter", "duo")
        private val UNKNOWN = setOf("unknown", "unknown artist", "<unknown>", "various artists")

        /** A kind of performer: just mentioning "music" isn't enough ("music streaming service"). */
        private val musicWords = Regex(
            """\b(singer|rapper|band|musician|songwriter|record producer|producer|dj|disc jockey|composer|duo|trio|""" +
                """group|vocalist|guitarist|pianist|drummer|bassist|orchestra|ensemble|choir|recording artist|""" +
                """music artist|mc|beatmaker|turntablist)\b""",
            RegexOption.IGNORE_CASE,
        )
        private val notAnArtist = Regex(
            """\b(album|song|single|ep|discography|videography|tour|film|episode|label|festival|service|company|""" +
                """streaming|website|genre|magazine|software|video game|television|radio|station|venue|app)\b""",
            RegexOption.IGNORE_CASE,
        )

        /** True for "American rapper (born 1983)", false for "2020 studio album by Dua Lipa". */
        fun isMusical(description: String?): Boolean =
            description != null && musicWords.containsMatchIn(description) && !notAnArtist.containsMatchIn(description)

        /** "Future (rapper)" matches "Future"; "Sol Gabetta" doesn't match "Sol Vega". */
        fun sameName(title: String, name: String): Boolean {
            fun norm(s: String) = s.replace('_', ' ').replace(Regex("""\s*\([^)]*\)\s*$"""), "")
                .lowercase().replace("&", "and").replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()
            return norm(title) == norm(name)
        }

        /** Percent-encodes a URL path segment or query value (UTF-8). */
        internal fun encode(s: String): String = buildString {
            for (b in s.encodeToByteArray()) {
                val c = b.toInt() and 0xFF
                if (c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code || c.toChar() in "-._~") {
                    append(c.toChar())
                } else {
                    append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
                }
            }
        }

        private fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
    }
}
