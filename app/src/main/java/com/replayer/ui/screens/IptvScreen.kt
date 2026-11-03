package com.replayer.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.replayer.data.ChannelSortMode
import com.replayer.data.IptvManager
import com.replayer.model.IptvChannel
import com.replayer.ui.components.TvFocusCard
import com.replayer.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun IptvScreen(
    onPlayChannel: (IptvChannel) -> Unit,
    onNavigateToSettings: () -> Unit,
    onBackToHome: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val iptvManager = remember { IptvManager.getInstance(context) }

    val channels by iptvManager.channels.collectAsState()
    val favoriteIds by iptvManager.favoriteChannelIds.collectAsState()
    val epgMap by iptvManager.epgMap.collectAsState()
    val isLoading by iptvManager.isLoading.collectAsState()
    val epgProgress by iptvManager.epgProgress.collectAsState()
    val statusMsg by iptvManager.statusMessage.collectAsState()

    // Level 1: Category folder selection. Level 2: Inside folder
    var selectedFolder by remember { mutableStateOf<String?>(null) }
    var sortMode by remember { mutableStateOf(ChannelSortMode.DEFAULT) }

    val folders = remember(channels) { iptvManager.getCategoryFolders() }

    // Remote back button handling: inside folder -> back to folders; on folders -> back to Home
    BackHandler(enabled = true) {
        if (selectedFolder != null && folders.size > 1) {
            selectedFolder = null
        } else {
            onBackToHome()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 24.dp)
    ) {
        // TOP BAR
        val isInsideFolder = selectedFolder != null || (folders.size <= 1 && channels.isNotEmpty())
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isInsideFolder) {
                val currentFolder = selectedFolder ?: folders.firstOrNull() ?: "Все каналы"
                // Inside Folder Header
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (folders.size > 1 && selectedFolder != null) {
                        TvFocusCard(
                            onClick = { selectedFolder = null },
                            modifier = Modifier
                                .width(130.dp)
                                .height(44.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Назад", tint = AccentCyan, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Папки", style = MaterialTheme.typography.bodyMedium, color = AccentCyan)
                            }
                        }
                        Spacer(Modifier.width(16.dp))
                    }
                    Column {
                        Text(
                            text = if (folders.size <= 1) "ТВ каналы" else currentFolder,
                            style = MaterialTheme.typography.displayLarge
                        )
                        val folderChannels = remember(channels, currentFolder) {
                            iptvManager.getChannelsInFolder(currentFolder)
                        }
                        Text(
                            text = "Каналов: ${folderChannels.size} • Сортировка: ${sortMode.label}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }

                // Sort toggle button inside folder (Requirement 9)
                TvFocusCard(
                    onClick = {
                        sortMode = when (sortMode) {
                            ChannelSortMode.DEFAULT -> ChannelSortMode.BY_NAME
                            ChannelSortMode.BY_NAME -> ChannelSortMode.BY_WATCH_TIME
                            ChannelSortMode.BY_WATCH_TIME -> ChannelSortMode.DEFAULT
                        }
                    },
                    modifier = Modifier
                        .width(230.dp)
                        .height(44.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Sort, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(text = sortMode.label, style = MaterialTheme.typography.bodyMedium, color = AccentCyan)
                    }
                }
            } else {
                // Top Level (Folders List) Header
                Column {
                    Text(
                        text = "ТВ каналы",
                        style = MaterialTheme.typography.displayLarge
                    )
                    Text(
                        text = if (channels.isEmpty()) "Плейлист не настроен" else "Категорий: ${folders.size} • Всего каналов: ${channels.size}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (channels.isNotEmpty()) {
                        TvFocusCard(
                            onClick = { scope.launch { iptvManager.refreshPlaylist() } },
                            modifier = Modifier
                                .width(150.dp)
                                .height(44.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Обновить", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }

                    TvFocusCard(
                        onClick = onNavigateToSettings,
                        modifier = Modifier
                            .width(160.dp)
                            .height(44.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Настройки ТВ", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }

        // Live EPG / Playlist background progress indicator (Requirement 1)
        if (epgProgress != null || isLoading) {
            Spacer(modifier = Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator(
                    progress = { epgProgress ?: 0.5f },
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = AccentCyan,
                    trackColor = DarkSurface
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = statusMsg ?: "Загрузка...",
                    style = MaterialTheme.typography.bodySmall,
                    color = AccentCyan
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // MAIN CONTENT
        if (channels.isEmpty()) {
            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = statusMsg ?: "Загрузка каналов...",
                            style = MaterialTheme.typography.titleMedium,
                            color = AccentCyan
                        )
                    }
                }
            } else {
                // Empty State: Playlist Not Configured
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 60.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(DarkCard),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Tv,
                                contentDescription = null,
                                tint = AccentCyan.copy(alpha = 0.7f),
                                modifier = Modifier.size(38.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Плейлист IPTV не загружен",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Добавьте ссылку на .m3u плейлист или выберите файл в Настройках",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        TvFocusCard(
                            onClick = onNavigateToSettings,
                            modifier = Modifier
                                .width(220.dp)
                                .height(48.dp)
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Перейти в настройки", color = AccentCyan, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        } else if (selectedFolder == null && folders.size > 1) {
            // LEVEL 1: Category Folders Grid (Requirement 5 & 8)
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 240.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                items(folders) { folder ->
                    val count = remember(channels, folder) {
                        iptvManager.getChannelsInFolder(folder).size
                    }

                    TvFocusCard(
                        onClick = { selectedFolder = folder },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(110.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(20.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(AccentCyan.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Folder,
                                        contentDescription = null,
                                        tint = AccentCyan,
                                        modifier = Modifier.size(26.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(16.dp))
                                Column {
                                    Text(
                                        text = folder,
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "$count каналов",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // LEVEL 2: Channels inside the selected folder (or all channels if single folder)
            val currentFolder = selectedFolder ?: folders.firstOrNull() ?: "Все каналы"
            val folderChannels = remember(channels, currentFolder) {
                iptvManager.getChannelsInFolder(currentFolder)
            }
            val sortedList = remember(folderChannels, sortMode) {
                iptvManager.getSortedChannels(folderChannels, sortMode)
            }

            if (sortedList.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "В этой категории нет каналов",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextSecondary
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 32.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(sortedList, key = { it.id }) { channel ->
                        val isFav = favoriteIds.contains(channel.id)
                        val currentProg = remember(channel, epgMap) {
                            iptvManager.getCurrentProgram(channel)
                        }

                        TvFocusCard(
                            onClick = { onPlayChannel(channel) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(78.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 20.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (!channel.logoUrl.isNullOrBlank()) {
                                        AsyncImage(
                                            model = channel.logoUrl,
                                            contentDescription = channel.name,
                                            modifier = Modifier
                                                .size(48.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Color.White.copy(alpha = 0.08f)),
                                            contentScale = ContentScale.Fit
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(48.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(DarkSurface),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Tv,
                                                contentDescription = null,
                                                tint = AccentCyan,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(16.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = channel.name,
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                            color = TextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))

                                        // Subtitle: Shows CURRENT PROGRAM instead of "Общие" (Requirement 7)
                                        if (currentProg != null) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = "${currentProg.formattedTimeRange()}  ${currentProg.title}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = TextSecondary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f, fill = false)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                LinearProgressIndicator(
                                                    progress = { currentProg.progress() },
                                                    modifier = Modifier
                                                        .width(44.dp)
                                                        .height(4.dp)
                                                        .clip(RoundedCornerShape(2.dp)),
                                                    color = AccentCyan,
                                                    trackColor = DarkSurface
                                                )
                                            }
                                        } else {
                                            Text(
                                                text = "Прямой эфир",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = TextSecondary
                                            )
                                        }
                                    }
                                }

                                // Favorite Toggle Button
                                IconButton(
                                    onClick = { iptvManager.toggleFavorite(channel.id) },
                                    modifier = Modifier.padding(start = 12.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                        contentDescription = if (isFav) "В избранном" else "Добавить в избранное",
                                        tint = if (isFav) Color(0xFFFF4081) else TextSecondary,
                                        modifier = Modifier.size(24.dp)
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
