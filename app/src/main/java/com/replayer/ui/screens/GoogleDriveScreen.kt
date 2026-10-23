package com.replayer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
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
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.replayer.model.DeviceCodeResponse
import com.replayer.model.MediaItem
import com.replayer.model.MediaType
import com.replayer.network.gdrive.GoogleDriveAuthManager
import com.replayer.network.gdrive.GoogleDriveService
import com.replayer.ui.components.TvFocusCard
import com.replayer.ui.theme.AccentCyan
import com.replayer.ui.theme.TextPrimary
import com.replayer.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun GoogleDriveScreen(
    onPlayMedia: (MediaItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val authManager = remember { GoogleDriveAuthManager(context) }
    val driveService = remember { GoogleDriveService(authManager) }

    var isAuthenticated by remember { mutableStateOf(authManager.getSavedAccessToken() != null) }
    var deviceCodeInfo by remember { mutableStateOf<DeviceCodeResponse?>(null) }
    var currentItems by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var currentFolderId by remember { mutableStateOf("root") }

    LaunchedEffect(isAuthenticated) {
        if (isAuthenticated) {
            currentItems = driveService.listFolder(currentFolderId)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp)
    ) {
        Text(
            text = "Google Drive",
            style = MaterialTheme.typography.displayLarge
        )

        Spacer(modifier = Modifier.height(24.dp))

        if (!isAuthenticated) {
            // TV Device Auth Flow
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (deviceCodeInfo == null) {
                    TvFocusCard(
                        onClick = {
                            scope.launch {
                                // Demo Client ID - replace with user's Google Cloud Console OAuth 2.0 TV Client ID
                                val info = authManager.requestDeviceCode("YOUR_TV_CLIENT_ID.apps.googleusercontent.com")
                                deviceCodeInfo = info
                            }
                        },
                        modifier = Modifier
                            .height(100.dp)
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "Подключить Google Drive",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                } else {
                    Text(
                        text = "Откройте на телефоне или компьютере:",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = deviceCodeInfo?.verification_url ?: "google.com/device",
                        color = AccentCyan,
                        fontSize = 32.sp,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                    Text(
                        text = "И введите код:",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = deviceCodeInfo?.user_code ?: "----",
                        color = TextPrimary,
                        fontSize = 48.sp,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                    Text(
                        text = "Ожидание авторизации...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            }
        } else {
            // Files grid
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 220.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(currentItems) { item ->
                    TvFocusCard(
                        onClick = {
                            if (item.type == MediaType.DIRECTORY) {
                                currentFolderId = item.id
                                scope.launch {
                                    currentItems = driveService.listFolder(item.id)
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
