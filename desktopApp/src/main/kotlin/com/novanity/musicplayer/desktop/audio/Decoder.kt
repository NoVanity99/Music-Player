package com.novanity.musicplayer.desktop.audio

import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameFilter
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Frame
import java.nio.ShortBuffer

const val OUT_RATE = 48_000
const val OUT_CHANNELS = 2

/**
 * Decodes one file to 48 kHz interleaved stereo 16-bit PCM (FFmpeg resamples/downmixes), with an
 * optional atempo filter for playback speed. Not thread-safe: used only by the engine thread.
 */
class Decoder private constructor(private val grabber: FFmpegFrameGrabber, speed: Float) : AutoCloseable {
    /** Track length from the container (0 if unknown). */
    val durationMs: Long = (grabber.lengthInTime / 1000).coerceAtLeast(0)

    /** Source-time position of the end of what has been decoded so far. */
    private var decodedMs: Long = 0

    /** Source-time position of the next sample [fill] will return. */
    val positionMs: Long
        get() {
            val waiting = leftover?.let { it.size - leftoverPos } ?: 0
            return (decodedMs - (waiting / OUT_CHANNELS * 1000L / OUT_RATE * speed).toLong()).coerceAtLeast(0)
        }

    var speed: Float = speed
        private set

    private var filter: FFmpegFrameFilter? = null
    private var leftover: ShortArray? = null
    private var leftoverPos = 0
    private var eof = false

    init {
        configureFilter()
    }

    private fun configureFilter() {
        filter?.runCatching { stop(); release() }
        filter = null
        if (speed != 1f) {
            filter = FFmpegFrameFilter("atempo=${"%.3f".format(java.util.Locale.ROOT, speed)}", OUT_CHANNELS).apply {
                sampleFormat = avutil.AV_SAMPLE_FMT_S16
                sampleRate = OUT_RATE
                start()
            }
        }
    }

    fun setSpeed(value: Float) {
        if (value == speed) return
        speed = value
        leftover = null
        configureFilter()
    }

    fun seek(ms: Long) {
        grabber.setTimestamp(ms.coerceAtLeast(0) * 1000L)
        decodedMs = ms.coerceAtLeast(0)
        leftover = null
        eof = false
        configureFilter()
    }

    private fun Frame.shorts(): ShortArray? {
        val buf = samples?.firstOrNull() as? ShortBuffer ?: return null
        val dup = buf.duplicate()
        return ShortArray(dup.remaining()).also { dup.get(it) }
    }

    /** Next decoded (and tempo-filtered) block, or null at the end of the file. */
    private fun nextBlock(): ShortArray? {
        while (!eof) {
            val frame = grabber.grabSamples()
            if (frame == null) {
                eof = true
                break
            }
            val raw = frame.shorts() ?: continue
            // Source-time position at the end of this frame.
            val frameStartMs = frame.timestamp / 1000
            decodedMs = frameStartMs + raw.size / OUT_CHANNELS * 1000L / OUT_RATE
            val f = filter ?: return raw
            f.push(frame)
            val out = generateSequence { f.pullSamples() }.mapNotNull { it.shorts() }.toList()
            if (out.isNotEmpty()) return concat(out)
        }
        // Drain whatever the tempo filter still holds.
        val f = filter ?: return null
        runCatching { f.push(null) }
        val out = generateSequence { runCatching { f.pullSamples() }.getOrNull() }.mapNotNull { it.shorts() }.toList()
        return if (out.isEmpty()) null else concat(out).also { filter = null }
    }

    private fun concat(parts: List<ShortArray>): ShortArray {
        if (parts.size == 1) return parts[0]
        val out = ShortArray(parts.sumOf { it.size })
        var p = 0
        parts.forEach { it.copyInto(out, p); p += it.size }
        return out
    }

    /** Fills [dst] with up to dst.size samples; returns how many were written (0 = finished). */
    fun fill(dst: ShortArray): Int {
        var written = 0
        while (written < dst.size) {
            var block = leftover
            if (block == null || leftoverPos >= block.size) {
                block = nextBlock() ?: break
                leftover = block
                leftoverPos = 0
            }
            val n = minOf(dst.size - written, block.size - leftoverPos)
            block.copyInto(dst, written, leftoverPos, leftoverPos + n)
            leftoverPos += n
            written += n
        }
        return written
    }

    /**
     * Decodes the first [ms] of audio now and keeps it in memory, so playing it later needs no
     * reading (from disk or the network) at the moment it starts.
     */
    fun prime(ms: Int) {
        val ahead = ShortArray(OUT_RATE * OUT_CHANNELS / 1000 * ms)
        val n = fill(ahead)
        // Whatever was still waiting behind those samples goes after them.
        val rest = leftover?.let { it.copyOfRange(leftoverPos.coerceAtMost(it.size), it.size) } ?: ShortArray(0)
        leftover = ahead.copyOf(n) + rest
        leftoverPos = 0
    }

    override fun close() {
        filter?.runCatching { stop(); release() }
        runCatching { grabber.stop() }
        runCatching { grabber.release() }
    }

    companion object {
        /**
         * Opens a file path, or an http(s) URL to stream ([headers] are sent with every request,
         * including the Range requests FFmpeg makes when seeking).
         */
        fun open(path: String, speed: Float, headers: Map<String, String> = emptyMap()): Decoder {
            val grabber = FFmpegFrameGrabber(path).apply {
                sampleFormat = avutil.AV_SAMPLE_FMT_S16
                sampleRate = OUT_RATE
                audioChannels = OUT_CHANNELS
                if (path.startsWith("http://") || path.startsWith("https://")) {
                    if (headers.isNotEmpty()) setOption("headers", headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" })
                    setOption("user_agent", "VanityPlayer/Desktop")
                    setOption("reconnect", "1")
                    setOption("reconnect_streamed", "1")
                    setOption("reconnect_delay_max", "5")
                    setOption("rw_timeout", "15000000") // 15 s, in microseconds
                }
            }
            try {
                grabber.start()
                if (grabber.audioStream < 0) error("No audio stream")
            } catch (t: Throwable) {
                runCatching { grabber.release() }
                throw t
            }
            return Decoder(grabber, speed)
        }
    }
}
