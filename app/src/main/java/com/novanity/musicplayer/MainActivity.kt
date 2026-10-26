package com.novanity.musicplayer

import android.content.Intent
import android.graphics.Color
import android.media.AudioManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.novanity.musicplayer.data.ThemeMode
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.novanity.musicplayer.platform.AndroidPlatformUi
import com.novanity.musicplayer.platform.LocalPlatform
import com.novanity.musicplayer.playback.Media3PlayerController
import com.novanity.musicplayer.ui.LibraryViewModel
import com.novanity.musicplayer.ui.MusicPlayerRoot
import com.novanity.musicplayer.ui.PlayerViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.novanity.musicplayer.ui.RootUiState
import com.novanity.musicplayer.ui.screens.PermissionGate
import com.novanity.musicplayer.ui.theme.MusicPlayerTheme

class MainActivity : ComponentActivity() {

    private val rootUiState: RootUiState by viewModels()
    private val libraryViewModel: LibraryViewModel by viewModels {
        viewModelFactory {
            initializer {
                val g = appContainer
                LibraryViewModel(g.musicRepository, g.playlistRepository, g.historyRepository, g.favoritesRepository, g.settings)
            }
        }
    }
    private val playerViewModel: PlayerViewModel by viewModels {
        viewModelFactory {
            initializer {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                PlayerViewModel(Media3PlayerController(applicationContext, appContainer.musicRepository, scope), appContainer.settings)
            }
        }
    }
    private val platform by lazy { AndroidPlatformUi(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        volumeControlStream = AudioManager.STREAM_MUSIC
        handleIntent(intent)
        val updates = (application as MusicApp).updates
        if (savedInstanceState == null) updates.checkOnLaunch()

        val container = appContainer
        setContent {
            val themeMode by container.settings.themeMode.collectAsStateWithLifecycle()
            val dynamicColor by container.settings.dynamicColor.collectAsStateWithLifecycle()
            val dark = when (themeMode) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            LaunchedEffect(dark) {
                val style = if (dark) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            CompositionLocalProvider(LocalPlatform provides platform) {
                MusicPlayerTheme(darkTheme = dark, dynamicColor = dynamicColor) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        PermissionGate {
                            MusicPlayerRoot(libraryViewModel, playerViewModel, rootUiState, updates)
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from "Install unknown apps": continue a pending update install.
        (application as MusicApp).updates.resumeInstall()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_NOW_PLAYING, false) == true) {
            rootUiState.nowPlayingOpen = true
            intent.removeExtra(EXTRA_OPEN_NOW_PLAYING)
        }
    }

    companion object {
        const val EXTRA_OPEN_NOW_PLAYING = "open_now_playing"
    }
}
