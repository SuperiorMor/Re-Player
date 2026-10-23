package com.replayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.replayer.model.MediaItem
import com.replayer.ui.components.NavItem
import com.replayer.ui.components.TvNavigationRail
import com.replayer.ui.screens.GoogleDriveScreen
import com.replayer.ui.screens.HomeScreen
import com.replayer.ui.screens.PlayerScreen
import com.replayer.ui.screens.UpnpBrowseScreen
import com.replayer.ui.theme.DarkBackground
import com.replayer.ui.theme.RePlayerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RePlayerTheme {
                var selectedNav by remember { mutableStateOf(NavItem.HOME) }
                var activeMediaItem by remember { mutableStateOf<MediaItem?>(null) }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(DarkBackground)
                ) {
                    if (activeMediaItem != null) {
                        // Fullscreen Player
                        PlayerScreen(
                            mediaItem = activeMediaItem!!,
                            onBack = { activeMediaItem = null }
                        )
                    } else {
                        // TV Navigation & Main UI
                        Row(modifier = Modifier.fillMaxSize()) {
                            TvNavigationRail(
                                selectedItem = selectedNav,
                                onItemSelected = { selectedNav = it }
                            )

                            Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                                when (selectedNav) {
                                    NavItem.HOME -> HomeScreen(onNavigate = { selectedNav = it })
                                    NavItem.UPNP -> UpnpBrowseScreen(onPlayMedia = { activeMediaItem = it })
                                    NavItem.GDRIVE -> GoogleDriveScreen(onPlayMedia = { activeMediaItem = it })
                                    NavItem.SETTINGS -> HomeScreen(onNavigate = { selectedNav = it })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
