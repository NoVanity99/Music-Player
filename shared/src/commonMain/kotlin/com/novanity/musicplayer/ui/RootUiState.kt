package com.novanity.musicplayer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

/** Activity-scoped UI flags that must survive rotation and be reachable from intents. */
class RootUiState : ViewModel() {
    var nowPlayingOpen by mutableStateOf(false)
}
