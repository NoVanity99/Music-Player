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
import com.novanity.musicplayer.data.Song
import com.novanity.musicplayer.playback.PlayerController
import com.novanity.musicplayer.playback.PlayerUiState
import com.novanity.musicplayer.playback.QueueEntry
import com.novanity.musicplayer.playback.RepeatMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.delay

/**
 * Friends and listening together against a running Worker:
 * `CLOUD_TEST_URL=https://... CLOUD_TEST_KEY=... ./gradlew :desktopApp:test --tests '*SocialFlowTest*'`
 * Two throwaway accounts on two "devices", each with a pretend player; both are deleted again.
 */
class SocialFlowTest {
    /** Remembers what it was told to play, like a player without sound. */
    private class FakePlayer : PlayerController {
        val queue = ArrayList<Song>()
        var index = -1
        var position = 0L
        override val state = MutableStateFlow(PlayerUiState(connected = true))
        override val upNext = MutableStateFlow<List<QueueEntry>>(emptyList())
        private fun entry(i: Int) = queue[i].let { QueueEntry(i, "${it.id}/$i", it.id, it.title, it.artist) }
        private fun publish(playing: Boolean) {
            state.value = PlayerUiState(connected = true, current = if (index in queue.indices) entry(index) else null, isPlaying = playing && index in queue.indices)
            upNext.value = (index + 1 until queue.size).map(::entry)
        }
        val current: Song? get() = queue.getOrNull(index)
        override fun currentPositionMs() = position
        override fun setQueue(songs: List<Song>, startIndex: Int, shuffle: Boolean) { queue.clear(); queue += songs; index = startIndex; position = 0; publish(true) }
        override fun playNext(songs: List<Song>) { queue.addAll(index + 1, songs); publish(state.value.isPlaying) }
        override fun addToQueue(songs: List<Song>) { queue += songs; publish(state.value.isPlaying) }
        override fun togglePlayPause() = publish(!state.value.isPlaying)
        override fun pause() = publish(false)
        override fun previous() {}
        override fun next() { if (index + 1 < queue.size) { index++; position = 0; publish(true) } }
        override fun previousTrack() {}
        override fun nextTrack() = next()
        override fun seekTo(positionMs: Long) { position = positionMs }
        override fun setShuffle(enabled: Boolean) {}
        override fun setRepeatMode(mode: RepeatMode) {}
        override fun playQueueIndex(index: Int) {}
        override fun removeQueueIndex(index: Int) {}
        override fun moveQueueItem(from: Int, to: Int) {}
        override fun clearUpNext() { while (queue.size > index + 1) queue.removeAt(queue.size - 1); publish(state.value.isPlaying) }
        override fun release() {}
    }

