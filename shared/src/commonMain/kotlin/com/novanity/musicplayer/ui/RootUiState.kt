package com.novanity.musicplayer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

/** Activity-scoped UI flags that must survive rotation and be reachable from intents. */
class RootUiState : ViewModel() {
    var nowPlayingOpen by mutableStateOf(false)

    /** Desktop: keyboard shortcuts sheet. */
    var showShortcuts by mutableStateOf(false)

    /** Wide layout: right-hand side panel (queue or lyrics); null = hidden. */
    var sidePanel by mutableStateOf<SidePanel?>(SidePanel.QUEUE)
}

enum class SidePanel { QUEUE, LYRICS }
