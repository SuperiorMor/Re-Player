package com.replayer.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.replayer.data.IptvManager
import com.replayer.data.ServerConfigManager
import com.replayer.model.UpnpDevice
import com.replayer.network.upnp.SsdpDiscovery
import com.replayer.ui.components.TvFocusCard
import com.replayer.ui.theme.*
import com.replayer.updater.ReleaseInfo
import com.replayer.updater.UpdateCheckResult
import com.replayer.updater.UpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private sealed class UpdateUiState {
    object Idle : UpdateUiState()
    object Checking : UpdateUiState()
    object UpToDate : UpdateUiState()
    object NoReleases : UpdateUiState()
    data class Available(val release: ReleaseInfo) : UpdateUiState()
    data class Downloading(val progress: Float) : UpdateUiState()
    data class ReadyToInstall(val apkFile: File) : UpdateUiState()
    data class Error(val message: String) : UpdateUiState()
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val serverConfigManager = remember { ServerConfigManager(context) }
    val discovery = remember { SsdpDiscovery(context) }
    val updateManager = remember { UpdateManager(context) }
    val currentVersion = remember { getCurrentAppVersion(context) }

    val connectedUpnpDevice by serverConfigManager.connectedUpnpDevice.collectAsState()

    // UPnP scanning state
    var discoveredDevices by remember { mutableStateOf<List<UpnpDevice>>(emptyList()) }
    var isScanningUpnp by remember { mutableStateOf(false) }
    var showAvailableServers by remember { mutableStateOf(false) }

    // IPTV state
    val iptvManager = remember { IptvManager(context) }
    var iptvPlaylistInput by remember { mutableStateOf(iptvManager.getSavedPlaylistUrl() ?: "") }
    var iptvEpgInput by remember { mutableStateOf(iptvManager.getSavedEpgUrl() ?: "") }
    val iptvIsLoading by iptvManager.isLoading.collectAsState()
    val iptvPlaylistProgress by iptvManager.playlistProgress.collectAsState()
    val iptvEpgProgress by iptvManager.epgProgress.collectAsState()
    val iptvStatusMessage by iptvManager.statusMessage.collectAsState()

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

    // App updates state
    var updateUiState by remember { mutableStateOf<UpdateUiState>(UpdateUiState.Idle) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var showChangelogDialog by remember { mutableStateOf(false) }
    var changelogRelease by remember { mutableStateOf<ReleaseInfo?>(null) }
    val updateActionFocusRequester = remember { FocusRequester() }

    LaunchedEffect(updateUiState) {
        if (updateUiState is UpdateUiState.Downloading || updateUiState is UpdateUiState.ReadyToInstall) {
            delay(60)
            try {
                updateActionFocusRequester.requestFocus()
            } catch (_: Exception) {}
        }
    }

    BackHandler(enabled = true) {
        onBack()
    }

    // Auto-scan for DLNA on entering settings if none connected
    LaunchedEffect(Unit) {
        if (connectedUpnpDevice == null) {
            isScanningUpnp = true
            discovery.discoverDevices().collect { list ->
                discoveredDevices = list
                isScanningUpnp = false
            }
        }
    }

    fun rescanUpnp() {
        isScanningUpnp = true
        scope.launch {
            discovery.discoverDevices().collect { list ->
                discoveredDevices = list
                isScanningUpnp = false
            }
        }
    }

    fun startCheckUpdates() {
        updateUiState = UpdateUiState.Checking
        scope.launch {
            val result = updateManager.checkForUpdates(currentVersion)
            updateUiState = when (result) {
                is UpdateCheckResult.UpdateAvailable -> UpdateUiState.Available(result.release)
                is UpdateCheckResult.UpToDate -> UpdateUiState.UpToDate
                is UpdateCheckResult.NoReleasesFound -> UpdateUiState.NoReleases
                is UpdateCheckResult.Error -> UpdateUiState.Error(result.message)
            }
        }
    }

    fun startDownload(release: ReleaseInfo) {
        updateUiState = UpdateUiState.Downloading(0f)
        downloadProgress = 0f
        scope.launch {
            val file = updateManager.downloadApk(release.downloadUrl, release.fileName) { progress ->
                downloadProgress = progress
                updateUiState = UpdateUiState.Downloading(progress)
            }
            updateUiState = if (file != null) {
                UpdateUiState.ReadyToInstall(file)
            } else {
                UpdateUiState.Error("Не удалось загрузить файл обновления")
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp, vertical = 24.dp)
    ) {
        Text(
            text = "Настройки",
            style = MaterialTheme.typography.displayLarge
        )
        Text(
            text = "Управление сетевыми серверами DLNA / UPnP и обновлениями",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        // ---------------------------------------------------------
        // 1. Серверы DLNA / UPnP
        // ---------------------------------------------------------
        Text(
            text = "Серверы DLNA / UPnP",
            style = MaterialTheme.typography.titleLarge,
            color = TextPrimary,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        // Connected Device Card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(DarkCard)
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (connectedUpnpDevice != null) AccentCyan.copy(alpha = 0.15f) else DarkSurface),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = null,
                            tint = if (connectedUpnpDevice != null) AccentCyan else TextSecondary
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = connectedUpnpDevice?.friendlyName ?: "Сервер не выбран",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary
                            )
                            if (connectedUpnpDevice != null) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(AccentCyan)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = connectedUpnpDevice?.location ?: "Выберите сервер из списка найденных в локальной сети",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }

                if (connectedUpnpDevice != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TvFocusCard(
                            onClick = {
                                showAvailableServers = !showAvailableServers
                                if (showAvailableServers) rescanUpnp()
                            },
                            modifier = Modifier
                                .width(150.dp)
                                .height(44.dp)
                        ) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = if (showAvailableServers) "Скрыть серверы" else "Сменить сервер",
                                    color = AccentCyan,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }

                        TvFocusCard(
                            onClick = {
                                serverConfigManager.disconnectUpnpDevice()
                                showAvailableServers = true
                            },
                            modifier = Modifier
                                .width(120.dp)
                                .height(44.dp)
                        ) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(text = "Отключить", color = Color(0xFFFF5252), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }

        // Discovered Devices Section (shown when none connected or when toggled via "Сменить сервер")
        if (connectedUpnpDevice == null || showAvailableServers) {
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Доступные медиасерверы в сети",
                style = MaterialTheme.typography.titleMedium,
                color = TextSecondary,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(DarkCard)
                    .padding(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (discoveredDevices.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (isScanningUpnp) "Поиск серверов в локальной сети..." else "Серверы не найдены. Убедитесь, что ПК или NAS включены и находятся в одной сети Wi-Fi.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        }
                    } else {
                        discoveredDevices.forEach { device ->
                            TvFocusCard(
                                onClick = {
                                    serverConfigManager.connectUpnpDevice(device)
                                    showAvailableServers = false
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(72.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 20.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Folder, contentDescription = null, tint = AccentCyan)
                                        Spacer(modifier = Modifier.width(16.dp))
                                        Column {
                                            Text(text = device.friendlyName, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                                            Text(text = device.manufacturer.ifEmpty { device.location }, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                                        }
                                    }
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(AccentCyan.copy(alpha = 0.15f))
                                            .padding(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text(text = "Подключить", color = AccentCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }

                    // Rescan button
                    TvFocusCard(
                        onClick = { rescanUpnp() },
                        modifier = Modifier
                            .width(220.dp)
                            .height(48.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isScanningUpnp) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = AccentCyan, strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(18.dp))
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = if (isScanningUpnp) "Поиск..." else "Обновить список", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        // ---------------------------------------------------------
        // 2. IPTV / Онлайн ТВ
        // ---------------------------------------------------------
        Text(
            text = "IPTV / Онлайн ТВ",
            style = MaterialTheme.typography.titleLarge,
            color = TextPrimary,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(DarkCard)
                .padding(20.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Добавьте ссылку на плейлист каналов (M3U / M3U8) и программу передач EPG (XMLTV).",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Text(
                        text = "✓ Сохраняется автоматически",
                        style = MaterialTheme.typography.labelSmall,
                        color = AccentCyan
                    )
                }

                OutlinedTextField(
                    value = iptvPlaylistInput,
                    onValueChange = {
                        iptvPlaylistInput = it
                        iptvManager.saveUrlsOnly(it, iptvEpgInput)
                    },
                    label = { Text("URL плейлиста (.m3u / .m3u8)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = AccentCyan,
                        unfocusedBorderColor = DarkSurface,
                        focusedLabelColor = AccentCyan,
                        unfocusedLabelColor = TextSecondary,
                        cursorColor = AccentCyan
                    )
                )

                OutlinedTextField(
                    value = iptvEpgInput,
                    onValueChange = {
                        iptvEpgInput = it
                        iptvManager.saveUrlsOnly(iptvPlaylistInput, it)
                    },
                    label = { Text("URL программы передач (.xml / .xml.gz, необязательно)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = AccentCyan,
                        unfocusedBorderColor = DarkSurface,
                        focusedLabelColor = AccentCyan,
                        unfocusedLabelColor = TextSecondary,
                        cursorColor = AccentCyan
                    )
                )

                // Live download progress bar (Requirement 1)
                if (iptvPlaylistProgress != null || iptvEpgProgress != null || iptvIsLoading) {
                    val progVal = iptvEpgProgress ?: iptvPlaylistProgress ?: 0f
                    Column(modifier = Modifier.fillMaxWidth()) {
                        LinearProgressIndicator(
                            progress = { progVal },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = AccentCyan,
                            trackColor = DarkSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = iptvStatusMessage ?: "Загрузка...",
                            style = MaterialTheme.typography.bodySmall,
                            color = AccentCyan
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TvFocusCard(
                        onClick = {
                            iptvManager.saveConfigAndLoad(iptvPlaylistInput, iptvEpgInput)
                        },
                        modifier = Modifier
                            .width(220.dp)
                            .height(48.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (iptvIsLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = AccentCyan,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Загрузка...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AccentCyan
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.CloudDownload,
                                    contentDescription = null,
                                    tint = AccentCyan,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Загрузить по ссылке",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = AccentCyan
                                )
                            }
                        }
                    }

                    // Local USB / storage file picker button (Requirement 2)
                    TvFocusCard(
                        onClick = { scanLocalFiles() },
                        modifier = Modifier
                            .width(240.dp)
                            .height(48.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = null,
                                tint = AccentCyan,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isScanningLocalFiles) "Поиск файлов..." else "Выбрать файл с флешки",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimary
                            )
                        }
                    }

                    if (!iptvStatusMessage.isNullOrBlank() && iptvPlaylistProgress == null && iptvEpgProgress == null) {
                        Text(
                            text = iptvStatusMessage ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (iptvStatusMessage?.contains("Ошибка", ignoreCase = true) == true) {
                                Color(0xFFFF5252)
                            } else {
                                AccentCyan
                            },
                            modifier = Modifier.padding(start = 12.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        // ---------------------------------------------------------
        // 3. Обновление приложения
        // ---------------------------------------------------------
        Text(
            text = "Обновление приложения",
            style = MaterialTheme.typography.titleLarge,
            color = TextPrimary,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(DarkCard)
                .padding(horizontal = 20.dp, vertical = 18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.SystemUpdate,
                        contentDescription = null,
                        tint = AccentCyan,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = "Re:Player", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(AccentCyan.copy(alpha = 0.2f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "v$currentVersion",
                                    color = AccentCyan,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = when (val state = updateUiState) {
                                is UpdateUiState.Checking -> "Проверка обновлений на GitHub..."
                                is UpdateUiState.UpToDate -> "У вас установлена актуальная версия"
                                is UpdateUiState.NoReleases -> "Релизы на GitHub не найдены"
                                is UpdateUiState.Available -> "Доступна новая версия: ${state.release.tagName}"
                                is UpdateUiState.Downloading -> "Загрузка обновления: ${(state.progress * 100).toInt()}%"
                                is UpdateUiState.ReadyToInstall -> "Загрузка завершена! Нажмите «Установить»."
                                is UpdateUiState.Error -> state.message
                                else -> "Проверка новых версий на GitHub и установка в один клик"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = when (updateUiState) {
                                is UpdateUiState.Available -> AccentCyan
                                is UpdateUiState.Error -> Color(0xFFFF5252)
                                else -> TextSecondary
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                when (val state = updateUiState) {
                    is UpdateUiState.Checking -> {
                        TvFocusCard(
                            onClick = {},
                            modifier = Modifier
                                .width(170.dp)
                                .height(44.dp)
                        ) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = AccentCyan,
                                    strokeWidth = 2.dp
                                )
                            }
                        }
                    }

                    is UpdateUiState.Available -> {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TvFocusCard(
                                onClick = {
                                    changelogRelease = state.release
                                    showChangelogDialog = true
                                },
                                modifier = Modifier
                                    .width(150.dp)
                                    .height(44.dp)
                            ) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "Что нового",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = TextSecondary
                                    )
                                }
                            }

                            TvFocusCard(
                                onClick = { startDownload(state.release) },
                                modifier = Modifier
                                    .width(150.dp)
                                    .height(44.dp)
                                    .focusRequester(updateActionFocusRequester)
                            ) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "Загрузить",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = AccentCyan,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    is UpdateUiState.Downloading -> {
                        Column(
                            modifier = Modifier.width(170.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            LinearProgressIndicator(
                                progress = { downloadProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = AccentCyan,
                                trackColor = DarkSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${(downloadProgress * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = AccentCyan
                            )
                        }
                    }

                    is UpdateUiState.ReadyToInstall -> {
                        TvFocusCard(
                            onClick = { updateManager.installApk(state.apkFile) },
                            modifier = Modifier
                                .width(170.dp)
                                .height(44.dp)
                                .focusRequester(updateActionFocusRequester)
                        ) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = "Установить",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AccentCyan,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    else -> {
                        TvFocusCard(
                            onClick = { startCheckUpdates() },
                            modifier = Modifier
                                .width(170.dp)
                                .height(44.dp)
                        ) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = "Проверить сейчас",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextPrimary
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------
    // Changelog Dialog
    // ---------------------------------------------------------
    if (showChangelogDialog && changelogRelease != null) {
        val release = changelogRelease!!
        Dialog(onDismissRequest = { showChangelogDialog = false }) {
            Box(
                modifier = Modifier
                    .width(520.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(DarkCard)
                    .border(1.dp, AccentCyan.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                    .padding(28.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Что нового в ${release.tagName}",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary
                            )
                            Text(
                                text = "Обновление готово к установке",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(AccentCyan.copy(alpha = 0.15f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = release.tagName,
                                color = AccentCyan,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    val changelogItems = remember(release) {
                        getFriendlyChangelog(release.tagName, release.changelog)
                    }

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        changelogItems.forEach { item ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top
                            ) {
                                Box(
                                    modifier = Modifier
                                        .padding(top = 6.dp)
                                        .size(6.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(AccentCyan)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = item,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextPrimary,
                                    lineHeight = 20.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TvFocusCard(
                            onClick = { showChangelogDialog = false },
                            modifier = Modifier
                                .width(120.dp)
                                .height(44.dp)
                        ) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Закрыть", color = TextSecondary)
                            }
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        TvFocusCard(
                            onClick = {
                                showChangelogDialog = false
                                startDownload(release)
                            },
                            modifier = Modifier
                                .width(140.dp)
                                .height(44.dp)
                        ) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Загрузить", color = AccentCyan, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }

    // Local USB / Storage M3U File Picker Dialog (Requirement 2)
    if (showLocalFileDialog) {
        Dialog(onDismissRequest = { showLocalFileDialog = false }) {
            Box(
                modifier = Modifier
                    .width(520.dp)
                    .height(440.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(DarkCard)
                    .border(1.dp, AccentCyan.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                    .padding(24.dp)
            ) {
                Column {
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
                        androidx.compose.foundation.lazy.LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            androidx.compose.foundation.lazy.items(localFiles) { file ->
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
                                            Icon(Icons.Default.Folder, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(24.dp))
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
                                        Text(
                                            text = "${file.length() / 1024} КБ",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = AccentCyan
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

private fun getFriendlyChangelog(version: String, rawChangelog: String): List<String> {
    if (version.contains("0.8")) {
        return listOf(
            "Наглядный прогресс загрузки телепрограммы и плейлистов с процентами.",
            "Раздел ТВ: категории представлены как папки с сортировкой по времени просмотра и названию.",
            "Плеер ТВ: время начала и конца текущей передачи, live-таймбар и телепрограмма по отдельной кнопке.",
            "Главный экран: добавлен раздел телепрограммы избранных каналов в стиле Google TV.",
            "Поддержка подключения .m3u с флешек без ручного ввода ссылки."
        )
    }

    if (version.contains("0.7")) {
        return listOf(
            "Полноценный IPTV плеер: загрузка M3U / M3U8 плейлистов и программы передач EPG (XMLTV, включая .xml.gz архив).",
            "Главный экран в стиле Android TV: верхняя лента недавнего видео и избранных ТВ-каналов, а также компактные папки DLNA.",
            "Компактные настройки серверов: аккуратная карточка подключенного DLNA с кнопкой смены сервера.",
            "Чистый проводник файлов: скрыты лишние технические плашки форматов для быстрого и наглядного выбора видео."
        )
    }

    if (version.contains("0.6")) {
        return listOf(
            "Глобальная оптимизация и стабильность: проект полностью сфокусирован на молниеносной работе с домашними серверами DLNA / UPnP.",
            "Надёжный LibVLC: устранены вылеты при закрытии плеера и исправлено переключение звуковых дорожек.",
            "Плавный фокус пульта: мгновенное и предсказуемое управление кнопками Android TV без потери курсора."
        )
    }

    if (version.contains("0.5")) {
        return listOf(
            "Полный переход на плеерный движок LibVLC: встроенный нативный FFmpeg гарантирует 100% плавное воспроизведение любых AVI (DivX, Xvid) и идеальную синхронизацию звука.",
            "Аппаратное и программное декодирование: плавная картинка и чистый звук на любых ТВ без задержек и артефактов.",
            "Прямое воспроизведение DLNA: мгновенный старт потокового видео с выбором звуковых дорожек и субтитров."
        )
    }

    if (version.contains("0.4")) {
        return listOf(
            "Точный расчёт времени аудиочанков в AVI: устранена рассинхронизация звука через накопление реального времени сэмплов в ChunkReader.",
            "Автооткрытие папок: мгновенный доступ к видеофайлам."
        )
    }

    val extracted = rawChangelog.lines()
        .map { it.trim() }
        .filter { it.startsWith("-") || it.startsWith("*") || (it.length > 2 && it[0].isDigit() && it[1] == '.') }
        .map { line ->
            line.removePrefix("-").removePrefix("*").replace(Regex("^\\d+\\.\\s*"), "").trim()
        }
        .filter { it.isNotEmpty() && !it.contains("Full Changelog", ignoreCase = true) }
        .take(5)

    if (extracted.size >= 2) {
        return extracted
    }

    return listOf(
        "Повышена стабильность и плавность воспроизведения видео и звука.",
        "Улучшена отзывчивость управления пультом Android TV.",
        "Оптимизирована работа с сетевыми папками и хранилищем."
    )
}

private fun getCurrentAppVersion(context: Context): String {
    return try {
        val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        pInfo.versionName ?: "0.8.0"
    } catch (e: Exception) {
        "0.8.0"
    }
}

private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