    private fun waitFor(what: String, timeoutMs: Long = 30_000, check: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (check()) return
            Thread.sleep(100)
        }
        error("Timed out waiting for: $what")
    }

    private fun device(url: String, key: String): AppGraph {
        val dir = Files.createTempDirectory("social").toFile()
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
    fun aFriendListensAlongAndCanOnlyAddToTheQueue() = runBlocking {
        val url = System.getenv("CLOUD_TEST_URL")
        assumeTrue("CLOUD_TEST_URL not set", !url.isNullOrBlank())
        val key = System.getenv("CLOUD_TEST_KEY") ?: "dev-key"
        val stamp = System.currentTimeMillis().toString().takeLast(9)
        val hostName = "t_host_$stamp"
        val guestName = "t_guest_$stamp"
        val pass = "correct-horse-battery"

        val hostDevice = device(url!!, key)
        val guestDevice = device(url, key)
        try {
            waitFor("libraries online") { hostDevice.cloud.status.value is CloudStatus.Online && guestDevice.cloud.status.value is CloudStatus.Online }
            hostDevice.musicRepository.refresh(); guestDevice.musicRepository.refresh()
            val songs = hostDevice.musicRepository.songs.value!!
            assertTrue(songs.size >= 6)
            assertNull(hostDevice.account!!.signUp(hostName, pass))
            assertNull(guestDevice.account!!.signUp(guestName, pass))
            val host = hostDevice.social!!
            val guest = guestDevice.social!!
            val hostPlayer = FakePlayer()
            val guestPlayer = FakePlayer()
            host.wrap(hostPlayer)
            val guestUi = guest.wrap(guestPlayer) // what the guest's buttons talk to

            // The guest was in the middle of their own music.
            guestUi.setQueue(listOf(songs[4], songs[5]), 0, false)
            guestPlayer.position = 61_000

            // A profile picture: uploaded, then served back at the address the app builds.
            val picture = java.io.ByteArrayOutputStream().also {
                javax.imageio.ImageIO.write(java.awt.image.BufferedImage(320, 320, java.awt.image.BufferedImage.TYPE_INT_RGB), "jpg", it)
            }.toByteArray()
            assertNull(hostDevice.account!!.setAvatar(picture))
            val pictureUrl = assertNotNull(hostDevice.account!!.avatarUrl(hostDevice.account!!.profile.value.avatar))
            assertTrue(UpdateCore.fetch(pictureUrl, 2_000_000).contentEquals(picture))
            assertTrue("JPEG or PNG" in hostDevice.account!!.setAvatar("not a picture at all, just some text that is long enough to pass the size check ".repeat(3).toByteArray())!!)

            // Friends: a request, then an accept.
            guest.addFriend(hostName)
            waitFor("the request arrives") { runBlocking { host.pulse() }; guestName in host.state.value.requests }
            host.answerFriend(guestName, true)
            waitFor("they are friends") { runBlocking { guest.pulse() }; guest.state.value.friends.any { it.username == hostName } }

            // The host plays something; the guest sees it under their name.
            hostPlayer.setQueue(listOf(songs[0], songs[1]), 0, false)
            hostPlayer.position = 30_000
            host.pulse()
            waitFor("the guest sees what the host plays") {
                runBlocking { guest.pulse() }
                guest.state.value.friends.first { it.username == hostName }.let { it.avatar != null && it.playing && guestDevice.musicRepository.songsById[com.novanity.musicplayer.data.CloudRepository.songIdFor(it.songId!!)]?.id == songs[0].id }
            }

            // The guest asks to listen along; the host gets the request and accepts.
            guest.askToListen(hostName)
            waitFor("the listen request arrives") { runBlocking { host.pulse() }; host.state.value.listenRequests.any { it.from == guestName } }
            host.answerListen(host.state.value.listenRequests.first(), true)
            waitFor("the guest follows the host") {
                runBlocking { host.pulse(); guest.pulse() }
                guest.isGuest && guestPlayer.current?.id == songs[0].id && guestPlayer.position >= 30_000
            }
            assertTrue(guestPlayer.position < 60_000, "at the host's place in the song, not the guest's old one")
            assertEquals(songs[1].id, guestPlayer.queue.getOrNull(1)?.id, "the host's next song is lined up")
            waitFor("the host sees the guest") { runBlocking { host.pulse() }; host.state.value.session?.members == listOf(guestName) }

            // The guest can't steer: skipping and pausing do nothing.
            guestUi.next(); guestUi.pause(); guestUi.seekTo(5)
            assertEquals(songs[0].id, guestPlayer.current?.id)
            assertTrue(guestPlayer.state.value.isPlaying && guestPlayer.position >= 30_000)

            // ...but can add to the queue, which lands in the host's queue.
            guestUi.addToQueue(listOf(songs[3]))
            waitFor("the host gets the added song") { runBlocking { host.pulse() }; hostPlayer.queue.any { it.id == songs[3].id } }
            assertTrue(guestPlayer.queue.none { it.id == songs[3].id } || guestPlayer.queue.size <= 2)

            // The host moves on and pauses; the guest follows both.
            hostPlayer.next(); hostPlayer.position = 4_000
            waitFor("the guest follows to the next song") { runBlocking { host.pulse(); guest.pulse() }; guestPlayer.current?.id == songs[1].id }
            hostPlayer.pause()
            waitFor("the guest pauses with the host") { runBlocking { host.pulse(); guest.pulse() }; !guestPlayer.state.value.isPlaying }

            // Leaving puts the guest back where they were before, paused.
            guest.leave()
            waitFor("the guest is back on their own music") { guestPlayer.current?.id == songs[4].id && guestPlayer.position == 61_000L && !guestPlayer.state.value.isPlaying }
            assertEquals(listOf(songs[4].id, songs[5].id), guestPlayer.queue.map { it.id })
            waitFor("the session is over for both") { runBlocking { host.pulse(); guest.pulse() }; host.state.value.session == null && !guest.isGuest }
            guestUi.next()
            assertEquals(songs[5].id, guestPlayer.current?.id, "their own controls work again")
        } finally {
            hostDevice.account?.deleteAccount()
            guestDevice.account?.deleteAccount()
        }
    }
}
