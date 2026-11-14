package com.novanity.musicplayer.desktop.audio

import com.novanity.musicplayer.AppGraph
import com.novanity.musicplayer.data.Song
import com.novanity.musicplayer.playback.PlayerController
import com.novanity.musicplayer.playback.PlayerUiState
import com.novanity.musicplayer.playback.QueueEntry
import com.novanity.musicplayer.playback.RepeatMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Desktop playback: FFmpeg decoding into the system audio output, with the queue, shuffle, repeat,
 * crossfade and state persistence. All state lives on one engine thread; public methods just post
 * commands to it.
 */
class FfmpegPlayerController(
    private val graph: AppGraph,
    private val scope: CoroutineScope,
    private val onError: (String) -> Unit,
) : PlayerController {

    private class Entry(val key: String, val song: Song)

    private val _state = MutableStateFlow(PlayerUiState(connected = true))
    override val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val analyzer = SpectrumAnalyzer()
    override val spectrum: StateFlow<FloatArray> = analyzer.spectrum
    private val _upNext = MutableStateFlow<List<QueueEntry>>(emptyList())
    override val upNext: StateFlow<List<QueueEntry>> = _upNext.asStateFlow()

    private val commands = LinkedBlockingQueue<() -> Unit>()
    private val keys = AtomicLong(0)
    @Volatile private var reportedPositionMs = 0L
    @Volatile private var alive = true

    // ---- Engine-thread state ----
    private val entries = ArrayList<Entry>()
    private val order = ArrayList<Int>() // play order of entry indices
    private var current = -1
    private var playing = false
    private var shuffle = false
    private var repeat = RepeatMode.OFF
    private var volume = 1f
    private var speed = 1f
    private var crossfadeMs = 0
    private var voice: Decoder? = null
    private var fading: Decoder? = null
    private var fadePos = 0L
    private var fadeTotal = 0L
    private var errorStreak = 0
    private var lastHistoryKey: String? = null
    private var lastSave = 0L
    private val output = AudioOutput.open()
    private val buffer = ShortArray(2048 * OUT_CHANNELS)
    private val fadeBuffer = ShortArray(buffer.size)
    private val bytes = ByteArray(buffer.size * 2)

    // Equalizer: the settings are read on the engine thread, the filters live there.
    @Volatile private var eqLevels: List<Int>? = null
    private val eq = DesktopEqualizer.Chain()
    private val eqTail = DesktopEqualizer.Chain()

    // Advanced transitions: the next song of the album is opened ahead of time and joined on
    // without a gap. [preloadKey] is the queue entry being (or already) opened.
    private val reader = GaplessReader(buffer.size)
    private var preloadKey: String? = null
    private var preloaded: Decoder? = null

    private val thread = Thread(::runEngine, "audio-engine").apply {
        isDaemon = true
        priority = Thread.MAX_PRIORITY - 1
        start()
    }

    init {
        restore()
        scope.launch {
            kotlinx.coroutines.flow.combine(graph.settings.eqEnabled, graph.settings.eqPreset, graph.settings.eqBands) { e, p, b -> DesktopEqualizer.levels(e, p, b) }
                .collect { eqLevels = it }
        }
    }

    private fun post(cmd: () -> Unit) {
        commands.offer(cmd)
    }

    // ================= Public API (any thread) =================

    override fun currentPositionMs(): Long = reportedPositionMs

    override fun setQueue(songs: List<Song>, startIndex: Int, shuffle: Boolean) = post {
        replaceQueue(songs, startIndex, shuffle, startMs = 0, play = true)
    }

    override fun playNext(songs: List<Song>) = post {
        if (entries.isEmpty()) return@post replaceQueue(songs, 0, false, 0, true)
        val at = current + 1
        insert(at, songs, afterCurrentInOrder = true)
    }

    override fun addToQueue(songs: List<Song>) = post {
        if (entries.isEmpty()) return@post replaceQueue(songs, 0, false, 0, true)
        insert(entries.size, songs, afterCurrentInOrder = false)
    }

    override fun togglePlayPause() = post {
        if (current < 0) return@post
        if (voice == null) openCurrent(0, keepPlaying = true) else playing = !playing
        if (playing) errorStreak = 0
        publish()
    }

    override fun pause() = post {
        playing = false
        publish()
    }

    override fun previous() = post {
        if (reportedPositionMs > 3_000 || orderPos() <= 0 && repeat != RepeatMode.ALL) seekInternal(0) else step(-1, userInitiated = true)
    }

    override fun next() = post { step(+1, userInitiated = true) }
    override fun previousTrack() = post { step(-1, userInitiated = true) }
    override fun nextTrack() = post { step(+1, userInitiated = true) }
    override fun seekTo(positionMs: Long) = post { seekInternal(positionMs) }

    override fun setShuffle(enabled: Boolean) = post {
        shuffle = enabled
        rebuildOrder()
        graph.playbackStateStore.saveModes(shuffle, repeat.storedValue)
        publish(queueChanged = true)
    }

    override fun setRepeatMode(mode: RepeatMode) = post {
        repeat = mode
        graph.playbackStateStore.saveModes(shuffle, repeat.storedValue)
        publish(queueChanged = true)
    }

    override fun playQueueIndex(index: Int) = post {
        if (index !in entries.indices) return@post
        current = index
        playing = true
        openCurrent(0, keepPlaying = true)
    }

    override fun removeQueueIndex(index: Int) = post {
        if (index !in entries.indices) return@post
        if (index == current) {
            val wasPlaying = playing
            val nextIdx = order.getOrNull(orderPos() + 1)
            removeAt(index)
            if (nextIdx != null) {
                current = if (nextIdx > index) nextIdx - 1 else nextIdx
                openCurrent(0, keepPlaying = wasPlaying)
            } else {
                closeVoices()
                current = if (entries.isEmpty()) -1 else 0
                playing = false
                publish(queueChanged = true)
            }
        } else {
            removeAt(index)
            publish(queueChanged = true)
        }
        saveQueue()
    }

    override fun moveQueueItem(from: Int, to: Int) = post {
        if (from == to || from !in entries.indices || to !in entries.indices) return@post
        val currentEntry = entries.getOrNull(current)
        val e = entries.removeAt(from)
        entries.add(to, e)
        current = entries.indexOf(currentEntry)
        rebuildOrder(keepShuffle = shuffle)
        saveQueue()
        publish(queueChanged = true)
    }

    override fun clearUpNext() = post {
        val keep = entries.getOrNull(current) ?: return@post
        entries.clear(); entries += keep
        current = 0
        rebuildOrder()
        saveQueue()
        publish(queueChanged = true)
    }

    override fun setVolume(volume: Float) = post { this.volume = volume.coerceIn(0f, 1f) }

    override fun setSpeed(speed: Float) = post {
        this.speed = speed.coerceIn(0.5f, 2f)
        voice?.setSpeed(this.speed)
        preloaded?.setSpeed(this.speed)
        output.flush()
    }

    override fun setCrossfadeMs(ms: Int) = post { crossfadeMs = ms.coerceIn(0, 12_000) }

    override fun release() {
        post {
            saveAll()
            closeVoices()
            alive = false
        }
        thread.join(1_500)
        output.close()
    }

    // ================= Engine thread =================

    private fun runEngine() {
        while (alive) {
            try {
                var cmd = commands.poll()
                while (cmd != null) {
                    cmd()
                    cmd = commands.poll()
                }
                val v = voice
                if (!playing || v == null) {
                    output.pause()
                    commands.poll(100, TimeUnit.MILLISECONDS)?.invoke()
                    continue
                }
                output.resume()
                renderChunk(v)
            } catch (t: Throwable) {
                // Never let one bad file or command kill playback.
                handleDecodeError(t)
            }
        }
    }

    private fun renderChunk(first: Decoder) {
        var v = first
        // Start crossfading into the next track near the end of this one.
        val seamless = seamlessNext()
        if (seamless != null) preloadIfNear(v, seamless) else dropPreload()
        if (seamless == null && crossfadeMs > 0 && fading == null && v.durationMs > crossfadeMs * 2 &&
            v.positionMs >= v.durationMs - crossfadeMs && repeat != RepeatMode.ONE
        ) {
            if (startCrossfade()) return
        }

        var n = reader.fill(v, buffer, 0, trim = seamless != null)
        if (reader.finished) {
            val next = preloaded
            if (seamless != null && next != null && preloadKey == entries[seamless].key) {
                // Join the next song of the album onto this one, sample to sample.
                preloaded = null
                preloadKey = null
                v.close()
                voice = next
                v = next
                current = seamless
                reader.reset(trimStart = true)
                n += reader.fill(next, buffer, n, trim = seamlessNext() != null)
                lastHistoryKey = null
                publish(queueChanged = true)
                graph.playbackStateStore.savePosition(current, 0)
            } else if (n == 0) {
                onTrackFinished()
                return
            }
        }
        if (n == 0) return
        val levels = eqLevels
        eq.process(buffer, n, levels)
        val gain = volume * volume // perceptual volume curve
        val out = fading
        if (out != null) {
            val m = out.fill(fadeBuffer)
            eqTail.process(fadeBuffer, m, levels)
            for (i in 0 until n) {
                val p = ((fadePos + i / OUT_CHANNELS).toDouble() / fadeTotal).coerceIn(0.0, 1.0)
                val gIn = sin(p * PI / 2)
                val gOut = cos(p * PI / 2)
                val tail = if (i < m) fadeBuffer[i] * gOut else 0.0
                buffer[i] = clip((buffer[i] * gIn + tail) * gain)
            }
            fadePos += n / OUT_CHANNELS
            if (m == 0 || fadePos >= fadeTotal) {
                out.close()
                fading = null
            }
        } else if (gain != 1f) {
            for (i in 0 until n) buffer[i] = clip(buffer[i] * gain.toDouble())
        }
        analyzer.feed(buffer, n, gain.toDouble())
        for (i in 0 until n) {
            val s = buffer[i].toInt()
            bytes[i * 2] = (s and 0xFF).toByte()
            bytes[i * 2 + 1] = (s shr 8).toByte()
        }
        output.write(bytes, n * 2)
        errorStreak = 0
        reportedPositionMs = (v.positionMs - (output.latencyMs() * v.speed).toLong()).coerceAtLeast(0)
        recordHistoryIfNeeded()
        val now = System.currentTimeMillis()
        if (now - lastSave > 5_000) {
            lastSave = now
            graph.playbackStateStore.savePosition(current, reportedPositionMs)
        }
    }

    private companion object {
        /** How long before the end of a song the next one is opened for a seamless join. */
        const val PRELOAD_MS = 20_000L
    }

    private fun clip(v: Double): Short = v.coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble()).toInt().toShort()

    private fun startCrossfade(): Boolean {
        val nextIdx = nextIndex(+1) ?: return false
        val incoming = try {
            openSong(entries[nextIdx].song)
        } catch (_: Throwable) {
            return false // let the normal end-of-track path handle the bad file
        }
        fading = voice
        reader.reset(trimStart = false)
        fadePos = 0
        fadeTotal = crossfadeMs.toLong() * OUT_RATE / 1000
        voice = incoming
        current = nextIdx
        reportedPositionMs = 0
        lastHistoryKey = null
        publish(queueChanged = true)
        graph.playbackStateStore.savePosition(current, 0)
        return true
    }

    private fun onTrackFinished() {
        if (repeat == RepeatMode.ONE) {
            voice?.seek(0)
            lastHistoryKey = null
            return
        }
        val nextIdx = nextIndex(+1)
        if (nextIdx == null) {
            // End of the queue: stop at the start of the last song, like Android does.
            closeVoices()
            playing = false
            reportedPositionMs = 0
            publish()
            return
        }
        current = nextIdx
        openCurrent(0, keepPlaying = true, flush = false) // let the last notes already queued play out
    }

    /** The next queue entry when it should be joined on seamlessly (same marked album, setting on). */
    private fun seamlessNext(): Int? {
        if (repeat == RepeatMode.ONE || !graph.settings.advancedTransitions.value) return null
        val a = entries.getOrNull(current)?.song ?: return null
        val nextIdx = nextIndex(+1)?.takeIf { it != current } ?: return null
        val b = entries[nextIdx].song
        return nextIdx.takeIf { a.albumId == b.albumId && graph.cloud.hasAdvancedTransitions(a) }
    }

    /** Opens the next song in the background shortly before this one ends, so the join never waits. */
    private fun preloadIfNear(v: Decoder, nextIdx: Int) {
        val entry = entries[nextIdx]
        if (preloadKey == entry.key) return
        if (v.durationMs <= 0 || v.durationMs - v.positionMs > PRELOAD_MS) return
        dropPreload()
        preloadKey = entry.key
        Thread({
            val d = runCatching { openSong(entry.song) }.getOrNull()
            post { if (d != null && preloadKey == entry.key && preloaded == null) preloaded = d else d?.close() }
        }, "audio-preload").apply { isDaemon = true }.start()
    }

    private fun dropPreload() {
        preloaded?.close()
        preloaded = null
        preloadKey = null
    }

    private fun handleDecodeError(t: Throwable) {
        val song = entries.getOrNull(current)?.song
        val title = song?.title ?: "this track"
        val streamed = song != null && (graph.musicRepository.songsById[song.id]?.uri ?: song.uri).startsWith("http")
        if (streamed) {
            // A stream that won't open is a connection or key problem: skipping ahead wouldn't help.
            onError("Can't stream “$title”. Check your internet connection.")
            scope.launch { graph.cloud.refresh() } // if the server is gone, switch the library to offline
            closeVoices()
            errorStreak = 0
            playing = false
            publish()
            return
        }
        onError("Can't play “$title” — skipping")
        closeVoices()
        errorStreak++
        val nextIdx = nextIndex(+1)
        if (errorStreak >= entries.size || nextIdx == null) {
            errorStreak = 0
            playing = false
            publish()
            return
        }
        current = nextIdx
        openCurrent(0, keepPlaying = true)
    }

    /** Opens the current entry's decoder at [startMs]. Decode errors are routed to [handleDecodeError]. */
    private fun openCurrent(startMs: Long, keepPlaying: Boolean, flush: Boolean = true) {
        closeVoices()
        val entry = entries.getOrNull(current) ?: return publish(queueChanged = true)
        try {
            val d = openSong(entry.song)
            if (startMs > 0) d.seek(startMs)
            voice = d
            reader.reset(trimStart = false)
            playing = keepPlaying
            reportedPositionMs = startMs
            lastHistoryKey = null
        } catch (t: Throwable) {
            publish(queueChanged = true)
            return handleDecodeError(t)
        }
        if (flush) output.flush()
        graph.playbackStateStore.savePosition(current, startMs)
        publish(queueChanged = true)
    }

    /**
     * Opens a song's decoder. A cloud song that has been downloaded since it was queued plays from
     * the local file; otherwise it streams, with the access key sent only to the user's own server.
     */
    private fun openSong(song: Song): Decoder {
        val uri = graph.musicRepository.songsById[song.id]?.uri ?: song.uri
        return Decoder.open(uri, speed, if (graph.cloud.isOurs(uri)) graph.cloud.authHeaders else emptyMap())
    }

    private fun closeVoices() {
        voice?.close(); voice = null
        fading?.close(); fading = null
        dropPreload()
    }

    private fun seekInternal(ms: Long) {
        val v = voice
        if (v == null) {
            if (current >= 0) openCurrent(ms, keepPlaying = playing)
            return
        }
        fading?.close(); fading = null
        v.seek(ms)
        reader.reset(trimStart = false)
        output.flush()
        reportedPositionMs = ms
        graph.playbackStateStore.savePosition(current, ms)
    }

    private fun step(direction: Int, userInitiated: Boolean) {
        val target = nextIndex(direction) ?: return
        current = target
        openCurrent(0, keepPlaying = playing || userInitiated)
    }

    private fun orderPos(): Int = order.indexOf(current)

    private fun nextIndex(direction: Int): Int? {
        if (order.isEmpty()) return null
        val p = orderPos()
        val q = p + direction
        return when {
            q in order.indices -> order[q]
            repeat == RepeatMode.ALL -> order[(q + order.size) % order.size]
            else -> null
        }
    }

    private fun rebuildOrder(keepShuffle: Boolean = shuffle) {
        order.clear()
        if (!keepShuffle || entries.isEmpty()) {
            order += entries.indices
            return
        }
        // Current song first, the rest shuffled after it.
        val rest = entries.indices.filter { it != current }.shuffled()
        if (current >= 0) order += current
        order += rest
    }

    private fun replaceQueue(songs: List<Song>, startIndex: Int, shuffle: Boolean, startMs: Long, play: Boolean) {
        entries.clear()
        songs.forEach { entries += Entry("${it.id}/${keys.incrementAndGet()}", it) }
        current = if (entries.isEmpty()) -1 else startIndex.coerceIn(entries.indices)
        this.shuffle = shuffle
        rebuildOrder()
        errorStreak = 0
        saveQueue()
        graph.playbackStateStore.saveModes(shuffle, repeat.storedValue)
        if (current >= 0) openCurrent(startMs, keepPlaying = play) else publish(queueChanged = true)
    }

    private fun insert(at: Int, songs: List<Song>, afterCurrentInOrder: Boolean) {
        val newEntries = songs.map { Entry("${it.id}/${keys.incrementAndGet()}", it) }
        entries.addAll(at, newEntries)
        if (current >= at) current += newEntries.size
        for (i in order.indices) if (order[i] >= at) order[i] += newEntries.size
        val newIdx = (at until at + newEntries.size).toList()
        if (afterCurrentInOrder) order.addAll(orderPos() + 1, newIdx) else order.addAll(newIdx)
        saveQueue()
        publish(queueChanged = true)
    }

    private fun removeAt(index: Int) {
        entries.removeAt(index)
        order.remove(index)
        for (i in order.indices) if (order[i] > index) order[i] -= 1
        if (current > index) current -= 1
    }

    private fun recordHistoryIfNeeded() {
        val entry = entries.getOrNull(current) ?: return
        if (entry.key == lastHistoryKey) return
        lastHistoryKey = entry.key
        scope.launch(Dispatchers.IO) { graph.historyRepository.record(entry.song.id) }
    }

    private fun publish(queueChanged: Boolean = false) {
        val entry = entries.getOrNull(current)
        val p = orderPos()
        _state.value = PlayerUiState(
            connected = true,
            current = entry?.let { QueueEntry(current, it.key, it.song.id, it.song.title, it.song.artist) },
            isPlaying = playing && voice != null,
            durationMs = voice?.durationMs?.takeIf { it > 0 } ?: entry?.song?.durationMs ?: 0,
            shuffle = shuffle,
            repeatMode = repeat,
            hasNext = p >= 0 && (p + 1 < order.size || (repeat == RepeatMode.ALL && order.size > 1)),
            hasPrevious = p >= 0 && (p > 0 || (repeat == RepeatMode.ALL && order.size > 1)),
        )
        if (queueChanged) {
            _upNext.value = if (p < 0) emptyList() else order.drop(p + 1).map { i ->
                val e = entries[i]
                QueueEntry(i, e.key, e.song.id, e.song.title, e.song.artist)
            }
        }
    }

    private fun saveQueue() = graph.playbackStateStore.saveQueue(entries.map { it.song.id })

    private fun saveAll() {
        saveQueue()
        graph.playbackStateStore.savePosition(current, reportedPositionMs)
        graph.playbackStateStore.saveModes(shuffle, repeat.storedValue)
    }

    /** Puts back the last session's queue and position (paused), like the Android app does. */
    private fun restore() {
        scope.launch(Dispatchers.IO) {
            val saved = graph.playbackStateStore.load() ?: return@launch
            val byId = graph.musicRepository.ensureLoaded()
            val songs = ArrayList<Song>()
            var start = 0
            var found = false
            saved.songIds.forEachIndexed { i, id ->
                val s = byId[id] ?: return@forEachIndexed
                if (i == saved.index) { start = songs.size; found = true }
                songs += s
            }
            if (songs.isEmpty()) return@launch
            post {
                if (entries.isNotEmpty()) return@post // the user already started something
                repeat = RepeatMode.fromStored(saved.repeatMode)
                replaceQueue(songs, start, saved.shuffle, if (found) saved.positionMs else 0, play = false)
            }
        }
    }
}
