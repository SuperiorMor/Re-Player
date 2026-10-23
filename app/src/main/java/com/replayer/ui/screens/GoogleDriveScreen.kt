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
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
import com.replayer.ui.theme.DarkCard
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
    var authError by remember { mutableStateOf<String?>(null) }
    var isRequestingAuth by remember { mutableStateOf(false) }

    var currentItems by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var folderStack by remember { mutableStateOf<List<Pair<String, String>>>(listOf("root" to "Мой диск")) }
    var isLoading by remember { mutableStateOf(false) }

    // User preferences: Default view is LIST
    var viewMode by remember { mutableStateOf(ViewMode.LIST) }
    var sortMode by remember { mutableStateOf(SortMode.BY_NAME) }

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
                    text = if (isAuthenticated) breadcrumb else "Авторизация облачного хранилища",
                    style = MaterialTheme.typography.bodyLarge,
                    color = AccentCyan,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (isAuthenticated) {
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

        if (!isAuthenticated) {
            // TV Device Auth Flow
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (deviceCodeInfo == null) {
                    Column(
                        modifier = Modifier
                            .width(540.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(DarkCard)
                            .padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.CloudQueue,
                            contentDescription = null,
                            tint = AccentCyan,
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Подключение Google Drive к Re:Player",
                            style = MaterialTheme.typography.titleLarge,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Для безопасного доступа к файлам Google Drive на ТВ используется стандартный вход через QR-код или короткий код на телефоне / ПК.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )

                        if (authError != null) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = authError!!,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFFFF5252)
                            )
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        TvFocusCard(
                            onClick = {
                                isRequestingAuth = true
                                authError = null
                                scope.launch {
                                    val savedId = authManager.getSavedClientId()
                                    val clientIdToUse = if (savedId.isNotBlank()) savedId else "YOUR_TV_CLIENT_ID"
                                    val result = authManager.requestDeviceCode(clientIdToUse)
                                    isRequestingAuth = false
                                    result.onSuccess { info ->
                                        deviceCodeInfo = info
                                    }.onFailure { err ->
                                        authError = err.localizedMessage ?: "Ошибка подключения к Google OAuth"
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                        ) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                if (isRequestingAuth) {
                                    CircularProgressIndicator(color = AccentCyan, modifier = Modifier.size(24.dp))
                                } else {
                                    Text(
                                        text = "Получить код для авторизации",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = TextPrimary
                                    )
                                }
                            }
                        }
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
            // Drive Files in List or Grid mode
            if (viewMode == ViewMode.LIST) {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(sortedItems) { item ->
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
                                    folderStack = folderStack + (item.id to item.title)
                                    loadFolder(item.id)
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
                                    MediaType.UNKNOWN -> Icons.Default.VideoFile
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
