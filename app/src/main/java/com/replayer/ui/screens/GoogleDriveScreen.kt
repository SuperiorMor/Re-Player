package com.replayer.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
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
import androidx.compose.ui.unit.sp
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

@Composable
fun GoogleDriveScreen(
    onPlayMedia: (MediaItem) -> Unit,
    onBackToHome: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val authManager = remember { GoogleDriveAuthManager(context) }
    val driveService = remember { GoogleDriveService(authManager) }

    var isAuthenticated by remember { mutableStateOf(authManager.getSavedAccessToken() != null) }
    var deviceCodeInfo by remember { mutableStateOf<DeviceCodeResponse?>(null) }
    var currentItems by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var folderStack by remember { mutableStateOf<List<Pair<String, String>>>(listOf("root" to "Мой диск")) }
    var isLoading by remember { mutableStateOf(false) }

    fun loadFolder(folderId: String) {
        isLoading = true
        scope.launch {
            currentItems = driveService.listFolder(folderId)
            isLoading = false
        }
    }

    LaunchedEffect(isAuthenticated) {
        if (isAuthenticated) {
            loadFolder("root")
        }
    }

    fun navigateBack() {
        if (folderStack.size > 1) {
            val parentIdx = folderStack.size - 2
            val parentFolder = folderStack[parentIdx]
            folderStack = folderStack.take(parentIdx + 1)
            loadFolder(parentFolder.first)
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Google Drive",
                    style = MaterialTheme.typography.displayLarge
                )
                val breadcrumb = folderStack.joinToString(" / ") { it.second }
                Text(
                    text = if (isAuthenticated) breadcrumb else "Авторизация устройства",
                    style = MaterialTheme.typography.bodyLarge,
                    color = AccentCyan,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (isAuthenticated && folderStack.size > 1) {
                TvFocusCard(
                    onClick = { navigateBack() },
                    modifier = Modifier
                        .width(150.dp)
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
                            text = "Назад",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                    }
                }
            }
        }

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
                        style = MaterialTheme.typography.displayMedium,
                        color = AccentCyan
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "И введите код подтверждения:",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = deviceCodeInfo?.user_code ?: "XXXX-XXXX",
                        style = MaterialTheme.typography.displayLarge.copy(fontSize = 48.sp),
                        color = TextPrimary
                    )
                }
            }
        } else if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = AccentCyan)
            }
        } else {
            // Drive Files Grid
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 220.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (folderStack.size > 1) {
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
                                    text = ".. На уровень выше",
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                items(currentItems) { item ->
                    TvFocusCard(
                        onClick = {
                            if (item.type == MediaType.DIRECTORY) {
                                folderStack = folderStack + (item.id to item.title)
                                loadFolder(item.id)
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
