package com.novanity.musicplayer.ui

import com.novanity.musicplayer.ui.components.AppBackHandler
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.novanity.musicplayer.ui.components.AddToPlaylistDialog
import com.novanity.musicplayer.ui.components.LocalNowPlaying
import com.novanity.musicplayer.ui.components.NowPlayingInfo
import com.novanity.musicplayer.ui.screens.DetailScreen
import com.novanity.musicplayer.ui.screens.FolderSetupScreen
import com.novanity.musicplayer.ui.screens.HomeScreen
import com.novanity.musicplayer.ui.screens.LibraryScreen
import com.novanity.musicplayer.ui.screens.MiniPlayer
import com.novanity.musicplayer.ui.screens.NowPlayingScreen
import com.novanity.musicplayer.ui.screens.SearchScreen

/** The whole app UI, shared by Android and desktop. Each platform creates the ViewModels. */
@Composable
fun MusicPlayerRoot(
    library: LibraryViewModel,
    player: PlayerViewModel,
    uiState: RootUiState,
    updates: com.novanity.musicplayer.update.UpdateManager? = null,
) {
    val actions = remember(library, player, updates) { SongActions(player, library, updates) }
    updates?.let { com.novanity.musicplayer.ui.components.UpdateDialog(it) }
    val snackbars = remember { SnackbarHostState() }
    LaunchedEffect(library) {
        library.messages.collect { snackbars.currentSnackbarData?.dismiss()
            snackbars.showSnackbar(it) }
    }

    val state by player.state.collectAsStateWithLifecycle()
    val songs by library.songs.collectAsStateWithLifecycle()
    val songsById = remember(songs) { library.songsById }
    val likedIds by library.likedIds.collectAsStateWithLifecycle()
    val currentSong = state.current?.songId?.let(songsById::get)
    val stateHolder = rememberSaveableStateHolder()

    val includedFolders by library.includedFolders.collectAsStateWithLifecycle()
    LaunchedEffect(library) {
        library.newSongsFound.collect { n -> library.notify(if (n == 1) "Found 1 new song" else "Found $n new songs") }
    }

    // No folder chosen yet: nothing is auto-detected, so ask where the music lives.
    if (includedFolders.isEmpty()) {
        FolderSetupScreen(actions)
        return
    }

    AppBackHandler(enabled = library.backStack.isNotEmpty() || library.tab != Tab.HOME) { library.back() }

    val screens: @Composable (PaddingValues) -> Unit = { padding ->
        val destination = library.backStack.lastOrNull()
        AnimatedContent(
            targetState = Triple(library.tab, library.backStack.size, destination),
            transitionSpec = {
                val forward = targetState.first == initialState.first && targetState.second > initialState.second
                if (forward) {
                    (slideInHorizontally(tween(260)) { it / 6 } + fadeIn(tween(260))) togetherWith fadeOut(tween(160))
                } else {
                    fadeIn(tween(220)) togetherWith fadeOut(tween(160))
                }
            },
            label = "screen",
        ) { (tab, depth, dest) ->
            // Keep each screen's scroll position when navigating away and back.
            stateHolder.SaveableStateProvider("$tab/$depth/$dest") {
                Box(Modifier.fillMaxSize()) {
                    when {
                        dest != null -> DetailScreen(dest, actions, padding)
                        tab == Tab.HOME -> HomeScreen(actions, padding)
                        tab == Tab.SEARCH -> SearchScreen(actions, padding)
                        else -> LibraryScreen(actions, padding)
                    }
                }
            }
        }
    }

    CompositionLocalProvider(LocalNowPlaying provides NowPlayingInfo(state.current?.songId, state.isPlaying)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= WideLayoutMinWidth
            if (wide) {
                // Desktop / large windows: sidebar, content panel, side panel and a player bar.
                Scaffold(
                    snackbarHost = { SnackbarHost(snackbars, Modifier.padding(bottom = 100.dp)) },
                    contentWindowInsets = WindowInsets(0),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                ) { _ ->
                    WideLayout(library, player, actions, uiState, maxWidth, screens)
                }
            } else {
                Scaffold(
                    snackbarHost = { SnackbarHost(snackbars) },
                    contentWindowInsets = WindowInsets(0),
                    containerColor = MaterialTheme.colorScheme.background,
                    bottomBar = {
                        val bg = MaterialTheme.colorScheme.background
                        Column(
                            Modifier.background(
                                // Fade in behind the mini player, then fully opaque behind the tabs.
                                Brush.verticalGradient(0f to Color.Transparent, 0.3f to bg.copy(alpha = 0.85f), 0.45f to bg, 1f to bg)
                            )
                        ) {
                            if (state.hasMedia) {
                                MiniPlayer(
                                    state = state,
                                    song = currentSong,
                                    actions = actions,
                                    liked = currentSong != null && currentSong.id in likedIds,
                                    onExpand = { uiState.nowPlayingOpen = true },
                                )
                            }
                            BottomNav(library.tab, library::selectTab)
                        }
                    },
                ) { padding -> screens(padding) }
                if (uiState.showShortcuts) ShortcutsDialog { uiState.showShortcuts = false }
            }

            AnimatedVisibility(
                visible = uiState.nowPlayingOpen && state.hasMedia,
                enter = slideInVertically(tween(320)) { it },
                exit = slideOutVertically(tween(260)) { it },
            ) {
                AppBackHandler { uiState.nowPlayingOpen = false }
                NowPlayingScreen(
                    state = state,
                    songsById = songsById,
                    actions = actions,
                    onCollapse = { uiState.nowPlayingOpen = false },
                )
            }
        }
    }

    library.pendingPlaylistAdd?.let { pending ->
        val playlists by library.playlists.collectAsStateWithLifecycle()
        val dismiss = { library.pendingPlaylistAdd = null }
        AddToPlaylistDialog(
            songs = pending,
            playlists = playlists,
            onPick = { playlist ->
                library.addToPlaylist(playlist.id, pending)
                library.notify("Added to “${playlist.name}”")
                dismiss()
            },
            onCreate = { name ->
                library.createPlaylist(name, pending)
                library.notify("Created “$name”")
                dismiss()
            },
            onDismiss = dismiss,
        )
    }
}

@Composable
private fun BottomNav(selected: Tab, onSelect: (Tab) -> Unit) {
    NavigationBar(containerColor = Color.Transparent, tonalElevation = 0.dp) {
        Tab.entries.forEach { tab ->
            val isSelected = tab == selected
            NavigationBarItem(
                selected = isSelected,
                onClick = { onSelect(tab) },
                icon = {
                    Icon(
                        when (tab) {
                            Tab.HOME -> if (isSelected) Icons.Rounded.Home else Icons.Outlined.Home
                            Tab.SEARCH -> Icons.Rounded.Search
                            Tab.LIBRARY -> if (isSelected) Icons.Rounded.LibraryMusic else Icons.Outlined.LibraryMusic
                        },
                        contentDescription = null,
                    )
                },
                label = { Text(tab.label, style = MaterialTheme.typography.labelMedium) },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = Color.Transparent,
                    selectedIconColor = MaterialTheme.colorScheme.onSurface,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}
