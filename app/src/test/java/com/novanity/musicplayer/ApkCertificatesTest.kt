package com.novanity.musicplayer

import com.novanity.musicplayer.platform.ApkCertificates
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Checks real APKs: `APK_FILES=a.apk:b.apk ./gradlew :app:testDebugUnitTest --tests '*ApkCertificates*'`.
 * All of them must report the same, non-empty signer (e.g. a CI release and a local release build).
 */
class ApkCertificatesTest {
    @Test
    fun releaseBuildsShareTheSigner() {
        val files = System.getenv("APK_FILES").orEmpty().split(File.pathSeparator).filter { it.isNotBlank() }.map(::File)
        assumeTrue("APK_FILES not set", files.isNotEmpty())
        val signers = files.map { ApkCertificates.of(it) }
        signers.forEachIndexed { i, s -> assertTrue("${files[i]} has no verified signer", s.isNotEmpty()) }
        signers.forEach { assertEquals(signers.first(), it) }
        println("Signer: ${signers.first()}")
    }

    @Test
    fun notAnApk() {
        val f = File.createTempFile("bogus", ".apk").apply { writeText("not a zip"); deleteOnExit() }
        assertTrue(runCatching { ApkCertificates.of(f) }.getOrDefault(emptySet()).isEmpty())
    }
}
