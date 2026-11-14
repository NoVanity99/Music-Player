package com.novanity.musicplayer.desktop

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.novanity.musicplayer.AppGraph
import com.novanity.musicplayer.data.AccountState
import com.novanity.musicplayer.data.CloudConfig
import com.novanity.musicplayer.data.CloudStatus
import com.novanity.musicplayer.data.HttpText
import com.novanity.musicplayer.data.db.AppDatabase
import com.novanity.musicplayer.data.db.AppDatabase.Companion.configure
import com.novanity.musicplayer.updatecore.UpdateCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Accounts against a running Worker with a database:
 * `CLOUD_TEST_URL=https://... CLOUD_TEST_KEY=... ./gradlew :desktopApp:test --tests '*AccountFlowTest*'`
 * Creates two throwaway accounts and deletes them again.
 */
class AccountFlowTest {
    private fun waitFor(what: String, timeoutMs: Long = 30_000, check: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (check()) return
            Thread.sleep(100)
        }
        error("Timed out waiting for: $what")
    }

    /** A fresh install: its own data folder, like another device (or this one after a reinstall). */
    private fun device(url: String, key: String): AppGraph {
        val dir = Files.createTempDirectory("account").toFile()
        return AppGraph(
            databaseFactory = {
                Room.databaseBuilder<AppDatabase>(File(dir, "music.db").absolutePath)
                    .setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).configure().build()
            },
            storeFactory = { name -> PropertiesStore(File(dir, "$name.properties")) },
            appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            httpGet = { u, h -> UpdateCore.getText(u, "VanityPlayer-test", 32_000_000, h).let { HttpText(it.first, it.second) } },
            httpSend = { m, u, h, b -> UpdateCore.sendJson(m, u, h, b, "VanityPlayer-test").let { HttpText(it.first, it.second) } },
            builtInCloud = CloudConfig(url, key),
        )
    }

    @Test
    fun eachAccountHasItsOwnLikesHistoryAndPlaylists() = runBlocking {
        val url = System.getenv("CLOUD_TEST_URL")
        assumeTrue("CLOUD_TEST_URL not set", !url.isNullOrBlank())
        val key = System.getenv("CLOUD_TEST_KEY") ?: "dev-key"
        val stamp = System.currentTimeMillis().toString().takeLast(9)
        val alice = "t_alice_$stamp"
        val bob = "t_bob_$stamp"
        val pass = "correct-horse-battery"

        val phone = device(url!!, key)
        val account = assertNotNull(phone.account)
        assertEquals(AccountState.SignedOut, account.state.value)
        waitFor("library online") { phone.cloud.status.value is CloudStatus.Online }
        phone.musicRepository.refresh()
        val songs = phone.musicRepository.songs.value!!
        assertTrue(songs.size >= 3, "need at least 3 songs in the cloud library")
        val (a, b, c) = songs
        suspend fun likes() = phone.favoritesRepository.songIds.first().toSet()
        suspend fun played() = phone.historyRepository.events.first().map { it.songId }.toSet()
        suspend fun playlists() = phone.playlistRepository.playlists.first().map { it.name to it.songCount }

        // Mistakes are explained.
        assertTrue("8 characters" in account.signUp(alice, "short")!!)
        assertTrue("Wrong username or password" in account.signIn(alice, pass)!!)

        // Alice signs up, likes two songs, plays one and makes a playlist.
        assertNull(account.signUp(alice, pass))
        assertIs<AccountState.SignedIn>(account.state.value)
        assertTrue("already taken" in device(url, key).account!!.signUp(alice, pass)!!)
        phone.favoritesRepository.setLiked(a.id, true)
        phone.favoritesRepository.setLiked(b.id, true)
        phone.historyRepository.record(a.id)
        val list = phone.playlistRepository.create("Alice's mix")
        phone.playlistRepository.addSongs(list, listOf(a.id, c.id))
        account.signOut()
        assertEquals(AccountState.SignedOut, account.state.value)
        assertTrue(likes().isEmpty() && played().isEmpty() && playlists().isEmpty(), "signing out clears the device")

        // Bob, on the same device, starts empty and gets his own.
        assertNull(account.signUp(bob, pass))
        account.sync()
        assertTrue(likes().isEmpty() && played().isEmpty() && playlists().isEmpty(), "a new account starts empty")
        phone.favoritesRepository.setLiked(c.id, true)
        phone.historyRepository.record(b.id)
        account.signOut()

        // Alice signs in on another device: everything of hers is there, nothing of Bob's.
        val tablet = device(url, key)
        waitFor("tablet online") { tablet.cloud.status.value is CloudStatus.Online }
        assertNull(tablet.account!!.signIn(alice.uppercase(), pass)) // usernames aren't case-sensitive
        tablet.account!!.sync()
        assertEquals(setOf(a.id, b.id), tablet.favoritesRepository.songIds.first().toSet())
        assertEquals(setOf(a.id), tablet.historyRepository.events.first().map { it.songId }.toSet())
        assertEquals(listOf("Alice's mix" to 2), tablet.playlistRepository.playlists.first().map { it.name to it.songCount })

        // She unlikes one there; back on the phone it's gone too, and Bob's data never shows.
        tablet.favoritesRepository.setLiked(b.id, false)
        tablet.account!!.signOut()
        assertNull(account.signIn(alice, pass))
        account.sync()
        assertEquals(setOf(a.id), likes())
        assertEquals(setOf(a.id), played())

        // Clean up: delete both accounts; a deleted account can't sign in.
        assertNull(account.deleteAccount())
        assertEquals(AccountState.SignedOut, account.state.value)
        assertTrue("Wrong username or password" in account.signIn(alice, pass)!!)
        assertNull(account.signIn(bob, pass))
        account.sync()
        assertEquals(setOf(c.id), likes())
        assertEquals(setOf(b.id), played())
        assertNull(account.deleteAccount())
    }
}
