package com.replayer.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VideoFile
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp)
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

            if (selectedDevice != null) {
                TvFocusCard(
                    onClick = { navigateBack() },
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
                            Icons.Default.ArrowBack,
                            contentDescription = null,
                            tint = AccentCyan,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (folderStack.size > 1) "Назад" else "К серверам",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

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
                verticalArrangement = Arrangement.spacedBy(16.dp)
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
            // Folder Contents
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 220.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Quick parent navigation card
                item {
                    TvFocusCard(
                        onClick = { navigateBack() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.ArrowBack, contentDescription = null, tint = AccentCyan)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = if (folderStack.size > 1) ".. На уровень выше" else "← К серверам",
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                items(currentItems) { item ->
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
                            .height(120.dp)
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
                                else -> Icons.Default.VideoFile
                            }
                            Icon(icon, contentDescription = null, tint = AccentCyan)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.titleMedium,
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
