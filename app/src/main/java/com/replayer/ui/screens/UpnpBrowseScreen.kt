package com.replayer.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import com.replayer.data.FavoritesManager
import com.replayer.data.ServerConfigManager
import com.replayer.model.FavoriteFolder
import com.replayer.model.MediaItem
import com.replayer.model.MediaSourceType
import com.replayer.model.MediaType
import com.replayer.model.SortMode
import com.replayer.model.ViewMode
import com.replayer.network.upnp.ContentDirectoryService
import com.replayer.ui.components.TvFocusCard
import com.replayer.ui.theme.AccentCyan
import com.replayer.ui.theme.DarkCard
import com.replayer.ui.theme.DarkSurface
import com.replayer.ui.theme.TextPrimary
import com.replayer.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun UpnpBrowseScreen(
    onPlayMedia: (MediaItem) -> Unit,
    onNavigateToSettings: () -> Unit,
    onBackToHome: () -> Unit,
    modifier: Modifier = Modifier,
    initialFolder: Pair<String, String>? = null,
    lastPlayedMediaId: String? = null,
    isPlayerActive: Boolean = false,
    onFolderLoaded: () -> Unit = {},
    onConsumeLastPlayedMediaId: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val serverConfigManager = remember { ServerConfigManager(context) }
    val favoritesManager = remember { FavoritesManager(context) }
    val contentDirectory = remember { ContentDirectoryService() }

    val connectedDevice by serverConfigManager.connectedUpnpDevice.collectAsState()
    val favorites by favoritesManager.favorites.collectAsState()

    var currentItems by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var folderStack by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var isLoadingFolder by remember { mutableStateOf(false) }

    // User preferences: Default view is LIST
    var viewMode by remember { mutableStateOf(ViewMode.LIST) }
    var sortMode by remember { mutableStateOf(SortMode.BY_NAME) }

    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()

    val firstItemFocusRequester = remember { FocusRequester() }
    val targetItemFocusRequester = remember { FocusRequester() }
    var lastExitTimestamp by remember { mutableLongStateOf(0L) }

    fun loadFolder(controlUrl: String, folderId: String) {
        isLoadingFolder = true
        scope.launch {
            currentItems = contentDirectory.browse(controlUrl, folderId)
            isLoadingFolder = false
        }
    }

    fun openFolder(controlUrl: String, folderId: String, title: String) {
        onConsumeLastPlayedMediaId()
        folderStack = folderStack + Pair(folderId, title)
        loadFolder(controlUrl, folderId)
    }

    // Initialize or switch folder when connectedDevice or initialFolder changes
    LaunchedEffect(connectedDevice, initialFolder) {
        if (connectedDevice != null) {
            val controlUrl = connectedDevice!!.contentDirectoryControlUrl ?: return@LaunchedEffect
            if (initialFolder != null) {
                if (folderStack.lastOrNull()?.first != initialFolder.first) {
                    folderStack = listOf(Pair("0", connectedDevice!!.friendlyName), initialFolder)
                    loadFolder(controlUrl, initialFolder.first)
                    onFolderLoaded()
                }
            } else if (folderStack.isEmpty()) {
                folderStack = listOf(Pair("0", connectedDevice!!.friendlyName))
                loadFolder(controlUrl, "0")
            }
        }
    }

    fun navigateBack() {
        onConsumeLastPlayedMediaId()
        if (folderStack.size > 1) {
            val parentIdx = folderStack.size - 2
            val parentFolder = folderStack[parentIdx]
            folderStack = folderStack.take(parentIdx + 1)
            connectedDevice?.contentDirectoryControlUrl?.let { url ->
                loadFolder(url, parentFolder.first)
            }
        } else {
            onBackToHome()
        }
    }

    BackHandler(enabled = !isPlayerActive) {
        // Prevent accidental multiple back taps within 400ms after closing player from popping folders
        if (System.currentTimeMillis() - lastExitTimestamp < 400) {
            return@BackHandler
        }
        navigateBack()
    }

    // Sorting
    val sortedItems = remember(currentItems, sortMode) {
        when (sortMode) {
            SortMode.BY_NAME -> {
                currentItems.sortedWith(
                    compareBy<MediaItem> { it.type != MediaType.DIRECTORY }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
                )
            }
            SortMode.BY_DATE -> {
                currentItems.sortedWith(
                    compareBy<MediaItem> { it.type != MediaType.DIRECTORY }
                        .thenByDescending { it.dateAdded ?: "" }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
                )
            }
        }
    }

    if (connectedDevice == null) {
        // Not connected state
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(DarkCard),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        tint = AccentCyan,
                        modifier = Modifier.size(42.dp)
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = "Подключите свой сервер DLNA/UPnP в настройках",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Выберите DLNA сервер (например Keenetic Giga или медиасервер ПК) в меню настроек.",
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
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "Перейти в настройки", color = AccentCyan, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 24.dp)
    ) {
        // Header with Breadcrumb and Controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = connectedDevice!!.friendlyName,
                    style = MaterialTheme.typography.displayLarge
                )
                val breadcrumb = folderStack.joinToString(" / ") { it.second }
                Text(
                    text = breadcrumb.ifEmpty { connectedDevice!!.friendlyName },
                    style = MaterialTheme.typography.bodyLarge,
                    color = AccentCyan,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // View Switcher (List / Grid)
                TvFocusCard(
                    onClick = {
                        viewMode = if (viewMode == ViewMode.LIST) ViewMode.GRID else ViewMode.LIST
                    },
                    modifier = Modifier
                        .width(160.dp)
                        .height(48.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (viewMode == ViewMode.LIST) Icons.Default.ViewList else Icons.Default.GridView,
                            contentDescription = null,
                            tint = AccentCyan,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (viewMode == ViewMode.LIST) "Вид: Список" else "Вид: Плитка",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary
                        )
                    }
                }

                // Sort Switcher (Name / Date)
                TvFocusCard(
                    onClick = {
                        sortMode = if (sortMode == SortMode.BY_NAME) SortMode.BY_DATE else SortMode.BY_NAME
                    },
                    modifier = Modifier
                        .width(170.dp)
                        .height(48.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Sort,
                            contentDescription = null,
                            tint = AccentCyan,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (sortMode == SortMode.BY_NAME) "Сорт: По имени" else "Сорт: По дате",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Box(modifier = Modifier.fillMaxSize()) {
            if (sortedItems.isEmpty() && !isLoadingFolder) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Папка пуста",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextSecondary
                    )
                }
            } else if (viewMode == ViewMode.LIST) {
                // Detailed List View
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                items(sortedItems.size, key = { sortedItems[it].id }) { index ->
                    val item = sortedItems[index]
                    val isFirst = index == 0
                    val isTarget = item.id == lastPlayedMediaId
                    val isFav = favorites.any { it.id == item.id }

                    val itemFocusModifier = when {
                        isTarget -> Modifier.focusRequester(targetItemFocusRequester)
                        isFirst -> Modifier.focusRequester(firstItemFocusRequester)
                        else -> Modifier
                    }

                    TvFocusCard(
                        onClick = {
                            if (item.type == MediaType.DIRECTORY) {
                                connectedDevice?.contentDirectoryControlUrl?.let { url ->
                                    openFolder(url, item.id, item.title)
                                }
                            } else {
                                onPlayMedia(item)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(78.dp)
                            .then(itemFocusModifier)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 20.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val icon = when (item.type) {
                                MediaType.DIRECTORY -> Icons.Default.Folder
                                MediaType.VIDEO -> Icons.Default.VideoFile
                                MediaType.AUDIO -> Icons.Default.AudioFile
                                MediaType.IMAGE -> Icons.Default.Image
                                MediaType.UNKNOWN -> Icons.Default.VideoFile
                            }
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(AccentCyan.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    tint = AccentCyan,
                                    modifier = Modifier.size(24.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(16.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = TextPrimary
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    // Format Badge
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(AccentCyan.copy(alpha = 0.2f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = item.fileFormat(),
                                            color = AccentCyan,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    val sizeStr = item.formattedSize()
                                    if (sizeStr.isNotEmpty()) {
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(
                                            text = sizeStr,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextSecondary
                                        )
                                    }

                                    val dateStr = item.formattedDate()
                                    if (dateStr.isNotEmpty()) {
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(
                                            text = dateStr,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextSecondary
                                        )
                                    }
                                }
                            }

                            // Favorite Heart button for folders
                            if (item.type == MediaType.DIRECTORY) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isFav) Color(0xFFFF4081).copy(alpha = 0.15f) else Color.Transparent)
                                        .clickable {
                                            favoritesManager.toggleFavorite(
                                                FavoriteFolder(
                                                    id = item.id,
                                                    title = item.title,
                                                    sourceType = MediaSourceType.UPNP_DLNA,
                                                    serverControlUrl = connectedDevice?.contentDirectoryControlUrl,
                                                    parentId = item.parentId
                                                )
                                            )
                                        },
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
        } else {
            // Grid View
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(minSize = 220.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(sortedItems.size, key = { sortedItems[it].id }) { index ->
                    val item = sortedItems[index]
                    val isFirst = index == 0
                    val isTarget = item.id == lastPlayedMediaId
                    val isFav = favorites.any { it.id == item.id }

                    val itemFocusModifier = when {
                        isTarget -> Modifier.focusRequester(targetItemFocusRequester)
                        isFirst -> Modifier.focusRequester(firstItemFocusRequester)
                        else -> Modifier
                    }

                    TvFocusCard(
                        onClick = {
                            if (item.type == MediaType.DIRECTORY) {
                                connectedDevice?.contentDirectoryControlUrl?.let { url ->
                                    openFolder(url, item.id, item.title)
                                }
                            } else {
                                onPlayMedia(item)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(130.dp)
                            .then(itemFocusModifier)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.Center
                        ) {
                            val icon = when (item.type) {
                                MediaType.DIRECTORY -> Icons.Default.Folder
                                MediaType.VIDEO -> Icons.Default.VideoFile
                                MediaType.AUDIO -> Icons.Default.AudioFile
                                MediaType.IMAGE -> Icons.Default.Image
                                MediaType.UNKNOWN -> Icons.Default.VideoFile
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(icon, contentDescription = null, tint = AccentCyan)

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (item.type == MediaType.DIRECTORY) {
                                        Icon(
                                            imageVector = if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                            contentDescription = null,
                                            tint = if (isFav) Color(0xFFFF4081) else TextSecondary,
                                            modifier = Modifier
                                                .size(20.dp)
                                                .clickable {
                                                    favoritesManager.toggleFavorite(
                                                        FavoriteFolder(
                                                            id = item.id,
                                                            title = item.title,
                                                            sourceType = MediaSourceType.UPNP_DLNA,
                                                            serverControlUrl = connectedDevice?.contentDirectoryControlUrl,
                                                            parentId = item.parentId
                                                        )
                                                    )
                                                }
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                    }

                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(AccentCyan.copy(alpha = 0.2f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = item.fileFormat(),
                                            color = AccentCyan,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val sizeStr = item.formattedSize()
                            if (sizeStr.isNotEmpty()) {
                                Text(
                                    text = sizeStr,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                        }
                    }
                }
                }
            }

            if (isLoadingFolder) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = AccentCyan)
                }
            }
        }
    }

    // 1. Returning from player: focus the played video item and keep focus on it!
    LaunchedEffect(isPlayerActive) {
        if (!isPlayerActive && lastPlayedMediaId != null) {
            val targetIndex = sortedItems.indexOfFirst { it.id == lastPlayedMediaId }
            if (targetIndex >= 0) {
                delay(60)
                try {
                    if (viewMode == ViewMode.LIST) {
                        listState.scrollToItem(maxOf(0, targetIndex))
                    } else {
                        gridState.scrollToItem(maxOf(0, targetIndex))
                    }
                } catch (_: Exception) {}
                delay(80)
                try {
                    targetItemFocusRequester.requestFocus()
                } catch (_: Exception) {}
            }
        }
    }

    // 2. Folder navigation (entering folder or pressing Back): focus first item
    LaunchedEffect(folderStack) {
        if (folderStack.isNotEmpty() && !isPlayerActive) {
            delay(60)
            try {
                if (viewMode == ViewMode.LIST) {
                    listState.scrollToItem(0)
                } else {
                    gridState.scrollToItem(0)
                }
                firstItemFocusRequester.requestFocus()
            } catch (_: Exception) {}
        }
    }

    // 3. Initial folder items loaded
    LaunchedEffect(currentItems) {
        if (!isPlayerActive && lastPlayedMediaId == null && currentItems.isNotEmpty()) {
            delay(50)
            try {
                firstItemFocusRequester.requestFocus()
            } catch (_: Exception) {}
        }
    }
}
