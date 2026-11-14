package com.novanity.musicplayer.desktop

import com.novanity.musicplayer.data.CloudConfig
import com.novanity.musicplayer.data.CloudFiles
import com.novanity.musicplayer.data.CloudRepository
import com.novanity.musicplayer.data.CloudStatus
import com.novanity.musicplayer.data.HttpText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Songs added to the server show up by themselves, and the full list is only fetched when it changed. */
class CloudPollTest {
    @Test
    fun newSongsAppearWithoutRestarting() = runBlocking {
        var songs = 2
        var libraryFetches = 0
        var down = false
        val http: suspend (String, Map<String, String>) -> HttpText = { url, _ ->
            if (down) throw java.io.IOException("offline")
            if (url.endsWith("/library/version")) HttpText(200, """{"version":"v$songs"}""")
            else {
                libraryFetches++
                HttpText(200, """{"version":1,"songs":[${(1..songs).joinToString(",") { """{"id":"s$it","title":"Song $it","artist":"slayr","album":"Gaia"}""" }}]}""")
            }
        }
        val dir = Files.createTempDirectory("poll").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repo = CloudRepository(
            PropertiesStore(File(dir, "secure.properties")), PropertiesStore(File(dir, "cache.properties")), http,
            CloudFiles.None, scope, flowOf(emptyList()), CloudConfig("https://example.test", "key"), pollMs = 50,
        )
        suspend fun waitFor(count: Int) = withTimeout(10_000) { while (repo.songs.value.size != count) delay(20) }
        try {
            waitFor(2)
            delay(400) // several polls with nothing new: the first one re-reads the list, the rest don't
            assertTrue(libraryFetches <= 2, "fetched the whole list $libraryFetches times")
            songs = 5
            waitFor(5)
            // A failed check doesn't hide the library.
            down = true
            delay(300)
            assertTrue(repo.status.value is CloudStatus.Online)
            assertEquals(5, repo.songs.value.size)
        } finally {
            scope.cancel()
        }
    }
}
