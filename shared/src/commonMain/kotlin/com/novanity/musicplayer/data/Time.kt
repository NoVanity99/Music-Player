package com.novanity.musicplayer.data

import kotlin.time.Clock

/** Wall-clock time in epoch milliseconds. */
fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()
