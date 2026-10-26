package com.novanity.musicplayer.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Talks to a fake Discord client over a Unix socket, speaking the real IPC framing. */
class DiscordPresenceTest {

    private class FakeDiscord(val channel: SocketChannel) {
        fun read(): Pair<Int, JsonObject> {
            val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            while (header.hasRemaining()) check(channel.read(header) >= 0)
            header.flip()
            val op = header.int
            val body = ByteBuffer.allocate(header.int)
            while (body.hasRemaining()) check(channel.read(body) >= 0)
            return op to Json.parseToJsonElement(String(body.array())).jsonObject
        }

        fun write(op: Int, json: JsonObject) {
            val bytes = json.toString().toByteArray()
            val b = ByteBuffer.allocate(8 + bytes.size).order(ByteOrder.LITTLE_ENDIAN).putInt(op).putInt(bytes.size).put(bytes)
            b.flip()
            while (b.hasRemaining()) channel.write(b)
        }

        /** Reads one SET_ACTIVITY and acknowledges it (optionally as an error). */
        fun setActivity(error: Boolean = false): JsonObject {
            val (op, msg) = read()
            assertEquals(1, op)
            assertEquals("SET_ACTIVITY", msg["cmd"]!!.jsonPrimitive.content)
            write(1, buildJsonObject {
                put("cmd", "SET_ACTIVITY")
                put("nonce", msg["nonce"]!!.jsonPrimitive.content)
                if (error) put("evt", "ERROR")
            })
            return msg["args"]!!.jsonObject
        }
    }

    @Test
    fun handshakeSetAndClear() {
        val dir = Files.createTempDirectory("ipc")
        val path = dir.resolve("discord-ipc-0")
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply { bind(UnixDomainSocketAddress.of(path)) }
        val presence = DiscordPresence("1234", socketPaths = { listOf(dir.resolve("missing").toString(), path.toString()) })
        try {
            presence.update(PresenceActivity("A", "Some Artist", "Album", startMs = 1_000, endMs = 181_000))
            val discord = FakeDiscord(server.accept())

            val (op, hello) = discord.read()
            assertEquals(0, op)
            assertEquals("1234", hello["client_id"]!!.jsonPrimitive.content)
            discord.write(1, buildJsonObject { put("cmd", "DISPATCH"); put("evt", "READY") })

            // First try includes status_display_type; pretend this Discord is too old for it.
            val first = discord.setActivity(error = true)["activity"]!!.jsonObject
            assertEquals(2, first["status_display_type"]!!.jsonPrimitive.int)
            val activity = discord.setActivity()["activity"]!!.jsonObject
            assertNull(activity["status_display_type"])
            assertEquals(2, activity["type"]!!.jsonPrimitive.int)
            assertEquals("A​", activity["details"]!!.jsonPrimitive.content) // padded to Discord's 2-char minimum
            assertEquals("Some Artist", activity["state"]!!.jsonPrimitive.content)
            assertEquals(181_000, activity["timestamps"]!!.jsonObject["end"]!!.jsonPrimitive.long)
            assertEquals("logo", activity["assets"]!!.jsonObject["large_image"]!!.jsonPrimitive.content)

            // Pausing clears it (after the rate-limit gap).
            presence.update(null)
            val cleared = discord.setActivity()
            assertFalse("activity" in cleared)
            assertTrue("pid" in cleared)
        } finally {
            presence.close()
            server.close()
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun noDiscordIsSilent() {
        val presence = DiscordPresence("1234", socketPaths = { emptyList() })
        presence.update(PresenceActivity("Song", "Artist", "", 0, null))
        Thread.sleep(200)
        presence.close() // no exceptions, returns promptly
    }

    @Test
    fun fieldLimits() {
        assertEquals("Unknown", DiscordPresence.field("  "))
        assertEquals(128, DiscordPresence.field("x".repeat(300)).length)
    }
}
