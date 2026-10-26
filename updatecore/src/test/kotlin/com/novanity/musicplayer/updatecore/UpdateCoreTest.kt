package com.novanity.musicplayer.updatecore

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateCoreTest {
    private fun res(name: String) = File(javaClass.classLoader.getResource(name)!!.toURI())

    @Test
    fun verifiesOpenSslSignatureAndRejectsTampering() {
        val pub = res("public.b64").readText().trim()
        val data = res("manifest.json").readBytes()
        val sig = res("manifest.json.sig").readText()
        assertTrue(UpdateCore.verifyEd25519(pub, data, sig), "signature made by `openssl pkeyutl -sign` verifies")
        val tampered = data.copyOf().also { it[it.size - 3] = '8'.code.toByte() }
        assertFalse(UpdateCore.verifyEd25519(pub, tampered, sig), "edited manifest is rejected")
        assertFalse(UpdateCore.verifyEd25519(pub, data, "AAAA"), "garbage signature is rejected")
    }

    @Test
    fun sha256MatchesKnownValue() {
        val f = File.createTempFile("sha", ".bin").apply { writeText("abc"); deleteOnExit() }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", UpdateCore.sha256(f))
    }
}
