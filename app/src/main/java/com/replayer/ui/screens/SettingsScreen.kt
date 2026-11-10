package com.replayer.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.replayer.data.IptvManager
import com.replayer.data.ServerConfigManager
import com.replayer.model.UpnpDevice
import com.replayer.network.upnp.SsdpDiscovery
import com.replayer.ui.components.DiagnosticsSection
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

private enum class UrlEditTarget {
    PLAYLIST, EPG
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    enterSectionTrigger: Int = 0,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val serverConfigManager = remember { ServerConfigManager(context) }
    val discovery = remember { SsdpDiscovery(context) }
    val updateManager = remember { UpdateManager(context) }
    val currentVersion = remember { getCurrentAppVersion(context) }
    val entryFocusRequester = remember { FocusRequester() }

    LaunchedEffect(enterSectionTrigger) {
        if (enterSectionTrigger > 0) {
            delay(50)
            try {
                entryFocusRequester.requestFocus()
            } catch (_: Exception) {}
        }
    }

    val connectedUpnpDevice by serverConfigManager.connectedUpnpDevice.collectAsState()

    // UPnP scanning state
    var discoveredDevices by remember { mutableStateOf<List<UpnpDevice>>(emptyList()) }
    var isScanningUpnp by remember { mutableStateOf(false) }
    var showAvailableServers by remember { mutableStateOf(false) }

