package com.novanity.musicplayer.desktop

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateShortcutTest {
    @Test
    fun onlyVanityPlayerShortcutsAreMatched() {
        assertTrue(DesktopUpdatePlatform.isVanityShortcut("VanityPlayer.lnk"))
        assertTrue(DesktopUpdatePlatform.isVanityShortcut("Vanity Player.lnk"))
        assertTrue(DesktopUpdatePlatform.isVanityShortcut("vanityplayer.LNK"))
        // The launcher is a separate app: its shortcut is never touched.
        assertFalse(DesktopUpdatePlatform.isVanityShortcut("Vanity.lnk"))
        assertFalse(DesktopUpdatePlatform.isVanityShortcut("VanityPlayer.exe"))
        assertFalse(DesktopUpdatePlatform.isVanityShortcut("Spotify.lnk"))
    }
}
