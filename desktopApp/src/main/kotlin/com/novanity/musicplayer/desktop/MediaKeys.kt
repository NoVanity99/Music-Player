package com.novanity.musicplayer.desktop

import com.github.kwhat.jnativehook.GlobalScreen
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener
import java.util.logging.Level
import java.util.logging.Logger

/**
 * System-wide media keys (play/pause, next, previous, stop) that work while the window is in the
 * background. If the native hook can't load, media keys still work while the window is focused.
 */
object MediaKeys {
    @Volatile
    var active = false
        private set

    fun start(onPlayPause: () -> Unit, onNext: () -> Unit, onPrevious: () -> Unit, onStop: () -> Unit) {
        runCatching {
            Logger.getLogger(GlobalScreen::class.java.`package`.name).apply {
                level = Level.OFF
                useParentHandlers = false
            }
            GlobalScreen.registerNativeHook()
            GlobalScreen.addNativeKeyListener(object : NativeKeyListener {
                override fun nativeKeyPressed(e: NativeKeyEvent) {
                    when (e.keyCode) {
                        NativeKeyEvent.VC_MEDIA_PLAY -> onPlayPause()
                        NativeKeyEvent.VC_MEDIA_NEXT -> onNext()
                        NativeKeyEvent.VC_MEDIA_PREVIOUS -> onPrevious()
                        NativeKeyEvent.VC_MEDIA_STOP -> onStop()
                    }
                }
            })
            active = true
        }
    }

    fun stop() {
        if (active) runCatching { GlobalScreen.unregisterNativeHook() }
        active = false
    }
}
