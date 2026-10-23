package com.replayer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VideoFile
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
        scope.launch {
            val items = contentDirectory.browse(controlUrl, folderId)
            currentItems = items
            folderStack = folderStack + Pair(folderId, title)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "UPnP / DLNA Серверы",
                    style = MaterialTheme.typography.displayLarge
                )
                Text(
                    text = if (isScanning) "Сканирование Wi-Fi сети..." else "Найдено устройств: ${devices.size}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = AccentCyan
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (selectedDevice == null) {
            // List discovered devices
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
                            Icon(Icons.Default.Folder, contentDescription = null, tint = AccentCyan)
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
            // Inside selected server
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 220.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
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
