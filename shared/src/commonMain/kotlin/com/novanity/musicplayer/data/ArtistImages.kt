package com.novanity.musicplayer.data

import com.novanity.musicplayer.platform.KeyValueStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One picture found for an artist: the address of a square image and who it shows. */
data class ArtistImageOption(val url: String, val label: String)

@Serializable
private data class DeezerArtist(val name: String = "", val picture_xl: String = "", val nb_fan: Int = 0)

@Serializable
private data class DeezerSearch(val data: List<DeezerArtist> = emptyList())

/**
 * Artist pictures. For each artist the player looks them up once on Deezer (a public music
 * catalogue; only the artist's name is sent) and remembers the answer, including "none found",
 * in which case the caller falls back to an album cover. The user can also choose a picture for
 * an artist's profile picture or banner; those choices are kept here too.
 *
 * A picture reference is either a web address, or "album:<album id>" for a cover from the library.
 */
class ArtistImages(
    private val store: KeyValueStore,
    private val httpGet: suspend (url: String) -> HttpText,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lookups = Semaphore(3)
    private val lock = Mutex()
    private val memory = HashMap<String, String>() // key -> address, "" for none

    private val _version = MutableStateFlow(0)

    /** Changes whenever a choice or a looked-up picture changes, so pictures on screen refresh. */
    val version: StateFlow<Int> = _version.asStateFlow()

    private fun key(name: String) = name.trim().lowercase()

    /** What the user chose for [slot] ("avatar" or "banner"), or null when it's automatic. */
    fun choice(name: String, slot: String): String? = store.getString("$slot:${key(name)}")?.takeIf { it.isNotBlank() }

    fun setChoice(name: String, slot: String, reference: String?) {
        store.putString("$slot:${key(name)}", reference)
        _version.value++
    }

    /** The automatic picture if it's already known (no waiting); null when none or not looked up yet. */
    fun knownAuto(name: String): String? = (memory[key(name)] ?: store.getString("auto:${key(name)}"))?.takeIf { it.isNotBlank() }

    /** The automatic picture for an artist: the best exact-name match, or null when there is none. */
    suspend fun auto(name: String): String? {
        val k = key(name)
        if (k.isEmpty() || k == "unknown artist" || k == "various artists") return null
        lock.withLock { memory[k] }?.let { return it.takeIf(String::isNotBlank) }
        store.getString("auto:$k")?.let { saved ->
            lock.withLock { memory[k] = saved }
            return saved.takeIf(String::isNotBlank)
        }
        val found = try {
            lookups.withPermit { fetch(name) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null // offline or busy: try again next time
        } ?: return null
        // Only someone with exactly this name, and the best-known one when several share it.
        val best = found.filter { sameName(it.name, name) && hasPicture(it) }.maxByOrNull { it.nb_fan }?.picture_xl.orEmpty()
        lock.withLock { memory[k] = best }
        store.putString("auto:$k", best)
        if (best.isNotEmpty()) _version.value++
        return best.takeIf(String::isNotBlank)
    }

    /** Pictures to choose from for a search (usually the artist's name), best-known first. */
    suspend fun search(query: String): List<ArtistImageOption> {
        if (query.isBlank()) return emptyList()
        val found = try {
            lookups.withPermit { fetch(query) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return emptyList()
        return found.filter(::hasPicture).sortedByDescending { it.nb_fan }.distinctBy { it.picture_xl }
            .map { ArtistImageOption(it.picture_xl, it.name) }
    }

    private suspend fun fetch(query: String): List<DeezerArtist>? {
        val response = httpGet("https://api.deezer.com/search/artist?limit=12&q=" + ArtistBios.encode(query.trim()))
        if (response.status != 200) return null
        return runCatching { json.decodeFromString(DeezerSearch.serializer(), response.body).data }.getOrNull()
    }

    companion object {
        /** Deezer gives every artist an address; ones without a picture have an empty picture id in it. */
        private fun hasPicture(a: DeezerArtist) = a.picture_xl.startsWith("https://") && "/images/artist//" !in a.picture_xl

        internal fun sameName(a: String, b: String): Boolean {
            fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
            return norm(a).isNotEmpty() && norm(a) == norm(b)
        }

        /** The same picture at a smaller size, for small round pictures in lists. */
        fun small(url: String): String = url.replace("/1000x1000-", "/250x250-")
    }
}
