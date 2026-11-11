package com.replayer

import android.os.Build
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
import androidx.compose.material.icons.outlined.ExitToApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.replayer.diagnostics.CrashReporter
import com.replayer.model.MediaItem
import com.replayer.model.MediaSourceType
import com.replayer.ui.components.AppSplashScreen
import com.replayer.ui.components.NavItem
import com.replayer.ui.components.TvFocusCard
import com.replayer.ui.components.TvNavigationRail
import com.replayer.ui.screens.HomeScreen
import com.replayer.ui.screens.IptvScreen
import com.replayer.ui.screens.PlayerScreen
import com.replayer.ui.screens.SettingsScreen
import com.replayer.ui.screens.UpnpBrowseScreen
import com.replayer.ui.theme.AccentCyan
import com.replayer.ui.theme.AccentOrange
import com.replayer.ui.theme.DarkBackground
import com.replayer.ui.theme.RePlayerTheme
import com.replayer.ui.theme.TextPrimary
import com.replayer.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RePlayerTheme {
                var showSplash by remember { mutableStateOf(true) }

                if (showSplash) {
                    AppSplashScreen(onTimeout = { showSplash = false })
                } else {
                    val scope = rememberCoroutineScope()
                    val windowInfo = LocalWindowInfo.current
                    val railFocusRequesters = remember {
                        NavItem.entries.associateWith { FocusRequester() }
                    }

                    var selectedNav by remember { mutableStateOf(NavItem.HOME) }
                    var activeMediaItem by remember { mutableStateOf<MediaItem?>(null) }
                    var activePlaylist by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
                    var showExitDialog by remember { mutableStateOf(false) }
                    var initialUpnpFolder by remember { mutableStateOf<Pair<String, String>?>(null) }
                    var lastPlayedMediaId by remember { mutableStateOf<String?>(null) }
                    var sectionFocusTrigger by remember { mutableIntStateOf(0) }
                    var pendingEnterSection by remember { mutableStateOf<NavItem?>(null) }
                    var anyMainUiHasFocus by remember { mutableStateOf(false) }
                    var wasFocusInContent by remember { mutableStateOf(false) }
                    var hadPlayerActive by remember { mutableStateOf(false) }

                    fun focusRailSelected() {
                        wasFocusInContent = false
                        pendingEnterSection = null
                        scope.launch {
                            delay(40)
                            try {
                                railFocusRequesters[selectedNav]?.requestFocus()
                            } catch (_: Exception) {}
                        }
                    }

                    fun requestEnterSection(nav: NavItem) {
                        selectedNav = nav
                        pendingEnterSection = nav
                        sectionFocusTrigger++
                    }

                    LaunchedEffect(selectedNav) {
                        CrashReporter.breadcrumb("Nav", "Tab=$selectedNav")
                    }

                    LaunchedEffect(activeMediaItem?.id) {
                        val item = activeMediaItem
                        if (item != null) {
                            hadPlayerActive = true
                            CrashReporter.breadcrumb("Nav", "OpenPlayer: ${item.sourceType} ${item.title}")
                        } else {
                            CrashReporter.breadcrumb("Nav", "ClosePlayer")
                            if (hadPlayerActive) {
                                hadPlayerActive = false
                                if (wasFocusInContent) {
                                    if (selectedNav != NavItem.UPNP) {
                                        requestEnterSection(selectedNav)
                                    }
                                } else {
                                    focusRailSelected()
                                }
                            }
                        }
                    }

                    // Initial focus immediately when Splash finishes so cursor is always visible on start
                    LaunchedEffect(Unit) {
                        delay(80)
                        if (!anyMainUiHasFocus) {
                            try {
                                railFocusRequesters[selectedNav]?.requestFocus()
                            } catch (_: Exception) {}
                        }
                    }

                    // Global watchdog: if window regains focus (from dialog/system window/task switch)
                    // and no view inside MainActivity has focus, restore focus to the active section in rail
                    LaunchedEffect(windowInfo.isWindowFocused, anyMainUiHasFocus, activeMediaItem, showExitDialog, selectedNav) {
                        if (windowInfo.isWindowFocused && activeMediaItem == null && !showExitDialog && !anyMainUiHasFocus) {
                            delay(450)
                            if (windowInfo.isWindowFocused && activeMediaItem == null && !showExitDialog && !anyMainUiHasFocus) {
                                wasFocusInContent = false
                                try {
                                    railFocusRequesters[selectedNav]?.requestFocus()
                                } catch (_: Exception) {}
                            }
                        }
                    }

                    // Exit confirmation dialog on Home screen
                    if (activeMediaItem == null && selectedNav == NavItem.HOME && !showExitDialog) {
                        BackHandler(enabled = true) {
                            showExitDialog = true
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(Color(0xFF14D4F4).copy(alpha = 0.08f), DarkBackground),
                                    center = Offset(0f, 0f),
                                    radius = 1500f
                                )
                            )
                    ) {
                        // TV Navigation & Main UI (always kept alive so state is preserved on player close)
                        @OptIn(ExperimentalComposeUiApi::class)
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .onFocusChanged { state ->
                                    anyMainUiHasFocus = state.hasFocus
                                }
                                .focusProperties {
                                    canFocus = (activeMediaItem == null)
                                }
                        ) {
                            TvNavigationRail(
                                selectedItem = selectedNav,
                                itemFocusRequesters = railFocusRequesters,
                                shouldSnapToSelectedOnFocus = wasFocusInContent,
                                onSnappedToSelected = { wasFocusInContent = false },
                                onItemSelected = { item ->
                                    wasFocusInContent = false
                                    pendingEnterSection = null
                                    if (selectedNav != item) {
                                        selectedNav = item
                                        initialUpnpFolder = null
                                    }
                                },
                                onEnterSection = { item ->
                                    wasFocusInContent = false
                                    initialUpnpFolder = null
                                    requestEnterSection(item)
                                }
                            )

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxSize()
                                    .onFocusChanged { state ->
                                        if (state.hasFocus) {
                                            wasFocusInContent = true
                                        }
                                    }
                            ) {
                                when (selectedNav) {
                                    NavItem.HOME -> HomeScreen(
                                        onOpenFavorite = { fav ->
                                            if (fav.sourceType == MediaSourceType.UPNP_DLNA) {
                                                initialUpnpFolder = Pair(fav.id, fav.title)
                                                requestEnterSection(NavItem.UPNP)
                                            }
                                        },
                                        onPlayMedia = { mediaItem ->
                                            lastPlayedMediaId = mediaItem.id
                                            activeMediaItem = mediaItem
                                        },
                                        onPlayChannel = { channel, channelList ->
                                            activePlaylist = channelList.map { it.toMediaItem() }.ifEmpty { listOf(channel.toMediaItem()) }
                                            activeMediaItem = channel.toMediaItem()
                                        },
                                        onFallbackToRail = { focusRailSelected() },
                                        enterSectionTrigger = if (pendingEnterSection == NavItem.HOME) sectionFocusTrigger else 0,
                                        onConsumeEnterTrigger = {
                                            if (pendingEnterSection == NavItem.HOME) pendingEnterSection = null
                                        }
                                    )
                                    NavItem.UPNP -> UpnpBrowseScreen(
                                        onPlayMedia = { item, playlist ->
                                            lastPlayedMediaId = item.id
                                            activeMediaItem = item
                                            activePlaylist = playlist
                                        },
                                        onNavigateToSettings = {
                                            requestEnterSection(NavItem.SETTINGS)
                                        },
                                        onBackToHome = {
                                            selectedNav = NavItem.HOME
                                            focusRailSelected()
                                        },
                                        initialFolder = initialUpnpFolder,
                                        lastPlayedMediaId = lastPlayedMediaId,
                                        isPlayerActive = (activeMediaItem != null),
                                        enterSectionTrigger = if (pendingEnterSection == NavItem.UPNP) sectionFocusTrigger else 0,
                                        onConsumeEnterTrigger = {
                                            if (pendingEnterSection == NavItem.UPNP) pendingEnterSection = null
                                        },
                                        onFolderLoaded = { initialUpnpFolder = null },
                                        onConsumeLastPlayedMediaId = { lastPlayedMediaId = null }
                                    )
                                    NavItem.IPTV -> IptvScreen(
                                        onPlayChannel = { channel, channelList ->
                                            activePlaylist = channelList.map { it.toMediaItem() }.ifEmpty { listOf(channel.toMediaItem()) }
                                            activeMediaItem = channel.toMediaItem()
                                        },
                                        onNavigateToSettings = {
                                            requestEnterSection(NavItem.SETTINGS)
                                        },
                                        onBackToHome = {
                                            selectedNav = NavItem.HOME
                                            focusRailSelected()
                                        },
                                        enterSectionTrigger = if (pendingEnterSection == NavItem.IPTV) sectionFocusTrigger else 0,
                                        onConsumeEnterTrigger = {
                                            if (pendingEnterSection == NavItem.IPTV) pendingEnterSection = null
                                        }
                                    )
                                    NavItem.SETTINGS -> SettingsScreen(
                                        onBack = {
                                            selectedNav = NavItem.HOME
                                            focusRailSelected()
                                        },
                                        enterSectionTrigger = if (pendingEnterSection == NavItem.SETTINGS) sectionFocusTrigger else 0,
                                        onConsumeEnterTrigger = {
                                            if (pendingEnterSection == NavItem.SETTINGS) pendingEnterSection = null
                                        }
                                    )
                                }
                            }
                        }

                        // Fullscreen Player Overlay
                        if (activeMediaItem != null) {
                            PlayerScreen(
                                mediaItem = activeMediaItem!!,
                                playlist = activePlaylist,
                                onPlayMediaItem = { activeMediaItem = it },
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
                                        .border(1.dp, AccentCyan.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .matchParentSize()
                                            .background(Color(0xCC151B26))
                                            .then(
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                                    Modifier.blur(20.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                                                } else Modifier
                                            )
                                    )
                                    Column(
                                        modifier = Modifier.padding(28.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            Icons.Outlined.ExitToApp,
                                            contentDescription = null,
                                            tint = AccentCyan,
                                            modifier = Modifier.size(40.dp)
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
                                                        color = AccentOrange
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
}
