package com.replayer.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
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
import com.replayer.R
import com.replayer.data.IptvManager
import com.replayer.data.ServerConfigManager
import com.replayer.model.UpnpDevice
import com.replayer.network.upnp.SsdpDiscovery
import com.replayer.ui.components.DiagnosticsSection
import com.replayer.ui.components.TvFocusCard
import com.replayer.ui.components.settings.*
import com.replayer.ui.theme.*
import com.replayer.updater.ReleaseInfo
import com.replayer.updater.UpdateChannel
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

@OptIn(ExperimentalFoundationApi::class)
private val EdgeOnlyBringIntoViewSpec = object : BringIntoViewSpec {
    override fun calculateScrollDistance(
        offset: Float,
        size: Float,
        containerSize: Float
    ): Float {
        val margin = (containerSize * 0.04f).coerceIn(12f, 36f)
        val leadingEdge = offset
        val trailingEdge = offset + size
        return when {
            leadingEdge >= margin && trailingEdge <= (containerSize - margin) -> 0f
            leadingEdge < margin && trailingEdge > (containerSize - margin) -> leadingEdge - margin
            leadingEdge < margin -> leadingEdge - margin
            else -> trailingEdge - (containerSize - margin)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
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
    var updateChannel by remember { mutableStateOf(updateManager.getUpdateChannel()) }
    var channelSwitchNotice by remember { mutableStateOf<String?>(null) }
    var rightTapCount by remember { mutableIntStateOf(0) }
    var lastRightTapMs by remember { mutableLongStateOf(0L) }
    var updateUiState by remember { mutableStateOf<UpdateUiState>(UpdateUiState.Idle) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var showChangelogDialog by remember { mutableStateOf(false) }
    var changelogRelease by remember { mutableStateOf<ReleaseInfo?>(null) }
    val updateActionFocusRequester = remember { FocusRequester() }
    var showSupportQrDialog by remember { mutableStateOf(false) }
    val feedbackQrFocusRequester = remember { FocusRequester() }

    LaunchedEffect(channelSwitchNotice) {
        if (channelSwitchNotice != null) {
            delay(5000L)
            channelSwitchNotice = null
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

    fun startCheckUpdates(channel: UpdateChannel = updateChannel) {
        updateUiState = UpdateUiState.Checking
        scope.launch {
            val result = updateManager.checkForUpdates(currentVersion, channel)
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
            .padding(start = 32.dp, end = 32.dp, top = 38.dp, bottom = 16.dp)
    ) {
        // Pinned header stays aligned with Logo regardless of content scroll
        Text(
            text = "Настройки",
            style = MaterialTheme.typography.displayLarge
        )
        Text(
            text = "Управление сетевыми серверами DLNA / UPnP и обновлениями",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            modifier = Modifier.padding(bottom = 18.dp)
        )

        CompositionLocalProvider(LocalBringIntoViewSpec provides EdgeOnlyBringIntoViewSpec) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 16.dp)
            ) {

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
                            .focusProperties { left = navMenuFocusRequester }
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
                            .focusProperties { left = navMenuFocusRequester }
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
                            if (updateChannel == UpdateChannel.BETA || currentVersion.contains("beta", ignoreCase = true)) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(AccentOrange.copy(alpha = 0.22f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "BETA",
                                        color = AccentOrange,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                        val isRunningBetaBuild = currentVersion.contains("beta", ignoreCase = true)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = when (val state = updateUiState) {
                                is UpdateUiState.Checking -> "Проверка обновлений на GitHub..."
                                is UpdateUiState.UpToDate -> {
                                    if (updateChannel == UpdateChannel.STABLE && isRunningBetaBuild) {
                                        "Канал Stable выбран. Установлена более новая Beta v$currentVersion (откат требует переустановки)."
                                    } else {
                                        "У вас установлена актуальная версия"
                                    }
                                }
                                is UpdateUiState.NoReleases -> "Релизы на GitHub не найдены"
                                is UpdateUiState.Available -> "Доступна новая версия: ${state.release.tagName}"
                                is UpdateUiState.Downloading -> "Идёт скачивание файла обновления..."
                                is UpdateUiState.ReadyToInstall -> "Загрузка завершена! Нажмите кнопку «Установка»."
                                is UpdateUiState.Error -> state.message
                                else -> "Проверка новых версий на GitHub и установка в один клик"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = when {
                                updateUiState is UpdateUiState.Available -> AccentCyan
                                updateUiState is UpdateUiState.Error -> AccentOrange
                                updateUiState is UpdateUiState.UpToDate && updateChannel == UpdateChannel.STABLE && isRunningBetaBuild -> AccentOrange
                                else -> TextSecondary
                            }
                        )
                        if (channelSwitchNotice != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = channelSwitchNotice!!,
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                color = if (updateChannel == UpdateChannel.BETA) AccentOrange else AccentCyan
                            )
                        }
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
                                .focusProperties {
                                    right = FocusRequester.Cancel
                                    if (availState == null) {
                                        left = navMenuFocusRequester
                                    }
                                }
                                .onFocusChanged { focusState ->
                                    if (!focusState.isFocused) {
                                        rightTapCount = 0
                                    }
                                }
                                .onPreviewKeyEvent { keyEvent ->
                                    if (keyEvent.type == KeyEventType.KeyDown &&
                                        keyEvent.key == Key.DirectionRight &&
                                        keyEvent.nativeKeyEvent.repeatCount == 0
                                    ) {
                                        val now = android.os.SystemClock.uptimeMillis()
                                        val nextCount = if (now - lastRightTapMs <= 3500L) rightTapCount + 1 else 1
                                        lastRightTapMs = now
                                        if (nextCount >= 5) {
                                            rightTapCount = 0
                                            val nextChannel = if (updateChannel == UpdateChannel.STABLE) {
                                                UpdateChannel.BETA
                                            } else {
                                                UpdateChannel.STABLE
                                            }
                                            updateChannel = nextChannel
                                            updateManager.setUpdateChannel(nextChannel)
                                            channelSwitchNotice = if (nextChannel == UpdateChannel.BETA) {
                                                "Канал обновлений изменён на BETA"
                                            } else {
                                                "Канал обновлений изменён на STABLE"
                                            }
                                            startCheckUpdates(nextChannel)
                                        } else {
                                            rightTapCount = nextCount
                                        }
                                        return@onPreviewKeyEvent true
                                    }
                                    false
                                }
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

        DiagnosticsSection(leftFocusRequester = navMenuFocusRequester)
            }
        }
    }

    // ---------------------------------------------------------
    // Feedback / Donate Large QR Dialog
    // ---------------------------------------------------------
    if (showSupportQrDialog) {
        SettingsSupportQrDialog(
            onDismiss = { showSupportQrDialog = false }
        )
    }

    // ---------------------------------------------------------
    // Changelog Dialog
    // ---------------------------------------------------------
    if (showChangelogDialog && changelogRelease != null) {
        val release = changelogRelease!!
        SettingsChangelogDialog(
            release = release,
            onDownload = {
                showChangelogDialog = false
                startDownload(release)
            },
            onDismiss = { showChangelogDialog = false }
        )
    }

    // ---------------------------------------------------------
    // Local File Browser Dialog
    // ---------------------------------------------------------
    if (showLocalFileDialog) {
        SettingsLocalPlaylistDialog(
            files = localFiles,
            onSelectFile = { file ->
                showLocalFileDialog = false
                scope.launch { iptvManager.loadFromLocalFile(file) }
            },
            onDismiss = { showLocalFileDialog = false }
        )
    }

    // ---------------------------------------------------------
    // URL Edit Dialog (Only opens when user explicitly clicks the URL card)
    // ---------------------------------------------------------
    val activeUrlTarget = editingUrlTarget
    if (activeUrlTarget != null) {
        SettingsUrlEditDialog(
            target = activeUrlTarget,
            value = editingUrlValue,
            onValueChange = { editingUrlValue = it },
            onSave = { cleanUrl ->
                if (activeUrlTarget == UrlEditTarget.PLAYLIST) {
                    iptvPlaylistInput = cleanUrl
                    iptvManager.saveConfigAndLoad(cleanUrl, iptvEpgInput)
                } else {
                    iptvEpgInput = cleanUrl
                    iptvManager.saveConfigAndLoad(iptvPlaylistInput, cleanUrl)
                }
                editingUrlTarget = null
            },
            onDismiss = { editingUrlTarget = null }
        )
    }
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
