package com.novanity.musicplayer.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.novanity.musicplayer.AppContainer
import com.novanity.musicplayer.ui.components.AddToPlaylistDialog
import com.novanity.musicplayer.ui.screens.DetailScreen
import com.novanity.musicplayer.ui.screens.LibraryScreen
import com.novanity.musicplayer.ui.screens.MiniPlayer
import com.novanity.musicplayer.ui.screens.NowPlayingScreen

@Composable
fun MusicPlayerRoot(container: AppContainer, uiState: RootUiState) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val library: LibraryViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                LibraryViewModel(container.musicRepository, container.playlistRepository, container.historyRepository, container.settings)
            }
        }
    )
    val player: PlayerViewModel = viewModel(
        factory = viewModelFactory { initializer { PlayerViewModel(appContext, container.musicRepository) } }
    )
    val actions = remember(library, player) { SongActions(appContext, player, library) }

    val state by player.state.collectAsStateWithLifecycle()
    // Re-read songsById whenever the library changes.
    val songs by container.musicRepository.songs.collectAsStateWithLifecycle()
    val songsById = remember(songs) { container.musicRepository.songsById }
    val currentSongId = state.current?.songId
    val destination = library.backStack.lastOrNull()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                BackHandler(enabled = destination != null) { library.pop() }
                if (destination == null) {
                    LibraryScreen(actions, currentSongId)
                } else {
                    DetailScreen(destination, actions, currentSongId)
                }
            }
            if (state.hasMedia) {
                MiniPlayer(
                    state = state,
                    song = currentSongId?.let(songsById::get),
                    player = player,
                    onExpand = { uiState.nowPlayingOpen = true },
                )
            } else {
                Spacer(Modifier.navigationBarsPadding())
            }
        }

        AnimatedVisibility(
            visible = uiState.nowPlayingOpen && state.hasMedia,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            BackHandler { uiState.nowPlayingOpen = false }
            NowPlayingScreen(
                state = state,
                songsById = songsById,
                actions = actions,
                onCollapse = { uiState.nowPlayingOpen = false },
            )
        }
    }

    library.pendingPlaylistAdd.value?.let { pending ->
        val playlists by library.allPlaylists.collectAsStateWithLifecycle()
        val dismiss = { library.pendingPlaylistAdd.value = null }
        AddToPlaylistDialog(
            songs = pending,
            playlists = playlists,
            onPick = { playlist ->
                library.addToPlaylist(playlist.id, pending)
                Toast.makeText(context, "Added to “${playlist.name}”", Toast.LENGTH_SHORT).show()
                dismiss()
            },
            onCreate = { name ->
                library.createPlaylist(name, pending)
                Toast.makeText(context, "Created “$name”", Toast.LENGTH_SHORT).show()
                dismiss()
            },
            onDismiss = dismiss,
        )
    }
}
