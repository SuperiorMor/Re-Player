package com.replayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.replayer.model.FavoriteFolder
import com.replayer.model.MediaItem
import com.replayer.model.MediaSourceType
import com.replayer.ui.components.NavItem
import com.replayer.ui.components.TvFocusCard
import com.replayer.ui.components.TvNavigationRail
import com.replayer.ui.screens.GoogleDriveScreen
import com.replayer.ui.screens.HomeScreen
import com.replayer.ui.screens.PlayerScreen
import com.replayer.ui.screens.SettingsScreen
import com.replayer.ui.screens.UpnpBrowseScreen
import com.replayer.ui.theme.AccentCyan
import com.replayer.ui.theme.DarkBackground
import com.replayer.ui.theme.DarkCard
import com.replayer.ui.theme.RePlayerTheme
import com.replayer.ui.theme.TextPrimary
import com.replayer.ui.theme.TextSecondary

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RePlayerTheme {
                var selectedNav by remember { mutableStateOf(NavItem.HOME) }
                var activeMediaItem by remember { mutableStateOf<MediaItem?>(null) }
                var showExitDialog by remember { mutableStateOf(false) }
                var initialUpnpFolder by remember { mutableStateOf<Pair<String, String>?>(null) }
                var initialGdriveFolder by remember { mutableStateOf<Pair<String, String>?>(null) }

                // Exit confirmation dialog on Home screen
                if (activeMediaItem == null && selectedNav == NavItem.HOME && !showExitDialog) {
                    BackHandler(enabled = true) {
                        showExitDialog = true
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(DarkBackground)
                ) {
                    // TV Navigation & Main UI (always kept alive so state is preserved on player close)
                    Row(modifier = Modifier.fillMaxSize()) {
                        TvNavigationRail(
                            selectedItem = selectedNav,
                            onItemSelected = {
                                selectedNav = it
                                initialUpnpFolder = null
                                initialGdriveFolder = null
                            }
                        )

                        Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                            when (selectedNav) {
                                NavItem.HOME -> HomeScreen(
                                    onOpenFavorite = { fav ->
                                        if (fav.sourceType == MediaSourceType.UPNP) {
                                            initialUpnpFolder = Pair(fav.id, fav.title)
                                            selectedNav = NavItem.UPNP
                                        } else if (fav.sourceType == MediaSourceType.GOOGLE_DRIVE) {
                                            initialGdriveFolder = Pair(fav.id, fav.title)
                                            selectedNav = NavItem.GDRIVE
                                        }
                                    }
                                )
                                NavItem.UPNP -> UpnpBrowseScreen(
                                    onPlayMedia = { activeMediaItem = it },
                                    onNavigateToSettings = { selectedNav = NavItem.SETTINGS },
                                    onBackToHome = { selectedNav = NavItem.HOME },
                                    initialFolder = initialUpnpFolder
                                )
                                NavItem.GDRIVE -> GoogleDriveScreen(
                                    onPlayMedia = { activeMediaItem = it },
                                    onNavigateToSettings = { selectedNav = NavItem.SETTINGS },
                                    onBackToHome = { selectedNav = NavItem.HOME },
                                    initialFolder = initialGdriveFolder
                                )
                                NavItem.SETTINGS -> SettingsScreen(
                                    onBack = { selectedNav = NavItem.HOME }
                                )
                            }
                        }
                    }

                    // Fullscreen Player Overlay
                    if (activeMediaItem != null) {
                        PlayerScreen(
                            mediaItem = activeMediaItem!!,
                            onBack = { activeMediaItem = null }
                        )
                    }

                    // TV Exit Confirmation Dialog
                    if (showExitDialog) {
                        BackHandler(enabled = true) {
                            showExitDialog = false
                        }

                        Dialog(onDismissRequest = { showExitDialog = false }) {
                            Box(
                                modifier = Modifier
                                    .width(440.dp)
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(DarkCard)
                                    .border(1.dp, AccentCyan.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                                    .padding(28.dp)
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        Icons.Default.ExitToApp,
                                        contentDescription = null,
                                        tint = AccentCyan,
                                        modifier = Modifier.size(44.dp)
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = "Выход из Re:Player",
                                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                        color = TextPrimary
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Вы точно хотите закрыть приложение?",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = TextSecondary,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(24.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                                    ) {
                                        TvFocusCard(
                                            onClick = {
                                                showExitDialog = false
                                                this@MainActivity.finish()
                                            },
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(52.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier.fillMaxSize(),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = "Да, выйти",
                                                    style = MaterialTheme.typography.titleMedium,
                                                    color = Color(0xFFFF5252)
                                                )
                                            }
                                        }

                                        TvFocusCard(
                                            onClick = { showExitDialog = false },
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(52.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier.fillMaxSize(),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = "Отмена",
                                                    style = MaterialTheme.typography.titleMedium,
                                                    color = TextPrimary
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
