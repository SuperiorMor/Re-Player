package com.replayer.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.replayer.model.MediaItem
import com.replayer.model.MediaType
import com.replayer.model.UpnpDevice
import com.replayer.network.upnp.ContentDirectoryService
import com.replayer.network.upnp.SsdpDiscovery
import com.replayer.ui.components.TvFocusCard
import com.replayer.ui.theme.AccentCyan
import com.replayer.ui.theme.TextPrimary
import com.replayer.ui.theme.TextSecondary
import kotlinx.coroutines.launch

enum class ViewMode {
    LIST, GRID
}

enum class SortMode {
    BY_NAME, BY_DATE
}

@Composable
fun UpnpBrowseScreen(
    onPlayMedia: (MediaItem) -> Unit,
    onBackToHome: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val discovery = remember { SsdpDiscovery(context) }
    val contentDirectory = remember { ContentDirectoryService() }

    var devices by remember { mutableStateOf<List<UpnpDevice>>(emptyList()) }
    var selectedDevice by remember { mutableStateOf<UpnpDevice?>(null) }
    var currentItems by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var folderStack by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) } // Pair(id, title)
    var isScanning by remember { mutableStateOf(false) }
    var isLoadingFolder by remember { mutableStateOf(false) }

    // User preferences: Default view is LIST
    var viewMode by remember { mutableStateOf(ViewMode.LIST) }
    var sortMode by remember { mutableStateOf(SortMode.BY_NAME) }

    fun scanDevices() {
        isScanning = true
        scope.launch {
            discovery.discoverDevices().collect { list ->
                devices = list
                isScanning = false
            }
        }
    }

    LaunchedEffect(Unit) {
        scanDevices()
    }

    fun openFolder(controlUrl: String, folderId: String, title: String) {
        isLoadingFolder = true
        scope.launch {
            val items = contentDirectory.browse(controlUrl, folderId)
            currentItems = items
            folderStack = folderStack + Pair(folderId, title)
            isLoadingFolder = false
        }
    }

    fun navigateBack() {
        if (folderStack.size > 1) {
            val parentIdx = folderStack.size - 2
            val parentFolder = folderStack[parentIdx]
            val newStack = folderStack.take(parentIdx + 1)
            selectedDevice?.contentDirectoryControlUrl?.let { url ->
                isLoadingFolder = true
                scope.launch {
                    val items = contentDirectory.browse(url, parentFolder.first)
                    currentItems = items
                    folderStack = newStack
                    isLoadingFolder = false
                }
            }
        } else if (selectedDevice != null) {
            selectedDevice = null
            folderStack = emptyList()
            currentItems = emptyList()
        } else {
            onBackToHome()
        }
    }

    BackHandler(enabled = true) {
        navigateBack()
    }

    // Sorted items list
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
            Column(modifier = Modifier.weight(1f)) {
                if (selectedDevice == null) {
                    Text(
                        text = "UPnP / DLNA Серверы",
                        style = MaterialTheme.typography.displayLarge
                    )
                    Text(
                        text = if (isScanning) "Сканирование локальной сети..." else "Найдено устройств: ${devices.size}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = AccentCyan
                    )
                } else {
                    Text(
                        text = selectedDevice?.friendlyName ?: "DLNA Сервер",
                        style = MaterialTheme.typography.displayLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val breadcrumbs = folderStack.joinToString(" / ") { it.second }
                    Text(
                        text = if (breadcrumbs.isNotEmpty()) breadcrumbs else "Корневой каталог",
                        style = MaterialTheme.typography.bodyLarge,
                        color = AccentCyan,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Controls for View Mode and Sort Mode
            if (selectedDevice != null) {
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
        }

        Spacer(modifier = Modifier.height(20.dp))

        if (isLoadingFolder) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = AccentCyan)
            }
        } else if (selectedDevice == null) {
            // Discovered DLNA Servers List
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 240.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(devices) { device ->
                    TvFocusCard(
                        onClick = {
                            selectedDevice = device
                            device.contentDirectoryControlUrl?.let { url ->
                                openFolder(url, "0", device.friendlyName)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(130.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.Dns, contentDescription = null, tint = AccentCyan)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = device.friendlyName,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = device.manufacturer,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        } else {
            // Folder Contents: List View (Default) or Grid View
            if (viewMode == ViewMode.LIST) {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(sortedItems) { item ->
                        TvFocusCard(
                            onClick = {
                                if (item.type == MediaType.DIRECTORY) {
                                    selectedDevice?.contentDirectoryControlUrl?.let { url ->
                                        openFolder(url, item.id, item.title)
                                    }
                                } else {
                                    onPlayMedia(item)
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(78.dp)
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
                            }
                        }
                    }
                }
            } else {
                // Grid View
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 220.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(sortedItems) { item ->
                        TvFocusCard(
                            onClick = {
                                if (item.type == MediaType.DIRECTORY) {
                                    selectedDevice?.contentDirectoryControlUrl?.let { url ->
                                        openFolder(url, item.id, item.title)
                                    }
                                } else {
                                    onPlayMedia(item)
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(130.dp)
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
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(icon, contentDescription = null, tint = AccentCyan)
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(AccentCyan.copy(alpha = 0.2f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = item.fileFormat(),
                                            color = AccentCyan,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val sizeStr = item.formattedSize()
                                if (sizeStr.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = sizeStr,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary,
                                        maxLines = 1
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
