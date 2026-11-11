package com.replayer.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.replayer.R
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
    firstItemFocusRequester: FocusRequester,
    navMenuFocusRequester: FocusRequester,
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
    var showSupportQrDialog by remember { mutableStateOf(false) }
    val feedbackQrFocusRequester = remember { FocusRequester() }

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
            .padding(start = 32.dp, end = 32.dp, top = 38.dp, bottom = 24.dp)
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
                                .focusRequester(firstItemFocusRequester)
                                .focusProperties { left = navMenuFocusRequester }
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
                            backgroundColor = AccentCyan,
                            focusedBackgroundColor = AccentCyanBright,
                            modifier = Modifier
                                .width(130.dp)
                                .height(44.dp)
                        ) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = "Отключить",
                                    color = DarkBackground,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                                )
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
                                    .focusProperties { left = navMenuFocusRequester }
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
                            .then(if (connectedUpnpDevice == null) Modifier.focusRequester(firstItemFocusRequester) else Modifier)
                            .focusProperties { left = navMenuFocusRequester }
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
                    val isEpgError = iptvStatusMessage?.let {
                        it.contains("Ошибка", ignoreCase = true) || it.contains("Сбой", ignoreCase = true)
                    } == true
                    val isEpgFresh = epgMap.isNotEmpty() &&
                        lastEpgUpdateTime > 0L &&
                        (System.currentTimeMillis() - lastEpgUpdateTime) < 24 * 3600 * 1000L &&
                        !isEpgError

                    TvFocusCard(
                        onClick = {
                            if (iptvEpgInput.isNotBlank()) {
                                iptvManager.loadEpgOnly(iptvEpgInput)
                            } else if (iptvPlaylistInput.isNotBlank()) {
                                scope.launch { iptvManager.refreshPlaylist() }
                            }
                        },
                        backgroundColor = if (isEpgFresh) AccentOrange.copy(alpha = 0.14f) else CardBackground,
                        focusedBackgroundColor = if (isEpgFresh) AccentOrange.copy(alpha = 0.25f) else CardFocusedBackground,
                        focusBorderColor = if (isEpgFresh) AccentOrange else FocusBorder,
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
                            val statusColor = when {
                                isEpgFresh -> AccentOrange
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
                                if (isEpgFresh) {
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(CircleShape)
                                            .background(
                                                Brush.linearGradient(
                                                    colors = listOf(AccentGold, AccentOrange)
                                                )
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = null,
                                            tint = DarkBackground,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                } else {
                                    Icon(
                                        imageVector = if (isEpgError) Icons.Outlined.WarningAmber else Icons.Outlined.Refresh,
                                        contentDescription = null,
                                        tint = statusColor,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
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
                                        color = if (isEpgFresh) AccentGold.copy(alpha = 0.9f) else TextSecondary,
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

        Spacer(modifier = Modifier.height(28.dp))

        // ---------------------------------------------------------
        // 4. Обратная связь и поддержка
        // ---------------------------------------------------------
        Text(
            text = "Обратная связь",
            style = MaterialTheme.typography.titleLarge,
            color = TextPrimary,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(DarkCard)
                .border(
                    width = 1.dp,
                    brush = Brush.horizontalGradient(
                        colors = listOf(AccentCyan.copy(alpha = 0.28f), AccentOrange.copy(alpha = 0.28f))
                    ),
                    shape = RoundedCornerShape(14.dp)
                )
                .padding(horizontal = 20.dp, vertical = 18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Email,
                            contentDescription = null,
                            tint = AccentCyan,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Связаться с разработчиком и поддержать проект",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                    }

                    Text(
                        text = buildAnnotatedString {
                            append("Если у тебя возникли трудности с использованием приложения, отправь лог мне в ТГ или напиши на ")
                            withStyle(SpanStyle(color = AccentCyanBright, fontWeight = FontWeight.SemiBold)) {
                                append("morganism@yandex.ru")
                            }
                            append(" с темой ")
                            withStyle(SpanStyle(color = AccentOrange, fontWeight = FontWeight.SemiBold)) {
                                append("\"Re:Player\"")
                            }
                            append(". Я постараюсь решить эту проблему.")
                        },
                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
                        color = TextSecondary
                    )

                    Text(
                        text = "Если захочешь поблагодарить словом или копеечкой, также пиши на почту или воспользуйся этим QR.",
                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
                        color = TextSecondary
                    )

                    Text(
                        text = "Приятного пользования!",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = AccentCyanBright
                    )
                }

                TvFocusCard(
                    onClick = { showSupportQrDialog = true },
                    focusBorderColor = AccentOrange,
                    cornerRadius = 16.dp,
                    modifier = Modifier
                        .width(168.dp)
                        .focusRequester(feedbackQrFocusRequester)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(136.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.White)
                                .border(
                                    width = 2.dp,
                                    brush = Brush.linearGradient(
                                        colors = listOf(AccentCyan, AccentGold, AccentOrange)
                                    ),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .padding(2.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.qr_support_donate),
                                contentDescription = "QR-код обратной связи и поддержки",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Увеличить QR",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = AccentOrange,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        DiagnosticsSection()
    }

    // ---------------------------------------------------------
    // Feedback / Donate Large QR Dialog
    // ---------------------------------------------------------
    if (showSupportQrDialog) {
        val qrCloseFocusRequester = remember { FocusRequester() }

        Dialog(onDismissRequest = { showSupportQrDialog = false }) {
            Box(
                modifier = Modifier
                    .width(460.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .border(
                        width = 1.dp,
                        brush = Brush.horizontalGradient(
                            colors = listOf(AccentCyan.copy(alpha = 0.6f), AccentOrange.copy(alpha = 0.6f))
                        ),
                        shape = RoundedCornerShape(22.dp)
                    )
            ) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Color(0xCC151B26))
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    AccentCyan.copy(alpha = 0.12f),
                                    Color.Transparent,
                                    AccentOrange.copy(alpha = 0.08f)
                                )
                            )
                        )
                        .then(
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                Modifier.blur(20.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                            } else Modifier
                        )
                )
                Column(
                    modifier = Modifier.padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Обратная связь • Re:Player",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Наведите камеру телефона на QR-код или напишите на morganism@yandex.ru с темой \"Re:Player\"",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Box(
                        modifier = Modifier
                            .size(270.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color.White)
                            .border(
                                width = 2.5.dp,
                                brush = Brush.linearGradient(
                                    colors = listOf(AccentCyan, AccentGold, AccentOrange)
                                ),
                                shape = RoundedCornerShape(18.dp)
                            )
                            .padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.qr_support_donate),
                            contentDescription = "QR-код поддержки Re:Player",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
                    Spacer(modifier = Modifier.height(22.dp))
                    TvFocusCard(
                        onClick = { showSupportQrDialog = false },
                        modifier = Modifier
                            .width(180.dp)
                            .height(44.dp)
                            .focusRequester(qrCloseFocusRequester)
                    ) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "Закрыть",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = AccentCyanBright
                            )
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
                Column(modifier = Modifier.padding(28.dp)) {
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

            Box(
                modifier = Modifier
                    .width(620.dp)
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
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
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
    if (version.contains("1.0.9")) {
        return listOf(
            "Звёздочки избранного (DLNA, IPTV, Главная): переведены в фирменный оранжевый цвет без рамок и фоновых подложек.",
            "DLNA избранное: исправлена кнопка звёздочки (разделена с открытием папки) и добавлено удержание OK на папке для добавления в избранное без её открытия.",
            "IPTV архив: вместо текстовых значков «3д/7д» отображается аккуратный оранжевый значок VHS-кассеты.",
            "Телепрограмма в плеере: убрано дёрганье и провал текущей передачи вниз при открытии — она сразу зафиксирована вверху списка.",
            "Навигация: убрана кратковременная вспышка иконки «Домик» и прыжок курсора через первый элемент при смене папок DLNA и категорий ТВ."
        )
    }

    if (version.contains("1.0.8")) {
        return listOf(
            "Свободная навигация с левым меню: нажатие Вправо с любого пункта меню переносит курсор в текущий открытый раздел.",
            "Сохранность состояния меню: открытие папок DLNA, категорий ТВ, каналов и возврат из корня раздела назад в меню больше не сбрасывают выбранный раздел на «Главная».",
            "Память позиции в глубоких папках DLNA: при переходе по вложенным папкам и при возврате назад курсор автоматически встаёт на последний открытый или недосмотренный элемент.",
            "IPTV: плавное восстановление курсора между сеткой категорий и списком каналов с запоминанием последнего канала в категории."
        )
    }

    if (version.contains("1.0.7")) {
        return listOf(
            "Полная переработка D-pad навигации: удалены все LaunchedEffect/delay костыли фокуса, переход между левым меню и экранами работает строго через focusProperties.",
            "Левое меню: разделы переключаются только по нажатию OK, проход Вверх/Вниз подсвечивает кнопки с 4 состояниями без переключения экрана, Вправо входит в контент.",
            "DLNA и IPTV: добавлен встроенный Modifier.focusRestorer() и ключи item.id для автоматической памяти позиции курсора в списках.",
            "Главная: при нажатии Вправо на избранном канале оранжевый минус открывается мгновенно без фокусных задержек."
        )
    }

    if (version.contains("1.0.6")) {
        return listOf(
            "Навигация: исправлена потеря/перескок фокуса — при нажатии OK на раздел в левом меню курсор надёжно остаётся на кнопке с чётким откликом нажатия.",
            "Дизайн: заголовки всех четырёх разделов точно выровнены по видимой высоте логотипа слева.",
            "ТВ-плеер: ускорено и стабилизировано переключение каналов кнопками Влево / Вправо с мгновенным баннером канала и индикацией буферизации.",
            "Программа передач: текущая передача с описанием гарантированно открывается в самом верху списка.",
            "Главная: убран столбец звёзд и проблемный диалог удержания — при наведении на канал и нажатии Вправо справа появляется оранжевый минус для удаления из избранного."
        )
    }

    if (version.contains("1.0.5")) {
        return listOf(
            "Меню навигации: нажатие OK переключает раздел и оставляет курсор на кнопке меню без скачков и мерцания, переход в контент — по кнопке Вправо; заголовки выровнены по логотипу.",
            "ТВ-плеер: кнопки Влево / Вправо на пульте переключают предыдущий / следующий канал без выхода в меню.",
            "Программа передач в плеере: убрана лишняя кнопка «Закрыть», а текущая передача открывается сразу наверху списка.",
            "Главная: прямо в блоке «Телепрограмма избранных каналов» можно убрать канал из избранного (звёздочкой справа или удержанием OK).",
            "Плеер: убрана съехавшая вниз тень на кнопке Play/Pause."
        )
    }

    if (version.contains("1.0.4")) {
        return listOf(
            "Исправлена навигация в ТВ: переход вниз от кнопок «Обновить»/«Настройки ТВ» в папки, фокус сразу на первом канале при открытии категории, кнопка Back возвращает в папки с одного нажатия.",
            "Убрана лишняя кнопка «Папки» в ТВ, а в DLNA выход по кнопке Back теперь срабатывает мгновенно с первого нажатия.",
            "Главная: постеры «Продолжить просмотр» получили формат кинопостеров 2:3, а ободок фокуса «Избранные папки» больше не обрезается.",
            "Настройки: кнопка «Отключить» в фирменном синем стиле, кнопка «Программа обновлена» в оранжевых цветах с обновлённой галочкой, отцентрирован QR-код обратной связи и упрощён блок сбоев."
        )
    }

    if (version.contains("1.0.3")) {
        return listOf(
            "Срочный фикс меню: исправлено застревание курсора на «Домике» — теперь по меню можно свободно ходить Вверх/Вниз к Настройкам и другим разделам.",
            "Добавлен точный возврат на активную иконку при выходе Влево из контента.",
            "Оптимизированы тайминги восстановления фокуса после смены экранов и возврата из диалогов."
        )
    }

    if (version.contains("1.0.2")) {
        return listOf(
            "Полностью переработана логика фокуса D-pad: курсор сразу виден при старте и сохраняется при переходе в меню, диалоги, плеер и обратно в окно.",
            "Добавлен новый раздел в настройках «Обратная связь» с контактами разработчика и читаемым QR-кодом поддержки в стиле Re:Player."
        )
    }

    if (version.contains("1.0.1")) {
        return listOf(
            "Исправлен фокус D-pad через focusRestorer: курсор больше не улетает на Домик при сортировке и возврате из меню.",
            "Левая панель запоминает активный раздел при выходе Влево и не отпускает курсор мимо.",
            "Обновлённый анимированный прелоудер при старте приложения.",
            "Полупрозрачные стеклянные диалоги с размытием (Android 12+), радиальное свечение фона и тень кнопки Play/Pause."
        )
    }

    if (version.contains("1.0")) {
        return listOf(
            "Стабильный релиз Re:Player v1.0.0: устранены сбои и улёты фокуса D-pad при сортировке и переключении разделов.",
            "Надёжная ловушка фокуса в панели управления плеером — курсор пульта не уходит за края кнопок.",
            "Новый анимированный прелоадер при запуске и радиальный градиент главного фона.",
            "Обновлённые полупрозрачные диалоги с эффектом размытия, свечение кнопки Play/Pause и плавное появление постеров DLNA.",
            "Полная очистка от устаревших зависимостей и ускорение сборки."
        )
    }

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
