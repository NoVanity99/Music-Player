package com.novanity.musicplayer.desktop

import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameRecorder
import java.io.File
import java.nio.ShortBuffer
import kotlin.math.PI
import kotlin.math.sin

/** Writes a short tagged sine-wave track with FFmpeg, for tests. */
fun writeTestTrack(file: File, seconds: Double, freq: Double, title: String, artist: String, album: String, track: Int) {
    val rate = 44_100
    val (codec, format) = when (file.extension) {
        "mp3" -> avcodec.AV_CODEC_ID_MP3 to "mp3"
        "flac" -> avcodec.AV_CODEC_ID_FLAC to "flac"
        "opus" -> avcodec.AV_CODEC_ID_OPUS to "ogg"
        else -> avcodec.AV_CODEC_ID_PCM_S16LE to "wav"
    }
    val recorder = FFmpegFrameRecorder(file, 2).apply {
        this.format = format
        audioCodec = codec
        sampleRate = if (codec == avcodec.AV_CODEC_ID_OPUS) 48_000 else rate
        audioBitrate = 128_000
        if (codec == avcodec.AV_CODEC_ID_OPUS) setAudioOption("strict", "-2")
        setMetadata("title", title)
        setMetadata("artist", artist)
        setMetadata("album", album)
        setMetadata("track", track.toString())
    }
    recorder.start()
    val sr = recorder.sampleRate
    val total = (seconds * sr).toInt()
    val chunk = 1024
    var n = 0
    while (n < total) {
        val len = minOf(chunk, total - n)
        val buf = ShortArray(len * 2)
        for (i in 0 until len) {
            val v = (sin(2 * PI * freq * (n + i) / sr) * 8000).toInt().toShort()
            buf[i * 2] = v; buf[i * 2 + 1] = v
        }
        recorder.recordSamples(sr, 2, ShortBuffer.wrap(buf))
        n += len
    }
    recorder.stop()
    recorder.release()
    check(avutil.AV_SAMPLE_FMT_S16 >= 0)
}
