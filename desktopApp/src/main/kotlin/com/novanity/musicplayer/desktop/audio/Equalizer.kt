package com.novanity.musicplayer.desktop.audio

import com.novanity.musicplayer.platform.EqBand
import com.novanity.musicplayer.platform.EqualizerInfo
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The desktop equalizer: five bands (a low shelf, three peaks, a high shelf) applied to the
 * decoded audio. Levels are in millibels, like Android's, so both apps share the same settings.
 */
object DesktopEqualizer {
    private const val RANGE = 1500 // +/- 15 dB
    private val CENTERS = listOf(60, 230, 910, 3600, 14000)

    private val PRESETS: List<Pair<String, List<Int>>> = listOf(
        "Flat" to listOf(0, 0, 0, 0, 0),
        "Bass boost" to listOf(600, 400, 0, 0, 0),
        "Treble boost" to listOf(0, 0, 0, 300, 600),
        "Vocal" to listOf(-200, 0, 400, 300, 0),
        "Rock" to listOf(500, 300, -100, 300, 500),
        "Pop" to listOf(-100, 200, 500, 200, -100),
        "Hip-hop" to listOf(500, 300, 0, 100, 300),
        "Electronic" to listOf(400, 100, 0, 200, 500),
        "Classical" to listOf(400, 200, -200, 300, 400),
        "Loudness" to listOf(600, 200, 0, 100, 400),
    )

    val info = EqualizerInfo(CENTERS.map { EqBand(it, -RANGE, RANGE) }, PRESETS.map { it.first })

    /** The band levels to use for these settings, or null when the equalizer is off or flat. */
    fun levels(enabled: Boolean, preset: Int, bands: List<Int>): List<Int>? {
        if (!enabled) return null
        val levels = PRESETS.getOrNull(preset)?.second ?: CENTERS.indices.map { (bands.getOrNull(it) ?: 0).coerceIn(-RANGE, RANGE) }
        return levels.takeIf { l -> l.any { it != 0 } }
    }

    /** One stream's filters. Not thread-safe: each is used by the engine thread only. */
    class Chain {
        private var levels: List<Int>? = null
        private var coeffs = DoubleArray(0) // b0 b1 b2 a1 a2 per band (normalised)
        private var state = DoubleArray(0) // x1 x2 y1 y2 per band per channel
        private var preamp = 1.0

        private fun configure(l: List<Int>?) {
            levels = l
            if (l == null) return
            coeffs = DoubleArray(l.size * 5)
            state = DoubleArray(l.size * 4 * OUT_CHANNELS)
            l.forEachIndexed { i, mb -> design(i, l.size, CENTERS[i].toDouble(), mb / 100.0).copyInto(coeffs, i * 5) }
            // Leave some headroom for boosts so loud music doesn't clip.
            preamp = 10.0.pow(-(l.max().coerceAtLeast(0) / 100.0) * 0.5 / 20.0)
        }

        private fun design(i: Int, count: Int, hz: Double, db: Double): DoubleArray {
            val a = 10.0.pow(db / 40.0)
            val f = when (i) { 0 -> 95.0; count - 1 -> 9000.0; else -> hz }
            val w = 2 * PI * f / OUT_RATE
            val c = cos(w)
            val s = sin(w)
            val b0: Double; val b1: Double; val b2: Double; val a0: Double; val a1: Double; val a2: Double
            when (i) {
                0 -> { // low shelf
                    val al = s / 2 * sqrt(2.0); val r = 2 * sqrt(a) * al
                    b0 = a * ((a + 1) - (a - 1) * c + r); b1 = 2 * a * ((a - 1) - (a + 1) * c); b2 = a * ((a + 1) - (a - 1) * c - r)
                    a0 = (a + 1) + (a - 1) * c + r; a1 = -2 * ((a - 1) + (a + 1) * c); a2 = (a + 1) + (a - 1) * c - r
                }
                count - 1 -> { // high shelf
                    val al = s / 2 * sqrt(2.0); val r = 2 * sqrt(a) * al
                    b0 = a * ((a + 1) + (a - 1) * c + r); b1 = -2 * a * ((a - 1) + (a + 1) * c); b2 = a * ((a + 1) + (a - 1) * c - r)
                    a0 = (a + 1) - (a - 1) * c + r; a1 = 2 * ((a - 1) - (a + 1) * c); a2 = (a + 1) - (a - 1) * c - r
                }
                else -> { // peak, about two octaves wide
                    val al = s / (2 * 0.7)
                    b0 = 1 + al * a; b1 = -2 * c; b2 = 1 - al * a
                    a0 = 1 + al / a; a1 = -2 * c; a2 = 1 - al / a
                }
            }
            return doubleArrayOf(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
        }

        /** Filters [n] interleaved samples of [buf] in place for [target] (null = off). */
        fun process(buf: ShortArray, n: Int, target: List<Int>?) {
            if (target != levels) configure(target)
            val l = levels ?: return
            val bands = l.size
            for (i in 0 until n) {
                val ch = i % OUT_CHANNELS
                var x = buf[i] * preamp
                for (b in 0 until bands) {
                    val k = b * 5
                    val st = (b * OUT_CHANNELS + ch) * 4
                    val y = coeffs[k] * x + coeffs[k + 1] * state[st] + coeffs[k + 2] * state[st + 1] - coeffs[k + 3] * state[st + 2] - coeffs[k + 4] * state[st + 3]
                    state[st + 1] = state[st]; state[st] = x
                    state[st + 3] = state[st + 2]; state[st + 2] = y
                    x = y
                }
                buf[i] = x.coerceIn(-32768.0, 32767.0).toInt().toShort()
            }
        }
    }
}
