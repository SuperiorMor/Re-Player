package com.replayer.ui.screens

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.replayer.data.ChannelSortMode
import com.replayer.data.IptvManager
import com.replayer.model.IptvChannel
import com.replayer.ui.components.TvFocusCard
import com.replayer.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun IptvScreen(
    onPlayChannel: (IptvChannel) -> Unit,
    onNavigateToSettings: () -> Unit,
    onBackToHome: () -> Unit = {},
    enterSectionTrigger: Int = 0,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val iptvManager = remember { IptvManager.getInstance(context) }

    val channels by iptvManager.channels.collectAsState()
    val favoriteIds by iptvManager.favoriteChannelIds.collectAsState()
    val epgMap by iptvManager.epgMap.collectAsState()
    val watchTimes by iptvManager.watchTimes.collectAsState()
    val isLoading by iptvManager.isLoading.collectAsState()
    val epgProgress by iptvManager.epgProgress.collectAsState()
    val statusMsg by iptvManager.statusMessage.collectAsState()

    // Level 1: Category folder selection. Level 2: Inside folder
    var selectedFolder by remember { mutableStateOf<String?>(null) }
    var sortMode by remember { mutableStateOf(ChannelSortMode.BY_WATCH_TIME) }
    val entryFocusRequester = remember { FocusRequester() }
    val holdFocusRequester = remember { FocusRequester() }
    var isSwitchingView by remember { mutableStateOf(false) }
    var pendingFolderTarget by remember { mutableStateOf<String?>(null) }
    var pendingHasSwitch by remember { mutableStateOf(false) }

    fun switchCategory(targetFolder: String?) {
        pendingFolderTarget = targetFolder
        pendingHasSwitch = true
        isSwitchingView = true
    }

    LaunchedEffect(isSwitchingView, pendingHasSwitch) {
        if (!isSwitchingView || !pendingHasSwitch) return@LaunchedEffect
        delay(16)
        try {
            holdFocusRequester.requestFocus()
        } catch (_: Exception) {}
        selectedFolder = pendingFolderTarget
        pendingHasSwitch = false
        delay(150)
        try {
            entryFocusRequester.requestFocus()
        } catch (_: Exception) {}
        isSwitchingView = false
    }

    LaunchedEffect(enterSectionTrigger, isLoading, channels.isEmpty()) {
        if (enterSectionTrigger > 0 && !isSwitchingView) {
            delay(150) // Важно! Ждем отрисовки Compose
            try {
                entryFocusRequester.requestFocus()
            } catch (_: Exception) {}
        }
    }

    val folders = remember(channels) { iptvManager.getCategoryFolders() }

    var showLocalFileDialog by remember { mutableStateOf(false) }
    var localFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var isScanningLocalFiles by remember { mutableStateOf(false) }

    fun scanLocalFiles() {
        isScanningLocalFiles = true
        scope.launch(Dispatchers.IO) {
            val candidates = mutableListOf<File>()
            val searchDirs = listOfNotNull(
                File("/storage"),
                File("/sdcard"),
                File("/storage/emulated/0/Download"),
                File("/storage/emulated/0"),
                context.getExternalFilesDir(null)
            )
            for (dir in searchDirs) {
                try {
                    if (dir.exists() && dir.canRead()) {
                        dir.walkTopDown().maxDepth(3).forEach { f ->
                            if (f.isFile && (f.name.endsWith(".m3u", ignoreCase = true) || f.name.endsWith(".m3u8", ignoreCase = true))) {
                                candidates.add(f)
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
            localFiles = candidates.distinctBy { it.absolutePath }
            isScanningLocalFiles = false
            showLocalFileDialog = true
        }
    }

    // Remote back button handling: inside folder -> back to folders; on folders -> back to Home
    BackHandler(enabled = true) {
        if (selectedFolder != null && folders.size > 1) {
            switchCategory(null)
        } else {
            onBackToHome()
        }
    }

    val isInsideFolder = selectedFolder != null || (folders.size <= 1 && channels.isNotEmpty())

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(start = 28.dp, end = 28.dp, top = 24.dp, bottom = 16.dp)
    ) {
        if (isSwitchingView) {
            Box(
                modifier = Modifier
                    .size(1.dp)
                    .focusRequester(holdFocusRequester)
                    .focusable()
            )
        }
        // TOP BAR — vertically aligned with the left 64dp Logo
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isInsideFolder) {
                val currentFolder = selectedFolder ?: folders.firstOrNull() ?: "Все каналы"
                // Inside Folder Header (Compact so 8+ channels fit)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (folders.size > 1 && selectedFolder != null) {
                        TvFocusCard(
                            onClick = { switchCategory(null) },
                            modifier = Modifier
                                .width(116.dp)
                                .height(38.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.ArrowBackIosNew, contentDescription = "Назад", tint = AccentCyan, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Папки", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold), color = AccentCyan)
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (folders.size <= 1) "ТВ каналы" else currentFolder,
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                        val folderChannels = remember(channels, currentFolder) {
                            iptvManager.getChannelsInFolder(currentFolder)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "• ${folderChannels.size} каналов",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }

                // Sort toggle button inside folder
                TvFocusCard(
                    onClick = {
                        sortMode = when (sortMode) {
                            ChannelSortMode.BY_WATCH_TIME -> ChannelSortMode.BY_NAME
                            ChannelSortMode.BY_NAME -> ChannelSortMode.DEFAULT
                            ChannelSortMode.DEFAULT -> ChannelSortMode.BY_WATCH_TIME
                        }
                    },
                    modifier = Modifier
                        .width(210.dp)
                        .height(38.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.SwapVert, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(text = sortMode.label, style = MaterialTheme.typography.bodySmall, color = AccentCyan)
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
                                Icon(Icons.Outlined.Refresh, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(18.dp))
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
                            .then(
                                if (channels.isEmpty() && isLoading) Modifier.focusRequester(entryFocusRequester)
                                else Modifier
                            )
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Outlined.Settings, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(18.dp))
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(DarkCard)
                    .border(1.dp, BorderSubtle, RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(DarkSurface)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction = (epgProgress ?: 0.5f).coerceIn(0.04f, 1f))
                            .fillMaxHeight()
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(AccentCyan, AccentGold, AccentOrange)
                                )
                            )
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = statusMsg ?: "Загрузка...",
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color = AccentCyanBright
                )
            }
        }

        Spacer(modifier = Modifier.height(if (isInsideFolder) 10.dp else 16.dp))

        // MAIN CONTENT
        if (channels.isEmpty()) {
            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(DarkCard)
                            .border(1.dp, BorderSubtle, RoundedCornerShape(14.dp))
                            .padding(horizontal = 24.dp, vertical = 16.dp)
                    ) {
                        CircularProgressIndicator(
                            color = AccentCyan,
                            strokeWidth = 2.5.dp,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Text(
                            text = statusMsg ?: "Загрузка каналов...",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
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
                                imageVector = Icons.Outlined.LiveTv,
                                contentDescription = null,
                                tint = AccentCyan.copy(alpha = 0.7f),
                                modifier = Modifier.size(38.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = if (epgMap.isNotEmpty()) "Телепрограмма готова, но нет списка каналов" else "Плейлист IPTV не загружен",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (epgMap.isNotEmpty()) {
                                "Телепрограмма загружена (${epgMap.size} каналов в базе). Для показа ТВ каналов требуется плейлист (.m3u / .m3u8) со ссылками на видеопотоки."
                            } else {
                                "Добавьте ссылку на .m3u плейлист или выберите файл с флешки"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 48.dp)
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            TvFocusCard(
                                onClick = { scanLocalFiles() },
                                modifier = Modifier
                                    .width(240.dp)
                                    .height(48.dp)
                                    .focusRequester(entryFocusRequester)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Outlined.FolderOpen, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (isScanningLocalFiles) "Поиск файлов..." else "Плейлист с флешки",
                                        color = AccentCyan,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                            TvFocusCard(
                                onClick = onNavigateToSettings,
                                modifier = Modifier
                                    .width(200.dp)
                                    .height(48.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Outlined.Settings, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Настройки ТВ", color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                                }
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
                itemsIndexed(folders) { folderIdx, folder ->
                    val count = remember(channels, folder) {
                        iptvManager.getChannelsInFolder(folder).size
                    }

                    TvFocusCard(
                        onClick = { switchCategory(folder) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(84.dp)
                            .then(if (folderIdx == 0) Modifier.focusRequester(entryFocusRequester) else Modifier)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
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
                                        imageVector = Icons.Outlined.FolderOpen,
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
            val sortedList = remember(folderChannels, sortMode, watchTimes) {
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
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(sortedList, key = { _, ch -> ch.id }) { idx, channel ->
                        val isFav = favoriteIds.contains(channel.id)
                        val currentProg = remember(channel, epgMap) {
                            iptvManager.getCurrentProgram(channel)
                        }
                        val progress = currentProg?.progress()?.coerceIn(0f, 1f) ?: 0f
                        val starInteraction = remember { MutableInteractionSource() }
                        val isStarFocused by starInteraction.collectIsFocusedAsState()

                        TvFocusCard(
                            onClick = { onPlayChannel(channel) },
                            isSelected = isStarFocused,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .then(if (idx == 0) Modifier.focusRequester(entryFocusRequester) else Modifier)
                        ) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                // Subtle full-card gradient timebar fill on background
                                if (currentProg != null && progress > 0f) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .fillMaxWidth(fraction = progress)
                                            .background(
                                                Brush.horizontalGradient(
                                                    colors = listOf(
                                                        AccentCyan.copy(alpha = 0.06f),
                                                        AccentOrange.copy(alpha = 0.16f)
                                                    )
                                                )
                                            )
                                    )
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(30.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(DarkSurface.copy(alpha = 0.85f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.LiveTv,
                                                contentDescription = null,
                                                tint = AccentCyan.copy(alpha = 0.65f),
                                                modifier = Modifier.size(16.dp)
                                            )
                                            if (!channel.logoUrl.isNullOrBlank()) {
                                                AsyncImage(
                                                    model = channel.logoUrl,
                                                    contentDescription = channel.name,
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .padding(2.dp),
                                                    contentScale = ContentScale.Fit
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.width(10.dp))

                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = channel.name,
                                                    style = MaterialTheme.typography.bodyMedium.copy(
                                                        fontSize = 13.5.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        lineHeight = 15.sp
                                                    ),
                                                    color = TextPrimary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f, fill = false)
                                                )
                                                if (channel.hasArchive) {
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Box(
                                                        modifier = Modifier
                                                            .clip(RoundedCornerShape(4.dp))
                                                            .background(AccentOrange.copy(alpha = 0.08f))
                                                            .border(0.75.dp, AccentOrange.copy(alpha = 0.50f), RoundedCornerShape(4.dp))
                                                            .padding(horizontal = 5.dp, vertical = 1.dp)
                                                    ) {
                                                        Text(
                                                            text = if (channel.catchupDays > 0) "↺ ${channel.catchupDays}д" else "↺",
                                                            fontSize = 9.sp,
                                                            fontWeight = FontWeight.Medium,
                                                            color = AccentOrange
                                                        )
                                                    }
                                                }
                                            }
                                            Text(
                                                text = currentProg?.title ?: "Прямой эфир",
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontSize = 11.sp,
                                                    lineHeight = 13.sp
                                                ),
                                                color = if (currentProg != null) TextSecondary else TextSecondary.copy(alpha = 0.65f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }

                                    // Compact TV-Focusable Favorite Toggle Button with explicit FocusBorder
                                    val starScale by animateFloatAsState(if (isStarFocused) 1.12f else 1.0f, label = "star_scale")
                                    val starBg by animateColorAsState(
                                        targetValue = when {
                                            isStarFocused && isFav -> AccentGold.copy(alpha = 0.24f)
                                            isStarFocused -> AccentCyan.copy(alpha = 0.22f)
                                            isFav -> AccentGold.copy(alpha = 0.12f)
                                            else -> Color.Transparent
                                        },
                                        label = "star_bg"
                                    )
                                    val starBorder = when {
                                        isStarFocused && isFav -> AccentGold
                                        isStarFocused -> FocusBorder
                                        isFav -> AccentGold.copy(alpha = 0.45f)
                                        else -> Color.Transparent
                                    }
                                    Box(
                                        modifier = Modifier
                                            .padding(start = 4.dp)
                                            .size(30.dp)
                                            .scale(starScale)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(starBg)
                                            .then(
                                                if (starBorder != Color.Transparent) {
                                                    Modifier.border(if (isStarFocused) 2.dp else 1.dp, starBorder, RoundedCornerShape(8.dp))
                                                } else Modifier
                                            )
                                            .clickable(
                                                interactionSource = starInteraction,
                                                indication = null
                                            ) {
                                                iptvManager.toggleFavorite(channel.id)
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = if (isFav) Icons.Rounded.Star else Icons.Outlined.StarOutline,
                                            contentDescription = if (isFav) "В избранном" else "Добавить в избранное",
                                            tint = when {
                                                isFav -> AccentGold
                                                isStarFocused -> AccentCyanBright
                                                else -> TextSecondary
                                            },
                                            modifier = Modifier.size(16.dp)
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

    if (showLocalFileDialog) {
        Dialog(onDismissRequest = { showLocalFileDialog = false }) {
            Box(
                modifier = Modifier
                    .width(520.dp)
                    .height(440.dp)
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
                Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Файлы плейлистов (.m3u / .m3u8)",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary
                            )
                            Text(
                                text = "Найдено на флешке и в памяти: ${localFiles.size}",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                        TvFocusCard(
                            onClick = { showLocalFileDialog = false },
                            modifier = Modifier.width(110.dp).height(40.dp)
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Закрыть", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    if (localFiles.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "Файлы .m3u / .m3u8 не найдены на подключенных накопителях.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(localFiles, key = { it.absolutePath }) { file ->
                                TvFocusCard(
                                    onClick = {
                                        showLocalFileDialog = false
                                        scope.launch { iptvManager.loadFromLocalFile(file) }
                                    },
                                    modifier = Modifier.fillMaxWidth().height(64.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            modifier = Modifier.weight(1f),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Outlined.FolderOpen, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(24.dp))
                                            Spacer(Modifier.width(12.dp))
                                            Column {
                                                Text(
                                                    text = file.name,
                                                    style = MaterialTheme.typography.titleMedium,
                                                    color = TextPrimary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = file.parent ?: "",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = TextSecondary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
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
