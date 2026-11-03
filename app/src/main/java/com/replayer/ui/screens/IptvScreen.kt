package com.replayer.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
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
    BackHandler(enabled = true) {
        onBackToHome()
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val iptvManager = remember { IptvManager(context) }

    val channels by iptvManager.channels.collectAsState()
    val favoriteIds by iptvManager.favoriteChannelIds.collectAsState()
    val epgMap by iptvManager.epgMap.collectAsState()
    val isLoading by iptvManager.isLoading.collectAsState()
    val statusMsg by iptvManager.statusMessage.collectAsState()

    val groups = remember(channels) { iptvManager.getGroups() }
    var selectedGroup by remember { mutableStateOf("Все") }

    val filteredChannels = remember(channels, selectedGroup, favoriteIds) {
        when (selectedGroup) {
            "Все" -> channels
            "Избранное" -> channels.filter { favoriteIds.contains(it.id) }
            else -> channels.filter { it.group == selectedGroup }
        }
    }

    val firstItemFocusRequester = remember { FocusRequester() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 24.dp)
    ) {
        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "ТВ каналы",
                    style = MaterialTheme.typography.displayLarge
                )
                Text(
                    text = if (channels.isEmpty()) "Плейлист не настроен" else "Каналов: ${channels.size} • $selectedGroup",
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
                            if (isLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = AccentCyan, strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(18.dp))
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = "Обновить", style = MaterialTheme.typography.bodyMedium)
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
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "Настройки ТВ", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Empty Playlist State
        if (channels.isEmpty()) {
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
                        text = "Плейлист IPTV не добавлен",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Добавьте ссылку на плейлист M3U / M3U8 в настройках приложения",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    TvFocusCard(
                        onClick = onNavigateToSettings,
                        modifier = Modifier
                            .width(220.dp)
                            .height(48.dp)
                    ) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "Настроить IPTV",
                                color = AccentCyan,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            }
        } else {
            // Categories Row (Horizontal Pills)
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(groups) { group ->
                    val isSelected = group == selectedGroup
                    TvFocusCard(
                        onClick = { selectedGroup = group },
                        modifier = Modifier.height(40.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .background(if (isSelected) AccentCyan.copy(alpha = 0.25f) else Color.Transparent)
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = group,
                                color = if (isSelected) AccentCyan else TextPrimary,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Channel List with Televizo-style EPG
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                itemsIndexed(filteredChannels, key = { _, ch -> ch.id }) { index, channel ->
                    val isFav = favoriteIds.contains(channel.id)
                    val prog = remember(channel, epgMap) { iptvManager.getCurrentProgram(channel) }
                    val isFirst = index == 0

                    TvFocusCard(
                        onClick = { onPlayChannel(channel) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(76.dp)
                            .then(if (isFirst) Modifier.focusRequester(firstItemFocusRequester) else Modifier)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Channel Logo or Placeholder
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(DarkSurface),
                                contentAlignment = Alignment.Center
                            ) {
                                if (!channel.logoUrl.isNullOrBlank()) {
                                    AsyncImage(
                                        model = channel.logoUrl,
                                        contentDescription = channel.name,
                                        modifier = Modifier.size(44.dp)
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Tv,
                                        contentDescription = null,
                                        tint = AccentCyan,
                                        modifier = Modifier.size(26.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(16.dp))

                            // Channel Title & EPG Program
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = channel.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(AccentCyan.copy(alpha = 0.15f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = "LIVE",
                                            color = AccentCyan,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                if (prog != null) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "${prog.formattedTimeRange()}  ${prog.title}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    LinearProgressIndicator(
                                        progress = { prog.progress() },
                                        modifier = Modifier
                                            .width(180.dp)
                                            .height(3.dp)
                                            .clip(RoundedCornerShape(2.dp)),
                                        color = AccentCyan,
                                        trackColor = DarkSurface
                                    )
                                } else {
                                    Text(
                                        text = channel.group,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(16.dp))

                            // Favorite Toggle Button
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(if (isFav) Color(0xFFFF4081).copy(alpha = 0.2f) else DarkSurface)
                                    .clickable { iptvManager.toggleFavorite(channel.id) },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    contentDescription = "Избранное",
                                    tint = if (isFav) Color(0xFFFF4081) else TextSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
