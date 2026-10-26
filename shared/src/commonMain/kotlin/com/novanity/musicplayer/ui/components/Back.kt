package com.novanity.musicplayer.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi

/** System back (Android back gesture / Esc on desktop) for the shared UI. */
@OptIn(ExperimentalComposeUiApi::class)
@Suppress("DEPRECATION") // Still the simplest cross-platform API; NavigationEventHandler is its successor.
@Composable
fun AppBackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    androidx.compose.ui.backhandler.BackHandler(enabled, onBack)
}