    // IPTV state
    val iptvManager = remember { IptvManager.getInstance(context) }
    var iptvPlaylistInput by remember { mutableStateOf(iptvManager.getSavedPlaylistUrl() ?: "") }
    var iptvEpgInput by remember { mutableStateOf(iptvManager.getSavedEpgUrl() ?: "") }
    var editingUrlTarget by remember { mutableStateOf<UrlEditTarget?>(null) }
    var editingUrlValue by remember { mutableStateOf("") }
    val iptvIsLoading by iptvManager.isLoading.collectAsState()
    val iptvPlaylistProgress by iptvManager.playlistProgress.collectAsState()
    val iptvEpgProgress by iptvManager.epgProgress.collectAsState()
    val iptvStatusMessage by iptvManager.statusMessage.collectAsState()
    val lastEpgUpdateText by iptvManager.lastEpgUpdateText.collectAsState()
    val lastEpgUpdateTime by iptvManager.lastEpgUpdateTime.collectAsState()
    val epgMap by iptvManager.epgMap.collectAsState()

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
        if (updateUiState is UpdateUiState.ReadyToInstall) {
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
            if (file != null) {
                updateUiState = UpdateUiState.ReadyToInstall(file)
                try {
                    updateManager.installApk(file)
                } catch (_: Exception) {}
            } else {
                updateUiState = UpdateUiState.Error("Не удалось загрузить файл обновления")
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 32.dp, end = 32.dp, top = 30.dp, bottom = 24.dp)
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
                            imageVector = Icons.Outlined.FolderOpen,
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
                                .focusRequester(entryFocusRequester)
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
                                Text(text = "Отключить", color = AccentOrange, style = MaterialTheme.typography.bodyMedium)
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
                                        Icon(Icons.Outlined.FolderOpen, contentDescription = null, tint = AccentCyan)
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
                            .then(if (connectedUpnpDevice == null) Modifier.focusRequester(entryFocusRequester) else Modifier)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isScanningUpnp) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = AccentCyan, strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Outlined.Refresh, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(18.dp))
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
                // Row 1: Playlist URL + "Выбрать с флешки"
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TvFocusCard(
                        onClick = {
                            editingUrlValue = iptvPlaylistInput
                            editingUrlTarget = UrlEditTarget.PLAYLIST
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(60.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "URL плейлиста (.m3u / .m3u8)",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = AccentCyan
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = if (iptvPlaylistInput.isNotBlank()) iptvPlaylistInput else "Нажмите для ввода ссылки",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (iptvPlaylistInput.isNotBlank()) TextPrimary else TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Icon(
                                imageVector = Icons.Outlined.Edit,
                                contentDescription = "Редактировать",
                                tint = AccentCyan,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    // Button: "Выбрать с флешки" (shows loading status if downloading playlist)
                    TvFocusCard(
                        onClick = { scanLocalFiles() },
                        modifier = Modifier
                            .width(260.dp)
                            .height(60.dp)
                    ) {
                        if (iptvIsLoading) {
                            val p = (iptvPlaylistProgress ?: 0.5f).coerceIn(0f, 1f)
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(DarkSurface),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(fraction = p)
                                        .background(Brush.horizontalGradient(listOf(AccentCyan, AccentGold, AccentOrange)))
                                )
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = if (p >= 1f) "Загрузка каналов..." else "Загрузка: ${(p * 100).toInt()}%",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                        color = if (p > 0.5f) DarkBackground else TextPrimary
                                    )
                                }
                            }
                        } else {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.FolderOpen,
                                    contentDescription = null,
                                    tint = AccentCyan,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = if (isScanningLocalFiles) "Поиск файлов..." else "Выбрать с флешки",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                        color = TextPrimary
                                    )
                                    Text(
                                        text = "Файл .m3u / .m3u8",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary
                                    )
                                }
                            }
                        }
                    }
                }

                // Row 2: EPG URL + "Обновлено: X ч. назад" (Click = force refresh, progress like "Проверить обновления")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TvFocusCard(
                        onClick = {
                            editingUrlValue = iptvEpgInput
                            editingUrlTarget = UrlEditTarget.EPG
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(60.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "URL программы передач (.xml / .xml.gz)",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = AccentCyan
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = if (iptvEpgInput.isNotBlank()) iptvEpgInput else "Нажмите для ввода ссылки",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (iptvEpgInput.isNotBlank()) TextPrimary else TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Icon(
                                imageVector = Icons.Outlined.Edit,
                                contentDescription = "Редактировать",
                                tint = AccentCyan,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    // Button: "Обновлено: X ч. назад"
                    TvFocusCard(
                        onClick = {
                            if (iptvEpgInput.isNotBlank()) {
                                iptvManager.loadEpgOnly(iptvEpgInput)
                            } else if (iptvPlaylistInput.isNotBlank()) {
                                scope.launch { iptvManager.refreshPlaylist() }
                            }
                        },
                        modifier = Modifier
                            .width(260.dp)
                            .height(60.dp)
                    ) {
                        if (iptvEpgProgress != null) {
                            val p = (iptvEpgProgress ?: 0f).coerceIn(0f, 1f)
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(DarkSurface),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(fraction = p)
                                        .background(Brush.horizontalGradient(listOf(AccentCyan, AccentGold, AccentOrange)))
                                )
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = if (p >= 1f) "Индексация..." else "EPG: ${(p * 100).toInt()}%",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                        color = if (p > 0.5f) DarkBackground else TextPrimary
                                    )
                                }
                            }
                        } else {
                            val isEpgError = iptvStatusMessage?.let {
                                it.contains("Ошибка", ignoreCase = true) || it.contains("Сбой", ignoreCase = true)
                            } == true
                            val isEpgFresh = epgMap.isNotEmpty() &&
                                lastEpgUpdateTime > 0L &&
                                (System.currentTimeMillis() - lastEpgUpdateTime) < 24 * 3600 * 1000L &&
                                !isEpgError

                            val statusIcon = when {
                                isEpgFresh -> Icons.Outlined.CheckCircle
                                isEpgError -> Icons.Outlined.WarningAmber
                                else -> Icons.Outlined.Refresh
                            }
                            val statusColor = when {
                                isEpgFresh -> StatusSuccess
                                isEpgError -> AccentOrange
                                iptvEpgInput.isBlank() -> TextSecondary
                                else -> AccentCyan
                            }
                            val primaryLabel = when {
                                isEpgFresh -> "Программа обновлена"
                                isEpgError -> "Ошибка EPG"
                                iptvEpgInput.isBlank() -> "Нет ссылки EPG"
                                else -> "Обновить программу"
                            }
                            val secondaryLabel = when {
                                isEpgFresh -> lastEpgUpdateText.ifBlank { "Актуально" }
                                isEpgError -> "Нажмите для повтора"
                                iptvEpgInput.isBlank() -> "Укажите ссылку слева"
                                else -> "Нажмите для загрузки"
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = statusIcon,
                                    contentDescription = null,
                                    tint = statusColor,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = primaryLabel,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                        color = statusColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = secondaryLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }

                // Show bottom status ONLY on errors/warnings to keep settings clean
                val isCriticalMsg = iptvStatusMessage?.let {
                    it.contains("Ошибка", ignoreCase = true) ||
                        it.contains("Сбой", ignoreCase = true) ||
                        it.contains("не найдено", ignoreCase = true)
                } == true
                if (isCriticalMsg && !iptvStatusMessage.isNullOrBlank()) {
                    Text(
                        text = iptvStatusMessage ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = AccentOrange,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
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
                        imageVector = Icons.Outlined.SystemUpdate,
                        contentDescription = null,
                        tint = AccentCyan,
                        modifier = Modifier.size(30.dp)
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
                                is UpdateUiState.Downloading -> "Идёт скачивание файла обновления..."
                                is UpdateUiState.ReadyToInstall -> "Загрузка завершена! Нажмите кнопку «Установка»."
                                is UpdateUiState.Error -> state.message
                                else -> "Проверка новых версий на GitHub и установка в один клик"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = when (updateUiState) {
                                is UpdateUiState.Available -> AccentCyan
                                is UpdateUiState.Error -> AccentOrange
                                else -> TextSecondary
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                // Persistent button prevents Compose from dropping focus back to NavigationRail (Home)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val availState = updateUiState as? UpdateUiState.Available
                    if (availState != null) {
                        TvFocusCard(
                            onClick = {
                                changelogRelease = availState.release
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
                    }

                    key("primary_update_action_btn") {
                        TvFocusCard(
                            onClick = {
                                when (val st = updateUiState) {
                                    is UpdateUiState.Checking, is UpdateUiState.Downloading -> Unit
                                    is UpdateUiState.Available -> startDownload(st.release)
                                    is UpdateUiState.ReadyToInstall -> updateManager.installApk(st.apkFile)
                                    else -> startCheckUpdates()
                                }
                            },
                            modifier = Modifier
                                .width(if (availState != null) 150.dp else 175.dp)
                                .height(44.dp)
                                .focusRequester(updateActionFocusRequester)
                        ) {
                            when (updateUiState) {
                                is UpdateUiState.Checking -> {
                                    Row(
                                        modifier = Modifier.fillMaxSize(),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        CircularProgressIndicator(
                                            color = AccentCyan,
                                            strokeWidth = 2.dp,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Проверка...",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = AccentCyan
                                        )
                                    }
                                }

                                is UpdateUiState.Available -> {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text(
                                            text = "Загрузить",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = AccentOrange,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                is UpdateUiState.Downloading -> {
                                    val p = downloadProgress.coerceIn(0f, 1f)
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(DarkSurface),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxHeight()
                                                .fillMaxWidth(fraction = p)
                                                .background(Brush.horizontalGradient(listOf(AccentCyan, AccentGold, AccentOrange)))
                                        )
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "${(p * 100).toInt()}%",
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                                color = if (p > 0.5f) DarkBackground else TextPrimary
                                            )
                                        }
                                    }
                                }

                                is UpdateUiState.ReadyToInstall -> {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.CheckCircle,
                                                contentDescription = null,
                                                tint = AccentCyanBright,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Text(
                                                text = "Установка",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = AccentCyanBright,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }

                                else -> {
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
        }

        DiagnosticsSection()
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
    // URL Edit Dialog (Only opens when user explicitly clicks the URL card)
    if (editingUrlTarget != null) {
        val target = editingUrlTarget!!
        Dialog(onDismissRequest = { editingUrlTarget = null }) {
            val dialogFocusRequester = remember { FocusRequester() }
            val clipboardManager = LocalClipboardManager.current
            val clipboardText = remember { clipboardManager.getText()?.text }
            val canPaste = remember(clipboardText) {
                !clipboardText.isNullOrBlank() && (clipboardText.startsWith("http://", ignoreCase = true) || clipboardText.startsWith("https://", ignoreCase = true))
            }

            LaunchedEffect(Unit) {
                delay(120)
                try {
                    dialogFocusRequester.requestFocus()
                } catch (_: Exception) {}
            }

            Box(
                modifier = Modifier
                    .width(620.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(DarkCard)
                    .border(1.dp, AccentCyan.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                    .padding(24.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        text = if (target == UrlEditTarget.PLAYLIST)
                            "URL плейлиста (.m3u / .m3u8)"
                        else
                            "URL программы передач (.xml / .xml.gz)",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )

                    OutlinedTextField(
                        value = editingUrlValue,
                        onValueChange = { editingUrlValue = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(dialogFocusRequester),
                        singleLine = true,
                        placeholder = {
                            Text(
                                if (target == UrlEditTarget.PLAYLIST) "https://example.com/playlist.m3u" else "https://example.com/epg.xml.gz",
                                color = TextSecondary
                            )
                        },
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

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (canPaste) {
                            TvFocusCard(
                                onClick = {
                                    clipboardText?.let { editingUrlValue = it.trim() }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("Вставить из буфера", style = MaterialTheme.typography.bodyMedium, color = AccentCyan)
                                }
                            }
                        }

                        if (editingUrlValue.isNotBlank()) {
                            TvFocusCard(
                                onClick = { editingUrlValue = "" },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("Очистить", style = MaterialTheme.typography.bodyMedium, color = AccentOrange)
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        TvFocusCard(
                            onClick = {
                                val clean = editingUrlValue.trim()
                                if (target == UrlEditTarget.PLAYLIST) {
                                    iptvPlaylistInput = clean
                                    iptvManager.saveConfigAndLoad(clean, iptvEpgInput)
                                } else {
                                    iptvEpgInput = clean
                                    iptvManager.saveConfigAndLoad(iptvPlaylistInput, clean)
                                }
                                editingUrlTarget = null
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    "Сохранить",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = AccentCyan,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        TvFocusCard(
                            onClick = { editingUrlTarget = null },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Отмена", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
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
            "Стабильная загрузка телепрограммы: авто-маршрутизация CDN для epg.it999.ru и epg.one (исправлен сбой соединения).",
            "Отдельная кнопка «Обновить EPG» для быстрой загрузки программы передач в один клик.",
            "Плавный фокус пульта: кнопка обновления удерживает фокус при скачивании и сразу открывает установку.",
            "Настройки ТВ: ссылки открывают экранную клавиатуру только при нажатии ОК, без ложных открытий при навигации.",
            "Наглядный прогресс загрузки телепрограммы и плейлистов с процентами.",
            "Раздел ТВ: категории представлены как папки с сортировкой по времени просмотра и названию."
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
