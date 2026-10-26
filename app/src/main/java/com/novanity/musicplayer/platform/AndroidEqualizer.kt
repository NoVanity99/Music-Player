package com.novanity.musicplayer.platform

import android.media.audiofx.Equalizer
import android.util.Log

/**
 * The system equalizer attached to the player's audio session. Shared in-process between the
 * playback service (which attaches it) and the settings UI (which reads its bands/presets).
 */
object AndroidEqualizer {
    private var eq: Equalizer? = null
    private var lastEnabled = false
    private var lastPreset = -1
    private var lastBands: List<Int> = emptyList()

    /** Bands and presets; probes a temporary instance if playback hasn't started yet. */
    val info: EqualizerInfo? by lazy {
        val probe = eq ?: runCatching { Equalizer(0, 0) }.getOrNull()
        val result = probe?.let { e ->
            runCatching {
                val range = e.bandLevelRange
                EqualizerInfo(
                    bands = (0 until e.numberOfBands).map { b ->
                        EqBand(e.getCenterFreq(b.toShort()) / 1000, range[0].toInt(), range[1].toInt())
                    },
                    presets = (0 until e.numberOfPresets).map { e.getPresetName(it.toShort()) },
                )
            }.getOrNull()
        }
        if (probe !== eq) probe?.release()
        result
    }

    fun attach(audioSessionId: Int) {
        release()
        eq = runCatching { Equalizer(0, audioSessionId) }.onFailure { Log.w("AndroidEqualizer", "No equalizer", it) }.getOrNull()
        apply(lastEnabled, lastPreset, lastBands)
    }

    fun apply(enabled: Boolean, preset: Int, bands: List<Int>) {
        lastEnabled = enabled; lastPreset = preset; lastBands = bands
        val e = eq ?: return
        runCatching {
            if (enabled) {
                if (preset in 0 until e.numberOfPresets) {
                    e.usePreset(preset.toShort())
                } else {
                    bands.forEachIndexed { i, level -> if (i < e.numberOfBands) e.setBandLevel(i.toShort(), level.toShort()) }
                }
            }
            e.enabled = enabled
        }
    }

    fun release() {
        eq?.runCatching { release() }
        eq = null
    }
}
