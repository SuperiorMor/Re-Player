package com.replayer.ui.screens

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.replayer.data.ServerConfigManager
import com.replayer.model.DeviceCodeResponse
import com.replayer.model.UpnpDevice
import com.replayer.network.gdrive.GoogleDriveAuthManager
import com.replayer.network.upnp.SsdpDiscovery
import com.replayer.ui.components.TvFocusCard
import com.replayer.ui.theme.AccentCyan
import com.replayer.ui.theme.DarkCard
import com.replayer.ui.theme.DarkSurface
import com.replayer.ui.theme.TextPrimary
import com.replayer.ui.theme.TextSecondary
import com.replayer.updater.ReleaseInfo
import com.replayer.updater.UpdateCheckResult
import com.replayer.updater.UpdateManager
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
    val authManager = remember { GoogleDriveAuthManager(context) }
    val discovery = remember { SsdpDiscovery(context) }
    val updateManager = remember { UpdateManager(context) }
    val currentVersion = remember { getCurrentAppVersion(context) }

    val connectedUpnpDevice by serverConfigManager.connectedUpnpDevice.collectAsState()
    var isGdriveAuthenticated by remember { mutableStateOf(authManager.isAuthenticated()) }

    // UPnP scanning state
    var discoveredDevices by remember { mutableStateOf<List<UpnpDevice>>(emptyList()) }
    var isScanningUpnp by remember { mutableStateOf(false) }

    // Google Drive Client ID Dialog & Device Code
    var showClientIdDialog by remember { mutableStateOf(false) }
    var clientIdInput by remember { mutableStateOf(authManager.getSavedClientId()) }
    var deviceCodeInfo by remember { mutableStateOf<DeviceCodeResponse?>(null) }
    var isPollingDriveAuth by remember { mutableStateOf(false) }
    var driveErrorMessage by remember { mutableStateOf<String?>(null) }

    // App updates state
    var updateUiState by remember { mutableStateOf<UpdateUiState>(UpdateUiState.Idle) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }

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

    fun startDriveDeviceCodeFlow() {
        val clientId = authManager.getSavedClientId()
        if (clientId.isBlank()) {
            showClientIdDialog = true
            return
        }

        driveErrorMessage = null
        scope.launch {
            val result = authManager.requestDeviceCode(clientId)
            result.onSuccess { info ->
                deviceCodeInfo = info
                isPollingDriveAuth = true
                scope.launch {
                    val token = authManager.pollForToken(
                        clientId = clientId,
                        deviceCode = info.device_code,
                        intervalSec = info.interval.coerceAtLeast(5)
                    )
                    isPollingDriveAuth = false
                    if (token != null && token.access_token != null) {
                        isGdriveAuthenticated = true
                        deviceCodeInfo = null
                    } else {
                        driveErrorMessage = "Время ожидания авторизации истекло. Попробуйте снова."
                    }
                }
            }.onFailure { err ->
                driveErrorMessage = err.localizedMessage ?: "Ошибка запроса кода"
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
            text = "Подключение медиа-серверов и системные параметры",
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
            modifier = Modifier.padding(top = 4.dp, bottom = 24.dp)
        )

        // ---------------------------------------------------------
        // 1. DLNA / UPnP Серверы
        // ---------------------------------------------------------
        Text(
            text = "Серверы DLNA / UPnP",
            style = MaterialTheme.typography.titleLarge,
            color = TextPrimary,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        if (connectedUpnpDevice != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(DarkCard)
                    .padding(vertical = 6.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(AccentCyan.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Folder, contentDescription = null, tint = AccentCyan)
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = connectedUpnpDevice!!.friendlyName,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TextPrimary
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(AccentCyan.copy(alpha = 0.2f))
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "Подключено",
                                        color = AccentCyan,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Text(
                                text = connectedUpnpDevice!!.location,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }

                    TvFocusCard(
                        onClick = { serverConfigManager.disconnectUpnpDevice() },
                        modifier = Modifier
                            .width(140.dp)
                            .height(44.dp)
                    ) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(text = "Отключить", color = Color(0xFFFF5252), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        } else {
            // Discovered devices list
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (discoveredDevices.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(DarkCard)
                            .padding(20.dp)
                    ) {
                        Text(
                            text = if (isScanningUpnp) "Поиск серверов в локальной сети..." else "Серверы не найдены. Убедитесь, что DLNA включен на роутере/ПК.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                } else {
                    discoveredDevices.forEach { device ->
                        TvFocusCard(
                            onClick = { serverConfigManager.connectUpnpDevice(device) },
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

        Spacer(modifier = Modifier.height(28.dp))

        // ---------------------------------------------------------
        // 2. Google Drive Хранилище
        // ---------------------------------------------------------
        Text(
            text = "Google Drive",
            style = MaterialTheme.typography.titleLarge,
            color = TextPrimary,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(DarkCard)
                .padding(vertical = 4.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.Center
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
                                .background(Color(0xFF4285F4).copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Cloud, contentDescription = null, tint = Color(0xFF4285F4))
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(text = "Google Drive", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                                Spacer(modifier = Modifier.width(12.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(if (isGdriveAuthenticated) AccentCyan.copy(alpha = 0.2f) else DarkSurface)
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = if (isGdriveAuthenticated) "Подключено" else "Не подключено",
                                        color = if (isGdriveAuthenticated) AccentCyan else TextSecondary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            val savedClientId = authManager.getSavedClientId()
                            Text(
                                text = if (savedClientId.isNotBlank()) "Client ID: ${savedClientId.take(16)}..." else "OAuth Client ID: не задан",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TvFocusCard(
                            onClick = {
                                clientIdInput = authManager.getSavedClientId()
                                showClientIdDialog = true
                            },
                            modifier = Modifier
                                .width(150.dp)
                                .height(44.dp)
                        ) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(text = "Настроить ID", style = MaterialTheme.typography.bodyMedium)
                            }
                        }

                        if (isGdriveAuthenticated) {
                            TvFocusCard(
                                onClick = {
                                    authManager.clearAuth()
                                    isGdriveAuthenticated = false
                                },
                                modifier = Modifier
                                    .width(140.dp)
                                    .height(44.dp)
                            ) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(text = "Отключить", color = Color(0xFFFF5252), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        } else if (deviceCodeInfo == null) {
                            TvFocusCard(
                                onClick = { startDriveDeviceCodeFlow() },
                                modifier = Modifier
                                    .width(160.dp)
                                    .height(44.dp)
                            ) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(text = "Получить код", color = AccentCyan, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }

                if (deviceCodeInfo != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(DarkSurface)
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Откройте на телефоне/ПК: google.com/device",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimary
                            )
                            Text(
                                text = "Код подтверждения: ${deviceCodeInfo!!.user_code}",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = AccentCyan
                            )
                        }
                        if (isPollingDriveAuth) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = AccentCyan, strokeWidth = 2.dp)
                        }
                    }
                }
            }
        }

        if (driveErrorMessage != null) {
            Text(
                text = driveErrorMessage!!,
                color = Color(0xFFFF5252),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp)
            )
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

        TvFocusCard(
            onClick = {
                if (updateUiState !is UpdateUiState.Checking && updateUiState !is UpdateUiState.Downloading) {
                    startCheckUpdates()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(90.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.SystemUpdate,
                        contentDescription = null,
                        tint = AccentCyan,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = "Re:Player", style = MaterialTheme.typography.titleMedium)
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
                        Text(
                            text = when (updateUiState) {
                                is UpdateUiState.Checking -> "Проверка обновлений на GitHub..."
                                is UpdateUiState.UpToDate -> "У вас установлена актуальная версия"
                                is UpdateUiState.NoReleases -> "Релизы на GitHub не найдены"
                                is UpdateUiState.Available -> "Доступна новая версия: ${(updateUiState as UpdateUiState.Available).release.tagName}"
                                is UpdateUiState.Downloading -> "Загрузка обновления..."
                                is UpdateUiState.ReadyToInstall -> "Загрузка завершена! Нажмите для установки."
                                is UpdateUiState.Error -> (updateUiState as UpdateUiState.Error).message
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

                when (val state = updateUiState) {
                    is UpdateUiState.Checking -> {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = AccentCyan, strokeWidth = 2.dp)
                    }
                    is UpdateUiState.Available -> {
                        TvFocusCard(
                            onClick = { startDownload(state.release) },
                            modifier = Modifier
                                .width(150.dp)
                                .height(44.dp)
                        ) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(text = "Скачать", color = AccentCyan, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    is UpdateUiState.ReadyToInstall -> {
                        TvFocusCard(
                            onClick = { updateManager.installApk(state.apkFile) },
                            modifier = Modifier
                                .width(160.dp)
                                .height(44.dp)
                        ) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(text = "Установить", color = AccentCyan, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    else -> {
                        Text(text = "Проверить", color = AccentCyan, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------
    // Dialog: Setup Google OAuth 2.0 Client ID
    // ---------------------------------------------------------
    if (showClientIdDialog) {
        Dialog(onDismissRequest = { showClientIdDialog = false }) {
            Box(
                modifier = Modifier
                    .width(520.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(DarkCard)
                    .border(1.dp, AccentCyan.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                    .padding(24.dp)
            ) {
                Column {
                    Text(
                        text = "Настройка Google Drive",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Для безопасного подключения Google Drive на ТВ требуется бесплатный OAuth Client ID:\n" +
                                "1. Откройте console.cloud.google.com на телефоне или ПК\n" +
                                "2. Создайте проект и включите 'Google Drive API'\n" +
                                "3. В 'Учетные данные' создайте 'OAuth Client ID' (тип: 'Телевизоры и устройства с ограниченным вводом')\n" +
                                "4. Вставьте Client ID ниже:",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        lineHeight = 18.sp
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = clientIdInput,
                        onValueChange = { clientIdInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = {
                            Text("123456789-abc.apps.googleusercontent.com", color = TextSecondary.copy(alpha = 0.5f), fontSize = 12.sp)
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentCyan,
                            unfocusedBorderColor = DarkSurface,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TvFocusCard(
                            onClick = { showClientIdDialog = false },
                            modifier = Modifier
                                .width(120.dp)
                                .height(44.dp)
                        ) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Отмена", color = TextSecondary)
                            }
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        TvFocusCard(
                            onClick = {
                                authManager.saveClientId(clientIdInput)
                                showClientIdDialog = false
                                startDriveDeviceCodeFlow()
                            },
                            modifier = Modifier
                                .width(140.dp)
                                .height(44.dp)
                        ) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Сохранить", color = AccentCyan, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun getCurrentAppVersion(context: Context): String {
    return try {
        val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        pInfo.versionName ?: "0.1.3"
    } catch (e: Exception) {
        "0.1.3"
    }
}
